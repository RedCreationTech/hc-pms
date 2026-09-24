(ns com.ruoyi.frontend.pages.pms.detail
  "项目详情与生命周期操作."
  (:require
    ["@ant-design/icons" :refer [ArrowRightOutlined EditOutlined]]
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.form :as project-form]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [com.ruoyi.frontend.pages.pms.structure :as structure]
    [com.ruoyi.frontend.pages.pms.planning :as planning]
    [com.ruoyi.frontend.pages.pms.governance :as governance]
    [com.ruoyi.frontend.pages.pms.finance :as finance]
    [com.ruoyi.frontend.pages.pms.closure :as closure]
    [com.ruoyi.frontend.pages.pms.delivery :as delivery]
    [com.ruoyi.frontend.pages.pms.integration :as integration]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))

(defn- lifecycle
  "突出真实生命周期状态和阶段位置."
  [project]
  (let [colors (shared/use-colors)
        current (:status project)
        stages [["draft" "01" "草稿"] ["initiated" "02" "立项"] ["planning" "03" "计划"]
                ["execution" "04" "执行"] ["closing" "05" "收尾"] ["closed" "06" "关闭"]]]
    [:div {:style {:display "flex" :gap 12 :marginBottom 24 :alignItems "center" :flexWrap "wrap"}}
     (for [[status number label] stages]
       ^{:key status}
       [:div {:style {:padding "12px 18px" :borderRadius 8 :minWidth 112 :flex "1 1 112px"
                      :background (if (= current status) (:accent colors) (:soft colors))
                      :border (str "1px solid " (if (= current status) (:primary colors) (:border colors)))}}
        [:span {:style {:fontSize 11 :fontWeight 700 :color (:muted colors) :marginRight 12}} number]
        [:span {:style {:fontWeight 600 :color (if (= current status) (:primary colors) (:muted colors))}} label]])
     (when (contains? #{"paused" "cancelled"} current) [shared/status-tag current])]))

(defn- summary-grid
  "展示项目标识,归属与计划日期."
  [project]
  [antd/descriptions {:column {:xs 1 :sm 2 :md 3} :size "small"
                      :items (mapv (fn [[key label]]
                                     {:key (name key) :label label
                                      :children (shared/display-value (get (assoc project :project_type_label (shared/project-type-label (:project_type project))) key))})
                                   [[:project_no "项目编号"] [:project_type_label "项目类别"] [:customer "客户"] [:contract_no "合同编号"]
                                    [:manager_name "项目经理"] [:dept_name "所属部门"]
                                    [:start_date "计划开始"] [:end_date "计划完成"]])}])

(defn- transition-dialog
  "每次生命周期变更要求确认,取消还需填写原因."
  [project target on-close on-saved]
  (let [[form] (antd/form-use-form)
        cancelling? (= target "cancelled")
        reason-required? (contains? #{"cancelled" "paused"} target)
        title (case target "initiated" "确认立项" "planning" "进入计划阶段" "execution" "进入执行阶段"
                    "closing" "进入收尾阶段" "closed" "正式关闭项目" "paused" "暂停项目" "取消项目")
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
      [antd/form-item {:name "reason" :label (if reason-required? "变更原因" "变更说明")
                       :rules (when reason-required? [{:required true :whitespace true :message (if cancelling? "请填写取消原因" "请填写暂停原因")}])}
       [antd/text-area {:rows 3 :maxLength 500 :showCount true
                        :placeholder (if cancelling? "说明取消原因" "可补充本次变更的背景")}]]]]))

(defn- lifecycle-actions
  "只提供当前版本已实现的合法状态操作."
  [project set-target!]
  (let [can-transition? (shared/use-permission "pms:project:transition")
        status (:status project)
        next-status (if (= "paused" status) (:resume_status project)
                      ({"draft" "initiated" "initiated" "planning" "planning" "execution"
                        "execution" "closing" "closing" "closed"} status))]
    (when can-transition?
      [antd/space
       (when-not (contains? #{"closed" "cancelled"} status)
         [antd/button {:danger true :on-click #(set-target! "cancelled")} "取消项目"])
       (when (contains? #{"execution" "closing"} status)
         [antd/button {:on-click #(set-target! "paused")} "暂停项目"])
       (when next-status
         [antd/button {:type "primary" :icon (r/as-element [:> ArrowRightOutlined])
                       :on-click #(set-target! next-status)}
          (if (= status "paused") "恢复项目"
              (get {"initiated" "确认立项" "planning" "进入计划" "execution" "进入执行"
                    "closing" "进入收尾" "closed" "正式关闭"} next-status))])])))

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

(defn- template-dialog
  "从已发布且适用于当前项目类别的平台模板中选择并一次性实例化项目网络 (A07/A09)."
  [project on-close on-saved]
  (let [resource (shared/use-resource "/config/project-template" {} [])
        templates (filterv #(and (= "published" (:status %)) (some #{(:project_type project)} (:project_types %)))
                           (get-in resource [:data :rows]))]
    (cond
      (:error resource) [antd/modal {:title "应用项目模板" :open true :onCancel on-close :footer nil} [shared/error-panel (:error resource) (:refresh! resource)]]
      (:loading? resource) [antd/modal {:title "应用项目模板" :open true :onCancel on-close :footer nil} [antd/spin]]
      :else
      [w/mutation-dialog {:title "应用项目模板" :path (str "/projects/" (:project_id project) "/governance/template-instances")
                          :project project :on-close on-close :on-saved on-saved
                          :description (if (seq templates)
                                         "按模板一次性建立子项目/单机结构, Gate模板, 阶段与结构计划容器, 交付要求与收尾清单; 每个项目只能实例化一次, 后续模板修订不追溯覆盖."
                                         "当前项目类别没有已发布模板, 请先在 模板与规则 页面导入并发布.")
                          :fields [{:key :template_id :label "已发布模板" :type :select :required? true
                                    :options (mapv #(hash-map :value (:id %) :label (str (:title %) " (" (:code %) " v" (:revision %) ")")) templates)}
                                   {:key :reason :label "应用说明" :type :textarea}]}])))

(defn- template-summary
  "展示已实例化模板的版本快照."
  [template]
  (let [colors (shared/use-colors)]
    [:div {:style {:marginTop 16 :padding "12px 16px" :borderRadius 8 :background (:soft colors)}}
     [antd/space {:wrap true}
      [antd/tag {:color "geekblue"} (str "项目模板 " (:title template) " · " (:code template) " v" (:template_revision template))]
      [antd/tag (str "阶段 " (count (:stages template)))]
      [antd/tag (str "结构节点 " (:node_count template))]
      [antd/tag (str "Gate模板 " (:gate_template_count template))]
      [antd/tag (str "计划容器 " (:task_count template))]
      [antd/tag (str "收尾清单 " (:closure_item_count template))]]
     [:div {:style {:fontSize 12 :color (:muted colors) :marginTop 8}}
      (str "阶段权重: " (str/join " / " (map #(str (:name %) " " (:weight %) "%") (:stages template)))
           (when (seq (:team_roles template)) (str "  |  团队角色: " (str/join ", " (:team_roles template)))))]]))

(defn- detail-body
  "以项目结构,团队与变更记录组织详情工作区."
  [project revision options changed! edit! target! apply-template!]
  (let [editable? (not (contains? #{"closed" "cancelled" "paused"} (:status project)))
        can-edit? (and (shared/use-permission "pms:project:edit") editable?)]
    [:div {:style {:display "grid" :gap 20}}
     [shared/panel "项目概况" (when-not editable? (if (= "paused" (:status project)) "项目已暂停,恢复后可继续维护" "项目已结束,当前为只读视图"))
      [antd/space
       (when can-edit? [antd/button {:icon (r/as-element [:> EditOutlined]) :on-click edit!} "编辑资料"])
       (when (and can-edit? (nil? (:template project)) (contains? #{"draft" "initiated" "planning"} (:status project)))
         [antd/button {:on-click apply-template!} "应用项目模板"])
       [lifecycle-actions project target!]]
      [lifecycle project] [summary-grid project]
      (when (:template project) [template-summary (:template project)])
      (when (= "planning" (:status project))
        [:p {:style {:margin "16px 0 0" :fontSize 12 :color "#7b8798"}}
         "完善计划并通过独立基线与阶段Gate评审后,可进入执行阶段."])]
     [structure/project-structure (:project_id project) revision editable? changed!]
     [structure/project-members (:project_id project) revision options editable? changed! (:version project)]
     [project-events (:project_id project) revision]]))

(defn- workbench-content
  "项目级工作台统一组织计划,治理,费用与结项."
  [project revision options changed! edit! target! apply-template!]
  (let [members (shared/use-resource (str "/projects/" (:project_id project) "/members") {} [revision])
        ids (set (map :user_id (get-in members [:data :rows])))
        member-options (assoc options :users (filterv #(contains? ids (:user_id %)) (:users options)))]
  [antd/tabs {:defaultActiveKey "overview" :destroyOnHidden false
              :items [{:key "overview" :label "项目概况"
                       :children (r/as-element [detail-body project revision options changed! edit! target! apply-template!])}
                      {:key "planning" :label "计划与执行"
                       :children (r/as-element [planning/planning-workspace project revision member-options changed!])}
                      {:key "governance" :label "需求与治理"
                       :children (r/as-element [governance/governance-workspace project revision member-options changed!])}
                      {:key "delivery" :label "工程交付"
                       :children (r/as-element [delivery/delivery-workspace project revision member-options changed!])}
                      {:key "time" :label "实际工时"
                       :children (r/as-element [finance/time-workspace project revision member-options changed!])}
                      {:key "finance" :label "项目费用"
                       :children (r/as-element [finance/finance-workspace project revision member-options changed!])}
                      {:key "integration" :label "接口运维"
                       :children (r/as-element [integration/integration-workspace project revision member-options changed!])}
                      {:key "closure" :label "结项与移交"
                       :children (r/as-element [closure/closure-workspace project revision member-options changed!])}]}]))

(defn project-detail
  "项目详情抽屉,集中维护版本与刷新关联数据."
  [{:keys [id options on-close on-change]}]
  (let [[revision set-revision!] (hooks/use-state 0)
        [editing? set-editing!] (hooks/use-state false)
        [target set-target!] (hooks/use-state nil)
        [applying? set-applying!] (hooks/use-state false)
        {:keys [data loading? error refresh!]} (shared/use-resource (str "/projects/" id) {} [revision])
        changed! (fn [] (set-revision! inc) (on-change))]
    [antd/drawer {:title (r/as-element
                          [:div {:style {:display "flex" :gap 12 :alignItems "center"}}
                           [:span (or (:name data) "项目详情")]
                           (when data [shared/status-tag (:status data)])])
                  :open true :size "min(1440px, 100vw)"
                  :onClose on-close :destroyOnHidden true}
     (cond
       error [shared/error-panel error refresh!]
       (and loading? (nil? data)) [:div {:style {:padding 64 :textAlign "center"}} [antd/spin]]
       :else [w/refresh-boundary loading?
              [workbench-content data revision options changed! #(set-editing! true) set-target! #(set-applying! true)]])
     (when applying? [template-dialog data #(set-applying! false) (fn [_] (set-applying! false) (changed!))])
     (when editing? [project-form/project-form
                     {:project data :options options :on-close #(set-editing! false)
                      :on-saved (fn [_] (set-editing! false) (changed!))}])
     (when target [transition-dialog data target #(set-target! nil)
                   (fn [_] (set-target! nil) (changed!))])]))
