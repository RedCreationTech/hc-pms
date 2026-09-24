(ns com.ruoyi.frontend.pages.pms.planning
  "项目计划,资源与独立基线审批工作台."
  (:require [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.approval :as approval]
            [com.ruoyi.frontend.pages.pms.plan-forms :as forms]
            [com.ruoyi.frontend.pages.pms.plan-views :as views]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]
            [reagent.hooks :as hooks]))

(defn- remove-dialog
  "确认删除计划关联而不静默改变计划."
  [path title]
  {:title title :path path :method :delete :fields [] :description "确认删除这条计划记录? 此操作将记录在项目审计轨迹中."})

(defn- task-section
  "维护 WBS 分解与执行阶段进度反馈."
  [{:keys [base model options editable? can-feedback? project open!]}]
  [shared/panel "WBS 工作分解" "汇总任务组织层级,任务和里程碑参与排程"
   (when editable? [antd/button {:type "primary" :on-click #(open! (forms/task-dialog base model options nil))} "新建WBS任务"])
   [w/record-table (:tasks model)
    [(w/text-column :wbs_code "WBS编号") (w/text-column :name "任务名称")
     {:title "类型" :dataIndex "task_type" :render #(get w/labels % %)}
     {:title "节点" :dataIndex "node_id" :width 130 :render #(w/related-label (:nodes model) :node_id :node_code %)}
     {:title "阶段" :dataIndex "stage_code" :width 110 :render shared/display-value}
     (w/text-column :owner_name "责任人") (w/text-column :duration_days "工作日")
     (w/state-column) (w/text-column :percent_complete "进度%")
     {:title "实际开始/完成" :key "actual" :width 190
      :render (fn [_ row] (let [a (aget row "actual_start") b (aget row "actual_end")] (if (or a b) (str (or a "—") " / " (or b "—")) "—")))}
     {:title "来源" :dataIndex "source_type" :width 90 :render #(get {"derived" "模板派生" "template" "模板容器" "meeting_action" "会议行动"} % (or % "手工"))}]
    (fn [task]
      [antd/space
       (when editable? [w/edit-button "编辑" #(open! (forms/task-dialog base model options task))])
       (when editable? [w/edit-button "删除" #(open! (remove-dialog (str base "/tasks/" (:task_id task)) "删除WBS任务"))])
       (when (and can-feedback? (= "execution" (:status project)) (not= "summary" (:task_type task)))
         [w/edit-button "反馈进度" #(open! (forms/feedback-dialog base task))])])]])

(defn- dependency-section
  "展示四类任务依赖与工作日间隔."
  [{:keys [base model editable? open!]}]
  [shared/panel "任务依赖" "支持 FS / SS / FF / SF,循环关系由服务器拒绝"
   (when editable? [antd/button {:on-click #(open! (forms/dependency-dialog base model))} "添加任务依赖"])
   [w/record-table (:dependencies model)
    [{:title "前置任务" :dataIndex "predecessor_id" :render #(w/related-label (:tasks model) :task_id :name %)}
     {:title "后续任务" :dataIndex "successor_id" :render #(w/related-label (:tasks model) :task_id :name %)}
     (w/text-column :dependency_type "依赖关系") (w/text-column :lag_days "间隔工作日")]
    (when editable? (fn [row] [w/edit-button "移除" #(open! (remove-dialog (str base "/dependencies/" (:dependency_id row)) "移除任务依赖"))]))]])

(defn- resource-section
  "维护资源容量,日历例外与每日任务分配."
  [{:keys [base model options editable? open!]}]
  [shared/panel "项目资源" "人员和设备共用可用容量规则"
   (when editable? [antd/button {:on-click #(open! (forms/resource-dialog base options nil))} "新增项目资源"])
   [w/record-table (:resources model)
    [(w/text-column :name "资源名称") {:title "类型" :dataIndex "resource_type" :render #(get w/labels % %)}
     (w/text-column :daily_capacity "每日可用工时")]
    (when editable?
      (fn [resource]
        [antd/space
         [w/edit-button "编辑" #(open! (forms/resource-dialog base options resource))]
         [w/edit-button "日历例外" #(open! (forms/capacity-dialog base resource))]
         [w/edit-button "删除" #(open! (remove-dialog (str base "/resources/" (:resource_id resource)) "删除项目资源"))]]))]])

(defn- allocation-section
  "展示资源任务分配并允许移除关联."
  [{:keys [base model editable? open!]}]
  [shared/panel "任务资源分配" "任务每日计划负荷用于资源超载计算"
   (when editable? [antd/button {:on-click #(open! (forms/allocation-dialog base model))} "分配任务资源"])
   [w/record-table (:allocations model)
    [{:title "任务" :dataIndex "task_id" :render #(w/related-label (:tasks model) :task_id :name %)}
     {:title "资源" :dataIndex "resource_id" :render #(w/related-label (:resources model) :resource_id :name %)}
     (w/text-column :hours_per_day "每日计划工时")]
    (when editable? (fn [row] [w/edit-button "移除" #(open! (remove-dialog (str base "/allocations/" (:allocation_id row)) "移除资源分配"))]))]])

(defn- calendar-section
  "显示项目工作日历以及容量例外."
  [{:keys [base model editable? open!]}]
  (let [calendar (:calendar model)]
    [shared/panel "项目工作日历" "CPM 计算跳过休息日,优先应用调休工作日"
     (when editable? [antd/button {:on-click #(open! (forms/calendar-dialog base calendar))} "编辑工作日历"])
     [:div {:style {:display "flex" :gap 12 :flexWrap "wrap" :marginBottom 16}}
      [antd/tag (str "每周工作日: " (clojure.string/join ", " (:working_days calendar))) ]
      [antd/tag (str "每日 " (:hours_per_day calendar) " 小时")]
      [antd/tag (str "休息日 " (count (:holidays calendar)) " 天")]
      [antd/tag (str "调休 " (count (:extra_workdays calendar)) " 天")]]
     [w/record-table (:capacities model)
      [{:title "资源" :dataIndex "resource_id" :render #(w/related-label (:resources model) :resource_id :name %)}
       (w/text-column :date "例外日期") (w/text-column :capacity_hours "当日可用工时")] nil]]))

(defn- submit-dialog
  "执行期基线修订必须关联已独立批准的变更."
  [base model project]
  {:title "提交计划审批" :path (str base "/planning/submit")
   :fields (cond-> [{:key :comment :label "提交说明" :type :textarea}]
             (= "execution" (:status project))
             (conj {:key :change_id :label "已批准变更" :type :select :required? true
                    :options (w/options (:approved_changes model) :id :title)
                    :hint "执行期调整须先完成独立变更评审,再形成新计划基线."}))})

(defn- baseline-section
  "展示不可变基线并要求提交者以外的审批人决策 (已发布 \"计划基线\" 审批策略时按策略逐级审批)."
  [{:keys [base model project editable? can-approve? open! compare!]}]
  (let [{:keys [flows]} (approval/use-project-flows (str "/projects/" (:project_id project)) "plan-baseline"
                                                    (hash (map (juxt :baseline_id :status) (:baselines model))))
        chained (approval/pending-ids flows)]
  [shared/panel "计划提交与基线" "提交时冻结当前计划,独立审批后成为正式基线"
   (when (and editable? (contains? #{"planning" "execution"} (:status project)))
     [antd/button {:type "primary" :on-click #(open! (submit-dialog base model project))} "提交计划审批"])
   [w/record-table (:baselines model)
    [(w/text-column :plan_revision "计划修订") (w/state-column) (w/text-column :submitted_name "提交人")
     (w/text-column :submitted_at "提交时间") (w/text-column :reviewed_name "审批人") (w/text-column :review_comment "审批意见")]
    (fn [baseline]
      [antd/space
       [w/edit-button "查看差异" #(compare! baseline)]
       (when (and can-approve? (= "submitted" (:status baseline)) (not (contains? chained (:baseline_id baseline)))
                  (not= (:current_user_id model) (:submitted_by baseline)))
         [:<>
          [w/edit-button "批准" #(open! (forms/approval-dialog base baseline "approved"))]
          [w/edit-button "驳回" #(open! (forms/approval-dialog base baseline "rejected"))]])])]
   [approval/latest-flow-panel flows "计划基线审批进度"]]))

(defn- feedback-section
  "保留每次实际执行反馈及责任人."
  [{:keys [model]}]
  [shared/panel "执行反馈记录" "工时单在费用页签单独申报和审批" nil
   [w/record-table (:feedback model)
    [{:title "任务" :dataIndex "task_id" :render #(w/related-label (:tasks model) :task_id :name %)}
     (w/text-column :user_name "反馈人") (w/state-column) (w/text-column :percent_complete "进度%")
     (w/text-column :remaining_days "剩余天数") (w/text-column :actual_start "实际开始") (w/text-column :actual_end "实际完成")
     (w/text-column :comment "说明") (w/text-column :created_at "时间")] nil]])

(defn- rollup-tab
  "进度卷积 + 挣值预测 + 主子冲突 + 趋势快照 + 重排记录 (增量6)."
  [{:keys [base model editable? can-feedback? project open!]}]
  (let [actions [antd/space {:wrap true}
                 (when (and editable? (seq (:stages model)))
                   [antd/button {:size "small" :on-click #(open! (forms/derive-dialog base))} "派生主/子/单机计划"])
                 (when (and editable? (seq (:stages model)))
                   [antd/button {:size "small" :on-click #(open! (forms/stage-weights-dialog base (:stages model)))} "覆盖阶段权重"])
                 (when (and can-feedback? (= "execution" (:status project)))
                   [antd/button {:size "small" :on-click #(open! (forms/snapshot-dialog base))} "生成进度快照"])]
        node-action (when editable?
                      (fn [node] (when (not= "main" (:node_type node))
                                   [w/edit-button "重排" #(open! (forms/reschedule-dialog base node))])))]
    [:div {:style {:display "grid" :gap 20}}
     [views/progress-rollup model actions node-action]
     [views/conflicts-panel model]
     [views/earned-value-panel model]
     [views/history-panel model]
     [views/reschedules-panel model]]))


(defn- planning-content
  "按计划编制,资源,基线和反馈组织工作台."
  [context]
  (let [model (:model context)]
    [:div
     [:div {:style {:display "flex" :gap 12 :alignItems "center" :marginBottom 16}}
      [:strong (str "计划修订 " (:plan_revision model))] [w/badge (:plan_status model)]
      (when (= "submitted" (:plan_status model)) [:span {:style {:color "#718096"}} "计划已冻结,等待独立审批."])
      (when (seq (:plan_conflicts model)) [antd/tag {:color "red"} (str "主子约束冲突 " (count (:plan_conflicts model)))])
      (when-let [spi (get-in model [:earned_value :spi])] [antd/tag {:color (if (< spi 0.9) "red" "blue")} (str "SPI " spi)])]
     [antd/tabs {:items
                  [{:key "wbs" :label "WBS与排程" :children (r/as-element [:div {:style {:display "grid" :gap 20}}
                                                                          [task-section context] [dependency-section context] [views/gantt model]])}
                   {:key "resources" :label "资源与日历" :children (r/as-element [:div {:style {:display "grid" :gap 20}}
                                                                                [resource-section context] [allocation-section context]
                                                                                [calendar-section context] [views/overloads model]])}
                   {:key "rollup" :label "进度卷积" :children (r/as-element [rollup-tab context])}
                   {:key "baselines" :label "审批与基线" :children (r/as-element [baseline-section context])}
                   {:key "feedback" :label "执行反馈" :children (r/as-element [feedback-section context])}]}]]))

(defn planning-workspace
  "加载计划读模型并统一携带当前项目版本写入."
  [project revision options changed!]
  (let [base (str "/projects/" (:project_id project))
        resource (shared/use-resource (str base "/planning") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil)
        [comparing set-comparing!] (hooks/use-state nil)
        can-edit? (shared/use-permission "pms:project:edit")
        can-approve? (and (shared/use-permission "pms:plan:approve") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
        editable? (and can-edit? (not (contains? #{"closed" "cancelled" "paused" "closing"} (:status project)))
                       (not= "submitted" (get-in resource [:data :plan_status])))
        context {:base base :model (:data resource) :project project :options options :editable? editable?
                 :can-approve? can-approve? :can-feedback? can-edit? :open! set-dialog! :compare! set-comparing!}]
    [:div
     [w/resource-view resource (fn [_] [planning-content context])]
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                   :on-saved (fn [_] (set-dialog! nil) (changed!))})])
     (when comparing [views/baseline-diff base comparing #(set-comparing! nil)])]))
