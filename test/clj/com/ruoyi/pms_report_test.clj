(ns com.ruoyi.pms-report-test
  "G16 报表指标定义与导出: 已发布定义的求值, 授权范围, 状态过滤, CSV 与页面同列的真实数据库测试."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance-cost :as cost]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.portfolio :as portfolio]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.report :as report]
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
  "报表测试专属角色与用户: 9451 管理配置与项目, 9452 只读 (无财务查看权限)."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9450,'Report test','pms-report-test',40,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9450,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9455,'Report reader','pms-report-reader',41,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9455,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:config:list','pms:dashboard:query')"])
  (doseq [[id role] [[9451 9450] [9452 9455]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "report-test-" id) (str "报表测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-report-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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


(defn- error-status
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- request
  [method path uid payload]
  (let [req (cond-> (-> (mock/request method path) (mock/content-type "application/json")
                        (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              payload (mock/body (json/generate-string payload)))
        response (*handler* req) raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw
                   (bytes? raw) (String. ^bytes raw "UTF-8") :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))


(defn- raw-body
  "取原始响应体 (CSV 导出不是 JSON 信封)."
  [method path uid]
  (let [req (-> (mock/request method path) (mock/header "accept" "text/csv")
                (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" []))))
        raw (:body (*handler* req))]
    (cond (string? raw) raw
          (bytes? raw) (String. ^bytes raw "UTF-8")
          :else (slurp raw))))


;; ── G16 报表指标定义与导出 ─────────────────────────────────────────

(defn- reset-definitions!
  "G16 在此命名空间内独占: 清空上一用例的指标定义, 让每个用例从已知口径开始."
  []
  (doseq [row (config/records (:query-fn *service*) "report-metric")]
    (jdbc/execute! (:db *service*) ["DELETE FROM pms_config_record WHERE config_id=?" (:id row)])))


(defn- metric!
  "建立并可选发布一条报表指标定义."
  ([body] (metric! body true))
  ([body publish?]
   (let [record (config/create! *service* (actor 9451) "report-metric" body)]
     (when publish?
       (config/publish! *service* (actor 9451) "report-metric" (:id record) {:reason "G16 测试"}))
     record)))


(defn- project!
  "在报表测试角色下建立项目."
  []
  (let [id (:project_id (pms/create-project! *service* (actor 9451)
                                             {:project_no (str "RPT-" (subs (str (java.util.UUID/randomUUID)) 24)) :name "报表口径项目"
                                              :customer "本地测试" :contract_no "RPT-TEST" :project_type "line"
                                              :manager_id 9451 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
    (pms/set-member! *service* (actor 9451) id {:user_id 9452 :role "viewer"})
    id))


(defn- cost-version!
  "建立费用版本并加一笔明细."
  [id kind amount]
  (let [current (:version (pms/project *service* (actor 9451) id))
        version (:result (cost/create! *service* (actor 9451) id
                                       {:kind kind :period "2026-09" :currency "CNY" :name "报表口径"
                                        :revenue "1000.00" :reviewer_id 9452 :version current}))]
    (cost/add-entry! *service* (actor 9451) id (:id version)
                     {:category "other" :label "报表测试明细" :amount amount
                      :version (:version (pms/project *service* (actor 9451) id))})
    version))


(deftest report-metric-definition-derives-code-and-validates-scope
  (reset-definitions!)
  (testing "编码由 对象/聚合/字段/状态 派生, 调用方不填写"
    (is (= "PROJ-COUNT" (:code (metric! {:object_type "project" :aggregate "count" :label "项目数"}))))
    (is (= "PROJ-SUM-OPEN_ISSUES" (:code (metric! {:object_type "project" :aggregate "sum" :field "open_issues" :unit "count" :label "问题合计"}))))
    (is (= "COST-ENTRY-SUM-AMOUNT_MINOR@APPROVED"
           (:code (metric! {:object_type "cost-entry" :aggregate "sum" :field "amount_minor" :status "approved"
                            :unit "money" :label "批准金额"})))))
  (testing "同口径重复定义 409, 状态不同即不同口径"
    (is (= 409 (error-status #(metric! {:object_type "project" :aggregate "count" :label "重复项目数"}))))
    (is (some? (metric! {:object_type "project" :aggregate "count" :status "closed" :label "已关闭项目数"}))))
  (testing "非法口径被拒"
    (is (= 400 (error-status #(metric! {:object_type "unknown" :aggregate "count" :label "x"}))))
    (is (= 400 (error-status #(metric! {:object_type "project" :aggregate "median" :label "x"}))))
    (is (= 400 (error-status #(metric! {:object_type "project" :aggregate "sum" :field "name" :label "x"}))))
    (is (= 400 (error-status #(metric! {:object_type "project" :aggregate "count" :field "overall_percent" :label "x"}))))
    (is (= 400 (error-status #(metric! {:object_type "task" :aggregate "avg" :field "percent" :label "x"}))))
    (is (= 400 (error-status #(metric! {:object_type "project" :aggregate "count" :status "published" :label "x"}))))
    (is (= 400 (error-status #(metric! {:object_type "project" :aggregate "count" :unit "day" :label "x"}))))))


(deftest report-export-evaluates-published-definitions-only
  (reset-definitions!)
  (let [id (project!)
        _version (cost-version! id "actual" "120.00")
        _published (mapv #(metric! %) [{:object_type "project" :aggregate "count" :label "项目数"}
                                       {:object_type "project" :aggregate "sum" :field "open_issues" :unit "count" :label "问题合计"}
                                       {:object_type "cost-entry" :aggregate "sum" :field "amount_minor" :status "approved"
                                        :unit "money" :label "批准金额"}
                                       {:object_type "cost-entry" :aggregate "sum" :field "amount_minor"
                                        :unit "money" :label "明细金额"}])
        draft (metric! {:object_type "task" :aggregate "count" :label "草稿任务数"} false)
        report (report/reports *service* (actor 9451))
        codes (set (map :code (:rows report)))]
    (testing "只有已发布定义参与求值, 草稿版本如实列入 excluded"
      (is (contains? codes "PROJ-COUNT"))
      (is (contains? codes "COST-ENTRY-SUM-AMOUNT_MINOR@APPROVED"))
      (is (not (contains? codes (:code draft))))
      (is (some #(= (:code draft) (:code %)) (:excluded report))))
    (testing "项目计数按授权范围计行, 明细按状态过滤且金额精确到分"
      (let [by-code (into {} (map (juxt :code identity) (:rows report)))]
        (is (pos? (get-in by-code ["PROJ-COUNT" :row_count])))
        (is (= (get-in by-code ["PROJ-COUNT" :row_count]) (get-in by-code ["PROJ-COUNT" :value])))
        ;; 明细状态跟随所属版本: 草稿计入不限状态口径, 状态过滤为 approved 的口径如实为 0.
        (is (= "120.00" (get-in by-code ["COST-ENTRY-SUM-AMOUNT_MINOR" :value])))
        (is (= 1 (get-in by-code ["COST-ENTRY-SUM-AMOUNT_MINOR" :row_count])))
        (is (zero? (get-in by-code ["COST-ENTRY-SUM-AMOUNT_MINOR@APPROVED" :row_count])))))
    (testing "导出列与行列值同源, 列数一致"
      (is (= (count (:columns report)) (count (report/csv-cells (first (:rows report))))))
      (is (= ["指标编码" "指标名称" "单位" "对象" "聚合" "字段" "状态过滤" "行数" "指标值"]
             (mapv :title (:columns report)))))))


(deftest report-project-fields-derive-from-plan-not-zero
  (reset-definitions!)
  ;; 进度/齐套/问题等字段在 pms_project 表上并不存在, 必须与组合看板同源派生, 否则求值会把整列静默算成 0.
  (let [id (project!)
        _ (planning/create-task! *service* (actor 9451) id
                                 {:name "报表口径任务" :wbs_code "T-1" :task_type "task" :duration_days 2
                                  :owner_id 9451 :start_date "2026-09-01"
                                  :version (:version (pms/project *service* (actor 9451) id))})
        _ (metric! {:object_type "project" :aggregate "sum" :field "open_issues" :unit "count" :label "问题合计"})
        _ (metric! {:object_type "project" :aggregate "sum" :field "kit_percent" :unit "percent" :label "齐套合计"})
        report (report/reports *service* (actor 9451))
        by-code (into {} (map (juxt :code identity) (:rows report)))
        card (first (filter (fn [row] (= id (:project_id row)))
                            (:projects (portfolio/portfolio *service* (actor 9451)))))]
    (testing "问题/风险按治理记录计数, 无记录时如实为 0 且计行"
      (is (= 0 (get-in by-code ["PROJ-SUM-OPEN_ISSUES" :value])))
      (is (pos? (get-in by-code ["PROJ-SUM-OPEN_ISSUES" :row_count]))))
    (testing "齐套率与组合看板同源, 不是静默的 0 列"
      (is (some? (:kit_percent card)))
      (is (= (:kit_percent card) (get-in by-code ["PROJ-SUM-KIT_PERCENT" :raw])))
      (is (some? (:overall_percent card))))))


(deftest report-export-requires-finance-permission-for-cost-entries
  (reset-definitions!)
  (metric! {:object_type "cost-entry" :aggregate "count" :label "明细条数"})
  (let [report (report/reports *service* (actor 9452))]
    (is (false? (:finance_visible report)))
    (is (not-any? #(= "cost-entry" (:object_type %)) (:rows report)))
    (is (pos? (:withheld_count report)))))


(deftest report-http-contract
  (is (= 401 (:status (request :get "/api/pms/reports" nil nil))))
  (is (= 401 (:status (request :get "/api/pms/reports/export" nil nil))))
  (let [resp (request :get "/api/pms/reports" 9451 nil)
        data (:data (:body resp))]
    (is (= 200 (:status resp)))
    (is (true? (:available data)))
    (is (= (count (:rows data)) (:metric_count data))))
  (let [export (raw-body :get "/api/pms/reports/export" 9451)]
    (is (string? export))
    (is (str/starts-with? export "\uFEFF"))
    (is (str/starts-with? (subs export 1) "指标编码,指标名称,单位,对象,聚合,字段,状态过滤,行数,指标值"))
    (is (= (inc (count (:rows (:data (:body (request :get "/api/pms/reports" 9451 nil))))))
           (count (str/split-lines (str/trim (subs export 1)))))))
  ;; 只读用户可看报表但不能维护定义.
  (is (= 200 (:status (request :get "/api/pms/reports" 9452 nil))))
  (is (= 403 (:status (request :post "/api/pms/config/report-metric" 9452 {:object_type "project" :aggregate "count" :label "越权"})))))
