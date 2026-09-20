(ns com.ruoyi.frontend.pages.menu
  "菜单管理页面 — 树形表格、CRUD、图标选择器、行拖拽排序。"
  (:require
    ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined CheckOutlined ColumnHeightOutlined DragOutlined]]
    ["@dnd-kit/core" :as dnd-kit-core]
    ["@dnd-kit/sortable" :as dnd-sortable]
    ["@dnd-kit/utilities" :refer [CSS]]
    ["react" :as react]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.icon-picker :as icon-picker]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


;; ─── 辅助：平铺菜单转树 ──────────────────────────────────────────────────────

(defn- build-menu-tree
  "将平铺菜单列表按 parent_id 构建为树形结构，并附加层级信息用于对齐 RuoYi 树形表格缩进。"
  ([items parent-id]
   (build-menu-tree items parent-id 0))
  ([items parent-id level]
   (->> items
        (filter #(= parent-id (:parent_id %)))
        (mapv (fn [m]
                (let [children (build-menu-tree items (:menu_id m) (inc level))
                      node (assoc m :_level level)]
                  (if (seq children)
                    (assoc node :children children)
                    node)))))))


;; ─── 辅助：平铺转排序数组 ──────────────────────────────────────────────────────

(defn- flatten-menu-keys
  "将树形菜单平铺，按展平后的显示顺序返回 menu_id 列表，
   保持现有的 parent-child 层级关系。"
  [items]
  (mapcat
    (fn [item]
      (if-let [children (seq (:children item))]
        (cons (:menu_id item) (flatten-menu-keys children))
        [(:menu_id item)]))
    items))


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
  (let [[label tone] (cond
                       (= is-frame "1") ["外链" {:borderColor "#fde2e2" :background "#fef0f0" :color "#f56c6c"}]
                       (= menu-type "M") ["目录" {:borderColor "#d9ecff" :background "#ecf5ff" :color "#409eff"}]
                       (= menu-type "F") ["按钮" {:borderColor "#faecd8" :background "#fdf6ec" :color "#e6a23c"}]
                       :else ["菜单" {:borderColor "#e1f3d8" :background "#f0f9eb" :color "#67c23a"}])]
    [antd/tag {:style (merge {:minWidth 42
                              :height 24
                              :margin 0
                              :padding "0 10px"
                              :borderWidth 1
                              :borderStyle "solid"
                              :borderRadius 4
                              :fontSize 13
                              :lineHeight "22px"
                              :textAlign "center"}
                             tone)}
     label]))


(defn- menu-style-overrides
  []
  [:style
   ".ruoyi-menu-actions { display: inline-flex; align-items: center; gap: 4px; }\n.ruoyi-menu-action-btn.ant-btn-link { height: 30px; padding: 4px 8px; border: 2px solid transparent; border-radius: 8px; color: #409eff; }\n.ruoyi-menu-action-btn.ant-btn-link:hover, .ruoyi-menu-action-btn.ant-btn-link:focus, .ruoyi-menu-action-btn.ant-btn-link:active { color: #409eff !important; background: #ecf5ff !important; border-color: #b3d8ff !important; }\n.ruoyi-menu-action-btn.ant-btn-link.ant-btn-dangerous { color: #409eff; }\n.ruoyi-menu-delete-modal .ant-modal-content { border-radius: 4px; }\n.ruoyi-menu-delete-modal .ant-modal-header { padding: 15px 15px 10px; border-bottom: 0; }\n.ruoyi-menu-delete-modal .ant-modal-title { font-size: 18px; font-weight: 600; color: #303133; }\n.ruoyi-menu-delete-modal .ant-modal-body { padding: 10px 15px 20px; }\n.ruoyi-menu-delete-modal .ant-modal-footer { padding: 10px 15px 15px; border-top: 0; }\n.ruoyi-menu-delete-modal .ant-modal-footer .ant-btn { height: 32px; padding: 0 15px; font-size: 14px; border-radius: 4px; }\n.ruoyi-menu-delete-modal .ant-modal-footer .ant-btn-primary { background: #409eff; border-color: #409eff; }\n.ruoyi-menu-delete-modal .ant-modal-footer .ant-btn-primary:hover { background: #66b1ff; border-color: #66b1ff; }\n.ruoyi-menu-delete-message { display: flex; align-items: center; gap: 10px; min-height: 36px; font-size: 14px; line-height: 22px; color: #606266; }"])


