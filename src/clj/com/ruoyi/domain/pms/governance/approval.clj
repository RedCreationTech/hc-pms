(ns com.ruoyi.domain.pms.governance.approval
  "项目章程与正式变更的类型化内容和独立评审."
  (:require [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(def charter-fields
  "章程必备内容字段."
  [:title :objective :scope :success_criteria :sponsor_id])

(def charter-budget-fields
  "章程可选初始预算字段,随内容版本不可变持久化."
  [:initial_budget :budget_currency])

(def charter-pm-fields
  "章程可选授权项目经理字段,随内容版本不可变持久化."
  [:authorized_pm_id])

(def change-fields
  "变更必须明确的影响维度."
  [:title :reason :scope_impact :schedule_impact :cost_impact :quality_impact :resource_impact])

(def change-impact-fields
  "变更可选量化影响字段,随内容版本不可变持久化."
  [:schedule_impact_days :cost_impact_amount])

(def high-impact-schedule-days
  "工期影响达到该天数即判定为高影响变更."
  10)

(def high-impact-cost-minor
  "成本影响达到该最小单位(即100000.00)即判定为高影响变更."
  10000000)

(defn- charter-budget!
  "校验章程可选初始预算:金额非负并规范化为两位小数,币种缺省CNY;未填预算时不写入任何预算键."
  [body]
  (when-let [raw (:initial_budget body)]
    (let [amount (money/amount! raw "初始预算")]
      (when (neg? amount) (r/fail! 400 "初始预算不得为负数"))
      (let [currency (if-let [c (:budget_currency body)]
                       (do (when-not (money/currencies c) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD")) c)
                       "CNY")]
        {:initial_budget (money/money amount) :budget_currency currency}))))

(defn- charter-pm!
  "校验章程可选授权项目经理:须为有效本地用户;未指定时不写入任何授权PM键,仍由项目 manager_id 隐含承载."
  [q body]
  (when-let [uid (:authorized_pm_id body)]
    {:authorized_pm_id (s/user! q uid)}))

(defn- change-impact!
  "校验变更可选量化影响: 工期影响为0..3650整数天, 成本影响为最多两位小数非负金额并规范化; 未填时不写入对应键."
  [body]
  (cond-> {}
    (some? (:schedule_impact_days body))
    (assoc :schedule_impact_days
           (let [s (str (:schedule_impact_days body))]
             (when-not (re-matches #"[0-9]{1,4}" s) (r/fail! 400 "工期影响须为0至3650的整数天"))
             (let [d (Long/parseLong s)]
               (when (> d 3650) (r/fail! 400 "工期影响不得超过3650天"))
               d)))
    (some? (:cost_impact_amount body))
    (assoc :cost_impact_amount
           (let [amount (money/amount! (:cost_impact_amount body) "成本影响")]
             (when (neg? amount) (r/fail! 400 "成本影响金额不得为负数"))
             (money/money amount)))))

(defn content!
  "分别校验章程和变更内容,拒绝任意JSON字段."
  [q kind body]
  (let [fields (if (= kind "charter") charter-fields change-fields)
        allowed (if (= kind "charter")
                  (into (into charter-fields charter-budget-fields) charter-pm-fields)
                  (into change-fields change-impact-fields))]
    (s/input! body allowed)
    (cond-> (into {} (for [field (remove #{:sponsor_id} fields)]
                       [field (s/text! body field (if (= field :title) 200 4000))]))
      (= kind "charter") (assoc :sponsor_id (s/user! q (:sponsor_id body)))
      (= kind "charter") (merge (charter-budget! body))
      (= kind "charter") (merge (charter-pm! q body))
      (= kind "change") (merge (change-impact! body)))))

(defn create!
  "创建章程的新版本或独立变更申请."
  [svc actor id kind body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".created")
    (fn [q project]
      (let [fields (content! q kind body)
            prior (when (= kind "charter") (s/records q project kind))
            revision (inc (reduce max 0 (map :revision prior)))]
        (s/insert! q project actor kind
                   (cond-> fields (= kind "charter") (assoc :code "charter"))
                   {:revision revision})))))

(defn revise!
  "为已存在的变更或章程创建新内容版本,旧审批依据不漂移."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".revised")
    (fn [q project]
      (let [old (s/latest! q project (s/record! q project kind rid))
            content (content! q kind body)]
        (s/insert! q project actor kind (assoc content :code (:code old) :previous_id rid)
                   {:revision (inc (:revision old))})))))

(defn submit!
  "冻结当前内容并选择具有权限的独立审核人."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".submitted")
    (fn [q project]
      (s/input! body [:reviewer_id])
      (let [record (s/latest! q project (s/record! q project kind rid))
            reviewer (s/reviewer! q project actor (:reviewer_id body))]
        (s/status! record #{"draft" "rejected"})
        (s/change! q project record "in_review"
                   {:reviewer_id reviewer :submitted_by (:user_id actor)})))))

(defn decide!
  "指定独立审核人批准或退回当前提交版本."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:quality:approve" body (str kind ".decided") {:write? false}
    (fn [q project]
      (s/input! body [:decision :reason])
      (let [record (s/latest! q project (s/record! q project kind rid))
            decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
        (s/status! record #{"in_review"})
        (s/decision-actor! actor record)
        (s/change! q project record decision
                   {:decision_reason (s/text! body :reason)
                    :decided_by (:user_id actor)})))))

(defn approved-change!
  "验证后续基线命令所引用的变更确已通过独立批准."
  [q project rid]
  (let [record (s/latest! q project (s/record! q project "change" rid))]
    (s/status! record #{"approved"})
    record))

(defn change-read-model
  "为变更读模型补充只读派生高影响判定: 工期影响达到阈值或成本影响金额达到阈值时 change_high_impact 为 true; 仅读取时计算, 不落存储."
  [record]
  (let [days (:schedule_impact_days record)
        cost-str (:cost_impact_amount record)
        cost-minor (when (and (some? cost-str) (not= "" cost-str)) (money/amount! cost-str "成本影响"))
        high? (or (when (number? days) (>= days high-impact-schedule-days))
                  (when (some? cost-minor) (>= cost-minor high-impact-cost-minor)))]
    (assoc record :change_high_impact (boolean high?))))
