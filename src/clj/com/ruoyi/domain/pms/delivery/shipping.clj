(ns com.ruoyi.domain.pms.delivery.shipping
  "真实发运记录,独立签收验证和售后异常处理闭环."
  (:require [com.ruoyi.domain.pms.delivery.fieldwork :as fieldwork]
            [com.ruoyi.domain.pms.delivery.production :as production]
            [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.gates :as gates]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.rules :as r]))


(defn create-shipment!
  "建立指定装配范围和签收验证人的发运准备记录."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.shipment.created" false
    (fn [q project]
      (g/input! body [:code :title :assembly_ids :consignee :delivery_address :planned_date :reviewer_id :task_id :requirement_ids])
      (let [ids (:assembly_ids body)]
        (when-not (and (vector? ids) (<= 1 (count ids) 50) (= (count ids) (count (set ids))))
          (r/fail! 400 "发运范围必须为1到50个不重复装配对象"))
        (doseq [rid ids] (d/record! q project "assembly" rid))
        (d/insert! q project actor "shipment"
                   (merge (d/references! q project body)
                          {:code (g/text! body :code 100) :title (g/text! body :title 200)
                           :assembly_ids ids :consignee (g/text! body :consignee 200)
                           :delivery_address (g/text! body :delivery_address 1000)
                           :planned_date (g/date! body :planned_date)
                           :reviewer_id (g/reviewer! q project actor (:reviewer_id body))
                           :external_sync_status "not_configured"}) "draft")))))


