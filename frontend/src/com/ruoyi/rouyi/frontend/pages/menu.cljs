(ns com.ruoyi.rouyi.frontend.pages.menu
  "菜单管理页面 — 树形表格、CRUD、图标选择器。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]
   [com.ruoyi.rouyi.frontend.components.icon-picker :as icon-picker]))

;; ─── 菜单类型标签 ──────────────────────────────────────────────────────

(def menu-type-map {"M" "目录" "C" "菜单" "F" "按钮"})

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

;; ─── 搜索栏 ────────────────────────────────────────────────────────

(defn- search-bar []
  (let [[menu-name set-menu-name!] (hooks/use-state "")
        [status set-status!] (hooks/use-state nil)]
    [:div {:style {:display "flex" :gap 8 :marginBottom 12 :flexWrap "wrap" :alignItems "center"}}
     [antd/input {:placeholder "菜单名称" :style {:width 200}
                  :value menu-name :onChange #(set-menu-name! (-> % .-target .-value))}]
     [antd/select {:placeholder "状态" :style {:width 120} :allowClear true
                   :value status :onChange #(set-status! %)}
      [antd/select-option {:value "0"} "正常"]
      [antd/select-option {:value "1"} "停用"]]
     [antd/button {:type "primary" :icon (r/as-element [:> SearchOutlined])
                   :on-click #(rf/dispatch [:menus/search {:menu_name menu-name :status status}])}
      "搜索"]
     [antd/button {:icon (r/as-element [:> ReloadOutlined])
                   :on-click #(do (set-menu-name! "") (set-status! nil)
                                  (rf/dispatch [:menus/fetch]))}
      "重置"]]))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar []
  [:div {:style {:display "flex" :gap 8 :marginBottom 16}}
   [antd/button {:type "primary"
                 :icon (r/as-element [:> PlusOutlined])
                 :on-click #(rf/dispatch [:menus/open-modal])}
    "新增菜单"]
   [antd/button {:icon (r/as-element [:> ReloadOutlined])
                 :on-click #(rf/dispatch [:menus/fetch])}
    "刷新"]])

;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- menu-columns []
  #js [#js {:title "菜单名称" :dataIndex "menu_name" :key "menu_name" :width 220
            :render (fn [v ^js record]
                      (r/as-element
                       [antd/space
                        (icon-picker/icon-element (.-icon record) {:style {:fontSize 14}})
                        [:span v]]))}
       #js {:title "图标" :dataIndex "icon" :key "icon" :width 120
            :render (fn [v _]
                      (r/as-element
                       [antd/space
                        (icon-picker/icon-element v {:style {:fontSize 14}})
                        [:span (or v "-")]]))}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 80}
       #js {:title "权限标识" :dataIndex "perms" :key "perms" :width 150}
       #js {:title "组件路径" :dataIndex "component" :key "component" :width 150}
       #js {:title "类型" :dataIndex "menu_type" :key "menu_type" :width 80
            :render (fn [v _]
                      (r/as-element
                       [antd/tag (get menu-type-map v "菜单")]))}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:color (if (= v "0") "green" "red")}
                        (if (= v "0") "正常" "停用")]))}
       #js {:title "操作" :key "action" :width 220
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/space
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> PlusOutlined])
                                      :on-click #(do (rf/dispatch [:menus/update-form :parent_id (.-menu_id record)])
                                                     (rf/dispatch [:menus/open-modal]))}
                         "新增"]
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> EditOutlined])
                                      :on-click #(rf/dispatch [:menus/edit (js->clj record :keywordize-keys true)])}
                         "编辑"]
                        [antd/popconfirm {:title "确认删除该菜单？"
                                          :onConfirm #(rf/dispatch [:menus/delete (.-menu_id record)])}
                         [antd/button {:type "link" :danger true :size "small"
                                       :icon (r/as-element [:> DeleteOutlined])}
                          "删除"]]]))}])

;; ─── 菜单编辑弹窗 ──────────────────────────────────────────────────────

(defn- edit-modal []
  (let [visible? @(rf/subscribe [:menus/modal-visible?])
        editing? @(rf/subscribe [:menus/editing?])
        form-data @(rf/subscribe [:menus/form-data])
        menu-type (:menu_type form-data "M")]
    [antd/modal {:title (if editing? "修改菜单" "新增菜单")
                 :open visible?
                 :width 700
                 :onOk #(rf/dispatch [:menus/submit])
                 :onCancel #(rf/dispatch [:menus/close-modal])
                 :destroyOnHidden true}
     [antd/form {:labelCol {:span 6} :wrapperCol {:span 16}}
      [antd/form-item {:label "上级菜单"}
       [antd/tree-select {:style {:width "100%"}
                          :placeholder "选择上级菜单（空为顶级）"
                          :allowClear true
                          :treeDefaultExpandAll true
                          :treeData (clj->js (menu-tree-options @(rf/subscribe [:menus/tree-data])))
                          :value (:parent_id form-data)
                          :on-change #(rf/dispatch [:menus/update-form :parent_id %1])}]]
      [antd/form-item {:label "菜单类型" :required true}
       [antd/radio-group {:value menu-type
                          :on-change #(rf/dispatch [:menus/update-form :menu_type (.. % -target -value)])}
        [antd/radio {:value "M"} "目录"]
        [antd/radio {:value "C"} "菜单"]
        [antd/radio {:value "F"} "按钮"]]]
      [antd/form-item {:label "菜单图标"}
       [icon-picker/icon-picker {:value (:icon form-data)
                                 :on-change #(rf/dispatch [:menus/update-form :icon %])}]]
      [antd/form-item {:label "菜单名称" :required true}
       [antd/input {:value (:menu_name form-data "")
                    :on-change #(rf/dispatch [:menus/update-form :menu_name (.. % -target -value)])}]]
      [antd/form-item {:label "显示排序"}
       [antd/input {:type "number"
                    :value (:order_num form-data 0)
                    :on-change #(rf/dispatch [:menus/update-form :order_num (js/parseInt (.. % -target -value) 10)])}]]
      (when (not= menu-type "F")
        [:<>
         [antd/form-item {:label "路由地址"}
          [antd/input {:value (:path form-data "")
                       :on-change #(rf/dispatch [:menus/update-form :path (.. % -target -value)])}]]
         [antd/form-item {:label "组件路径"}
          [antd/input {:value (:component form-data "")
                       :on-change #(rf/dispatch [:menus/update-form :component (.. % -target -value)])}]]])
      (when (not= menu-type "M")
        [antd/form-item {:label "权限标识"}
         [antd/input {:value (:perms form-data "")
                      :on-change #(rf/dispatch [:menus/update-form :perms (.. % -target -value)])}]])
      [antd/form-item {:label "菜单状态"}
       [antd/radio-group {:value (:status form-data "0")
                          :on-change #(rf/dispatch [:menus/update-form :status (.. % -target -value)])}
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      (when (not= menu-type "F")
        [antd/form-item {:label "显示状态"}
         [antd/radio-group {:value (:visible form-data "0")
                            :on-change #(rf/dispatch [:menus/update-form :visible (.. % -target -value)])}
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
        tree-data (build-menu-tree items 0)]
    [:div
     [search-bar]
     [toolbar]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "menu_id"
                  :loading loading?
                  :columns (menu-columns)
                  :dataSource (clj->js tree-data)
                  :pagination false
                  :defaultExpandAllRows true
                  :childrenColumnName "children"}]
     [edit-modal]]))