;; ─── 搜索栏 ────────────────────────────────────────────────────────

(defn- search-bar
  [{:keys [visible?]}]
  (let [[menu-name set-menu-name!] (hooks/use-state "")
        [status set-status!] (hooks/use-state nil)]
    [page-search/page-search {:visible? visible?}
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


;; ─── dnd-kit 行拖拽组件 ──────────────────────────────────────────────────────


(defn- sortable-row
  "可拖拽的行，包裹 antd Table tr。"
  [{:keys [id row-props]}]
  (let [sortable (dnd-sortable/useSortable (clj->js {:id id}))
        attributes (.-attributes sortable)
        listeners (.-listeners sortable)
        set-node-ref (.-setNodeRef sortable)
        transform (.-transform sortable)
        transition (.-transition sortable)
        is-dragging (.-isDragging sortable)
        base-style (js->clj (or (.-style row-props) #js {}) :keywordize-keys true)
        row-style (merge base-style
                         (when transform
                           {:transform (.toString transform)
                            :transition (or transition "transform 200ms ease")})
                         (when is-dragging
                           {:zIndex 9999
                            :position "relative"
                            :background "#fafafa"
                            :boxShadow "0 0 0 1px #1677ff"}))
        active-el (.-activeElement js/document)
        active-tag (when active-el (.-tagName active-el))
        drag-props (when (not= active-tag "INPUT") listeners)
        props (js/Object.assign #js {} row-props attributes drag-props)]
    (set! (.-ref props) set-node-ref)
    (set! (.-style props) (clj->js row-style))
    (set! (.-key props) (str "sortable-row-" id))
    (react/createElement "tr" props (.-children row-props))))


;; ─── 保存排序按钮 ──────────────────────────────────────────────────────

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar
  [{:keys [all-expanded? on-toggle-expand on-save-sort on-toggle-search]}]
  [page-toolbar/page-toolbar
   {:style {:padding "8px 22px 10px 22px"}
    :left [page-toolbar/toolbar-left
           [page-toolbar/toolbar-button {:kind :add
                                         :icon (r/as-element [:> PlusOutlined])
                                         :on-click #(rf/dispatch [:menus/open-modal])
                                         :label "新增"}]
           [page-toolbar/toolbar-button {:kind :export
                                         :icon (r/as-element [:> CheckOutlined])
                                         :on-click on-save-sort
                                         :label "保存排序"}]
           [page-toolbar/toolbar-button {:kind :import
                                         :icon (r/as-element [:> ColumnHeightOutlined])
                                         :on-click on-toggle-expand
                                         :label "展开/折叠"}]]
    :right [page-toolbar/toolbar-right
            [page-toolbar/round-tool-button {:title "显示/隐藏搜索"
                                             :icon (r/as-element [:> SearchOutlined])
                                             :on-click on-toggle-search}]
            [page-toolbar/round-tool-button {:title "刷新"
                                             :icon (r/as-element [:> ReloadOutlined])
                                             :on-click #(rf/dispatch [:menus/fetch])}]]}])


;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- menu-columns
  [on-order-change on-delete-click]
  #js [#js {:title "菜单名称" :dataIndex "menu_name" :key "menu_name" :width 220
            :className "ruoyi-tree-name-cell"
            :render (fn [v ^js record]
                      (r/as-element
                        [:span {:style {:display "inline-flex"
                                        :alignItems "center"
                                        :gap 5
                                        :minWidth 0
                                        :paddingLeft (* 18 (or (.-_level record) 0))}}
                         (when (seq (or (.-icon record) ""))
                           (icon-picker/icon-element (.-icon record) {:style {:width 16 :height 16 :color "#606266"}}))
                         [:span {:style {:overflow "hidden"
                                         :textOverflow "ellipsis"
                                         :whiteSpace "nowrap"}}
                          v]]))}
       #js {:title "类型" :dataIndex "menu_type" :key "menu_type" :width 100
            :render (fn [v ^js record]
                      (r/as-element [menu-type-tag v (.-is_frame record)]))}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 200
            :className "ruoyi-menu-sort-cell"
            :render (fn [v ^js record]
                      (r/as-element
                        [antd/input-number {:value (or v 0)
                                            :min 0
                                            :size "small"
                                            :style {:width 88}
                                            :onChange #(on-order-change (.-menu_id record) (or % 0))}]))}
       #js {:title "权限标识" :dataIndex "perms" :key "perms"
            :render (fn [v _] (or v ""))}
       #js {:title "组件路径" :dataIndex "component" :key "component"
            :render (fn [v _] (or v ""))}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:className "ruoyi-status-tag"}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "操作" :key "action" :width 260
            :className "ruoyi-menu-action-cell"
            :render (fn [_ ^js record]
                      (r/as-element
                        [:div {:className "ruoyi-menu-actions"}
                         [antd/button {:type "link" :size "small" :className "ruoyi-menu-action-btn"
                                       :icon (r/as-element [:> EditOutlined])
                                       :on-click #(rf/dispatch [:menus/edit (js->clj record :keywordize-keys true)])}
                          "修改"]
                         [antd/button {:type "link" :size "small" :className "ruoyi-menu-action-btn"
                                       :icon (r/as-element [:> PlusOutlined])
                                       :on-click #(rf/dispatch [:menus/open-modal {:parent_id (.-menu_id record)}])}
                          "新增"]
                         [antd/button {:type "link" :danger true :size "small" :className "ruoyi-menu-action-btn"
                                       :icon (r/as-element [:> DeleteOutlined])
                                       :on-click #(on-delete-click (js->clj record :keywordize-keys true))}
                          "删除"]]))}])


;; ─── 菜单编辑弹窗 ──────────────────────────────────────────────────────

(defn- form-row
  [& children]
  (into [:div {:style {:display "grid"
                       :gridTemplateColumns "1fr 1fr"
                       :columnGap 28}}]
        children))


(defn- form-cell
  ([]
   [:div])
  ([child]
   [form-cell nil child])
  ([style child]
   [:div {:style style} child]))


(defn- label-with-tip
  [label tip]
  (r/as-element
    [antd/tooltip {:title tip}
     [:span {:style {:display "inline-flex" :alignItems "center" :gap 5}}
      [:span {:style {:display "inline-flex"
                      :alignItems "center"
                      :justifyContent "center"
                      :width 14
                      :height 14
                      :borderRadius "50%"
                      :background "#606266"
                      :color "#fff"
                      :fontSize 10
                      :lineHeight "14px"}}
       "?"]
      [:span label]]]))


(defn- edit-modal
  []
  (let [visible? @(rf/subscribe [:menus/modal-visible?])
        editing @(rf/subscribe [:menus/editing])
        form-data @(rf/subscribe [:menus/form-data])
        menu-tree @(rf/subscribe [:menus/tree-data])
        [form] (antd/form-use-form)
        [menu-type set-menu-type!] (hooks/use-state "M")
        defaults {:parent_id 0 :menu_type "M" :order_num nil :status "0" :visible "0" :is_frame "1" :is_cache "0"}
        form-item-style {:marginBottom 22}
        required-rule (fn [message]
                        (clj->js [{:required true :message message :trigger "blur"}]))
        radio-style {:display "flex" :gap 32}
        full-span {:gridColumn "1 / -1"}]
    (hooks/use-effect
      (fn []
        (when visible?
          (let [initial (merge defaults form-data)]
            (.setFieldsValue form (clj->js initial))
            (set-menu-type! (:menu_type initial "M"))))
        js/undefined)
      [visible? form-data])
    [antd/modal {:title (if editing "修改菜单" "添加菜单")
                 :open visible?
                 :width 680
                 :style {:width 680}
                 :onCancel #(rf/dispatch [:menus/close-modal])
                 :destroyOnHidden true
                 :footer (r/as-element
                           [antd/space {:style {:display "flex" :justifyContent "flex-end"}}
                            [antd/button {:type "primary" :on-click #(.submit form)} "确 定"]
                            [antd/button {:on-click #(rf/dispatch [:menus/close-modal])} "取 消"]])}
     [antd/form {:form form
                 :labelCol {:flex "100px"}
                 :wrapperCol {:flex "1"}
                 :labelAlign "right"
                 :colon false
                 :preserve false
                 :style {:padding "8px 0 0"}
                 :onFinish (fn [values]
                             (rf/dispatch [:menus/submit (js->clj values :keywordize-keys true)]))
                 :initialValues (clj->js (merge defaults form-data))
                 :onValuesChange (fn [changed _]
                                   (when-let [t (goog.object/get changed "menu_type")]
                                     (set-menu-type! t)))}
      [form-row
       [form-cell full-span
        [antd/form-item {:style form-item-style :label "上级菜单" :name "parent_id"}
         [antd/tree-select {:style {:width "100%"}
                            :placeholder "选择上级菜单"
                            :allowClear true
                            :treeDefaultExpandAll true
                            :treeData (clj->js [{:title "主类目"
                                                 :value 0
                                                 :key "0"
                                                 :children (menu-tree-options menu-tree)}])}]]]
       [form-cell full-span
        [antd/form-item {:style form-item-style :label "菜单类型" :name "menu_type"}
         [antd/radio-group {:style radio-style}
          [antd/radio {:value "M"} "目录"]
          [antd/radio {:value "C"} "菜单"]
          [antd/radio {:value "F"} "按钮"]]]]
       (when (not= menu-type "F")
         [form-cell
          [antd/form-item {:style form-item-style :label "菜单图标" :name "icon"}
           [icon-picker/icon-picker {:placeholder "点击选择图标"}]]])
       [form-cell
        [antd/form-item {:style form-item-style
                         :label "显示排序"
                         :name "order_num"
                         :rules (required-rule "菜单顺序不能为空")}
         [antd/input-number {:min 0 :style {:width "100%"}}]]]
       [form-cell
        [antd/form-item {:style form-item-style
                         :label "菜单名称"
                         :name "menu_name"
                         :rules (required-rule "菜单名称不能为空")}
         [antd/input {:placeholder "请输入菜单名称"}]]]
       (when (= menu-type "C")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "路由名称" "默认不填则和路由地址相同：如地址为：user，则名称为 User。特殊情况下请自定义，保证唯一性。")
                           :name "route_name"}
           [antd/input {:placeholder "请输入路由名称"}]]])
       (when (not= menu-type "C")
         [form-cell])
       (when (not= menu-type "F")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "是否外链" "选择是外链则路由地址需要以 http(s):// 开头")
                           :name "is_frame"}
           [antd/radio-group {:style radio-style}
            [antd/radio {:value "0"} "是"]
            [antd/radio {:value "1"} "否"]]]])
       (when (not= menu-type "F")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "路由地址" "访问的路由地址，如：user；如外网地址需内链访问则以 http(s):// 开头")
                           :name "path"
                           :rules (required-rule "路由地址不能为空")}
           [antd/input {:placeholder "请输入路由地址"}]]])
       (when (= menu-type "C")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "组件路径" "访问的组件路径，如：system/user/index，默认在 views 目录下")
                           :name "component"}
           [antd/input {:placeholder "请输入组件路径"}]]])
       (when (not= menu-type "M")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "权限字符" "控制器中定义的权限字符，如：system:user:list")
                           :name "perms"}
           [antd/input {:placeholder "请输入权限标识" :maxLength 100}]]])
       (when (= menu-type "C")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "路由参数" "访问路由的默认传递参数，如：{\"id\": 1, \"name\": \"ry\"}")
                           :name "query"}
           [antd/input {:placeholder "请输入路由参数" :maxLength 255}]]])
       (when (= menu-type "C")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "是否缓存" "选择是则会被 keep-alive 缓存，需要匹配组件的 name 和地址保持一致")
                           :name "is_cache"}
           [antd/radio-group {:style radio-style}
            [antd/radio {:value "0"} "缓存"]
            [antd/radio {:value "1"} "不缓存"]]]])
       (when (not= menu-type "F")
         [form-cell
          [antd/form-item {:style form-item-style
                           :label (label-with-tip "显示状态" "选择隐藏则路由将不会出现在侧边栏，但仍然可以访问")
                           :name "visible"}
           [antd/radio-group {:style radio-style}
            [antd/radio {:value "0"} "显示"]
            [antd/radio {:value "1"} "隐藏"]]]])
       [form-cell
        [antd/form-item {:style form-item-style
                         :label (label-with-tip "菜单状态" "选择停用则路由将不会出现在侧边栏，也不能被访问")
                         :name "status"}
         [antd/radio-group {:style radio-style}
          [antd/radio {:value "0"} "正常"]
          [antd/radio {:value "1"} "停用"]]]]]]]))


