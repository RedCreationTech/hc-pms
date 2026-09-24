(ns com.ruoyi.frontend.pages.pms.finance
  "项目工时,成本版本与按实际工时分摊工作台."
  (:require [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.approval :as approval]
            [com.ruoyi.frontend.pages.pms.governance-forms :as forms]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]
            [reagent.hooks :as hooks]))

(def kinds [{:value "estimate" :label "概算"} {:value "budget" :label "预算"}
            {:value "actual" :label "核算"} {:value "settlement" :label "决算"}])
(def categories [{:value "material" :label "材料"} {:value "labor" :label "人工"}
                 {:value "manufacturing" :label "制造"} {:value "travel" :label "差旅"}
                 {:value "other" :label "其他"} {:value "change_loss" :label "变更损失"}])

(defn- cost-dialog
  "新建固定期间与币种的独立成本版本."
  [base options]
  {:title "新建成本版本" :path (str base "/cost-versions") :initial {:kind "budget" :currency "CNY" :revenue "0.00"}
   :fields [{:key :name :label "成本版本名称" :required? true}
            {:key :kind :label "成本阶段" :type :select :options kinds :required? true}
            {:key :period :label "核算期间" :required? true :hint "YYYY-MM,例如2026-09"}
            {:key :currency :label "币种" :type :select :options (mapv #(hash-map :value % :label %) ["CNY" "USD" "EUR" "GBP" "HKD"]) :required? true}
            {:key :revenue :label "收入金额" :required? true :hint "精确到小数点后两位,同版本禁止混币."}
            (forms/reviewer-field options)]})

(defn- time-dialog
  "提交绑定真实WBS任务的实际工时单."
  [base planning options]
  {:title "提交实际工时" :path (str base "/time-entries") :initial {:hours "8.00"}
   :fields [{:key :task_id :label "WBS任务" :type :select :required? true :options (w/options (:tasks planning) :task_id :name)}
            {:key :work_date :label "工作日期" :type :date :required? true}
            {:key :hours :label "实际工时" :required? true :hint "以小时填写,最多两位小数."}
            {:key :note :label "工作内容" :type :textarea :required? true}
            (forms/reviewer-field options)]})

(defn- cost-entry-dialog
  "为草稿成本版本添加可追溯条目."
  [base cost]
  {:title "添加成本条目" :path (str base "/cost-versions/" (:id cost) "/entries") :initial {:category "material"}
   :description (str (:name cost) " / " (:currency cost))
   :fields [{:key :category :label "成本类别" :type :select :options categories :required? true}
            {:key :label :label "条目名称" :required? true}
            {:key :amount :label "金额" :required? true :hint "精确到小数点后两位."}
            {:key :source_ref :label "来源单据引用" :required? true}]})

(defn- allocation-dialog
  "按已批准实际工时进行可重放的精确分摊."
  [base cost]
  {:title "按批准工时分摊" :path (str base "/cost-versions/" (:id cost) "/allocate")
   :description "仅使用指定期间内已批准的工时.没有可分摊工时时服务端拒绝操作.同一业务批次标识不会重复记账."
   :fields [{:key :label :label "分摊事项" :required? true}
            {:key :amount :label "待分摊金额" :required? true}
            {:key :from_date :label "期间开始" :type :date :required? true}
            {:key :to_date :label "期间结束" :type :date :required? true}
            {:key :idempotency_key :label "业务批次编号" :required? true :hint "同一批次重试请保持编号不变."}]})

(defn- review-dialog
  "成本和工时审批均保留独立意见."
  [path decision title]
  {:title title :path path :transform #(assoc % :decision decision)
   :fields [{:key :reason :label "审批意见" :type :textarea :required? true}]})

(defn- reviewer?
  "限定指定审批人与提交人分离."
  [options record]
  (and (= (:currentUserId options) (:reviewer_id record))
       (not= (:currentUserId options) (or (:submitted_by record) (:user_id record)))))

(defn- summary-cards
  "展示按服务端口径计算的四阶段金额与毛利."
  [model]
  [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit,minmax(200px,1fr))" :gap 14 :marginBottom 22}}
   (for [{:keys [value label]} kinds
         :let [summary (get-in model [:summary (keyword value)])]]
     ^{:key value}
     [:div {:style {:border "1px solid #e8edf3" :borderRadius 8 :padding 18}}
      [:div {:style {:color "#718096"}} label]
      [:div {:style {:fontSize 24 :fontWeight 650 :margin "10px 0"}} (or (:amount summary) "—")]
      [:div {:style {:fontSize 12 :color "#718096"}}
       (str (or (:currency summary) "") " / 收入 " (or (:revenue summary) "—") " / 毛利 " (or (:margin summary) "—"))]])])

(defn- time-section
  "工时只有独立批准后才进入实际分摊口径."
  [{:keys [base model planning options time-editable? time-approve? open!]}]
  [shared/panel "实际工时单" "绑定任务,独立审批,保留原始填报记录"
   (when time-editable? [antd/button {:on-click #(open! (time-dialog base planning options))} "提交实际工时"])
   [w/record-table (:time_entries model)
    [{:title "任务" :dataIndex "task_id" :render #(w/related-label (:tasks planning) :task_id :name %)}
     (w/text-column :work_date "工作日期") (w/text-column :hours "小时")
     (w/text-column :note "工作内容")
     {:title "更正" :dataIndex "corrects_entry_id" :width 160
      :render (fn [v row] (r/as-element (cond (seq v) [antd/tag {:color "orange"} (str "更正单 · " (aget row "correction_reason"))]
                                              (= "corrected" (aget row "status")) [antd/tag "已被更正"]
                                              :else [:span "—"])))}
     {:title "状态" :dataIndex "status" :width 100
      :render #(r/as-element [antd/tag {:color (case % "approved" "green" "rejected" "red" "corrected" "default" "blue")}
                              (get {"submitted" "待审核" "approved" "已批准" "rejected" "已驳回" "corrected" "已更正"} % %)])}]
    (fn [entry]
      [antd/space
       (when (and time-approve? (= "submitted" (:status entry)) (reviewer? options entry))
         [:<>
          [w/edit-button "批准" #(open! (review-dialog (str base "/time-entries/" (:id entry) "/review") "approved" "批准工时单"))]
          [w/edit-button "驳回" #(open! (review-dialog (str base "/time-entries/" (:id entry) "/review") "rejected" "驳回工时单"))]])
       (when (and time-editable? (= "approved" (:status entry)) (= (:currentUserId options) (:user_id entry)))
         [w/edit-button "更正" #(open! {:title "更正已批准工时" :path (str base "/time-entries/" (:id entry) "/correct")
                                       :description "原工时单置为已更正并释放容量, 更正单重新独立审核; 封期内不可更正."
                                       :initial {:hours (:hours entry) :note (:note entry)}
                                       :fields [{:key :hours :label "更正后小时" :required? true}
                                                {:key :note :label "工作内容" :required? true}
                                                {:key :reason :label "更正原因" :type :textarea :required? true}
                                                (forms/reviewer-field options)]})])])]])

(def category-labels
  {"material" "材料" "labor" "人工" "manufacturing" "制造" "travel" "差旅/现场" "other" "其它" "change_loss" "变更损失"})

(defn- four-count-section
  "F06 四算拉通: 概算/预算/核算/决算最新批准版本按分类对比与逐级差异 (只读派生)."
  [{:keys [model]}]
  (let [fc (:four_count model) v (:versions fc)]
    [shared/panel "四算拉通" (if (:comparable fc) (str "各口径最新已批准版本 · " (or (:currency fc) "")) "各口径币种不一致, 不可直接比较") nil
     (if (empty? (:rows fc))
       [:span {:style {:color "#98a2b3"}} "尚无已批准的成本版本, 批准后自动拉通比较."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         (for [[k label] [[:budget_vs_estimate "预算-概算"] [:actual_vs_budget "核算-预算"] [:settlement_vs_actual "决算-核算"] [:settlement_vs_budget "决算-预算"]]
               :let [val (get-in fc [:variances k])] :when val] ^{:key k}
           [antd/tag {:color (if (.startsWith val "-") "green" "volcano")} (str label " " val)])]
        [antd/table {:rowKey "category" :size "small" :pagination false
                     :dataSource (clj->js (conj (vec (:rows fc)) (assoc (:totals fc) :category "合计")))
                     :columns (clj->js [{:title "分类" :dataIndex "category" :render (fn [c] (get category-labels c c))}
                                        {:title (str "概算" (when (:estimate v) (str " v" (:version_no (:estimate v))))) :dataIndex "estimate" :render (fn [x] (or x "—"))}
                                        {:title (str "预算" (when (:budget v) (str " v" (:version_no (:budget v))))) :dataIndex "budget" :render (fn [x] (or x "—"))}
                                        {:title (str "核算" (when (:actual v) (str " v" (:version_no (:actual v))))) :dataIndex "actual" :render (fn [x] (or x "—"))}
                                        {:title (str "决算" (when (:settlement v) (str " v" (:version_no (:settlement v))))) :dataIndex "settlement" :render (fn [x] (or x "—"))}])}]])]))

(defn- cost-actions
  "根据版本状态提供条目维护,提交或独立审批."
  [{:keys [base options editable? approve? open! select!]} cost]
  (let [path (str base "/cost-versions/" (:id cost))]
    [antd/space {:wrap true}
     [w/edit-button "查看明细" #(select! (:id cost))]
     (when (and editable? (= "draft" (:status cost)))
       [:<>
        [w/edit-button "添加条目" #(open! (cost-entry-dialog base cost))]
        [w/edit-button "工时分摊" #(open! (allocation-dialog base cost))]
        [w/edit-button "提交审批" #(open! {:title "提交成本版本审批" :path (str path "/submit") :fields []})]])
     (when (and editable? (contains? #{"approved" "rejected"} (:status cost)))
       [w/edit-button "新修订" #(open! {:title "修订成本版本" :path (str path "/revise")
                                       :fields [(forms/reviewer-field options)]})])
     (when (and editable? (contains? #{"draft" "rejected"} (:status cost)))
       [w/edit-button "放弃版本" #(open! {:title "放弃成本版本" :path (str path "/cancel")
                                         :fields [{:key :reason :label "放弃原因" :type :textarea :required? true}]})])
     (when (and approve? (= "submitted" (:status cost)) (not (:chain_pending cost)) (reviewer? options cost))
       [:<>
        [w/edit-button "批准" #(open! (review-dialog (str path "/review") "approved" "批准成本版本"))]
        [w/edit-button "驳回" #(open! (review-dialog (str path "/review") "rejected" "驳回成本版本"))]])]))

(defn- cost-section
  "四阶段成本版本相互独立,批准后形成比较依据."
  [{:keys [base model options editable? open!] :as context}]
  [shared/panel "成本版本台账" "概算 / 预算 / 核算 / 决算"
   (when editable? [antd/button {:type "primary" :on-click #(open! (cost-dialog base options))} "新建成本版本"])
   [w/record-table (:cost_versions model)
    [(w/text-column :name "版本名称") {:title "阶段" :dataIndex "kind" :render #(or (:label (some (fn [x] (when (= % (:value x)) x)) kinds)) %)}
     (w/text-column :period "期间") (w/text-column :currency "币种") (w/text-column :total "成本金额")
     (w/text-column :revenue "收入") (w/state-column)] #(cost-actions context %) ]])

(defn- ledger-section
  "按选中的真实成本版本显示条目与来源."
  [{:keys [base model editable? open!]} selected]
  (when-let [cost (some #(when (= selected (:id %)) %) (:cost_versions model))]
    [shared/panel (str "成本明细 / " (:name cost)) (:currency cost) nil
     [approval/biz-flow base "cost-version" (:id cost)]
     [w/record-table (:entries cost)
      [(w/text-column :label "条目") {:title "类别" :dataIndex "category" :render #(or (:label (some (fn [x] (when (= % (:value x)) x)) categories)) %)}
       (w/text-column :amount "金额") (w/text-column :source_ref "来源引用")]
      (when (and editable? (= "draft" (:status cost)))
        (fn [entry]
          (when-not (.startsWith (or (:source_ref entry) "") "allocation:")
            [w/edit-button "删除条目"
             #(open! {:title "删除成本条目" :method :delete :fields []
                       :description (str "删除条目: " (:label entry))
                       :path (str base "/cost-versions/" (:id cost) "/entries/" (:id entry))})])))]]))

(defn- allocation-history
  "显示已固化的费用分摊批次及总额."
  [model]
  [shared/panel "费用分摊记录" "每次分摊保留期间,幂等批次和输入摘要" nil
   [w/record-table (:allocations model)
    [(w/text-column :label "分摊事项") (w/text-column :amount "分摊总额")
     (w/text-column :from_date "开始日期") (w/text-column :to_date "结束日期")
     (w/text-column :idempotency_key "业务批次") (w/text-column :input_hash "输入摘要")] nil]])

(defn- finance-content
  "以成本与工时双视图组织财务工作."
  [context selected]
  [:div
   [:p {:style {:fontSize 12 :color "#718096"}}
    (if-let [period (get-in context [:model :summary_context :period])]
      (str "比较口径: " period " / " (get-in context [:model :summary_context :currency]) " / 仅采用各阶段最新批准版本")
      "成本比较将在版本获得批准后生成.")]
   [summary-cards (:model context)]
   [antd/tabs {:items [{:key "costs" :label "成本与分摊"
                        :children (r/as-element [:div {:style {:display "grid" :gap 20}}
                                                [four-count-section context]
                                                [cost-section context] [ledger-section context selected] [allocation-history (:model context)]])}
                       {:key "time" :label "实际工时" :children (r/as-element [time-section context])}]}]])

(defn- finance-data
  "集中加载费用读模型,审批结果会刷新项目版本."
  [project revision options changed!]
  (let [base (str "/projects/" (:project_id project))
        resource (shared/use-resource (str base "/finance") {} [revision])
        planning (shared/use-resource (str base "/planning") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil) [selected set-selected!] (hooks/use-state nil)
        context {:base base :model (:data resource) :planning (:data planning) :options options
                 :editable? (and (shared/use-permission "pms:finance:edit") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
                 :time-editable? (and (shared/use-permission "pms:project:edit") (= "execution" (:status project)))
                 :approve? (and (shared/use-permission "pms:finance:approve") (not (contains? #{"closed" "cancelled" "paused"} (:status project)))) :time-approve? (and (shared/use-permission "pms:time:approve") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
                 :open! set-dialog! :select! set-selected!}]
    [:div
     [w/resource-view (shared/first-load resource planning) (fn [_] [finance-content context selected])]
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                   :on-saved (fn [_] (set-dialog! nil) (changed!))})])]))

(defn time-workspace
  "普通项目成员通过工时专用读模型查看和提交工时,不读取费用."
  [project revision options changed!]
  (let [base (str "/projects/" (:project_id project))
        resource (shared/use-resource (str base "/time-entries") {} [revision])
        planning (shared/use-resource (str base "/planning") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil)
        context {:base base :model {:time_entries (get-in resource [:data :rows])}
                 :planning (:data planning) :options options :open! set-dialog!
                 :time-editable? (and (shared/use-permission "pms:project:edit") (= "execution" (:status project)))
                 :time-approve? (and (shared/use-permission "pms:time:approve") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))}]
    ;; 弹窗在点击时固化计划读模型里的任务/节点选项; 首次加载时两类资源都返回后才渲染可操作内容 (与工程交付页一致).
    [:div [w/resource-view (shared/first-load resource planning) (fn [_] [time-section context])]
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                   :on-saved (fn [_] (set-dialog! nil) (changed!))})])]))

(defn finance-workspace
  "按财务读取权限隔离敏感费用信息."
  [project revision options changed!]
  (if (shared/use-permission "pms:finance:query")
    [finance-data project revision options changed!]
    [shared/empty-state "当前账号没有项目费用读取权限" nil]))
