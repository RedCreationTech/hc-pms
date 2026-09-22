(ns com.ruoyi.pms-delivery-scenario
  "供生命周期集成测试复用的真实交付场景,不直接写库或绕过状态机."
  (:require [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.domain.pms.service :as pms]))


(defn- command!
  "每次从实际项目读取版本并执行公开业务服务."
  [ctx reviewer? resource action rid body]
  (let [{:keys [svc project-id manager reviewer]} ctx
        actor (pms/actor svc {:user-id (if reviewer? reviewer manager)})
        version (:version (pms/project svc actor project-id))]
    (:result (delivery/command! svc actor project-id resource action rid (assoc body :version version)))))


(defn- references
  "为所有执行记录关联同一真实任务及确切URS版本."
  [ctx]
  {:task_id (:task-id ctx) :requirement_ids [(:requirement-id ctx)]})


(defn- review!
  "提交实际证据并由独立指定审核人批准."
  [ctx resource rid action]
  (command! ctx false resource action rid
            (cond-> {:evidence_ids [(:evidence-id ctx)]}
              (not= :shipments resource) (assoc :reviewer_id (:reviewer ctx))))
  (command! ctx true resource :decision rid {:decision "approved" :reason "独立核验实际记录"}))


(defn- assembly!
  "完成申请,冻结,齐套,开工及独立装配交检."
  [ctx]
  (let [material (command! ctx false :material-requests :create nil
                           (merge (references ctx) {:code "SC-MR" :title "验收材料" :request_type "standard"
                                                    :owner_id (:manager ctx) :needed_on "2026-10-01"
                                                    :items [{:code "PART" :name "验收部件" :quantity 1 :unit "个"}]}))]
    (review! ctx :material-requests (:id material) :submit)
    (let [bom (command! ctx false :boms :create nil {:code "SC-BOM" :title "冻结配置" :material_request_id (:id material)})]
      (review! ctx :boms (:id bom) :freeze)
      (command! ctx false :boms :kit (:id bom) {:items [{:code "PART" :available_quantity 1}] :evidence_ids [(:evidence-id ctx)]})
      (let [assembly (command! ctx false :assemblies :create nil
                               (merge (references ctx) {:code "SC-ASSEMBLY" :title "实际装配"
                                                       :owner_id (:manager ctx) :bom_id (:id bom)}))]
        (command! ctx false :assemblies :start (:id assembly) {:evidence_ids [(:evidence-id ctx)]})
        (review! ctx :assemblies (:id assembly) :submit)))))


(defn- test!
  "登记指定类型实际结果并独立检验."
  [ctx assembly type]
  (let [record (command! ctx false :tests :create nil
                         (merge (references ctx) {:code (str "SC-" type) :title (str type "验收") :test_type type
                                                 :assembly_id (:id assembly) :owner_id (:manager ctx)
                                                 :criteria [{:code "QC" :title "符合已确认URS" :required true}]}))]
    (command! ctx false :tests :results (:id record)
              {:checks [{:code "QC" :passed true :actual "实际测试满足准则" :evidence_ids [(:evidence-id ctx)]}]
               :due_date "2026-10-03"})
    (review! ctx :tests (:id record) :submit)))


(defn complete-delivery!
  "输入真实执行项目,任务,证据和URS,完成整条标准交付链后返回工作台."
  [svc manager reviewer project-id task-id evidence-id requirement-id]
  (let [ctx {:svc svc :manager manager :reviewer reviewer :project-id project-id
             :task-id task-id :evidence-id evidence-id :requirement-id requirement-id}
        assembly (assembly! ctx)]
    (test! ctx assembly "SIT")
    (test! ctx assembly "FAT")
    (let [shipment (command! ctx false :shipments :create nil
                             (merge (references ctx) {:code "SC-SHIP" :title "客户交付" :assembly_ids [(:id assembly)]
                                                     :consignee "实际接收团队" :delivery_address "约定交付地址"
                                                     :planned_date "2026-10-05" :reviewer_id reviewer}))]
      (review! ctx :shipments (:id shipment) :submit)
      (command! ctx false :shipments :dispatch (:id shipment)
                {:shipped_on (.toString (.minusDays (java.time.LocalDate/now) 2)) :tracking_no "SC-TRANSPORT" :evidence_ids [evidence-id]})
      (command! ctx true :shipments :receipt (:id shipment)
                {:received_on (.toString (.minusDays (java.time.LocalDate/now) 1)) :receiver_name "已签收人员" :acceptance "accepted" :evidence_ids [evidence-id]}))
    (test! ctx assembly "SAT")
    (delivery/workspace svc (pms/actor svc {:user-id manager}) project-id)))