(defn shipping-ready!
  "发运前核对全部装配交检与适用SIT/FAT试验."
  [q project shipment]
  (when (some #(and (= "blocker" (:severity %)) (not= "closed" (:status %))) (g/records q project "issue"))
    (r/fail! 409 "仍有未独立关闭的阻塞问题,不能发运"))
  (let [config (d/configuration q project)
        types (remove #{"SAT"} (:required_test_types config))]
    (fieldwork/conditions-ready! config shipment)
    (doseq [rid (:assembly_ids shipment)]
      (g/status! (d/record! q project "assembly" rid) #{"approved"})
      (when (some #{"quality"} (:required_stages config))
        (doseq [type types]
          (when-not (production/type-approved? q project rid type)
            (r/fail! 409 (str "发运范围缺少已批准试验: " type)))))))
  true)


(defn submit-shipment!
  "在所有适用发运前条件满足时申请独立放行."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.shipment.submitted" false
    (fn [q project]
      (g/input! body [:evidence_ids])
      (let [shipment (d/record! q project "shipment" rid)]
        (shipping-ready! q project shipment)
        (d/submit! q project actor shipment (assoc body :reviewer_id (:reviewer_id shipment)))))))


(defn decide-shipment!
  "指定审核人独立检查发运准备并批准放行或退回."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.shipment.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (let [shipment (d/record! q project "shipment" rid)]
        (when (= "approved" (:decision body)) (shipping-ready! q project shipment))
        (d/decide! q project actor shipment body "released")))))


(defn dispatch!
  "登记真实发运日期,物流标识和装箱发运证据,不模拟外部回传."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.shipment.dispatched" false
    (fn [q project]
      (g/input! body [:shipped_on :tracking_no :evidence_ids])
      (d/execution! project)
      (let [shipment (d/record! q project "shipment" rid)]
        (g/status! shipment #{"released"})
        (shipping-ready! q project shipment)
        (gates/checkpoint-ready! q project "shipment.dispatch")
        (when (= (:user_id actor) (:reviewer_id shipment))
          (r/fail! 409 "指定签收验证人不能同时登记实际发运"))
        (let [shipped (d/change! q project shipment "shipped"
                                 {:shipped_on (d/actual-date! body :shipped_on) :tracking_no (g/text! body :tracking_no 200)
                                  :shipped_by (:user_id actor) :shipment_source "manual_record"
                                  :dispatch_evidence_ids (g/evidence! q project (:evidence_ids body) true)})]
          ;; E07: 发运事实触发交底任务与截止期.
          (when-not (some #(= (:id shipped) (:shipment_id %)) (d/records q project "handover"))
            (fieldwork/create-handover! q project actor shipped (d/configuration q project)))
          shipped)))))


(defn- receipt-service!
  "拒收或条件接收生成售后异常,同一未关闭异常不重复建单."
  [q project actor shipment body]
  (let [prior (when-let [rid (:receipt_service_id shipment)] (d/record! q project "service" rid))
        fields {:title (g/text! body :exception_reason) :owner_id (d/owner! q project (:owner_id body))
                :due_date (g/date! body :due_date) :shipment_id (:id shipment) :source "receipt"}]
    (if (and prior (not= "closed" (:status prior))) prior
      (d/insert! q project actor "service"
                 (merge (select-keys shipment [:task_id :requirement_ids]) fields) "open"))))


(defn- receipt-fields!
  "核对签收日期,接收结论与所有历史收货异常."
  [q project shipment body]
  (let [date (d/actual-date! body :received_on)
        acceptance (g/enum! (:acceptance body) #{"accepted" "conditional" "rejected"} "acceptance")]
    (when (neg? (compare date (:shipped_on shipment))) (r/fail! 400 "签收日期不得早于实际发运日期"))
    (when (= "accepted" acceptance)
      (doseq [rid (:receipt_service_ids shipment)]
        (g/status! (d/record! q project "service" rid) #{"closed"})))
    {:received_on date :acceptance acceptance :receiver_name (g/text! body :receiver_name 200)
     :receipt_evidence_ids (g/evidence! q project (:evidence_ids body) true)}))


(defn receipt!
  "指定独立验证人核实签收凭据,非完整接受强制生成售后异常."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.shipment.receipt-confirmed" true
    (fn [q project]
      (g/input! body [:received_on :receiver_name :acceptance :evidence_ids :exception_reason :owner_id :due_date])
      (d/execution! project)
      (let [shipment (d/record! q project "shipment" rid)
            fields (receipt-fields! q project shipment body)]
        (g/status! shipment #{"shipped" "conditional" "returned"})
        (g/decision-actor! actor shipment)
        (when (= (:user_id actor) (:shipped_by shipment)) (r/fail! 403 "发运登记人不能自行确认签收"))
        (let [service (when-not (= "accepted" (:acceptance fields)) (receipt-service! q project actor shipment body))
              status (case (:acceptance fields) "accepted" "received" "conditional" "conditional" "returned")]
          (d/change! q project shipment status
                     (cond-> (assoc fields :receipt_confirmed_by (:user_id actor)
                                          :receipt_history (conj (vec (:receipt_history shipment))
                                                                 (assoc fields :confirmed_by (:user_id actor))))
                       service (assoc :receipt_service_id (:id service)
                                      :receipt_service_ids (vec (distinct (conj (vec (:receipt_service_ids shipment)) (:id service))))))))))))


(defn create-service!
  "登记关联已发运项目的售后异常及明确责任和期限."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.service.created" false
    (fn [q project]
      (g/input! body [:code :title :shipment_id :owner_id :due_date :task_id :requirement_ids])
      (d/execution! project)
      (let [shipment (d/record! q project "shipment" (:shipment_id body))]
        (g/status! shipment #{"shipped" "received" "conditional" "returned"})
        (d/insert! q project actor "service"
                   (merge (d/references! q project body)
                          {:code (g/text! body :code 100) :title (g/text! body :title 200)
                           :shipment_id (:id shipment) :owner_id (d/owner! q project (:owner_id body))
                           :due_date (g/date! body :due_date) :source "manual_record"}) "open")))))


(defn resolve-service!
  "提交售后处理结果,固定证据并指定独立验证人."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.service.resolution-submitted" false
    (fn [q project]
      (g/input! body [:resolution :reviewer_id :evidence_ids])
      (d/execution! project)
      (let [service (d/record! q project "service" rid)]
        (g/status! service #{"open" "rejected"})
        (d/change! q project service "in_review"
                   {:resolution (g/text! body :resolution) :submitted_by (:user_id actor)
                    :reviewer_id (g/reviewer! q project actor (:reviewer_id body))
                    :evidence_ids (g/evidence! q project (:evidence_ids body) true)})))))


(defn decide-service!
  "指定独立人员验证售后整改后关闭或退回."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.service.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (d/decide! q project actor (d/record! q project "service" rid) body "closed"))))
