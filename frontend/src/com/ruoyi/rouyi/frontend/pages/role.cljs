(ns com.ruoyi.rouyi.frontend.pages.role
  "角色管理页面 — 搜索、CRUD、菜单权限分配。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [DownloadOutlined SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined SafetyOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]
   [com.ruoyi.rouyi.frontend.api :as api]))

;; ─── 辅助函数 ──────────────────────────────────────────────────────

(defn- menu->tree-node
  "将菜单数据转换为 Ant Design Tree 节点格式。"
  [menu]
  (let [node {:title (:menu_name menu)
              :key (str (:menu_id menu))}]
    (if-let [children (seq (:children menu))]
      (assoc node :children (mapv menu->tree-node children))
      node)))

(defn- dept->tree-node
  "将部门数据转换为 Ant Design Tree 节点格式。"
  [dept]
  (let [node {:title (:dept_name dept)
              :key (str (:dept_id dept))}]
    (if-let [children (seq (:children dept))]
      (assoc node :children (mapv dept->tree-node children))
      node)))

;; ─── 搜索表单 ──────────────────────────────────────────────────────

(defn- search-form []
  (let [query-params @(rf/subscribe [:roles/query-params])]
    [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding 16 :marginBottom 12 :borderRadius 8 :border "1px solid var(--ant-color-border-secondary, #e8e8e8)"}}
     [:div {:style {:display "flex" :flexWrap "wrap" :gap 12}}
      [:div {:style {:display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "角色名称"]
       [antd/input {:placeholder "请输入角色名称"
                    :style {:width 200}
                    :value (:role_name query-params)
                    :on-change #(rf/dispatch [:roles/update-query :role_name (.. % -target -value)])}]]
      [:div {:style {:display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "权限字符"]
       [antd/input {:placeholder "请输入权限字符"
                    :style {:width 200}
                    :value (:role_key query-params)
                    :on-change #(rf/dispatch [:roles/update-query :role_key (.. % -target -value)])}]]
      [:div {:style {:display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "状态"]
       [antd/select {:placeholder "角色状态"
                     :style {:width 200}
                     :value (:status query-params)
                     :allowClear true
                     :on-change #(rf/dispatch [:roles/update-query :status %])}
        [antd/select-option {:value "0"} "正常"]
        [antd/select-option {:value "1"} "停用"]]]
      [:div {:style {:display "flex" :gap 8 :alignItems "flex-end"}}
       [antd/button {:type "primary"
                     :icon (r/as-element [:> SearchOutlined])
                     :on-click #(rf/dispatch [:roles/fetch (:roles/query-params @re-frame.db/app-db)])}
        "搜索"]
       [antd/button {:icon (r/as-element [:> ReloadOutlined])
                     :on-click #(do (rf/dispatch [:roles/reset-query])
                                    (rf/dispatch [:roles/fetch {}]))}
        "重置"]
       [antd/button {:icon (r/as-element [:> DownloadOutlined])
                     :on-click #(api/export-roles {})}
        "导出"]]]]))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar []
  [:div {:style {:display "flex" :gap 8 :marginBottom 16}}
   [antd/button {:type "primary"
                 :icon (r/as-element [:> PlusOutlined])
                 :on-click #(rf/dispatch [:roles/open-modal])}
    "新增"]
   [antd/button {:icon (r/as-element [:> ReloadOutlined])
                 :on-click #(rf/dispatch [:roles/fetch {}])}
    "刷新"]
   [antd/button {:icon (r/as-element [:> DownloadOutlined])
                 :on-click #(api/export-roles {})}
    "导出"]])

;; ─── 表格列定义 ──────────────────────────────────────────────────────

(defn- role-columns []
  #js [#js {:title "角色编号" :dataIndex "role_id" :key "role_id" :width 80}
       #js {:title "角色名称" :dataIndex "role_name" :key "role_name"
            :render (fn [v record]
                      (r/as-element
                       [:a {:style {:cursor "pointer" :color "#1677ff"}
                            :on-click #(rf/dispatch [:roles/edit (js->clj record :keywordize-keys true)])}
                        v]))}
       #js {:title "权限字符" :dataIndex "role_key" :key "role_key"}
       #js {:title "显示顺序" :dataIndex "role_sort" :key "role_sort" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v ^js record]
                      (r/as-element
                       [antd/switch {:checked (= v "0")
                                     :checkedChildren "正常"
                                     :unCheckedChildren "停用"
                                     :on-change (fn [checked?]
                                                  (rf/dispatch [:roles/change-status
                                                                (.-role_id record)
                                                                (if checked? "0" "1")]))}]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 220
            :render (fn [_ ^js record]
                      (r/as-element
                       [([antd/button {:type "link" :size "small"
                                      :on-click #(rf/dispatch [:roles/open-user-alloc (js->clj record :keywordize-keys true)])}
                         "分配用户"])
                        [antd/button {:type "link" :size "small"
                                      :on-click #(rf/dispatch [:roles/open-data-scope (js->clj record :keywordize-keys true)])}
                         "数据权限"]
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> SafetyOutlined])
                                      :on-click #(rf/dispatch [:roles/open-permission (js->clj record :keywordize-keys true)])}
                         "分配权限"]
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> EditOutlined])
                                      :on-click #(rf/dispatch [:roles/edit (js->clj record :keywordize-keys true)])}
                         "编辑"]
                        [antd/popconfirm {:title "确认删除该角色？"
                                          :onConfirm #(rf/dispatch [:roles/delete (.-role_id record)])}
                         [antd/button {:type "link" :danger true :size "small"
                                       :icon (r/as-element [:> DeleteOutlined])}
                          "删除"]]]))}])

;; ─── 编辑弹窗 ──────────────────────────────────────────────────────

(defn- edit-modal []
  (let [visible? @(rf/subscribe [:roles/modal-visible?])
        editing? @(rf/subscribe [:roles/editing?])
        form-data @(rf/subscribe [:roles/form-data])]
    [antd/modal {:title (if editing? "修改角色" "新增角色")
                 :open visible?
                 :onOk #(rf/dispatch [:roles/submit])
                 :onCancel #(rf/dispatch [:roles/close-modal])
                 :destroyOnHidden true}
     [antd/form {:labelCol {:span 6} :wrapperCol {:span 16}}
      [antd/form-item {:label "角色名称" :required true}
       [antd/input {:value (:role_name form-data "")
                    :on-change #(rf/dispatch [:roles/update-form :role_name (.. % -target -value)])}]]
      [antd/form-item {:label "权限字符" :required true}
       [antd/input {:value (:role_key form-data "")
                    :on-change #(rf/dispatch [:roles/update-form :role_key (.. % -target -value)])}]]
      [antd/form-item {:label "角色顺序"}
       [antd/input {:type "number"
                    :value (:role_sort form-data 0)
                    :on-change #(rf/dispatch [:roles/update-form :role_sort (js/parseInt (.. % -target -value) 10)])}]]
      [antd/form-item {:label "状态"}
       [antd/radio-group {:value (:status form-data "0")
                          :on-change #(rf/dispatch [:roles/update-form :status (.. % -target -value)])}
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      [antd/form-item {:label "备注"}
       [antd/text-area {:value (:remark form-data "")
                        :rows 3
                        :on-change #(rf/dispatch [:roles/update-form :remark (.. % -target -value)])}]]]]))

;; ─── 权限分配弹窗 ──────────────────────────────────────────────────────

(defn- permission-modal []
  (let [visible? @(rf/subscribe [:roles/permission-visible?])
        role @(rf/subscribe [:roles/permission-role])
        menu-tree @(rf/subscribe [:roles/menu-tree])
        checked-keys @(rf/subscribe [:roles/checked-keys])]
    [antd/modal {:title (str "分配权限 - " (:role_name role))
                 :open visible?
                 :width 500
                 :onOk #(rf/dispatch [:roles/save-permission])
                 :onCancel #(rf/dispatch [:roles/close-permission])
                 :destroyOnHidden true
                 :afterOpenChange (fn [open?]
                                    (when open?
                                      (rf/dispatch [:roles/fetch-menu-tree])))}
     (if (seq menu-tree)
       [antd/tree {:checkable true
                   :defaultExpandAll true
                   :checkedKeys (clj->js checked-keys)
                   :treeData (clj->js (mapv menu->tree-node menu-tree))
                   :onCheck (fn [keys _]
                              (rf/dispatch [:roles/set-checked-keys (js->clj keys)]))}]
       [:div {:style {:textAlign "center" :padding 24 :color "#999"}}
        "加载菜单树中..."])]))

;; ─── 数据权限弹窗 ──────────────────────────────────────────────────────

(defn- data-scope-modal []
  (let [visible? @(rf/subscribe [:roles/data-scope-visible?])
        role @(rf/subscribe [:roles/data-scope-role])
        data-scope @(rf/subscribe [:roles/data-scope])
        dept-tree @(rf/subscribe [:roles/data-scope-dept-tree])
        checked-keys @(rf/subscribe [:roles/data-scope-checked-keys])]
    [antd/modal {:title (str "数据权限 - " (:role_name role))
                 :open visible?
                 :width 500
                 :onOk #(rf/dispatch [:roles/save-data-scope])
                 :onCancel #(rf/dispatch [:roles/close-data-scope])
                 :destroyOnHidden true}
     [antd/radio-group {:value data-scope
                        :style {:marginBottom 16}
                        :on-change #(rf/dispatch [:roles/set-data-scope (.. % -target -value)])}
      [antd/radio {:value "1"} "全部数据权限"]
      [antd/radio {:value "2"} "本部门数据"]
      [antd/radio {:value "3"} "本部门及以下数据"]
      [antd/radio {:value "4"} "仅本人数据"]
      [antd/radio {:value "5"} "自定义数据"]]
     (when (= data-scope "5")
       (if (seq dept-tree)
         [antd/tree {:checkable true
                     :defaultExpandAll true
                     :checkedKeys (clj->js checked-keys)
                     :treeData (clj->js (mapv dept->tree-node dept-tree))
                     :onCheck (fn [keys _]
                                (rf/dispatch [:roles/set-data-scope-checked-keys (js->clj keys)]))}]
         [:div {:style {:textAlign "center" :padding 24 :color "#999"}}
          "加载部门树中..."]))]))

;; ─── 主页面 ──────────────────────────────────────────────────────

(;; ─── 用户分配弹窗 ──────────────────────────────────────────────────────
 defn role-page [] (hooks/use-effect
   (fn []
     (rf/dispatch [:roles/fetch {}])
     js/undefined)
   []) (let [items @(rf/subscribe [:roles/items])
        total @(rf/subscribe [:roles/total])
        loading? @(rf/subscribe [:roles/loading?])]
    [:div
     [search-form]
     [toolbar]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "role_id"
                  :columns (role-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total
                               :pageSize 10
                               :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [edit-modal]
     [permission-modal]
     [data-scope-modal]
     [user-alloc-modal]]))
