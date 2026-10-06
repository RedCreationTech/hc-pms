(ns com.ruoyi.pms-test
  "在全新应用数据库上验证项目管理 API,事务和权限边界."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.closure :as closure]
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
           [java.time LocalDate]
           [java.util UUID]))

(def ^:dynamic *service* nil)
(def ^:dynamic *handler* nil)
(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))

(defn- query-function
  "创建可显式注入事务连接的 HugSQL 查询函数."
  [db]
  (let [queries (:fns (conman/bind-connection-map db {} "sql/pms.sql" "sql/pms_planning.sql" "sql/pms_planning_resources.sql" "sql/pms_planning_baselines.sql" "sql/pms_governance.sql" "sql/pms_finance.sql" "sql/pms_closure.sql" "sql/pms_integration.sql" "sql/pms_delivery.sql" "sql/pms_config.sql" "sql/pms_approval.sql"))]
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

(deftest member-lifecycle-is-derived-read-only
  (let [rows [{:user_id 1 :user_name "a" :nick_name "甲" :role "editor" :ends_on "2026-09-01"}
              {:user_id 2 :user_name "b" :nick_name "乙" :role "editor" :ends_on "2026-10-10"}
              {:user_id 3 :user_name "c" :nick_name "丙" :role "viewer" :ends_on "2026-10-06"}
              {:user_id 4 :user_name "d" :nick_name "丁" :role "viewer" :ends_on "2026-10-20"}
              {:user_id 5 :user_name "e" :nick_name "戊" :role "manager" :ends_on "2026-10-21"}
              {:user_id 6 :user_name "f" :nick_name "己" :role "editor" :ends_on nil}]
        {:keys [summary]} (pms/member-lifecycle rows "2026-10-06")
        by-id (zipmap (map :user_id (:rows (pms/member-lifecycle rows "2026-10-06")))
                      (map :lifecycle-state (:rows (pms/member-lifecycle rows "2026-10-06"))))]
    (testing "逐成员状态按窗口边界归类"
      (is (= "expired" (by-id 1)))
      (is (= "expiring-soon" (by-id 2)))
      (is (= "expiring-soon" (by-id 3)))           ;; 到期日=今天, 边界内
      (is (= "expiring-soon" (by-id 4)))           ;; 还剩14天, 窗口上界
      (is (= "active" (by-id 5)))                  ;; 还剩15天, 出窗口
      (is (= "open-ended" (by-id 6)))
      (is (= -35 (:days-left (first (:rows (pms/member-lifecycle rows "2026-10-06")))))))
    (testing "汇总计数与两个清单"
      (is (= 6 (:total summary)))
      (is (= 14 (:window-days summary)))
      (is (= "2026-10-06" (:today summary)))
      (is (= {:active 1 :expiring-soon 3 :expired 1 :open-ended 1} (:counts summary)))
      (is (= #{2 3 4} (set (map :user_id (:expiring-soon summary)))))
      (is (= [1] (mapv :user_id (:expired summary))))
      (is (= {:expiring-soon 2} (get-in summary [:by-role "viewer"])))
      (is (= {:active 1} (get-in summary [:by-role "manager"])))
      (is (= {:expired 1 :expiring-soon 1 :open-ended 1} (get-in summary [:by-role "editor"]))))))

(deftest member-ends-on-persists-and-lifecycle-visible
  (let [project (create!) id (:project_id project)
        soon (.toString (.plusDays (LocalDate/now) 5))
        far (.toString (.plusDays (LocalDate/now) 90))
        past "2000-01-01"]
    (pms/set-member! *service* (actor 1) id {:user_id 9104 :role "editor" :ends_on past})
    (pms/set-member! *service* (actor 1) id {:user_id 9105 :role "editor" :ends_on soon})
    (is (= 400 (error-status #(pms/set-member! *service* (actor 1) id {:user_id 9102 :role "editor" :ends_on "2026/1/1"}))))
    (is (= 400 (error-status #(pms/set-member! *service* (actor 1) id {:user_id 9102 :role "editor" :ends_on "2026-13-40"}))))
    (pms/set-member! *service* (actor 1) id {:user_id 9102 :role "editor" :ends_on far})
    (let [res (request :get (str "/api/pms/projects/" id "/members") 1 nil)
        body (:body res)
        rows (get-in body [:data :rows])
        lifecycle (get-in body [:data :lifecycle])
        ends-by (zipmap (map :user_id rows) (map :ends_on rows))
        state-by (zipmap (map :user_id rows) (map :lifecycle-state rows))]
      (is (= 200 (:status res)))
      (is (= past (ends-by 9104)))
      (is (= far (ends-by 9102)))
      (is (= "expired" (state-by 9104)))
      (is (= "expiring-soon" (state-by 9105)))
      (is (= "active" (state-by 9102)))
      (is (= 1 (get-in lifecycle [:counts :expired])))
      (is (= 1 (get-in lifecycle [:counts :expiring-soon])))
      (is (= 1 (get-in lifecycle [:counts :active])))
      (is (= [9104] (mapv :user_id (:expired lifecycle)))))))

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


(deftest closure-progress-summary-is-derived-read-only
  (let [today (java.time.LocalDate/parse "2026-06-01")
        empty (closure/progress-summary [] nil [] today)]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:closure-pct empty)))
    (is (= "none" (:approval-state empty)))
    (is (= 0 (:required-open empty)))
    (is (= 0 (:overdue-open empty)))
    (is (= ["check" "handoff"] (mapv :key (:by-kind empty))))
    (is (= [0 0] (mapv :total (:by-kind empty))))
    (is (= [0 0] (mapv :completed (:by-kind empty))))
    (is (= [0 0] (mapv :completed-pct (:by-kind empty)))))
  (let [items [{:kind "check" :status "completed" :required true}
               {:kind "check" :status "open" :required true :due_date "2026-05-01"}
               {:kind "handoff" :status "completed" :required true :due_date "2026-07-01"}
               {:kind "handoff" :status "open" :required false :due_date "2026-05-15"}]
        rollup (closure/progress-summary items {:status "submitted"} [{:id "L1"}] (java.time.LocalDate/parse "2026-06-01"))]
    (is (true? (:available rollup)))
    (is (= 4 (:total rollup)))
    (is (= 2 (:checks rollup)))
    (is (= 2 (:handoffs rollup)))
    (is (= 2 (:completed rollup)))
    (is (= 2 (:open rollup)))
    (is (= 3 (:required rollup)))
    (is (= 1 (:required-open rollup)))
    (is (= 2 (:overdue-open rollup)))
    (is (= 1 (:lessons rollup)))
    (is (= "submitted" (:approval-state rollup)))
    (is (= 50 (:closure-pct rollup)))
    (is (= [2 2] (mapv :total (:by-kind rollup))))
    (is (= [1 1] (mapv :completed (:by-kind rollup))))
    (is (= [50 50] (mapv :completed-pct (:by-kind rollup))))))


(deftest closure-overview-exposes-read-only-progress
  (let [project (create!) id (:project_id project)
        _ (pms/set-member! *service* (actor 1) id {:user_id 9104 :role "editor"})
        path (str "/api/pms/projects/" id)
        overview (fn [] (:data (:body (request :get (str path "/closure") 1 nil))))
        base (overview)
        _ (request :post (str path "/closure/checks") 1
                   {:version (:project_version base) :title "核对随机附件" :required true})
        _ (request :post (str path "/closure/handoffs") 1
                   {:version (:project_version (overview)) :required false
                    :title "现场备件移交" :owner_id 9104 :due_date "2000-01-01"})
        full (overview)
        progress (:progress full)]
    (is (= 200 (:status (request :get (str path "/closure") 1 nil))))
    (is (true? (:available progress)))
    (is (= 2 (:total progress)))
    (is (= 1 (:checks progress)))
    (is (= 1 (:handoffs progress)))
    (is (= 0 (:completed progress)))
    (is (= 2 (:open progress)))
    (is (= 1 (:required-open progress)))
    (is (= 1 (:overdue-open progress)))
    (is (= "none" (:approval-state progress)))
    (is (= 0 (:closure-pct progress)))
    (is (false? (:ready full)))
    (is (seq (:blockers full)))
    ;; 只读汇总不旁路真实门控: 项目仍在初始阶段, 提交关闭须 409
    (is (= 409 (:status (request :post (str path "/closure/submit") 1
                                 {:version (:project_version (overview)) :reviewer_id 9104})))))


(deftest closure-readiness-summary-is-derived-read-only
  ;; 全部就绪: 五类均空 -> ready true, 100%, next-focus nil
  (let [clear (closure/readiness-summary {:tasks [] :governance [] :finance [] :delivery [] :items []} nil)]
    (is (true? (:available clear)))
    (is (= ["tasks" "governance" "finance" "delivery" "items"] (mapv :key (:categories clear))))
    (is (= 0 (:total-blockers clear)))
    (is (= 5 (:clear-count clear)))
    (is (= 0 (:blocked-count clear)))
    (is (= 100 (:readiness-pct clear)))
    (is (true? (:ready clear)))
    (is (nil? (:next-focus clear)))
    (is (= "none" (:approval-state clear)))
    (is (every? :clear (:categories clear))))
  ;; 多域受阻: tasks2/gov0/fin1/del3/items1 -> 总7, 就绪类别1, 受阻4, 20%, next-focus 首个受阻类别 计划任务
  (let [rollup (closure/readiness-summary {:tasks ["T1" "T2"] :governance []
                                           :finance ["F1"] :delivery ["D1" "D2" "D3"] :items ["I1"]}
                                          {:status "submitted"})]
    (is (= 7 (:total-blockers rollup)))
    (is (= 1 (:clear-count rollup)))
    (is (= 4 (:blocked-count rollup)))
    (is (= 20 (:readiness-pct rollup)))
    (is (false? (:ready rollup)))
    (is (= "计划任务" (:next-focus rollup)))
    (is (= "submitted" (:approval-state rollup)))
    (is (= [2 0 1 3 1] (mapv :blocker-count (:categories rollup))))
    (is (= [false true false false false] (mapv :clear (:categories rollup))))
    (is (= [2 0 1 3 1] (mapv count (map :messages (:categories rollup))))))
  ;; 单域多条缺口: 交付链7条 -> messages 截前5, more 2, 仅它受阻 -> 就绪4/5=80%, next-focus 交付链
  (let [big (closure/readiness-summary {:tasks [] :governance [] :finance []
                                       :delivery (vec (map str (range 7))) :items []} nil)
        del (first (filter #(= "delivery" (:key %)) (:categories big)))]
    (is (= 7 (:blocker-count del)))
    (is (= 5 (count (:messages del))))
    (is (= 2 (:more del)))
    (is (= 7 (:total-blockers big)))
    (is (= 4 (:clear-count big)))
    (is (= 80 (:readiness-pct big)))
    (is (= "交付链" (:next-focus big)))))


(deftest closure-readiness-attached-in-overview-is-faithful
  (let [project (create!) id (:project_id project)
        path (str "/api/pms/projects/" id)
        body (:body (request :get (str path "/closure") 1 nil))
        overview (:data body)
        r (:readiness overview)]
    (is (= 200 (:status body)))
    (is (some? r))
    (is (true? (:available r)))
    (is (= ["tasks" "governance" "finance" "delivery" "items"] (mapv :key (:categories r))))
    ;; 只读投影与权威扁平 blockers 同源: 总缺口数 = :blockers 条数, 各类别之和 = 总缺口
    (is (= (:total-blockers r) (count (:blockers overview))))
    (is (= (:total-blockers r) (reduce + (map :blocker-count (:categories r)))))
    ;; 就绪百分比 = 已就绪类别 / 全部类别 (四舍五入整数)
    (is (= (:readiness-pct r)
           (int (Math/round ^double (* 100.0 (/ (double (:clear-count r)) 5.0))))))
    ;; 新建项目无任务/无决算/无齐套 -> 必然有缺口, 未就绪, next-focus 与 ready 互斥
    (is (false? (:ready r)))
    (is (= (:ready r) (empty? (:blockers overview))))
    (is (true? (pos? (:total-blockers r))))
    (is (string? (:next-focus r))))))
