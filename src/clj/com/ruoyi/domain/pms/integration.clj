(ns com.ruoyi.domain.pms.integration
  "接口运维工作台,回执对账及八类系统连接器配置状态."
  (:require [com.ruoyi.domain.pms.integration.codec :as codec]
            [com.ruoyi.domain.pms.integration.transport :as transport]
            [com.ruoyi.domain.pms.kernel :as k]))

(defn workspace
  "提供队列,来源版本和尝试摘要,仅显示连接器配置状态而不泄露凭据或地址."
  [svc actor id]
  (k/read! svc actor id "pms:integration:query"
    (fn [q project]
      (let [params {:project_id id}
            inbox (mapv codec/dto (q :integration/inbox params))
            outbox (mapv codec/dto (q :integration/outbox params))
            config (transport/connectors svc)]
        {:project_version (:version project) :inbox inbox :outbox outbox
         :facts (mapv codec/dto (q :integration/facts params))
         :attempts (vec (q :integration/attempts params))
         :connectors (mapv (fn [s] {:system s :configured (boolean (get config (keyword s)))}) (sort codec/systems))
         :reconciliation {:received (count inbox) :applied (count (filter #(= "applied" (:status %)) inbox))
                          :ignored (count (filter #(= "ignored" (:status %)) inbox))
                          :queued (count outbox) :delivered (count (filter #(= "delivered" (:status %)) outbox))
                          :unconfirmed (count (remove #(= "delivered" (:status %)) outbox))}}))))
