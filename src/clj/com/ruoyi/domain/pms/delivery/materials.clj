(ns com.ruoyi.domain.pms.delivery.materials
  "物料申请,独立批准,冻结BOM和可核对齐套备料."
  (:require [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.planning.progress :as progress]
            [com.ruoyi.domain.pms.rules :as r]))


(defn- quantity!
  "当前工程基线采用非负整数计件,拒绝小数和负库存."
  [value positive?]
  (when-not (and (integer? value) (<= (if positive? 1 0) value 1000000000))
    (r/fail! 400 "物料数量必须为规定范围内整数"))
  value)


(defn- items!
  "校验不重复物料编码及明确计量单位."
  [items]
  (when-not (and (vector? items) (<= 1 (count items) 200))
    (r/fail! 400 "物料清单需要1到200行"))
  (let [rows (mapv (fn [item]
                     (r/object! item [:code :name :quantity :unit])
                     {:code (g/text! item :code 100) :name (g/text! item :name 200)
                      :quantity (quantity! (:quantity item) true) :unit (g/text! item :unit 30)}) items)]
    (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "物料编码不得重复"))
    rows))


(defn create-request!
  "创建普通,长周期,原材预投或直发申请并固定任务及URS来源."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.material.created" false
    (fn [q project]
      (g/input! body [:code :title :request_type :owner_id :needed_on :items :task_id :requirement_ids :packaging_spec :node_id])
      (when (seq (:node_id body))
        (when-not (q :pms/node {:project_id (:project_id project) :node_id (:node_id body)})
          (r/fail! 404 "结构节点不存在或不属于本项目")))
      (let [type (g/enum! (:request_type body) #{"standard" "long_lead" "raw_material" "direct_ship" "packaging"} "request_type")]
        (when (and (= "packaging" type) (empty? (:packaging_spec body))) (r/fail! 400 "包材申请必须填写包装规格"))
        (d/insert! q project actor "material"
                   (merge (d/references! q project body)
                          (cond-> {:code (g/text! body :code 100) :title (g/text! body :title 200)
                                   :request_type type
                                   :owner_id (d/owner! q project (:owner_id body))
                                   :needed_on (g/date! body :needed_on) :items (items! (:items body))
                                   :external_sync_status "not_configured"}
                            (seq (:packaging_spec body)) (assoc :packaging_spec (g/text! body :packaging_spec 1000))
                            (seq (:node_id body)) (assoc :node_id (:node_id body)))) "draft")))))


(defn submit-request!
  "申请人提供真实依据并指定独立物料审核人."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.material.submitted" false
    (fn [q project]
      (g/input! body [:reviewer_id :evidence_ids])
      (d/submit! q project actor (d/record! q project "material" rid) body))))


(defn decide-request!
  "独立批准物料申请,不伪造采购订单或外部回执."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.material.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (d/decide! q project actor (d/record! q project "material" rid) body "approved"))))


(defn create-bom!
  "从已批准申请建立固定物料清单,禁止复制未批准申请."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.bom.created" false
    (fn [q project]
      (g/input! body [:code :title :material_request_id])
      (let [request (d/record! q project "material" (:material_request_id body))]
        (g/status! request #{"approved"})
        (d/insert! q project actor "bom"
                   (merge (select-keys request [:task_id :requirement_ids :items :owner_id])
                          {:code (g/text! body :code 100) :title (g/text! body :title 200)
                           :material_request_id (:id request) :external_sync_status "not_configured"}) "draft")))))


(defn freeze!
  "将BOM完整清单提交独立冻结审批."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.bom.freeze-submitted" false
    (fn [q project]
      (g/input! body [:reviewer_id :evidence_ids])
      (d/submit! q project actor (d/record! q project "bom" rid) body))))


(defn decide-bom!
  "冻结或拒绝BOM内容,冻结后仅记录实际齐套信息."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.bom.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (d/decide! q project actor (d/record! q project "bom" rid) body "frozen"))))


