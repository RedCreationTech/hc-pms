(ns com.ruoyi.frontend.pages.pms.project
  "项目中心的真实数据工作区."
  (:require
    ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined SearchOutlined ArrowUpOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.detail :as detail]
    [com.ruoyi.frontend.pages.pms.form :as project-form]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))

(defn- project-name
  "项目名称与编号作为列表视觉锚点."
  [record open!]
  (let [project (js->clj record :keywordize-keys true)]
    [:div
     (if open!
       [antd/button {:type "link" :style {:padding 0 :height "auto" :fontWeight 600}
                     :on-click #(open! (:project_id project))} (:name project)]
       [:span {:style {:fontWeight 600}} (:name project)])
     [:div {:style {:fontSize 11 :fontFamily "ui-monospace, SFMono-Regular, monospace"
                    :color "#8793a3" :marginTop 5}} (:project_no project)]]))

(defn- columns
  "适合项目经理扫描的关键项目字段."
  [open!]
  [{:title "项目 / 编号" :key "name" :width 260
    :render (fn [_ record] (r/as-element [project-name record open!]))}
   {:title "客户" :dataIndex "customer" :width 180 :ellipsis true :render shared/display-value}
   {:title "类型" :dataIndex "project_type" :width 110
    :render #(or (:label (some (fn [item] (when (= % (:value item)) item)) shared/project-types)) %)}
   {:title "项目经理" :dataIndex "manager_name" :width 110 :render shared/display-value}
   {:title "所属部门" :dataIndex "dept_name" :width 130 :ellipsis true :render shared/display-value}
   {:title "生命周期" :dataIndex "status" :width 110 :render #(r/as-element [shared/status-tag %])}
   {:title "计划完成" :dataIndex "end_date" :width 130 :render shared/display-value}
   {:title "" :key "action" :width 84 :fixed "right"
    :render (fn [_ ^js record]
              (when open!
                (r/as-element [antd/button {:type "link" :size "small"
                                            :on-click #(open! (.-project_id record))} "详情"])))}])

(defn- search-bar
  "关键字和状态筛选改变时返回第一页."
  [query set-query! loading? refresh!]
  (let [[text set-text!] (hooks/use-state (:q query ""))]
    [:div {:style {:display "flex" :gap 12 :flexWrap "wrap" :marginBottom 18}}
     [antd/input {:value text :aria-label "搜索项目" :placeholder "搜索项目名称、编号、客户"
                  :style {:width 320 :maxWidth "100%"} :allowClear true
                  :prefix (r/as-element [:> SearchOutlined {:style {:color "#8793a3"}}])
                  :onChange #(set-text! (.. % -target -value))
                  :onPressEnter #(set-query! (assoc query :q text :page 1))}]
     [antd/select {:value (not-empty (:status query)) :placeholder "全部生命周期" :allowClear true
                   :aria-label "生命周期筛选" :style {:width 160} :options shared/statuses
                   :onChange #(set-query! (assoc query :status (or % "") :page 1))}]
     [antd/button {:type "primary" :on-click #(set-query! (assoc query :q text :page 1))} "查询"]
     [antd/button {:on-click #(do (set-text! "") (set-query! {:page 1 :size (:size query) :q "" :status ""}))} "重置"]
     [:div {:style {:flex 1}}]
     [antd/button {:title "刷新项目" :aria-label "刷新项目" :loading loading?
                   :icon (r/as-element [:> ReloadOutlined]) :on-click refresh!}]]))

(defn- project-table
  "受控分页保留当前筛选条件."
  [resource query set-query! open! create!]
  (let [{:keys [data loading? error refresh!]} resource
        rows (:rows data [])]
    [:<>
     (when error [shared/error-panel error refresh!])
     [antd/table {:rowKey "project_id" :size "middle" :columns (clj->js (columns open!))
                  :dataSource (clj->js rows) :loading loading? :scroll {:x 1120}
                  :locale {:emptyText (r/as-element
                                       [shared/empty-state
                                        (if (or (seq (:q query)) (seq (:status query)))
                                          "没有匹配的项目,试试调整搜索条件" "还没有项目,从第一个项目开始")
                                        (when create! [antd/button {:type "primary" :on-click create!} "创建项目"])])}
                  :pagination {:current (:page query) :pageSize (:size query) :total (:total data 0)
                               :showSizeChanger true :pageSizeOptions [10 20 50]
                               :showTotal #(str "共 " % " 个项目")
                               :onChange #(set-query! (assoc query :page %1 :size %2))}}]]))

(defn- read-query
  "从地址恢复筛选与分页,保证刷新后视图一致."
  [params]
  (let [page (js/parseInt (or (.get params "page") "1") 10)
        size (js/parseInt (or (.get params "size") "10") 10)]
    {:page (if (and (js/Number.isFinite page) (pos? page)) page 1)
     :size (if (contains? #{10 20 50} size) size 10)
     :q (or (.get params "q") "")
     :status (or (.get params "status") "")}))

(defn- use-project-url
  "保存当前项目与筛选,详情地址可分享且刷新不会丢失."
  [query selected]
  (hooks/use-effect
    (fn []
      (let [params (js/URLSearchParams.)]
        (doseq [[key value] (assoc query :id selected)]
          (when (and (some? value) (not= "" value))
            (.set params (name key) (str value))))
        (.replaceState js/history nil "" (str "/pms/project?" (.toString params))))
      js/undefined)
    [(:page query) (:size query) (:q query) (:status query) selected]))

(defn- project-workspace
  "协调项目列表,新增抽屉与详情抽屉."
  []
  (let [colors (shared/use-colors)
        params (js/URLSearchParams. (.-search js/location))
        [query set-query!] (hooks/use-state (read-query params))
        [selected set-selected!] (hooks/use-state (.get params "id"))
        [creating? set-creating!] (hooks/use-state false)
        resource (shared/use-resource "/projects" query [(:page query) (:size query) (:q query) (:status query)])
        options (shared/use-resource "/options" {} [])
        can-add? (shared/use-permission "pms:project:add")
        can-dashboard? (shared/use-permission "pms:dashboard:query")
        can-query? (shared/use-permission "pms:project:query")
        create! (when (and can-add? (:data options)) #(set-creating! true))]
    (use-project-url query selected)
    [:main {:style {:padding "26px 28px" :color (:text colors) :maxWidth 1800 :margin "0 auto"}}
     [shared/page-heading "HC / PROJECT MANAGEMENT" "项目中心" "连接客户需求、项目结构与团队,让每一次交付都有清晰起点."
      [antd/space
       (when can-dashboard? [antd/button {:on-click #(rf/dispatch [:navigate :pms-dashboard])} "项目驾驶舱"])
       (when can-add? [antd/button {:type "primary" :disabled (nil? create!) :on-click create!
                                    :icon (r/as-element [:> PlusOutlined])} "创建项目"])]]
     (when (:error options) [shared/error-panel (:error options) (:refresh! options)])
     [shared/panel "项目台账" "按项目追踪责任、计划与当前阶段" nil
      [search-bar query set-query! (:loading? resource) (:refresh! resource)]
      [project-table resource query set-query! (when can-query? set-selected!) create!]]
     (when creating? [project-form/project-form
                     {:project nil :options (:data options) :on-close #(set-creating! false)
                      :on-saved (fn [project] (set-creating! false) ((:refresh! resource))
                                  (set-selected! (:project_id project)))}])
     (when (and selected can-query?) [detail/project-detail
                     {:id selected :options (:data options) :on-close #(set-selected! nil)
                      :on-change (:refresh! resource)}])]))

(defn project-page
  "项目台账遵循当前登录用户的模块权限."
  []
  (if (shared/use-permission "pms:project:list")
    [project-workspace]
    [shared/empty-state "当前账号没有项目中心访问权限" nil]))
