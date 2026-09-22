(ns com.ruoyi.frontend.pages.pms.detail
  "项目详情与生命周期操作."
  (:require
    ["@ant-design/icons" :refer [ArrowRightOutlined EditOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.form :as project-form]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.structure :as structure]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))

(defn- lifecycle
  "突出当前真实状态,规划后的阶段等待 Gate 能力上线."
  [project]
  (let [colors (shared/use-colors)
        current (:status project)
        stages [["draft" "01" "草稿"] ["initiated" "02" "立项"] ["planning" "03" "计划"]]]
    [:div {:style {:display "flex" :gap 12 :marginBottom 24 :alignItems "center" :flexWrap "wrap"}}
     (for [[status number label] stages]
       ^{:key status}
       [:div {:style {:padding "12px 18px" :borderRadius 8 :minWidth 112 :flex "1 1 112px"
                      :background (if (= current status) (:accent colors) (:soft colors))
                      :border (str "1px solid " (if (= current status) (:primary colors) (:border colors)))}}
        [:span {:style {:fontSize 11 :fontWeight 700 :color (:muted colors) :marginRight 12}} number]
        [:span {:style {:fontWeight 600 :color (if (= current status) (:primary colors) (:muted colors))}} label]])
     (when (= current "cancelled") [shared/status-tag current])]))

(defn- summary-grid
  "展示项目标识,归属与计划日期."
  [project]
  [antd/descriptions {:column {:xs 1 :sm 2 :md 3} :size "small"
                      :items (mapv (fn [[key label]]
                                     {:key (name key) :label label :children (shared/display-value (get project key))})
                                   [[:project_no "项目编号"] [:customer "客户"] [:contract_no "合同编号"]
                                    [:manager_name "项目经理"] [:dept_name "所属部门"]
                                    [:start_date "计划开始"] [:end_date "计划完成"]])}])

(defn- transition-dialog
  "每次生命周期变更要求确认,取消还需填写原因."
  [project target on-close on-saved]
  (let [[form] (antd/form-use-form)
        cancelling? (= target "cancelled")
        title (case target "initiated" "确认立项" "planning" "进入计划阶段" "取消项目")
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title title :open true :onCancel on-close :onOk #(.submit form)
                 :okText title :cancelText "返回" :confirmLoading busy?
                 :okButtonProps {:danger cancelling?} :destroyOnHidden true}
     (when error [shared/error-panel error nil])
     [:p (str "项目: " (:name project))]
     [:p {:style {:color "#7b8798"}}
      (if cancelling? "取消后项目停止推进,变更原因将保留在记录中."
          "确认当前项目资料后继续.本次状态变更会被记录.")]
     [antd/form {:form form :layout "vertical" :disabled busy?
                 :onFinish #(run! :post (str "/projects/" (:project_id project) "/transition")
                                  (merge {:status target :version (:version project)}
                                         (js->clj % :keywordize-keys true)) "项目状态已更新")}
      [antd/form-item {:name "reason" :label (if cancelling? "取消原因" "变更说明")
                       :rules (when cancelling? [{:required true :whitespace true :message "请填写取消原因"}])}
       [antd/text-area {:rows 3 :maxLength 500 :showCount true
                        :placeholder (if cancelling? "说明取消原因" "可补充本次变更的背景")}]]]]))

(defn- lifecycle-actions
  "只提供当前版本已实现的合法状态操作."
  [project set-target!]
  (let [can-transition? (shared/use-permission "pms:project:transition")
        status (:status project)
        next-status ({"draft" "initiated" "initiated" "planning"} status)]
    (when can-transition?
      [antd/space
       (when (contains? #{"draft" "initiated" "planning"} status)
         [antd/button {:danger true :on-click #(set-target! "cancelled")} "取消项目"])
       (when next-status
         [antd/button {:type "primary" :icon (r/as-element [:> ArrowRightOutlined])
                       :on-click #(set-target! next-status)}
          (if (= next-status "initiated") "确认立项" "进入计划")])])))

(defn- event-content
  "展示服务端生成的审计记录."
  [event]
  (let [colors (shared/use-colors)]
    [:div
     [:div {:style {:fontWeight 550}} (or (:description event) (:event_type event))]
     (when (or (:from_status event) (:to_status event))
       [:div {:style {:display "flex" :gap 8 :alignItems "center" :marginTop 8}}
        (when (:from_status event) [shared/status-tag (:from_status event)])
        [:span "→"] [shared/status-tag (:to_status event)]])
     [:div {:style {:fontSize 12 :color (:muted colors) :marginTop 8}}
      (str (or (:actor_name event) "系统") " · " (:created_at event))]]))

(defn- project-events
  "加载项目变更时间线,不给不存在的记录占位."
  [id revision]
  (let [{:keys [data loading? error refresh!]} (shared/use-resource (str "/projects/" id "/events") {} [revision])]
    [shared/panel "变更记录" "关键动作保留责任人与时间" nil
     (cond
       error [shared/error-panel error refresh!]
       loading? [antd/spin]
       (empty? (:rows data)) [shared/empty-state "暂无变更记录" nil]
       :else [antd/timeline {:items (mapv #(hash-map :key (:event_id %) :content (r/as-element [event-content %]))
                                         (:rows data))}])]))

(defn- detail-body
  "以项目结构,团队与变更记录组织详情工作区."
  [project revision options changed! edit! target!]
  (let [editable? (not (contains? #{"closed" "cancelled"} (:status project)))
        can-edit? (and (shared/use-permission "pms:project:edit") editable?)]
    [:div {:style {:display "grid" :gap 20}}
     [shared/panel "项目概况" (when-not editable? "项目已结束,当前为只读视图")
      [antd/space
       (when can-edit? [antd/button {:icon (r/as-element [:> EditOutlined]) :on-click edit!} "编辑资料"])
       [lifecycle-actions project target!]]
      [lifecycle project] [summary-grid project]
      (when (= "planning" (:status project))
        [:p {:style {:margin "16px 0 0" :fontSize 12 :color "#7b8798"}}
         "计划阶段: 可继续完善项目结构与团队.执行阶段将在 WBS 与基线能力完成后开放."])]
     [structure/project-structure (:project_id project) revision editable? changed!]
     [structure/project-members (:project_id project) revision options editable? changed!]
     [project-events (:project_id project) revision]]))

(defn project-detail
  "项目详情抽屉,集中维护版本与刷新关联数据."
  [{:keys [id options on-close on-change]}]
  (let [[revision set-revision!] (hooks/use-state 0)
        [editing? set-editing!] (hooks/use-state false)
        [target set-target!] (hooks/use-state nil)
        {:keys [data loading? error refresh!]} (shared/use-resource (str "/projects/" id) {} [revision])
        changed! (fn [] (set-revision! inc) (on-change))]
    [antd/drawer {:title (r/as-element
                          [:div {:style {:display "flex" :gap 12 :alignItems "center"}}
                           [:span (or (:name data) "项目详情")]
                           (when data [shared/status-tag (:status data)])])
                  :open true :size "min(980px, 100vw)"
                  :onClose on-close :destroyOnHidden true}
     (cond
       error [shared/error-panel error refresh!]
       loading? [:div {:style {:padding 64 :textAlign "center"}} [antd/spin]]
       :else [detail-body data revision options changed! #(set-editing! true) set-target!])
     (when editing? [project-form/project-form
                     {:project data :options options :on-close #(set-editing! false)
                      :on-saved (fn [_] (set-editing! false) (changed!))}])
     (when target [transition-dialog data target #(set-target! nil)
                   (fn [_] (set-target! nil) (changed!))])]))