(defn- availability!
  "逐行对照冻结BOM,拒绝缺行,多行,重复编码和超量."
  [bom items]
  (when-not (and (vector? items) (= (count items) (count (:items bom)))
                (= (set (map :code items)) (set (map :code (:items bom)))))
    (r/fail! 400 "齐套记录必须完整对应冻结BOM清单"))
  (let [supplied (into {} (map (juxt :code identity) items))]
    (mapv (fn [item]
            (let [actual (get supplied (:code item))]
              (r/object! actual [:code :available_quantity])
              (let [available (quantity! (:available_quantity actual) false)]
                (when (> available (:quantity item)) (r/fail! 400 "实际齐套量不得超过冻结需求量"))
                (assoc item :available_quantity available :complete (= available (:quantity item))))))
          (:items bom))))


(defn kit!
  "登记有来源证据的逐项备料数量,按齐套行数计算比例."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.bom.kitted" false
    (fn [q project]
      (g/input! body [:items :evidence_ids])
      (d/execution! project)
      (let [bom (d/record! q project "bom" rid)
            items (availability! bom (:items body))
            total (count items) complete (count (filter :complete items))
            ready? (= complete total)]
        (g/status! bom #{"frozen" "partial" "ready"})
        (when (and (not ready?)
                   (some #(and (= rid (:bom_id %)) (not= "draft" (:status %))) (d/records q project "assembly")))
          (r/fail! 409 "装配已经开工,不得降低已使用BOM的齐套状态"))
        (d/change! q project bom (if ready? "ready" "partial")
                   {:availability items :complete_line_count complete :required_line_count total
                    :kit_percent (/ (* 100.0 complete) total)
                    :kit_evidence_ids (g/evidence! q project (:evidence_ids body) true)
                    :kit_recorded_by (:user_id actor)})))))


(defn kitting-rollup
  "D06 齐套率多层只读卷积: 冻结BOM按其关联任务所属结构节点归集 (主/子/单机), 分子=齐套行数, 分母=冻结行数;
   缺件清单逐行给出需求量/实际量/缺口与责任人, 读取时派生不落库. 替代料与跨工单口径仍待业务确认."
  [boms tasks nodes]
  (let [frozen (filter #(contains? #{"frozen" "partial" "ready"} (:status %)) boms)
        task-by-id (into {} (map (juxt :task_id identity) tasks))
        bom-node (fn [bom] (when-let [task (get task-by-id (:task_id bom))]
                             (progress/task-node tasks task)))
        with-node (mapv #(assoc % :node_id (bom-node %)
                                :required_lines (count (:items %))
                                :complete_lines (or (:complete_line_count %) 0)) frozen)
        descendants (fn [node-id]
                      (loop [result #{node-id} frontier [node-id]]
                        (let [children (map :node_id (filter #(contains? (set frontier) (:parent_id %)) nodes))]
                          (if (empty? children) result (recur (into result children) (vec children))))))
        percent (fn [complete required] (if (zero? required) 0 (int (Math/round (* 100.0 (/ complete required))))))
        node-rows (mapv (fn [node]
                          (let [ids (descendants (:node_id node))
                                rows (if (= "main" (:node_type node)) with-node (filter #(contains? ids (:node_id %)) with-node))
                                required (reduce + 0 (map :required_lines rows))
                                complete (reduce + 0 (map :complete_lines rows))]
                            {:node_id (:node_id node) :parent_id (:parent_id node) :node_type (:node_type node)
                             :node_code (:node_code node) :name (:name node) :bom_count (count rows)
                             :required_lines required :complete_lines complete :kit_percent (percent complete required)
                             :shortage_lines (- required complete)}))
                        nodes)
        shortages (vec (for [bom with-node
                             item (or (:availability bom) (map #(assoc % :available_quantity 0 :complete false) (:items bom)))
                             :when (not (:complete item))]
                         {:bom_id (:id bom) :bom_code (:code bom) :node_id (:node_id bom)
                          :code (:code item) :name (:name item) :unit (:unit item)
                          :quantity (:quantity item) :available_quantity (or (:available_quantity item) 0)
                          :shortage (- (:quantity item) (or (:available_quantity item) 0))
                          :owner_id (:owner_id bom) :kit_recorded (boolean (:availability bom))}))
        required (reduce + 0 (map :required_lines with-node))
        complete (reduce + 0 (map :complete_lines with-node))]
    {:bom_count (count with-node) :required_lines required :complete_lines complete
     :kit_percent (percent complete required) :nodes node-rows :shortages shortages
     :unassigned_bom_count (count (remove :node_id with-node))}))
