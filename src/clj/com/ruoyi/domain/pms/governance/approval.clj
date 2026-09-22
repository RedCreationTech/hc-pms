(ns com.ruoyi.domain.pms.governance.approval
  "项目章程与正式变更的类型化内容和独立评审."
  (:require [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(def charter-fields
  "章程必备内容字段."
  [:title :objective :scope :success_criteria :sponsor_id])

(def change-fields
  "变更必须明确的影响维度."
  [:title :reason :scope_impact :schedule_impact :cost_impact :quality_impact :resource_impact])

(defn content!
  "分别校验章程和变更内容,拒绝任意JSON字段."
  [q kind body]
  (let [fields (if (= kind "charter") charter-fields change-fields)]
    (s/input! body fields)
    (cond-> (into {} (for [field (remove #{:sponsor_id} fields)]
                       [field (s/text! body field (if (= field :title) 200 4000))]))
      (= kind "charter") (assoc :sponsor_id (s/user! q (:sponsor_id body))))))

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
