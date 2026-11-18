(ns com.ruoyi.domain.pms.finance-forecast
  "H12/H13 预测完工成本 (EAC) 与完工尚需成本 (ETC) 只读派生:
   已批准预算 (BAC) 减已核销实际 (AC) 减已承诺未发生 (承诺成本) 得剩余预测,
   三者在同一口径下可比才输出金额, 否则如实标注不可比而不伪造预测.
   不写入任何费用版本, 不与费用池分摊重复计费, 不构成门控."
  (:require
    [com.ruoyi.domain.pms.finance-money :as money]))


(defn- same-currency?
  [a b]
  (and a b (= a b)))


(defn forecast
  "按基线口径派生 EAC/ETC.

   BAC  最新已批准预算总金额 (baseline 口径, 缺省 budget)
   AC   最新已批准实际成本总金额
   committed 已提交或已批准且尚未转实付的承诺本位金额

   ETC = max(BAC - AC - committed, 0), EAC = AC + committed + ETC.
   预算总额缺失或不为正时不可算, 金额键为 nil 且 comparable=false;
   已批准实际与预算币种不一致时同样不可算, 不做隐式换汇.
   币种不一致只在 currency_mismatch 标注真实币种, 供界面提示需人工对账."
  [q project-id baseline]
  (let [budget (q :finance/latest-approved-total {:project_id project-id :kind baseline})
        actual (q :finance/latest-approved-total {:project_id project-id :kind "actual"})
        committed (long (or (:committed_minor (q :finance/commitment-consumed {:project_id project-id})) 0))
        bac (when-let [t (:total_minor budget)] (long t))
        ac (when-let [t (:total_minor actual)] (long t))
        budget-currency (:currency budget)
        actual-currency (:currency actual)
        ;; 预算缺失时不算"币种不一致": 那是不可比而非错配, 否则界面会误报 ( / CNY).
        mismatch? (and bac actual (not (same-currency? budget-currency actual-currency)))
        comparable? (and bac (pos? bac) (not mismatch?))
        etc (when comparable? (max 0 (- bac (or ac 0) committed)))
        eac (when comparable? (+ (or ac 0) committed etc))
        used (when comparable? (- bac etc))]
    {:baseline baseline
     :available (boolean comparable?)
     :comparable (boolean comparable?)
     :currency budget-currency
     :currency_mismatch (boolean mismatch?)
     :actual_currency actual-currency
     :bac_minor bac
     :ac_minor ac
     :committed_minor committed
     :etc_minor etc
     :eac_minor eac
     :used_minor used
     :used_pct (when (and comparable? (pos? bac))
                 (long (Math/round (double (/ (* 100 used) bac)))))
     :remaining_pct (when (and comparable? (pos? bac))
                      (long (Math/round (double (/ (* 100 (- bac used)) bac)))))
     :over_budget (boolean (and comparable? (neg? (- bac (or ac 0) committed))))
     :eac (when comparable? (money/money eac))
     :etc (when comparable? (money/money etc))
     :bac (when bac (money/money bac))
     :ac (when ac (money/money ac))}))
