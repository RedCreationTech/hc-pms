(ns com.ruoyi.frontend.pages.menu
  "菜单管理页面 — 树形表格、CRUD、图标选择器。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined CheckOutlined ColumnHeightOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-search :as page-search]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.icon-picker :as icon-picker]))

;; ─── 辅助：平铺菜单转树 ──────────────────────────────────────────────────────

(defn- build-menu-tree
  "将平铺菜单列表按 parent_id 构建为树形结构。"
  [items parent-id]
  (->> items
       (filter #(= parent-id (:parent_id %)))
       (mapv (fn [m]
               (let [children (build-menu-tree items (:menu_id m))]
                 (if (seq children)
                   (assoc m :children children)
                   m))))))

;; ─── 辅助：菜单转树选项 ──────────────────────────────────────────────────────

(defn- menu-tree-options
  "将后端菜单树转为 TreeSelect 使用的选项。"
  [menus]
  (when (seq menus)
    (mapv (fn [m]
            (let [node {:title (:menu_name m) :value (:menu_id m) :key (str (:menu_id m))}]
              (if-let [children (seq (menu-tree-options (:children m)))]
                (assoc node :children children)
                node)))
          menus)))

(defn- expandable-menu-ids
  [nodes]
  (->> nodes
       (filter #(seq (:children %)))
       (mapcat #(cons (:menu_id %) (expandable-menu-ids (:children %))))
       vec))

(defn- menu-type-tag
  [menu-type is-frame]
  (let [label (cond
                (= is-frame "1") "外链"
                (= menu-type "M") "目录"
                (= menu-type "F") "按钮"
                :else "菜单")]
    [antd/tag {:className "ruoyi-menu-type-tag"} label]))

;; ─── 搜索栏 ────────────────────────────────────────────────────────

(defn- search-bar []
  (let [[menu-name set-menu-name!] (hooks/use-state "")
        [status set-status!] (hooks/use-state nil)]
    [page-search/page-search {:visible? true}
     [page-search/search-row
      [page-search/search-item
       "菜单名称"
       [antd/input {:placeholder "请输入菜单名称"
                    :style (merge page-search/input-style {:width 260})
                    :value menu-name
                    :onChange #(set-menu-name! (-> % .-target .-value))}]
       {:width 360}]
      [page-search/search-item
       "状态"
       [antd/select {:placeholder "菜单状态"
                     :style (merge page-search/select-style {:width 260})
                     :allowClear true
                     :value status
                     :onChange #(set-status! %)}
        [antd/select-option {:value "0"} "正常"]
        [antd/select-option {:value "1"} "停用"]]
       {:width 340}]
      [page-search/search-actions
       [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                    :on-click #(rf/dispatch [:menus/search {:menu_name menu-name :status status}])}]
       [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                   :on-click #(do (set-menu-name! "")
                                                  (set-status! nil)
                                                  (rf/dispatch [:menus/fetch]))}]]]]))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar []
  [page-toolbar/page-toolbar
   {:style {:padding "8px 22px 10px 22px"}
    :left [page-toolbar/toolbar-left
           [page-toolbar/toolbar-button {:kind :add
                                         :icon (r/as-element [:> PlusOutlined])
                                         :on-click #(rf/dispatch [:menus/open-modal])
                                         :label "新增"}]
           [page-toolbar/toolbar-button {:kind :export
                                         :icon (r/as-element [:> CheckOutlined])
                                         :label "保存排序"}]
           [page-toolbar/toolbar-button {:kind :import
                                         :icon (r/as-element [:> ColumnHeightOutlined])
                                         :label "展开/折叠"}]]
    :right [page-toolbar/toolbar-right
            [page-toolbar/round-tool-button {:title "搜索"
                                             :icon (r/as-element [:> SearchOutlined])
                                             :on-click #(rf/dispatch [:menus/search {}])}]
            [page-toolbar/round-tool-button {:title "刷新"
                                             :icon (r/as-element [:> ReloadOutlined])
                                             :on-click #(rf/dispatch [:menus/fetch])}]]}])

;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- menu-columns []
  #js [#js {:title "菜单名称" :dataIndex "menu_name" :key "menu_name" :width 280
            :className "ruoyi-tree-name-cell"
            :render (fn [v ^js record]
                      (r/as-element
                       [:span {:style {:display "inline-flex"
                                       :alignItems "center"
                                       :gap 8
                                       :minWidth 0}}
                        (icon-picker/icon-element (.-icon record) {:style {:fontSize 16 :color "#606266"}})
                        [:span {:style {:overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}} v]]))}
       #js {:title "图标" :dataIndex "icon" :key "icon" :width 140
            :render (fn [v _]
                      (r/as-element
                       [:span {:style {:display "inline-flex" :alignItems "center" :gap 8}}
                        (icon-picker/icon-element v {:style {:fontSize 16 :color "#606266"}})
                        [:span (or v "")]]))}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 80
            :className "ruoyi-menu-sort-cell"}
       #js {:title "权限标识" :dataIndex "perms" :key "perms" :width 220
            :render (fn [v _] (or v ""))}
       #js {:title "组件路径" :dataIndex "component" :key "component" :width 240
            :render (fn [v _] (or v ""))}
       #js {:title "类型" :dataIndex "menu_type" :key "menu_type" :width 110
            :render (fn [v ^js record]
                      (r/as-element [menu-type-tag v (.-is_frame record)]))}
       #js {:title "状态" :dataIndex "status" :key "status" :width 120
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:className "ruoyi-status-tag"}
                        (if (= v "0") "正常" "停用")]))}
       #js {:title "操作" :key "action" :width 300
            :className "ruoyi-menu-action-cell"
            :render (fn [_ ^js record]
                      (r/as-element
                       [:div {:className "ruoyi-menu-actions"}
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> EditOutlined])
                                      :on-click #(rf/dispatch [:menus/edit (js->clj record :keywordize-keys true)])}
                         "修改"]
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> PlusOutlined])
                                      :on-click #(rf/dispatch [:menus/open-modal {:parent_id (.-menu_id record)}])}
                         "新增"]
                        [antd/popconfirm {:title "确认删除该菜单？"
                                          :onConfirm #(rf/dispatch [:menus/delete (.-menu_id record)])}
                         [antd/button {:type "link" :danger true :size "small"
                                       :icon (r/as-element [:> DeleteOutlined])}
                          "删除"]]]))}])

