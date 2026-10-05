(ns com.ruoyi.frontend.pages.pms.closure
  "结项检查,交付移交与独立关闭审批工作台."
  (:require [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.governance-forms :as forms]
            [com.ruoyi.frontend.pages.pms.approval :as chain]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]
            [reagent.hooks :as hooks]))>

(defn- evidence-field
  "结项证据必须选择一个已保存的不可变文档版本."
  [documents]
  {:key :evidence_ref :label "交付证据版本" :type :select :required? true
   :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / " (:title %) " V" (:revision %))) documents)})

(defn- blocker-panel
  "明确显示服务端尚未通过的结项条件."
  [model]
  [shared/panel "结项准入检查" "检查任务,质量Gate,问题,工时,决算与正式移交" nil
   (if (seq (:blockers model))
     [:ul {:style {:margin 0 :paddingLeft 20 :lineHeight 2 :color "#b76537"}}
      (for [blocker (:blockers model)] ^{:key blocker} [:li blocker])]
     [:div {:style {:color "#45846c"}} "当前结项前置检查已通过."])])

(defn- checklist
  "逐条检查交付完成情况并绑定证据版本."
  [{:keys [base model documents editable? open!]}]
  [shared/panel "收尾检查清单" "必需检查项完成后才能提交正式关闭审批"
   (when editable? [antd/button {:on-click #(open! {:title "添加收尾检查项" :path (str base "/checks")
                                                   :transform (fn [data] (assoc data :required true))
                                                   :fields [{:key :title :label "检查项" :required? true}]})} "添加检查项"])
   [w/record-table (:checks model) [(w/text-column :title "检查项") (w/state-column) (w/text-column :comment "完成说明")]
    (when editable? (fn [row]
                     (when-not (contains? #{"done" "completed"} (:status row))
                       [w/edit-button "确认完成" #(open! {:title "完成收尾检查" :path (str base "/checks/" (:id row) "/complete")
                                                        :fields [(evidence-field documents) {:key :comment :label "完成说明" :type :textarea :required? true}]})]))) ]])

(defn- handoffs
  "移交事项保留负责人,到期日和签收证据."
  [{:keys [base model options documents editable? open!]}]
  [shared/panel "交付移交" "每项移交均需实际证据确认完成"
   (when editable? [antd/button {:on-click #(open! {:title "新增交付移交" :path (str base "/handoffs")
                                                   :fields [{:key :title :label "移交事项" :required? true}
                                                            (forms/owner-field (:users options))
                                                            {:key :due_date :label "计划移交日期" :type :date :required? true}]})} "新增移交事项"])
   [w/record-table (:handoffs model) [(w/text-column :title "移交事项") (w/text-column :due_date "计划日期") (w/state-column)]
    (when editable? (fn [row]
                     (when (and (= (:owner_id row) (:currentUserId options)) (not (contains? #{"done" "completed"} (:status row))))
                       [w/edit-button "确认移交" #(open! {:title "确认交付移交" :path (str base "/handoffs/" (:id row) "/complete")
                                                        :fields [(evidence-field documents) {:key :comment :label "签收说明" :type :textarea :required? true}]})]))) ]])

(defn- lesson-stage-options
  "H15a 经验适用场景受控枚举选项 (与后端 closure/lesson-stages 白名单一致)."
  []
  (mapv #(hash-map :value % :label %)
        ["启动" "规划" "执行" "监控" "收尾" "质量" "交付" "成本" "风险" "采购" "干系人" "沟通"]))

(defn- lessons
  "保留有内容的经验复盘,服务下一次交付. H15a 可选标注适用场景 (受控枚举) 与跟进责任人 (须为有效项目成员)."
  [{:keys [base model options editable? open!]}]
  [shared/panel "经验复盘" "记录实际经验,原因和改进办法"
   (when editable? [antd/button {:on-click #(open! {:title "登记项目经验" :path (str base "/lessons")
                                                   :transform (fn [data]
                                                                (reduce (fn [m k] (let [v (get data k)]
                                                                                    (if (or (nil? v) (= "" v)) (dissoc m k) m)))
                                                                        data [:applicable_stage :owner_id]))
                                                   :fields [{:key :title :label "经验主题" :required? true}
                                                            {:key :category :label "经验类别" :required? true}
                                                            {:key :content :label "经验与改进建议" :type :textarea :required? true}
                                                            {:key :applicable_stage :label "适用场景 (可选)" :type :select
                                                             :options (lesson-stage-options)}
                                                            {:key :owner_id :label "跟进责任人 (可选)" :type :select
                                                             :options (w/user-options (:users options))}]})} "登记项目经验"])
   [w/record-table (:lessons model) [(w/text-column :title "主题") (w/text-column :category "类别")
                                     (w/text-column :applicable_stage "适用场景") (w/text-column :owner_name "责任人")
                                     (w/text-column :content "经验内容")] nil]])

