(ns com.ruoyi.domain.pms.finance-rate
  "H07 有效期费率表与批准工时人工成本只读派生: 人员费率优先于项目角色费率,
   按分钟比例折算到分 (向下取整, 不虚构精度), 缺费率的分钟如实单列为未覆盖."
  (:require
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance-money :as money]))


(defn- windowed?
  "费率有效期含端点; 无止日表示持续有效."
  [rate date]
  (and (string? (:effective_from rate))
       (<= (compare (:effective_from rate) date) 0)
       (or (nil? (:effective_to rate)) (<= (compare date (:effective_to rate)) 0))))


(defn- member-role
  [members user-id]
  (:role (some #(when (= user-id (:user_id %)) %) members)))


(defn rate-for
  "取用户在指定日期适用的费率: 人员范围优先, 其次按其项目成员角色; 无则 nil."
  [rates members user-id date]
  (or (some #(when (and (= "person" (:scope %)) (= user-id (:user_id %)) (windowed? % date)) %) rates)
      (when-let [role (member-role members user-id)]
        (some #(when (and (= "role" (:scope %)) (= role (:role %)) (windowed? % date)) %) rates))))


(defn- labor-amount
  "分钟 x 每小时费率 / 60, 整数除法向下取整到分."
  [rate-minor minutes]
  (quot (* (long rate-minor) (long minutes)) 60))


(defn summary
  "把项目已批准工时按当前生效费率表折算为人工成本 (读取时派生, 不写入任何费用版本)."
  [q project-id]
  (let [table (first (config/published q "rate-table"))
        rates (vec (:rates table))
        members (vec (q :pms/members {:project_id project-id}))
        rows (filterv #(= "approved" (:status %)) (q :finance/times {:project_id project-id}))
        enriched (mapv (fn [row]
                         (let [rate (rate-for rates members (:user_id row) (:work_date row))
                               amount (when rate (labor-amount (:amount_per_hour_minor rate) (:minutes row)))]
                           {:entry_id (:entry_id row) :user_id (:user_id row) :work_date (:work_date row)
                            :minutes (:minutes row) :hours (money/hours (:minutes row))
                            :covered (boolean rate)
                            :rate_code (:code rate) :rate_scope (:scope rate)
                            :amount (when amount (money/money amount)) :amount_minor (or amount 0)}))
                       rows)]
    {:configured? (boolean table)
     :base_currency (or (:base_currency table) "CNY")
     :covered_minor (reduce + 0 (map :amount_minor enriched))
     :covered_minutes (reduce + 0 (map #(if (:covered %) (:minutes %) 0) enriched))
     :uncovered_minutes (reduce + 0 (map #(if (:covered %) 0 (:minutes %)) enriched))
     :rows enriched}))
