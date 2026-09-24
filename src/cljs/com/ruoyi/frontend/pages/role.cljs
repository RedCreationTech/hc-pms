(ns com.ruoyi.frontend.pages.role
  "角色管理页面 -- 搜索,CRUD,菜单/数据/用户权限分配."
  (:require
    ["@ant-design/icons" :refer [DownloadOutlined SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.dept-tree-select :refer [build-tree]]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [com.ruoyi.frontend.permission :as permission]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


;; ─── 辅助函数 ──────────────────────────────────────────────────────

(defn- menu->tree-node
  "将菜单数据转换为 Ant Design Tree 节点格式."
  [menu]
  (let [node {:title (:menu_name menu)
              :key (str (:menu_id menu))}]
    (if-let [children (seq (:children menu))]
      (assoc node :children (mapv menu->tree-node children))
      node)))


(defn- dept-tree-data
  "部门平铺列表 -> Ant Design Tree 节点."
  [depts]
  (build-tree depts (fn [d] {:title (:dept_name d) :key (str (:dept_id d))})))


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
             (when (permission/permitted? "system:role:add")
               [page-toolbar/toolbar-button {:kind :add
                                             :icon (r/as-element [:> PlusOutlined])
                                             :on-click #(rf/dispatch [:roles/open-modal])
                                             :label "新增"}])
             (when (permission/permitted? "system:role:export")
               [page-toolbar/toolbar-button {:kind :export
                                             :icon (r/as-element [:> DownloadOutlined])
                                             :on-click #(api/export-roles {})
                                             :label "导出"}])]
      :right [page-toolbar/toolbar-right
              [page-toolbar/round-tool-button {:title "搜索"
                                               :icon (r/as-element [:> SearchOutlined])
                                               :on-click #(rf/dispatch [:roles/fetch query-params])}]
              [page-toolbar/round-tool-button {:title "刷新"
                                               :icon (r/as-element [:> ReloadOutlined])
                                               :on-click #(rf/dispatch [:roles/fetch {}])}]]}]))


;; ─── 表格列定义 ──────────────────────────────────────────────────────

(defn- role-columns
  [{:keys [can-edit? can-remove?]}]
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
                          (if (or (= "admin" (:role_key record-clj)) (not (or can-edit? can-remove?)))
                            [:div]
                            [:div {:className "ruoyi-menu-actions"}
                             (when can-edit?
                               [antd/button {:type "link" :size "small"
                                             :icon (r/as-element [:> EditOutlined])
                                             :on-click #(rf/dispatch [:roles/edit record-clj])}
                                "修改"])
                             (when can-remove?
                               [antd/popconfirm {:title "确认删除该角色？"
                                                 :onConfirm #(rf/dispatch [:roles/delete (.-role_id record)])}
                                [antd/button {:type "link" :danger true :size "small"
                                              :icon (r/as-element [:> DeleteOutlined])}
                                 "删除"]])
                             (when can-edit?
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
                                 "更多"]])]))))}])


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

(def ^:private scope-options
  [["1" "全部数据权限" "可以看到全公司的用户与部门"]
   ["2" "自定数据权限" "只能看到下面勾选的部门"]
   ["3" "本部门数据权限" "只能看到本人所在部门"]
   ["4" "本部门及以下数据权限" "本人所在部门及其全部下级部门"]
   ["5" "仅本人数据权限" "只能看到本人"]])


(defn- data-scope-modal
  "数据权限: 5 种范围; 自定义时勾选部门 (保存到 sys_role_dept). 一个用户有多个角色时取并集."
  []
  (let [visible? @(rf/subscribe [:roles/data-scope-visible?])
        role @(rf/subscribe [:roles/data-scope-role])
        depts @(rf/subscribe [:roles/data-scope-dept-tree])
        loaded-keys @(rf/subscribe [:roles/data-scope-checked-keys])
        [scope set-scope!] (hooks/use-state "1")
        [checked set-checked!] (hooks/use-state [])]
    (hooks/use-effect
      (fn []
        (when visible? (set-scope! (str (or (:data_scope role) "1"))))
        js/undefined)
      [visible? role])
    (hooks/use-effect
      (fn []
        (set-checked! (vec loaded-keys))
        js/undefined)
      [loaded-keys])
    [antd/modal {:title (str "分配数据权限 - " (:role_name role))
                 :open visible?
                 :width 560
                 :okButtonProps {:disabled (and (= "2" scope) (empty? checked))}
                 :onOk #(rf/dispatch [:roles/save-data-scope {:role_id (:role_id role)
                                                              :data_scope scope
                                                              :dept_ids (if (= "2" scope) checked [])}])
                 :onCancel #(rf/dispatch [:roles/close-data-scope])
                 :destroyOnHidden true}
     [:div {:style {:marginBottom 8 :color "#8c8c8c"}} "决定拥有该角色的用户在用户管理, 部门管理中能看到和操作哪些数据."]
     [antd/radio-group {:value scope :onChange #(set-scope! (.. % -target -value))
                        :style {:display "flex" :flexDirection "column" :gap 6}}
      (for [[v label hint] scope-options]
        ^{:key v}
        [antd/radio {:value v} [:span label [:span {:style {:color "#8c8c8c" :marginLeft 8 :fontSize 12}} hint]]])]
     (when (= "2" scope)
       [:div {:style {:marginTop 12 :padding 8 :border "1px solid #f0f0f0" :borderRadius 6 :maxHeight 280 :overflow "auto"}}
        [antd/tree {:checkable true
                    :checkStrictly true
                    :defaultExpandAll true
                    :checkedKeys (clj->js checked)
                    :onCheck (fn [v] (set-checked! (vec (.-checked v))))
                    :treeData (clj->js (dept-tree-data depts))}]])]))


