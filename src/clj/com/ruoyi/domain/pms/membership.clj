(ns com.ruoyi.domain.pms.membership
  "成员撤销前的跨业务责任交接检查,防止形成无人可处理的待办."
  (:require [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.rules :as r]))

(defn removal-ready!
  "校验未完成任务,待审工时成本和关闭审批均已交接."
  [q project uid]
  (let [params {:project_id (:project_id project)}
        tasks (q :planning/tasks params) costs (q :finance/versions params)
        times (q :finance/times params) approval (q :closure/approval params)
        items (q :closure/items params) reopen (q :reopen/latest params)
        blockers (concat (gov/member-removal-blockers q project uid)
                          (delivery/member-removal-blockers q project uid)
                          (when (some #(and (= uid (:owner_id %)) (not= "done" (:status %))) tasks)
                            ["该成员仍负责未完成计划任务,请先交接"])
                          (when (some #(and (= uid (:reviewer_id %)) (contains? #{"draft" "submitted"} (:status %))) costs)
                            ["该成员仍是未完成成本版本的审批人"])
                          (when (some #(and (= uid (:reviewer_id %)) (= "submitted" (:status %))) times)
                            ["该成员仍有待审核工时"])
                          (when (and (= uid (:reviewer_id approval)) (= "submitted" (:status approval)))
                            ["该成员仍是关闭审批人"])
                          (when (and (= uid (:reviewer_id reopen)) (= "submitted" (:status reopen)))
                            ["该成员仍是待审重开申请的指定审批人"])
                          (when (some #(and (= uid (:owner_id %)) (= "open" (:status %))) items)
                            ["该成员仍有未确认移交事项"]))]
    (when-let [message (first blockers)] (r/fail! 409 message))
    true))
