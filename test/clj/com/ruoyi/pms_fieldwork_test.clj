(ns com.ruoyi.pms-fieldwork-test
  "工勘 (B07), 装配步骤明细 (E01), 发货前本地条件 (E04), 交底时限 (E07), 现场任务 (E08/E09),
   齐套多层卷积 (D06), 包材申请 (D03), 阻断关口 (B11/B13), DQ (B08) 与单机局部暂停 (B16) 的真实数据库测试."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.domain.pms.delivery.fieldwork :as fieldwork]
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
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9640,'Fieldwork test','pms-fieldwork-test',60,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9640,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (doseq [id [9641 9642 9643]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "fieldwork-test-" id) (str "现场测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,9640)" id])))


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


(defn- version [id] (:version (pms/project *service* (actor 9641) id)))


(defn- gov!
  ([id resource action rid body] (gov! 9641 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- command!
  ([id resource action rid body] (command! 9641 id resource action rid body))
  ([uid id resource action rid body]
   (:result (delivery/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- transition!
  [id status]
  (pms/transition-project! *service* (actor 9641) id {:version (version id) :status status :reason "现场集成测试"}))


(defn- approve!
  [id resource rid]
  (gov! id resource :submit rid {:reviewer_id 9642})
  (gov! 9642 id resource :decision rid {:decision "approved" :reason "独立审查通过"}))


(defn- context!
  "建立带子项目/单机节点, 配置了工勘/发货前条件/交底期限的执行中项目."
  [config]
  (let [project (pms/create-project! *service* (actor 9641)
                                     {:project_no (str "FW-" (subs (str (UUID/randomUUID)) 0 8)) :name "现场闭环验证"
                                      :customer "本地测试" :contract_no "FW-TEST" :project_type "equipment"
                                      :manager_id 9641 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id project)
        _ (pms/set-member! *service* (actor 9641) id {:user_id 9642 :role "viewer"})
        _ (pms/set-member! *service* (actor 9641) id {:user_id 9643 :role "editor"})
        main (first (filter #(= "main" (:node_type %)) (:rows (pms/nodes *service* (actor 9641) id))))
        sub (pms/create-node! *service* (actor 9641) id {:parent_id (:node_id main) :node_type "sub" :node_code "U1" :name "主机单元"})
        machine (pms/create-node! *service* (actor 9641) id {:parent_id (:node_id sub) :node_type "machine" :node_code "M1" :name "主机#1"})
        document (gov! id :documents :create nil {:code "FW-EV" :title "现场证据" :filename "fw.txt" :content "现场记录"})
        req (gov! id :requirements :create nil {:code "URS-1" :text "现场交付须验证" :category "验收" :priority "required" :owner_id 9641})
        task (:result (planning/create-task! *service* (actor 9641) id
                                             {:version (version id) :wbs_code "D-1" :name "单机交付任务" :owner_id 9641
                                              :start_date "2026-09-22" :duration_days 1 :node_id (:node_id machine)}))
        charter (gov! id :charters :create nil {:title "章程" :objective "目标" :scope "范围" :success_criteria "准则" :sponsor_id 9643})
        template (gov! id :gate-templates :create nil {:code "EXEC-G" :title "执行前确认" :stage "execution" :required true
                                                      :checks [{:code "C" :title "证据齐全" :required true}]})
        gate (gov! id :gates :create nil {:template_id (:id template) :title "执行确认" :reviewer_id 9642})]
    (when config (command! id :configuration :update nil (merge {:required_stages ["materials" "assembly" "quality" "shipment"]
                                                                :required_test_types ["SIT" "FAT" "SAT"] :reason "测试配置"} config)))
    (approve! id :charters (:id charter))
    (gov! id :gates :checks (:id gate) {:checks [{:code "C" :passed true :evidence_ids [(:id document)]}]})
    (gov! id :gates :submit (:id gate) {})
    (gov! 9642 id :gates :decision (:id gate) {:decision "approved" :reason "独立验证"})
    (transition! id "initiated")
    (transition! id "planning")
    (let [baseline (:result (planning/submit-plan! *service* (actor 9641) id {:version (version id) :comment "提交"}))]
      (planning/review-plan! *service* (actor 9642) id (:baseline_id baseline) {:version (version id) :decision "approved" :comment "独立确认"}))
    (transition! id "execution")
    {:id id :evidence (:id document) :task_id (:task_id task) :requirement_ids [(:id req)]
     :sub sub :machine machine :main main}))


(defn- refs [ctx] (select-keys ctx [:task_id :requirement_ids]))


(defn- review!
  [ctx resource rid action]
  (let [id (:id ctx) evidence {:evidence_ids [(:evidence ctx)]}]
    (command! id resource action rid (cond-> evidence (not= :shipments resource) (assoc :reviewer_id 9642)))
    (command! 9642 id resource :decision rid {:decision "approved" :reason "独立核实证据"})))


(defn- frozen-bom!
  [ctx]
  (let [id (:id ctx)
        material (command! id :material-requests :create nil
                           (merge (refs ctx) {:code "MR-1" :title "长周期件预投" :request_type "long_lead" :owner_id 9641 :needed_on "2026-10-01"
                                             :items [{:code "M-1" :name "执行器" :quantity 2 :unit "个"} {:code "M-2" :name "连接线" :quantity 4 :unit "根"}]}))]
    (review! ctx :material-requests (:id material) :submit)
    (let [bom (command! id :boms :create nil {:code "BOM-1" :title "受控配置" :material_request_id (:id material)})]
      (review! ctx :boms (:id bom) :freeze))))


(defn- workspace [id] (delivery/workspace *service* (actor 9641) id))


(defn- today-minus [days] (str (.minusDays (LocalDate/now) days)))


(deftest survey-visits-are-controlled-and-block-closure
  (let [ctx (context! {:required_survey_visits 1}) id (:id ctx)]
    (is (some #(re-find #"工勘" %) (:blockers (workspace id))))
    (let [survey (command! id :surveys :create nil {:code "SV-1" :title "首次工勘" :visit_no 1 :owner_id 9641
                                                    :planned_date "2026-10-10" :deliverable "现场勘察报告" :task_id (:task_id ctx)})]
      (is (= "draft" (:status survey)))
      (is (= 409 (error-status #(command! id :surveys :create nil {:code "SV-1B" :title "重复次序" :visit_no 1 :owner_id 9641 :planned_date "2026-10-11" :deliverable "x"}))))
      (is (= 400 (error-status #(command! id :surveys :create nil {:code "SV-X" :title "非法次序" :visit_no 0 :owner_id 9641 :planned_date "2026-10-11" :deliverable "x"}))))
      ;; 实际日期不得晚于今天; 提交后由独立审核人确认.
      (is (= 400 (error-status #(command! id :surveys :submit (:id survey) {:reviewer_id 9642 :evidence_ids [(:evidence ctx)] :actual_date "2099-01-01"}))))
      (let [submitted (command! id :surveys :submit (:id survey) {:reviewer_id 9642 :evidence_ids [(:evidence ctx)] :actual_date (today-minus 1) :findings "地基满足要求"})]
        (is (= "in_review" (:status submitted)))
        (is (= (today-minus 1) (:actual_date submitted)))
        (is (= 403 (error-status #(command! 9643 id :surveys :decision (:id survey) {:decision "approved" :reason "冒名"}))))
        (is (= "approved" (:status (command! 9642 id :surveys :decision (:id survey) {:decision "approved" :reason "确认交付物"})))))
      (is (not-any? #(re-find #"工勘" %) (:blockers (workspace id))))
      (is (= 1 (count (get (:task_links (workspace id)) (:task_id ctx))))))))


(deftest packaging-request-requires-spec-and-links-back-to-task
  (let [ctx (context! nil) id (:id ctx)]
    (is (= 400 (error-status #(command! id :material-requests :create nil
                                        (merge (refs ctx) {:code "PK-0" :title "包材" :request_type "packaging" :owner_id 9641 :needed_on "2026-10-01"
                                                           :items [{:code "BOX" :name "木箱" :quantity 1 :unit "个"}]})))))
    (let [request (command! id :material-requests :create nil
                            (merge (refs ctx) {:code "PK-1" :title "包材申请" :request_type "packaging" :owner_id 9641 :needed_on "2026-10-01"
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
          assembly (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-1" :title "执行装配" :bom_id (:id bom) :owner_id 9641}))]
      ;; B11: 齐套Gate未通过 -> 装配开工被阻断.
      (is (= 409 (error-status #(command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]}))))
      (let [gate (gov! id :gates :create nil {:template_id (:id kitting) :title "齐套放行" :reviewer_id 9642})]
        (gov! id :gates :checks (:id gate) {:checks [{:code "KIT-1" :passed true :evidence_ids [(:evidence ctx)]}
                                                   {:code "KIT-2" :passed true :evidence_ids [(:evidence ctx)]}]})
        (gov! id :gates :submit (:id gate) {})
        (gov! 9642 id :gates :decision (:id gate) {:decision "approved" :reason "齐套确认"}))
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
    (let [assembly (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-1" :title "执行装配" :bom_id (:id bom) :owner_id 9641}))]
      (command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]})
      (review! ctx :assemblies (:id assembly) :submit)
      (doseq [type ["SIT" "FAT"]]
        (let [test (command! id :tests :create nil (merge (refs ctx) {:code (str type "-1") :title (str type "验证") :assembly_id (:id assembly)
                                                                     :test_type type :owner_id 9641
                                                                     :criteria [{:code "Q-1" :title "动作满足URS" :required true}]}))]
          (command! id :tests :results (:id test) {:checks [{:code "Q-1" :passed true :actual "通过" :evidence_ids [(:evidence ctx)]}] :due_date "2026-10-03"})
          (review! ctx :tests (:id test) :submit)))
      (command! id :shipments :create nil (merge (refs ctx) {:code "SHIP-1" :title "设备发运" :assembly_ids [(:id assembly)]
                                                            :consignee "现场接收团队" :delivery_address "客户指定地址"
                                                            :planned_date "2026-10-05" :reviewer_id 9642})))))


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
        dq (gov! id :dqs :create nil {:code "DQ-1" :title "主机DQ编制" :owner_id 9641
                                      :checklist [{:code "D1" :title "设计输入完整" :required true} {:code "D2" :title "图纸编号规范" :required false}]
                                      :deliverable_ids [(:id doc)] :task_id (:task_id ctx)})]
    (is (= "draft" (:status dq)))
    (is (= 409 (error-status #(gov! id :dqs :create nil {:code "DQ-1" :title "重复" :owner_id 9641 :checklist [{:code "D1" :title "x"}]}))))
    ;; 必需项未通过 -> 仍为草稿, 提交被拒.
    (gov! id :dqs :checks (:id dq) {:results [{:code "D1" :passed false :note "缺输入"} {:code "D2" :passed true :note ""}]})
    (is (= 409 (error-status #(gov! id :dqs :submit (:id dq) {:reviewer_id 9642}))))
    (is (= "ready" (:status (gov! id :dqs :checks (:id dq) {:results [{:code "D1" :passed true :note "已补齐"} {:code "D2" :passed true :note ""}]}))))
    (is (= "in_review" (:status (gov! id :dqs :submit (:id dq) {:reviewer_id 9642}))))
    (is (= 403 (error-status #(gov! 9643 id :dqs :decision (:id dq) {:decision "approved" :reason "冒名"}))))
    (is (= "approved" (:status (gov! 9642 id :dqs :decision (:id dq) {:decision "approved" :reason "签认"}))))
    (let [model (first (:dqs (gov/workspace *service* (actor 9641) id)))]
      (is (false? (:dq_stale model)))
      (is (= 2 (:dq_passed model))))
    ;; 交付件出现新版本 -> 只读标注签认依据失效.
    (gov! id :documents :revisions (:id doc) {:code "DQ-DOC" :title "DQ设计文件" :filename "dq.txt" :content "DQ v2"})
    (let [model (first (:dqs (gov/workspace *service* (actor 9641) id)))]
      (is (true? (:dq_stale model)))
      (is (= 1 (:dq_stale_count model))))))


(deftest node-pause-blocks-feedback-and-resumes-with-audit
  (let [ctx (context! nil) id (:id ctx)
        other (:result (planning/create-task! *service* (actor 9641) id {:version (version id) :wbs_code "D-2" :name "主计划任务" :owner_id 9641 :start_date "2026-09-22" :duration_days 1}))]
    (is (= 400 (error-status #(gov! id :node-pauses :create nil {:node_id (:node_id (:main ctx)) :reason "主项目"}))))
    (let [pause (gov! id :node-pauses :create nil {:node_id (:node_id (:sub ctx)) :reason "客户暂缓主机单元"})]
      (is (= "active" (:status pause)))
      (is (= "execution" (:project_status_at_pause pause)))
      (is (= 409 (error-status #(gov! id :node-pauses :create nil {:node_id (:node_id (:sub ctx)) :reason "重复"}))))
      ;; 子项目暂停后, 其下单机任务禁止反馈; 主计划任务不受影响.
      (is (= 409 (error-status #(planning/task-feedback! *service* (actor 9641) id (:task_id ctx)
                                                         {:version (version id) :status "in_progress" :percent_complete 10 :remaining_days 1}))))
      (is (some? (planning/task-feedback! *service* (actor 9641) id (:task_id other)
                                          {:version (version id) :status "in_progress" :percent_complete 10 :remaining_days 1})))
      (let [plan (planning/read-plan *service* (actor 9641) id)]
        (is (true? (:node_paused (first (filter #(= (:task_id ctx) (:task_id %)) (:tasks plan))))))
        (is (false? (:node_paused (first (filter #(= (:task_id other) (:task_id %)) (:tasks plan))))))
        (is (= 2 (count (:paused_node_ids plan)))))
      (let [resumed (gov! id :node-pauses :resume (:id pause) {:impact_note "顺延两周, 重排单机计划"})]
        (is (= "closed" (:status resumed)))
        (is (= "顺延两周, 重排单机计划" (:impact_note resumed)))
        (is (= 409 (error-status #(gov! id :node-pauses :resume (:id pause) {:impact_note "again"})))))
      (is (some? (planning/task-feedback! *service* (actor 9641) id (:task_id ctx)
                                          {:version (version id) :status "in_progress" :percent_complete 20 :remaining_days 1}))))))


(deftest kickoff-meeting-requires-pre-read-pack-and-baseline
  (let [ctx (context! nil) id (:id ctx)
        baseline (first (:baselines (planning/read-plan *service* (actor 9641) id)))]
    (is (= 409 (error-status #(gov! id :meetings :create nil {:title "启动会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9641] :meeting_type "kickoff"}))))
    (is (= 409 (error-status #(gov! id :meetings :create nil {:title "启动会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9641] :meeting_type "kickoff" :material_ids [(:evidence ctx)]}))))
    (is (= 404 (error-status #(gov! id :meetings :create nil {:title "启动会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9641] :meeting_type "kickoff" :material_ids [(:evidence ctx)] :baseline_id "nope"}))))
    (is (= 400 (error-status #(gov! id :meetings :create nil {:title "会" :held_on "2026-09-23" :minutes "纪要" :attendee_ids [9641] :meeting_type "party"}))))
    (let [meeting (gov! id :meetings :create nil {:title "项目启动会" :held_on "2026-09-23" :minutes "确认主计划与售前资料" :attendee_ids [9641 9643]
                                                  :meeting_type "kickoff" :material_ids [(:evidence ctx)] :baseline_id (:baseline_id baseline)})]
      (is (= "kickoff" (:meeting_type meeting)))
      (is (= (:baseline_id baseline) (:baseline_id meeting)))
      (is (= "approved" (:baseline_status meeting)))
      (let [model (first (filter #(= (:id meeting) (:id %)) (:meetings (gov/workspace *service* (actor 9641) id))))]
        (is (false? (:baseline_stale model)))
        (is (= "approved" (:baseline_current_status model)))))
    (is (= "regular" (:meeting_type (gov! id :meetings :create nil {:title "例会" :held_on "2026-09-24" :minutes "纪要" :attendee_ids [9641]}))))))


(deftest site-task-progress-summary-is-derived-read-only
  (let [empty (fieldwork/site-task-progress-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty) (:closed empty) (:in-progress empty) (:draft empty) (:delayed empty) (:closure-pct empty)))
    (is (nil? (:earliest-open empty)))
    (is (= [{:key "positioning" :total 0 :closed 0}
            {:key "installation" :total 0 :closed 0}
            {:key "commissioning" :total 0 :closed 0}
            {:key "sat" :total 0 :closed 0}]
           (:by-key empty))))
  (let [tasks [{:task_key "positioning" :status "closed" :planned_start "2026-09-01" :site_delayed false}
               {:task_key "installation" :status "in_progress" :planned_start "2026-09-10" :site_delayed false}
               {:task_key "commissioning" :status "draft" :planned_start "2026-09-05" :site_delayed true}
               {:task_key "sat" :status "draft" :planned_start "2026-12-20" :site_delayed false}]
        sum (fieldwork/site-task-progress-summary tasks)]
    (is (true? (:available sum)))
    (is (= 4 (:total sum)))
    (is (= 1 (:closed sum)))
    (is (= 1 (:in-progress sum)))
    (is (= 2 (:draft sum)))
    (is (= 1 (:delayed sum)))
    (is (= 25 (:closure-pct sum)))
    (is (= "2026-09-05" (:earliest-open sum)))
    (is (= [{:key "positioning" :total 1 :closed 1}
            {:key "installation" :total 1 :closed 0}
            {:key "commissioning" :total 1 :closed 0}
            {:key "sat" :total 1 :closed 0}]
           (:by-key sum)))))


(deftest site-task-progress-rollup-attached-to-delivery-workspace
  (let [ctx (context! {:pre_ship_conditions ["warehouse_in" "payment"] :handover_deadline_days 2 :site_lag_days 1 :handover_required true})
        id (:id ctx) shipment (shipped-shipment! ctx)]
    (command! id :shipments :conditions (:id shipment) {:warehouse_in_confirmed true :warehouse_note "WMS入库单 WP-001"
                                                       :payment_confirmed true :payment_note "财务确认提货款到账" :evidence_ids [(:evidence ctx)]})
    (review! ctx :shipments (:id shipment) :submit)
    (let [shipped-on (today-minus 3)
          _ (command! id :shipments :dispatch (:id shipment) {:shipped_on shipped-on :tracking_no "WP-TRACK" :evidence_ids [(:evidence ctx)]})
          handover (first (:handovers (workspace id)))
          _ (command! id :handovers :complete (:id handover) {:document_ids [(:evidence ctx)] :checklist_note "交底清单 WP" :completed_on (today-minus 1)})
          rollup (:site_task_progress (workspace id))]
      (is (true? (:available rollup)))
      (is (= 4 (:total rollup)))
      (is (= 0 (:closed rollup)))
      (is (= 4 (:draft rollup)))
      (is (= 0 (:delayed rollup)))
      (is (= 0 (:closure-pct rollup)))
      (is (= (str (LocalDate/now)) (:earliest-open rollup)))
      (is (= [{:key "positioning" :total 1 :closed 0} {:key "installation" :total 1 :closed 0}
              {:key "commissioning" :total 1 :closed 0} {:key "sat" :total 1 :closed 0}]
             (:by-key rollup)))
      (let [tasks (sort-by :sequence (:site_tasks (workspace id)))]
        (command! id :site-tasks :start (:id (first tasks)) {:actual_start (today-minus 0)})
        (command! id :site-tasks :complete (:id (first tasks)) {:actual_end (today-minus 0) :evidence_ids [(:evidence ctx)] :result "定位完成"})
        (let [after (:site_task_progress (workspace id))]
          (is (= 1 (:closed after)))
          (is (= 3 (:draft after)))
          (is (= 25 (:closure-pct after)))
          (is (= [{:key "positioning" :total 1 :closed 1} {:key "installation" :total 1 :closed 0}
                  {:key "commissioning" :total 1 :closed 0} {:key "sat" :total 1 :closed 0}]
                 (:by-key after))))))))


(deftest assembly-execution-summary-is-derived-read-only
  (let [empty (fieldwork/assembly-execution-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:step-pct empty)))
    (is (= ["not_started" "on_island" "assembling" "unit_inspection" "wiring_inspection" "off_island" "handover"]
           (mapv :key (:by-step empty))))
    (is (= [0 0 0 0 0 0 0] (mapv :total (:by-step empty)))))
  (let [assemblies [{:status "draft" :step_count 0 :current_step "not_started"}
                    {:status "in_progress" :step_count 2 :current_step "assembling"}
                    {:status "in_review" :step_count 5 :current_step "off_island"}
                    {:status "approved" :step_count 6 :current_step "handover"}]
        rollup (fieldwork/assembly-execution-summary assemblies)]
    (is (true? (:available rollup)))
    (is (= 4 (:total rollup)))
    (is (= 1 (:draft rollup)))
    (is (= 1 (:in-progress rollup)))
    (is (= 1 (:in-review rollup)))
    (is (= 1 (:approved rollup)))
    (is (= 25 (:closure-pct rollup)))
    (is (= 1 (:fully-stepped rollup)))
    (is (= 54 (:step-pct rollup)))
    (is (= [1 0 1 0 0 1 1] (mapv :total (:by-step rollup))))))


(deftest assembly-execution-progress-attached-to-delivery-workspace
  (let [ctx (context! nil) id (:id ctx) bom (frozen-bom! ctx)]
    (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 2} {:code "M-2" :available_quantity 4}] :evidence_ids [(:evidence ctx)]})
    (let [kitting (gov! id :gate-templates :from-catalog nil {:gate_type "kitting"})
          gate (gov! id :gates :create nil {:template_id (:id kitting) :title "齐套放行" :reviewer_id 9642})
          a (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-A" :title "装配甲" :bom_id (:id bom) :owner_id 9641}))
          b (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-B" :title "装配乙" :bom_id (:id bom) :owner_id 9641}))
          c (command! id :assemblies :create nil (merge (refs ctx) {:code "ASS-C" :title "装配丙" :bom_id (:id bom) :owner_id 9641}))]
      (gov! id :gates :checks (:id gate) {:checks [{:code "KIT-1" :passed true :evidence_ids [(:evidence ctx)]}
                                                   {:code "KIT-2" :passed true :evidence_ids [(:evidence ctx)]}]})
      (gov! id :gates :submit (:id gate) {})
      (gov! 9642 id :gates :decision (:id gate) {:decision "approved" :reason "齐套确认"})
      (command! id :assemblies :start (:id a) {:evidence_ids [(:evidence ctx)]})
      (doseq [step fieldwork/assembly-steps]
        (command! id :assemblies :steps (:id a) {:step step :actual_date (today-minus 1)}))
      (command! id :assemblies :start (:id b) {:evidence_ids [(:evidence ctx)]})
      (doseq [step ["on_island" "assembling"]]
        (command! id :assemblies :steps (:id b) {:step step :actual_date (today-minus 1)}))
      (let [pre (:assembly_execution_progress (workspace id))]
        (is (true? (:available pre)))
        (is (= 3 (:total pre)))
        (is (= 1 (:draft pre)))
        (is (= 2 (:in-progress pre)))
        (is (= 0 (:in-review pre)))
        (is (= 0 (:approved pre)))
        (is (= 0 (:closure-pct pre)))
        (is (= 1 (:fully-stepped pre)))
        (is (= 44 (:step-pct pre)))
        (is (= [1 0 1 0 0 0 1] (mapv :total (:by-step pre))))
        (review! ctx :assemblies (:id a) :submit)
        (let [post (:assembly_execution_progress (workspace id))]
          (is (= 1 (:approved post)))
          (is (= 1 (:in-progress post)))
          (is (= 1 (:draft post)))
          (is (= 0 (:in-review post)))
          (is (= 33 (:closure-pct post)))
          (is (= 1 (:fully-stepped post)))
          (is (= 44 (:step-pct post))))))))


(deftest preship-readiness-summary-is-derived-read-only
  (let [empty (fieldwork/preship-readiness-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:ready empty)))
    (is (= 0 (:blocked empty)))
    (is (= 0 (:released empty)))
    (is (= 0 (:readiness-pct empty)))
    (is (= ["fat" "remediation" "warehouse_in" "payment"] (mapv :code (:by-condition empty))))
    (is (= [0 0 0 0] (mapv :applicable (:by-condition empty))))
    (is (= [0 0 0 0] (mapv :satisfied (:by-condition empty)))))
  (let [shipments [{:status "released" :preship_checklist [{:code "fat" :satisfied true} {:code "remediation" :satisfied true}
                                                           {:code "warehouse_in" :satisfied true} {:code "payment" :satisfied true}]}
                   {:status "draft" :preship_checklist [{:code "fat" :satisfied true} {:code "remediation" :satisfied true}
                                                        {:code "warehouse_in" :satisfied false} {:code "payment" :satisfied false}]}
                   {:status "in_review" :preship_checklist [{:code "fat" :satisfied true} {:code "remediation" :satisfied false}]}]
        rollup (fieldwork/preship-readiness-summary shipments)]
    (is (true? (:available rollup)))
    (is (= 3 (:total rollup)))
    (is (= 1 (:ready rollup)))
    (is (= 2 (:blocked rollup)))
    (is (= 1 (:released rollup)))
    (is (= 33 (:readiness-pct rollup)))
    (is (= [3 3 2 2] (mapv :applicable (:by-condition rollup))))
    (is (= [3 2 1 1] (mapv :satisfied (:by-condition rollup))))
    (is (= ["fat" "remediation" "warehouse_in" "payment"] (mapv :code (:by-condition rollup))))))


(deftest preship-readiness-attached-to-delivery-workspace
  (let [ctx (context! {:pre_ship_conditions ["warehouse_in" "payment"] :handover_deadline_days 2})
        id (:id ctx) shipment (shipped-shipment! ctx)]
    (let [pre (:preship_readiness (workspace id))]
      (is (true? (:available pre)))
      (is (= 1 (:total pre)))
      (is (= 0 (:ready pre)))
      (is (= 1 (:blocked pre)))
      (is (= 0 (:released pre)))
      (is (= 0 (:readiness-pct pre)))
      (is (= [1 1 1 1] (mapv :applicable (:by-condition pre))))
      (is (= [1 1 0 0] (mapv :satisfied (:by-condition pre)))))
    (command! id :shipments :conditions (:id shipment) {:warehouse_in_confirmed true :warehouse_note "WMS入库单 IN-001"
                                                        :payment_confirmed true :payment_note "财务确认提货款到账" :evidence_ids [(:evidence ctx)]})
    (review! ctx :shipments (:id shipment) :submit)
    (let [post (:preship_readiness (workspace id))]
      (is (= 1 (:total post)))
      (is (= 1 (:ready post)))
      (is (= 0 (:blocked post)))
      (is (= 1 (:released post)))
      (is (= 100 (:readiness-pct post)))
      (is (= [1 1 1 1] (mapv :applicable (:by-condition post))))
      (is (= [1 1 1 1] (mapv :satisfied (:by-condition post)))))))


(deftest shipment-closure-summary-is-derived-read-only
  (let [empty (fieldwork/shipment-closure-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:draft empty)))
    (is (= 0 (:received empty)))
    (is (= 0 (:released-or-beyond empty)))
    (is (= 0 (:closure-pct empty)))
    (is (= 0 (:receipt-pct empty)))
    (is (= ["draft" "in_review" "rejected" "released" "shipped" "received" "conditional" "returned"]
           (mapv :key (:by-status empty))))
    (is (= [0 0 0 0 0 0 0 0] (mapv :count (:by-status empty)))))
  (let [shipments (mapv #(hash-map :status %)
                        ["draft" "in_review" "released" "shipped" "received" "received" "conditional" "returned" "rejected"])
        rollup (fieldwork/shipment-closure-summary shipments)]
    (is (true? (:available rollup)))
    (is (= 9 (:total rollup)))
    (is (= 1 (:draft rollup)))
    (is (= 1 (:in-review rollup)))
    (is (= 1 (:rejected rollup)))
    (is (= 1 (:released rollup)))
    (is (= 1 (:shipped rollup)))
    (is (= 2 (:received rollup)))
    (is (= 1 (:conditional rollup)))
    (is (= 1 (:returned rollup)))
    (is (= 6 (:released-or-beyond rollup)))
    (is (= 5 (:dispatched rollup)))
    (is (= 2 (:in-transit rollup)))
    (is (= 2 (:exception rollup)))
    (is (= 22 (:closure-pct rollup)))
    (is (= 50 (:receipt-pct rollup)))
    (is (= [1 1 1 1 1 2 1 1] (mapv :count (:by-status rollup))))))


(deftest shipment-closure-attached-to-delivery-workspace
  (let [ctx (context! nil) id (:id ctx) shipment (shipped-shipment! ctx)]
    (let [pre (:shipment_closure (workspace id))
          row (first (filter #(= (:id shipment) (:id %)) (:shipments (workspace id))))]
      (is (true? (:available pre)))
      (is (= 1 (:total pre)))
      (is (= 1 (:draft pre)))
      (is (= 0 (:released-or-beyond pre)))
      (is (= 0 (:closure-pct pre)))
      (is (= 0 (:receipt-pct pre)))
      (is (= "draft" (:status row))))
    (review! ctx :shipments (:id shipment) :submit)
    (command! id :shipments :dispatch (:id shipment) {:shipped_on (today-minus 2) :tracking_no "SC-TRACK" :evidence_ids [(:evidence ctx)]})
    (let [mid (:shipment_closure (workspace id))]
      (is (= 1 (:shipped mid)))
      (is (= 1 (:released-or-beyond mid)))
      (is (= 1 (:dispatched mid)))
      (is (= 1 (:in-transit mid)))
      (is (= 0 (:received mid)))
      (is (= 0 (:closure-pct mid)))
      (is (= 0 (:receipt-pct mid))))
    (command! 9642 id :shipments :receipt (:id shipment) {:received_on (today-minus 1) :receiver_name "现场接收人" :acceptance "accepted" :evidence_ids [(:evidence ctx)]})
    (let [post (:shipment_closure (workspace id))
          row (first (filter #(= (:id shipment) (:id %)) (:shipments (workspace id))))]
      (is (= 1 (:total post)))
      (is (= 1 (:received post)))
      (is (= 1 (:released-or-beyond post)))
      (is (= 1 (:dispatched post)))
      (is (= 0 (:in-transit post)))
      (is (= 0 (:exception post)))
      (is (= 100 (:closure-pct post)))
      (is (= 100 (:receipt-pct post)))
      (is (= "received" (:status row))))))


(deftest handover-timeliness-summary-is-derived-read-only
  (let [empty (fieldwork/handover-timeliness-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:open empty)))
    (is (= 0 (:closed empty)))
    (is (= 0 (:overdue-open empty)))
    (is (= 0 (:completed-on-time empty)))
    (is (= 0 (:completed-late empty)))
    (is (= 0 (:on-time-pct empty)))
    (is (nil? (:nearest-deadline empty)))
    (is (= ["open-pending" "open-overdue" "closed-on-time" "closed-late"]
           (mapv :key (:by-state empty))))
    (is (= [0 0 0 0] (mapv :count (:by-state empty)))))
  (let [handovers [{:status "open" :deadline "2026-10-10" :handover_overdue false}
                  {:status "open" :deadline "2026-09-01" :handover_overdue true}
                  {:status "closed" :deadline "2026-09-15" :completed_late false}
                  {:status "closed" :deadline "2026-09-20" :completed_late false}
                  {:status "closed" :deadline "2026-09-25" :completed_late true}]
        rollup (fieldwork/handover-timeliness-summary handovers)]
    (is (true? (:available rollup)))
    (is (= 5 (:total rollup)))
    (is (= 2 (:open rollup)))
    (is (= 3 (:closed rollup)))
    (is (= 1 (:overdue-open rollup)))
    (is (= 2 (:completed-on-time rollup)))
    (is (= 1 (:completed-late rollup)))
    (is (= 67 (:on-time-pct rollup)))
    (is (= "2026-09-01" (:nearest-deadline rollup)))
    (is (= [1 1 2 1] (mapv :count (:by-state rollup))))))


(deftest handover-timeliness-attached-to-delivery-workspace
  (let [ctx (context! {:handover_deadline_days 2 :handover_required true})
        id (:id ctx) shipment (shipped-shipment! ctx)]
    (review! ctx :shipments (:id shipment) :submit)
    (command! id :shipments :dispatch (:id shipment) {:shipped_on (today-minus 1) :tracking_no "HT-TRACK" :evidence_ids [(:evidence ctx)]})
    (let [handover (first (:handovers (workspace id)))
          pre (:handover_timeliness (workspace id))]
      (is (= "open" (:status handover)))
      (is (true? (:available pre)))
      (is (= 1 (:total pre)))
      (is (= 1 (:open pre)))
      (is (= 0 (:closed pre)))
      (is (= 0 (:overdue-open pre)))
      (is (= 0 (:on-time-pct pre)))
      (is (= (:deadline handover) (:nearest-deadline pre)))
      (command! id :handovers :complete (:id handover) {:document_ids [(:evidence ctx)] :checklist_note "交底清单 HT" :completed_on (today-minus 0)})
      (let [post (:handover_timeliness (workspace id))
            row (first (:handovers (workspace id)))]
        (is (= "closed" (:status row)))
        (is (false? (:completed_late row)))
        (is (= 1 (:total post)))
        (is (= 0 (:open post)))
        (is (= 1 (:closed post)))
        (is (= 1 (:completed-on-time post)))
        (is (= 0 (:completed-late post)))
        (is (= 100 (:on-time-pct post)))
        (is (nil? (:nearest-deadline post)))))))


(deftest survey-closure-summary-is-derived-read-only
  (let [empty (fieldwork/survey-closure-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:draft empty)))
    (is (= 0 (:in-review empty)))
    (is (= 0 (:approved empty)))
    (is (= 0 (:rejected empty)))
    (is (= 0 (:open empty)))
    (is (= 0 (:overdue-open empty)))
    (is (= 0 (:closure-pct empty)))
    (is (nil? (:nearest-planned empty)))
    (is (= ["draft" "in_review" "approved" "rejected"]
           (mapv :key (:by-status empty))))
    (is (= [0 0 0 0] (mapv :count (:by-status empty)))))
  (let [surveys [{:status "draft" :planned_date "2026-10-10" :survey_overdue false}
                 {:status "in_review" :planned_date "2026-09-01" :survey_overdue true}
                 {:status "approved" :planned_date "2026-09-15"}
                 {:status "approved" :planned_date "2026-09-20"}
                 {:status "rejected" :planned_date "2026-08-01"}]
        rollup (fieldwork/survey-closure-summary surveys)]
    (is (true? (:available rollup)))
    (is (= 5 (:total rollup)))
    (is (= 1 (:draft rollup)))
    (is (= 1 (:in-review rollup)))
    (is (= 2 (:approved rollup)))
    (is (= 1 (:rejected rollup)))
    (is (= 2 (:open rollup)))
    (is (= 1 (:overdue-open rollup)))
    (is (= 40 (:closure-pct rollup)))
    (is (= "2026-09-01" (:nearest-planned rollup)))
    (is (= [1 1 2 1] (mapv :count (:by-status rollup))))))


(deftest survey-closure-attached-to-delivery-workspace
  (let [ctx (context! {:required_survey_visits 1}) id (:id ctx)]
    (let [sv1 (command! id :surveys :create nil {:code "SV-1" :title "首勘" :visit_no 1 :owner_id 9641
                                                  :planned_date (today-minus 20) :deliverable "地基记录"})
          sv2 (command! id :surveys :create nil {:code "SV-2" :title "复勘" :visit_no 2 :owner_id 9641
                                                  :planned_date "2099-01-01" :deliverable "二次记录"})
          pre (:survey_closure (workspace id))]
      (is (= "draft" (:status sv1)))
      (is (= "draft" (:status sv2)))
      (is (true? (:available pre)))
      (is (= 2 (:total pre)))
      (is (= 2 (:draft pre)))
      (is (= 2 (:open pre)))
      (is (= 0 (:approved pre)))
      (is (= 1 (:overdue-open pre)))
      (is (= 0 (:closure-pct pre)))
      (is (= (today-minus 20) (:nearest-planned pre)))
      (command! id :surveys :submit (:id sv2) {:reviewer_id 9642 :evidence_ids [(:evidence ctx)] :actual_date (today-minus 1)})
      (command! 9642 id :surveys :decision (:id sv2) {:decision "approved" :reason "独立确认复勘"})
      (command! id :surveys :submit (:id sv1) {:reviewer_id 9642 :evidence_ids [(:evidence ctx)] :actual_date (today-minus 1)})
      (let [mid (:survey_closure (workspace id))
            row1 (first (filter #(= (:id sv1) (:id %)) (:surveys (workspace id))))]
        (is (= "in_review" (:status row1)))
        (is (= 2 (:total mid)))
        (is (= 1 (:approved mid)))
        (is (= 1 (:in-review mid)))
        (is (= 0 (:draft mid)))
        (is (= 1 (:overdue-open mid)))
        (is (= 50 (:closure-pct mid)))
        (is (= (today-minus 20) (:nearest-planned mid)))
        (command! 9642 id :surveys :decision (:id sv1) {:decision "approved" :reason "独立确认首勘"})
        (let [post (:survey_closure (workspace id))]
          (is (= 2 (:approved post)))
          (is (= 0 (:open post)))
          (is (= 0 (:overdue-open post)))
          (is (= 100 (:closure-pct post)))
          (is (nil? (:nearest-planned post))))))))


(deftest test-execution-summary-is-derived-read-only
  (let [empty (fieldwork/test-execution-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:closure-pct empty)))
    (is (= 0 (:results-recorded empty)))
    (is (= 0 (:required-ready empty)))
    (is (= ["SIT" "FAT" "SAT"] (mapv :key (:by-type empty))))
    (is (= [0 0 0] (mapv :total (:by-type empty))))
    (is (= [0 0 0] (mapv :approved (:by-type empty))))
    (is (= [0 0 0] (mapv :approved-pct (:by-type empty)))))
  (let [tests [{:status "draft" :test_type "SIT" :test_results_recorded false :test_required_all_passed false}
               {:status "ready" :test_type "SIT" :test_results_recorded true :test_required_all_passed true}
               {:status "in_review" :test_type "FAT" :test_results_recorded true :test_required_all_passed true}
               {:status "approved" :test_type "FAT" :test_results_recorded true :test_required_all_passed true}]
        rollup (fieldwork/test-execution-summary tests)]
    (is (true? (:available rollup)))
    (is (= 4 (:total rollup)))
    (is (= 1 (:draft rollup)))
    (is (= 1 (:ready rollup)))
    (is (= 1 (:in-review rollup)))
    (is (= 1 (:approved rollup)))
    (is (= 0 (:rejected rollup)))
    (is (= 3 (:open rollup)))
    (is (= 3 (:results-recorded rollup)))
    (is (= 3 (:required-ready rollup)))
    (is (= 25 (:closure-pct rollup)))
    (is (= ["SIT" "FAT" "SAT"] (mapv :key (:by-type rollup))))
    (is (= [2 2 0] (mapv :total (:by-type rollup))))
    (is (= [0 1 0] (mapv :approved (:by-type rollup))))
    (is (= [0 50 0] (mapv :approved-pct (:by-type rollup))))))


(deftest test-execution-read-model-derives-required-criteria
  (let [draft (fieldwork/test-execution-read-model
               {:status "draft" :criteria [{:code "Q-1" :required true} {:code "Q-2" :required false}]})
        partial (fieldwork/test-execution-read-model
                 {:status "ready" :criteria [{:code "Q-1" :required true} {:code "Q-2" :required false}]
                  :checks [{:code "Q-1" :required true :passed false} {:code "Q-2" :required false :passed true}]})
        passed (fieldwork/test-execution-read-model
                {:status "ready" :criteria [{:code "Q-1" :required true} {:code "Q-2" :required false}]
                 :checks [{:code "Q-1" :required true :passed true} {:code "Q-2" :required false :passed true}]})]
    (is (= 1 (:test_criteria_required draft)))
    (is (false? (:test_results_recorded draft)))
    (is (= 0 (:test_required_passed draft)))
    (is (false? (:test_required_all_passed draft)))
    (is (true? (:test_results_recorded partial)))
    (is (= 1 (:test_criteria_required partial)))
    (is (= 0 (:test_required_passed partial)))
    (is (false? (:test_required_all_passed partial)))
    (is (= 1 (:test_required_passed passed)))
    (is (true? (:test_required_all_passed passed)))))


(deftest test-execution-attached-to-delivery-workspace
  (let [ctx (context! nil)
        id (:id ctx)
        _ (shipped-shipment! ctx)
        approved (first (filter #(= "approved" (:status %)) (:tests (workspace id))))
        _ (command! id :tests :create nil
                    (merge (refs ctx)
                           {:code "SIT-2" :title "补充SIT准则"
                            :assembly_id (:assembly_id approved)
                            :test_type "SIT" :owner_id 9641
                            :criteria [{:code "Q-9" :title "附加必检" :required true}]}))]
    (let [pre (:test_execution (workspace id))
          draft-row (first (filter #(= "SIT-2" (:code %)) (:tests (workspace id))))]
      (is (true? (:available pre)))
      (is (= 3 (:total pre)))
      (is (= 1 (:draft pre)))
      (is (= 2 (:approved pre)))
      (is (= 1 (:open pre)))
      (is (= 2 (:results-recorded pre)))
      (is (= 2 (:required-ready pre)))
      (is (= 67 (:closure-pct pre)))
      (is (= [2 1 0] (mapv :total (:by-type pre))))
      (is (= [1 1 0] (mapv :approved (:by-type pre))))
      (is (= [50 100 0] (mapv :approved-pct (:by-type pre))))
      (is (false? (:test_results_recorded draft-row)))
      (is (= 1 (:test_criteria_required draft-row)))
      (is (= 0 (:test_required_passed draft-row)))
      (is (false? (:test_required_all_passed draft-row))))))
