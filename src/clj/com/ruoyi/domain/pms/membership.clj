(ns com.ruoyi.domain.pms.membership
  "成员撤销前的跨业务责任交接检查,防止形成无人可处理的待办."
  (:require
    [com.ruoyi.domain.pms.approval-chain :as chain]
    [com.ruoyi.domain.pms.delivery :as delivery]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.store :as store]
    [com.ruoyi.domain.pms.rules :as r]))


(defn- execution-blockers
  "未完成计划任务, 未完成成本版本审批人, 待审工时, 尚未结束的审批流步骤与未读本地通知五类未交接责任."
  [q params uid]
  (concat
    (when (some #(and (= uid (:owner_id %)) (not= "done" (:status %))) (q :planning/tasks params))
      ["该成员仍负责未完成计划任务,请先交接"])
    (when (some #(and (= uid (:reviewer_id %)) (contains? #{"draft" "submitted"} (:status %))) (q :finance/versions params))
      ["该成员仍是未完成成本版本的审批人"])
    (when (some #(and (= uid (:reviewer_id %)) (= "submitted" (:status %))) (q :finance/times params))
      ["该成员仍有待审核工时"])
    (when (seq (chain/pending-approver-flows q params uid))
      ["该成员仍是未结束审批流的待定审批人,请先完成或改派"])
    (when (some #(and (= uid (:recipient_id %)) (= "unread" (:status %)))
                (map store/decode (q :pms/gov-in-projects {:project_ids [(:project_id params)] :kinds ["notification"]})))
      ["该成员仍有未读的项目通知,撤销后其将丢失投递目标"])))


(defn- closure-blockers
  "关闭审批人, 待审重开指定审批人与未确认移交事项三类未交接责任."
  [q params uid]
  (let [approval (q :closure/approval params) reopen (q :reopen/latest params) items (q :closure/items params)]
    (concat
      (when (and (= uid (:reviewer_id approval)) (= "submitted" (:status approval)))
        ["该成员仍是关闭审批人"])
      (when (and (= uid (:reviewer_id reopen)) (= "submitted" (:status reopen)))
        ["该成员仍是待审重开申请的指定审批人"])
      (when (some #(and (= uid (:owner_id %)) (= "open" (:status %))) items)
        ["该成员仍有未确认移交事项"]))))


(defn- removal-blockers
  "撤销成员前的全部跨业务责任阻塞消息: 治理/交付跨域在前, 计划财务与收尾域在后 (顺序即 409 取首条的优先级)."
  [q project uid]
  (let [params {:project_id (:project_id project)}]
    (concat (gov/member-removal-blockers q project uid)
            (delivery/member-removal-blockers q project uid)
            (execution-blockers q params uid)
            (closure-blockers q params uid))))


(defn removal-ready!
  "校验未完成任务,待审工时成本,未结束审批流,未读通知与关闭审批均已交接."
  [q project uid]
  (when-let [message (first (removal-blockers q project uid))] (r/fail! 409 message))
  true)
