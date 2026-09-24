(ns com.ruoyi.web.authz-test
  "S1 安全与权限底线: 系统与办公接口的实时权限校验, 会话撤销, 敏感字段, 超级管理员保护, 登录锁定与注册开关.
  在全新 SQLite 库上经真实路由与中间件验证."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.infra.login-guard :as login-guard]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.controllers.common :as common]
    [com.ruoyi.web.routes.api :as api]
    [com.ruoyi.web.routes.auth :as auth-routes]
    [com.ruoyi.web.routes.business :as business-routes]
    [com.ruoyi.web.routes.system :as system-routes]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [reitit.core :as r]
    [reitit.ring :as ring]
    [ring.mock.request :as mock])
  (:import
    (java.nio.file
      Files)))


(def ^:dynamic *db* nil)
(def ^:dynamic *q* nil)
(def ^:dynamic *handler* nil)

(def password "Pass-12345")


(defn- query-function
  [db]
  (let [queries (:fns (conman/bind-connection-map db {} "queries.sql" "sql/system.sql" "sql/log.sql" "sql/job.sql"))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([conn name params] ((get-in queries [name :fn]) conn params)))))


(defn- seed!
  "9200 普通角色 (无菜单), 9201 用户查看 (用户列表), 9202 用户维护 (用户列表/查询/新增/修改)."
  [db]
  (let [hash (security/hash-password password)]
    (doseq [[id key] [[9200 "plain"] [9201 "user-viewer"] [9202 "user-admin"]]]
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag) VALUES (?,?,?,5,'1','0','0')" id (str "角色" id) key]))
    (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9201,menu_id FROM sys_menu WHERE perms = 'system:user:list'"])
    (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9202,menu_id FROM sys_menu WHERE perms IN ('system:user:list','system:user:query','system:user:add','system:user:edit')"])
    (doseq [[uid rid] [[9201 9200] [9202 9201] [9203 9202] [9204 9200]]]
      (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,password,status,del_flag) VALUES (?,4,?,?,?,'0','0')"
                         uid (str "authz" uid) (str "权限测试" uid) hash])
      (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" uid rid]))
    (jdbc/execute! db ["UPDATE sys_user SET password = ? WHERE user_id = 1" hash])))


(defn- fixture
  [f]
  (let [file (Files/createTempFile "hc-authz-" ".db" (make-array java.nio.file.attribute.FileAttribute 0))
        db (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" file)})]
    (try
      (migratus/migrate {:store :database :db {:datasource db} :migration-dir "migrations-sqlite"})
      (seed! db)
      (let [q (query-function db)
            svc {:query-fn q :db db}
            opts {:query-fn q :datasource db :user-service svc :role-service svc :menu-service svc :dept-service svc
                  :post-service svc :dict-service svc :config-service svc :log-service svc
                  :online-service {:list-online (fn [params] (apply online/list-online (mapcat (fn [[k v]] [(keyword (name k)) v]) params)))
                                   :force-logout online/force-logout!}}
            handler (ring/ring-handler
                      (ring/router [["/api" api/route-data
                                     (auth-routes/auth-routes opts)
                                     (system-routes/system-routes opts)
                                     (business-routes/business-routes opts)]]
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
  [user-name pwd]
  (let [resp (request :post "/api/auth/login" nil {:username user-name :password pwd})]
    (get-in resp [:body :data :token])))


(defn- code
  [resp]
  (or (get-in resp [:body :code]) (:status resp)))


(deftest every-system-and-office-endpoint-declares-permissions
  (testing "系统与办公路由组的每个接口都在路由数据中声明 :perms (未声明即拒绝)"
    (let [router (ring/router [["/api" (system-routes/system-routes {}) (business-routes/business-routes {})]]
                              {:reitit.router/sequential true :conflicts nil})
          missing (for [[path data] (r/routes router)
                        method [:get :post :put :delete]
                        :let [m (get data method)]
                        :when (and m (not (contains? m :perms)) (not (contains? data :perms)))]
                    [method path])]
      (is (> (count (r/routes router)) 100))
      (is (empty? missing) (str "未声明权限的接口: " (vec missing))))))


(deftest system-and-office-apis-check-live-permissions
  (let [admin (login! "admin" password)
        plain (login! "authz9201" password)
        viewer (login! "authz9202" password)]
    (is (string? admin))
    (testing "超级管理员放行, 返回数据不含密码"
      (let [resp (request :get "/api/system/user?page=1&size=50" admin nil)]
        (is (= 200 (code resp)))
        (is (seq (get-in resp [:body :data :rows])))
        (is (every? #(not (contains? % :password)) (get-in resp [:body :data :rows]))))
      (is (not (contains? (get-in (request :get "/api/system/user/9201" admin nil) [:body :data]) :password))))
    (testing "没有菜单权限的登录用户访问系统管理接口返回 403"
      (doseq [[method path body] [[:get "/api/system/user" nil]
                                  [:post "/api/system/role" {:role_name "越权" :role_key "x"}]
                                  [:put "/api/system/menu/1" {:menu_name "x"}]
                                  [:put "/api/system/user/9202/resetPwd" {}]
                                  [:get "/api/system/online" nil]
                                  [:get "/api/system/config" nil]
                                  [:get "/api/business/bpm/model" nil]
                                  [:post "/api/business/bpm/model/deploy/1" nil]
                                  [:get "/api/business/bpm/task/all" nil]
                                  [:get "/api/business/bpm/user-group" nil]]]
        (is (= 403 (:status (request method path plain body))) (str method " " path))))
    (testing "只有用户列表权限: 列表 200, 新增 403"
      (is (= 200 (code (request :get "/api/system/user" viewer nil))))
      (is (= 403 (:status (request :post "/api/system/user" viewer {:user_name "x" :nick_name "x" :password "12345678"})))))
    (testing "仅需登录的接口: 个人中心, 选人组件, 字典数据"
      (is (= 200 (code (request :get "/api/system/profile" plain nil))))
      (is (= 200 (code (request :get "/api/system/user/options" plain nil))))
      (is (every? #(= #{:user_id :user_name :nick_name :dept_id} (set (keys %)))
                  (get-in (request :get "/api/system/user/options" plain nil) [:body :data])))
      (is (= 200 (code (request :get "/api/system/dept/options" plain nil))))
      (is (= 200 (code (request :get "/api/system/dict/data?dict_type=sys_user_sex" plain nil)))))
    (testing "撤销菜单权限后下一次请求即失效"
      (jdbc/execute! *db* ["DELETE FROM sys_role_menu WHERE role_id = 9201"])
      (is (= 403 (:status (request :get "/api/system/user" viewer nil))))
      (jdbc/execute! *db* ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9201,menu_id FROM sys_menu WHERE perms = 'system:user:list'"]))
    (testing "getInfo: 超级管理员拥有 *:*:* 与全部启用菜单, 普通用户只有授权菜单"
      (let [a (get-in (request :get "/api/auth/getInfo" admin nil) [:body :data])
            v (get-in (request :get "/api/auth/getInfo" viewer nil) [:body :data])]
        (is (= ["*:*:*"] (:permissions a)))
        (is (> (count (:menus a)) 3))
        (is (= ["system:user:list"] (:permissions v)))
        (is (= ["系统管理"] (mapv :menu_name (:menus v))))
        (is (= ["用户管理"] (mapv :menu_name (:children (first (:menus v))))))))))


(deftest profile-and-registration-accept-only-safe-fields
  (let [plain (login! "authz9204" password)]
    (testing "个人信息更新忽略角色, 部门, 状态与密码"
      (jdbc/execute! *db* ["UPDATE sys_user SET dept_id = 4 WHERE user_id = 9204"])
      (is (= 200 (code (request :put "/api/system/profile" plain
                                {:nick_name "改昵称" :roles [1] :dept_id 1 :status "1" :password "hijack123"}))))
      (let [row (first (jdbc/execute! *db* ["SELECT nick_name, dept_id, status FROM sys_user WHERE user_id = 9204"]))]
        (is (= "改昵称" (:sys_user/nick_name row)))
        (is (= 4 (:sys_user/dept_id row)))
        (is (= "0" (:sys_user/status row))))
      (is (= [9200] (mapv :sys_user_role/role_id (jdbc/execute! *db* ["SELECT role_id FROM sys_user_role WHERE user_id = 9204"]))))
      (is (string? (login! "authz9204" password)) "原密码仍可登录"))
    (testing "注册默认关闭; 开启后注册用户没有任何角色"
      (is (= 403 (code (request :post "/api/auth/register" nil {:username "selfreg1" :password "Pass-123" :roles [1]}))))
      (jdbc/execute! *db* ["UPDATE sys_config SET config_value = 'true' WHERE config_key = 'sys.account.registerUser'"])
      (is (= 200 (code (request :post "/api/auth/register" nil {:username "selfreg1" :password "Pass-123" :roles [1] :status "0"}))))
      (let [uid (:sys_user/user_id (first (jdbc/execute! *db* ["SELECT user_id FROM sys_user WHERE user_name = 'selfreg1'"])))]
        (is (empty? (jdbc/execute! *db* ["SELECT role_id FROM sys_user_role WHERE user_id = ?" uid]))))
      (is (= 400 (code (request :post "/api/auth/register" nil {:username "selfreg2" :password "123"}))) "密码策略")
      (jdbc/execute! *db* ["UPDATE sys_config SET config_value = 'false' WHERE config_key = 'sys.account.registerUser'"]))))


(deftest sessions-are-revoked-on-logout-kick-disable-and-password-reset
  (let [admin (login! "admin" password)]
    (testing "令牌过期时间按秒, 带会话编号"
      (let [claims (security/parse-token admin)
            now-s (quot (System/currentTimeMillis) 1000)]
        (is (string? (:jti claims)))
        (is (< now-s (:exp claims) (+ now-s (* 25 3600)))))
      (is (nil? (security/parse-token (security/generate-token 1 "admin" [1] :exp-hours -1))) "已过期令牌无效"))
    (testing "退出后令牌立即失效"
      (let [t (login! "authz9204" password)]
        (is (= 200 (code (request :get "/api/system/profile" t nil))))
        (request :post "/api/auth/logout" t nil)
        (is (= 401 (:status (request :get "/api/system/profile" t nil))))))
    (testing "在线列表只返回会话编号, 强退后令牌失效且撤销记录持久化"
      (let [t (login! "authz9204" password)
            rows (get-in (request :get "/api/system/online?user_name=authz9204" admin nil) [:body :data :rows])
            jti (:jti (security/parse-token t))]
        (is (some #(= jti (:token-id %)) rows))
        (is (every? #(not (contains? % :token)) rows))
        (is (every? #(not (str/includes? (json/generate-string %) t)) rows))
        (is (= 200 (code (request :delete (str "/api/system/online/" jti) admin nil))))
        (is (= 401 (:status (request :get "/api/system/profile" t nil))))
        (online/set-query-fn! *q*)
        (is (= 401 (:status (request :get "/api/system/profile" t nil))) "重新加载撤销记录后仍然无效")))
    (testing "停用用户撤销其令牌, 启用后需重新登录"
      (let [t (login! "authz9204" password)]
        (is (= 200 (code (request :put "/api/system/user/9204/status/1" admin nil))))
        (is (= 401 (:status (request :get "/api/system/profile" t nil))))
        (is (nil? (login! "authz9204" password)))
        (request :put "/api/system/user/9204/status/0" admin nil)
        (is (string? (login! "authz9204" password)))))
    (testing "重置密码撤销旧令牌, 新密码可登录"
      (let [t (login! "authz9204" password)]
        (is (= 200 (code (request :put "/api/system/user/9204/resetPwd" admin {:password "NewPass-1"}))))
        (is (= 401 (:status (request :get "/api/system/profile" t nil))))
        (is (string? (login! "authz9204" "NewPass-1")))
        (request :put "/api/system/user/9204/resetPwd" admin {:password password})))
    (testing "编辑用户不会改动其密码"
      (is (= 200 (code (request :put "/api/system/user/9204" admin {:nick_name "编辑后" :dept_id 5}))))
      (is (string? (login! "authz9204" password))))))


(deftest super-admin-and-admin-role-are-protected
  (let [admin (login! "admin" password)
        editor (login! "authz9203" password)]
    (testing "用户维护角色不能修改超级管理员用户, 不能授予超级管理员角色"
      (is (= 403 (code (request :put "/api/system/user/1" editor {:nick_name "改管理员"}))))
      (is (= 403 (code (request :put "/api/system/user/9204" editor {:nick_name "x" :roles [1]}))))
      (is (= 403 (code (request :post "/api/system/user" editor {:user_name "escalate1" :nick_name "x" :password "Pass-1234" :roles [1]}))))
      (is (= 200 (code (request :post "/api/system/user" editor {:user_name "normal1" :nick_name "普通" :password "Pass-1234" :roles [9200]})))))
    (testing "超级管理员角色不可修改或删除; admin 权限字符保留; 角色名称与字符唯一"
      (is (= 403 (code (request :put "/api/system/role/1" admin {:role_name "改名"}))))
      (is (= 403 (code (request :delete "/api/system/role/1" admin nil))))
      (is (= 400 (code (request :post "/api/system/role" admin {:role_name "伪管理员" :role_key "admin"}))))
      (is (= 400 (code (request :post "/api/system/role" admin {:role_name "角色9200" :role_key "dup-name"}))))
      (is (= 400 (code (request :delete "/api/system/role/9200" admin nil))) "已分配用户的角色不能删除"))
    (testing "超级管理员用户不能被删除或停用"
      (is (= 500 (code (request :delete "/api/system/user/1" admin nil))) "不能删除当前用户")
      (is (= 403 (code (request :put "/api/system/user/1/status/1" admin nil)))))))


(deftest login-lockout-and-unified-message
  (let [admin (login! "admin" password)]
    (login-guard/clear! "authz9204")
    (testing "账号不存在与密码错误提示一致"
      (is (= "用户名或密码错误" (get-in (request :post "/api/auth/login" nil {:username "no-such-user" :password "x12345"}) [:body :msg])))
      (is (= "用户名或密码错误" (get-in (request :post "/api/auth/login" nil {:username "authz9204" :password "wrong-pass"}) [:body :msg]))))
    (testing "连续 5 次失败锁定, 正确密码也不能登录, 解锁后恢复"
      (dotimes [_ 4] (request :post "/api/auth/login" nil {:username "authz9204" :password "wrong-pass"}))
      (is (str/includes? (get-in (request :post "/api/auth/login" nil {:username "authz9204" :password password}) [:body :msg]) "锁定"))
      (is (= 200 (code (request :put "/api/system/login-log/unlock/authz9204" admin nil))))
      (is (string? (login! "authz9204" password))))
    (testing "验证码按参数开启后必须提供"
      (jdbc/execute! *db* ["UPDATE sys_config SET config_value = 'true' WHERE config_key = 'sys.account.captchaEnabled'"])
      (is (true? (get-in (request :get "/api/auth/loginConfig" nil nil) [:body :data :captchaEnabled])))
      (is (= "验证码错误或已过期" (get-in (request :post "/api/auth/login" nil {:username "admin" :password password}) [:body :msg])))
      (jdbc/execute! *db* ["UPDATE sys_config SET config_value = 'false' WHERE config_key = 'sys.account.captchaEnabled'"])
      (is (string? (login! "admin" password))))))


(deftest download-names-are-confined-to-the-upload-directory
  (testing "只接受单层文件名, 解析结果必须仍在目录内"
    (is (some? (common/resolve-inside "uploads/" "a1b2-c3.txt")))
    (doseq [bad [nil "" "../deps.edn" "a/b.txt" "..\\x" ".hidden" "sub/../../x" "/etc/hosts" "x/.."]]
      (is (nil? (common/resolve-inside "uploads/" bad)) (pr-str bad)))))
