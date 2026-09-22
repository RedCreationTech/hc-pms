(ns com.ruoyi.domain.pms.governance.collaboration
  "项目风险问题的验证关闭及会议行动转真实计划任务."
  (:require
    [com.ruoyi.domain.pms.governance.reviews :as reviews]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(defn- score!
  "校验风险概率或影响等级为1到5."
  [value]
  (when-not (and (integer? value) (<= 1 value 5)) (r/fail! 400 "概率与影响必须为1到5整数"))
  value)


(defn create-risk!
  "登记有明确责任人与预防措施的风险."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.created"
             (fn [q project]
               (s/input! body [:title :probability :impact :owner_id :mitigation :due_date])
               (let [probability (score! (:probability body)) impact (score! (:impact body))]
                 (s/insert! q project actor "risk"
                            {:title (s/text! body :title 200) :probability probability :impact impact
                             :score (* probability impact) :owner_id (k/user! q project (:owner_id body) "负责人")
                             :mitigation (s/text! body :mitigation) :due_date (s/date! body :due_date)}
                            {:status "open"})))))


(defn mitigate!
  "记录风险措施实际执行证据并保留其后转问题的能力."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.mitigated"
             (fn [q project]
               (s/input! body [:mitigation :evidence_ids])
               (let [risk (s/record! q project "risk" rid)]
                 (s/status! risk #{"open" "mitigated"})
                 (s/change! q project risk "mitigated"
                            {:mitigation (s/text! body :mitigation)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)})))))


(defn- issue-fields!
  "校验问题内容,严重度,责任人与解决期限."
  [q project body]
  (s/input! body [:title :severity :owner_id :due_date])
  {:title (s/text! body :title 200)
   :severity (s/enum! (:severity body) #{"blocker" "major" "minor"} "severity")
   :owner_id (k/user! q project (:owner_id body) "负责人") :due_date (s/date! body :due_date)})


(defn create-issue!
  "创建需经过独立验证才能关闭的问题."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.created"
             (fn [q project] (s/insert! q project actor "issue" (issue-fields! q project body) {:status "open"}))))


(defn materialize!
  "风险发生时幂等生成问题并保留双向来源关联."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.materialized"
             (fn [q project]
               (s/input! body [:title])
               (let [risk (s/record! q project "risk" rid)]
                 (if-let [target (:issue_id risk)]
                   (s/record! q project "issue" target)
                   (do
                     (s/status! risk #{"open" "mitigated"})
                     (let [issue (s/insert! q project actor "issue"
                                            {:title (or (not-empty (s/optional-text! body :title 200)) (:title risk))
                                             :owner_id (:owner_id risk) :due_date (:due_date risk)
                                             :severity (if (>= (:impact risk) 4) "blocker" "major")
                                             :source_risk_id rid} {:status "open"})]
                       (s/change! q project risk "materialized" {:issue_id (:id issue)})
                       issue)))))))


(defn resolve!
  "提交整改内容和确切证据版本,指定独立验证人."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.resolution-submitted"
             (fn [q project]
               (s/input! body [:resolution :evidence_ids :reviewer_id])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (s/change! q project issue "in_review"
                            {:review_action "closure" :resolution (s/text! body :resolution)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)
                             :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)})))))


(defn verify!
  "指定独立人员验证整改关闭,或批准有理由的受控重开."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "issue.verified" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [issue (s/record! q project "issue" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! issue #{"in_review"})
                 (s/decision-actor! actor issue)
                 (if (= "reopen" (:review_action issue))
                   (reviews/decide-reopening! q project actor issue body)
                   (do
                     (s/evidence! q project (:evidence_ids issue) true)
                     (s/change! q project issue (if (= decision "approved") "closed" "rejected")
                                {:verification_reason (s/text! body :reason) :verified_by (:user_id actor)})))))))


