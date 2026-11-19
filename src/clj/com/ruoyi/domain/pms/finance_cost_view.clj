(ns com.ruoyi.domain.pms.finance-cost-view
  "四算版本读模型投影: 仅依赖查询函数与金额格式化, 不依赖任何写路径.
   独立成叶节点命名空间, 供四算, 变更损失, 组合看板与集成出站共用同一 DTO 口径, 避免循环依赖."
  (:require
    [com.ruoyi.domain.pms.approval-chain :as chain]
    [com.ruoyi.domain.pms.finance-money :as money]))


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
               :margin (money/money (- (:revenue_minor version) total))
               ;; 按审批策略逐级审批中 (前端据此隐藏原单人审核入口)
               :chain_pending (and (= "submitted" (:status version))
                                   (some? (chain/pending-flow q "cost-version" (:version_id version))))))))
