(ns com.ruoyi.pms-platform-test
  "G18 平台能力与兼容基线: 由真实运行环境与真实请求 User-Agent 派生, 不猜未确认的兼容矩阵."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.platform :as platform]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.readiness :as readiness]
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
      Files)))


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
  "平台测试专属角色: 9460 具备项目查看权限, 9461 无 PMS 权限."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9460,'Platform test','pms-platform-test',42,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9460,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:dashboard:query')"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9461,'Platform reader','pms-platform-reader',43,'0','0')"])
  (doseq [[id role] [[9462 9460] [9463 9461]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "platform-test-" id) (str "平台测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-platform-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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
  [path uid user-agent]
  (let [req (cond-> (-> (mock/request :get path) (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              user-agent (mock/header "user-agent" user-agent))
        response (*handler* req)
        raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw
                   (bytes? raw) (String. ^bytes raw "UTF-8") :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))


(deftest client-baseline-parses-real-user-agents
  (testing "主流浏览器按真实 UA 判定, 低于基线不算通过"
    (is (= {:family "chrome" :version 120 :supported true}
           (select-keys (:client (platform/capabilities *service* (actor 9462)
                                                        {"user-agent" "Mozilla/5.0 (Macintosh) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"}))
                        [:family :version :supported])))
    (is (false? (:supported (:client (platform/capabilities *service* (actor 9462)
                                                            {"user-agent" "Mozilla/5.0 Firefox/60.0"})))))
    (is (= "safari" (:family (:client (platform/capabilities *service* (actor 9462)
                                                             {"user-agent" "Mozilla/5.0 (Macintosh) Version/16.4 Safari/605.1.15"}))))))
  (testing "无法识别的客户端如实标为未上报, 不当作通过"
    (let [client (:client (platform/capabilities *service* (actor 9462) {"user-agent" "curl/8.4.0"}))]
      (is (false? (:reported client)))
      (is (nil? (:supported client))))))


(deftest capabilities-report-runtime-and-database-truth
  (let [data (platform/capabilities *service* (actor 9462) {"user-agent" "Mozilla/5.0 Chrome/120.0"})]
    (testing "JDK 与数据库由真实运行环境派生"
      (is (= (System/getProperty "java.version") (get-in data [:java :version])))
      (is (true? (get-in data [:java :supported])))
      (let [kind (get-in data [:database :kind]) expected (if (and *jdbc-url* (.contains ^String *jdbc-url* "mysql")) "mysql" "sqlite")]
        (is (= expected kind))
        (is (true? (get-in data [:database :supported])))
        (is (= [expected] (mapv :kind (filter :current (:databases data)))))
        (is (= #{"sqlite" "mysql"} (set (map :kind (:databases data)))))))
    (testing "报表口径分布来自已发布配置, 空库时为 0 而不是伪造"
      (is (zero? (get-in data [:metrics :published])))
      (is (= 3 (count (get-in data [:metrics :definitions]))))
      (is (every? #(zero? (:metric_count %)) (get-in data [:metrics :definitions])))
      (is (= ["project" "cost-entry" "task"] (get-in data [:metrics :object_types])))
      (is (every? #(and (:object_type %) (:label %)) (get-in data [:metrics :definitions]))))
    (testing "环境核对是真实行数, 空库为 0"
      (is (zero? (get-in data [:environment :project_rows])))
      (is (zero? (get-in data [:environment :task_rows]))))))


(deftest capabilities-http-contract
  (is (= 401 (:status (request "/api/pms/platform/capabilities" nil nil))))
  (is (= 403 (:status (request "/api/pms/platform/capabilities" 9463 "Mozilla/5.0 Chrome/120.0"))))
  (let [resp (request "/api/pms/platform/capabilities" 9462 "Mozilla/5.0 (X11; Linux) Chrome/121.0.0.0 Safari/537.36")
        data (:data (:body resp))]
    (is (= 200 (:status resp)))
    (is (= "chrome" (get-in data [:client :family])))
    (is (true? (get-in data [:client :supported])))
    (is (= "Reagent 2 + Ant Design 6" (get-in data [:frontend :framework])))
    (is (seq (:browsers data)))))


(deftest readiness-reports-live-runtime-and-migration-truth
  (let [data (readiness/readiness *service* (actor 9462) {"JDBC_URL" "jdbc:sqlite:/x.db" "JAVA_HOME" "/opt/jdk"})]
    (testing "连接探测是真实往返, 但如实报告耗时与阈值"
      (is (true? (get-in data [:database :connected])))
      (is (false? (get-in data [:database :slow])))
      (is (nat-int? (get-in data [:database :latency-ms])))
      (is (= readiness/max-latency-ms (get-in data [:database :threshold-ms])))
      (is (nil? (get-in data [:database :error]))))
    (testing "迁移对账与启动同源: 已应用全部迁移, 待应用为 0"
      (is (nil? (get-in data [:migrations :unreadable])) "读得到清单时不标 unreadable")
      (is (pos? (get-in data [:migrations :known])))
      (is (= (get-in data [:migrations :known]) (get-in data [:migrations :applied])))
      (is (empty? (get-in data [:migrations :pending])))
      (is (zero? (:pending-count data)))
      (is (true? (:ready data))))
    (testing "配置只报在位与否, 绝不回显取值"
      (is (true? (:config-complete data)))
      (is (empty? (:missing-config data)))
      (is (every? #(false? (:value_exposed %)) (:config data)))
      (is (= #{"JDBC_URL" "JAVA_HOME"} (set (map :key (:config data)))))
      (is (not-any? #(some-> (get-in data [:config % :value]) some?) (range (count (:config data))))))
    (testing "只读复核: 明确声明无副作用, 支持库为随包迁移的两种"
      (is (true? (:read-only data)))
      (is (empty? (:side-effects data)))
      (is (= #{"sqlite" "mysql"} (set (:supports data))))
      (is (string? (:checked-at data)))))
  (testing "缺失关键配置如实点名, 不猜默认值"
    (let [data (readiness/readiness *service* (actor 9462) {"JAVA_HOME" "/opt/jdk"})]
      (is (false? (:config-complete data)))
      (is (= ["JDBC_URL"] (:missing-config data)))
      (is (true? (:ready data)) "配置缺失不阻断就绪判定, 只如实点名")))
  (testing "迁移清单读不到时标 unreadable, 不下「已全部应用」结论"
    (let [data (readiness/readiness *service* (actor 9462) nil)]
      (is (map? (:migrations data))))
    (with-redefs [readiness/migration-check-fns {:all (fn [_] (throw (ex-info "boom" {})))}]
      (let [data (readiness/readiness *service* (actor 9462) nil)]
        (is (true? (get-in data [:migrations :unreadable])))
        (is (zero? (:pending-count data)))
        (is (true? (:ready data))))))
  (testing "权限: 无 PMS 权限的用户被拒"
    (is (= 403 (try (readiness/readiness *service* (actor 9463) nil) nil
                    (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))))


(deftest readiness-http-contract
  (is (= 401 (:status (request "/api/pms/platform/readiness" nil nil))))
  (is (= 403 (:status (request "/api/pms/platform/readiness" 9463 nil))))
  (let [resp (request "/api/pms/platform/readiness" 9462 nil)
        data (:data (:body resp))]
    (is (= 200 (:status resp)))
    (is (true? (:ready data)))
    (is (true? (get-in data [:database :connected])))
    (is (zero? (:pending-count data)))
    (is (true? (:read-only data)))
    (is (every? #(false? (:value_exposed %)) (:config data)))
    (is (not (re-find #"jdbc:" (pr-str data))) "响应体不得出现连接串")))
