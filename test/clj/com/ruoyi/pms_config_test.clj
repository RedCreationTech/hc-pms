(ns com.ruoyi.pms-config-test
  "平台模板/编码规则/经营目标/封期配置及项目网络实例化 (A07/A09/A10/A11, B03/B06-B15 关口目录) 的真实数据库测试."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.gates :as gates]
    [com.ruoyi.domain.pms.planning :as planning]
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
  "配置测试专属角色与用户: 9401 管理配置+项目, 9402 只读, 9403 审批人."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9400,'Config test','pms-config-test',40,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9400,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9410,'Config reader','pms-config-reader',41,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9410,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:config:list')"])
  (doseq [[id role] [[9401 9400] [9402 9410] [9403 9400]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "config-test-" id) (str "配置测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-config-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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


(defn- project!
  ([] (project! "line"))
  ([type]
   (let [id (:project_id (pms/create-project! *service* (actor 9401)
                                              {:project_no (str "CFG-" (subs (str (UUID/randomUUID)) 0 8)) :name "模板实例化验证"
                                               :customer "本地测试" :contract_no "CFG-TEST" :project_type type
                                               :manager_id 9401 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
     (pms/set-member! *service* (actor 9401) id {:user_id 9403 :role "viewer"})
     id)))


(defn- version
  [id]
  (:version (pms/project *service* (actor 9401) id)))


(defn- command!
  ([id resource action rid body] (command! 9401 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- import-and-publish!
  "从目录导入并发布一个模板, 返回生效记录."
  [kind code]
  (let [draft (config/import-catalog! *service* (actor 9401) kind {:code code})]
    (config/publish! *service* (actor 9401) kind (:id draft) {:reason "发布用于测试"})))


(defn- published
  [kind code]
  (config/published-by-code (:query-fn *service*) kind code))


(deftest template-catalog-import-publish-revise-and-retire
  (let [listing (config/listing *service* (actor 9402) "project-template")]
    (is (<= 6 (count (:catalog listing))))
    (is (some #(= "TPL-LINE" (:code %)) (:catalog listing)))
    ;; 只读用户不能维护配置.
    (is (= 403 (error-status #(config/import-catalog! *service* (actor 9402) "project-template" {:code "TPL-LINE"}))))
    (let [draft (config/import-catalog! *service* (actor 9401) "project-template" {:code "TPL-LINE"})]
      (is (= "draft" (:status draft)))
      (is (= 1 (:revision draft)))
      (is (= 100 (reduce + (map :weight (:stages draft)))))
      ;; 重复编码需走修订.
      (is (= 409 (error-status #(config/import-catalog! *service* (actor 9401) "project-template" {:code "TPL-LINE"}))))
      (let [live (config/publish! *service* (actor 9401) "project-template" (:id draft) {:reason "首版发布"})]
        (is (= "published" (:status live)))
        (is (= "published" (last (map :action (:history live)))))
        ;; 修订: 从最新版本派生草稿, 发布后旧版退役, 内容仍保留.
        (let [rev (config/revise! *service* (actor 9401) "project-template" (:id live)
                                  (-> (dissoc live :id :config_id :kind :revision :status :created_by :created_at :updated_at :created_by_name :history :previous_id)
                                      (assoc :title "整线工程订单项目 V2")))]
          (is (= 2 (:revision rev)))
          (is (= "draft" (:status rev)))
          (is (= (:id live) (:previous_id rev)))
          (config/publish! *service* (actor 9401) "project-template" (:id rev) {:reason "第二版"})
          (let [rows (:rows (config/listing *service* (actor 9401) "project-template"))
                v1 (first (filter #(= (:id live) (:id %)) rows))
                v2 (first (filter #(= (:id rev) (:id %)) rows))]
            (is (= "retired" (:status v1)))
            (is (= "整线工程订单项目" (:title v1)))
            (is (= "published" (:status v2)))
            (is (= "整线工程订单项目 V2" (:title (published "project-template" "TPL-LINE"))))
            ;; 对已退役版本修订被拒 (非最新), 退役当前版本后无生效模板.
            (is (= 409 (error-status #(config/revise! *service* (actor 9401) "project-template" (:id live) {:title "x"}))))
            (config/retire! *service* (actor 9401) "project-template" (:id rev) {:reason "停用"})
            (is (nil? (published "project-template" "TPL-LINE")))))))
    ;; 模板内容校验: 权重不为100, 非法项目类别, 非法检查点均 400.
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "project-template"
                                              {:code "BAD-1" :title "坏模板" :project_types ["line"]
                                               :stages [{:code "S1" :name "a" :weight 50}]}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "project-template"
                                              {:code "BAD-2" :title "坏模板" :project_types ["unknown"]
                                               :stages [{:code "S1" :name "a" :weight 100}]}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "project-template"
                                              {:code "BAD-3" :title "坏模板" :project_types ["line"]
                                               :stages [{:code "S1" :name "a" :weight 100}]
                                               :gate_templates [{:code "G" :title "g" :stage "execution" :required true
                                                                 :blocks ["nope"] :checks [{:code "C" :title "c" :required true}]}]}))))))


(deftest coding-rule-renders-next-code-and-enforces-format
  (let [q (:query-fn *service*)
        rule (config/create! *service* (actor 9401) "coding-rule"
                             {:code "RULE-PROJECT-STRICT" :object_type "project" :pattern "HC-{YYYY}-{SEQ:4}" :enforced true
                              :description "严格项目编号"})]
    ;; 草稿不生效: 任意编号仍可建项目.
    (is (string? (project!)))
    (config/publish! *service* (actor 9401) "coding-rule" (:id rule) {:reason "启用"})
    (let [suggestion (config/next-code *service* (actor 9401) "project" nil nil)
          year (str (.getYear (java.time.LocalDate/now)))]
      (is (re-matches (re-pattern (str "HC-" year "-[0-9]{4}")) (:code suggestion)))
      (is (true? (get-in suggestion [:rule :enforced])))
      ;; 强制规则: 不符合格式的项目编号被 400 拒绝, 符合的通过.
      (is (= 400 (error-status #(pms/create-project! *service* (actor 9401)
                                                     {:project_no "FREE-1" :name "不合规编号" :project_type "line"
                                                      :manager_id 9401 :dept_id 1}))))
      (is (= (:code suggestion) (:project_no (pms/create-project! *service* (actor 9401)
                                                                  {:project_no (:code suggestion) :name "合规编号" :project_type "line"
                                                                   :manager_id 9401 :dept_id 1})))))
    ;; 缺少流水占位符或未知占位符被拒.
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "coding-rule" {:code "R-X" :object_type "task" :pattern "T-{YYYY}"}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "coding-rule" {:code "R-Y" :object_type "task" :pattern "T-{FOO}-{SEQ:2}"}))))
    (is (some? (config/pattern-regex "DOC-{PROJECT}-{SEQ:3}")))
    (is (re-matches (config/pattern-regex "DOC-{PROJECT}-{SEQ:3}") "DOC-HC-2026-0001-007"))
    ;; 退役后不再强制.
    (config/retire! *service* (actor 9401) "coding-rule" (:id (published "coding-rule" "RULE-PROJECT-STRICT")) {:reason "停用"})
    (is (nil? (:code (config/next-code *service* (actor 9401) "project" nil nil))))
    (is (string? (:project_id (pms/create-project! *service* (actor 9401)
                                                   {:project_no (str "FREE-" (subs (str (UUID/randomUUID)) 0 6)) :name "自由编号" :project_type "line"
                                                    :manager_id 9401 :dept_id 1}))))
    (is (fn? q))))


(deftest template-instantiation-builds-project-network-once
  (let [template (import-and-publish! "project-template" "TPL-EQUIPMENT")
        id (project! "equipment")
        other (project! "line")
        q (:query-fn *service*)]
    ;; 类别不匹配的项目不能套用.
    (is (= 409 (error-status #(command! other :template-instances :create nil {:template_id (:id template)}))))
    (let [instance (command! id :template-instances :create nil {:template_id (:id template) :reason "按设备模板建网"})
          nodes (:rows (pms/nodes *service* (actor 9401) id))
          plan (planning/read-plan *service* (actor 9401) id)
          ws (gov/workspace *service* (actor 9401) id)]
      (is (= "registered" (:status instance)))
      (is (= "TPL-EQUIPMENT" (:code instance)))
      (is (= (:revision template) (:template_revision instance)))
      ;; 结构: 主 + 2 子 + 1 单机.
      (is (= 3 (:node_count instance)))
      (is (= #{"main" "sub" "machine"} (set (map :node_type nodes))))
      (is (= 2 (count (filter #(= "sub" (:node_type %)) nodes))))
      ;; Gate 模板来自模板, 带类型与阻断检查点.
      (is (= 8 (:gate_template_count instance)))
      (let [kitting (first (filter #(= "kitting" (:gate_type %)) (:gate_templates ws)))]
        (is (= ["assembly.start"] (:blocks kitting)))
        (is (= "manufacturing" (:stage kitting))))
      (is (= 8 (count (:gate_progress ws))))
      (is (every? #(= "not_started" (:status %)) (:gate_progress ws)))
      ;; 计划容器: 8 个阶段 + 3 个结构节点计划, 带阶段/节点映射.
      (is (= 11 (:task_count instance)))
      (is (= 8 (count (filter :stage_code (:tasks plan)))))
      (is (= 3 (count (filter :node_id (:tasks plan)))))
      (is (every? #(= "summary" (:task_type %)) (:tasks plan)))
      ;; 交付要求与收尾清单.
      (is (true? (:delivery_configured instance)))
      (is (= 3 (:closure_item_count instance)))
      (is (= 3 (count (q :closure/items {:project_id id}))))
      ;; 只能实例化一次; 模板后续修订不影响实例快照.
      (is (= 409 (error-status #(command! id :template-instances :create nil {:template_id (:id template)}))))
      (let [rev (config/revise! *service* (actor 9401) "project-template" (:id template)
                                (-> (dissoc template :id :config_id :kind :revision :status :created_by :created_at :updated_at :created_by_name :history :previous_id)
                                    (assoc :stages [{:code "X" :name "单阶段" :weight 100}])))]
        (config/publish! *service* (actor 9401) "project-template" (:id rev) {:reason "改阶段"})
        (is (= 8 (count (:stages (first (:template_instances (gov/workspace *service* (actor 9401) id)))))))))
    ;; 未发布草稿不能应用.
    (let [draft (config/create! *service* (actor 9401) "project-template"
                                {:code "TPL-DRAFT" :title "草稿模板" :project_types ["line"]
                                 :stages [{:code "S1" :name "a" :weight 100}]})]
      (is (= 409 (error-status #(command! other :template-instances :create nil {:template_id (:id draft)})))))))


(deftest gate-catalog-creates-typed-template-and-blocks-checkpoint
  (let [id (project!) q (:query-fn *service*)
        project (pms/project *service* (actor 9401) id)
        template (command! id :gate-templates :from-catalog nil {:gate_type "kitting"})]
    (is (= "GT-KITTING" (:code template)))
    (is (= "kitting" (:gate_type template)))
    (is (= ["assembly.start"] (:blocks template)))
    (is (= 409 (error-status #(command! id :gate-templates :from-catalog nil {:gate_type "kitting"}))))
    (is (= 404 (error-status #(command! id :gate-templates :from-catalog nil {:gate_type "nope"}))))
    ;; 未通过实例前, 阻断检查点拒绝.
    (is (= 409 (error-status #(gates/checkpoint-ready! q project "assembly.start"))))
    (is (true? (gates/checkpoint-ready! q project "shipment.dispatch")))
    ;; 手工模板可声明 require_released 检查项与检查点.
    (let [manual (command! id :gate-templates :create nil
                           {:code "GT-FAT-X" :title "FAT放行" :gate_type "fat-confirm" :stage "delivery" :required true
                            :blocks ["shipment.dispatch"] :checks [{:code "F1" :title "FAT报告已发布" :required true :require_released true}]})
          document (command! id :documents :create nil {:code "FAT-RPT" :title "FAT报告" :filename "fat.txt" :content "FAT record"})
          gate (command! id :gates :create nil {:template_id (:id manual) :title "FAT放行检查" :reviewer_id 9403})]
      (is (true? (get-in manual [:checks 0 :require_released])))
      (command! id :gates :checks (:id gate) {:checks [{:code "F1" :passed true :evidence_ids [(:id document)]}]})
      ;; 证据未发布 -> 提交被拒; 发布后放行并解除阻断.
      (is (= 409 (error-status #(command! id :gates :submit (:id gate) {}))))
      (command! id :documents :submit (:id document) {:reviewer_id 9403})
      (command! 9403 id :documents :decision (:id document) {:decision "approved" :reason "发布"})
      (is (= "in_review" (:status (command! id :gates :submit (:id gate) {}))))
      (is (= 409 (error-status #(gates/checkpoint-ready! q project "shipment.dispatch"))))
      (is (= "approved" (:status (command! 9403 id :gates :decision (:id gate) {:decision "approved" :reason "放行"}))))
      (is (true? (gates/checkpoint-ready! q project "shipment.dispatch")))
      (let [progress (first (filter #(= "GT-FAT-X" (:code %)) (:gate_progress (gov/workspace *service* (actor 9401) id))))]
        (is (= "approved" (:status progress)))
        (is (= 1 (:passed_checks progress)))
        (is (true? (:passed progress)))))))


(deftest quarterly-target-rd-pool-and-period-lock-lifecycle
  (let [target (config/create! *service* (actor 9401) "quarterly-target"
                               {:year 2026 :quarter 4 :metric "revenue" :target_value "1500000" :basis "年度经营计划分解"})]
    (is (= "2026Q4-revenue" (:code target)))
    (is (= "1500000.00" (:target_value target)))
    (is (= "CNY" (:currency target)))
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "quarterly-target" {:year 2026 :quarter 5 :metric "revenue" :target_value "1"}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9401) "quarterly-target" {:year 2026 :quarter 1 :metric "closed_projects" :target_value 1.5}))))
    (config/publish! *service* (actor 9401) "quarterly-target" (:id target) {:reason "下达"})
    ;; 修订不改写历史: 旧版退役仍保留原目标值.
    (let [rev (config/revise! *service* (actor 9401) "quarterly-target" (:id target) {:year 2026 :quarter 4 :metric "revenue" :target_value "1800000"})]
      (config/publish! *service* (actor 9401) "quarterly-target" (:id rev) {:reason "上调"})
      (let [rows (:rows (config/listing *service* (actor 9401) "quarterly-target"))]
        (is (= "1500000.00" (:target_value (first (filter #(= (:id target) (:id %)) rows)))))
        (is (= "retired" (:status (first (filter #(= (:id target) (:id %)) rows)))))
        (is (= "1800000.00" (:target_value (published "quarterly-target" "2026Q4-revenue")))))))
  (let [pool (config/create! *service* (actor 9401) "rd-pool" {:period "2026-09" :amount "50000" :currency "CNY" :description "九月研发池"})]
    (is (= "POOL-2026-09" (:code pool)))
    (is (= "frozen" (:status (config/publish! *service* (actor 9401) "rd-pool" (:id pool) {:reason "冻结"})))))
  (let [lock (config/create! *service* (actor 9401) "period-lock" {:period "2026-08" :reason "八月工时封期"})]
    (is (= "locked" (:status lock)))
    (is (= 400 (error-status #(config/revise! *service* (actor 9401) "period-lock" (:id lock) {:period "2026-08" :reason "x"}))))
    (is (= "retired" (:status (config/retire! *service* (actor 9401) "period-lock" (:id lock) {:reason "解锁"}))))))


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


(deftest config-http-contract
  (is (= 401 (:status (request :get "/api/pms/config/project-template" nil nil))))
  (is (= 200 (:status (request :get "/api/pms/config/project-template" 9402 nil))))
  (is (= 403 (:status (request :post "/api/pms/config/project-template/import" 9402 {:code "TPL-SERVICE"}))))
  (let [created (request :post "/api/pms/config/project-template/import" 9401 {:code "TPL-SERVICE"})
        id (get-in created [:body :data :id])]
    (is (= 200 (:status created)))
    (is (= 200 (:status (request :post (str "/api/pms/config/project-template/" id "/publish") 9401 {:reason "http"}))))
    (is (= 409 (:status (request :post (str "/api/pms/config/project-template/" id "/publish") 9401 {:reason "again"}))))
    (is (= 400 (:status (request :get "/api/pms/config/unknown-kind" 9401 nil))))
    (let [next (request :get "/api/pms/coding-rules/next?object_type=project" 9401 nil)]
      (is (= 200 (:status next)))
      (is (nil? (get-in next [:body :data :code]))))))
