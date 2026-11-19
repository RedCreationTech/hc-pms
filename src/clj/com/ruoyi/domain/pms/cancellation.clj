(ns com.ruoyi.domain.pms.cancellation
  "B17 审批驱动的项目/单机取消 (本地侧).
   取消申请须关联已批准外部变更的依据编号与受影响对象 (结构节点/计划/交付记录),
   审批通过后停止该范围后续下发, 未执行的外发记录列为待补偿对账项.
   外发后已执行部分的真实补偿与 OA/ERP 回执仍待外部合同, 本地如实标注 not_configured."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.ruoyi.domain.pms.approval-chain :as chain]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.governance.store :as g]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r]))


(def kind
  "治理记录类型: 取消申请."
  "cancellation")


(def statuses
  "取消申请状态: 待审 -> 已提交 -> 已批准/已驳回/已撤回."
  #{"pending" "submitted" "approved" "rejected" "withdrawn"})


(def targets
  "取消范围: 整项目 / 指定结构节点 (子项目或单机)."
  #{"project" "node"})


(def external-kinds
  "受影响对象类型: 结构节点, 计划任务, 交付记录."
  #{"node" "task" "delivery"})


(def outbound-kinds
  "会向外部下发的交付记录类型; 取消后停止后续下发并登记待补偿."
  #{"shipment" "service"})


(def source-sync
  "外部变更依据的同步状态: 本地登记不适用, OA 一律 not_configured (待真实合同)."
  {"local" "not_applicable" "oa" "not_configured"})


(defn- scope-nodes
  "取消范围内 (节点及其全部后代) 的结构节点; 整项目时返回全部节点."
  [q project target node-id]
  (let [nodes (q :pms/nodes {:project_id (:project_id project)})]
    (if (= "project" target)
      (vec nodes)
      (loop [ids #{node-id}
             frontier [node-id]]
        (let [children (filterv #(contains? (set frontier) (:parent_id %)) nodes)]
          (if (empty? children)
            (filterv #(contains? ids (:node_id %)) nodes)
            (recur (into ids (map :node_id children)) (mapv :node_id children))))))))


(def ^:private open-task-statuses
  "仍未完成的任务状态 (与计划域一致: todo/in_progress 为未完成)."
  #{"todo" "in_progress"})


(defn- all-tasks
  [q project]
  (q :planning/tasks {:project_id (:project_id project)}))


(defn affected-tasks
  "范围内仍未完成的计划任务."
  [q project node-ids]
  (let [ids (set (map :node_id node-ids))]
    (->> (all-tasks q project)
         (filter #(and (ids (:node_id %)) (open-task-statuses (:status %))))
         vec)))


(defn outbound-records
  "范围内已下发给外部的交付记录 (经计划任务归属节点解析), 用于停止后续下发并列出待补偿项."
  [q project node-ids]
  (let [node-of (into {} (map (juxt :task_id :node_id)) (all-tasks q project))
        ids (set (map :node_id node-ids))]
    (vec (for [delivery-kind outbound-kinds
               row (q :delivery/list {:project_id (:project_id project) :kind delivery-kind})
               :let [record (g/decode row)]
               :when (ids (node-of (:task_id record)))]
           record))))


(defn- impacted!
  "调用方显式声明的受影响对象必须真实属于取消范围, 不得跨项目或越范围引用."
  [q project node-ids items]
  (when-not (vector? items) (r/fail! 400 "受影响对象必须是数组"))
  (let [nodes (set (map :node_id node-ids))
        task-ids (set (map :task_id (affected-tasks q project node-ids)))
        deliveries (set (map :id (outbound-records q project node-ids)))]
    (doseq [item items]
      (let [kind (:kind item) id (:id item)]
        (when-not (contains? external-kinds kind) (r/fail! 400 "受影响对象类型必须为 node, task 或 delivery"))
        (when-not (string? id) (r/fail! 400 "受影响对象必须填写编号"))
        (when-not (case kind "node" (nodes id) "task" (task-ids id) "delivery" (deliveries id))
          (r/fail! 409 (str "受影响对象 " id " 不属于本次取消范围或不存在")))))
    (vec (distinct items))))


(defn- change-basis!
  "外部变更依据: 须引用本项目已批准的变更记录, 否则不能发起取消."
  [q project basis-id]
  (when-not (string? basis-id) (r/fail! 400 "取消必须关联已批准的变更依据"))
  (let [change (g/record! q project "change" basis-id)]
    (when-not (= "approved" (:status change)) (r/fail! 409 "外部变更依据尚未批准"))
    change))


(defn- scope!
  "解析并校验取消范围, 返回范围内节点."
  [q project body]
  (let [target (g/enum! (or (:target body) "project") targets "取消范围")]
    (case target
      "project" (scope-nodes q project "project" nil)
      "node" (let [node-id (:node_id body)
                   node (when node-id (q :pms/node {:project_id (:project_id project) :node_id node-id}))]
               (when-not node (r/fail! 404 "结构节点不存在或不属于本项目"))
               (scope-nodes q project "node" (:node_id node))))))


(defn create!
  "登记取消申请: 关联已批准变更依据, 冻结受影响对象清单, 状态待提交."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:transition" body "cancellation.created"
             (fn [q project]
               (r/object! body [:version :target :node_id :basis_change_id :reason :source :reference_no :items])
               (let [nodes (scope! q project body)
                     node (when (:node_id body)
                            (q :pms/node {:project_id (:project_id project) :node_id (:node_id body)}))
                     basis (change-basis! q project (:basis_change_id body))
                     source (g/enum! (or (:source body) "local") (set (keys source-sync)) "依据来源")
                     items (impacted! q project nodes (or (:items body) []))
                     tasks (affected-tasks q project nodes)
                     outbound (outbound-records q project nodes)]
                 (-> (g/insert! q project actor kind
                                {:title (str "取消申请 " (:project_no project)
                                             (when node (str " / " (:node_code node) " " (:name node))))
                                 :target (:target body)
                                 :node_id (:node_id node)
                                 :node_code (:node_code node)
                                 :node_name (:name node)
                                 :reason (g/text! body :reason)
                                 :basis_change_id (:record_id basis)
                                 :basis_change_code (:code basis)
                                 :basis_change_title (:title basis)
                                 :source source
                                 :source_sync (get source-sync source)
                                 :reference_no (g/optional-text! body :reference_no 100)
                                 :project_status_at_request (:status project)
                                 :items items
                                 :affected_nodes (mapv (fn [n]
                                                         {:id (:node_id n) :code (:node_code n)
                                                          :name (:name n) :type (:node_type n)}) nodes)
                                 :affected_tasks (mapv (fn [t] {:id (:task_id t) :code (:code t) :status (:status t)}) tasks)
                                 :outbound (mapv (fn [d] {:id (:id d) :type (:kind d) :status (:status d)}) outbound)
                                 :external_compensation "not_configured"
                                 :requested_by (:user_id actor)}
                                {:status "pending"})
                     (assoc :affected_task_total (count tasks) :outbound_total (count outbound)))))))


(defn withdraw!
  "申请人撤回尚未提交的取消申请."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:transition" body "cancellation.withdrawn"
             (fn [q project]
               (r/object! body [:version :reason])
               (let [record (g/record! q project kind rid)]
                 (g/status! record #{"pending"})
                 (when-not (= (:user_id actor) (:requested_by record)) (r/fail! 403 "只有申请人可以撤回"))
                 (g/change! q project record "withdrawn"
                            {:withdraw_reason (g/text! body :reason)
                             :withdrawn_by (:user_id actor)
                             :withdrawn_at (str (java.time.Instant/now))})))))


(defn submit!
  "提交取消审批: 复核变更依据与受影响对象仍然成立, 冻结审批依据."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:transition" body "cancellation.submitted"
             (fn [q project]
               (r/object! body [:version :reviewer_id :reason])
               (let [record (g/record! q project kind rid)]
                 (g/status! record #{"pending"})
                 (change-basis! q project (:basis_change_id record))
                 (let [nodes (scope! q project record)
                       items (impacted! q project nodes (:items record))
                       policy? (some? (chain/policy q "cancellation"))
                       reviewer (when (or (not policy?) (some? (:reviewer_id body)))
                                  (k/user! q project (:reviewer_id body) "取消审批人"))]
                   (when (and (not policy?) (nil? reviewer)) (r/fail! 400 "请指定取消审批人"))
                   (when (= reviewer (:user_id actor)) (r/fail! 409 "审批人不得为提交人"))
                   (let [started (chain/start! q actor project "cancellation" rid
                                               {:reviewer-id reviewer
                                                :title (str "取消审批 " (:code record))})]
                     (g/change! q project record "submitted"
                                {:items items
                                 :reviewer_id (or reviewer (:first-approver started))
                                 :submitted_by (:user_id actor)
                                 :submitted_on (str (java.time.Instant/now))})))))))


(defn- stop-scope?
  "取消已批准, 且记录范围覆盖该节点 (整项目或节点及其后代)."
  [approved node-id]
  (boolean
    (some (fn [record]
            (and (= "approved" (:status record))
                 (case (:target record)
                   "project" true
                   "node" (some #(= node-id (:id %)) (:affected_nodes record))
                   false)))
          approved)))


(defn cancelled-node-ids
  "已批准取消所覆盖的全部结构节点编号 (含后代), 供交付/计划侧停止后续下发."
  [q project]
  (reduce (fn [acc record]
            (if (= "approved" (:status record))
              (into acc (map :id (:affected_nodes record)))
              acc))
          #{}
          (g/records q project kind)))


(defn- reconcile!
  "批准时按当时真实数据重算受影响对象, 生成停止下发与待补偿对账清单."
  [q project record]
  (let [nodes (scope! q project record)
        tasks (affected-tasks q project nodes)
        outbound (outbound-records q project nodes)]
    {:affected_nodes (mapv (fn [n] {:id (:node_id n) :code (:node_code n) :name (:name n) :type (:node_type n)}) nodes)
     :affected_tasks (mapv (fn [t] {:id (:task_id t) :code (:code t) :status (:status t)}) tasks)
     :outbound (mapv (fn [d] {:id (:id d) :type (:kind d) :status (:status d)}) outbound)
     :dispatch_stopped true
     :stopped_tasks (count tasks)
     :pending_compensation (count outbound)
     :external_compensation "not_configured"}))


(defn- finalize!
  "审批链落定取消申请: 通过前复核依据仍有效, 通过后冻结停止下发范围."
  [q actor project rid decision reason]
  (let [record (g/record! q project kind rid)]
    (when-not (= "submitted" (:status record)) (r/fail! 409 "没有待处理的取消申请"))
    (when (= "approved" decision)
      (change-basis! q project (:basis_change_id record))
      (let [reconcile (reconcile! q project record)]
        (g/change! q project record "approved"
                   (merge reconcile
                          {:decision_reason (or reason "")
                           :decided_by (:user_id actor)
                           :decided_on (str (java.time.Instant/now))})))
      record)
    (when (= "rejected" decision)
      (g/change! q project record "rejected"
                 {:decision_reason (or reason "")
                  :decided_by (:user_id actor)
                  :decided_on (str (java.time.Instant/now))
                  :dispatch_stopped false}))))


(chain/register-adapter! "cancellation" {:finalize finalize!})


(defn review!
  "未配置审批策略时的单人独立审核入口, 与策略链互斥."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:transition" body "cancellation.reviewed" {:write? false}
             (fn [q project]
               (r/object! body [:version :decision :reason])
               (let [record (g/record! q project kind rid)
                     decision (g/enum! (:decision body) r/decisions "decision")]
                 (g/status! record #{"submitted"})
                 (chain/guard-legacy-decision! q "cancellation" rid)
                 (g/decision-actor! actor record)
                 (finalize! q actor project rid decision (g/optional-text! body :reason 1000))
                 (g/record! q project kind rid)))))


(defn- summary
  [rows]
  {:total (count rows)
   :pending (count (filter #(= "pending" (:status %)) rows))
   :submitted (count (filter #(= "submitted" (:status %)) rows))
   :approved (count (filter #(= "approved" (:status %)) rows))
   :stopped (reduce + (map #(long (or (:stopped_tasks %) 0)) (filter #(= "approved" (:status %)) rows)))
   :pending_compensation (reduce + (map #(long (or (:pending_compensation %) 0)) (filter #(= "approved" (:status %)) rows)))})


(defn workspace
  "项目取消申请只读汇总: 申请明细, 已生效停止范围与待补偿对账项."
  [svc actor id]
  (r/permit! actor ["pms:project:list" "pms:project:query" "pms:project:transition"])
  (let [q (:query-fn svc)
        project (r/access! q actor (q :pms/project {:project_id id}) false)
        rows (g/records q project kind)]
    {:version (:version project)
     :project_status (:status project)
     :targets (vec (sort targets))
     :external_compensation "not_configured"
     :rows rows
     :summary (summary rows)}))


(defn view
  "单条取消申请明细, 含审批链状态."
  [svc actor id rid]
  (let [{:keys [rows] :as data} (workspace svc actor id)]
    (assoc data :record (or (first (filter #(= rid (:id %)) rows)) (r/fail! 404 "取消申请不存在")))))


(defn approval-types
  "取消类型是否已配置多级审批策略, 供前端决定提交时是否必填审批人."
  [svc actor]
  (r/permit! actor ["pms:project:list" "pms:project:query" "pms:project:transition"])
  {:requires_reviewer (not (some? (chain/policy (:query-fn svc) "cancellation")))
   :configured (contains? (set (keys config/approval-types)) "cancellation")})
