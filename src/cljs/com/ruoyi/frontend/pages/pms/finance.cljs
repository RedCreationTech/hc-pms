(ns com.ruoyi.frontend.pages.pms.finance
  "项目工时,成本版本与按实际工时分摊工作台."
  (:require [clojure.string :as str]
            [com.ruoyi.frontend.antd :as antd]
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
(def commitment-kinds [{:value "contract" :label "合同"} {:value "purchase" :label "采购"}
                       {:value "labor" :label "人工"} {:value "other" :label "其他"}])
(def currencies ["CNY" "USD" "EUR" "GBP" "HKD"])
(def budget-baselines [{:value "estimate" :label "概算"} {:value "budget" :label "预算"}])
(def budget-action-options [{:value "warn" :label "提醒"} {:value "require_approval" :label "需上级审批"}
                            {:value "block" :label "阻断"}])

(defn- cost-dialog
  "新建固定期间与币种的独立成本版本."
  [base options]
  {:title "新建成本版本" :path (str base "/cost-versions") :initial {:kind "budget" :currency "CNY" :revenue "0.00"}
   :fields [{:key :name :label "成本版本名称" :required? true}
            {:key :kind :label "成本阶段" :type :select :options kinds :required? true}
            {:key :period :label "核算期间" :required? true :hint "YYYY-MM,例如2026-09"}
            {:key :currency :label "币种" :type :select :options (mapv #(hash-map :value % :label %) currencies) :required? true}
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

(defn- commitment-dialog
  "H12 登记承诺草稿, 支持本位币种和汇率折算; H13c 可指定会计期间."
  [base options]
  {:title "登记承诺" :path (str base "/commitments") :initial {:kind "purchase" :currency "CNY" :base_currency "CNY" :exchange_rate "1"}
   :transform #(if (empty? (str/trim (or (:period %) ""))) (dissoc % :period) (update % :period str/trim))
   :description "承诺=已下达但尚未验收/结算的义务. 外币需填写折算汇率, 本位金额=总额×汇率, 保留两位小数. 会计期间留空默认当前月."
   :fields [{:key :code :label "承诺编号" :required? true :hint "同一项目内不可重复"}
            {:key :period :label "会计期间" :hint "YYYY-MM, 留空默认当前月; 已封账期间不可登记"}
            {:key :kind :label "承诺类别" :type :select :options commitment-kinds :required? true}
            {:key :supplier :label "供应商/承包方" :required? true}
            {:key :currency :label "结算币种" :type :select :options (mapv #(hash-map :value % :label %) currencies) :required? true}
            {:key :gross :label "结算总额" :required? true :hint "精确到小数点后两位."}
            {:key :base_currency :label "本位币种" :type :select :options (mapv #(hash-map :value % :label %) currencies) :required? true}
            {:key :exchange_rate :label "折算汇率" :required? true :hint "结算币种→本位币种, 例如 7.1234"}
            {:key :description :label "备注" :type :textarea}
            (forms/reviewer-field options)]})

(defn- commitment-edit-dialog
  "H12 草稿可修改, 提交后禁止; H13c 可调整会计期间."
  [base options commitment]
  {:title "修改承诺草稿" :method :put :path (str base "/commitments/" (:id commitment))
   :initial (select-keys commitment [:period :kind :supplier :currency :gross :base_currency :exchange_rate :description])
   :transform #(if (empty? (str/trim (or (:period %) ""))) (dissoc % :period) (update % :period str/trim))
   :fields [{:key :period :label "会计期间" :hint "YYYY-MM; 留空保持原期间, 原期间或新期间已封账均不可变更"}
            {:key :kind :label "承诺类别" :type :select :options commitment-kinds :required? true}
            {:key :supplier :label "供应商/承包方" :required? true}
            {:key :currency :label "结算币种" :type :select :options (mapv #(hash-map :value % :label %) currencies) :required? true}
            {:key :gross :label "结算总额" :required? true}
            {:key :base_currency :label "本位币种" :type :select :options (mapv #(hash-map :value % :label %) currencies) :required? true}
            {:key :exchange_rate :label "折算汇率" :required? true}
            {:key :description :label "备注" :type :textarea}
            (forms/reviewer-field options)]})

(defn- commitment-submit-dialog
  "H12 提交承诺触发预算占用评估; 常规提交不携带 override_block."
  [base commitment]
  {:title "提交承诺" :path (str base "/commitments/" (:id commitment) "/submit")
   :description (str "将按所选基线评估预算占用率. 上次评估: " (or (:control_note commitment) "首次提交"))
   :initial {:baseline "budget"}
   :fields [{:key :baseline :label "评估基线" :type :select :options budget-baselines :required? true}
            {:key :reason :label "备注" :type :textarea}]})

(defn- commitment-override-dialog
  "H12 阻断时携带 override_block=true 强制放行, 记录审批理由."
  [base commitment]
  {:title "强制放行提交" :path (str base "/commitments/" (:id commitment) "/submit")
   :description "预算占用已触发阻断规则. 强制放行会写入审计, 请说明理由并确认权限."
   :initial {:baseline "budget"}
   :transform #(assoc % :override_block true)
   :fields [{:key :baseline :label "评估基线" :type :select :options budget-baselines :required? true}
            {:key :reason :label "放行理由" :type :textarea :required? true}]})

(defn- commitment-release-dialog
  "H12 已批准承诺可按剩余转实付."
  [base commitment]
  {:title "转实付" :path (str base "/commitments/" (:id commitment) "/release")
   :description (str "剩余可释放: " (or (:remaining commitment) "0.00") ", 全额释放后自动进入已释放状态.")
   :fields [{:key :amount :label "本次释放金额" :required? true :hint "本位币种口径, 精确到小数点后两位."}]})

(defn- commitment-cancel-dialog
  "H12 只有草稿或被驳回可取消."
  [base commitment]
  {:title "取消承诺" :path (str base "/commitments/" (:id commitment) "/cancel")
   :description "已批准或已释放的承诺不允许直接取消."
   :fields [{:key :reason :label "取消原因" :type :textarea :required? true}]})

(defn- commitment-review-dialog
  "H12 独立审批承诺."
  [base commitment decision]
  {:title (if (= decision "approved") "批准承诺" "驳回承诺")
   :path (str base "/commitments/" (:id commitment) "/review")
   :transform #(assoc % :decision decision)
   :fields [{:key :reason :label (if (= decision "rejected") "驳回原因" "审批意见") :type :textarea :required? (= decision "rejected")}]})

(defn- budget-rule-dialog
  "H12 新增项目层预算控制规则."
  [base]
  {:title "新增项目预算控制规则" :path (str base "/budget-rules")
   :initial {:baseline "budget" :action "warn" :threshold_pct 90}
   :description "项目层规则只影响本项目; 阈值=预算占用率百分比, 达到即触发. 触发阻断时提交需要显式强制放行."
   :transform #(assoc % :enabled true :project_scoped true)
   :fields [{:key :baseline :label "评估基线" :type :select :options budget-baselines :required? true}
            {:key :threshold_pct :label "阈值(%)" :type :number :min 5 :max 500 :required? true}
            {:key :action :label "动作" :type :select :options budget-action-options :required? true}
            {:key :note :label "备注" :type :textarea :required? true}]})

(defn- budget-rule-disable-dialog
  "H12 停用预算控制规则, 保留历史."
  [base rule]
  {:title "停用规则" :path (str base "/budget-rules/" (:id rule) "/disable")
   :description (str "基线=" (:baseline rule) ", 阈值=" (:threshold_pct rule) "%, 动作=" (:action rule))
   :fields [{:key :reason :label "停用原因" :type :textarea :required? true}]})

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

(defn- timesheet-review-section
  "F04 工时审核闭环健康度: 按项目全部工时单只读派生, 汇总待审核/已批准/已驳回/已更正/更正待审与审核完成率及工时小时分布."
  [{:keys [model]}]
  (let [tr (:timesheet_review model)]
    [shared/panel "工时审核闭环汇总" "只读派生 · 反映工时单独立审批的推进情况, 不构成任何门控" nil
     (if-not (:available tr)
       [:span {:style {:color "#98a2b3"}} "尚无人提交工时单, 提交后此处自动汇总审核闭环健康度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "待审核 " (:submitted tr))]
         [antd/tag {:color "green"} (str "已批准 " (:approved tr))]
         [antd/tag {:color "red"} (str "已驳回 " (:rejected tr))]
         [antd/tag {:color "default"} (str "已更正 " (:corrected tr))]
         (when (pos? (:correction-pending tr)) [antd/tag {:color "orange"} (str "更正待审 " (:correction-pending tr))])]
        [:div {:style {:fontSize 13 :color "#4b5563"}}
         (str "共 " (:total tr) " 张工时单, 已作决定 " (:processed tr) " 张, 审核完成率 " (:review-pct tr) "%")]
        [:div {:style {:fontSize 12 :color "#718096"}}
         (str "工时: 合计 " (:hours-total tr) "h / 已批准 " (:hours-approved tr) "h / 待审核 " (:hours-pending tr) "h")]])]))

(defn- cost-review-section
  "F06 四算版本审批闭环健康度: 按项目全部费用版本只读派生, 汇总草稿/审批中/已批准/已驳回/已取消与审批完成率及概算-预算-核算-决算各口径分布."
  [{:keys [model]}]
  (let [cr (:cost_review model)
        kind-labels {"estimate" "概算" "budget" "预算" "actual" "核算" "settlement" "决算"}]
    [shared/panel "四算版本审批闭环汇总" "只读派生 · 反映概算/预算/核算/决算版本独立审批的推进情况, 不构成任何门控" nil
     (if-not (:available cr)
       [:span {:style {:color "#98a2b3"}} "尚无四算费用版本, 建立后此处自动汇总审批闭环健康度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "geekblue"} (str "版本总数 " (:total cr))]
         [antd/tag {:color "green"} (str "已批准 " (:approved cr))]
         [antd/tag {:color "blue"} (str "审批中 " (:submitted cr))]
         [antd/tag {:color "default"} (str "草稿 " (:draft cr))]
         [antd/tag {:color "red"} (str "已驳回 " (:rejected cr))]
         (when (pos? (:cancelled cr)) [antd/tag {:color "orange"} (str "已取消 " (:cancelled cr))])]
        [:div {:style {:fontSize 13 :color "#4b5563"}}
         (str "共 " (:total cr) " 个四算版本, 已作决定 " (:processed cr) " 个, 待处理 " (:pending cr) " 个, 审批完成率 " (:review-pct cr) "%")]
        [antd/space {:wrap true}
         (for [bk (:by-kind cr) :when (pos? (:total bk))] ^{:key (:kind bk)}
           [antd/tag {} (str (get kind-labels (:kind bk) (:kind bk)) " · 总" (:total bk) " 批准" (:approved bk) " 待" (:pending bk))])]])]))

(defn- allocation-review-section
  "F05 研发费用分摊闭环健康度: 按项目全部分摊批次只读派生, 汇总冻结费用池金额, 实际摊出金额, 生成成本条目数, 覆盖任务数与舍入零头, 不构成门控."
  [{:keys [model]}]
  (let [ar (:allocation_review model)]
    [shared/panel "研发费用分摊闭环汇总" "只读派生 · 反映费用池按批准工时分摊的固化情况与总额守恒, 不构成任何门控" nil
     (if-not (:available ar)
       [:span {:style {:color "#98a2b3"}} "尚无费用分摊批次, 执行分摊后此处自动汇总闭环健康度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "geekblue"} (str "分摊批次 " (:total ar))]
         [antd/tag {:color "purple"} (str "覆盖任务 " (:task-count ar))]
         [antd/tag {:color "green"} (str "生成成本条目 " (:entry-count ar))]
         (when (pos? (:zero-task-count ar)) [antd/tag {:color "orange"} (str "舍入未摊任务 " (:zero-task-count ar))])
         [antd/tag {:color (if (:conserved ar) "green" "red")} (if (:conserved ar) "总额守恒" "总额异常")]]
        [:div {:style {:fontSize 13 :color "#4b5563"}}
         (str "冻结费用池合计 " (:pool-amount ar) " 元, 实际摊出 " (:allocated-amount ar) " 元, 生成 " (:entry-count ar) " 条人工成本, 覆盖 " (:task-count ar) " 个任务"
              (when (pos? (:zero-task-count ar)) (str ", 其中 " (:zero-task-count ar) " 个任务因工时占比过小舍入为 0 未生成条目")))] ])]))

(defn- cost-margin-section
  "F08 成本毛利看板: 概算-预算-核算-决算各口径取最新已批准版本, 只读派生收入, 成本, 毛利与毛利率, 零或负收入标注不可算, 不构成门控."
  [{:keys [model]}]
  (let [cm (:cost_margin model)
        kind-labels {"estimate" "概算" "budget" "预算" "actual" "核算" "settlement" "决算"}
        row (fn [bk]
              (let [label (get kind-labels (:kind bk) (:kind bk))]
                (if-not (:present bk)
                  ^{:key (:kind bk)}
                  [:div {:style {:fontSize 13 :color "#98a2b3"}} (str label " · 尚无已批准版本")]
                  ^{:key (:kind bk)}
                  [:div {:style {:fontSize 13 :color "#4b5563"}}
                   (str label " (v" (:version_no bk) ") 收入 " (:revenue bk) " 成本 " (:cost bk) " 毛利 " (:margin bk) " ")
                   (if (:computable bk)
                     [antd/tag {:color (if (neg? (or (:margin-pct bk) 0)) "red" "green")}
                      (str "毛利率 " (:margin-pct bk) "%")]
                     [antd/tag {:color "orange"} "不可算 (零或负收入)"])])))]
    [shared/panel "成本毛利看板" "只读派生 · 各口径最新已批准版本的收入, 成本, 毛利与毛利率, 不构成任何门控" nil
     (if-not (:available cm)
       [:span {:style {:color "#98a2b3"}} "尚无已批准的四算版本, 独立批准后此处自动汇总各口径毛利."]
       (into [:div {:style {:display "grid" :gap 12}}]
             (cons (when-not (:comparable cm)
                     [antd/tag {:color "red"} "各口径币种不一致, 毛利率不可横向比较"])
                   (map row (:by-kind cm)))))]))
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
  (let [locked (set (:locked_periods model))]
    [shared/panel "成本版本台账" "概算 / 预算 / 核算 / 决算"
     (when (seq locked)
       [antd/alert {:type "warning" :show-icon true :style #js {:marginBottom 12}
                    :message (str "已封账会计期间: " (str/join " " (sort locked)) " - 该期间的费用版本不可新建或变更, 需先在平台配置解锁")
                    :description "封期后该期间的成本版本提交,增删明细,修订与取消均被拒绝; 已提交版本的独立审批不受影响"}])
     (when editable? [antd/button {:type "primary" :on-click #(open! (cost-dialog base options))} "新建成本版本"])
     [w/record-table (:cost_versions model)
      [(w/text-column :name "版本名称") {:title "阶段" :dataIndex "kind" :render #(or (:label (some (fn [x] (when (= % (:value x)) x)) kinds)) %)}
       {:title "期间" :dataIndex "period" :render (fn [period] (if (locked period) (r/as-element [:span period " " [antd/tag {:color "red"} "已封账"]]) period))}
       (w/text-column :currency "币种") (w/text-column :total "成本金额")
       (w/text-column :revenue "收入") (w/state-column)] #(cost-actions context %) ]]))

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

(defn- commitment-status-tag
  [status]
  (r/as-element [antd/tag {:color (case status
                                    "draft" "default"
                                    "submitted" "blue"
                                    "approved" "green"
                                    "rejected" "red"
                                    "released" "purple"
                                    "cancelled" "default"
                                    "blue")}
                 (get {"draft" "草稿" "submitted" "待审批" "approved" "已批准"
                       "rejected" "已驳回" "released" "已释放" "cancelled" "已取消"} status status)]))

(defn- commitment-actions
  "按承诺状态提供草稿修改/提交/审批/释放/取消入口."
  [{:keys [base options editable? approve? open!]} commitment]
  (let [status (:status commitment)]
    [antd/space {:wrap true}
     (when (and editable? (= "draft" status))
       [:<>
        [w/edit-button "修改" #(open! (commitment-edit-dialog base options commitment))]
        [w/edit-button "提交" #(open! (commitment-submit-dialog base commitment))]
        [w/edit-button "强制放行" #(open! (commitment-override-dialog base commitment))]
        [w/edit-button "取消" #(open! (commitment-cancel-dialog base commitment))]])
     (when (and editable? (= "rejected" status))
       [w/edit-button "取消" #(open! (commitment-cancel-dialog base commitment))])
     (when (and approve? (= "submitted" status) (reviewer? options commitment))
       [:<>
        [w/edit-button "批准" #(open! (commitment-review-dialog base commitment "approved"))]
        [w/edit-button "驳回" #(open! (commitment-review-dialog base commitment "rejected"))]])
     (when (and editable? (= "approved" status))
       [w/edit-button "转实付" #(open! (commitment-release-dialog base commitment))])]))

(defn- commitment-section
  "H12 承诺台账: 已下达但未验收/结算的义务, 独立审批+预算占用评估; H13c 纳入会计期间封期."
  [{:keys [base model options editable? open!] :as context}]
  (let [locked (set (:locked_periods model))]
    [shared/panel "承诺台账" "合同/采购/人工/其他承诺, 提交时按预算占用评估, 支持部分转实付"
     (when (seq locked)
       [antd/alert {:type "warning" :show-icon true :style #js {:marginBottom 12}
                    :message (str "已封账会计期间: " (str/join " " (sort locked)) " - 该期间的承诺不可登记或变更, 需先在平台配置解锁")
                    :description "封期后该期间的承诺登记,草稿修改,提交,转实付与取消均被拒绝; 已提交承诺的独立审批不受影响"}])
     (when editable? [antd/button {:type "primary" :on-click #(open! (commitment-dialog base options))} "登记承诺"])
     [w/record-table (:commitments model)
      [(w/text-column :code "承诺编号")
       {:title "期间" :dataIndex "period" :width 110 :render (fn [period] (if (locked period) (r/as-element [:span (or period "—") " " [antd/tag {:color "red"} "已封账"]]) (or period "—")))}
       {:title "类别" :dataIndex "kind" :render #(or (:label (some (fn [x] (when (= % (:value x)) x)) commitment-kinds)) %)}
       (w/text-column :supplier "供应商/承包方")
       (w/text-column :gross "结算总额") (w/text-column :currency "币种")
       (w/text-column :base "本位金额") (w/text-column :released "已释放")
       (w/text-column :remaining "剩余")
       {:title "预算评估" :dataIndex "control_note" :width 220 :render (fn [v] (r/as-element [:span {:style {:fontSize 12 :color "#718096"}} (or v "—")]))}
       {:title "状态" :dataIndex "status" :width 100 :render commitment-status-tag}]
      (fn [row] (commitment-actions context row))]]))

(defn- format-minor
  [v]
  (if (nil? v) "—" (.toFixed (/ (double v) 100.0) 2)))

(defn- budget-control-panel
  "H12 预算占用评估: 显示基线/占用率/触发规则/决策."
  [{:keys [model]}]
  (let [control (get-in model [:budget_control :budget])]
    [shared/panel "预算占用评估" "预算基线口径下的已承诺占用率与最近一次提交的门控决定" nil
     (if-not (:comparable control)
       [:span {:style {:color "#98a2b3"}} "尚无已批准预算基线, 无法评估占用率."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "已批准预算 " (or (:currency control) "") " " (format-minor (get-in control [:budget :total_minor])))]
         [antd/tag (str "已承诺 " (format-minor (:consumed_minor control)))]
         [antd/tag {:color (if (and (:remaining_minor control) (neg? (:remaining_minor control))) "red" "default")}
                    (str "剩余 " (format-minor (:remaining_minor control)))]
         [antd/tag {:color (case (:decision control) "block" "red" "require_approval" "orange" "warn" "gold" "green")}
                    (str "占用率 " (or (:ratio_pct control) 0) "% · "
                         (get {"ok" "未触发" "warn" "触发提醒" "require_approval" "需上级审批" "block" "触发阻断"} (:decision control) ""))]]
        (when (seq (:triggered control))
          [antd/space {:wrap true}
           (for [t (:triggered control)] ^{:key (:rule_id t)}
             [antd/tag {:color (get {"warn" "gold" "require_approval" "orange" "block" "red"} (:action t) "blue")}
                        (str (:action t) " @ " (:threshold_pct t) "%")])])])]))

(defn- budget-rules-section
  "H12 预算控制规则: 系统默认与项目层叠加, 项目层可覆盖."
  [{:keys [base model editable? open!]}]
  [shared/panel "预算控制规则" "系统默认与项目层规则共同生效, 阈值命中即触发"
   (when editable? [antd/button {:on-click #(open! (budget-rule-dialog base))} "新增项目规则"])
   [w/record-table (:budget_rules model)
    [{:title "作用域" :dataIndex "project_id" :width 90
      :render (fn [v] (r/as-element [antd/tag {:color (if v "green" "blue")} (if v "项目层" "系统默认")]))}
     (w/text-column :baseline "基线")
     (w/text-column :threshold_pct "阈值(%)")
     {:title "动作" :dataIndex "action" :render (fn [v] (r/as-element [antd/tag {:color (get {"warn" "gold" "require_approval" "orange" "block" "red"} v "default")} v]))}
     {:title "启用" :dataIndex "enabled" :width 80
      :render (fn [v] (r/as-element [antd/tag {:color (if v "green" "default")} (if v "已启用" "已停用")]))}
     (w/text-column :note "备注")]
    (fn [rule]
      (when (and editable? (:enabled rule))
        [w/edit-button "停用" #(open! (budget-rule-disable-dialog base rule))]))]])

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
                                                [cost-review-section context]
                                                [cost-margin-section context]
                                                [four-count-section context]
                                                [cost-section context] [ledger-section context selected] [allocation-review-section context] [allocation-history (:model context)]])}
                       {:key "commitments" :label "承诺与预算控制"
                        :children (r/as-element [:div {:style {:display "grid" :gap 20}}
                                                 [budget-control-panel context]
                                                 [commitment-section context]
                                                 [budget-rules-section context]])}
                       {:key "time" :label "实际工时" :children (r/as-element [:div {:style {:display "grid" :gap 20}} [timesheet-review-section context] [time-section context]])}]}]])

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
