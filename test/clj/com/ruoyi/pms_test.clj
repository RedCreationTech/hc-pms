(ns com.ruoyi.pms-test
  "在全新应用数据库上验证项目管理 API,事务和权限边界."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.infra.security :as security]
            [com.ruoyi.web.routes.api :as api]
            [com.ruoyi.web.routes.pms :as routes]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [java.nio.file Files]
           [java.util UUID]))

(def ^:dynamic *service* nil)
(def ^:dynamic *handler* nil)
(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))

(defn- query-function
  "创建可显式注入事务连接的 HugSQL 查询函数."
  [db]
  (let [queries (:fns (conman/bind-connection-map db {} "sql/pms.sql" "sql/pms_planning.sql" "sql/pms_planning_resources.sql" "sql/pms_planning_baselines.sql" "sql/pms_governance.sql" "sql/pms_finance.sql" "sql/pms_closure.sql" "sql/pms_integration.sql" "sql/pms_delivery.sql"))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))

(defn- seed-users!
  "建立独立角色,用于分开验证功能权限与项目成员权限."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9100,'PMS test','pms-test',10,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9100,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (doseq [id [9101 9102 9103 9104 9105]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "pms-test-" id) (str "测试用户" id)])
    (when-not (= 9105 id)
      (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,9100)" id]))))

(defn- database-fixture
  "创建隔离 SQLite 数据库,或使用调用者提供的全新 MySQL 数据库."
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "hc-pms-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})
        migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")]
    (try
      (migratus/migrate {:store :database :db {:datasource db} :migration-dir migration-dir})
      (seed-users! db)
      (let [service {:db db :query-fn (query-function db)}
            handler (ring/ring-handler
                      (ring/router [["/api" api/route-data
                                     (routes/pms-routes {:pms-service service})]]))]
        (binding [*service* service *handler* handler] (f)))
      (finally (when file (Files/deleteIfExists file))))))

(use-fixtures :once database-fixture)

(defn- actor
  "取得测试用户的实时身份."
  [id]
  (pms/actor *service* {:user-id id}))

(defn- body
  "生成唯一且完整的项目创建请求."
  ([] (body 9101))
  ([manager]
   {:project_no (str "PMS-" (subs (str (UUID/randomUUID)) 0 8))
    :name "自动化产线项目" :customer "测试客户" :contract_no "HT-01"
    :project_type "equipment" :manager_id manager :dept_id 1
    :start_date "2026-01-01" :end_date "2026-02-01"}))

(defn- create!
  "由管理员创建项目."
  ([] (create! (body)))
  ([params] (pms/create-project! *service* (actor 1) params)))

(defn- error-status
  "返回领域操作的预期业务错误状态."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(defn- request
  "经真实路由,认证与 JSON 中间件发送请求."
  [method path user-id payload]
  (let [req (cond-> (-> (mock/request method path)
                         (mock/content-type "application/json")
                         (mock/header "accept" "application/json"))
              user-id (mock/header "authorization"
                                   (str "Bearer " (security/generate-token user-id "test" [])))
              payload (mock/body (json/generate-string payload)))
        response (*handler* req)
        raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw (bytes? raw) (String. ^bytes raw "UTF-8")
                   :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))

(deftest rest-contract-and-validation
  (let [created (request :post "/api/pms/projects" 1 (body))
        project (get-in created [:body :data])
        id (:project_id project) path (str "/api/pms/projects/" id)]
    (is (= 200 (:status created)))
    (is (= "draft" (:status project)))
    (is (= 1 (:version project)))
    (is (string? (:manager_name project)))
    (is (= 200 (:status (request :get path 9101 nil))))
    (is (= 401 (:status (request :get path nil nil))))
    (is (= 403 (:status (request :get path 9103 nil))))
    (is (= 403 (:status (request :get path 9105 nil))))
    (is (= 404 (:status (request :get "/api/pms/projects/not-found" 1 nil))))
    (is (= 400 (:status (request :get "/api/pms/projects?page=0" 1 nil))))
    (is (= 400 (:status (request :get "/api/pms/projects?size=101" 1 nil))))
    (is (= 400 (:status (request :post "/api/pms/projects" 1 (assoc (body) :status "execution")))))
    (is (= 409 (:status (request :post "/api/pms/projects" 1 (select-keys project (keys (body)))))))
    (let [options (get-in (request :get "/api/pms/options" 1 nil) [:body :data])]
      (is (= 1 (:currentUserId options)))
      (is (seq (:users options)))
      (is (every? #(not (contains? % :password)) (:users options))))))

(deftest invalid-inputs
  (doseq [changes [{:start_date "2026-02-30"} {:end_date "2025-01-01"}
                   {:manager_id 999999} {:dept_id 999999} {:manager_id {}}
                   {:name "  "} {:project_type "unknown"} {:customer []}]]
    (is (= 400 (error-status #(create! (merge (body) changes))))))
  (is (= 400 (error-status #(pms/create-project! *service* (actor 1) [])))))

(deftest structure-and-optimistic-lock
  (let [project (create!) id (:project_id project)
        root (first (:rows (pms/nodes *service* (actor 1) id)))
        sub (pms/create-node! *service* (actor 9101) id
                              {:parent_id (:node_id root) :node_type "sub" :node_code "S-1" :name "包装段"})
        machine {:parent_id (:node_id sub) :node_type "machine" :node_code "M-1" :name "封箱机"}
        other (:project_id (create! (body 9102)))]
    (is (= "main" (:node_type root)))
    (is (= "machine" (:node_type (pms/create-node! *service* (actor 9101) id machine))))
    (is (= 3 (count (:rows (pms/nodes *service* (actor 1) id)))))
    (is (= 409 (error-status #(pms/create-node! *service* (actor 1) id machine))))
    (is (= 400 (error-status #(pms/create-node! *service* (actor 1) other machine))))
    (is (= 400 (error-status #(pms/create-node! *service* (actor 1) id
                                                              (assoc machine :parent_id (:node_id root) :node_code "M-2")))))
    (is (= 409 (error-status #(pms/update-project! *service* (actor 9101) id {:name "陈旧更新" :version 1}))))
    (let [latest (pms/project *service* (actor 1) id)
          edited (pms/update-project! *service* (actor 9101) id
                                      {:name "已修改" :version (:version latest)})]
      (is (= "已修改" (:name edited)))
      (is (= (inc (:version latest)) (:version edited)))
      (is (= 409 (error-status #(pms/update-project! *service* (actor 1) id
                                                    {:name "覆盖" :version (:version latest)})))))))

(deftest data-isolation-and-members
  (let [project (create!) id (:project_id project)
        other (create! (body 9102))]
    (pms/set-member! *service* (actor 1) id {:user_id 9104 :role "viewer"})
    (pms/set-member! *service* (actor 1) id {:user_id 9105 :role "editor"})
    (is (= id (:project_id (pms/project *service* (actor 9104) id))))
    (is (= 403 (error-status #(pms/project *service* (actor 9104) (:project_id other)))))
    (is (= 403 (error-status #(pms/update-project! *service* (actor 9104) id {:name "禁止" :version 3}))))
    (is (= 403 (error-status #(pms/update-project! *service* (actor 9105) id {:name "禁止" :version 3}))))
    (is (= 0 (:total (pms/projects *service* (actor 9103) {}))))
    (is (= 0 (:total (pms/dashboard *service* (actor 9103)))))
    (let [rows (:rows (pms/projects *service* (actor 9104) {:q (:project_no project)}))]
      (is (= [id] (mapv :project_id rows))))
    (is (= 400 (error-status #(pms/set-member! *service* (actor 1) id {:user_id 999999 :role "editor"}))))
    (is (= 409 (error-status #(pms/set-member! *service* (actor 1) id {:user_id 9101 :role "viewer"}))))
    (pms/set-member! *service* (actor 1) id {:user_id 9104 :role "editor"})
    (is (= 1 (count (filter #(= 9104 (:user_id %)) (:rows (pms/members *service* (actor 1) id))))))))

(deftest lifecycle-and-audit
  (let [project (create!) id (:project_id project)
        step (fn [status version reason]
               (pms/transition-project! *service* (actor 9101) id
                                        {:status status :version version :reason reason}))]
    (is (= 409 (error-status #(step "planning" 1 "跳级"))))
    (is (= "initiated" (:status (step "initiated" 1 "确认立项"))))
    (is (= 409 (error-status #(step "planning" 1 "旧版本"))))
    (is (= "planning" (:status (step "planning" 2 "开始编制计划"))))
    (let [blocked (request :post (str "/api/pms/projects/" id "/transition")
                           9101 {:status "execution" :version 3 :reason "开始执行"})]
      (is (= 409 (:status blocked)))
      (is (re-find #"批准基线" (get-in blocked [:body :msg]))))
    (is (= 400 (error-status #(step "cancelled" 3 ""))))
    (is (= "cancelled" (:status (step "cancelled" 3 "合同取消"))))
    (is (= 409 (error-status #(pms/update-project! *service* (actor 1) id {:name "禁止" :version 4}))))
    (is (= 409 (error-status #(pms/set-member! *service* (actor 1) id {:user_id 9103 :role "editor"}))))
    (is (= 409 (error-status #(pms/create-node! *service* (actor 1) id {}))))
    (is (= 409 (error-status #(step "draft" 4 "重开"))))
    (let [events (:rows (pms/events *service* (actor 1) id))]
      (is (= 4 (count events)))
      (is (= [4 3 2 1] (mapv :aggregate_version events)))
      (is (= 3 (count (filter #(= "project.transitioned" (:event_type %)) events))))
      (is (some #(= "合同取消" (:description %)) events)))))

(deftest audit-failure-rolls-back-entire-creation
  (let [original (:query-fn *service*)
        payload (body)
        broken (assoc *service* :query-fn
                      (fn
                        ([name params] (original name params))
                        ([tx name params]
                         (if (= name :pms/insert-event!)
                           (throw (ex-info "模拟审计不可写" {:status 503}))
                           (original tx name params)))))]
    (is (= 503 (error-status #(pms/create-project! broken (actor 1) payload))))
    (is (nil? (original :pms/project-no payload)))
    (is (= 0 (:total (pms/projects *service* (actor 1) {:q (:project_no payload)}))))))


(deftest project-root-stays-consistent
  (let [project (create!) id (:project_id project)
        updated (pms/update-project! *service* (actor 1) id
                                     {:version 1 :name "新主项目名称" :project_no (str "RENAMED-" id)})
        root (first (:rows (pms/nodes *service* (actor 1) id)))]
    (is (= (:project_no updated) (:node_code root)))
    (is (= (:name updated) (:name root)))
    (pms/create-node! *service* (actor 1) id
                      {:parent_id (:node_id root) :node_type "sub" :node_code "OCCUPIED" :name "子项目"})
    (is (= 409 (error-status #(pms/update-project! *service* (actor 1) id
                                                  {:version 3 :project_no "OCCUPIED"}))))
    (is (= (:project_no updated) (:project_no (pms/project *service* (actor 1) id))))))

(deftest disabled-user-and-revoked-permission
  (let [db (:db *service*)]
    (try
      (jdbc/execute! db ["UPDATE sys_user SET status='1' WHERE user_id=9103"])
      (is (= 401 (:status (request :get "/api/pms/projects" 9103 nil))))
      (finally (jdbc/execute! db ["UPDATE sys_user SET status='0' WHERE user_id=9103"])))
    (try
      (jdbc/execute! db ["DELETE FROM sys_user_role WHERE user_id=9103"])
      (is (= 403 (:status (request :get "/api/pms/projects" 9103 nil))))
      (finally (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (9103,9100)"])))))

(deftest unexpected-errors-do-not-expose-internals
  (with-redefs [pms/actor (fn [& _] (throw (ex-info "secret-sql-details" {:sql "sensitive-query"})))]
    (let [response (request :get "/api/pms/projects" 1 nil)]
      (is (= 500 (:status response)))
      (is (= {:code 500 :msg "服务暂时不可用,请稍后重试" :data nil} (:body response))))))

(deftest simultaneous-updates-have-one-winner
  (let [project (create!) id (:project_id project)
        original (:query-fn *service*)
        barrier (java.util.concurrent.CountDownLatch. 2)
        svc (assoc *service* :query-fn
                   (fn
                     ([name params] (original name params))
                     ([tx name params]
                      (when (= name :pms/update-project!)
                        (.countDown barrier)
                        (.await barrier 5 java.util.concurrent.TimeUnit/SECONDS))
                      (original tx name params))))
        user (actor 9101)
        update (fn [name]
                 (try (pms/update-project! svc user id {:version 1 :name name}) 200
                      (catch clojure.lang.ExceptionInfo e (or (:status (ex-data e)) (throw e)))))
        first (future (update "并发一"))
        second (future (update "并发二"))
        results [(deref first 15000 :timeout) (deref second 15000 :timeout)]]
    (is (= [200 409] (sort results)))
    (is (= 2 (:version (pms/project *service* user id))))
    (is (= [2 1] (mapv :aggregate_version (:rows (pms/events *service* user id)))))))
