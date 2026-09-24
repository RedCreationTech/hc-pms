(ns com.ruoyi.domain.pms.finance-pool
  "F05 跨项目研发费用池: 冻结费用池按期间内各项目已批准工时分摊 (最大余数法, 总额守恒),
   在每个项目生成待审核的核算 (actual) 版本与人工成本条目, 零工时项目不分摊, 幂等不重复计费."
  (:require [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules])
  (:import [java.time YearMonth]))

(defn- period-range
  [period]
  (let [ym (YearMonth/parse period)]
    {:from_date (str (.atDay ym 1)) :to_date (str (.atEndOfMonth ym))}))

(defn- weights
  "按项目合并期间内已批准分钟 (排除已更正的原单), 相同输入排序稳定."
  [q period]
  (->> (q :finance/approved-times-in-period (period-range period))
       (group-by :project_id)
       (map (fn [[pid rows]] {:task_id pid :minutes (reduce + 0 (map :minutes rows))}))
       (sort-by :task_id) vec))

(defn preview
  "只读预览分摊结果, 不写入."
  [svc actor config-id]
  (rules/permit! actor ["pms:finance:query" "pms:finance:approve"])
  (let [q (:query-fn svc) pool (config/record! q "rd-pool" config-id)
        ws (weights q (:period pool))
        rows (when (some #(pos? (:minutes %)) ws) (money/distribute (:amount_minor pool) ws))]
    {:pool (select-keys pool [:id :code :period :amount :currency :status :allocation])
     :total_minutes (reduce + 0 (map :minutes ws))
     :rows (mapv (fn [row] (let [project (q :pms/project {:project_id (:task_id row)})]
                             {:project_id (:task_id row) :project_no (:project_no project) :project_name (:name project)
                              :minutes (:minutes row) :hours (money/hours (:minutes row))
                              :amount (money/money (:amount_minor row)) :amount_minor (:amount_minor row)}))
                 rows)
     :conserved (= (:amount_minor pool) (reduce + 0 (map :amount_minor rows)))}))

(defn allocate!
  "执行跨项目分摊: 每个受益项目生成核算草稿版本 (待各自财务审批), 费用池记录分摊结果并不可重复分摊."
  [svc actor config-id body]
  (rules/permit! actor "pms:finance:approve")
  (rules/object! body [:reviewer_id :name])
  (kernel/transaction! svc
    (fn [q]
      (let [pool (config/record! q "rd-pool" config-id)]
        (s/status! pool #{"frozen"})
        (when (:allocation pool) (rules/fail! 409 "费用池已完成分摊, 不能重复计费"))
        (let [reviewer (s/user! q (:reviewer_id body))
              ws (weights q (:period pool))
              rows (money/distribute (:amount_minor pool) ws)
              name (rules/text! (or (:name body) (str "跨项目研发费用池 " (:code pool))) "版本名称" 200 true)
              results (vec (for [row rows :when (pos? (:amount_minor row))
                                 :let [project (q :pms/project {:project_id (:task_id row)})
                                       version {:version_id (kernel/id) :project_id (:project_id project) :kind "actual"
                                                :period (:period pool) :currency (:currency pool) :name name
                                                :version_no (:next_no (q :finance/next-version {:project_id (:project_id project) :kind "actual" :period (:period pool)}))
                                                :submitted_by (:user_id actor) :reviewer_id reviewer}]]
                             (do (when (= reviewer (:user_id actor)) (rules/fail! 400 "财务审批人不能是分摊执行人"))
                                 (q :finance/insert-plain-version! version)
                                 (q :finance/insert-entry! {:entry_id (kernel/id) :project_id (:project_id project) :version_id (:version_id version)
                                                            :category "labor" :label (str name " / " (:minutes row) " 分钟")
                                                            :amount_minor (:amount_minor row) :source_ref (str "rd-pool:" (:id pool))})
                                 {:project_id (:project_id project) :project_no (:project_no project) :project_name (:name project)
                                  :minutes (:minutes row) :amount (money/money (:amount_minor row)) :amount_minor (:amount_minor row)
                                  :version_id (:version_id version)})))]
          (when-not (= (:amount_minor pool) (reduce + 0 (map :amount_minor results))) (rules/fail! 500 "分摊结果与费用池总额不守恒"))
          (config/change! q "rd-pool" pool "frozen" actor "allocated" (str "分摊到 " (count results) " 个项目")
                          {:allocation {:rows results :allocated_at (str (java.time.Instant/now)) :allocated_by (:user_id actor)
                                        :reviewer_id reviewer :total_minutes (reduce + 0 (map :minutes ws))}}))))))
