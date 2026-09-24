(ns com.ruoyi.domain.pms.delivery
  "交付执行工作台,受控业务命令及收尾阻塞检查."
  (:require [com.ruoyi.domain.pms.delivery.fieldwork :as fieldwork]
            [com.ruoyi.domain.pms.delivery.materials :as materials]
            [com.ruoyi.domain.pms.delivery.production :as production]
            [com.ruoyi.domain.pms.delivery.shipping :as shipping]
            [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.time LocalDate]))


(def sections "交付工作台类型集合." {:material_requests "material" :boms "bom" :assemblies "assembly"
                                     :tests "test" :shipments "shipment" :service_cases "service"
                                     :surveys "survey" :handovers "handover" :site_tasks "site-task"})


(defn- quality-blockers
  "逐装配和适用试验类型检查正式批准结果."
  [q project config assemblies]
  (for [assembly assemblies type (:required_test_types config)
        :when (not (production/type-approved? q project (:id assembly) type))]
    (str "装配 " (:code assembly) " 缺少已批准 " type " 试验")))


(defn closure-blockers
  "返回完整交付链仍未完成的必要事实,不把未接入外部系统算作已同步."
  [q project]
  (let [config (d/configuration q project) stages (set (:required_stages config))
        boms (d/records q project "bom") assemblies (d/records q project "assembly")
        shipments (d/records q project "shipment") services (d/records q project "service")
        received (filter #(= "received" (:status %)) shipments)]
    (vec (concat
           (when (and (stages "materials") (not-any? #(= "ready" (:status %)) boms)) ["缺少已冻结并实际齐套的BOM"])
           (when (and (or (stages "assembly") (stages "quality")) (empty? assemblies)) ["缺少实际装配交检记录"])
           (for [assembly assemblies :when (not= "approved" (:status assembly))]
             (str "装配尚未独立交检通过: " (:code assembly)))
           (when (stages "quality") (quality-blockers q project config assemblies))
           (when (and (stages "shipment") (empty? shipments)) ["缺少真实发运及签收记录"])
           (for [shipment shipments :when (not= "received" (:status shipment))]
             (str "发运单尚未完整签收: " (:code shipment)))
           (when (stages "shipment")
             (for [assembly assemblies :when (not-any? #(some #{(:id assembly)} (:assembly_ids %)) received)]
               (str "装配范围尚未完整交付: " (:code assembly))))
           (for [service services :when (not= "closed" (:status service))]
             (str "售后异常尚未独立关闭: " (:title service)))
           (fieldwork/survey-blockers q project config)
           (for [handover (d/records q project "handover") :when (= "open" (:status handover))]
             (str "交底尚未完成: " (:code handover)))))))


(defn closure-ready!
  "供项目生命周期调用完整交付事实的收尾约束."
  [q project]
  (when-let [message (first (closure-blockers q project))] (r/fail! 409 message))
  true)


(defn member-removal-blockers
  "保护尚在执行或等待确认的责任人与指定审批人."
  [q project uid]
  (let [active (remove #(contains? #{"approved" "ready" "received" "closed"} (:status %))
                       (mapcat #(d/records q project %) (vals sections)))]
    (cond-> []
      (some #(= uid (:owner_id %)) active) (conj "该成员仍负责未完成的交付执行记录")
      (some #(= uid (:reviewer_id %)) active) (conj "该成员仍是未完成交付执行记录的指定审批人"))))


(defn- task-links
  "按来源任务归集其发起的申请/交付记录状态, 供任务侧回看结果 (D03 回到来源任务)."
  [data]
  (->> (concat (:material_requests data) (:surveys data) (:assemblies data) (:tests data) (:shipments data))
       (filter :task_id)
       (group-by :task_id)
       (map (fn [[task-id rows]] [task-id (mapv #(select-keys % [:id :kind :code :title :status :request_type]) rows)]))
       (into {})))


(defn workspace
  "读取实际交付对象, 只读派生 (齐套卷积/装配步骤/发货前条件/交底时限/现场任务延误) 和明确的未接入外部同步状态."
  [svc actor id]
  (k/read! svc actor id "pms:project:query"
    (fn [q project]
      (let [config (d/configuration q project)
            today (LocalDate/now)
            data (into {} (for [[section kind] sections] [section (d/records q project kind)]))
            tasks (vec (q :planning/tasks {:project_id (:project_id project)}))
            nodes (vec (q :pms/nodes {:project_id (:project_id project)}))
            blocker-free? (not-any? #(and (= "blocker" (:severity %)) (not= "closed" (:status %))) (g/records q project "issue"))
            types (remove #{"SAT"} (:required_test_types config))
            fat-ok? (fn [shipment] (every? (fn [rid] (every? #(production/type-approved? q project rid %) types)) (:assembly_ids shipment)))]
        (-> data
            (update :assemblies #(mapv fieldwork/assembly-read-model %))
            (update :shipments #(mapv (fn [s] (fieldwork/shipment-read-model config (fat-ok? s) blocker-free? s)) %))
            (update :handovers #(mapv (partial fieldwork/handover-read-model today) %))
            (update :site_tasks #(mapv (partial fieldwork/site-task-read-model today) %))
            (assoc :configuration config :project_version (:version project)
                   :blockers (closure-blockers q project) :external_sync_status "not_configured"
                   :kitting_rollup (materials/kitting-rollup (:boms data) tasks nodes)
                   :task_links (task-links data)
                   :assembly_steps fieldwork/assembly-steps))))))


(def commands
  "交付工作流固定命令,没有任意payload CRUD入口."
  {[:material-requests :create] materials/create-request! [:material-requests :submit] materials/submit-request!
   [:material-requests :decision] materials/decide-request! [:boms :create] materials/create-bom!
   [:boms :freeze] materials/freeze! [:boms :decision] materials/decide-bom! [:boms :kit] materials/kit!
   [:assemblies :create] production/create-assembly! [:assemblies :start] production/start!
   [:assemblies :submit] production/submit-assembly! [:assemblies :decision] production/decide-assembly!
   [:tests :create] production/create-test! [:tests :results] production/record-results!
   [:tests :submit] production/submit-test! [:tests :decision] production/decide-test!
   [:shipments :create] shipping/create-shipment! [:shipments :submit] shipping/submit-shipment!
   [:shipments :decision] shipping/decide-shipment! [:shipments :dispatch] shipping/dispatch!
   [:shipments :receipt] shipping/receipt! [:service-cases :create] shipping/create-service!
   [:service-cases :resolve] shipping/resolve-service! [:service-cases :decision] shipping/decide-service!
   [:surveys :create] fieldwork/create-survey! [:surveys :submit] fieldwork/submit-survey!
   [:surveys :decision] fieldwork/decide-survey!
   [:assemblies :steps] fieldwork/record-step!
   [:shipments :conditions] fieldwork/confirm-conditions!
   [:handovers :complete] fieldwork/complete-handover!
   [:site-tasks :start] fieldwork/start-site-task! [:site-tasks :complete] fieldwork/complete-site-task!})


(defn command!
  "将明确HTTP动作分派给相应业务状态机."
  [svc actor id resource action rid body]
  (if (= [resource action] [:configuration :update]) (d/configure! svc actor id body)
    (if-let [command (get commands [resource action])] (command svc actor id rid body)
      (r/fail! 404 "交付命令不存在"))))
