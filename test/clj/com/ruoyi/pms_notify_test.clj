(ns com.ruoyi.pms-notify-test
  "C11 本地通知管道: 授权收件人待办, 去重, 已读与处理, 撤权立即失效, 重试不重复通知, 不暴露跨项目内容."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.governance.store :as gov]
    [com.ruoyi.domain.pms.notify :as notify]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.service :as pms]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.routes.api :as api]
    [com.ruoyi.web.routes.pms :as routes]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [reitit.ring :as ring]
    [ring.mock.request :as mock])
  (:import
    (java.nio.file
      Files)
    (java.time
      LocalDate)
    (java.util
      UUID)))


(def ^:dynamic *service* nil)
(def ^:dynamic *handler* nil)
(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  "9800 全权限; 9801 项目负责人; 9802 / 9803 普通项目成员 (仅 list/query/edit)."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9800,'Notify owner','pms-notify-owner',80,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9800,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9801,'Notify member','pms-notify-member',81,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9801,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:project:edit')"])
  ;; 9809 无 PMS 菜单的只登记用户 (用于 403 合同), 9810 不存在的用户 (用于 401 合同)
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9802,'Notify none','pms-notify-none',82,'0','0')"])
  (doseq [[id role] [[9800 9800] [9801 9800] [9802 9801] [9803 9801] [9809 9802]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "notify-test-" id) (str "通知测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-notify-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (seed! db)
      (let [svc {:db db :query-fn (query-function db)}
            handler (ring/ring-handler (ring/router [["/api" api/route-data
                                                      (routes/pms-routes {:pms-service svc})]]))]
        (binding [*service* svc *handler* handler] (f)))
      (finally (when file (Files/deleteIfExists file))))))


(use-fixtures :once database-fixture)


(defn- actor
  [uid]
  (pms/actor *service* {:user-id uid}))


