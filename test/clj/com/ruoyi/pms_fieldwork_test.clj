(ns com.ruoyi.pms-fieldwork-test
  "工勘 (B07), 装配步骤明细 (E01), 发货前本地条件 (E04), 交底时限 (E07), 现场任务 (E08/E09),
   齐套多层卷积 (D06), 包材申请 (D03), 阻断关口 (B11/B13), DQ (B08) 与单机局部暂停 (B16) 的真实数据库测试."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.planning :as planning]
            [com.ruoyi.domain.pms.queries :as queries]
            [com.ruoyi.domain.pms.service :as pms]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [java.nio.file Files]
           [java.time LocalDate]
           [java.util UUID]))


(def ^:dynamic *service* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9600,'Fieldwork test','pms-fieldwork-test',60,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9600,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (doseq [id [9601 9602 9603]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "fieldwork-test-" id) (str "现场测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,9600)" id])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-fieldwork-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (seed! db)
      (binding [*service* {:db db :query-fn (query-function db)}] (f))
      (finally (when file (Files/deleteIfExists file))))))


(use-fixtures :once database-fixture)


(defn- actor [uid] (pms/actor *service* {:user-id uid}))


(defn- error-status
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- version [id] (:version (pms/project *service* (actor 9601) id)))


(defn- gov!
  ([id resource action rid body] (gov! 9601 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- command!
  ([id resource action rid body] (command! 9601 id resource action rid body))
  ([uid id resource action rid body]
   (:result (delivery/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- transition!
  [id status]
  (pms/transition-project! *service* (actor 9601) id {:version (version id) :status status :reason "现场集成测试"}))


(defn- approve!
  [id resource rid]
  (gov! id resource :submit rid {:reviewer_id 9602})
  (gov! 9602 id resource :decision rid {:decision "approved" :reason "独立审查通过"}))


(defn- context!
  "建立带子项目/单机节点, 配置了工勘/发货前条件/交底期限的执行中项目."
  [config]
  (let [project (pms/create-project! *service* (actor 9601)
                                     {:project_no (str "FW-" (subs (str (UUID/randomUUID)) 0 8)) :name "现场闭环验证"
                                      :customer "本地测试" :contract_no "FW-TEST" :project_type "equipment"
                                      :manager_id 9601 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id project)
        _ (pms/set-member! *service* (actor 9601) id {:user_id 9602 :role "viewer"})
        _ (pms/set-member! *service* (actor 9601) id {:user_id 9603 :role "editor"})
        main (first (filter #(= "main" (:node_type %)) (:rows (pms/nodes *service* (actor 9601) id))))
        sub (pms/create-node! *service* (actor 9601) id {:parent_id (:node_id main) :node_type "sub" :node_code "U1" :name "主机单元"})
        machine (pms/create-node! *service* (actor 9601) id {:parent_id (:node_id sub) :node_type "machine" :node_code "M1" :name "主机#1"})
        document (gov! id :documents :create nil {:code "FW-EV" :title "现场证据" :filename "fw.txt" :content "现场记录"})
        req (gov! id :requirements :create nil {:code "URS-1" :text "现场交付须验证" :category "验收" :priority "required" :owner_id 9601})
        task (:result (planning/create-task! *service* (actor 9601) id
                                             {:version (version id) :wbs_code "D-1" :name "单机交付任务" :owner_id 9601
                                              :start_date "2026-09-22" :duration_days 1 :node_id (:node_id machine)}))
        charter (gov! id :charters :create nil {:title "章程" :objective "目标" :scope "范围" :success_criteria "准则" :sponsor_id 9603})
        template (gov! id :gate-templates :create nil {:code "EXEC-G" :title "执行前确认" :stage "execution" :required true
                                                      :checks [{:code "C" :title "证据齐全" :required true}]})
        gate (gov! id :gates :create nil {:template_id (:id template) :title "执行确认" :reviewer_id 9602})]
    (when config (command! id :configuration :update nil (merge {:required_stages ["materials" "assembly" "quality" "shipment"]
                                                                :required_test_types ["SIT" "FAT" "SAT"] :reason "测试配置"} config)))
    (approve! id :charters (:id charter))
    (gov! id :gates :checks (:id gate) {:checks [{:code "C" :passed true :evidence_ids [(:id document)]}]})
    (gov! id :gates :submit (:id gate) {})
    (gov! 9602 id :gates :decision (:id gate) {:decision "approved" :reason "独立验证"})
    (transition! id "initiated")
    (transition! id "planning")
    (let [baseline (:result (planning/submit-plan! *service* (actor 9601) id {:version (version id) :comment "提交"}))]
      (planning/review-plan! *service* (actor 9602) id (:baseline_id baseline) {:version (version id) :decision "approved" :comment "独立确认"}))
    (transition! id "execution")
    {:id id :evidence (:id document) :task_id (:task_id task) :requirement_ids [(:id req)]
     :sub sub :machine machine :main main}))


(defn- refs [ctx] (select-keys ctx [:task_id :requirement_ids]))


(defn- review!
  [ctx resource rid action]
  (let [id (:id ctx) evidence {:evidence_ids [(:evidence ctx)]}]
    (command! id resource action rid (cond-> evidence (not= :shipments resource) (assoc :reviewer_id 9602)))
    (command! 9602 id resource :decision rid {:decision "approved" :reason "独立核实证据"})))


(defn- frozen-bom!
  [ctx]
  (let [id (:id ctx)
        material (command! id :material-requests :create nil
                           (merge (refs ctx) {:code "MR-1" :title "长周期件预投" :request_type "long_lead" :owner_id 9601 :needed_on "2026-10-01"
                                             :items [{:code "M-1" :name "执行器" :quantity 2 :unit "个"} {:code "M-2" :name "连接线" :quantity 4 :unit "根"}]}))]
    (review! ctx :material-requests (:id material) :submit)
    (let [bom (command! id :boms :create nil {:code "BOM-1" :title "受控配置" :material_request_id (:id material)})]
      (review! ctx :boms (:id bom) :freeze))))


(defn- workspace [id] (delivery/workspace *service* (actor 9601) id))


(defn- today-minus [days] (str (.minusDays (LocalDate/now) days)))


(deftest survey-visits-are-controlled-and-block-closure
  (let [ctx (context! {:required_survey_visits 1}) id (:id ctx)]
    (is (some #(re-find #"工勘" %) (:blockers (workspace id))))
    (let [survey (command! id :surveys :create nil {:code "SV-1" :title "首次工勘" :visit_no 1 :owner_id 9601
                                                    :planned_date "2026-10-10" :deliverable "现场勘察报告" :task_id (:task_id ctx)})]
      (is (= "draft" (:status survey)))
      (is (= 409 (error-status #(command! id :surveys :create nil {:code "SV-1B" :title "重复次序" :visit_no 1 :owner_id 9601 :planned_date "2026-10-11" :deliverable "x"}))))
      (is (= 400 (error-status #(command! id :surveys :create nil {:code "SV-X" :title "非法次序" :visit_no 0 :owner_id 9601 :planned_date "2026-10-11" :deliverable "x"}))))
      ;; 实际日期不得晚于今天; 提交后由独立审核人确认.
      (is (= 400 (error-status #(command! id :surveys :submit (:id survey) {:reviewer_id 9602 :evidence_ids [(:evidence ctx)] :actual_date "2099-01-01"}))))
      (let [submitted (command! id :surveys :submit (:id survey) {:reviewer_id 9602 :evidence_ids [(:evidence ctx)] :actual_date (today-minus 1) :findings "地基满足要求"})]
        (is (= "in_review" (:status submitted)))
        (is (= (today-minus 1) (:actual_date submitted)))
        (is (= 403 (error-status #(command! 9603 id :surveys :decision (:id survey) {:decision "approved" :reason "冒名"}))))
        (is (= "approved" (:status (command! 9602 id :surveys :decision (:id survey) {:decision "approved" :reason "确认交付物"})))))
      (is (not-any? #(re-find #"工勘" %) (:blockers (workspace id))))
      (is (= 1 (count (get (:task_links (workspace id)) (:task_id ctx))))))))


(deftest packaging-request-requires-spec-and-links-back-to-task
  (let [ctx (context! nil) id (:id ctx)]
    (is (= 400 (error-status #(command! id :material-requests :create nil
                                        (merge (refs ctx) {:code "PK-0" :title "包材" :request_type "packaging" :owner_id 9601 :needed_on "2026-10-01"
                                                           :items [{:code "BOX" :name "木箱" :quantity 1 :unit "个"}]})))))
    (let [request (command! id :material-requests :create nil
                            (merge (refs ctx) {:code "PK-1" :title "包材申请" :request_type "packaging" :owner_id 9601 :needed_on "2026-10-01"
                                               :packaging_spec "出口木箱 2000x1200x1500 熏蒸" :node_id (:node_id (:machine ctx))
                                               :items [{:code "BOX" :name "木箱" :quantity 1 :unit "个"}]}))]
      (is (= "packaging" (:request_type request)))
      (is (= (:node_id (:machine ctx)) (:node_id request)))
      (review! ctx :material-requests (:id request) :submit)
      (let [links (get (:task_links (workspace id)) (:task_id ctx))]
        (is (some #(and (= "PK-1" (:code %)) (= "approved" (:status %))) links))))))


(deftest kitting-gate-blocks-assembly-start-and-steps-are-monotonic
  (let [ctx (context! nil) id (:id ctx) bom (frozen-bom! ctx)]
    (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 2} {:code "M-2" :available_quantity 4}] :evidence_ids [(:evidence ctx)]})
    (let [kitting (gov! id :gate-templates :from-catalog nil {:gate_type "kitting"})
          assembly (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-1" :title "执行装配" :bom_id (:id bom) :owner_id 9601}))]
      ;; B11: 齐套Gate未通过 -> 装配开工被阻断.
      (is (= 409 (error-status #(command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]}))))
      (let [gate (gov! id :gates :create nil {:template_id (:id kitting) :title "齐套放行" :reviewer_id 9602})]
        (gov! id :gates :checks (:id gate) {:checks [{:code "KIT-1" :passed true :evidence_ids [(:evidence ctx)]}
                                                   {:code "KIT-2" :passed true :evidence_ids [(:evidence ctx)]}]})
        (gov! id :gates :submit (:id gate) {})
        (gov! 9602 id :gates :decision (:id gate) {:decision "approved" :reason "齐套确认"}))
      (is (= "in_progress" (:status (command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]}))))
      ;; E01: 上岛 -> 装配 -> 单机交检 ... 顺序单调, 日期不倒退, 不得晚于今天.
      (is (= 409 (error-status #(command! id :assemblies :steps (:id assembly) {:step "assembling" :actual_date (today-minus 3)}))))
      (is (= 400 (error-status #(command! id :assemblies :steps (:id assembly) {:step "on_island" :actual_date "2099-01-01"}))))
      (let [first-step (command! id :assemblies :steps (:id assembly) {:step "on_island" :actual_date (today-minus 3) :note "设备上岛"})]
        (is (= 1 (count (:steps first-step))))
        (is (= 409 (error-status #(command! id :assemblies :steps (:id assembly) {:step "on_island" :actual_date (today-minus 3)}))))
        (is (= 400 (error-status #(command! id :assemblies :steps (:id assembly) {:step "assembling" :actual_date (today-minus 5)}))))
        (command! id :assemblies :steps (:id assembly) {:step "assembling" :actual_date (today-minus 2)})
        (let [model (first (filter #(= (:id assembly) (:id %)) (:assemblies (workspace id))))]
          (is (= 2 (:step_count model)))
          (is (= "assembling" (:current_step model)))
          (is (= "unit_inspection" (:next_step model)))
          (is (= "not_configured" (:mes_sync_status model))))))))


(deftest kitting-rollup-aggregates-by-structure-node-with-shortages
  (let [ctx (context! nil) id (:id ctx) bom (frozen-bom! ctx)]
    (let [before (:kitting_rollup (workspace id))]
      (is (= 1 (:bom_count before)))
      (is (= 0 (:kit_percent before)))
      (is (= 2 (count (:shortages before))))
      (is (false? (:kit_recorded (first (:shortages before))))))
    (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 2} {:code "M-2" :available_quantity 3}] :evidence_ids [(:evidence ctx)]})
    (let [rollup (:kitting_rollup (workspace id))
          machine (first (filter #(= (:node_id (:machine ctx)) (:node_id %)) (:nodes rollup)))
          sub (first (filter #(= (:node_id (:sub ctx)) (:node_id %)) (:nodes rollup)))
          main (first (filter #(= "main" (:node_type %)) (:nodes rollup)))]
      (is (= 50 (:kit_percent rollup)))
      (is (= 2 (:required_lines rollup)))
      (is (= 1 (:complete_lines rollup)))
      ;; BOM 关联的任务映射到单机 M1, 子项目 U1 与主项目逐层卷积同一分子分母.
      (is (= 50 (:kit_percent machine)))
      (is (= 50 (:kit_percent sub)))
      (is (= 50 (:kit_percent main)))
      (is (= 1 (:shortage_lines machine)))
      (let [shortage (first (:shortages rollup))]
        (is (= "M-2" (:code shortage)))
        (is (= 1 (:shortage shortage)))
        (is (= (:node_id (:machine ctx)) (:node_id shortage)))
        (is (true? (:kit_recorded shortage)))))))


(defn- shipped-shipment!
  "完成装配, SIT/FAT 与发运放行, 返回发运单."
  [ctx]
  (let [id (:id ctx) bom (frozen-bom! ctx)]
    (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 2} {:code "M-2" :available_quantity 4}] :evidence_ids [(:evidence ctx)]})
    (let [assembly (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-1" :title "执行装配" :bom_id (:id bom) :owner_id 9601}))]
      (command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]})
      (review! ctx :assemblies (:id assembly) :submit)
      (doseq [type ["SIT" "FAT"]]
        (let [test (command! id :tests :create nil (merge (refs ctx) {:code (str type "-1") :title (str type "验证") :assembly_id (:id assembly)
                                                                     :test_type type :owner_id 9601
                                                                     :criteria [{:code "Q-1" :title "动作满足URS" :required true}]}))]
          (command! id :tests :results (:id test) {:checks [{:code "Q-1" :passed true :actual "通过" :evidence_ids [(:evidence ctx)]}] :due_date "2026-10-03"})
          (review! ctx :tests (:id test) :submit)))
      (command! id :shipments :create nil (merge (refs ctx) {:code "SHIP-1" :title "设备发运" :assembly_ids [(:id assembly)]
                                                            :consignee "现场接收团队" :delivery_address "客户指定地址"
                                                            :planned_date "2026-10-05" :reviewer_id 9602})))))


(deftest preship-conditions-handover-deadline-and-site-tasks
  (let [ctx (context! {:pre_ship_conditions ["warehouse_in" "payment"] :handover_deadline_days 2 :site_lag_days 1 :handover_required true})
        id (:id ctx) shipment (shipped-shipment! ctx)]
    ;; E04: 未确认入库/提货款 -> 发运提交被拒; 读模型给出条件清单.
    (is (= 409 (error-status #(command! id :shipments :submit (:id shipment) {:evidence_ids [(:evidence ctx)]}))))
    (let [checklist (:preship_checklist (first (filter #(= (:id shipment) (:id %)) (:shipments (workspace id)))))]
      (is (= 4 (count checklist)))
      (is (true? (:satisfied (first (filter #(= "fat" (:code %)) checklist)))))
      (is (false? (:satisfied (first (filter #(= "payment" (:code %)) checklist))))))
    (command! id :shipments :conditions (:id shipment) {:warehouse_in_confirmed true :warehouse_note "WMS入库单 IN-001"
                                                        :payment_confirmed true :payment_note "财务确认提货款到账" :evidence_ids [(:evidence ctx)]})
    (is (= "local_fact" (:source (:preconditions (first (filter #(= (:id shipment) (:id %)) (:shipments (workspace id))))))))
    (review! ctx :shipments (:id shipment) :submit)
    ;; E07: 发运登记自动生成交底任务, 截止期 = 发运日 + 2 天.
    (let [shipped-on (today-minus 4)
          dispatched (command! id :shipments :dispatch (:id shipment) {:shipped_on shipped-on :tracking_no "FW-TRACK" :evidence_ids [(:evidence ctx)]})
          handover (first (:handovers (workspace id)))]
      (is (= "shipped" (:status dispatched)))
      (is (some? handover))
      (is (= "open" (:status handover)))
      (is (= (str (.plusDays (LocalDate/parse shipped-on) 2)) (:deadline handover)))
      (is (true? (:handover_overdue handover)))
      (is (neg? (:handover_days_left handover)))
      (is (some #(re-find #"交底" %) (:blockers (workspace id))))
      (is (= 409 (error-status #(command! id :shipments :dispatch (:id shipment) {:shipped_on shipped-on :tracking_no "X" :evidence_ids [(:evidence ctx)]}))))
      (is (= 409 (error-status #(command! id :handovers :complete (:id handover) {:document_ids [] :completed_on (today-minus 1)}))))
      (is (= 400 (error-status #(command! id :handovers :complete (:id handover) {:document_ids [(:evidence ctx)] :completed_on (today-minus 6)}))))
      (let [completed (command! id :handovers :complete (:id handover) {:document_ids [(:evidence ctx)] :checklist_note "交底清单 V1" :completed_on (today-minus 1)})]
        (is (= "closed" (:status completed)))
        (is (true? (:completed_late completed)))
        (is (= "not_configured" (:crm_sync_status completed))))
      (is (not-any? #(re-find #"交底" %) (:blockers (workspace id))))
      ;; E08/E09: 交底完成后按 1 天滞后生成 4 个现场任务, 顺序执行不可乱序.
      (let [tasks (sort-by :sequence (:site_tasks (workspace id)))]
        (is (= ["positioning" "installation" "commissioning" "sat"] (mapv :task_key tasks)))
        (is (= (str (.plusDays (LocalDate/parse (today-minus 1)) 1)) (:planned_start (first tasks))))
        (is (every? #(= "not_configured" (:erp_dispatch_status %)) tasks))
        (is (= 409 (error-status #(command! id :site-tasks :start (:id (second tasks)) {:actual_start (today-minus 0)}))))
        (is (= "in_progress" (:status (command! id :site-tasks :start (:id (first tasks)) {:actual_start (today-minus 0)}))))
        (is (= 400 (error-status #(command! id :site-tasks :complete (:id (first tasks)) {:actual_end (today-minus 1) :evidence_ids [(:evidence ctx)]}))))
        (is (= "closed" (:status (command! id :site-tasks :complete (:id (first tasks)) {:actual_end (today-minus 0) :evidence_ids [(:evidence ctx)] :result "定位完成"}))))
        (is (= "in_progress" (:status (command! id :site-tasks :start (:id (second tasks)) {:actual_start (today-minus 0)}))))))))


(deftest dq-checklist-deliverables-and-stale-flag
  (let [ctx (context! nil) id (:id ctx)
        doc (gov! id :documents :create nil {:code "DQ-DOC" :title "DQ设计文件" :filename "dq.txt" :content "DQ v1"})
        dq (gov! id :dqs :create nil {:code "DQ-1" :title "主机DQ编制" :owner_id 9601
                                      :checklist [{:code "D1" :title "设计输入完整" :required true} {:code "D2" :title "图纸编号规范" :required false}]
                                      :deliverable_ids [(:id doc)] :task_id (:task_id ctx)})]
    (is (= "draft" (:status dq)))
    (is (= 409 (error-status #(gov! id :dqs :create nil {:code "DQ-1" :title "重复" :owner_id 9601 :checklist [{:code "D1" :title "x"}]}))))
    ;; 必需项未通过 -> 仍为草稿, 提交被拒.
    (gov! id :dqs :checks (:id dq) {:results [{:code "D1" :passed false :note "缺输入"} {:code "D2" :passed true :note ""}]})
    (is (= 409 (error-status #(gov! id :dqs :submit (:id dq) {:reviewer_id 9602}))))
    (is (= "ready" (:status (gov! id :dqs :checks (:id dq) {:results [{:code "D1" :passed true :note "已补齐"} {:code "D2" :passed true :note ""}]}))))
    (is (= "in_review" (:status (gov! id :dqs :submit (:id dq) {:reviewer_id 9602}))))
    (is (= 403 (error-status #(gov! 9603 id :dqs :decision (:id dq) {:decision "approved" :reason "冒名"}))))
    (is (= "approved" (:status (gov! 9602 id :dqs :decision (:id dq) {:decision "approved" :reason "签认"}))))
    (let [model (first (:dqs (gov/workspace *service* (actor 9601) id)))]
      (is (false? (:dq_stale model)))
      (is (= 2 (:dq_passed model))))
    ;; 交付件出现新版本 -> 只读标注签认依据失效.
    (gov! id :documents :revisions (:id doc) {:code "DQ-DOC" :title "DQ设计文件" :filename "dq.txt" :content "DQ v2"})
    (let [model (first (:dqs (gov/workspace *service* (actor 9601) id)))]
      (is (true? (:dq_stale model)))
      (is (= 1 (:dq_stale_count model))))))


(deftest node-pause-blocks-feedback-and-resumes-with-audit
  (let [ctx (context! nil) id (:id ctx)
        other (:result (planning/create-task! *service* (actor 9601) id {:version (version id) :wbs_code "D-2" :name "主计划任务" :owner_id 9601 :start_date "2026-09-22" :duration_days 1}))]
    (is (= 400 (error-status #(gov! id :node-pauses :create nil {:node_id (:node_id (:main ctx)) :reason "主项目"}))))
    (let [pause (gov! id :node-pauses :create nil {:node_id (:node_id (:sub ctx)) :reason "客户暂缓主机单元"})]
      (is (= "active" (:status pause)))
      (is (= "execution" (:project_status_at_pause pause)))
      (is (= 409 (error-status #(gov! id :node-pauses :create nil {:node_id (:node_id (:sub ctx)) :reason "重复"}))))
      ;; 子项目暂停后, 其下单机任务禁止反馈; 主计划任务不受影响.
      (is (= 409 (error-status #(planning/task-feedback! *service* (actor 9601) id (:task_id ctx)
                                                         {:version (version id) :status "in_progress" :percent_complete 10 :remaining_days 1}))))
      (is (some? (planning/task-feedback! *service* (actor 9601) id (:task_id other)
                                          {:version (version id) :status "in_progress" :percent_complete 10 :remaining_days 1})))
      (let [plan (planning/read-plan *service* (actor 9601) id)]
        (is (true? (:node_paused (first (filter #(= (:task_id ctx) (:task_id %)) (:tasks plan))))))
        (is (false? (:node_paused (first (filter #(= (:task_id other) (:task_id %)) (:tasks plan))))))
        (is (= 2 (count (:paused_node_ids plan)))))
      (let [resumed (gov! id :node-pauses :resume (:id pause) {:impact_note "顺延两周, 重排单机计划"})]
        (is (= "closed" (:status resumed)))
        (is (= "顺延两周, 重排单机计划" (:impact_note resumed)))
        (is (= 409 (error-status #(gov! id :node-pauses :resume (:id pause) {:impact_note "again"})))))
      (is (some? (planning/task-feedback! *service* (actor 9601) id (:task_id ctx)
                                          {:version (version id) :status "in_progress" :percent_complete 20 :remaining_days 1}))))))


(deftest kickoff-meeting-requires-pre-read-pack-and-baseline
  (let [ctx (context! nil) id (:id ctx)
        baseline (first (:baselines (planning/read-plan *service* (actor 9601) id)))]
    (is (= 409 (error-status #(gov! id :meetings :create nil {:title "启动会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9601] :meeting_type "kickoff"}))))
    (is (= 409 (error-status #(gov! id :meetings :create nil {:title "启动会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9601] :meeting_type "kickoff" :material_ids [(:evidence ctx)]}))))
    (is (= 404 (error-status #(gov! id :meetings :create nil {:title "启动会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9601] :meeting_type "kickoff" :material_ids [(:evidence ctx)] :baseline_id "nope"}))))
    (is (= 400 (error-status #(gov! id :meetings :create nil {:title "会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9601] :meeting_type "party"}))))
    (let [meeting (gov! id :meetings :create nil {:title "项目启动会" :held_on "2026-09-23" :minutes "确认主计划与售前资料" :attendee_ids [9601 9603]
                                                  :meeting_type "kickoff" :material_ids [(:evidence ctx)] :baseline_id (:baseline_id baseline)})]
      (is (= "kickoff" (:meeting_type meeting)))
      (is (= (:baseline_id baseline) (:baseline_id meeting)))
      (is (= "approved" (:baseline_status meeting)))
      (let [model (first (filter #(= (:id meeting) (:id %)) (:meetings (gov/workspace *service* (actor 9601) id))))]
        (is (false? (:baseline_stale model)))
        (is (= "approved" (:baseline_current_status model)))))
    (is (= "regular" (:meeting_type (gov! id :meetings :create nil {:title "例会" :held_on "2026-09-24" :minutes "纪要" :attendee_ids [9601]}))))))
