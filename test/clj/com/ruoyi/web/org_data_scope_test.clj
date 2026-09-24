(ns com.ruoyi.web.org-data-scope-test
  "S2 组织与数据权限: 5 种数据范围 (含自定义部门与多角色并集) 作用于用户与部门的列表, 详情和写操作;
  部门祖级维护, 移动, 删除/停用校验, 同级重名与部门负责人. 在全新 SQLite 库上经真实路由与中间件验证."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.system.data-scope :as data-scope]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.routes.api :as api]
    [com.ruoyi.web.routes.auth :as auth-routes]
    [com.ruoyi.web.routes.system :as system-routes]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set]
    [reitit.ring :as ring]
    [ring.mock.request :as mock])
  (:import
    (java.nio.file
      Files)))


(def ^:dynamic *db* nil)
(def ^:dynamic *q* nil)
(def ^:dynamic *handler* nil)

(def password "Pass-12345")

;; 种子部门: 1 红创科技 > 2 深圳总公司 > (4 研发部门, 5 市场部门); 1 > 3 长沙分公司 > 6 财务部门

(def user-perms
  ["system:user:list" "system:user:query" "system:user:add" "system:user:edit" "system:user:remove"
   "system:dept:list" "system:dept:query" "system:dept:add" "system:dept:edit" "system:dept:remove"])


(defn- query-function
  [db]
  (let [queries (:fns (conman/bind-connection-map db {} "queries.sql" "sql/system.sql" "sql/log.sql" "sql/job.sql"))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([conn name params] ((get-in queries [name :fn]) conn params)))))


(defn- seed!
  "9300 本部门 (3), 9301 本部门及以下 (4), 9302 自定义 (2, 由接口设置部门), 9303 仅本人 (5); 均授予用户/部门管理权限."
  [db]
  (let [hash (security/hash-password password)]
    (doseq [[id key scope] [[9300 "dept-mgr" "3"] [9301 "branch-mgr" "4"] [9302 "custom-mgr" "1"] [9303 "self-only" "5"]]]
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag) VALUES (?,?,?,5,?,'0','0')"
                         id (str "范围" key) key scope])
      (doseq [p user-perms]
        (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT ?,menu_id FROM sys_menu WHERE perms = ?" id p])))
    (doseq [[uid dept rid] [[9310 4 9300] [9311 3 9301] [9312 4 9302] [9313 5 9303] [9314 5 nil] [9315 6 nil] [9316 4 nil]]]
      (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,password,status,del_flag) VALUES (?,?,?,?,?,'0','0')"
                         uid dept (str "scope" uid) (str "范围用户" uid) hash])
      (when rid
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" uid rid])))
    (jdbc/execute! db ["UPDATE sys_user SET password = ? WHERE user_id = 1" hash])))


