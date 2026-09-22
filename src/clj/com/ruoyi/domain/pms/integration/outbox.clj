(ns com.ruoyi.domain.pms.integration.outbox
  "只从已批准计划或成本及真实文档生成不可变外发消息,客户端不能伪造正文."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.integration.codec :as codec]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn finance-permission!
  "财务正文外发与读取必须另有财务查询权限."
  [actor topic]
  (when (= "cost.approved" topic) (r/permit! actor "pms:finance:query")))

(defn- source!
  "按明确主题读取同项目的批准快照或实际文档版本."
  [q actor project topic source-id]
  (finance-permission! actor topic)
  (case topic
    "plan.published"
    (let [row (q :planning/baseline {:project_id (:project_id project) :baseline_id source-id})]
      (when-not (= "approved" (:status row)) (r/fail! 409 "只允许外发同项目已批准基线"))
      {:baseline_id source-id :snapshot_hash (:snapshot_hash row)
       :snapshot (json/parse-string (:snapshot_json row) true)})
    "cost.approved"
    (let [row (cost/version! q (:project_id project) source-id)]
      (when-not (= "approved" (:status row)) (r/fail! 409 "只允许外发已批准财务版本"))
      (cost/dto q row))
    "document.registered" (gov/evidence-version! q project source-id)
    (r/fail! 400 "外发主题必须为已批准计划,财务版本或真实文档")))

(defn enqueue!
  "以目标系统和业务幂等键建立持久发件箱,仅排队不暗中发送网络请求."
  [svc actor id body]
  (r/object! body [:version :target :topic :source_id :idempotency_key])
  (let [target (codec/system! (:target body))
        key (r/text! (:idempotency_key body) "幂等键" 100 true)]
    (k/mutate! svc actor id "pms:integration:edit" body "integration.queued"
      (fn [q project]
        (finance-permission! actor (:topic body))
        (if-let [old (q :integration/outbox-key {:project_id id :target target :idempotency_key key})]
          (do (when-not (= (select-keys old [:topic :source_id]) (select-keys body [:topic :source_id]))
                (r/fail! 409 "外发幂等键已用于不同对象"))
              (codec/dto old))
          (let [message-id (k/id) source (source! q actor project (:topic body) (:source_id body))
                payload (codec/encode {:schema_version 1 :message_id message-id :project_id id
                                       :topic (:topic body) :source_id (:source_id body) :data source})
                row {:message_id message-id :project_id id :target target :topic (:topic body)
                     :source_id (:source_id body) :idempotency_key key :created_by (:user_id actor)
                     :payload_json payload :payload_hash (money/digest payload)}]
            (q :integration/insert-outbox! row)
            (codec/dto (q :integration/message row))))))))

(defn detail
  "受项目和敏感财务权限控制地读取确定消息正文."
  [svc actor id message-id]
  (k/read! svc actor id "pms:integration:query"
    (fn [q _]
      (let [row (q :integration/message {:project_id id :message_id message-id})]
        (when-not row (r/fail! 404 "消息不存在"))
        (finance-permission! actor (:topic row))
        (assoc (codec/dto row) :payload (json/parse-string (:payload_json row) true))))))
