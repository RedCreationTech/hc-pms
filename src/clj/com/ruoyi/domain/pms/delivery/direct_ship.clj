(ns com.ruoyi.domain.pms.delivery.direct-ship
  "D04/D05 直发申请 (本地侧): 层流罩直发与非层流罩直发共用一条受控链路.
   申请须由对应计划任务发起并固定该任务的节点归属; 审批由申请人指定的独立审核人完成;
   D05 非层流罩还必须按物料编码关联采购订单号, 并在提交与批准时检查既有发运工单与已发运事实.
   外部 OA 审批回执、采购订单回执与多目标同步补偿仍待合同, 本地如实标注 not_configured."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.delivery.store :as d]
    [com.ruoyi.domain.pms.governance.store :as g]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.planning.progress :as progress]
    [com.ruoyi.domain.pms.rules :as r]))


(def kinds
  "直发申请类别: 层流罩 (D04) 与非层流罩 (D05)."
  #{"laminar" "non_laminar"})


(def kind
  "交付对象类型: 直发申请."
  "direct-ship")


(def statuses
  "直发申请状态: 草稿 -> 审批中 -> 已批准/已驳回; 已批准后可登记采购订单回执与发运协调结果."
  #{"draft" "in_review" "approved" "rejected"})


(def external-sync
  "外部系统同步状态: OA 审批与采购订单一律 not_configured, 不冒充已回执."
  "not_configured")


(defn- items!
  "校验直发物料清单: 不重复物料编码, 明确数量与计量单位."
  [items]
  (when-not (and (vector? items) (<= 1 (count items) 200))
    (r/fail! 400 "直发物料清单需要1到200行"))
  (let [rows (mapv (fn [item]
                     (r/object! item [:code :name :quantity :unit])
                     (let [quantity (:quantity item)]
                       (when-not (and (integer? quantity) (<= 1 quantity 1000000000))
                         (r/fail! 400 "直发物料数量必须为规定范围内整数"))
                       {:code (g/text! item :code 100) :name (g/text! item :name 200)
                        :quantity quantity :unit (g/text! item :unit 30)}))
                   items)]
    (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "直发物料编码不得重复"))
    rows))


(defn- task-node!
  "直发申请必须由对应计划任务发起, 并固定任务所属结构节点 (直接声明或沿父链继承)."
  [q project task-id]
  (let [task (q :planning/task {:project_id (:project_id project) :task_id task-id})
        node-id (when task (progress/task-node (q :planning/tasks {:project_id (:project_id project)}) task))]
    (when-not task (r/fail! 404 "关联任务不存在或不属于本项目"))
    (when-not node-id (r/fail! 409 "关联任务未归属任何结构节点, 无法确定直发目标"))
    (assoc task :node_id node-id)))


(defn- name-if-keyword
  "物料编码在 JSON 解析后可能为关键字, 统一归一为字符串键."
  [code]
  (if (keyword? code) (name code) code))


(defn- purchase-orders!
  "D05 非层流罩直发: 按物料编码关联采购订单号, 清单内每个编码都必须有归属订单."
  [orders]
  (when-not (and (map? orders) (seq orders))
    (r/fail! 400 "非层流罩直发必须按物料编码关联采购订单"))
  (into {}
        (for [[code order] orders]
          (if (and (string? order) (not (str/blank? order)))
            [(str/trim (name-if-keyword code)) (str/trim order)]
            (r/fail! 400 "采购订单号必须为文本且不为空")))))


(defn- shipments-for-node
  "取来源任务归属同一结构节点的发运记录; 发运记录本身只带 task_id, 经计划任务解析归属节点."
  [q project node-id]
  (let [tasks (q :planning/tasks {:project_id (:project_id project)})
        task-node (fn [task-id]
                    (when-let [task (first (filter #(= task-id (:task_id %)) tasks))]
                      (progress/task-node tasks task)))]
    (filterv #(= node-id (task-node (:task_id %))) (d/records q project "shipment"))))


(defn- existing-work-order!
  "检查既有发运工单 (同一节点) 与已发运事实. 已发运直接拒绝; 既有未发运工单也须先协调,
   由调用方列出冲突并发起地址/发运状态调整, 不得静默另开一条直发."
  [q project node-id]
  (let [same-node (shipments-for-node q project node-id)
        dispatched (filterv #(contains? #{"dispatched" "received"} (:status %)) same-node)]
    (when (seq dispatched)
      (r/fail! 409 "该节点已存在已发运工单, 不得再次直发"))
    same-node))


(defn- normalized
  "把读回记录里的采购订单键归一为物料编码字符串; 其余字段原样返回."
  [record]
  (if (nil? (:purchase_orders record))
    record
    (assoc record :purchase_orders
           (into {} (map (fn [[k v]] [(name-if-keyword k) v]) (:purchase_orders record))))))


(defn- conflicts
  "只读派生直发协调冲突清单, 供界面与审批人核对: 缺订单 / 既有发运工单 / 已发运."
  [q project record]
  (let [shipments (shipments-for-node q project (:node_id record))
        dispatched (filterv #(contains? #{"dispatched" "received"} (:status %)) shipments)
        orders (:purchase_orders (normalized record))
        missing-orders (when (= "non_laminar" (:direct_ship_type record))
                         (vec (for [item (:items record)
                                    :when (str/blank? (get orders (:code item) ""))]
                                (:code item))))]
    {:purchase_orders_missing missing-orders
     :existing_work_orders (mapv (fn [s] {:id (:id s) :code (:code s) :status (:status s)}) shipments)
     :already_dispatched (mapv (fn [s] {:id (:id s) :code (:code s)}) dispatched)
     :blocked (boolean (or (seq missing-orders) (seq shipments) (seq dispatched)))}))


(defn- base-fields
  "两类直发共用的申请内容: 类型, 任务来源, 节点归属, 物料清单与装配要求."
  [q project body]
  (let [type (g/enum! (:direct_ship_type body) kinds "direct_ship_type")
        task (task-node! q project (g/text! body :task_id 36))]
    (merge {:direct_ship_type type
            :task_id (:task_id task)
            :node_id (:node_id task)
            :items (items! (:items body))
            :assembly_requirement (g/optional-text! body :assembly_requirement 1000)
            :ship_address (g/optional-text! body :ship_address 500)
            :external_sync_status external-sync}
           (when (= "non_laminar" type)
             {:purchase_orders (purchase-orders! (:purchase_orders body))}))))


(defn create!
  "登记直发申请: 层流罩需指定审批类别, 非层流罩还需按物料编码关联采购订单."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.direct-ship.created" false
             (fn [q project]
               (g/input! body [:code :title :direct_ship_type :task_id :requirement_ids :items
                               :purchase_orders :assembly_requirement :ship_address :owner_id])
               (normalized (d/insert! q project actor kind
                                      (merge (d/references! q project body)
                                             (base-fields q project body)
                                             {:code (g/text! body :code 100) :title (g/text! body :title 200)
                                              :owner_id (d/owner! q project (:owner_id body))})
                                      "draft")))))


(defn update!
  "维护草稿或被驳回的直发申请; 已进入审批的申请内容不可再改."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.direct-ship.updated" false
             (fn [q project]
               (let [record (d/record! q project kind rid)]
                 (g/status! record #{"draft" "rejected"})
                 (g/input! body [:title :direct_ship_type :task_id :requirement_ids :items
                                 :purchase_orders :assembly_requirement :ship_address :owner_id])
                 (normalized
                   (d/change! q project record (:status record)
                              (merge (base-fields q project (merge record body))
                                     {:title (g/text! (merge record body) :title 200)
                                      :owner_id (d/owner! q project (or (:owner_id body) (:owner_id record)))})))))))


(defn submit!
  "提交直发审批: 复核既有发运工单与订单归属, 由申请人指定独立审核人."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.direct-ship.submitted" false
             (fn [q project]
               (g/input! body [:reviewer_id :evidence_ids])
               (let [record (d/record! q project kind rid)
                     _ (g/status! record #{"draft" "rejected"})
                     _ (existing-work-order! q project (:node_id record))
                     conflicts (conflicts q project record)]
                 (when (seq (:existing_work_orders conflicts))
                   (r/fail! 409 "该节点已有发运工单, 请先协调既有工单的发运状态与地址"))

                 (when (seq (:purchase_orders_missing conflicts))
                   (r/fail! 409 (str "非层流罩直发缺少采购订单: "
                                     (str/join ", " (:purchase_orders_missing conflicts)))))
                 (d/submit! q project actor record body)))))


(defn decide!
  "独立审核人批准或驳回直发申请; 批准时再次检查已发运事实."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "delivery.direct-ship.decided" {:write? false}
             (fn [q project]
               (g/input! body [:decision :reason])
               (let [record (d/record! q project kind rid)
                     decision (g/enum! (:decision body) r/decisions "decision")]
                 (when (= "approved" decision)
                   (existing-work-order! q project (:node_id record))
                   (let [conflicts (conflicts q project record)]
                     (when (seq (:purchase_orders_missing conflicts))
                       (r/fail! 409 "非层流罩直发缺少采购订单, 不得批准"))
                     (when (seq (:existing_work_orders conflicts))
                       (r/fail! 409 "该节点已有发运工单, 不得批准新的直发"))))
                 (normalized (d/decide! q project actor record body "approved"))))))


(defn ledgers
  "已批准直发申请的只读对账视图: 类型, 来源任务与节点名称, 订单与冲突清单."
  [q project rows]
  (let [tasks (into {} (map (juxt :task_id :name)) (q :planning/tasks {:project_id (:project_id project)}))
        nodes (into {} (map (juxt :node_id :node_code)) (q :pms/nodes {:project_id (:project_id project)}))]
    (mapv (fn [record]
            (-> (normalized record)
                (select-keys [:id :code :title :status :direct_ship_type :task_id :node_id
                              :items :purchase_orders :assembly_requirement :ship_address
                              :owner_id :reviewer_id :decision_reason])
                (assoc :external_sync_status external-sync
                       :task_name (get tasks (:task_id record))
                       :node_code (get nodes (:node_id record)))
                (merge (conflicts q project record))))
          rows)))


(defn direct-ship-summary
  "项目级直发申请只读汇总: 按类别统计草稿/审批中/已批准与协调冲突."
  [q project rows]
  (let [count-of (fn [pred] (count (filterv pred rows)))
        approved (filterv #(= "approved" (:status %)) rows)]
    {:total (count rows)
     :draft (count-of #(= "draft" (:status %)))
     :in_review (count-of #(= "in_review" (:status %)))
     :approved (count approved)
     :rejected (count-of #(= "rejected" (:status %)))
     :laminar (count-of #(= "laminar" (:direct_ship_type %)))
     :non_laminar (count-of #(= "non_laminar" (:direct_ship_type %)))
     :conflicts (count (filter #(:blocked (conflicts q project %)) rows))
     :external_sync_status external-sync}))
