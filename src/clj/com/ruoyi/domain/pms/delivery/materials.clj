(ns com.ruoyi.domain.pms.delivery.materials
  "物料申请,独立批准,冻结BOM和可核对齐套备料."
  (:require [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.store :as g]
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
      (g/input! body [:code :title :request_type :owner_id :needed_on :items :task_id :requirement_ids])
      (d/insert! q project actor "material"
                 (merge (d/references! q project body)
                        {:code (g/text! body :code 100) :title (g/text! body :title 200)
                         :request_type (g/enum! (:request_type body) #{"standard" "long_lead" "raw_material" "direct_ship"} "request_type")
                         :owner_id (d/owner! q project (:owner_id body))
                         :needed_on (g/date! body :needed_on) :items (items! (:items body))
                         :external_sync_status "not_configured"}) "draft"))))


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
