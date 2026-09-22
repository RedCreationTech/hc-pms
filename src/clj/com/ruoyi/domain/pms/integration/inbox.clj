(ns com.ruoyi.domain.pms.integration.inbox
  "认证收件箱去重和单调版本的来源事实投影,不冒充外部业务已审批."
  (:require [com.ruoyi.domain.pms.integration.codec :as codec]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn- input!
  "校验来源事件,独立业务键和正整数来源版本."
  [actor id body]
  (r/object! body [:version :source :event_id :entity_type :external_key :source_revision :data])
  (when-not (codec/entities (:entity_type body)) (r/fail! 400 "不支持的来源事实类型"))
  (merge {:message_id (k/id) :project_id id :received_by (:user_id actor)
          :source (codec/system! (:source body)) :entity_type (:entity_type body)
          :event_id (r/text! (:event_id body) "来源事件编号" 100 true)
          :external_key (r/text! (:external_key body) "来源业务编号" 150 true)
          :source_revision (r/positive-id! (:source_revision body) "来源版本")}
         (codec/payload! (:data body))))

(defn- apply-fact!
  "仅更高来源版本更新投影,乱序历史保留收件记录且不倒退."
  [q params]
  (let [old (q :integration/fact params)
        newer? (or (nil? old) (> (:source_revision params) (:source_revision old)))
        same? (= (:source_revision params) (:source_revision old))]
    (when (and same? (not= (:payload_hash old) (:payload_hash params)))
      (r/fail! 409 "同一来源版本携带不同内容,须在源系统修正版本"))
    (let [record (assoc params :status (if newer? "applied" "ignored"))]
      (q :integration/insert-inbox! record)
      (when newer?
        (q (if old :integration/update-fact! :integration/insert-fact!)
           (assoc params :fact_id (or (:fact_id old) (k/id)))))
      (codec/dto record))))

(defn receive!
  "按来源事件幂等收件,重复ID改变业务对象或内容一律拒绝."
  [svc actor id body]
  (let [params (input! actor id body)]
    (k/mutate! svc actor id "pms:integration:receive" body "integration.received"
      (fn [q _]
        (if-let [old (q :integration/inbox-key params)]
          (do
            (when-not (= (select-keys old [:entity_type :external_key :source_revision :payload_hash])
                         (select-keys params [:entity_type :external_key :source_revision :payload_hash]))
              (r/fail! 409 "来源事件编号已用于不同内容"))
            (codec/dto old))
          (apply-fact! q params))))))
