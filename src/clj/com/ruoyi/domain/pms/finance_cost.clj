(ns com.ruoyi.domain.pms.finance-cost
  "四算版本和成本明细, 提交快照及独立审批后保持不可变."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules])
  (:import [java.time YearMonth]))

(def categories
  "四算成本允许的分类."
  #{"material" "labor" "manufacturing" "travel" "other" "change_loss"})

(defn version!
  "读取同项目的费用版本, 缺失返回404."
  [q project-id version-id]
  (or (q :finance/version {:project_id project-id :version_id version-id})
      (rules/fail! 404 "费用版本不存在")))

(defn draft!
  "已提交和已批准版本不能覆盖, 修订应另建版本."
  [version]
  (when-not (= "draft" (:status version)) (rules/fail! 409 "费用版本已锁定,请创建修订版本"))
  version)

(defn entry-dto
  "返回精确金额字符串及成本项标识."
  [entry]
  (assoc entry :id (:entry_id entry) :amount (money/money (:amount_minor entry))))

(defn dto
  "读取费用版本的明细及可复算总额, 不使用数据库浮点SUM."
  [q version]
  (let [entries (mapv entry-dto (q :finance/entries version))
        total (reduce + 0 (map :amount_minor entries))]
    (-> version (dissoc :snapshot_json)
        (assoc :id (:version_id version) :entries entries
               :revenue (money/money (:revenue_minor version))
               :total (money/money total) :total_minor total
               :margin (money/money (- (:revenue_minor version) total))))))

(defn- input!
  "校验口径, 期间, 币种和指定审批人."
  [q actor project body]
  (rules/object! body [:version :kind :period :currency :name :revenue :reviewer_id])
  (when-not (contains? #{"estimate" "budget" "actual" "settlement"} (:kind body))
    (rules/fail! 400 "费用口径必须为概算/预算/核算/决算"))
  (let [period (rules/text! (:period body) "期间" 7 true)
        reviewer (kernel/user! q project (:reviewer_id body) "财务审批人")
        revenue (money/amount! (or (:revenue body) "0") "收入")]
    (when-not (re-matches #"[0-9]{4}-[0-9]{2}" period) (rules/fail! 400 "期间格式必须为YYYY-MM"))
    (try (YearMonth/parse period) (catch Exception _ (rules/fail! 400 "期间无效")))
    (when-not (money/currencies (:currency body)) (rules/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD"))
    (when (neg? revenue) (rules/fail! 400 "收入不能为负数"))
    (when (= reviewer (:user_id actor)) (rules/fail! 400 "财务审批人不能是提交者"))
    {:version_id (kernel/id) :project_id (:project_id project) :kind (:kind body)
     :period period :currency (:currency body) :revenue_minor revenue
     :name (rules/text! (:name body) "版本名称" 200 true)
     :submitted_by (:user_id actor) :reviewer_id reviewer}))

(defn create!
  "为明确期间建立新四算草稿, 编号在项目事务内递增."
  [svc actor project-id body]
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.created"
    (fn [q project]
      (let [version (input! q actor project body)
            version (assoc version :version_no (:next_no (q :finance/next-version version)))]
        (q :finance/insert-version! version)
        (dto q (version! q project-id (:version_id version)))))))

(defn add-entry!
  "新增草稿成本明细, 金额按精确最小单位存储并约束来源去重."
  [svc actor project-id version-id body]
  (rules/object! body [:version :category :label :amount :source_ref])
  (when-not (categories (:category body)) (rules/fail! 400 "无效费用分类"))
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.entry.created"
    (fn [q _]
      (let [version (draft! (version! q project-id version-id))
            entry {:entry_id (kernel/id) :project_id project-id :version_id version-id
                   :category (:category body) :label (rules/text! (:label body) "费用说明" 200 true)
                   :amount_minor (money/amount! (:amount body) "金额")
                   :source_ref (not-empty (rules/text! (:source_ref body) "来源引用" 200 false))}]
        (q :finance/insert-entry! entry)
        (dto q version)))))

(defn delete-entry!
  "仅允许移除尚未提交且不是分摊生成的草稿明细."
  [svc actor project-id version-id entry-id body]
  (rules/object! body [:version])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.entry.deleted"
    (fn [q _]
      (let [version (draft! (version! q project-id version-id))
            entry (q :finance/entry {:project_id project-id :entry_id entry-id})]
        (when-not (= version-id (:version_id entry)) (rules/fail! 404 "费用项不存在"))
        (when (.startsWith (or (:source_ref entry) "") "allocation:")
          (rules/fail! 409 "分摊结果不可单独删除,请创建新的费用版本"))
        (rules/changed! (q :finance/delete-entry! entry))
        (dto q version)))))

(defn submit!
  "冻结成本明细和口径为待审批快照."
  [svc actor project-id version-id body]
  (rules/object! body [:version])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.submitted"
    (fn [q project]
      (let [version (draft! (version! q project-id version-id))
            snapshot (dto q version)]
        (when (empty? (:entries snapshot)) (rules/fail! 409 "费用明细为空,不能提交"))
        (when (= (:user_id actor) (:reviewer_id version)) (rules/fail! 403 "指定审批人不能代替提交者提交"))
        (kernel/user! q project (:reviewer_id version) "财务审批人")
        (rules/changed! (q :finance/submit-version!
                           (assoc version :submitted_by (:user_id actor)
                                  :snapshot_json (json/generate-string snapshot))))
        (dto q (version! q project-id version-id))))))

(defn review!
  "对锁定版本独立批准或驳回, 原始快照和明细均保留."
  [svc actor project-id version-id body]
  (rules/object! body [:version :decision :reason])
  (when-not (contains? #{"approved" "rejected"} (:decision body)) (rules/fail! 400 "无效审核决定"))
  (kernel/mutate! svc actor project-id "pms:finance:approve" body "cost.reviewed" {:write? false}
    (fn [q project]
      (let [version (version! q project-id version-id)
            reason (rules/text! (:reason body) "审核意见" 1000 (= "rejected" (:decision body)))]
        (when-not (= "submitted" (:status version)) (rules/fail! 409 "费用版本不在待审批状态"))
        (kernel/independent-review! actor (:submitted_by version) (:reviewer_id version))
        (kernel/user! q project (:reviewer_id version) "财务审批人")
        (rules/changed! (q :finance/review-version! (assoc version :status (:decision body) :review_note reason)))
        (dto q (version! q project-id version-id))))))

(defn revise!
  "复制已处理版本形成新草稿, 不修改原批准或驳回记录."
  [svc actor project-id version-id body]
  (rules/object! body [:version :reviewer_id])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.revised"
    (fn [q project]
      (let [old (version! q project-id version-id)
            _ (when-not (contains? #{"approved" "rejected"} (:status old)) (rules/fail! 409 "只能修订已处理版本"))
            reviewer (kernel/user! q project (:reviewer_id body) "财务审批人")
            _ (when (= reviewer (:user_id actor)) (rules/fail! 400 "不能指定自己审批"))
            version (assoc old :version_id (kernel/id) :reviewer_id reviewer
                               :submitted_by (:user_id actor)
                               :version_no (:next_no (q :finance/next-version old)))]
        (q :finance/insert-version! version)
        (doseq [entry (q :finance/entries old)]
          (q :finance/insert-entry! (assoc entry :entry_id (kernel/id) :version_id (:version_id version))))
        (dto q (version! q project-id (:version_id version)))))))

(defn cancel!
  "保留历史地放弃费用草稿或驳回版本, 避免遗留草稿阻塞正式收尾."
  [svc actor project-id version-id body]
  (rules/object! body [:version :reason])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "cost.cancelled"
    (fn [q _]
      (let [version (version! q project-id version-id)
            reason (rules/text! (:reason body) "取消原因" 1000 true)]
        (when-not (contains? #{"draft" "rejected"} (:status version))
          (rules/fail! 409 "只能取消费用草稿或驳回版本"))
        (rules/changed! (q :finance/cancel-version! (assoc version :review_note reason)))
        (dto q (version! q project-id version-id))))))
