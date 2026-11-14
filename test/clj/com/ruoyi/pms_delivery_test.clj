(ns com.ruoyi.pms-delivery-test
  "真实数据库上的治理审批,证据,整批导入和跨模块闭环测试."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.domain.pms.delivery.store :as dstore]
            [com.ruoyi.domain.pms.delivery.materials :as materials]
            [com.ruoyi.pms-delivery-scenario :as scenario]
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
  (:import [java.nio.file Files]
           [java.util UUID]))


(def ^:dynamic *service* nil)


(def ^:dynamic *handler* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  "绑定治理以及会议行动转任务所需的真实参数化查询."
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} (distinct (conj queries/filenames "sql/pms_delivery.sql"))))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  "使用专属用户编号避免与其他模块共用MySQL测试库时冲突."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9500,'Delivery test','pms-delivery-test',30,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9500,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (doseq [id [9501 9502 9503 9504 9505]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "delivery-test-" id) (str "治理测试" id)])
    (when-not (= id 9505)
      (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,9500)" id]))))


(defn- database-fixture
  "全新SQLite或显式提供的MySQL库,迁移幂等且不删除其他模块表."
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-delivery-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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
  "使用真实数据库身份解析当前用户权限."
  [uid]
  (pms/actor *service* {:user-id uid}))


(defn- project!
  "创建项目并分离管理者,只读审批人和普通编辑者."
  []
  (let [project (pms/create-project! *service* (actor 1)
                 {:project_no (str "GOV-" (UUID/randomUUID)) :name "治理闭环验证"
                  :customer "本地测试" :contract_no "GOV-TEST" :project_type "equipment"
                  :manager_id 9501 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id project)]
    (pms/set-member! *service* (actor 1) id {:user_id 9502 :role "viewer"})
    (pms/set-member! *service* (actor 1) id {:user_id 9503 :role "editor"})
    id))


(defn- version
  "读取用于下一条命令的项目聚合版本."
  [id]
  (:version (pms/project *service* (actor 1) id)))


(defn- gov!
  "用当前项目版本执行真实类型化命令."
  ([id resource action rid body] (gov! 9501 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- workspace
  "读取当前项目的安全治理模型."
  [id]
  (gov/workspace *service* (actor 9501) id))


(defn- error-status
  "提取预期业务异常的HTTP状态."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- error-info
  "提取业务错误状态与文案, 成功时返回 nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- document!
  "上传真实文本作为可核验的版本证据."
  [id code]
  (gov! id :documents :create nil
            {:code code :title "实际验证记录" :filename "验收.txt" :content " 真实证据\n"}))


(defn- charter-body
  "生成各字段齐全的项目章程."
  []
  {:title "项目章程" :objective "完成验收目标" :scope "约定设备及培训"
   :success_criteria "验证清单全部通过" :sponsor_id 9503})


(defn- approve!
  "由项目经理提交,只读的指定审核人独立批准."
  [id resource rid]
  (gov! id resource :submit rid {:reviewer_id 9502})
  (gov! 9502 id resource :decision rid {:decision "approved" :reason "独立审查通过"}))


(defn- command!
  "使用当前聚合版本执行交付类型化命令."
  ([id resource action rid body] (command! 9501 id resource action rid body))
  ([uid id resource action rid body]
   (:result (delivery/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- transition!
  "经正式生命周期服务推进真实项目阶段."
  [id status]
  (pms/transition-project! *service* (actor 9501) id
                           {:version (version id) :status status :reason "交付集成测试"}))


(defn- execution-gate!
  "独立批准真实章程和执行Gate,不直接修改项目状态字段."
  [id evidence]
  (let [charter (gov! id :charters :create nil (charter-body))
        template (gov! id :gate-templates :create nil
                       {:code "EXEC-G" :title "执行前确认" :stage "execution" :required true
                        :checks [{:code "C" :title "证据齐全" :required true}]})
        gate (gov! id :gates :create nil {:template_id (:id template) :title "执行确认" :reviewer_id 9502})]
    (approve! id :charters (:id charter))
    (gov! id :gates :checks (:id gate) {:checks [{:code "C" :passed true :evidence_ids [evidence]}]})
    (gov! id :gates :submit (:id gate) {})
    (gov! 9502 id :gates :decision (:id gate) {:decision "approved" :reason "独立验证"})))


(defn- context!
  "完成任务,章程,Gate和独立计划基线审批后进入真实执行阶段."
  []
  (let [id (project!) document (document! id "REAL-EVIDENCE")
        req (gov! id :requirements :create nil {:code "URS-1" :text "设备交付须验证"
                                               :category "验收" :priority "required" :owner_id 9501})
        task (:result (planning/create-task! *service* (actor 9501) id
                                             {:version (version id) :wbs_code "D-1" :name "交付任务"
                                              :owner_id 9501 :start_date "2026-09-22" :duration_days 1}))]
    (execution-gate! id (:id document))
    (transition! id "initiated")
    (transition! id "planning")
    (let [baseline (:result (planning/submit-plan! *service* (actor 9501) id {:version (version id) :comment "提交"}))]
      (planning/review-plan! *service* (actor 9502) id (:baseline_id baseline)
                            {:version (version id) :decision "approved" :comment "独立确认"}))
    (transition! id "execution")
    {:id id :evidence (:id document) :task_id (:task_id task) :requirement_ids [(:id req)]}))


(defn- refs
  "为执行记录提供确定的真实任务和需求版本."
  [ctx]
  (select-keys ctx [:task_id :requirement_ids]))


(defn- review!
  "将交付记录提交给指定只读审批人并独立批准."
  [ctx resource rid action]
  (let [id (:id ctx) evidence {:evidence_ids [(:evidence ctx)]}]
    (command! id resource action rid (cond-> evidence (not= :shipments resource) (assoc :reviewer_id 9502)))
    (command! 9502 id resource :decision rid {:decision "approved" :reason "独立核实证据"})))


(defn- frozen-bom!
  "完成真实物料申请和BOM冻结的两次独立批准."
  [ctx]
  (let [id (:id ctx)
        material (command! id :material-requests :create nil
                           (merge (refs ctx) {:code "MR-1" :title "长周期件预投" :request_type "long_lead"
                                             :owner_id 9501 :needed_on "2026-10-01"
                                             :items [{:code "M-1" :name "执行器" :quantity 2 :unit "个"}
                                                     {:code "M-2" :name "连接线" :quantity 4 :unit "根"}]}))]
    (review! ctx :material-requests (:id material) :submit)
    (let [bom (command! id :boms :create nil {:code "BOM-1" :title "受控配置" :material_request_id (:id material)})]
      (review! ctx :boms (:id bom) :freeze))))


(defn- approved-assembly!
  "实际备料齐套,开工及独立装配交检."
  [ctx]
  (let [id (:id ctx) bom (frozen-bom! ctx)]
    (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 2} {:code "M-2" :available_quantity 4}]
                                      :evidence_ids [(:evidence ctx)]})
    (let [assembly (command! id :assemblies :create nil
                             (merge (refs ctx) {:code "ASS-1" :title "执行装配" :bom_id (:id bom) :owner_id 9501}))]
      (command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]})
      (review! ctx :assemblies (:id assembly) :submit))))


(defn- test-record!
  "预先创建明确验收准则的测试记录."
  [ctx assembly type]
  (command! (:id ctx) :tests :create nil
            (merge (refs ctx) {:code (str type "-1") :title (str type "验证") :assembly_id (:id assembly)
                              :test_type type :owner_id 9501
                              :criteria [{:code "Q-1" :title "动作满足URS" :required true}]})))


(defn- results!
  "提交有实际证据的测试结果."
  [ctx test passed]
  (command! (:id ctx) :tests :results (:id test)
            {:checks [{:code "Q-1" :passed passed :actual (if passed "测试通过" "动作超差") :evidence_ids [(:evidence ctx)]}]
             :due_date "2026-10-03"}))


(defn- approved-test!
  "登记通过结果并经独立检验批准试验."
  [ctx assembly type]
  (let [test (test-record! ctx assembly type)]
    (results! ctx test true)
    (review! ctx :tests (:id test) :submit)))


(defn- shipment!
  "准备并放行一个实际装配范围的发运单."
  [ctx assembly]
  (let [shipment (command! (:id ctx) :shipments :create nil
                           (merge (refs ctx) {:code "SHIP-1" :title "设备发运" :assembly_ids [(:id assembly)]
                                             :consignee "现场接收团队" :delivery_address "客户指定地址"
                                             :planned_date "2026-10-05" :reviewer_id 9502}))]
    (review! ctx :shipments (:id shipment) :submit)))


(defn- dispatch!
  "登记真实发运日期,物流单号和实际装箱凭据."
  [ctx shipment]
  (command! (:id ctx) :shipments :dispatch (:id shipment)
            {:shipped_on (.toString (.minusDays (java.time.LocalDate/now) 2)) :tracking_no "LOCAL-RECORD-1" :evidence_ids [(:evidence ctx)]}))


(defn- accept!
  "由指定独立验证人核实客户签收凭据."
  [ctx shipment]
  (command! 9502 (:id ctx) :shipments :receipt (:id shipment)
            {:received_on (.toString (.minusDays (java.time.LocalDate/now) 1)) :receiver_name "现场签收人" :acceptance "accepted" :evidence_ids [(:evidence ctx)]}))


(defn- fake-delivery-q
  "按 :delivery/list 请求的 kind 返回罐头行, 供 closure-blockers 纯表征测试 (不查库)."
  [rows-by-kind]
  (fn [name params]
    (when (= :delivery/list name) (get rows-by-kind (:kind params) []))))


(defn- blockers
  "在给定交付记录罐头数据下求收尾阻塞清单."
  [rows-by-kind]
  (delivery/closure-blockers (fake-delivery-q rows-by-kind) {:project_id 1}))


;; closure-blockers 重构前的行为钉: 全部阻塞规则的触发,顺序与 stage 门控, 纯桩不依赖数据库.
(deftest closure-blockers-covers-every-delivery-clause
  (testing "完整阶段配置下逐条触发: BOM/交检/试验/签收/交付范围/售后/工勘/交底"
    (is (= ["缺少已冻结并实际齐套的BOM"
            "装配尚未独立交检通过: A1"
            "装配 A1 缺少已批准 SIT 试验"
            "发运单尚未完整签收: S1"
            "装配范围尚未完整交付: A1"
            "售后异常尚未独立关闭: 售后X"
            "已确认工勘 0 次, 少于配置要求 1 次"
            "交底尚未完成: H1"]
           (blockers {"configuration" [{:record_id 1 :payload "{}"
                                        :required_stages ["materials" "assembly" "quality" "shipment"]
                                        :required_test_types ["SIT"] :handover_required true
                                        :required_survey_visits 1}]
                      "bom" []
                      "assembly" [{:record_id 21 :payload "{}" :code "A1" :status "draft"}]
                      "shipment" [{:record_id 31 :payload "{}" :code "S1" :status "shipped"}]
                      "service" [{:record_id 41 :payload "{}" :title "售后X" :status "open"}]
                      "survey" []
                      "handover" [{:record_id 51 :payload "{}" :code "H1" :status "open"}]}))))
  (testing "空装配与空发运: 容器级缺失提示先于逐条提示"
    (is (= ["缺少已冻结并实际齐套的BOM"
            "缺少实际装配交检记录"
            "缺少真实发运及签收记录"]
           (blockers {"configuration" [{:record_id 1 :payload "{}"
                                        :required_stages ["materials" "assembly" "quality" "shipment"]
                                        :required_test_types [] :handover_required false
                                        :required_survey_visits 0}]
                      "bom" [] "assembly" [] "shipment" [] "service" [] "survey" [] "handover" []}))))
  (testing "未配置的阶段不产生对应阻塞, 逐条装配/发运/售后提示不受 stage 门控"
    (is (= ["装配尚未独立交检通过: A1"
            "发运单尚未完整签收: S1"
            "售后异常尚未独立关闭: 售后X"]
           (blockers {"configuration" [{:record_id 1 :payload "{}" :required_stages []
                                        :required_test_types [] :handover_required false
                                        :required_survey_visits 0}]
                      "bom" []
                      "assembly" [{:record_id 21 :payload "{}" :code "A1" :status "draft"}]
                      "shipment" [{:record_id 31 :payload "{}" :code "S1" :status "shipped"}]
                      "service" [{:record_id 41 :payload "{}" :title "售后X" :status "open"}]
                      "survey" [] "handover" []})))))


(defn- config-err
  "调用配置校验并返回 [status message] 或 nil (通过)."
  [body project]
  (try (#'dstore/validate-configuration! body project) nil
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


;; configure! 重构前的行为钉: 全部门控 (阶段锁/数值区间/数组/枚举/前置依赖/布尔位), 纯函数不依赖数据库.
(deftest configuration-validation-gates-every-field
  (let [draft {:status "draft"}
        valid {:required_stages ["materials" "assembly"] :required_test_types ["SIT"] :reason "配置"
               :required_survey_visits 2 :handover_deadline_days 7 :site_lag_days 3
               :pre_ship_conditions ["warehouse_in"] :handover_required true}]
    (testing "合法配置通过, 字段白名单外的键被拒"
      (is (nil? (config-err valid draft)))
      (is (= [400 "请求包含不支持或不可修改的字段"] (config-err (assoc valid :extra 1) draft))))
    (testing "执行开始后锁定"
      (is (= [409 "执行开始后不可修改适用交付流程"] (config-err valid {:status "execution"}))))
    (testing "数值字段必须是 0 到上限的整数"
      (is (= [400 "required_survey_visits 必须是0到10的整数"] (config-err (assoc valid :required_survey_visits 11) draft)))
      (is (= [400 "handover_deadline_days 必须是0到30的整数"] (config-err (assoc valid :handover_deadline_days -1) draft)))
      (is (= [400 "site_lag_days 必须是0到30的整数"] (config-err (assoc valid :site_lag_days 1.5) draft))))
    (testing "发货前条件必须为不重复受控数组"
      (is (= [400 "发货前条件必须为不重复数组"] (config-err (assoc valid :pre_ship_conditions ["payment" "payment"]) draft)))
      (is (= [400 "发货前条件必须为不重复数组"] (config-err (assoc valid :pre_ship_conditions "payment") draft)))
      (is (= [400 "pre_ship_conditions取值不合法"] (config-err (assoc valid :pre_ship_conditions ["nope"]) draft))))
    (testing "环节与试验类型必须为非空不重复受控数组"
      (is (= [400 "适用流程与试验类型必须为非空不重复数组"] (config-err (assoc valid :required_stages []) draft)))
      (is (= [400 "适用流程与试验类型必须为非空不重复数组"] (config-err (assoc valid :required_test_types ["SIT" "SIT"]) draft)))
      (is (= [400 "适用流程与试验类型必须为非空不重复数组"] (config-err (assoc valid :required_stages "materials") draft)))
      (is (= [400 "required_test_types取值不合法"] (config-err (assoc valid :required_test_types ["XYZ"]) draft))))
    (testing "环节前置依赖与布尔位"
      (is (= [400 "适用环节必须包含物料,装配,质量的必要前置"] (config-err (assoc valid :required_stages ["shipment"]) draft)))
      (is (= [400 "handover_required必须为布尔值"] (config-err (assoc valid :handover_required "yes") draft))))))


(deftest material-freeze-and-kitting-enforce-real-prerequisites
  (let [ctx (context!) id (:id ctx) bom (frozen-bom! ctx)
        partial (command! id :boms :kit (:id bom)
                          {:items [{:code "M-1" :available_quantity 1} {:code "M-2" :available_quantity 4}]
                           :evidence_ids [(:evidence ctx)]})]
    (is (= "frozen" (:status bom)))
    (is (= "partial" (:status partial)))
    (is (= 50.0 (:kit_percent partial)))
    (is (= [2 4] (mapv :quantity (:items partial))))
    (is (= 409 (error-status #(command! id :assemblies :create nil
                                      (merge (refs ctx) {:code "A" :title "违规开工" :bom_id (:id bom) :owner_id 9501})))))
    (is (= 400 (error-status #(command! id :boms :kit (:id bom)
                                      {:items [{:code "M-1" :available_quantity 99} {:code "M-2" :available_quantity 4}]
                                       :evidence_ids [(:evidence ctx)]}))))
    (is (= 409 (error-status #(command! id :configuration :update nil
                                      {:required_stages ["materials"] :required_test_types ["SIT"] :reason "执行后绕开"}))))
    (is (= 403 (error-status #(delivery/workspace *service* (actor 9504) id))))
    (is (= "not_configured" (:external_sync_status (delivery/workspace *service* (actor 9501) id))))))


;; batch54 (mutator 方向: cover the uncovered): production 五门 — 准则两门400, 结果完整性400, SAT 发运门409, 前序版本变化409.
(deftest test-creation-results-and-prerequisite-gates
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx)]
    (testing "试验准则三门400: 空数组, 无必检项, 编码重复"
      (is (= 400 (error-status #(command! id :tests :create nil
                                          (merge (refs ctx) {:code "CR-1" :title "准则空" :assembly_id (:id assembly)
                                                            :test_type "SIT" :owner_id 9501 :criteria []})))))
      (is (= 400 (error-status #(command! id :tests :create nil
                                          (merge (refs ctx) {:code "CR-2" :title "无必检" :assembly_id (:id assembly)
                                                            :test_type "SIT" :owner_id 9501
                                                            :criteria [{:code "A" :title "a" :required false}]})))))
      (is (= 400 (error-status #(command! id :tests :create nil
                                          (merge (refs ctx) {:code "CR-3" :title "编码重复" :assembly_id (:id assembly)
                                                            :test_type "SIT" :owner_id 9501
                                                            :criteria [{:code "A" :title "a" :required true}
                                                                       {:code "A" :title "b" :required false}]}))))))
    (testing "登记结果必须与准则码集一致400"
      (let [test (command! id :tests :create nil
                           (merge (refs ctx) {:code "CR-R" :title "结果完整性" :assembly_id (:id assembly)
                                              :test_type "SIT" :owner_id 9501
                                              :criteria [{:code "Q-1" :title "动作满足URS" :required true}]}))]
        (is (= 400 (error-status #(command! id :tests :results (:id test)
                                            {:checks [] :due_date "2026-10-03"}))))))
    (testing "SAT现场试验须有已接收的实际发运记录409 (SIT/FAT 已批准但尚无发运)"
      (approved-test! ctx assembly "SIT")
      (approved-test! ctx assembly "FAT")
      (let [sat (test-record! ctx assembly "SAT")]
        (is (= 409 (error-status #(results! ctx sat true))))))
    (testing "前序试验版本已变化时签核被拒409 (记录结果后新建并批准了更新的 SIT)"
      (let [fat (command! id :tests :create nil
                          (merge (refs ctx) {:code "FAT-2" :title "FAT复验" :assembly_id (:id assembly)
                                             :test_type "FAT" :owner_id 9501
                                             :criteria [{:code "Q-1" :title "动作满足URS" :required true}]}))]
        (results! ctx fat true)
        (let [sit2 (command! id :tests :create nil
                             (merge (refs ctx) {:code "SIT-2" :title "SIT更新版" :assembly_id (:id assembly)
                                                :test_type "SIT" :owner_id 9501
                                                :criteria [{:code "Q-1" :title "动作满足URS" :required true}]}))]
          (results! ctx sit2 true)
          (review! ctx :tests (:id sit2) :submit))
        (is (= 409 (error-status #(review! ctx :tests (:id fat) :submit))))))))


(deftest full-delivery-requires-sat-and-independent-receipt
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx)]
    (approved-test! ctx assembly "SIT")
    (let [premature (test-record! ctx assembly "SAT")]
      (is (= 409 (error-status #(results! ctx premature true))))
      (is (= 409 (error-status #(review! ctx :tests (:id premature) :submit))))
      (approved-test! ctx assembly "FAT")
      (let [shipment (shipment! ctx assembly)]
        (dispatch! ctx shipment)
        (is (= 403 (error-status #(command! id :shipments :receipt (:id shipment)
                                          {:received_on (.toString (.minusDays (java.time.LocalDate/now) 1)) :receiver_name "自己签收" :acceptance "accepted"
                                           :evidence_ids [(:evidence ctx)]}))))
        (is (seq (:blockers (delivery/workspace *service* (actor 9501) id))))
        (is (= "received" (:status (accept! ctx shipment))))
        (results! ctx premature true)
        (review! ctx :tests (:id premature) :submit)
        (is (empty? (:blockers (delivery/workspace *service* (actor 9501) id))))
        (is (= 409 (error-status #(planning/delete-task! *service* (actor 9501) id (:task_id ctx) {:version (version id)}))))))))


(deftest failed-test-creates-one-traceable-independent-remediation
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx) test (test-record! ctx assembly "SIT")
        failure (results! ctx test false) repeated (results! ctx test false)
        issue-id (get-in failure [:checks 0 :issue_id])]
    (is (string? issue-id))
    (is (= issue-id (get-in repeated [:checks 0 :issue_id])))
    (is (= 2 (count (:result_history repeated))))
    (is (= 409 (error-status #(review! ctx :tests (:id test) :submit))))
    (results! ctx test true)
    (is (= 409 (error-status #(review! ctx :tests (:id test) :submit))))
    (gov! id :issues :resolve issue-id {:resolution "已处理并复验" :evidence_ids [(:evidence ctx)] :reviewer_id 9502})
    (gov! 9502 id :issues :decision issue-id {:decision "approved" :reason "独立复验满足"})
    (is (= "approved" (:status (review! ctx :tests (:id test) :submit))))
    (let [record (first (:tests (delivery/workspace *service* (actor 9501) id)))]
      (is (= issue-id (get-in record [:checks 0 :issue_id])))
      (is (= 3 (count (:result_history record)))))))


(deftest failing-required-result-surfaces-test-remediation-closure-rollup
  "E02/E03: 必检不合格自动生成的整改问题在交付工作区以只读闭环汇总可见, 关闭问题后由 pending 翻转为 resolved; 只读派生不放宽真实签核门控."
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx) test (test-record! ctx assembly "SIT")
        ws-read (fn [] (delivery/workspace *service* (actor 9501) id))
        test-row (fn [] (first (filterv (fn [t] (= (:id test) (:id t))) (:tests (ws-read)))))
        closure (fn [] (:test_remediation_closure (ws-read)))]
    ;; 登记前: 尚无整改问题, 逐条试验与顶层汇总均为 none / 不可用.
    (is (= "none" (:test_remediation_state (test-row))))
    (is (false? (:available (closure))))
    ;; 提交不合格必检结果 -> 自动生成一条 blocker 整改问题, 只读派生为 pending, 到期日不晚于今日故计入逾期.
    (let [failure (results! ctx test false) issue-id (get-in failure [:checks 0 :issue_id])]
      (is (string? issue-id))
      (let [row (test-row)]
        (is (= "pending" (:test_remediation_state row)))
        (is (= 1 (:test_remediation_total row)))
        (is (= 1 (:test_remediation_open row)))
        (is (= 0 (:test_remediation_closed row)))
        (is (= 1 (:test_remediation_overdue row))))
      (let [sum (closure)]
        (is (true? (:available sum)))
        (is (= 1 (:tests-with-remediation sum)))
        (is (= 1 (:pending-tests sum)))
        (is (= 0 (:resolved-tests sum)))
        (is (= 1 (:overdue-tests sum)))
        (is (= 1 (:issue-total sum)))
        (is (= 0 (:issue-closed sum)))
        (is (= 1 (:issue-open sum)))
        (is (= 0 (:closure-pct sum))))
      ;; 读模型不放宽门控: 问题未关闭时提交仍被真实 ready-test! 拦截, 与既有失败用例一致.
      (is (= 409 (error-status #(review! ctx :tests (:id test) :submit))))
      ;; 复验通过并独立关闭整改问题: 同一问题状态版本堆叠被 latest 去重(仍计一条), 逐条翻转 resolved, 闭环率 100.
      (results! ctx test true)
      (gov! id :issues :resolve issue-id {:resolution "已处理并复验" :evidence_ids [(:evidence ctx)] :reviewer_id 9502})
      (gov! 9502 id :issues :decision issue-id {:decision "approved" :reason "独立复验满足"})
      (let [row (test-row)]
        (is (= "resolved" (:test_remediation_state row)))
        (is (= 1 (:test_remediation_total row)))
        (is (= 0 (:test_remediation_open row)))
        (is (= 1 (:test_remediation_closed row)))
        (is (= 0 (:test_remediation_overdue row))))
      (let [sum (closure)]
        (is (= 1 (:resolved-tests sum)))
        (is (= 0 (:pending-tests sum)))
        (is (= 0 (:overdue-tests sum)))
        (is (= 1 (:issue-closed sum)))
        (is (= 0 (:issue-open sum)))
        (is (= 100 (:closure-pct sum)))))))


(deftest conditional-receipt-forces-service-resolution-before-acceptance
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx)]
    (approved-test! ctx assembly "SIT")
    (approved-test! ctx assembly "FAT")
    (let [shipment (shipment! ctx assembly)]
      (dispatch! ctx shipment)
      (let [body {:received_on (.toString (.minusDays (java.time.LocalDate/now) 1)) :receiver_name "现场接收人" :acceptance "conditional"
                  :evidence_ids [(:evidence ctx)] :exception_reason "运输后附件需更换" :owner_id 9501 :due_date "2026-10-09"}
            receipt (command! 9502 id :shipments :receipt (:id shipment) body)
            repeated (command! 9502 id :shipments :receipt (:id shipment) body)
            service-id (:receipt_service_id receipt)]
        (is (= "conditional" (:status receipt)))
        (is (= service-id (:receipt_service_id repeated)))
        (is (= 1 (count (:service_cases (delivery/workspace *service* (actor 9501) id)))))
        (is (= 409 (error-status #(accept! ctx shipment))))
        (command! id :service-cases :resolve service-id
                  {:resolution "更换附件并现场确认" :reviewer_id 9502 :evidence_ids [(:evidence ctx)]})
        (is (= 403 (error-status #(command! id :service-cases :decision service-id {:decision "approved" :reason "自行关闭"}))))
        (command! 9502 id :service-cases :decision service-id {:decision "approved" :reason "现场独立验证"})
        (is (= "received" (:status (accept! ctx shipment))))))))


(deftest applicability-cannot-skip-upstream-execution
  (let [id (project!)]
    (is (= 400 (error-status #(command! id :configuration :update nil
                                      {:required_stages ["quality"] :required_test_types ["SIT"] :reason "缺少前置"}))))
    (is (= 400 (error-status #(command! id :configuration :update nil
                                      {:required_stages ["shipment"] :required_test_types ["FAT"] :reason "缺少前置"}))))
    (is (some #(re-find #"装配" %) (:blockers (delivery/workspace *service* (actor 9501) id))))))


(deftest renewed-inspection-invalidates-previous-shipping-qualification
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx)]
    (approved-test! ctx assembly "SIT")
    (approved-test! ctx assembly "FAT")
    (let [shipment (shipment! ctx assembly)
          retest (command! id :tests :create nil
                           (merge (refs ctx) {:code "SIT-RETEST" :title "变更后的SIT复验" :assembly_id (:id assembly)
                                             :test_type "SIT" :owner_id 9501
                                             :criteria [{:code "Q-1" :title "变更后仍满足URS" :required true}]}))]
      (is (= 409 (error-status #(dispatch! ctx shipment))))
      (results! ctx retest false)
      (is (= 409 (error-status #(dispatch! ctx shipment))))
      (is (some #(re-find #"SIT" %) (:blockers (delivery/workspace *service* (actor 9501) id)))))))


(deftest reusable-scenario-preserves-all-business-approvals
  (let [ctx (context!) result (scenario/complete-delivery! *service* 9501 9502 (:id ctx)
                                                         (:task_id ctx) (:evidence ctx) (first (:requirement_ids ctx)))]
    (is (empty? (:blockers result)))
    (is (= #{"SIT" "FAT" "SAT"} (set (map :test_type (:tests result)))))
    (is (= "received" (:status (first (:shipments result)))))
    (is (every? #(not= (:submitted_by %) (:decided_by %)) (:tests result)))))


(deftest actual-dates-and-test-sequence-cannot-be-fabricated
  (let [ctx (context!) id (:id ctx) assembly (approved-assembly! ctx)
        fat (test-record! ctx assembly "FAT") today (java.time.LocalDate/now)
        future (str (.plusDays today 1))]
    (is (= 409 (error-status #(results! ctx fat true))))
    (approved-test! ctx assembly "SIT")
    (results! ctx fat true)
    (review! ctx :tests (:id fat) :submit)
    (let [shipment (shipment! ctx assembly)
          receipt {:received_on future :receiver_name "签收人" :acceptance "accepted" :evidence_ids [(:evidence ctx)]}]
      (is (= 400 (error-status #(command! id :shipments :dispatch (:id shipment)
                                        {:shipped_on future :tracking_no "未发生" :evidence_ids [(:evidence ctx)]}))))
      (dispatch! ctx shipment)
      (is (= 400 (error-status #(command! 9502 id :shipments :receipt (:id shipment) receipt))))
      (is (= 400 (error-status #(command! 9502 id :shipments :receipt (:id shipment)
                                        (assoc receipt :received_on (str (.minusDays today 3)))))))
      (is (= "received" (:status (accept! ctx shipment))))))


(defn- submit-material!
  "把物料申请提交给独立审批人,停在 in_review."
  [ctx rid]
  (command! (:id ctx) :material-requests :submit rid {:reviewer_id 9502 :evidence_ids [(:evidence ctx)]}))


(defn- decide-material!
  "由独立审批人批准或驳回物料申请."
  [ctx rid decision]
  (command! 9502 (:id ctx) :material-requests :decision rid {:decision decision :reason "独立核验物料申请"}))


(defn- submit-bom!
  "把 BOM 提交独立冻结审批,停在 in_review."
  [ctx rid]
  (command! (:id ctx) :boms :freeze rid {:reviewer_id 9502 :evidence_ids [(:evidence ctx)]}))


(defn- decide-bom!
  "由独立审批人冻结或驳回 BOM."
  [ctx rid decision]
  (command! 9502 (:id ctx) :boms :decision rid {:decision decision :reason "独立核验物料清单"}))


(defn- new-request!
  "创建一条物料申请并返回记录."
  [ctx code]
  (command! (:id ctx) :material-requests :create nil
            (merge (refs ctx) {:code code :title "审批闭环物料" :request_type "standard"
                               :owner_id 9501 :needed_on "2026-10-10"
                               :items [{:code (str code "-P") :name "部件" :quantity 1 :unit "个"}]})))


(deftest material-approval-summary-is-pure-derived-read-model
  (is (false? (:available (materials/material-approval-summary [] []))))
  (let [empty (materials/material-approval-summary [] [])]
    (is (zero? (:total empty)))
    (is (zero? (:closure-pct empty)))
    (is (= [{:key "request" :total 0 :approved 0 :in-review 0 :rejected 0 :draft 0 :closure-pct 0}
            {:key "bom" :total 0 :approved 0 :in-review 0 :rejected 0 :draft 0 :closure-pct 0}]
           (mapv #(select-keys % [:key :total :approved :in-review :rejected :draft :closure-pct]) (:by-source empty)))))
  (let [rows (materials/material-approval-summary
              [{:status "approved"} {:status "draft"} {:status "in_review"} {:status "rejected"}]
              [{:status "frozen"} {:status "partial"} {:status "ready"} {:status "draft"}])
        by (into {} (map (juxt :key identity) (:by-source rows)))]
    (is (true? (:available rows)))
    (is (= 8 (:total rows)))
    (is (= 4 (:approved rows)))
    (is (= 50 (:closure-pct rows)))
    (is (= 4 (:request-total rows)))
    (is (= 4 (:bom-total rows)))
    (is (= ["request" "bom"] (mapv :key (:by-source rows))))
    (is (= {:total 4 :approved 1 :in-review 1 :rejected 1 :draft 1 :closure-pct 25}
           (select-keys (get by "request") [:total :approved :in-review :rejected :draft :closure-pct])))
    (is (= {:total 4 :approved 3 :in-review 0 :rejected 0 :draft 1 :closure-pct 75}
           (select-keys (get by "bom") [:total :approved :in-review :rejected :draft :closure-pct])))))


(deftest material-approval-summary-survives-real-workspace-lifecycle
  (let [ctx (context!) id (:id ctx)
        draft-req (new-request! ctx "MR-D")
        review-req (new-request! ctx "MR-R")
        approved-req (new-request! ctx "MR-A")
        rejected-req (new-request! ctx "MR-X")
        _ (submit-material! ctx (:id review-req))
        _ (submit-material! ctx (:id approved-req))
        _ (decide-material! ctx (:id approved-req) "approved")
        _ (submit-material! ctx (:id rejected-req))
        _ (decide-material! ctx (:id rejected-req) "rejected")
        bom-draft (command! id :boms :create nil {:code "BOM-D" :title "受控草稿" :material_request_id (:id approved-req)})
        bom-frozen (command! id :boms :create nil {:code "BOM-F" :title "受控冻结" :material_request_id (:id approved-req)})
        bom-kitted (command! id :boms :create nil {:code "BOM-K" :title "受控齐套" :material_request_id (:id approved-req)})
        bom-rejected (command! id :boms :create nil {:code "BOM-X" :title "受控驳回" :material_request_id (:id approved-req)})
        _ (submit-bom! ctx (:id bom-frozen))
        _ (decide-bom! ctx (:id bom-frozen) "approved")
        _ (submit-bom! ctx (:id bom-kitted))
        _ (decide-bom! ctx (:id bom-kitted) "approved")
        _ (command! id :boms :kit (:id bom-kitted) {:items [{:code "MR-A-P" :available_quantity 1}] :evidence_ids [(:evidence ctx)]})
        _ (submit-bom! ctx (:id bom-rejected))
        _ (decide-bom! ctx (:id bom-rejected) "rejected")
        ws (delivery/workspace *service* (actor 9501) id)
        summary (:material_approval_summary ws)
        status-of (fn [rows code] (:status (first (filterv #(= code (:code %)) rows))))]
    (is (= "draft" (status-of (:material_requests ws) "MR-D")))
    (is (= "in_review" (status-of (:material_requests ws) "MR-R")))
    (is (= "approved" (status-of (:material_requests ws) "MR-A")))
    (is (= "rejected" (status-of (:material_requests ws) "MR-X")))
    (is (= "draft" (status-of (:boms ws) "BOM-D")))
    (is (= "frozen" (status-of (:boms ws) "BOM-F")))
    (is (= "ready" (status-of (:boms ws) "BOM-K")))
    (is (= "rejected" (status-of (:boms ws) "BOM-X")))
    (is (true? (:available summary)))
    (is (= 8 (:total summary)))
    (is (= 3 (:approved summary)))
    (is (= 1 (:in-review summary)))
    (is (= 2 (:rejected summary)))
    (is (= 2 (:draft summary)))
    (is (= 4 (:request-total summary)))
    (is (= 4 (:bom-total summary)))
    (is (= 38 (:closure-pct summary)))
    (is (= ["request" "bom"] (mapv :key (:by-source summary))))
    (is (= "not_configured" (:external_sync_status ws)))
    (is (some? (:kitting_rollup ws))))))


;; batch65 (mutator 方向: cover the uncovered): 物料数量/清单/节点门 (L14/L22/L39),
;; 齐套缺行与装配开工降级 (L102/L127), 发运范围与签收人登记冲突 (L19/L77),
;; 未知交付命令404 (L182). 注: delivery/closure-ready! (L91) 无任何调用者,
;; 与 chain/cancel! 同类属未接线公开能力, 按惯例不删不测.
(deftest material-node-kitting-shipment-and-command-guards
  (let [ctx (context!) id (:id ctx)
        item {:code "X-1" :name "部件" :quantity 1 :unit "个"}]
    (testing "物料数量必须为整数400"
      (is (= [400 "物料数量必须为规定范围内整数"]
             (error-info #(command! id :material-requests :create nil
                                    (merge (refs ctx) {:code "MQ-1" :title "小数数量" :request_type "standard"
                                                       :owner_id 9501 :needed_on "2026-10-01"
                                                       :items [(assoc item :quantity 1.5)]}))))))
    (testing "物料清单需要1到200行400"
      (is (= [400 "物料清单需要1到200行"]
             (error-info #(command! id :material-requests :create nil
                                    (merge (refs ctx) {:code "MQ-2" :title "空清单" :request_type "standard"
                                                       :owner_id 9501 :needed_on "2026-10-01" :items []}))))))
    (testing "结构节点不存在404 (先于清单校验)"
      (is (= [404 "结构节点不存在或不属于本项目"]
             (error-info #(command! id :material-requests :create nil
                                    (merge (refs ctx) {:code "MQ-3" :title "坏节点" :request_type "standard"
                                                       :owner_id 9501 :needed_on "2026-10-01"
                                                       :items [item] :node_id "NO-SUCH-NODE"}))))))
    (testing "齐套缺行400, 装配开工后降低齐套409, 交检批准后进入发运链"
      (let [bom (frozen-bom! ctx)]
        (is (= [400 "齐套记录必须完整对应冻结BOM清单"]
               (error-info #(command! id :boms :kit (:id bom)
                                      {:items [{:code "M-1" :available_quantity 1}]
                                       :evidence_ids [(:evidence ctx)]}))))
        (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 2}
                                                   {:code "M-2" :available_quantity 4}]
                                          :evidence_ids [(:evidence ctx)]})
        (let [assembly (command! id :assemblies :create nil
                                 (merge (refs ctx) {:code "A-KIT" :title "开工降级校验"
                                                    :bom_id (:id bom) :owner_id 9501}))]
          (command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]})
          (is (= [409 "装配已经开工,不得降低已使用BOM的齐套状态"]
                 (error-info #(command! id :boms :kit (:id bom)
                                        {:items [{:code "M-1" :available_quantity 1}
                                                 {:code "M-2" :available_quantity 4}]
                                         :evidence_ids [(:evidence ctx)]}))))
          (review! ctx :assemblies (:id assembly) :submit)
          (approved-test! ctx assembly "SIT")
          (approved-test! ctx assembly "FAT")
          (testing "发运范围必须非空且不重复400"
            (is (= [400 "发运范围必须为1到50个不重复装配对象"]
                   (error-info #(command! id :shipments :create nil
                                          (merge (refs ctx) {:code "SH-EMPTY" :title "空范围" :assembly_ids []
                                                             :consignee "现场" :delivery_address "客户"
                                                             :planned_date "2026-10-05" :reviewer_id 9502})))))
            (is (= [400 "发运范围必须为1到50个不重复装配对象"]
                   (error-info #(command! id :shipments :create nil
                                          (merge (refs ctx) {:code "SH-DUP" :title "重复范围"
                                                             :assembly_ids ["fake-a" "fake-a"]
                                                             :consignee "现场" :delivery_address "客户"
                                                             :planned_date "2026-10-05" :reviewer_id 9502}))))))
          (testing "签收验证人不能同时登记实际发运409"
            ;; 审核人须为可写成员: 9503 提交, 9501 (审核人) 放行后亲自登记发运命中 L77
            (let [shipment (command! 9503 id :shipments :create nil
                                     (merge (refs ctx) {:code "SHIP-SELF" :title "自审发运"
                                                        :assembly_ids [(:id assembly)]
                                                        :consignee "现场" :delivery_address "客户"
                                                        :planned_date "2026-10-05" :reviewer_id 9501}))]
              (command! 9503 id :shipments :submit (:id shipment) {:evidence_ids [(:evidence ctx)]})
              (command! 9501 id :shipments :decision (:id shipment)
                        {:decision "approved" :reason "独立放行"})
              (is (= [409 "指定签收验证人不能同时登记实际发运"]
                     (error-info #(command! 9501 id :shipments :dispatch (:id shipment)
                                            {:shipped_on (.toString (.minusDays (java.time.LocalDate/now) 2))
                                             :tracking_no "SELF-RECORD" :evidence_ids [(:evidence ctx)]})))))))))
    (testing "未知交付命令404"
      (is (= [404 "交付命令不存在"]
             (error-info #(command! id :bogus :create nil {})))))))
