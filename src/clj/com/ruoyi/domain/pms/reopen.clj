(ns com.ruoyi.domain.pms.reopen
  "保持历史归档不变的独立重开审批,批准后返回收尾并要求重新关闭审批."
  (:require [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn request!
  "只对已关闭项目提出明确范围和原因的重开申请."
  [svc actor id body]
  (r/object! body [:version :reason :scope :reviewer_id])
  (k/mutate! svc actor id "pms:project:reopen" body "project.reopen.requested" {:allow-terminal? true}
    (fn [q project]
      (when-not (= "closed" (:status project)) (r/fail! 409 "仅已关闭项目可申请重开"))
      (when (= "submitted" (:status (q :reopen/latest {:project_id id})))
        (r/fail! 409 "已有待审批的重开申请"))
      (let [reviewer (k/user! q project (:reviewer_id body) "重开审批人")
            row {:request_id (k/id) :project_id id :project_version (inc (:version project))
                 :submitted_by (:user_id actor) :reviewer_id reviewer
                 :reason (r/text! (:reason body) "重开原因" 1000 true)
                 :scope (r/text! (:scope body) "修正范围" 2000 true)}]
        (when (= reviewer (:user_id actor)) (r/fail! 400 "重开审批人不能是申请人"))
        (q :reopen/insert! row)
        (assoc row :id (:request_id row) :status "submitted")))))

(defn review!
  "指定独立审批人批准重开或拒绝,批准时清除当前归档标记并保留旧快照."
  [svc actor id request-id body]
  (r/object! body [:version :decision :reason])
  (when-not (contains? #{"approved" "rejected"} (:decision body)) (r/fail! 400 "无效重开审批决定"))
  (k/mutate! svc actor id "pms:project:reopen" body "project.reopen.reviewed"
    {:allow-terminal? true :write? false}
    (fn [q project]
      (let [row (q :reopen/latest {:project_id id})
            reason (r/text! (:reason body) "审批意见" 1000 true)]
        (when-not (and (= "closed" (:status project)) (= request-id (:request_id row))
                       (= "submitted" (:status row))) (r/fail! 409 "该项目没有对应待审重开申请"))
        (k/independent-review! actor (:submitted_by row) (:reviewer_id row))
        (r/changed! (q :reopen/review! (assoc row :status (:decision body) :review_note reason)))
        (when (= "approved" (:decision body))
          (q :lifecycle/set-status! {:project_id id :status "closing"})
          (q :reopen/reset-archive! {:project_id id :reopened_version (inc (:version project))}))
        (q :reopen/latest {:project_id id})))))
