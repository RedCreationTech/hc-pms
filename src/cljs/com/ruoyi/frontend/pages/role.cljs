(ns com.ruoyi.frontend.pages.role
  "角色管理页面 — 搜索、CRUD、菜单/数据/用户权限分配。"
  (:require
    ["@ant-design/icons" :refer [DownloadOutlined SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


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


(defn- user-columns
  [action-label on-action]
  #js [#js {:title "用户编号" :dataIndex "user_id" :key "user_id" :width 80}
       #js {:title "用户名称" :dataIndex "user_name" :key "user_name"}
       #js {:title "用户昵称" :dataIndex "nick_name" :key "nick_name"}
       #js {:title "手机号" :dataIndex "phonenumber" :key "phonenumber" :width 130}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 100
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/button {:type "link" :size "small"
                                      :on-click #(on-action (.-user_id record))}
                         action-label]))}])


;; ─── 搜索表单 ──────────────────────────────────────────────────────

(defn- search-form
  []
  (let [query-params @(rf/subscribe [:roles/query-params])]
    [page-search/page-search {:visible? true}
     [page-search/search-row
      [page-search/search-item
       "角色名称"
       [antd/input {:placeholder "请输入角色名称"
                    :style page-search/input-style
                    :value (:role_name query-params)
                    :on-change #(rf/dispatch [:roles/update-query :role_name (.. % -target -value)])}]]
      [page-search/search-item
       "权限字符"
       [antd/input {:placeholder "请输入权限字符"
                    :style page-search/input-style
                    :value (:role_key query-params)
                    :on-change #(rf/dispatch [:roles/update-query :role_key (.. % -target -value)])}]]
      [page-search/search-item
       "状态"
       [antd/select {:placeholder "角色状态"
                     :style page-search/select-style
                     :value (:status query-params)
                     :allowClear true
                     :on-change #(rf/dispatch [:roles/update-query :status %])}
        [antd/select-option {:value "0"} "正常"]
        [antd/select-option {:value "1"} "停用"]]]
      [page-search/search-actions
       [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                    :on-click #(rf/dispatch [:roles/fetch query-params])}]
       [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                   :on-click #(do (rf/dispatch [:roles/reset-query])
                                                  (rf/dispatch [:roles/fetch {}]))}]]]]))


;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar
  []
  (let [query-params @(rf/subscribe [:roles/query-params])]
    [page-toolbar/page-toolbar
     {:left [page-toolbar/toolbar-left
             [page-toolbar/toolbar-button {:kind :add
                                           :icon (r/as-element [:> PlusOutlined])
                                           :on-click #(rf/dispatch [:roles/open-modal])
                                           :label "新增"}]
             [page-toolbar/toolbar-button {:kind :edit
                                           :icon (r/as-element [:> EditOutlined])
                                           :disabled? true
                                           :label "修改"}]
             [page-toolbar/toolbar-button {:kind :delete
                                           :icon (r/as-element [:> DeleteOutlined])
                                           :disabled? true
                                           :label "删除"}]
             [page-toolbar/toolbar-button {:kind :export
                                           :icon (r/as-element [:> DownloadOutlined])
                                           :on-click #(api/export-roles {})
                                           :label "导出"}]]
      :right [page-toolbar/toolbar-right
              [page-toolbar/round-tool-button {:title "搜索"
                                               :icon (r/as-element [:> SearchOutlined])
                                               :on-click #(rf/dispatch [:roles/fetch query-params])}]
              [page-toolbar/round-tool-button {:title "刷新"
                                               :icon (r/as-element [:> ReloadOutlined])
                                               :on-click #(rf/dispatch [:roles/fetch {}])}]]}]))


;; ─── 表格列定义 ──────────────────────────────────────────────────────

(defn- role-columns
  []
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
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:className "ruoyi-status-tag"}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 260
            :render (fn [_ ^js record]
                      (let [record-clj (js->clj record :keywordize-keys true)]
                        (r/as-element
                          (if (= "admin" (:role_key record-clj))
                            [:div]
                            [:div {:className "ruoyi-menu-actions"}
                             [antd/button {:type "link" :size "small"
                                           :icon (r/as-element [:> EditOutlined])
                                           :on-click #(rf/dispatch [:roles/edit record-clj])}
                              "修改"]
                             [antd/popconfirm {:title "确认删除该角色？"
                                               :onConfirm #(rf/dispatch [:roles/delete (.-role_id record)])}
                              [antd/button {:type "link" :danger true :size "small"
                                            :icon (r/as-element [:> DeleteOutlined])}
                               "删除"]]
                             [antd/dropdown {:menu {:items (clj->js [{:key "data" :label "数据权限"}
                                                                     {:key "users" :label "分配用户"}
                                                                     {:key "perm" :label "分配权限"}])
                                                    :onClick (fn [e]
                                                               (case (.-key e)
                                                                 "data" (rf/dispatch [:roles/open-data-scope record-clj])
                                                                 "users" (rf/dispatch [:roles/open-user-alloc record-clj])
                                                                 "perm" (rf/dispatch [:roles/open-permission record-clj])
                                                                 nil))}}
                              [antd/button {:type "link" :size "small"}
                               "更多"]]]))))}])


;; ─── 编辑弹窗 ──────────────────────────────────────────────────────

(defn- edit-modal
  []
  (let [visible? @(rf/subscribe [:roles/modal-visible?])
        editing @(rf/subscribe [:roles/editing])
        form-data @(rf/subscribe [:roles/form-data])
        [form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when visible?
          (.setFieldsValue form (clj->js (merge {:role_sort 0 :status "0" :data_scope "1"} form-data))))
        js/undefined)
      [visible? form-data])
    [antd/modal {:title (if editing "修改角色" "新增角色")
                 :open visible?
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:roles/close-modal])
                 :destroyOnHidden true}
     [antd/form {:form form
                 :labelCol {:span 6}
                 :wrapperCol {:span 16}
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:roles/submit (js->clj values :keywordize-keys true)]))
                 :initialValues (clj->js (merge {:role_sort 0 :status "0" :data_scope "1"} form-data))}
      [antd/form-item {:label "角色名称" :name "role_name"
                       :rules [{:required true :message "请输入角色名称"}]}
       [antd/input {:placeholder "请输入角色名称"}]]
      [antd/form-item {:label "权限字符" :name "role_key"
                       :rules [{:required true :message "请输入权限字符"}]}
       [antd/input {:placeholder "请输入权限字符"}]]
      [antd/form-item {:label "角色顺序" :name "role_sort"}
       [antd/input {:type "number" :placeholder "请输入角色顺序"}]]
      [antd/form-item {:label "状态" :name "status"}
       [antd/radio-group
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      [antd/form-item {:label "备注" :name "remark"}
       [antd/text-area {:placeholder "请输入备注" :rows 3}]]]]))


;; ─── 数据权限弹窗 ──────────────────────────────────────────────────

(defn- data-scope-modal
  []
  (let [visible? @(rf/subscribe [:roles/data-scope-visible?])
        role @(rf/subscribe [:roles/data-scope-role])
        [form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when visible?
          (.setFieldsValue form (clj->js {:data_scope (:data_scope role "1")})))
        js/undefined)
      [visible? role])
    [antd/modal {:title (str "分配数据权限 - " (:role_name role))
                 :open visible?
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:roles/close-data-scope])
                 :destroyOnHidden true}
     [antd/form {:form form
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:roles/save-data-scope
                                           (:role_id role)
                                           (js->clj values :keywordize-keys true)]))}
      [antd/form-item {:label "权限范围" :name "data_scope"
                       :rules [{:required true :message "请选择权限范围"}]}
       [antd/radio-group
        [antd/radio {:value "1"} "全部数据权限"]
        [antd/radio {:value "2"} "自定数据权限"]
        [antd/radio {:value "3"} "本部门数据权限"]
        [antd/radio {:value "4"} "本部门及以下数据权限"]
        [antd/radio {:value "5"} "仅本人数据权限"]]]
      (when (= "2" (:data_scope role "1"))
        [antd/form-item {:label "数据权限" :name "dept_ids"}
         [antd/tree-select {:treeData (clj->js (mapv dept->tree-node @(rf/subscribe [:roles/data-scope-dept-tree])))
                            :multiple true
                            :placeholder "请选择部门"
                            :treeCheckable true
                            :showCheckedStrategy "SHOW_PARENT"}]])]]))


;; ─── 分配用户弹窗 ──────────────────────────────────────────────────

(defn- user-alloc-modal
  []
  (let [visible? @(rf/subscribe [:roles/user-alloc-visible?])
        role @(rf/subscribe [:roles/user-alloc-role])
        selected-users @(rf/subscribe [:roles/allocated-selected])]
    [antd/modal {:title (str "分配用户 - " (:role_name role))
                 :open visible?
                 :onOk #(rf/dispatch [:roles/save-user-alloc (:role_id role) selected-users])
                 :onCancel #(rf/dispatch [:roles/close-user-alloc])
                 :destroyOnHidden true
                 :width 800}
     [antd/table {:columns (user-columns "取消授权" #(rf/dispatch [:roles/unauth-user (:role_id role) %]))
                  :dataSource (clj->js @(rf/subscribe [:roles/allocated-items]))
                  :rowKey "user_id"
                  :pagination {:pageSize 10}}]]))


;; ─── 菜单权限弹窗 ──────────────────────────────────────────────────

(defn- permission-modal
  []
  (let [visible? @(rf/subscribe [:roles/permission-visible?])
        role @(rf/subscribe [:roles/permission-role])
        selected-keys @(rf/subscribe [:roles/checked-keys])]
    [antd/modal {:title (str "分配菜单权限 - " (:role_name role))
                 :open visible?
                 :onOk #(rf/dispatch [:roles/save-permission (:role_id role) selected-keys])
                 :onCancel #(rf/dispatch [:roles/close-permission])
                 :destroyOnHidden true}
     [antd/tree {:checkable true
                 :checkedKeys (clj->js selected-keys)
                 :onCheck #(rf/dispatch [:roles/update-menu-selection (js->clj % :keywordize-keys false)])
                 :treeData (clj->js (mapv menu->tree-node @(rf/subscribe [:roles/menu-tree])))}]]))


;; ─── 主页面 ────────────────────────────────────────────────────────

(defn role-page
  []
  [:div {:style {:padding "20px"}}
   [search-form]
   [toolbar]
   [antd/table {:columns (role-columns)
                :dataSource (clj->js @(rf/subscribe [:roles/items]))
                :rowKey "role_id"
                :loading @(rf/subscribe [:roles/loading?])
                :pagination {:pageSize 10 :showSizeChanger true}
                :bordered true}]
   [edit-modal]
   [data-scope-modal]
   [user-alloc-modal]
   [permission-modal]])