;; ─── 辅助：同级兄弟节点重新编号 ──────────────────────────────────────────────

(defn- reorder-siblings
  "给定平铺 items 列表，对同一 parent_id 的兄弟节点按指定顺序重排 order_num。"
  [items parent-id ordered-ids]
  (let [siblings (filter #(= parent-id (:parent_id %)) items)
        id->order (into {} (map-indexed (fn [i id] [id (inc i)]) ordered-ids))]
    (mapv (fn [item]
            (if (contains? id->order (:menu_id item))
              (assoc item :order_num (get id->order (:menu_id item)))
              item))
          items)))


(defn- flatten-menu-items
  [items]
  (mapcat (fn [item]
            (cons item (flatten-menu-items (:children item))))
          items))


(defn- update-item-order-num
  [items menu-id order-num]
  (mapv (fn [item]
          (let [item (if (= menu-id (:menu_id item))
                       (assoc item :order_num order-num)
                       item)]
            (if-let [children (seq (:children item))]
              (assoc item :children (update-item-order-num children menu-id order-num))
              item)))
        items))


(defn- original-order-map
  [items]
  (->> (flatten-menu-items items)
       (map (fn [item] [(:menu_id item) (:order_num item)]))
       (into {})))


(defn- collect-order-changes
  "遍历树形 items，只收集发生变化的 {menu_id, order_num}。"
  [items original-orders]
  (->> (flatten-menu-items items)
       (keep (fn [item]
               (let [menu-id (:menu_id item)
                     order-num (:order_num item)]
                 (when (not= (str (get original-orders menu-id)) (str order-num))
                   {:menu_id menu-id
                    :order_num order-num}))))
       vec))


;; ─── 删除确认弹窗 ──────────────────────────────────────────────────────

(defn- delete-confirm-modal
  [{:keys [target on-confirm on-cancel]}]
  [antd/modal {:open (boolean target)
               :centered true
               :width 420
               :className "ruoyi-menu-delete-modal"
               :title "系统提示"
               :closable true
               :maskClosable false
               :destroyOnHidden true
               :okText "确定"
               :cancelText "取消"
               :onOk on-confirm
               :onCancel on-cancel}
   [:div {:className "ruoyi-menu-delete-message"}
    [:span {:style {:display "inline-flex"
                    :alignItems "center"
                    :justifyContent "center"
                    :width 24
                    :height 24
                    :borderRadius "50%"
                    :background "#e6a23c"
                    :color "#fff"
                    :fontSize 16
                    :fontWeight 700
                    :lineHeight "24px"
                    :flexShrink 0}}
     "!"]
    [:span
     (str "是否确认删除名称为\"" (or (:menu_name target) "") "\"的数据项？")]]])


;; ─── 主页面 ──────────────────────────────────────────────────────

(defn menu-page
  []
  (hooks/use-effect
    (fn []
      (rf/dispatch [:menus/fetch])
      (rf/dispatch [:menus/fetch-tree])
      js/undefined)
    [])
  (let [items @(rf/subscribe [:menus/items])
        loading? @(rf/subscribe [:menus/loading?])
        [local-items set-local-items!] (hooks/use-state nil)
        [original-orders set-original-orders!] (hooks/use-state {})
        [expanded-keys set-expanded-keys!] (hooks/use-state :pending)
        [all-expanded? set-all-expanded!] (hooks/use-state true)
        [delete-target set-delete-target!] (hooks/use-state nil)
        [show-search? set-show-search!] (hooks/use-state true)
        items-source (or local-items items)
        tree-data (build-menu-tree items-source 0)
        expandable-ids (expandable-menu-ids tree-data)
        ;; dnd-sort 用的展平 ID 列表（仅同级交换）
        flat-ids (flatten-menu-keys tree-data)]

    ;; 外部 items 变化时重置本地状态
    (hooks/use-effect
      (fn []
        (set-local-items! nil)
        (set-original-orders! (original-order-map (build-menu-tree items 0)))
        js/undefined)
      [items])

    (hooks/use-effect
      (fn []
        (when (and (= expanded-keys :pending) (seq expandable-ids))
          (set-expanded-keys! expandable-ids))
        js/undefined)
      [items-source expanded-keys])

    ;; ── 拖拽排序回调 ──
    (let [handle-drag-end
          (hooks/use-callback
            (fn [active-id over-id]
              (let [active-items (or local-items items)
                    flat (flatten-menu-keys (build-menu-tree active-items 0))
                    active-idx (.indexOf (clj->js flat) active-id)
                    over-idx (.indexOf (clj->js flat) over-id)]
                (when (and (>= active-idx 0) (>= over-idx 0) (not= active-idx over-idx))
                  ;; 创建新顺序
                  (let [new-flat (vec
                                   (let [arr (to-array flat)]
                                     (.splice arr active-idx 1)
                                     (.splice arr over-idx 0 active-id)
                                     (js->clj arr)))
                        ;; 查找 active 和 over 的 parent_id
                        id->item (into {} (map (juxt :menu_id identity) active-items))
                        active-parent (:parent_id (get id->item active-id))
                        over-parent (:parent_id (get id->item over-id))]
                    ;; 只允许同级拖拽
                    (when (= active-parent over-parent)
                      ;; 重新编号同级兄弟
                      (let [sibling-ids (filter #(= active-parent (:parent_id (get id->item %))) new-flat)
                            updated (reorder-siblings active-items active-parent sibling-ids)]
                        (set-local-items! updated)))))))
            [items local-items])

          handle-save-sort
          (hooks/use-callback
            (fn []
              (let [current-items (or local-items items)
                    tree (build-menu-tree current-items 0)
                    changes (collect-order-changes tree original-orders)]
                (if (seq changes)
                  (rf/dispatch [:menus/save-sort changes])
                  (antd/warning! "未检测到排序修改"))))
            [items local-items original-orders])

          handle-order-change
          (hooks/use-callback
            (fn [menu-id order-num]
              (let [current-items (or local-items items)]
                (set-local-items! (update-item-order-num current-items menu-id order-num))))
            [items local-items])

          handle-delete-confirm
          (hooks/use-callback
            (fn []
              (when-let [target delete-target]
                (rf/dispatch [:menus/delete (:menu_id target)])
                (set-delete-target! nil)))
            [delete-target])

          handle-delete-cancel
          (hooks/use-callback
            (fn []
              (set-delete-target! nil))
            [])

          handle-toggle-expand
          (hooks/use-callback
            (fn []
              (if all-expanded?
                (set-expanded-keys! [])
                (do
                  (set-expanded-keys! expandable-ids)))
              (set-all-expanded! (not all-expanded?)))
            [all-expanded? expandable-ids])

          [drag-active-id set-drag-active-id!] (hooks/use-state nil)

          dnd-sensors (dnd-kit-core/useSensors
                        (dnd-kit-core/useSensor dnd-kit-core/PointerSensor
                                                (clj->js {:activationConstraint {:distance 8}}))
                        (dnd-kit-core/useSensor dnd-kit-core/KeyboardSensor))

          handle-drag-start (hooks/use-callback
                              (fn [event]
                                (set-drag-active-id! (.. event -active -id)))
                              [])
          handle-drag-end-wrapper (hooks/use-callback
                                    (fn [event]
                                      (set-drag-active-id! nil)
                                      (let [active (.. event -active -id)
                                            over (.. event -over -id)]
                                        (when (and active over (not= active over))
                                          (handle-drag-end active over))))
                                    [handle-drag-end])
          handle-drag-cancel (hooks/use-callback
                               (fn [_]
                                 (set-drag-active-id! nil))
                               [])]

      [:div {:style {:padding "0 12px 24px 12px"}}
       [menu-style-overrides]
       [:div {:style {:background "transparent"
                      :minHeight "calc(100vh - 214px)"
                      :padding "10px 8px 24px 8px"}}
        [search-bar {:visible? show-search?}]
        [toolbar {:all-expanded? all-expanded?
                  :on-toggle-expand handle-toggle-expand
                  :on-save-sort handle-save-sort
                  :on-toggle-search #(set-show-search! (not show-search?))}]
        [:> (.-DndContext dnd-kit-core)
         {:sensors dnd-sensors
          :onDragStart handle-drag-start
          :onDragEnd handle-drag-end-wrapper
          :onDragCancel handle-drag-cancel}
         [:> (.-SortableContext dnd-sortable)
          {:items (clj->js flat-ids)
           :strategy (.-rectSwappingStrategy dnd-sortable)}
          [antd/table {:scroll #js {:x 1180}
                       :rowKey "menu_id"
                       :loading loading?
                       :columns (menu-columns handle-order-change set-delete-target!)
                       :dataSource (clj->js tree-data)
                       :pagination false
                       :indentSize 24
                       :expandedRowKeys (clj->js (if (= expanded-keys :pending) expandable-ids expanded-keys))
                       :onExpand (fn [expanded? ^js record]
                                   (let [id (.-menu_id record)
                                         current-set (set (if (= expanded-keys :pending) expandable-ids expanded-keys))
                                         new-keys (vec (if expanded?
                                                         (conj current-set id)
                                                         (disj current-set id)))]
                                     (set-expanded-keys! new-keys)
                                     (set-all-expanded! (= (set new-keys) (set expandable-ids)))))
                       :childrenColumnName "children"
                       :components {:body {:row (fn [row-props]
                                                  (let [id (unchecked-get row-props "data-row-key")]
                                                    (r/as-element
                                                      [sortable-row {:id (or id "unknown")
                                                                     :row-props row-props
                                                                     :key (str "sortable-" id)}])))}}
                       :onRow (fn [record]
                                (let [menu-id (:menu_id (js->clj record :keywordize-keys true))]
                                  #js {:data-row-key menu-id}))}]]
         (when drag-active-id
           [:> (.-DragOverlay dnd-kit-core)
            {:dropAnimation nil}
            [:div {:style {:background "var(--ant-color-bg-container, #fff)"
                           :boxShadow "0 2px 8px rgba(0,0,0,0.15)"
                           :padding "8px 16px"
                           :borderRadius 4
                           :cursor "grabbing"
                           :display "inline-flex"
                           :alignItems "center"
                           :gap 8}
                   :key (str "drag-overlay-" drag-active-id)}
             [:> DragOutlined {:style {:color "#1677ff" :cursor "grab"}}]
             [:span drag-active-id]]])]
        [edit-modal]
        [delete-confirm-modal {:target delete-target
                               :on-confirm handle-delete-confirm
                               :on-cancel handle-delete-cancel}]]])))
