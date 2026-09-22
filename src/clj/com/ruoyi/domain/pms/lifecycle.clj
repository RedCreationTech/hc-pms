(ns com.ruoyi.domain.pms.lifecycle
  "由计划, 质量, 财务与收尾实际证据共同约束的项目生命周期."
  (:require [com.ruoyi.domain.pms.closure :as closure]
            [com.ruoyi.domain.pms.governance :as governance]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.membership :as membership]
            [com.ruoyi.domain.pms.planning :as planning]
            [com.ruoyi.domain.pms.rules :as rules]))

(defn- save-state!
  "建立或更新暂停恢复状态, 不覆盖审计历史."
  [q project state reason]
  (let [params {:project_id (:project_id project) :resume_status state :paused_reason reason}]
    (q (if (q :lifecycle/state params) :lifecycle/update-state! :lifecycle/insert-state!) params)))

(defn- edge!
  "校验状态边及暂停恢复目标, 原因必须由操作者填写."
  [q project body]
  (rules/object! body [:status :version :reason])
  (let [from (:status project) to (:status body)
        reason (rules/text! (:reason body) "状态变更原因" 1000 false)
        resume (:resume_status (q :lifecycle/state {:project_id (:project_id project)}))]
    (when-not (or (contains? #{["draft" "initiated"] ["initiated" "planning"]
                              ["planning" "execution"] ["execution" "closing"] ["closing" "closed"]} [from to])
                  (and (= from "paused") (= to resume))
                  (and (contains? #{"execution" "closing"} from) (= to "paused"))
                  (and (contains? #{"draft" "initiated" "planning" "execution" "closing" "paused"} from)
                       (= to "cancelled")))
      (rules/fail! 409 "不允许执行此状态转换"))
    (when (and (contains? #{"paused" "cancelled"} to) (empty? reason))
      (rules/fail! 400 "暂停或取消必须填写原因"))
    {:status to :reason reason}))

(defn- gates!
  "对进入执行, 收尾和关闭逐次核查当前证据, 不依赖前端标记."
  [q actor project target]
  (case target
    "execution" (do (planning/execution-ready! q project)
                     (governance/execution-ready! q project))
    "closing" (when-let [message (first (closure/task-blockers q project))]
                (rules/fail! 409 message))
    "closed" (do (rules/permit! actor "pms:project:transition") (closure/ready! q project))
    nil))

(defn transition!
  "原子推进项目状态并保留暂停前状态和归档时间."
  [svc actor project-id body]
  (let [response
        (kernel/mutate! svc actor project-id "pms:project:transition" body "project.transitioned"
          {:allow-paused? true}
          (fn [q project]
            (let [{:keys [status reason]} (edge! q project body)]
              (when-not (= "paused" (:status project)) (gates! q actor project status))
              (cond
                (= status "paused") (save-state! q project (:status project) reason)
                (= (:status project) "paused") (save-state! q project nil reason)
                (= status "closed") (save-state! q project nil reason))
              (rules/changed! (q :lifecycle/set-status! {:project_id project-id :status status}))
              (when (= status "closed") (q :lifecycle/archive! {:project_id project-id}))
              (assoc (q :pms/project {:project_id project-id})
                     :resume_status (:resume_status (q :lifecycle/state {:project_id project-id}))))))]
    (:result response)))

(defn remove-member!
  "撤销项目成员范围, 当前经理必须先完成负责人的交接."
  [svc actor project-id user-id body]
  (rules/object! body [:version])
  (kernel/mutate! svc actor project-id "pms:member:edit" body "member.removed"
    (fn [q project]
      (let [uid (rules/positive-id! user-id "成员")
            member (q :pms/member {:project_id project-id :user_id uid})]
        (when (= uid (:manager_id project)) (rules/fail! 409 "请先变更项目经理,再撤销原经理成员关系"))
        (when-not member (rules/fail! 404 "项目成员不存在"))
        (membership/removal-ready! q project uid)
        (rules/changed! (q :lifecycle/delete-member! {:project_id project-id :user_id uid}))
        {:user_id uid :status "removed"}))))
