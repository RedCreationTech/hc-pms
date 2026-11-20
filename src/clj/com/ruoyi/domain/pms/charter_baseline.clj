(ns com.ruoyi.domain.pms.charter-baseline
  "H01 章程初始预算与批准后财务基线的只读对账.
   章程批准时冻结的 initial_budget/budget_currency 是业务承诺口径, 财务侧 budget 版本是执行口径;
   两者本是两条独立路径, 谁也没核过彼此的差异. 本命名空间只读取已批准章程与财务 budget 版本做差额派生,
   不写存储, 不构成门控 (差异存在不阻止任何财务登记或章程修订), 也不做汇率换算:
   章程币种与财务版本币种不一致时只报 currency_mismatch, 不给金额差额, 避免跨币种伪比较."
  (:require
    [com.ruoyi.domain.pms.finance-money :as money]))


(defn- approved-charter
  "取已批准的最新一版章程 (按 revision 降序)."
  [charters]
  (->> charters
       (filter #(= "approved" (:status %)))
       (sort-by :revision)
       last))


(defn- latest-budget-version
  "财务 budget 版本的执行口径: 取最新已批准版本, 无已批准则取最新版本并标注未批准."
  [versions]
  (let [budgets (filter #(= "budget" (:kind %)) versions)]
    (or (->> budgets (filter #(= "approved" (:status %))) (sort-by :version_no) last)
        (->> budgets (sort-by :version_no) last))))


(defn- budget-total-minor
  "版本总额 = 明细金额之和 (与 finance/cost-view 同一口径, 不使用数据库浮点 SUM)."
  [version]
  (reduce + 0 (map :amount_minor (:entries version))))


(defn reconciliation
  "章程初始预算与财务 budget 基线的只读对账.
   charters 为已按 revision 排序的章程版本 (需 :status/:revision/:initial_budget/:budget_currency),
   budget-version 为 finance/cost-view dto (含 :entries 与 :currency), 无 budget 版本时为 nil."
  [charters budget-version]
  (let [charter (approved-charter charters)
        ;; 章程 initial_budget 以 money 字符串冻结 (如 "88.90"), 财务版本以整数最小单位存储, 先归一到同一口径.
        charter-minor (when-some [b (:initial_budget charter)] (money/amount! b "章程初始预算"))
        charter-currency (:budget_currency charter)
        baseline-minor (when budget-version (budget-total-minor budget-version))
        baseline-currency (:currency budget-version)
        declared? (some? charter-minor)
        baseline? (some? budget-version)
        same-currency? (and declared? baseline? (= charter-currency baseline-currency))
        variance (when (and same-currency? (some? baseline-minor)) (- baseline-minor charter-minor))
        total (when same-currency? charter-minor)]
    {:available (boolean (and declared? baseline?))
     :charter {:approved (some? charter) :revision (:revision charter) :code (:code charter)
               :declared declared? :amount (when declared? (money/money charter-minor))
               :amount_minor charter-minor :currency charter-currency}
     :baseline {:present baseline? :version_id (:id budget-version)
                :version_no (:version_no budget-version) :status (:status budget-version)
                :approved (= "approved" (:status budget-version))
                :amount (when baseline? (money/money baseline-minor))
                :amount_minor baseline-minor :currency baseline-currency}
     :currency_match (boolean (and declared? baseline? (= charter-currency baseline-currency)))
     :variance (when variance (money/money variance))
     :variance_minor variance
     :variance_pct (when (and variance (pos? charter-minor))
                     (long (Math/round (* 100.0 (double (/ variance (double charter-minor)))))))
     :over_budget (boolean (and variance (pos? variance)))
     :within_budget (boolean (and variance (<= variance 0)))
     :state (cond
              (not declared?) "not_declared"
              (not baseline?) "no_baseline"
              (not same-currency?) "currency_mismatch"
              (zero? variance) "aligned"
              (pos? variance) "over"
              :else "under")}))
