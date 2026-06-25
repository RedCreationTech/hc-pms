(ns com.ruoyi.frontend.pages.menu
  "菜单管理页面 — 树形表格、CRUD、图标选择器、行拖拽排序。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined CheckOutlined ColumnHeightOutlined DragOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-search :as page-search]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.icon-picker :as icon-picker]
   ["@dnd-kit/core" :as dnd-kit-core]
   ["@dnd-kit/sortable" :as dnd-sortable]
   ["@dnd-kit/utilities" :refer [CSS]]))

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

;; ─── dnd-kit 行拖拽组件 ──────────────────────────────────────────────────────


(defn- sortable-row
  "可拖拽的行，包裹 antd Table tr。"
  [{:keys [id children style]}]
  (let [{:keys [attributes listeners setNodeRef transform transition isDragging]}
        (.useSortable dnd-sortable (clj->js {:id id}))
        row-style (merge (or style {})
                         (when transform
                           {:transform (.toString transform)
                            :transition (or transition "transform 200ms ease")})
                         (when isDragging
                           {:zIndex 9999
                            :position "relative"
                            :background "#fafafa"
                            :boxShadow "0 0 0 1px #1677ff"}))]
    (r/as-element
     [:tr (merge {:ref setNodeRef :style row-style :key (str "sortable-row-" id)}
                 attributes
                 (when (not= (.-tag (.-type js/document)) "INPUT")
                   listeners))
      children])))

;; ─── 保存排序按钮 ──────────────────────────────────────────────────────

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar [{:keys [all-expanded? on-toggle-expand on-save-sort]}]
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

(defn- collect-order-changes
  "遍历一组以展平顺序排列的 items（树中每层兄弟各自按显示顺序排序），
   收集需要保存的 {menu_id, order_num} 变更。"
  [items]
  (let [flat (flatten-menu-keys items)
        id-map (into {} (map (juxt :menu_id identity) (tree-seq :children :children items)))]
    (->> flat
         (keep (fn [menu-id]
                 (when-let [item (get id-map menu-id)]
                   {:menu_id menu-id :order_num (:order_num item)}))))))

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
        [local-items set-local-items!] (hooks/use-state nil)
        [expanded-keys set-expanded-keys!] (hooks/use-state :pending)
        [all-expanded? set-all-expanded!] (hooks/use-state true)
        items-source (or local-items items)
        tree-data (build-menu-tree items-source 0)
        expandable-ids (expandable-menu-ids tree-data)
        ;; dnd-sort 用的展平 ID 列表（仅同级交换）
        flat-ids (flatten-menu-keys tree-data)]

    ;; 外部 items 变化时重置本地状态
    (hooks/use-effect
     (fn []
       (set-local-items! nil)
       js/undefined)
     [items])

    (hooks/use-effect
     (fn []
       (when (= expanded-keys :pending)
         (set-expanded-keys! expandable-ids))
       js/undefined)
     [items-source])

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
                   tree (build-menu-tree current-items 0)]
               (rf/dispatch [:menus/save-sort (collect-order-changes tree)])))
           [items local-items])

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
          
          dnd-sensors (hooks/use-memo
                       (fn []
                         [(.useSensor dnd-kit-core/PointerSensor (clj->js {:activationConstraint {:distance 8}}))
                          (.useSensor dnd-kit-core/KeyboardSensor)])
                       [])
          
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
       [:div {:style {:background "#fff"
                      :minHeight "calc(100vh - 214px)"
                      :padding "10px 8px 24px 8px"}}
        [search-bar]
        [toolbar {:all-expanded? all-expanded?
                  :on-toggle-expand handle-toggle-expand
                  :on-save-sort handle-save-sort}]
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
                       :columns (menu-columns)
                       :dataSource (clj->js tree-data)
                       :pagination false
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
                                                  (let [record (.. row-props -data-row-key)
                                                        id (when record
                                                             (-> (js->clj record :keywordize-keys true)
                                                                 :menu_id))]
                                                    (r/as-element
                                                     [sortable-row
                                                      (merge {:id (or id "unknown")
                                                              :key (str "sortable-" id)}
                                                             (js->clj row-props :keywordize-keys true))])))}}
                       :onRow (fn [record]
                                (let [menu-id (:menu_id (js->clj record :keywordize-keys true))]
                                  #js {:data-row-key menu-id}))}]
         ]
         (when drag-active-id
           [:> (.-DragOverlay dnd-kit-core)
            {:dropAnimation nil}
            [:div {:style {:background "#fff"
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
       [edit-modal]]])))