(defn reassign-issue!
  "转派问题责任人, 保留原责任人与转派原因供审计, 新责任人须为当前项目成员."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.reassigned"
             (fn [q project]
               (s/input! body [:owner_id :reason])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (s/change! q project issue (:status issue)
                            {:owner_id (k/user! q project (:owner_id body) "新责任人")
                             :reassigned_from (:owner_id issue)
                             :reassign_reason (s/text! body :reason 500)
                             :reassigned_by (:user_id actor)})))))


(defn create-meeting!
  "持久化项目会议纪要和有效参会人员."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "meeting.recorded"
             (fn [q project]
               (s/input! body [:title :held_on :minutes :attendee_ids])
               (when-not (and (vector? (:attendee_ids body)) (<= 1 (count (:attendee_ids body)) 100))
                 (r/fail! 400 "参会人员必须为1到100人的数组"))
               (s/insert! q project actor "meeting"
                          {:title (s/text! body :title 200) :held_on (s/date! body :held_on)
                           :minutes (s/text! body :minutes 20000)
                           :attendee_ids (vec (distinct (map #(s/user! q %) (:attendee_ids body))))}
                          {:status "recorded"}))))


(defn create-action!
  "从确定会议派生有责任人和期限的行动项."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.created"
             (fn [q project]
               (s/input! body [:title :owner_id :due_date])
               (s/record! q project "meeting" rid)
               (s/insert! q project actor "action"
                          {:title (s/text! body :title 200) :owner_id (k/user! q project (:owner_id body) "负责人")
                           :due_date (s/date! body :due_date) :meeting_id rid}
                          {:status "open"}))))


(defn materialize-action!
  "在同一事务中幂等创建真实WBS任务,保留来源及目标ID."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.task-created"
             (fn [q project]
               (s/input! body [:start_date :duration_days :wbs_code])
               (let [action (s/record! q project "action" rid)]
                 (if-let [target (:target_task_id action)]
                   (do
                     (when-not (q :planning/task {:project_id (:project_id project) :task_id target})
                       (r/fail! 409 "已关联任务不存在,需要修复任务引用"))
                     {:target_task_id target :action action})
                   (let [task (planning/create-task-record!
                                q project actor
                                (cond-> {:name (:title action) :owner_id (:owner_id action)
                                         :start_date (or (:start_date body) (:due_date action))
                                         :duration_days (or (:duration_days body) 1)
                                         :description (str "会议行动项 " rid)
                                         :source_type "meeting_action" :source_id rid}
                                  (:wbs_code body) (assoc :wbs_code (:wbs_code body))))
                         target (:task_id task)]
                     (when-not target (r/fail! 500 "任务服务未返回有效任务ID"))
                     {:target_task_id target
                      :action (s/change! q project action "converted" {:target_task_id target})}))))))


(defn complete-action!
  "会议行动完成须提交结果说明与真实证据并指定独立验证人, 保留未转任务行动的追踪闭环."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.completion-submitted"
             (fn [q project]
               (s/input! body [:result :evidence_ids :reviewer_id])
               (let [action (s/record! q project "action" rid)]
                 (s/status! action #{"open" "rejected"})
                 (s/change! q project action "in_review"
                            {:review_action "action_closure"
                             :result (s/text! body :result 2000)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)
                             :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)})))))


(defn verify-action!
  "由指定独立审核人核验会议行动完成, 批准关闭或驳回退回负责人."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "action.verified" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [action (s/record! q project "action" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! action #{"in_review"})
                 (s/decision-actor! actor action)
                 (s/evidence! q project (:evidence_ids action) true)
                 (s/change! q project action (if (= decision "approved") "closed" "rejected")
                            {:verification_reason (s/text! body :reason 2000)
                             :verified_by (:user_id actor)})))))


(defn action-read-model
  "以服务器日期展示会议行动是否逾期未完成, 已关闭或已转真实任务的行动不再计逾期."
  [action]
  (assoc action :action_overdue
         (boolean (and (:due_date action)
                       (not (contains? #{"closed" "converted"} (:status action)))
                       (not (.isAfter (LocalDate/parse (:due_date action)) (LocalDate/now)))))))
