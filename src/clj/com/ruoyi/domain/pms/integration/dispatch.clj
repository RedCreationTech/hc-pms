(ns com.ruoyi.domain.pms.integration.dispatch
  "有持久尝试和回执的消息发送,失败退避及人工死信恢复."
  (:require [com.ruoyi.domain.pms.integration.codec :as codec]
            [com.ruoyi.domain.pms.integration.outbox :as outbox]
            [com.ruoyi.domain.pms.integration.transport :as transport]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn- row!
  "读取项目内发件,不允许跨项目操作."
  [q id message-id]
  (or (q :integration/message {:project_id id :message_id message-id}) (r/fail! 404 "外发消息不存在")))

(defn- claim!
  "先在事务内抢占唯一发送租约并登记尝试,网络操作在提交事务之后."
  [svc actor id message-id body]
  (k/mutate! svc actor id "pms:integration:deliver" body "integration.dispatch.started"
    (fn [q _]
      (let [row (row! q id message-id) now (System/currentTimeMillis) lease (k/id)]
        (outbox/finance-permission! actor (:topic row))
        (when-not (contains? #{"queued" "retry_wait"} (:status row)) (r/fail! 409 "消息不在可发送状态"))
        (when (> (or (:next_retry_at row) 0) now) (r/fail! 409 "尚未到达退避重试时间"))
        (r/changed! (q :integration/claim! (assoc row :lease_id lease :lease_started_at now)))
        (q :integration/attempt! {:attempt_id lease :project_id id :message_id message-id :attempt_no (inc (:attempts row))})
        (row! q id message-id)))))

(defn- result-fields
  "根据匹配回执和重试预算计算持久状态,HTTP成功不等于业务接收."
  [row result]
  (let [accepted? (:accepted? result)
        dead? (>= (:attempts row) (* 5 (inc (:retry_cycle row))))]
    (merge {:http_status nil :receipt_id nil :receipt_hash nil :error_code nil} result
           {:status (cond accepted? "delivered" dead? "dead_letter" :else "retry_wait")
            :next_retry_at (when-not (or accepted? dead?)
                             (+ (System/currentTimeMillis) (* 1000 (min 300 (long (Math/pow 2 (min 8 (:attempts row))))))))
            :attempt_id (:lease_id row)})))

(defn- finish-once!
  "即使项目在网络等待期间关闭,也要原子保留已发生交付的真实回执和审计."
  [svc actor row result]
  (k/transaction! svc
    (fn [q]
      (let [project (q :pms/project {:project_id (:project_id row)})
            fields (merge row (result-fields row result))]
        (r/changed! (q :pms/touch-project! project))
        (r/changed! (q :integration/finish! fields))
        (r/changed! (q :integration/receipt! fields))
        (k/event! q actor project "integration.dispatch.finished"
                  {:result (select-keys fields [:message_id :status :http_status :error_code])})
        {:result (codec/dto (row! q (:project_id row) (:message_id row)))
         :project_version (inc (:version project))}))))

(defn- finish!
  "仅重试回执事务的版本冲突,不重复执行网络发送."
  [svc actor row result]
  (loop [remaining 3]
    (let [attempt (try {:value (finish-once! svc actor row result)}
                       (catch clojure.lang.ExceptionInfo e {:error e}))]
      (if-let [error (:error attempt)]
        (if (and (> remaining 1) (= 409 (:status (ex-data error))))
          (recur (dec remaining)) (throw error))
        (:value attempt)))))

(defn deliver!
  "把队列消息交给配置的真实HTTP端点并验证回执,未配置时明确拒绝."
  [svc actor id message-id body]
  (r/object! body [:version])
  (let [row (k/read! svc actor id "pms:integration:deliver" (fn [q _] (row! q id message-id)))
        config (transport/connection! svc (:target row))
        claimed (:result (claim! svc actor id message-id body))
        result (transport/send! config claimed)]
    (finish! svc actor claimed result)))

(defn retry!
  "人工说明原因后重置一个重试周期,发送中租约超过30秒才允许恢复."
  [svc actor id message-id body]
  (r/object! body [:version :reason])
  (r/text! (:reason body) "重试原因" 1000 true)
  (k/mutate! svc actor id "pms:integration:deliver" body "integration.retry.requested"
    (fn [q _]
      (let [row (row! q id message-id)]
        (outbox/finance-permission! actor (:topic row))
        (when (and (= "sending" (:status row))
                   (< (System/currentTimeMillis) (+ 30000 (:lease_started_at row))))
          (r/fail! 409 "发送租约仍有效,请等待回执或租约到期"))
        (when-not (contains? #{"sending" "retry_wait" "dead_letter"} (:status row))
          (r/fail! 409 "仅失败,死信或过期发送可以重试"))
        (when (= "sending" (:status row)) (q :integration/abandon-attempt! {:project_id id :attempt_id (:lease_id row)}))
        (r/changed! (q :integration/retry! row))
        (codec/dto (row! q id message-id))))))
