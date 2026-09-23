(ns com.ruoyi.domain.pms.governance.reviews
  "问题受控重开及风险周期复审,所有决定由指定独立人员确认."
  (:require [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.time LocalDate]))



(defn- history
  "追加受控状态转移的审阅痕迹,保留原有关闭结果."
  [record actor action fields]
  (conj (vec (:workflow_history record))
        (merge {:action action :actor_id (:user_id actor) :on (str (LocalDate/now))
                :prior_status (:status record)} fields)))



(defn reopen-issue!
  "关闭问题仅能通过有理由,证据和独立指定人的重开申请重新处理."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.reopen-submitted"
    (fn [q project]
      (s/input! body [:reason :reviewer_id :evidence_ids])
      (let [issue (s/record! q project "issue" rid)
            reason (s/text! body :reason)
            evidence (s/evidence! q project (:evidence_ids body) true)]
        (s/status! issue #{"closed"})
        (s/change! q project issue "in_review"
                   {:review_action "reopen" :reopen_reason reason :submitted_by (:user_id actor)
                    :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                    :reopen_evidence_ids evidence
                    :workflow_history (history issue actor "reopen_requested"
                                               {:reason reason :evidence_ids evidence
                                                :prior_resolution (:resolution issue)
                                                :prior_verification_reason (:verification_reason issue)})})))))



(defn decide-reopening!
  "在调用方事务中独立批准重开或保持原来的已关闭状态."
  [q project actor issue body]
  (s/status! issue #{"in_review"})
  (s/decision-actor! actor issue)
  (s/evidence! q project (:reopen_evidence_ids issue) true)
  (let [decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
        reason (s/text! body :reason)]
    (s/change! q project issue (if (= "approved" decision) "open" "closed")
               {:reopen_decision decision :reopen_decision_reason reason :reopen_decided_by (:user_id actor)
                :review_action nil
                :workflow_history (history issue actor "reopen_decided" {:decision decision :reason reason})})))



(defn- next-review!
  "仍需跟踪的风险必须设置下一次未来复审日期."
  [body outcome]
  (when-not (= "closed" outcome)
    (let [date (s/date! body :next_review_date)]
      (when-not (.isAfter (LocalDate/parse date) (LocalDate/now))
        (r/fail! 400 "下一次风险复审日期必须晚于今天"))
      date)))



(defn- linked-issues-closed!
  "已发生风险的历史问题在关闭或恢复监控前必须全部验证关闭."
  [q project risk]
  (doseq [rid (distinct (remove nil? (conj (vec (:issue_ids risk)) (:issue_id risk))))]
    (s/status! (s/record! q project "issue" rid) #{"closed"})))



(defn submit-risk-review!
  "提交带真实证据的风险周期复审或关闭申请,待独立审批."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.review-submitted"
    (fn [q project]
      (s/input! body [:outcome :review_note :evidence_ids :reviewer_id :next_review_date])
      (let [risk (s/record! q project "risk" rid)
            outcome (s/enum! (:outcome body) #{"active" "mitigated" "closed"} "outcome")
            note (s/text! body :review_note)]
        (s/status! risk #{"open" "mitigated" "materialized" "closed"})
        (linked-issues-closed! q project risk)
        (s/change! q project risk "in_review"
                   {:review_action "risk_review" :review_previous_status (:status risk)
                    :requested_outcome outcome :review_note note
                    :next_review_date (next-review! body outcome)
                    :review_evidence_ids (s/evidence! q project (:evidence_ids body) true)
                    :reviewer_id (s/reviewer! q project actor (:reviewer_id body)) :submitted_by (:user_id actor)
                    :workflow_history (history risk actor "review_requested" {:outcome outcome :note note})})))))



(defn- approved-risk-patch
  "批准后保存新复审日期和历史问题来源,当前发生链可重新建立."
  [risk]
  {:review_due_date (:next_review_date risk) :last_reviewed_on (str (LocalDate/now))
   :issue_ids (vec (distinct (remove nil? (conj (vec (:issue_ids risk)) (:issue_id risk)))))
   :issue_id nil})


(defn decide-risk-review!
  "指定独立审核人批准复审结果,或恢复提交之前的风险状态."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "risk.review-decided" {:write? false}
    (fn [q project]
      (s/input! body [:decision :reason])
      (let [risk (s/record! q project "risk" rid)
            decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
            reason (s/text! body :reason) approved? (= "approved" decision)
            state (if approved? (case (:requested_outcome risk) "active" "open" "mitigated" "mitigated" "closed")
                      (:review_previous_status risk))]
        (s/status! risk #{"in_review"})
        (s/decision-actor! actor risk)
        (s/evidence! q project (:review_evidence_ids risk) true)
        (when approved?
          (linked-issues-closed! q project risk)
          (next-review! risk (:requested_outcome risk)))
        (s/change! q project risk state
                   (cond-> {:review_decided_by (:user_id actor) :review_decision_reason reason
                            :workflow_history (history risk actor "review_decided" {:decision decision :reason reason})}
                     approved? (merge (approved-risk-patch risk))))))))


(def review-due-soon-days
  "未关闭风险复审到期日距服务器当天不超过该天数(不含当天)即视为临期, 供复审到期倒计时提前关注."
  3)


(defn- days-until
  "到期日相对服务器当天的剩余天数; 负值表示已逾期天数, 空日期返回 nil. collaboration.clj 依赖本命名空间, 故此处独立定义私有副本以避免循环依赖, 只读派生不落库."
  [due]
  (when (some? due) (- (.toEpochDay (LocalDate/parse due)) (.toEpochDay (LocalDate/now)))))


(defn risk-read-model
  "以服务器日期展示风险复审是否已到期及剩余复审天数与临期提示, 已关闭风险无逾期与倒计时标记."
  [risk]
  (let [closed? (= "closed" (:status risk))
        due (if (contains? risk :review_due_date) (:review_due_date risk) (:due_date risk))
        days (when-not closed? (days-until due))]
    (assoc risk :review_due_date due
                :review_overdue (boolean (and (not closed?) due
                                              (not (.isAfter (LocalDate/parse due) (LocalDate/now)))))
                :review_due_in_days days
                :review_due_soon (boolean (and (some? days) (<= 1 days review-due-soon-days))))))
