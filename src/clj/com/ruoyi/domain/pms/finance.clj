(ns com.ruoyi.domain.pms.finance
  "四算, 工时和费用分摊读取模型, 财务金额查询拥有独立功能权限."
  (:require [com.ruoyi.domain.pms.finance-allocation :as allocation]
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

(defn overview
  "在项目权限和财务权限交集内返回可对账四算与工时数据."
  [svc actor project-id]
  (kernel/read! svc actor project-id "pms:finance:query"
    (fn [q project]
      (let [versions (mapv #(cost/dto q %) (q :finance/versions {:project_id project-id}))]
        (merge {:project_version (:version project) :cost_versions versions
                :time_entries (mapv time/dto (q :finance/times {:project_id project-id}))
                :allocations (mapv allocation/dto (q :finance/allocations {:project_id project-id}))}
               (summary versions))))))

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
