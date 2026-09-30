(ns com.ruoyi.domain.pms.finance
  "四算, 工时和费用分摊读取模型, 财务金额查询拥有独立功能权限."
  (:require [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.finance-allocation :as allocation]
            [com.ruoyi.domain.pms.finance-budget :as budget]
            [com.ruoyi.domain.pms.finance-commitment :as commitment]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.finance-time :as time]
            [com.ruoyi.domain.pms.kernel :as kernel]
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
     :review-pct (if (pos? total)
                   (int (Math/round ^double (* 100.0 (/ processed total))))
                   0)}))

(defn attach-cost-review-summary
  "把 cost-review-summary 挂到财务概览读模型顶层 :cost_review; 读取时派生, 不改变任何逐条费用版本."
  [data]
  (assoc data :cost_review (cost-review-summary (:cost_versions data))))

(defn overview
  "在项目权限和财务权限交集内返回可对账四算与工时数据."
  [svc actor project-id]
  (kernel/read! svc actor project-id "pms:finance:query"
    (fn [q project]
      (let [versions (mapv #(cost/dto q %) (q :finance/versions {:project_id project-id}))]
        (-> (merge {:project_version (:version project) :cost_versions versions
                :time_entries (mapv time/dto (q :finance/times {:project_id project-id}))
                :allocations (mapv allocation/dto (q :finance/allocations {:project_id project-id}))
                :four_count (four-count-comparison versions)
                :commitments (commitment/list-all q project-id)
                :budget_rules (budget/list-rules q project-id)
                :budget_control {:budget (budget/evaluate q project-id "budget" 0)
                                 :estimate (budget/evaluate q project-id "estimate" 0)}
                :locked_periods (mapv :period (config/published q "period-lock"))}
               (summary versions)) time/attach-timesheet-review-summary attach-cost-review-summary)))))

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
