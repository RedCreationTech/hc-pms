(ns com.ruoyi.domain.pms.finance-allocation
  "按批准工时分摊项目费用池, 固化输入与结果并保证幂等和总额守恒."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules]))

(defn dto
  "返回可复核分摊明细和输入来源快照."
  [row]
  (-> row (dissoc :input_json :result_json)
      (assoc :id (:allocation_id row) :amount (money/money (:amount_minor row))
             :inputs (json/parse-string (:input_json row) true)
             :rows (mapv #(assoc % :amount (money/money (:amount_minor %)))
                         (json/parse-string (:result_json row) true)))))

(defn- input!
  "校验费用池参数和明确期间, 保留来源幂等键."
  [project-id version-id body]
  (rules/object! body [:version :amount :from_date :to_date :idempotency_key :label])
  (let [from (rules/date! (:from_date body) "期间开始")
        to (rules/date! (:to_date body) "期间结束")
        amount (money/amount! (:amount body) "分摊金额")]
    (when-not (and from to (not (pos? (compare from to)))) (rules/fail! 400 "分摊期间无效"))
    (when-not (pos? amount) (rules/fail! 400 "分摊费用必须为正数"))
    {:project_id project-id :version_id version-id :amount_minor amount :from_date from :to_date to
     :idempotency_key (rules/text! (:idempotency_key body) "幂等键" 100 true)
     :label (rules/text! (:label body) "费用池名称" 150 true)}))

(defn- weights
  "按任务合并已批准分钟, 相同输入排序稳定."
  [times]
  (->> times (group-by :task_id)
       (map (fn [[task-id rows]] {:task_id task-id :minutes (reduce + 0 (map :minutes rows))}))
       (sort-by :task_id) vec))

(defn- create-allocation!
  "写入分摊输入快照和成本条目, 所有操作属于外层同一事务."
  [q params]
  (let [times (vec (q :finance/approved-times params))
        rows (money/distribute (:amount_minor params) (weights times))
        inputs {:request (select-keys params [:version_id :amount_minor :from_date :to_date :label])
                :times (mapv #(select-keys % [:entry_id :task_id :user_id :work_date :minutes]) times)}
        encoded (json/generate-string inputs)
        allocation (assoc params :allocation_id (kernel/id) :input_hash (money/digest encoded)
                          :input_json encoded :result_json (json/generate-string rows))]
    (q :finance/insert-allocation! allocation)
    (doseq [row rows :when (pos? (:amount_minor row))]
      (q :finance/insert-entry!
         {:entry_id (kernel/id) :project_id (:project_id params) :version_id (:version_id params)
          :category "labor" :label (str (:label params) " / " (:task_id row))
          :amount_minor (:amount_minor row)
          :source_ref (str "allocation:" (:allocation_id allocation) ":" (:task_id row))}))
    (dto allocation)))

(defn allocate!
  "执行费用池分摊, 相同业务键重试返回既有结果且不重复计费."
  [svc actor project-id version-id body]
  (let [params (input! project-id version-id body)]
    (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.allocated"
      (fn [q _]
        (if-let [existing (q :finance/allocation-key params)]
          (let [old (:request (json/parse-string (:input_json existing) true))]
            (when-not (= old (select-keys params [:version_id :amount_minor :from_date :to_date :label]))
              (rules/fail! 409 "幂等键已用于不同分摊请求"))
            (dto existing))
          (let [version (cost/draft! (cost/version! q project-id version-id))]
            (when-not (and (= (:period version) (subs (:from_date params) 0 7))
                           (= (:period version) (subs (:to_date params) 0 7)))
              (rules/fail! 400 "工时期间必须位于当前费用版本期间内"))
            (create-allocation! q params)))))))