(defn- request
  [method path uid payload]
  (let [req (cond-> (-> (mock/request method path) (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              payload (-> (mock/content-type "application/json") (mock/body (json/generate-string payload))))
        response (*handler* req)
        raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw
                   (bytes? raw) (String. ^bytes raw "UTF-8") :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))


(defn- project!
  "建立由 9802 负责的项目 (9802 为经理, 9803 为普通成员); 建项目需 pms:project:add, 由全权限用户创建."
  [name]
  (let [id (:project_id (pms/create-project! *service* (actor 9800)
                                             {:project_no (str "NT-" (subs (str (UUID/randomUUID)) 0 8)) :name name
                                              :customer "本地测试" :contract_no "NT-TEST" :project_type "equipment"
                                              :manager_id 9802 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
    (pms/set-member! *service* (actor 9800) id {:user_id 9803 :role "editor"})
    id))


(defn- reset-gov!
  "清空本测试库的治理记录与成员关系, 使各用例互不干扰 (同一默认角色用户跨用例共享数据)."
  []
  (jdbc/execute! (:db *service*) ["DELETE FROM pms_gov_record"])
  (jdbc/execute! (:db *service*) ["DELETE FROM pms_member"]))


(defn- overdue-issue!
  "由 9803 负责且已逾期的开放问题, 用于产生逾期事件."
  [id]
  (gov/insert! (:query-fn *service*)
               (pms/project *service* (actor 9802) id) (actor 9802) "issue"
               {:title "遗漏线缆留孔" :severity "major" :owner_id 9803
                :due_date (str (.minusDays (LocalDate/now) 5))}
               {:status "open"}))


(defn- notifications
  [uid]
  (-> (request :get "/api/pms/notifications" uid nil) :body :data))


(deftest deliver-dedupes-and-does-not-cross-projects
  (reset-gov!)
  (let [id (project! "通知项目")
        _ (overdue-issue! id)
        first-run (notify/deliver! (:query-fn *service*) (actor 9803) (str (LocalDate/now)))
        second-run (notify/deliver! (:query-fn *service*) (actor 9803) (str (LocalDate/now)))
        rows (:rows (notifications 9803))]
    (testing "同一事件对同一收件人只落一条通知, 重跑不重复 (按本次项目核对)"
      (is (pos? (:created first-run)))
      (is (zero? (:created second-run)))
      (is (= #{id} (set (map :project_id rows))))
      (is (= (count rows) (count (distinct (map :code rows))))))
    (testing "通知带项目上下文与目标对象, 全部为未读且只投本地"
      (is (every? #(= "unread" (:status %)) rows))
      (is (every? #(= "local" (:channel %)) rows))
      (is (some #(and (= "issue" (:target_kind %)) (= "overdue" (:nature %))) rows)))
    (testing "其他成员的收件箱不含别人的通知, 也不跨项目"
      (is (empty? (get-in (request :get "/api/pms/notifications" 9802 nil) [:body :data :rows]))
          (str (get-in (request :get "/api/pms/notifications" 9802 nil) [:body :data :rows]))))))


(deftest read-handle-and-revoke
  (reset-gov!)
  (let [id (project! "通知状态项目")
        _ (overdue-issue! id)
        _ (notify/deliver! (:query-fn *service*) (actor 9803) (str (LocalDate/now)))
        target (first (get-in (request :get "/api/pms/notifications" 9803 nil) [:body :data :rows]))]
    (testing "非本人不能标记他人通知"
      (is (= 403 (:status (request :post (str "/api/pms/notifications/" (:id target) "/status")
                                   9802 {:status "read"})))))
    (testing "不能跳过已读直接回退, 未知状态 400"
      (is (= 400 (:status (request :post (str "/api/pms/notifications/" (:id target) "/status") 9803 {:status "bogus"})))))
    (testing "已读后进入 read, 再处理后进入 handled 且汇总随之变化"
      (let [read (request :post (str "/api/pms/notifications/" (:id target) "/status") 9803 {:status "read"})
            handled (request :post (str "/api/pms/notifications/" (:id target) "/status") 9803 {:status "handled"})
            summary (get-in (request :get "/api/pms/notifications" 9803 nil) [:body :data :summary])]
        (is (= 200 (:status read)))
        (is (= "read" (get-in read [:body :data :status])))
        (is (= 200 (:status handled)))
        (is (= "handled" (get-in handled [:body :data :status])))
        (is (pos? (:handled summary)))))
    (testing "终态不能回退为未读, 非法流转 409"
      (is (= 409 (:status (request :post (str "/api/pms/notifications/" (:id target) "/status") 9803 {:status "unread"})))))
    (testing "重新投递不覆盖已处理状态"
      (notify/deliver! (:query-fn *service*) (actor 9803) (str (LocalDate/now)))
      (is (= "handled" (:status (first (filter #(= (:id target) (:id %))
                                               (get-in (request :get "/api/pms/notifications" 9803 nil) [:body :data :rows])))))))
    (testing "收件人被移除项目成员后立即看不到历史通知"
      (is (seq (get-in (request :get "/api/pms/notifications" 9803 nil) [:body :data :rows])))
      (jdbc/execute! (:db *service*) ["DELETE FROM pms_member WHERE project_id=? AND user_id=9803" id])
      (is (empty? (get-in (request :get "/api/pms/notifications" 9803 nil) [:body :data :rows]))))))


(deftest retry-failed-delivery-does-not-duplicate
  (reset-gov!)
  (let [id (project! "通知重试项目")
        _ (overdue-issue! id)
        _ (notify/deliver! (:query-fn *service*) (actor 9803) (str (LocalDate/now)))
        rows (get-in (request :get "/api/pms/notifications" 9803 nil) [:body :data :rows])
        target (first rows)
        failed (request :post (str "/api/pms/notifications/" (:id target) "/delivery-failed") 9803 nil)
        after-fail (notifications 9803)
        retry (request :post "/api/pms/notifications/retry" 9803 nil)
        after-retry (notifications 9803)]
    (testing "登记投递失败只改投递维度, 不改业务状态, 也不新增通知"
      (is (= 200 (:status failed)))
      (is (= "failed" (:delivery (some #(when (= (:id target) (:id %)) %) (:rows after-fail)))))
      (is (= (count rows) (:total after-fail)))
      (is (pos? (get-in after-fail [:summary :failed]))))
    (testing "重试把失败推进为已投递并累加尝试次数, 通知条数不变"
      (is (= 200 (:status retry)))
      (is (pos? (get-in retry [:body :data :retried])))
      (is (= (count rows) (:total after-retry)))
      (is (zero? (get-in after-retry [:summary :failed])))
      (is (= 1 (:attempts (some #(when (= (:id target) (:id %)) %) (:rows after-retry))))))))


(deftest notifications-http-contract
  (is (= 401 (:status (request :get "/api/pms/notifications" nil nil))))
  (is (= 403 (:status (request :get "/api/pms/notifications" 9809 nil))))
  (is (= 401 (:status (request :get "/api/pms/notifications" 9810 nil))))
  (let [resp (request :get "/api/pms/notifications" 9803 nil)]
    (is (= 200 (:status resp)))
    (is (= "local" (get-in resp [:body :data :channel])))
    (is (contains? (get-in resp [:body :data :summary]) :unread))))