;; ─── 菜单编辑弹窗 ──────────────────────────────────────────────────────

(defn- edit-modal []
  (let [visible? @(rf/subscribe [:menus/modal-visible?])
        editing @(rf/subscribe [:menus/editing])
        form-data @(rf/subscribe [:menus/form-data])
        [form] (antd/form-use-form)
        [menu-type set-menu-type!] (hooks/use-state "M")]
    (hooks/use-effect
     (fn []
       (when visible?
         (let [initial (merge {:menu_type "M" :order_num 0 :status "0" :visible "0" :is_frame "0" :is_cache "0"} form-data)]
           (.setFieldsValue form (clj->js initial))
           (set-menu-type! (:menu_type initial "M"))))
       js/undefined)
     [visible? form-data])
    [antd/modal {:title (if editing "修改菜单" "新增菜单")
                 :open visible?
                 :style {:width 700}
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:menus/close-modal])
                 :destroyOnHidden true}
     [antd/form {:form form
                 :labelCol {:span 6}
                 :wrapperCol {:span 16}
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:menus/submit (js->clj values :keywordize-keys true)]))
                 :initialValues (clj->js (merge {:menu_type "M" :order_num 0 :status "0" :visible "0" :is_frame "0" :is_cache "0"} form-data))
                 :onValuesChange (fn [changed _]
                                   (when-let [t (goog.object/get changed "menu_type")]
                                     (set-menu-type! t)))}
      [antd/form-item {:label "上级菜单" :name "parent_id"}
       [antd/tree-select {:style {:width "100%"}
                          :placeholder "选择上级菜单（空为顶级）"
                          :allowClear true
                          :treeDefaultExpandAll true
                          :treeData (clj->js (menu-tree-options @(rf/subscribe [:menus/tree-data])))}]]
      [antd/form-item {:label "菜单类型" :name "menu_type" :required true}
       [antd/radio-group
        [antd/radio {:value "M"} "目录"]
        [antd/radio {:value "C"} "菜单"]
        [antd/radio {:value "F"} "按钮"]]]
      [antd/form-item {:label "菜单图标" :name "icon"}
       [icon-picker/icon-picker {:placeholder "选择图标"}]]
      [antd/form-item {:label "菜单名称" :name "menu_name" :required true}
       [antd/input {:placeholder "请输入菜单名称"}]]
      [antd/form-item {:label "显示排序" :name "order_num"}
       [antd/input {:type "number" :placeholder "请输入显示排序"}]]
      (when (not= menu-type "F")
        [:<>
         [antd/form-item {:label "路由地址" :name "path"}
          [antd/input {:placeholder "请输入路由地址"}]]
         [antd/form-item {:label "路由名称" :name "route_name"}
          [antd/input {:placeholder "请输入路由名称"}]]
         [antd/form-item {:label "组件路径" :name "component"}
          [antd/input {:placeholder "请输入组件路径"}]]
         [antd/form-item {:label "路由参数" :name "query"}
          [antd/input {:placeholder "请输入路由参数"}]]
         [antd/form-item {:label "是否外链" :name "is_frame"}
          [antd/radio-group
           [antd/radio {:value "0"} "否"]
           [antd/radio {:value "1"} "是"]]]
         [antd/form-item {:label "是否缓存" :name "is_cache"}
          [antd/radio-group
           [antd/radio {:value "0"} "否"]
           [antd/radio {:value "1"} "是"]]]])
      (when (not= menu-type "M")
        [antd/form-item {:label "权限标识" :name "perms"}
         [antd/input {:placeholder "请输入权限标识"}]])
      [antd/form-item {:label "菜单状态" :name "status"}
       [antd/radio-group
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      (when (not= menu-type "F")
        [antd/form-item {:label "显示状态" :name "visible"}
         [antd/radio-group
          [antd/radio {:value "0"} "显示"]
          [antd/radio {:value "1"} "隐藏"]]])]]))

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn menu-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:menus/fetch])
     (rf/dispatch [:menus/fetch-tree])
     js/undefined)
   [])
  (let [items @(rf/subscribe [:menus/items])
        loading? @(rf/subscribe [:menus/loading?])
        [expanded-keys set-expanded-keys!] (hooks/use-state :pending)
        tree-data (build-menu-tree items 0)
        expandable-ids (expandable-menu-ids tree-data)]
    (hooks/use-effect
     (fn []
       (when (= expanded-keys :pending)
         (set-expanded-keys! expandable-ids))
       js/undefined)
     [items])
    [:div {:style {:padding "0 12px 24px 12px"}}
     [:div {:style {:background "#fff"
                    :minHeight "calc(100vh - 214px)"
                    :padding "10px 8px 24px 8px"}}
      [search-bar]
      [toolbar]
      [antd/table {:scroll #js {:x 1180}
                   :rowKey "menu_id"
                   :loading loading?
                   :columns (menu-columns)
                   :dataSource (clj->js tree-data)
                   :pagination false
                   :expandedRowKeys (clj->js (if (= expanded-keys :pending) expandable-ids expanded-keys))
                   :onExpand (fn [expanded? ^js record]
                               (let [id (.-menu_id record)
                                     current (set (if (= expanded-keys :pending) expandable-ids expanded-keys))]
                                 (set-expanded-keys!
                                  (vec (if expanded?
                                         (conj current id)
                                         (disj current id))))))
                   :childrenColumnName "children"}]]
     [edit-modal]]))
