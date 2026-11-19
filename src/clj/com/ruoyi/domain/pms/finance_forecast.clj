(ns com.ruoyi.domain.pms.finance-forecast
  "H12/H13 预测完工成本 (EAC) 与完工尚需成本 (ETC) 只读派生:
   已批准预算 (BAC) 减已核销实际 (AC) 减已承诺未发生 (承诺成本) 得剩余预测,
   三者在同一口径下可比才输出金额, 否则如实标注不可比而不伪造预测.
   不写入任何费用版本, 不与费用池分摊重复计费, 不构成门控.

   H13 汇率折算: 仅当预算与实际币种不同, 且存在已发布的汇率表在折算日期命中的直接或
   经本位币的中转 (A->CNY->B) 汇率时才折算; 折算结果显式标注所用汇率与折算日期,
   缺少汇率时如实不可算而不是猜一个数. 平台不做隐式换汇: 折算只在看板口径, 不写回账项."
  (:require
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance-money :as money])
  (:import
    (java.math
      BigDecimal
      RoundingMode)
    (java.time
      LocalDate)))


(defn- same-currency?
  [a b]
  (and a b (= a b)))


(def rate-scales
  "汇率小数位上限, 与 config/exchange-rate-entry! 的校验口径一致."
  8)


(defn- windowed?
  "汇率有效期含端点; 无止日表示持续有效."
  [row date]
  (and (string? (:effective_from row))
       (<= (compare (:effective_from row) date) 0)
       (or (nil? (:effective_to row)) (<= (compare date (:effective_to row)) 0))))


(defn direct-rate
  "取币种对在指定日期生效的汇率行; 无则 nil."
  [rates from to date]
  (some #(when (and (= from (:from_currency %)) (= to (:to_currency %)) (windowed? % date)) %) rates))


(defn resolve-rate
  "解析 from -> to 的汇率: 直接命中优先, 其次经本位币中转 (先 from->base 再 base->to).
   返回 :rate 汇率字符串, :path 币种链与 :via-base 是否中转; 缺汇率返回 nil (不做隐式换汇)."
  [rates from to date base]
  (if (= from to)
    {:rate "1" :path [from to] :via-base false}
    (if-let [direct (direct-rate rates from to date)]
      {:rate (:rate direct) :path [from to] :via-base false}
      (when-let [leg1 (direct-rate rates from base date)]
        (when-let [leg2 (direct-rate rates base to date)]
          (let [product (.multiply (BigDecimal. (:rate leg1)) (BigDecimal. (:rate leg2)))]
            {:rate (.toPlainString (.setScale product rate-scales RoundingMode/HALF_UP))
             :path [from base to] :via-base true}))))))


(defn convert-minor
  "把金额最小单位按汇率折算到目标币种, 四舍五入到分. 不经浮点."
  [minor rate]
  (.longValueExact (.setScale (.multiply (BigDecimal/valueOf (long minor)) (BigDecimal. rate)) 0 RoundingMode/HALF_UP)))


(defn forecast
  "按基线口径派生 EAC/ETC.

   BAC  最新已批准预算总金额 (baseline 口径, 缺省 budget)
   AC   最新已批准实际成本总金额
   committed 已提交或已批准且尚未转实付的承诺本位金额

   ETC = max(BAC - AC - committed, 0), EAC = AC + committed + ETC.
   预算总额缺失或不为正时不可算, 金额键为 nil 且 comparable=false;
   已批准实际与预算币种不一致时, 若有已发布汇率表且命中汇率则折算到预算币种并在
   :fx 中标注所用汇率与折算日期; 无可用汇率时不可算 (currency_mismatch=true),
   不做隐式换汇或估算汇率. 预算缺失时不算错配, 只标 available=false."
  ([q project-id baseline] (forecast q project-id baseline nil))
  ([q project-id baseline today]
   (let [budget (q :finance/latest-approved-total {:project_id project-id :kind baseline})
         actual (q :finance/latest-approved-total {:project_id project-id :kind "actual"})
         committed (long (or (:committed_minor (q :finance/commitment-consumed {:project_id project-id})) 0))
         bac (when-let [t (:total_minor budget)] (long t))
         actual-minor (when-let [t (:total_minor actual)] (long t))
         budget-currency (:currency budget)
         actual-currency (:currency actual)
         cross? (boolean (and actual-minor budget-currency actual-currency
                              (not (same-currency? budget-currency actual-currency))))
         date (str (or today (LocalDate/now)))
         fx (when cross?
              (when-let [table (first (config/published q "exchange-rate"))]
                (when-let [resolved (resolve-rate (:rates table) actual-currency budget-currency
                                                  date (:base_currency table))]
                  (assoc resolved :date date))))
         ac (when actual-minor
              (if (and fx cross?) (convert-minor actual-minor (:rate fx)) actual-minor))
         converted? (boolean (and fx cross?))
         ;; 预算缺失时不算"币种不一致": 那是不可比而非错配, 否则界面会误报 ( / CNY).
         mismatch? (boolean (and bac actual (not converted?) cross?))
         comparable? (boolean (and bac (pos? bac) (or (not mismatch?) converted?)))
         etc (when comparable? (max 0 (- bac (or ac 0) committed)))
         eac (when comparable? (+ (or ac 0) committed etc))
         used (when comparable? (- bac etc))]
     {:baseline baseline
      :available (boolean comparable?)
      :comparable (boolean comparable?)
      :currency budget-currency
      :currency_mismatch mismatch?
      :actual_currency actual-currency
      :bac_minor bac
      :ac_minor ac
      :ac_source_minor actual-minor
      :committed_minor committed
      :etc_minor etc
      :eac_minor eac
      :used_minor used
      :used_pct (when (and comparable? (pos? bac))
                  (long (Math/round (double (/ (* 100 used) bac)))))
      :remaining_pct (when (and comparable? (pos? bac))
                       (long (Math/round (double (/ (* 100 (- bac used)) bac)))))
      :over_budget (boolean (and comparable? (neg? (- bac (or ac 0) committed))))
      :fx (when fx
            {:from actual-currency :to budget-currency :rate (:rate fx) :path (:path fx)
             :via_base (boolean (:via-base fx)) :date (:date fx) :converted converted?})
      :eac (when comparable? (money/money eac))
      :etc (when comparable? (money/money etc))
      :bac (when bac (money/money bac))
      :ac (when ac (money/money ac))})))
