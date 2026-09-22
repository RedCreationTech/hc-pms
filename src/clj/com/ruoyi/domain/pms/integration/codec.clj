(ns com.ruoyi.domain.pms.integration.codec
  "企业接口消息的确定编码,来源白名单和安全读模型."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.rules :as r]))

(def systems "八类外部系统标识,ERP含SAP部署." #{"crm" "oa" "erp" "plm" "mes" "srm" "sales_material" "bi"})
(def entities "收件箱当前接收的外部事实类别,不自动覆盖PMS批准数据." #{"order" "material" "field_progress" "cost"})

(defn system! "校验约定连接器名称." [value]
  (when-not (systems value) (r/fail! 400 "不支持的外部系统")) value)

(defn encode "稳定序列化消息,相同内容不受JSON键顺序影响." [value]
  (json/generate-string (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %) value)))

(defn payload!
  "校验有限大小的结构化外部事实,存储时保留原始业务字段."
  [value]
  (when-not (and (map? value) (seq value)) (r/fail! 400 "事实内容必须是非空JSON对象"))
  (let [encoded (encode value)]
    (when (> (alength (.getBytes encoded "UTF-8")) 65536) (r/fail! 400 "事实内容不能超过64KiB"))
    {:payload_json encoded :payload_hash (money/digest encoded)}))

(defn dto
  "运维列表仅提供标识和摘要,不泄露潜在财务或文档正文."
  [row]
  (when row (-> row (dissoc :payload_json :lease_id) (assoc :id (or (:message_id row) (:fact_id row))))))
