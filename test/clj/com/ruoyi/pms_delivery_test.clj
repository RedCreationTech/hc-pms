(ns com.ruoyi.pms-delivery-test
  "真实数据库上的治理审批,证据,整批导入和跨模块闭环测试."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.delivery :as delivery]
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
      (is (= "received" (:status (accept! ctx shipment)))))))
