(ns com.ruoyi.domain.pms.finance-change-loss
  "F07 变更损失: 经批准的正式变更与核算费用明细逐项关联, 并按产品线 / 变更类型只读汇总.
   关联存在费用明细行上 (change_code 引用治理变更记录的业务编码), 只在核算口径的草稿版本内登记,
   因此登记前必须已有独立批准 (approved) 的变更记录, 且每个版本内同一条变更至多关联一条损失行,
   避免与成本账项重复计费. 汇总为读取时派生, 不落库, 不构成门控."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.finance-cost-view :as cost-view]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.governance.store :as store]
    [com.ruoyi.domain.pms.rules :as rules]))


(def project-key
  "无法归属产品线 (未填写 contract_no) 时的兜底分组键."
  "-")


(defn product-of
  "F07 项目所属产品线: 取项目合同号 (pms_project.contract_no) 作为本地可用的产品线分组维度,
   未填写时归入 project-key. 产品线主数据属于企业系统, 本地不臆造."
  [project]
  (let [line (:contract_no project)]
    (if (and (string? line) (seq (str/trim line)))
      (str/trim line)
      project-key)))


(defn- approved-change
  "返回该业务编码下决定性的已批准版本: 修订链上任一版本被独立批准即已有正式变更依据,
   但最新修订若已被明确驳回则视为否决, 不再接受关联 (须重新提交并经批准). 不存在返回 nil."
  [q project code]
  (let [chain (sort-by :revision (filter #(= code (:code %)) (store/records q project "change")))
        latest (last chain)]
    (when (and latest (not= "rejected" (:status latest)))
      (last (filter #(= "approved" (:status %)) chain)))))


(defn- change-journal
  "项目内每条治理变更最新修订的状态索引, 用于汇总时只统计已批准变更."
  [q project]
  (into {} (for [r (store/latest (store/records q project "change"))]
             [(:code r) {:status (:status r)
                         :title (:title r)
                         :change_type (:change_type r)
                         :schedule_impact_days (:schedule_impact_days r)
                         :cost_impact_amount (:cost_impact_amount r)}])))


(defn bind!
  "F07 在给定查询函数与已解码项目上下文中校验并写入一条变更损失关联, 返回更新后的变更编码.
   本命名空间只依赖规则与查询, 由 finance-cost 在项目事务内调用, 避免与四算模块循环依赖.
   约束: 只允许核算 (actual) 口径; 变更须存在且已有批准版本; 同版本内同一条变更至多一条损失;
   变更已声明成本影响时损失金额须与之相等, 避免既报影响又按不同金额重复计入成本."
  [q project {:keys [version vid entry change-code]}]
  (when-not (= "actual" (:kind version))
    (rules/fail! 400 "变更损失只能登记在核算口径的费用版本中"))
  (when-not (= "change_loss" (:category entry))
    (rules/fail! 400 "只有变更损失分类的费用项可以关联变更"))
  (let [code (rules/text! change-code "变更编码" 100 true)
        change (approved-change q project code)]
    (when-not change
      (rules/fail! 409 "只能关联已独立批准的正式变更, 请先完成该变更的审批"))
    (let [declared (not-empty (:cost_impact_amount change))]
      (when (some? declared)
        (let [declared-minor (money/amount! declared "变更成本影响")]
          (when (not= declared-minor (long (:amount_minor entry)))
            (rules/fail! 409 (str "损失金额必须与变更已声明的成本影响一致 (变更声明 "
                                  (money/money declared-minor) ")"))))))
    (when-let [other (->> (q :finance/entries {:project_id (:project_id project)
                                               :version_id vid})
                          (some #(when (and (= code (:change_code %))
                                            (not= (:entry_id entry) (:entry_id %)))
                                   %)))]
      (rules/fail! 409 (str "该变更已在版本内登记过损失 (" (:label other) "), 不重复计费")))
    (when-some [existing (:change_code entry)]
      (when-not (= existing code)
        (rules/fail! 409 "该损失明细已关联其它变更, 请先另行新建费用项")))
    code))


(defn- tagged-entries
  "在版本明细中找出已关联变更的损失行."
  [version]
  (filterv :change_code (:entries version)))


(defn summary
  "F07 项目级变更损失只读汇总: 只统计已批准四算版本中已关联变更的损失明细.
   每个版本内同一条变更多版 (概算/预算/核算) 只取一笔, 核算优先, 其次预算, 最后概算, 因此不与成本账项重复计费;
   按产品线 (项目 contract_no, 缺失归入 '-') 与变更类型 (未设定归入 unspecified) 分组给出笔数, 损失金额, 量化工期影响;
   只统计已批准变更, 未批准变更即使挂有损失也不计入并在 unapproved 计数."
  ([q project] (summary q project (change-journal q project)))
  ([q project journal]
   (let [versions (filterv #(= "approved" (:status %)) (q :finance/versions {:project_id (:project_id project)}))
         product (product-of project)
         rank {"actual" 0 "budget" 1 "estimate" 2 "settlement" 3}
         ;; 同一变更在多口径版本中的损失只保留一笔: 核算优先, 避免同一损失重复计入.
         chosen (->> versions
                     (mapcat (fn [v]
                               (map #(assoc % :version_kind (:kind v) :version_id (:version_id v)
                                            :version_no (:version_no v))
                                    (tagged-entries (cost-view/dto q v)))))
                     (sort-by (fn [e] [(get rank (:version_kind e) 9) (- (long (:version_no e))) (:entry_id e)]))
                     (reduce (fn [{:keys [seen rows] :as acc} e]
                               (if (seen (:change_code e))
                                 acc
                                 {:seen (conj seen (:change_code e)) :rows (conj rows e)}))
                             {:seen #{} :rows []})
                     :rows)
         currency (some->> versions (sort-by :version_no >) first :currency)
         classify (fn [e]
                    (let [info (get journal (:change_code e))]
                      (cond
                        (nil? info) :orphan
                        (not= "approved" (:status info)) :unapproved
                        :else :counted)))
         counted (filterv #(= :counted (classify %)) chosen)
         bucket (fn [rows key-fn]
                  (->> rows
                       (group-by key-fn)
                       (map (fn [[k rs]]
                              {:key (str k)
                               :count (count rs)
                               :amount (money/money (reduce + 0 (map :amount_minor rs)))
                               :amount_minor (reduce + 0 (map :amount_minor rs))
                               :schedule_days (reduce + 0 (map #(long (or (:schedule_impact_days (get journal (:change_code %))) 0)) rs))}))
                       (sort-by :key)
                       vec))
         total-minor (reduce + 0 (map :amount_minor counted))
         all-minor (reduce + 0 (map :amount_minor chosen))]
     {:available (boolean (seq versions))
      :currency currency
      :line "变更已声明的成本影响"
      :reconciled (= total-minor all-minor)
      :total_count (count counted)
      :total_amount (money/money total-minor)
      :total_schedule_days (reduce + 0 (map #(long (or (:schedule_impact_days (get journal (:change_code %))) 0)) counted))
      :by_product_line (bucket counted (constantly product))
      :by_type (bucket counted (fn [e]
                                 (let [t (:change_type (get journal (:change_code e)))]
                                   (if (seq t) t "unspecified"))))
      :unapproved_count (count (filterv #(= :unapproved (classify %)) chosen))
      :unapproved_amount (money/money (reduce + 0 (map :amount_minor (filterv #(= :unapproved (classify %)) chosen))))
      :orphan_count (count (filterv #(= :orphan (classify %)) chosen))
      :orphan_amount (money/money (reduce + 0 (map :amount_minor (filterv #(= :orphan (classify %)) chosen))))})))


(defn summary-for
  "按项目标识重建项目上下文后汇总; 供财务概览复用同一查询函数而不引入写路径依赖."
  [q project-id]
  (if-let [project (q :pms/project {:project_id project-id})]
    (summary q project)
    (summary q {:project_id project-id})))


(defn attach
  "把变更损失汇总挂到财务概览读模型顶层 :change_loss; 读取时派生, 不改变任何逐条费用版本."
  [q project-id data]
  (assoc data :change_loss (summary-for q project-id)))
