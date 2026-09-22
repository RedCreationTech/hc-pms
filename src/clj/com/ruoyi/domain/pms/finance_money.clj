(ns com.ruoyi.domain.pms.finance-money
  "金额最小单位和工时整数分钟的精确换算及守恒分摊."
  (:require [com.ruoyi.domain.pms.rules :as rules])
  (:import [java.math BigDecimal RoundingMode]
           [java.security MessageDigest]))

(def currencies
  "当前支持两位小数最小单位的币种, 不隐式推断汇率."
  #{"CNY" "USD" "EUR" "GBP" "HKD"})

(defn amount!
  "将最多两位小数金额转换为精确整数最小单位."
  [value label]
  (let [s (if (or (number? value) (string? value)) (str value) "")]
    (when-not (re-matches #"-?[0-9]{1,12}(\.[0-9]{1,2})?" s)
      (rules/fail! 400 (str label "必须为最多两位小数的有效金额")))
    (.longValueExact (.movePointRight (BigDecimal. s) 2))))

(defn money
  "把整数最小单位转换为不经过浮点数的十进制字符串."
  [minor]
  (.toPlainString (.setScale (.movePointLeft (BigDecimal/valueOf (long (or minor 0))) 2) 2)))

(defn minutes!
  "把有效工时转换为完整分钟, 单条不超过24小时."
  [hours]
  (let [s (if (or (number? hours) (string? hours)) (str hours) "")]
    (when-not (re-matches #"[0-9]{1,2}(\.[0-9]{1,2})?" s)
      (rules/fail! 400 "工时必须是最多两位小数的正数"))
    (let [minutes (try (.intValueExact (.multiply (BigDecimal. s) (BigDecimal. "60")))
                       (catch ArithmeticException _ (rules/fail! 400 "工时必须能够换算为完整分钟")))]
      (when-not (<= 1 minutes 1440) (rules/fail! 400 "工时必须在1分钟至24小时之间"))
      minutes)))

(defn hours
  "将整数分钟格式化为两位小数小时用于显示."
  [minutes]
  (.toPlainString (.divide (BigDecimal/valueOf (long minutes)) (BigDecimal. "60") 2 RoundingMode/HALF_UP)))

(defn digest
  "计算确定字符串的SHA256摘要, 用于输入快照和幂等校验."
  [text]
  (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                                   (.getBytes (str text) "UTF-8")))))

(defn distribute
  "用最大余数法按整数权重分摊金额, 保证余差分配确定且总额守恒."
  [amount weights]
  (when-not (pos? amount) (rules/fail! 400 "分摊金额必须大于零"))
  (let [weights (filterv #(pos? (:minutes %)) weights)
        total (reduce + 0 (map :minutes weights))]
    (when (zero? total) (rules/fail! 409 "期间内没有已批准工时,不能分摊费用"))
    (let [parts (mapv (fn [row]
                        (let [product (*' amount (:minutes row))]
                          (assoc row :amount_minor (long (quot product total))
                                 :remainder (mod product total)))) weights)
          residual (- amount (reduce + 0 (map :amount_minor parts)))
          winners (set (map :task_id (take residual
                                         (sort-by (juxt (comp - :remainder) :task_id) parts))))]
      (mapv #(-> % (update :amount_minor + (if (winners (:task_id %)) 1 0))
                    (dissoc :remainder)) parts))))