(defn- approval
  "所有前置项通过后提交独立结项审批 (已发布 \"项目结项\" 审批策略时按策略逐级审批)."
  [{:keys [root base model options project editable? approve? open!]}]
  (let [record (:approval model) current (:currentUserId options)
        policies (chain/use-policies)
        {:keys [flows]} (chain/use-project-flows root "closure" (str (:approval_id record) (:status record)))
        chained? (contains? (chain/pending-ids flows) (:approval_id record))]
    [shared/panel "正式关闭审批" "批准后项目经理可执行关闭,审批本身不隐式改变生命周期" nil
     [w/badge (:status record)]
     (when (:review_note record) [:p (:review_note record)])
     [antd/space
      (when (and editable? (= "closing" (:status project)) (empty? (:blockers model))
                 (not (contains? #{"submitted" "approved"} (:status record))))
        [antd/button {:type "primary" :on-click #(open! {:title "提交项目关闭审批" :path (str base "/submit")
                                                        :fields (chain/reviewer-fields policies "closure" (forms/reviewer-field options))})} "提交关闭审批"])
      (when (and approve? (not chained?) (= "closing" (:status project)) (= "submitted" (:status record)) (= current (:reviewer_id record)) (not= current (:submitted_by record)))
        [:<>
         [antd/button {:type "primary" :on-click #(open! (forms/decision-dialog (str base "/review") "approved" "批准项目关闭"))} "批准关闭"]
         [antd/button {:danger true :on-click #(open! (forms/decision-dialog (str base "/review") "rejected" "驳回项目关闭"))} "驳回关闭"]])]
     (when (some #(= (:approval_id record) (:biz_id %)) flows)
       [chain/latest-flow-panel (filter #(= (:approval_id record) (:biz_id %)) flows) "关闭审批进度"])]))

(defn- reopen-panel
  "关闭后通过明确修正范围和独立决定受控重开."
  [{:keys [root model options project reopen? open!]}]
  (let [record (:reopen_request model) current (:currentUserId options)
        closed? (= "closed" (:status project))]
    (when (or closed? record)
      [shared/panel "受控重开" "批准后返回收尾,历史归档仍保留,再次关闭须重新审批" nil
       (when record [:div [w/badge (:status record)] [:p (str "申请原因: " (:reason record))]
                      [:p (str "修正范围: " (:scope record))] [:p (:review_note record)]])
       (when (and closed? reopen? (not= "submitted" (:status record)))
         [antd/button {:on-click #(open! {:title "申请受控重开" :path (str root "/reopen-requests")
                                          :fields [{:key :reason :label "重开原因" :type :textarea :required? true}
                                                   {:key :scope :label "修正范围" :type :textarea :required? true}
                                                   (forms/reviewer-field options)]})} "申请受控重开"])
       (when (and closed? reopen? (= "submitted" (:status record))
                  (= current (:reviewer_id record)) (not= current (:submitted_by record)))
         [antd/space
          [antd/button {:type "primary" :on-click #(open! (forms/decision-dialog
            (str root "/reopen-requests/" (:request_id record) "/review") "approved" "批准项目重开"))} "批准重开"]
          [antd/button {:danger true :on-click #(open! (forms/decision-dialog
            (str root "/reopen-requests/" (:request_id record) "/review") "rejected" "驳回项目重开"))} "驳回重开"]])])) )

(defn- progress-panel
  "E10 收尾清单闭环进度只读汇总: 聚合检查项/移交项完成进度, 必需未完成, 逾期未完成与关闭审批状态; 只读派生, 不门控任何写操作."
  [{:keys [model]}]
  (let [p (:progress model)
        kind-rows (:by-kind p)
        state (:approval-state p)
        state-label ({"none" "未提交" "submitted" "审批中" "approved" "已批准" "rejected" "已驳回"} state state)]
    [shared/panel "收尾闭环进度" "聚合检查项与移交项完成进度/必需未完成/逾期未完成与关闭审批状态; 只读派生, 不门控任何写操作 (结项准入仍由上方检查与独立审批决定)" nil
     (if-not (:available p)
       [shared/empty-state "尚无收尾事项, 添加检查项或移交事项后跟踪结项进度" nil]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "收尾项 " (:total p))]
         [antd/tag {:color "default"} (str "待完成 " (:open p))]
         [antd/tag {:color "green"} (str "已完成 " (:completed p))]
         [antd/tag {:color (if (pos? (or (:required-open p) 0)) "red" "default")} (str "必需未完成 " (:required-open p))]
         [antd/tag {:color (if (pos? (or (:overdue-open p) 0)) "red" "default")} (str "逾期未完成 " (:overdue-open p))]
         [antd/tag (str "经验 " (:lessons p))]
         [antd/tag {:color (case state "approved" "green" "submitted" "processing" "rejected" "red" "default")} (str "关闭审批 " state-label)]
         [antd/tag {:color (if (>= (:closure-pct p) 100) "green" "gold")} (str "收尾闭环率 " (:closure-pct p) "%")]]
        [antd/progress {:percent (:closure-pct p) :size "small" :style {:width "100%"}}]
        [antd/table {:rowKey "key" :size "small" :pagination false :dataSource (clj->js kind-rows)
                     :columns (clj->js [{:title "收尾类别" :dataIndex "label" :width 160}
                                        {:title "完成数" :dataIndex "completed" :width 90}
                                        {:title "总数" :dataIndex "total" :width 90}
                                        {:title "完成率" :key "bar" :width 220
                                         :render (fn [_ row] (let [c (aget row "completed-pct")]
                                                                (r/as-element [antd/progress {:percent c :size "small" :style {:width 180}}])))}])}]])]))


(defn- lesson-summary-panel
  "H15 经验教训类别分布与作者覆盖度只读汇总: 聚合经验分类覆盖, 分类分布与集中度, 参与人数; H15a 追加适用场景覆盖与跟进责任人落地度; 只读派生, 不门控经验登记."
  [{:keys [model]}]
  (let [ls (:lesson_summary model)
        rows (:by-category ls)
        stage-rows (:by-stage ls)
        total (:total ls)]
    [shared/panel "经验复盘分布" "聚合经验分类覆盖/分布与集中度/参与人数, 及适用场景覆盖与跟进责任人落地度; 只读派生, 不门控经验登记" nil
     (if-not (:available ls)
       [shared/empty-state "尚无项目经验, 登记后跟踪复盘分类分布与贡献覆盖" nil]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "经验总数 " total)]
         [antd/tag {:color "geekblue"} (str "分类覆盖 " (:distinct-categories ls) " 类")]
         [antd/tag {:color "green"} (str "参与人数 " (:author-count ls))]
         [antd/tag (str "最活跃作者 " (:top-author-count ls) " 条")]
         [antd/tag {:color (if (pos? (:distinct-stages ls 0)) "purple" "default")}
          (str "适用场景覆盖 " (:stages-declared ls 0) " 条 / " (:distinct-stages ls 0) " 类")]
         [antd/tag {:color (if (>= (:owner-coverage-pct ls 0) 60) "green" "gold")}
          (str "责任人落地 " (:owner-assigned ls 0) "/" total " (" (:owner-coverage-pct ls 0) "%)")]
         (when (:dominant-category ls)
           [antd/tag {:color (if (>= (:concentration-pct ls) 60) "gold" "cyan")}
            (str "主导类别 " (:dominant-category ls) " " (:dominant-count ls) " 条")])
         [antd/tag {:color (if (>= (:concentration-pct ls) 60) "gold" "default")}
          (str "分类集中度 " (:concentration-pct ls) "%")]]
        [antd/table {:rowKey "category" :size "small" :pagination false :dataSource (clj->js rows)
                     :columns (clj->js [{:title "经验类别" :dataIndex "category" :width 200}
                                        {:title "条数" :dataIndex "count" :width 90}
                                        {:title "占比" :key "pct" :width 240
                                         :render (fn [_ row] (let [cnt (aget row "count")
                                                                    pct (if (pos? total) (int (Math/round ^double (* 100.0 (/ cnt total)))) 0)]
                                                                (r/as-element [antd/progress {:percent pct :size "small" :style {:width 180}}])))}])}]
        (when (seq stage-rows)
          [antd/table {:rowKey "stage" :size "small" :pagination false :dataSource (clj->js stage-rows)
                       :columns (clj->js [{:title "适用场景" :dataIndex "stage" :width 200}
                                          {:title "条数" :dataIndex "count" :width 90}
                                          {:title "占比" :key "pct" :width 240
                                           :render (fn [_ row] (let [cnt (aget row "count")
                                                                      pct (if (pos? total) (int (Math/round ^double (* 100.0 (/ cnt total)))) 0)]
                                                                  (r/as-element [antd/progress {:percent pct :size "small" :style {:width 180}}])))}])}])])]))


(defn- closure-content
  "按检查,移交,复盘与正式审批组织结项."
  [context]
  [:div {:style {:display "grid" :gap 20}}
   [blocker-panel (:model context)] [progress-panel context] [checklist context] [handoffs context]
   [lessons context] [lesson-summary-panel context] [approval context] [reopen-panel context]])

(defn closure-workspace
  "基于真实读模型维护结项证据与关闭决策."
  [project revision options changed!]
  (let [root (str "/projects/" (:project_id project)) base (str root "/closure")
        resource (shared/use-resource base {} [revision])
        governance (shared/use-resource (str root "/governance") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil)
        context {:root root :base base :model (:data resource) :documents (get-in governance [:data :documents])
                 :project project :options options :open! set-dialog!
                 :editable? (and (shared/use-permission "pms:project:edit") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
                 :reopen? (shared/use-permission "pms:project:reopen")
                 :approve? (shared/use-permission "pms:project:close")}]
    [:div
     [w/resource-view resource (fn [_] [closure-content context])]
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                   :on-saved (fn [_] (set-dialog! nil) (changed!))})])]))
