(ns com.ruoyi.domain.pms.finance
  "四算, 工时和费用分摊读取模型, 财务金额查询拥有独立功能权限."
  (:require
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance-allocation :as allocation]
    [com.ruoyi.domain.pms.finance-budget :as budget]
    [com.ruoyi.domain.pms.finance-commitment :as commitment]
    [com.ruoyi.domain.pms.finance-cost :as cost]
    [com.ruoyi.domain.pms.finance-forecast :as forecast]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.finance-rate :as rate]
    [com.ruoyi.domain.pms.finance-time :as time]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.read-model :as rm]
    [com.ruoyi.domain.pms.rules :as rules]))


(defn- summary
  "仅比较同一期间和币种的最新已批准四算版本, 缺失口径保持缺失."
  [versions]
  (let [approved (filter #(= "approved" (:status %)) versions)
        context (select-keys (first approved) [:period :currency])
        comparable (filter #(= context (select-keys % [:period :currency])) approved)]
    {:summary_context context
     :summary (into {} (for [[kind rows] (group-by :kind comparable)]
                         [kind (let [v (first (sort-by :version_no > rows))]
                                 (assoc (select-keys v [:version_id :period :currency :revenue :margin])
                                        :amount (:total v)))]))}))


(defn four-count-comparison
  "F06 四算拉通: 各口径取最新已批准版本, 按费用分类对比金额, 并给出概算->预算->核算->决算的逐级差异; 只读派生, 币种不一致时标注不可比."
  [versions]
  (let [approved (filter #(= "approved" (:status %)) versions)
        latest (into {} (for [kind ["estimate" "budget" "actual" "settlement"]]
                          [kind (->> approved (filter #(= kind (:kind %))) (sort-by :version_no >) first)]))
        currencies (distinct (map :currency (remove nil? (vals latest))))
        categories (distinct (mapcat #(map :category (:entries %)) (remove nil? (vals latest))))
        sum (fn [version category] (reduce + 0 (map :amount_minor (filter #(= category (:category %)) (:entries version)))))
        rows (mapv (fn [category]
                     (into {:category category}
                           (for [[kind version] latest] [(keyword kind) (when version (money/money (sum version category)))])))
                   categories)
        totals (into {} (for [[kind version] latest] [(keyword kind) (when version (:total version))]))
        diff (fn [a b] (when (and (get latest a) (get latest b)) (money/money (- (:total_minor (get latest b)) (:total_minor (get latest a))))))]
    {:comparable (<= (count currencies) 1) :currency (first currencies)
     :versions (into {} (for [[kind version] latest] [(keyword kind) (when version (select-keys version [:id :version_no :period :name :revenue :total :margin]))]))
     :rows rows :totals totals
     :variances {:budget_vs_estimate (diff "estimate" "budget") :actual_vs_budget (diff "budget" "actual")
                 :settlement_vs_actual (diff "actual" "settlement") :settlement_vs_budget (diff "budget" "settlement")}}))


(defn cost-review-summary
  "按项目全部四算版本只读聚合审批闭环健康度: 总数/各状态计数(草稿 draft, 审批中 submitted, 已批准 approved, 已驳回 rejected, 已取消 cancelled)/已作决定数/待处理数/审核完成率, 并按概算-预算-核算-决算四口径给出总数·已批准·待处理分布; 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [versions]
  (let [total (count versions)
        status-count (fn [x] (count (filterv #(= x (:status %)) versions)))
        draft (status-count "draft")
        submitted (status-count "submitted")
        approved (status-count "approved")
        rejected (status-count "rejected")
        cancelled (status-count "cancelled")
        processed (+ approved rejected)
        pending (+ draft submitted)
        by-kind (mapv (fn [k]
                        (let [rs (filterv #(= k (:kind %)) versions)]
                          {:kind k
                           :total (count rs)
                           :approved (count (filterv #(= "approved" (:status %)) rs))
                           :pending (count (filterv #(contains? #{"draft" "submitted"} (:status %)) rs))}))
                      ["estimate" "budget" "actual" "settlement"])]
    {:available (pos? total)
     :total total
     :draft draft
     :submitted submitted
     :approved approved
     :rejected rejected
     :cancelled cancelled
     :processed processed
     :pending pending
     :by-kind by-kind
     :review-pct (rm/pct total processed)}))


(defn attach-cost-review-summary
  "把 cost-review-summary 挂到财务概览读模型顶层 :cost_review; 读取时派生, 不改变任何逐条费用版本."
  [data]
  (assoc data :cost_review (cost-review-summary (:cost_versions data))))


(defn allocation-review-summary
  "按项目全部费用分摊批次只读聚合研发费用分摊闭环健康度: 批次数/冻结费用池金额合计/实际摊出金额合计/生成成本条目数/覆盖任务数/因舍入未摊到金额的任务数/总额是否守恒; 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [allocations]
  (let [total (count allocations)
        rows (mapcat :rows allocations)
        pool-minor (reduce + 0 (map :amount_minor allocations))
        allocated-minor (reduce + 0 (map :amount_minor rows))
        entry-count (count (filterv #(pos? (:amount_minor %)) rows))
        zero-task-count (count (filterv #(zero? (:amount_minor %)) rows))
        task-count (count (distinct (map :task_id rows)))]
    {:available (pos? total)
     :total total
     :pool-amount (money/money pool-minor)
     :allocated-amount (money/money allocated-minor)
     :entry-count entry-count
     :zero-task-count zero-task-count
     :task-count task-count
     :conserved (= pool-minor allocated-minor)}))


(defn attach-allocation-review-summary
  "把 allocation-review-summary 挂到财务概览读模型顶层 :allocation_review; 读取时派生, 不改变任何逐条分摊批次."
  [data]
  (assoc data :allocation_review (allocation-review-summary (:allocations data))))


(def tax-scales
  "税额与净利舍入口径小数位: 税额四舍五入到分, 净利按分取整, 全程 BigDecimal 不经浮点."
  2)


(defn- tax-context
  "F08 税率口径: 取本位币对应的已发布税率表; 无发布版本返回 nil (净利不可算, 不猜默认税率).
   非本位币版本不参与计算 (税率按本位币维护, 外币口径需先折算)."
  [q currency]
  (when currency
    (let [rows (config/published q "tax-rate")]
      (some #(when (= currency (:base_currency %)) %) rows))))


(defn- taxable-minor
  "税额 = 收入 × 增值税税率, 四舍五入到分."
  [revenue-minor vat]
  (-> (BigDecimal/valueOf (long revenue-minor))
      (.multiply (BigDecimal. vat))
      (.setScale tax-scales java.math.RoundingMode/HALF_UP)
      (.longValueExact)))


(defn margin-summary
  "F08 成本毛利看板只读派生: 概算-预算-核算-决算各口径取最新已批准版本, 给出收入/成本/毛利金额与毛利率(百分比), 零或负收入标注不可算(computable=false); 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号. 传入税率为已发布税率表内容时再派生税额与税后净利 (taxable 为 false 的口径不给净利)."
  ([versions] (margin-summary versions nil))
  ([versions tax-file]
   (let [approved (filterv #(= "approved" (:status %)) versions)
         latest (into {} (for [kind ["estimate" "budget" "actual" "settlement"]]
                           [kind (->> approved (filter #(= kind (:kind %))) (sort-by :version_no >) first)]))
         one (fn [kind]
               (if-let [v (get latest kind)]
                 (let [rev (long (:revenue_minor v))
                       cost (long (:total_minor v))
                       margin (- rev cost)
                       computable (pos? rev)
                       ;; 税率只在与该版本币种口径一致时适用, 不一致则不猜税率.
                       same-tax (and tax-file (or (nil? (:currency v)) (= (:currency v) (:base_currency tax-file))))
                       taxable (and computable same-tax)
                       vat-minor (when taxable (taxable-minor rev (:vat_rate tax-file)))
                       net-rate-minor (when taxable (taxable-minor rev (:net_rate tax-file)))
                       net-minor (when taxable (- (- rev vat-minor) net-rate-minor))]
                   {:kind kind
                    :present true
                    :version_no (:version_no v)
                    :period (:period v)
                    :currency (:currency v)
                    :name (:name v)
                    :revenue (:revenue v)
                    :cost (:total v)
                    :margin (:margin v)
                    :computable computable
                    :margin-pct (when computable (rm/pct rev margin))
                    :taxable (boolean taxable)
                    :vat_rate (when taxable (:vat_rate tax-file))
                    :tax_amount (when taxable (money/money vat-minor))
                    :net_margin (when taxable (money/money net-minor))
                    :net_margin_pct (when (and taxable (pos? rev)) (rm/pct rev net-minor))})
                 {:kind kind :present false}))]
     {:available (boolean (seq approved))
      :comparable (<= (count (distinct (remove nil? (map :currency (vals latest))))) 1)
      :tax (when tax-file (select-keys tax-file [:code :base_currency :vat_rate :net_rate]))
      :taxable (boolean (some :taxable (map one ["estimate" "budget" "actual" "settlement"])))
      :by-kind (mapv one ["estimate" "budget" "actual" "settlement"])})))


(defn attach-forecast
  "H12/H13 把预测完工成本挂到财务概览读模型顶层 :forecast, 按 budget 与 estimate 两个基线口径各派生一份.
   需要查询函数与项目, 故在 overview 内以 let 显式调用而非线程; 只读派生, 不写费用版本."
  [q project-id data]
  (let [today (str (java.time.LocalDate/now))]
    (assoc data :forecast {:budget (forecast/forecast q project-id "budget" today)
                           :estimate (forecast/forecast q project-id "estimate" today)})))


(defn attach-margin-summary
  "把 margin-summary 挂到财务概览读模型顶层 :cost_margin; 读取时派生, 不改变任何逐条费用版本.
   有已发布税率表 (tax-rate) 且币种口径一致时同时派生税额与税后净利."
  [q data]
  (let [versions (:cost_versions data)
        currency (some->> versions (filter #(= "approved" (:status %))) (sort-by :version_no >) first :currency)]
    (assoc data :cost_margin (margin-summary versions (tax-context q currency)))))


(defn commitment-closure-summary
  "H12 按项目全部承诺台账行只读聚合承诺闭环健康度: 总数/各状态计数(草稿 draft, 审批中 submitted, 已批准 approved, 已驳回 rejected, 已释放 released, 已取消 cancelled)/待处理数(draft+submitted)/已作决定数(approved+released+rejected+cancelled)/审批完成率; 并对已批准及已释放承诺按本位金额汇总承诺总额/已转实付额/剩余未释放额与释放闭环率; 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [commitments]
  (let [total (count commitments)
        status-count (fn [x] (count (filterv #(= x (:status %)) commitments)))
        draft (status-count "draft")
        submitted (status-count "submitted")
        approved (status-count "approved")
        rejected (status-count "rejected")
        released (status-count "released")
        cancelled (status-count "cancelled")
        pending (+ draft submitted)
        processed (+ approved released rejected cancelled)
        releasing (filterv #(contains? #{"approved" "released"} (:status %)) commitments)
        base-minor (reduce + 0 (map :base_minor releasing))
        released-minor (reduce + 0 (map :released_minor releasing))
        remaining-minor (- base-minor released-minor)]
    {:available (pos? total)
     :total total
     :draft draft
     :submitted submitted
     :approved approved
     :rejected rejected
     :released released
     :cancelled cancelled
     :pending pending
     :processed processed
     :review-pct (rm/pct total processed)
     :base-amount (money/money base-minor)
     :released-amount (money/money released-minor)
     :remaining-amount (money/money remaining-minor)
     :release-pct (rm/pct base-minor released-minor)}))


(defn attach-commitment-closure-summary
  "把 commitment-closure-summary 挂到财务概览读模型顶层 :commitment_closure; 读取时派生, 不改变任何逐条承诺."
  [data]
  (assoc data :commitment_closure (commitment-closure-summary (:commitments data))))


(defn overview
  "在项目权限和财务权限交集内返回可对账四算与工时数据."
  [svc actor project-id]
  (kernel/read! svc actor project-id "pms:finance:query"
                (fn [q project]
                  (let [versions (mapv #(cost/dto q %) (q :finance/versions {:project_id project-id}))
                        data (-> (merge {:project_version (:version project) :cost_versions versions
                                         :time_entries (mapv time/dto (q :finance/times {:project_id project-id}))
                                         :allocations (mapv allocation/dto (q :finance/allocations {:project_id project-id}))
                                         :four_count (four-count-comparison versions)
                                         :commitments (commitment/list-all q project-id)
                                         :budget_rules (budget/list-rules q project-id)
                                         :budget_control {:budget (budget/evaluate q project-id "budget" 0)
                                                          :estimate (budget/evaluate q project-id "estimate" 0)}
                                         :labor_cost (rate/summary q project-id)
                                         :locked_periods (mapv :period (config/published q "period-lock"))}
                                        (summary versions)) time/attach-timesheet-review-summary attach-cost-review-summary attach-allocation-review-summary attach-commitment-closure-summary)]
                    ;; forecast 与 税后净利 都需要查询函数读取已发布配置, 故在 let 内显式调用而非放进线程.
                    (attach-forecast q project-id (attach-margin-summary q data))))))


(defn times
  "普通成员仅看本人工时和待本人审核的工时, 不附带财务金额."
  [svc actor project-id]
  (kernel/read! svc actor project-id "pms:project:query"
                (fn [q project]
                  {:project_version (:version project)
                   :rows (->> (q :finance/times {:project_id project-id})
                              (filter #(or (:admin? actor) (= (:user_id actor) (:user_id %))
                                           (= (:user_id actor) (:reviewer_id %))))
                              (mapv time/dto))})))


(defn closure-blockers
  "关闭前必须有已批准决算并处理所有待审核工时及费用版本."
  [q project]
  (let [params {:project_id (:project_id project)}
        versions (q :finance/versions params)
        times (q :finance/times params)]
    (cond-> []
      (not-any? #(and (= "settlement" (:kind %)) (= "approved" (:status %))) versions)
      (conj "缺少已批准的项目决算")
      (some #(contains? #{"draft" "submitted"} (:status %)) versions)
      (conj "存在尚未处理的费用草稿或待审批版本")
      (some #(= "submitted" (:status %)) times)
      (conj "存在待审核工时"))))