;; ─── 分配用户弹窗 ──────────────────────────────────────────────────

(defn- user-alloc-modal
  "分配用户: 已分配 (可取消授权) 与 添加用户 (勾选未分配用户授权)."
  []
  (let [visible? @(rf/subscribe [:roles/user-alloc-visible?])
        role @(rf/subscribe [:roles/user-alloc-role])
        allocated-selected @(rf/subscribe [:roles/allocated-selected])
        unallocated-selected @(rf/subscribe [:roles/unallocated-selected])
        [tab set-tab!] (hooks/use-state "allocated")]
    (hooks/use-effect
      (fn []
        (when visible? (set-tab! "allocated"))
        js/undefined)
      [visible?])
    [antd/modal {:title (str "分配用户 - " (:role_name role))
                 :open visible?
                 :footer nil
                 :onCancel #(rf/dispatch [:roles/close-user-alloc])
                 :destroyOnHidden true
                 :width 820}
     [antd/tabs {:activeKey tab
                 :onChange (fn [k]
                             (set-tab! k)
                             (if (= "unallocated" k)
                               (rf/dispatch [:roles/fetch-unallocated])
                               (rf/dispatch [:roles/fetch-allocated])))
                 :items (clj->js
                          [{:key "allocated" :label "已分配用户"
                            :children (r/as-element
                                        [:div
                                         [antd/button {:danger true :disabled (empty? allocated-selected) :style {:marginBottom 8}
                                                       :onClick #(rf/dispatch [:roles/cancel-all-users])}
                                          "批量取消授权"]
                                         [antd/table {:columns (user-columns "取消授权" #(rf/dispatch [:roles/cancel-user %]))
                                                      :dataSource (clj->js @(rf/subscribe [:roles/allocated-items]))
                                                      :rowKey "user_id" :size "small"
                                                      :rowSelection {:selectedRowKeys (clj->js allocated-selected)
                                                                     :onChange #(rf/dispatch [:roles/set-allocated-selected (vec %)])}
                                                      :pagination {:pageSize 8}}]])}
                           {:key "unallocated" :label "添加用户"
                            :children (r/as-element
                                        [:div
                                         [antd/button {:type "primary" :disabled (empty? unallocated-selected) :style {:marginBottom 8}
                                                       :onClick #(rf/dispatch [:roles/select-all-users])}
                                          "授权选中用户"]
                                         [antd/table {:columns (user-columns "授权" #(rf/dispatch [:roles/select-users [%]]))
                                                      :dataSource (clj->js @(rf/subscribe [:roles/unallocated-items]))
                                                      :rowKey "user_id" :size "small"
                                                      :rowSelection {:selectedRowKeys (clj->js unallocated-selected)
                                                                     :onChange #(rf/dispatch [:roles/set-unallocated-selected (vec %)])}
                                                      :pagination {:pageSize 8}}]])}])}]]))


;; ─── 菜单权限弹窗 ──────────────────────────────────────────────────

(defn- leaf-keys
  "菜单树中没有下级的节点 key."
  [menus]
  (into #{} (mapcat (fn [m] (if (seq (:children m)) (leaf-keys (:children m)) [(str (:menu_id m))]))) menus))


(defn- permission-modal
  "父子联动勾选菜单与按钮. 已授权集合含上级目录, 显示时只把叶子交给树, 上级的全选/半选由树推导."
  []
  (let [visible? @(rf/subscribe [:roles/permission-visible?])
        role @(rf/subscribe [:roles/permission-role])
        selected-keys @(rf/subscribe [:roles/checked-keys])
        menus @(rf/subscribe [:roles/menu-tree])
        leaves (leaf-keys menus)]
    [antd/modal {:title (str "分配菜单权限 - " (:role_name role))
                 :open visible?
                 :width 560
                 :onOk #(rf/dispatch [:roles/save-permission (:role_id role) selected-keys])
                 :onCancel #(rf/dispatch [:roles/close-permission])
                 :destroyOnHidden true}
     [:div {:style {:color "#667085" :marginBottom 8}} "勾选菜单即授予其页面与按钮; 展开后可单独取消某个按钮 (如删除)."]
     [:div {:style {:maxHeight 460 :overflowY "auto"}}
      [antd/tree {:checkable true
                  :checkedKeys (clj->js (filterv leaves selected-keys))
                  :onCheck (fn [checked ^js info]
                             (rf/dispatch [:roles/update-menu-selection (js->clj checked) (js->clj (.-halfCheckedKeys info))]))
                  :treeData (clj->js (mapv menu->tree-node menus))}]]]))


;; ─── 主页面 ────────────────────────────────────────────────────────

(defn role-page
  []
  [:div {:style {:padding "20px"}}
   [search-form]
   [toolbar]
   [antd/table {:columns (role-columns {:can-edit? (permission/permitted? "system:role:edit")
                                        :can-remove? (permission/permitted? "system:role:remove")})
                :dataSource (clj->js @(rf/subscribe [:roles/items]))
                :rowKey "role_id"
                :loading @(rf/subscribe [:roles/loading?])
                :pagination {:pageSize 10 :showSizeChanger true}
                :bordered true}]
   [edit-modal]
   [data-scope-modal]
   [user-alloc-modal]
   [permission-modal]])