(defn- fixture
  [f]
  (let [file (Files/createTempFile "hc-scope-" ".db" (make-array java.nio.file.attribute.FileAttribute 0))
        db (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" file)})]
    (try
      (migratus/migrate {:store :database :db {:datasource db} :migration-dir "migrations-sqlite"})
      (seed! db)
      (let [q (query-function db)
            svc {:query-fn q :db db}
            opts {:query-fn q :datasource db :user-service svc :role-service svc :menu-service svc :dept-service svc
                  :post-service svc :dict-service svc :config-service svc :log-service svc
                  :online-service {:list-online (fn [_] {:rows [] :total 0}) :force-logout online/force-logout!}}
            handler (ring/ring-handler
                      (ring/router [["/api" api/route-data
                                     (auth-routes/auth-routes opts)
                                     (system-routes/system-routes opts)]]
                                   {:reitit.router/sequential true :conflicts nil}))]
        (with-redefs [online/cleanup-executor (delay nil)]
          (online/set-query-fn! q)
          (binding [*db* db *q* q *handler* handler]
            (f))))
      (finally
        (online/set-query-fn! nil)
        (Files/deleteIfExists file)))))


(use-fixtures :once fixture)


(defn- request
  [method path token payload]
  (let [req (cond-> (-> (mock/request method path)
                        (mock/content-type "application/json")
                        (mock/header "accept" "application/json"))
              token (mock/header "authorization" (str "Bearer " token))
              payload (mock/body (json/generate-string payload)))
        response (*handler* req)
        raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw (bytes? raw) (String. ^bytes raw "UTF-8")
                   (nil? raw) "{}" :else (slurp raw))]
    {:status (:status response)
     :body (try (json/parse-string text true) (catch Exception _ {:raw text}))}))


(defn- login!
  [user-name]
  (get-in (request :post "/api/auth/login" nil {:username user-name :password password}) [:body :data :token]))


(defn- code
  [resp]
  (or (get-in resp [:body :code]) (:status resp)))


(defn- user-ids
  [token]
  (set (map :user_id (get-in (request :get "/api/system/user?page=1&size=200" token nil) [:body :data :rows]))))


(defn- dept-ids
  [token]
  (set (map :dept_id (get-in (request :get "/api/system/dept" token nil) [:body :data]))))


(defn- dept-row
  [id]
  (first (jdbc/execute! *db* ["SELECT dept_id, parent_id, ancestors, leader, leader_id, status, del_flag FROM sys_dept WHERE dept_id = ?" id]
                        {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))


(deftest scope-pure-functions
  (let [depts [{:dept_id 1 :parent_id 0} {:dept_id 2 :parent_id 1} {:dept_id 3 :parent_id 1}
               {:dept_id 4 :parent_id 2} {:dept_id 5 :parent_id 2} {:dept_id 6 :parent_id 3}]
        q (fn [name params]
            (case name
              :list-all-depts depts
              :list-role-depts-for-roles (when (= [7] (:role_ids params)) [{:dept_id 5} {:dept_id 6}])))]
    (testing "下级按 parent_id 递归, 不依赖祖级字段"
      (is (= #{2 4 5} (data-scope/descendant-ids depts 2)))
      (is (= #{6} (data-scope/descendant-ids depts 6))))
    (testing "五种范围与并集"
      (is (:all? (data-scope/scope-of q {:admin? true :roles []})))
      (is (:all? (data-scope/scope-of q {:roles [{:role_id 3 :data_scope "3"} {:role_id 9 :data_scope "1"}] :dept_id 4})))
      (is (= #{4} (:dept-ids (data-scope/scope-of q {:roles [{:role_id 3 :data_scope "3"}] :dept_id 4}))))
      (is (= #{2 4 5} (:dept-ids (data-scope/scope-of q {:roles [{:role_id 3 :data_scope "4"}] :dept_id 2}))))
      (is (= #{5 6} (:dept-ids (data-scope/scope-of q {:roles [{:role_id 7 :data_scope "2"}] :dept_id 4}))))
      (is (= #{4 5 6} (:dept-ids (data-scope/scope-of q {:roles [{:role_id 7 :data_scope "2"} {:role_id 3 :data_scope "3"}] :dept_id 4}))))
      (let [self (data-scope/scope-of q {:roles [{:role_id 8 :data_scope "5"}] :dept_id 4 :user_id 42})]
        (is (= #{} (:dept-ids self)))
        (is (= 42 (:user-id self)))
        (is (data-scope/user-visible? self {:user_id 42 :dept_id 9}))
        (is (not (data-scope/user-visible? self {:user_id 43 :dept_id 4}))))
      (is (= {:all? false :dept-ids #{} :user-id 5} (data-scope/scope-of q {:roles [] :dept_id 4 :user_id 5})) "无角色只看本人"))
    (testing "SQL 参数: 空部门集合用 -1 占位"
      (is (= {:scope_all 1 :scope_dept_ids [-1] :scope_user_id -1} (data-scope/sql-params {:all? true})))
      (is (= {:scope_all 0 :scope_dept_ids [-1] :scope_user_id 7} (data-scope/sql-params {:all? false :dept-ids #{} :user-id 7}))))))


(deftest dept-scope-limits-users-and-depts
  (let [mgr (login! "scope9310")]
    (testing "本部门范围: 用户列表只含本部门, 部门列表只含本部门"
      (is (= (set (map :user_id (jdbc/execute! *db* ["SELECT user_id FROM sys_user WHERE dept_id = 4 AND del_flag = '0'"]
                                               {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))
             (user-ids mgr)))
      (is (every? #{1 9310 9312 9316} (user-ids mgr)))
      (is (= #{4} (dept-ids mgr)))
      (is (= #{4} (set (map :dept_id (get-in (request :get "/api/system/user/deptTree" mgr nil) [:body :data]))))))
    (testing "范围外用户: 详情, 修改, 停用, 重置密码, 删除, 分配角色均 403"
      (is (= 403 (code (request :get "/api/system/user/9314" mgr nil))))
      (is (= 403 (code (request :put "/api/system/user/9314" mgr {:nick_name "越权修改"}))))
      (is (= 403 (code (request :put "/api/system/user/9314/status/1" mgr nil))))
      (is (= 403 (code (request :delete "/api/system/user/9314" mgr nil))))
      (is (= 403 (code (request :get "/api/system/user/9314/authRole" mgr nil))))
      (is (= "范围用户9314" (:nick_name (first (jdbc/execute! *db* ["SELECT nick_name FROM sys_user WHERE user_id = 9314"]
                                                                {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))))))
    (testing "范围内用户可维护; 不能把用户调到范围外部门, 也不能在范围外部门新建用户"
      (is (= 200 (code (request :get "/api/system/user/9316" mgr nil))))
      (is (= 200 (code (request :put "/api/system/user/9316" mgr {:nick_name "研发同事"}))))
      (is (= 403 (code (request :put "/api/system/user/9316" mgr {:dept_id 5}))))
      (is (= 403 (code (request :post "/api/system/user" mgr {:user_name "scopeNew5" :nick_name "外部门" :password password :dept_id 5}))))
      (is (= 200 (code (request :post "/api/system/user" mgr {:user_name "scopeNew4" :nick_name "本部门新人" :password password :dept_id 4})))))
    (testing "范围外部门: 详情, 修改, 删除, 在其下新建均 403; 不能新建顶级部门"
      (is (= 403 (code (request :get "/api/system/dept/5" mgr nil))))
      (is (= 403 (code (request :put "/api/system/dept/5" mgr {:dept_name "越权"}))))
      (is (= 403 (code (request :delete "/api/system/dept/5" mgr nil))))
      (is (= 403 (code (request :post "/api/system/dept" mgr {:parent_id 5 :dept_name "越权子部门"}))))
      (is (= 403 (code (request :post "/api/system/dept" mgr {:parent_id 0 :dept_name "越权顶级"})))))))


(deftest branch-self-and-custom-scopes
  (let [admin (login! "admin")]
    (testing "本部门及以下: 长沙分公司经理看到分公司与财务部门的用户"
      (is (= #{9311 9315} (user-ids (login! "scope9311"))))
      (is (= #{3 6} (dept-ids (login! "scope9311")))))
    (testing "仅本人: 只看到自己, 部门列表为空"
      (let [self (login! "scope9313")]
        (is (= #{9313} (user-ids self)))
        (is (= #{} (dept-ids self)))
        (is (= 200 (code (request :get "/api/system/user/9313" self nil))))
        (is (= 403 (code (request :get "/api/system/user/9314" self nil))))))
    (testing "自定义范围经接口保存, 部门树回显已选部门"
      (is (= 400 (code (request :put "/api/system/role/dataScope" admin {:role_id 9302 :data_scope "2" :dept_ids ""}))))
      (is (= 400 (code (request :put "/api/system/role/dataScope" admin {:role_id 9302 :data_scope "9"}))))
      (is (= 403 (code (request :put "/api/system/role/dataScope" admin {:role_id 1 :data_scope "3"}))) "超级管理员角色不可调整")
      (is (= 200 (code (request :put "/api/system/role/dataScope" admin {:role_id 9302 :data_scope "2" :dept_ids "5,6"}))))
      (is (= #{5 6} (set (get-in (request :get "/api/system/role/deptTree/9302" admin nil) [:body :data :checked-keys]))))
      (is (= #{9313 9314 9315} (user-ids (login! "scope9312"))) "自定义部门之外 (含本人所在部门) 不可见"))
    (testing "多角色取并集: 再授予本部门角色后看到本部门与自定义部门"
      (jdbc/execute! *db* ["INSERT INTO sys_user_role(user_id,role_id) VALUES (9312,9300)"])
      (is (= #{4 5 6} (dept-ids (login! "scope9312"))))
      (is (contains? (user-ids (login! "scope9312")) 9316)))
    (testing "改为非自定义范围时清空自定义部门"
      (is (= 200 (code (request :put "/api/system/role/dataScope" admin {:role_id 9302 :data_scope "5"}))))
      (is (empty? (get-in (request :get "/api/system/role/deptTree/9302" admin nil) [:body :data :checked-keys]))))))


(deftest dept-tree-maintenance
  (let [admin (login! "admin")
        create! (fn [parent nm & [extra]]
                  (let [resp (request :post "/api/system/dept" admin (merge {:parent_id parent :dept_name nm :order_num 1} extra))]
                    (is (= 200 (code resp)) (pr-str resp))
                    (:dept_id (first (jdbc/execute! *db* ["SELECT dept_id FROM sys_dept WHERE dept_name = ? AND del_flag = '0'" nm]
                                                    {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))))]
    (testing "迁移已修正种子部门祖级"
      (is (= "0,1,2" (:ancestors (dept-row 4))))
      (is (= "0,1,3" (:ancestors (dept-row 6)))))
    (let [a (create! 1 "事业部A")
          a1 (create! a "A一组" {:leader_id 9316})
          a11 (create! a1 "A一组一班")
          b (create! 1 "事业部B")]
      (testing "新建时祖级 = 上级祖级 + 上级编号; 负责人为用户并同步显示名"
        (is (= (str "0,1," a) (:ancestors (dept-row a1))))
        (is (= (str "0,1," a "," a1) (:ancestors (dept-row a11))))
        (is (= 9316 (:leader_id (dept-row a1))))
        (is (= "范围用户9316" (:leader (dept-row a1)))))
      (testing "同级重名拒绝"
        (is (= 400 (code (request :post "/api/system/dept" admin {:parent_id a :dept_name "A一组"})))))
      (testing "不能挂到自己或自己的下级之下"
        (is (= 400 (code (request :put (str "/api/system/dept/" a) admin {:parent_id a}))))
        (is (= 400 (code (request :put (str "/api/system/dept/" a) admin {:parent_id a11})))))
      (testing "移动部门级联更新全部下级祖级"
        (is (= 200 (code (request :put (str "/api/system/dept/" a1) admin {:parent_id b}))))
        (is (= (str "0,1," b) (:ancestors (dept-row a1))))
        (is (= (str "0,1," b "," a1) (:ancestors (dept-row a11)))))
      (testing "有未停用下级不能停用; 有下级或有用户不能删除"
        (is (= 400 (code (request :put (str "/api/system/dept/" a1) admin {:status "1"}))))
        (is (= 400 (code (request :delete (str "/api/system/dept/" a1) admin nil))))
        (is (= 400 (code (request :delete "/api/system/dept/4" admin nil))) "研发部门有用户"))
      (testing "清空负责人, 叶子部门可停用与删除"
        (is (= 200 (code (request :put (str "/api/system/dept/" a1) admin {:leader_id nil}))))
        (is (nil? (:leader_id (dept-row a1))))
        (is (= 200 (code (request :put (str "/api/system/dept/" a11) admin {:status "1"}))))
        (is (= 200 (code (request :delete (str "/api/system/dept/" a11) admin nil))))
        (is (= "2" (:del_flag (dept-row a11))))))))
