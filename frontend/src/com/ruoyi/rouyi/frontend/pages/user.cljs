(ns com.ruoyi.rouyi.frontend.pages.user
  "用户管理页面 - 对齐 RuoYi-Vue 功能。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined UploadOutlined DownloadOutlined SettingOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]
   [com.ruoyi.rouyi.frontend.components.dept-tree-select :refer [dept-tree-select]]))

;; ─── 搜索表单 ──────────────────────────────────────────────────────

(defn- search-form []
  (let [query-params @(rf/subscribe [:users/query-params])
        show-search? @(rf/subscribe [:users/show-search?])]
    (when show-search?
      [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding 16 :marginBottom 12 :borderRadius 8 :border "1px solid var(--ant-color-border-secondary, #e8e8e8)"}}
       [:div {:style {:display "flex" :flexWrap "wrap" :gap 12}}
        [:div {:style {:display "flex" :alignItems "center" :gap 8}}
         [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "用户名称"]
         [antd/input {:placeholder "请输入用户名称"
                      :style {:width 200}
                      :value (:user_name query-params)
                      :on-change #(rf/dispatch [:users/update-query :user_name (.. % -target -value)])}]]
        [:div {:style {:display "flex" :alignItems "center" :gap 8}}
         [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "手机号码"]
         [antd/input {:placeholder "请输入手机号码"
                      :style {:width 200}
                      :value (:phonenumber query-params)
                      :on-change #(rf/dispatch [:users/update-query :phonenumber (.. % -target -value)])}]]
        [:div {:style {:display "flex" :alignItems "center" :gap 8}}
         [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "状态"]
         [antd/select {:placeholder "用户状态"
                       :style {:width 200}
                       :value (:status query-params)
                       :allowClear true
                       :on-change #(rf/dispatch [:users/update-query :status %])}
          [antd/select-option {:value "0"} "正常"]
          [antd/select-option {:value "1"} "停用"]]]
        [:div {:style {:display "flex" :alignItems "center" :gap 8}}
         [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "部门"]
         [dept-tree-select {:value (:dept_id query-params)
                            :placeholder "请选择部门"
                            :allow-clear? true
                            :on-change #(rf/dispatch [:users/update-query :dept_id %])}]]
        [:div {:style {:display "flex" :gap 8 :alignItems "flex-end"}}
         [antd/button {:type "primary"
                       :icon (r/as-element [:> SearchOutlined])
                       :on-click #(rf/dispatch [:users/search])}
          "搜索"]
         [antd/button {:icon (r/as-element [:> ReloadOutlined])
                       :on-click #(rf/dispatch [:users/reset-query])}
          "重置"]]]])))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar []
  (let [show-search? @(rf/subscribe [:users/show-search?])
        columns @(rf/subscribe [:users/columns])]
    [:div {:style {:display "flex" :justifyContent "space-between" :marginBottom 10}}
     [:div {:style {:display "flex" :gap 8}}
      [antd/button {:type "primary" :ghost true :size "small"
                    :icon (r/as-element [:> PlusOutlined])
                    :on-click #(rf/dispatch [:users/open-add])}
       "新增"]
      [antd/button {:type "success" :ghost true :size "small"
                    :icon (r/as-element [:> EditOutlined])
                    :disabled @(rf/subscribe [:users/selected-empty?])
                    :on-click #(rf/dispatch [:users/open-edit-selected])}
       "修改"]
      [antd/button {:type "danger" :ghost true :size "small"
                    :icon (r/as-element [:> DeleteOutlined])
                    :disabled @(rf/subscribe [:users/selected-empty?])
                    :on-click #(rf/dispatch [:users/batch-delete])}
       "删除"]
      [antd/button {:type "info" :ghost true :size "small"
                    :icon (r/as-element [:> UploadOutlined])
                    :on-click #(rf/dispatch [:users/open-import])}
       "导入"]
      [antd/button {:type "warning" :ghost true :size "small"
                    :icon (r/as-element [:> DownloadOutlined])
                    :on-click #(rf/dispatch [:users/export])}
       "导出"]]
     [:div {:style {:display "flex" :gap 4}}
      [antd/tooltip {:title "显示搜索"}
       [antd/button {:icon (r/as-element [:> SearchOutlined])
                     :size "small"
                     :type (if show-search? "primary" "default")
                     :on-click #(rf/dispatch [:users/toggle-search])}]]
      [antd/tooltip {:title "刷新"}
       [antd/button {:icon (r/as-element [:> ReloadOutlined])
                     :size "small"
                     :on-click #(rf/dispatch [:users/fetch-with-params])}]]
      [antd/tooltip {:title "显隐列"}
       [antd/dropdown {:menu {:items (clj->js
                                      (map (fn [[key {:keys [label visible?]}]]
                                             {:key (name key)
                                              :label (r/as-element
                                                      [:div {:style {:display "flex" :justifyContent "space-between"
                                                                     :alignItems "center" :width 120}}
                                                       [:span label]
                                                       [antd/switch {:size "small" :checked visible?}]])})
                                           columns))
                              :onClick (fn [e]
                                         (let [key (keyword (.-key e))]
                                           (rf/dispatch [:users/toggle-column key])))}
                       :trigger #js ["click"]}
        [antd/button {:icon (r/as-element [:> SettingOutlined])
                      :size "small"}]]]]]))

;; ─── 用户表格 ──────────────────────────────────────────────────────

(defn- user-columns []
  (let [columns-config @(rf/subscribe [:users/columns])]
    (clj->js
     (filterv some?
              [(when (get-in columns-config [:user_id :visible?])
                 {:title "用户编号" :dataIndex "user_id" :key "user_id" :width 80})
               (when (get-in columns-config [:user_name :visible?])
                 {:title "用户名称" :dataIndex "user_name" :key "user_name"
                  :render (fn [v record]
                            (r/as-element
                             [:a {:style {:cursor "pointer" :color "#1677ff"}
                                   :on-click #(rf/dispatch [:users/view-detail (.-user_id ^js record)])}
                              v]))})
               (when (get-in columns-config [:nick_name :visible?])
                 {:title "用户昵称" :dataIndex "nick_name" :key "nick_name"})
               (when (get-in columns-config [:dept_name :visible?])
                 {:title "部门" :dataIndex "dept_name" :key "dept_name"})
               (when (get-in columns-config [:phonenumber :visible?])
                 {:title "手机号码" :dataIndex "phonenumber" :key "phonenumber" :width 120})
               (when (get-in columns-config [:status :visible?])
                 {:title "状态" :dataIndex "status" :key "status" :width 100
                  :render (fn [v record]
                            (r/as-element
                             [antd/switch {:checked (= v "0")
                                           :checkedChildren "正常"
                                           :unCheckedChildren "停用"
                                           :on-change (fn [checked?]
                                                        (rf/dispatch [:users/change-status
                                                                      (.-user_id ^js record)
                                                                      (if checked? "0" "1")]))}]))})
               (when (get-in columns-config [:create_time :visible?])
                 {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 160})
               {:title "操作" :key "action" :width 200
                :render (fn [_ record]
                          (r/as-element
                           [antd/space
                            [antd/button {:type "link" :size "small"
                                          :on-click #(rf/dispatch [:users/open-edit (.-user_id ^js record)])}
                             "修改"]
                            [antd/button {:type "link" :danger true :size "small"
                                          :on-click #(rf/dispatch [:users/delete (.-user_id ^js record)])}
                             "删除"]
                            [antd/dropdown {:menu {:items (clj->js [{:key "resetPwd" :label (r/as-element [:span "重置密码"])}
                                                                   {:key "authRole" :label (r/as-element [:span "分配角色"])}])
                                                   :onClick (fn [e]
                                                              (case (.-key e)
                                                                "resetPwd" (rf/dispatch [:users/reset-password (.-user_id ^js record)])
                                                                "authRole" (rf/dispatch [:users/auth-role (.-user_id ^js record)])
                                                                nil))}}
                             [antd/button {:type "link" :size "small"} "更多 ▾"]]]))}]))))

;; ─── 自定义弹窗（替代 antd/modal，避免 antd 6 + Reagent 兼容问题）──

(defn- form-modal []
  (let [visible? @(rf/subscribe [:users/modal-visible?])
        editing? @(rf/subscribe [:users/editing?])
        form-data @(rf/subscribe [:users/form-data])]
    (when visible?
      [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.45)" :zIndex 1050
                     :display "flex" :justifyContent "center" :alignItems "center"}}
       [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding "24px" :borderRadius "8px" :width 600
                      :maxHeight "90vh" :overflow "auto" :boxShadow "0 6px 16px rgba(0,0,0,0.08)"}}
        [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                       :marginBottom 16 :paddingBottom 12 :borderBottom "1px solid #e8e8e8"}}
         [:h3 {:style {:margin 0 :fontSize 16}} (if editing? "修改用户" "添加用户")]
         [antd/button {:type "text" :size "small"
                       :on-click #(rf/dispatch [:users/close-modal])} "✕"]]
        [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 12}}
         [:div
          [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "用户昵称 *"]
          [antd/input {:value (:nick_name form-data "")
                       :placeholder "请输入用户昵称"
                       :on-change #(rf/dispatch [:users/update-form :nick_name (.. % -target -value)])}]]
         [:div
          [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "归属部门"]
          [dept-tree-select {:value (:dept_id form-data)
                             :placeholder "请选择归属部门"
                             :allow-clear? true
                             :on-change #(rf/dispatch [:users/update-form :dept_id %])}]]
         [:div
          [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "手机号码"]
          [antd/input {:value (:phonenumber form-data "")
                       :placeholder "请输入手机号码"
                       :on-change #(rf/dispatch [:users/update-form :phonenumber (.. % -target -value)])}]]
         [:div
          [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "邮箱"]
          [antd/input {:value (:email form-data "")
                       :placeholder "请输入邮箱"
                       :on-change #(rf/dispatch [:users/update-form :email (.. % -target -value)])}]]
         (when-not editing?
           [:div
            [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "用户名称 *"]
            [antd/input {:value (:user_name form-data "")
                         :placeholder "请输入用户名称"
                         :on-change #(rf/dispatch [:users/update-form :user_name (.. % -target -value)])}]])
         (when-not editing?
           [:div
            [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "用户密码 *"]
            [antd/input {:value (:password form-data "")
                         :placeholder "请输入用户密码" :type "password"
                         :on-change #(rf/dispatch [:users/update-form :password (.. % -target -value)])}]])

         [:div
          [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "用户性别"]
          [antd/select {:value (:sex form-data)
                        :placeholder "请选择性别"
                        :allowClear true
                        :on-change #(rf/dispatch [:users/update-form :sex %])}
           [antd/select-option {:value "0"} "男"]
           [antd/select-option {:value "1"} "女"]
           [antd/select-option {:value "2"} "未知"]]]
         [:div
          [:label {:style {:display "block" :marginBottom 4 :fontWeight 500 :fontSize 13}} "状态"]
          [:div
           [:label {:style {:marginRight 16 :cursor "pointer"}}
            [antd/radio {:value "0" :checked (= (:status form-data) "0")
                         :on-change #(rf/dispatch [:users/update-form :status "0"])}] "正常"]
           [:label {:style {:cursor "pointer"}}
            [antd/radio {:value "1" :checked (= (:status form-data) "1")
                         :on-change #(rf/dispatch [:users/update-form :status "1"])}] "停用"]]]]
        [:div {:style {:display "flex" :justifyContent "flex-end" :gap 8 :marginTop 20 :paddingTop 16 :borderTop "1px solid #e8e8e8"}}
         [antd/button {:on-click #(rf/dispatch [:users/close-modal])} "取消"]
         [antd/button {:type "primary" :on-click #(rf/dispatch [:users/submit-form])} "确定"]]]])))

;; ─── 主页面 ────────────────────────────────────────────────────────

(defn- reset-password-modal []
  (let [visible? @(rf/subscribe [:users/reset-pwd-visible?])
        username @(rf/subscribe [:users/reset-pwd-username])]
    (when visible?
      [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.45)" :zIndex 1060
                     :display "flex" :justifyContent "center" :alignItems "center"}}
       [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding 24 :borderRadius 8 :width 400
                      :boxShadow "0 6px 16px rgba(0,0,0,0.08)"}}
        [:h3 {:style {:margin "0 0 16px 0" :fontSize 16}} (str "重置密码 - " username)]
        [antd/input {:value @(rf/subscribe [:users/reset-pwd-value])
                     :placeholder "请输入新密码"
                     :type "password"
                     :on-change #(rf/dispatch [:users/update-reset-pwd (.. % -target -value)])}]
        [:div {:style {:display "flex" :justifyContent "flex-end" :gap 8 :marginTop 16}}
         [antd/button {:on-click #(rf/dispatch [:users/close-reset-password])} "取消"]
         [antd/button {:type "primary" :on-click #(rf/dispatch [:users/confirm-reset-password])} "确定"]]]])))

(defn- render-tree-node
  "递归渲染单个部门节点。"
  [dept selected-dept-id expanded-id depth]
  (let [has-children (seq (:children dept))
        node-id (:dept_id dept)
        is-expanded? (= node-id @expanded-id)
        is-selected? (= node-id selected-dept-id)]
    ^{:key (str "dept-" node-id)}
    [:div {:style {:fontSize 13}}
     [:div {:style {:display "flex" :alignItems "center"
                    :padding "4px 8px" :paddingLeft (str (* depth 16) "px")
                    :cursor "pointer"
                    :borderRadius 4
                    :background (if is-selected? "#e6f7ff" "transparent")
                    :color (if is-selected? "#1677ff" "#333")}
            :on-click (fn []
                        (rf/dispatch [:users/select-dept node-id])
                        (rf/dispatch [:users/fetch {:dept_id node-id}]))}
      ;; 展开/折叠按钮
      (when has-children
        [:span {:style {:display "inline-flex" :width 16 :cursor "pointer"
                        :marginRight 4 :color "#999"}
                :on-click (fn [e]
                            (.stopPropagation e)
                            (if is-expanded?
                              (reset! expanded-id nil)
                              (reset! expanded-id node-id)))}
         (if is-expanded? "▼" "▶")])
      ;; 占位（叶子节点对齐）
      (when-not has-children
        [:span {:style {:display "inline-flex" :width 16 :marginRight 4}} ""])
      ;; 图标
      [:span {:style {:marginRight 4 :fontSize 12}}
       (if has-children "📁" "📄")]
      ;; 名称
      [:span (:dept_name dept)]]
     ;; 子节点
     (when (and has-children is-expanded?)
       (for [child (:children dept)]
         (render-tree-node child selected-dept-id expanded-id (inc depth))))]))

(defn- dept-tree-sidebar []
  (let [dept-items @(rf/subscribe [:depts/tree])
        selected-dept-id @(rf/subscribe [:users/selected-dept-id])
        expanded-id (r/atom nil)]
    [:div {:style {:width 200 :minWidth 200 :background "var(--ant-color-bg-container, #fff)" 
                   :borderRadius 8 :border "1px solid #e8e8e8"
                   :padding 12 :display "flex" :flexDirection "column"}}
     [:div {:style {:display "flex" :justifyContent "space-between"
                    :alignItems "center" :marginBottom 8
                    :paddingBottom 8 :borderBottom "1px solid #f0f0f0"}}
      [:span {:style {:fontWeight 600 :fontSize 14}} "部门列表"]
      [:div
       [antd/button {:type "text" :size "small"
                     :on-click #(reset! expanded-id nil)}
        "折叠"]
       [antd/button {:type "text" :size "small"
                     :on-click #(rf/dispatch [:depts/fetch {}])}
        "刷新"]]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (for [dept dept-items]
        (render-tree-node dept selected-dept-id expanded-id 0))]]))

(defn- dept-tree-sidebar []
  (let [dept-items @(rf/subscribe [:depts/tree])
        selected-dept-id @(rf/subscribe [:users/selected-dept-id])]
    [:div {:style {:width 200 :minWidth 200 :background "var(--ant-color-bg-container, #fff)"
                   :borderRadius 8 :border "1px solid #e8e8e8"
                   :padding 12 :display "flex" :flexDirection "column"}}
     [:div {:style {:display "flex" :justifyContent "space-between"
                    :alignItems "center" :marginBottom 8
                    :paddingBottom 8 :borderBottom "1px solid #f0f0f0"}}
      [:span {:style {:fontWeight 600 :fontSize 14}} "部门列表"]
      [antd/button {:type "text" :size "small"
                    :on-click #(rf/dispatch [:depts/fetch {}])}
       "刷新"]]
     [:div {:style {:flex 1 :overflow "auto" :fontSize 13}}
      (if (seq dept-items)
        (for [d (flatten-tree dept-items)]
          ^{:key (str "dept-" (:dept_id d))}
          [:div {:style {:padding "4px 8px" :cursor "pointer"
                         :color (if (= (:dept_id d) selected-dept-id) "#1677ff" "#333")
                         :background (if (= (:dept_id d) selected-dept-id) "#e6f7ff" "transparent")
                         :borderRadius 4}
                 :on-click #(do (rf/dispatch [:users/select-dept (:dept_id d)])
                                (rf/dispatch [:users/fetch {:dept_id (:dept_id d)}]))}
           (:dept_name d)])
        [:div {:style {:textAlign "center" :color "#999" :padding 20}} "加载中..."])]]))

(defn- flatten-tree
  "将树形部门列表展平为带深度的序列。"
  ([nodes] (flatten-tree nodes 0))
  ([nodes depth]
   (mapcat (fn [node]
             (cons (assoc node :_depth depth)
                   (when (seq (:children node))
                     (flatten-tree (:children node) (inc depth)))))
           nodes)))

(defn- flatten-visible-tree
  "展平可见的部门节点（只展开 expanded-ids 中的节点）。"
  ([nodes expanded-ids depth]
   (mapcat (fn [node]
             (let [is-expanded? (contains? expanded-ids (:dept_id node))]
               (cons (assoc node :_depth depth)
                     (when (and (seq (:children node)) is-expanded?)
                       (flatten-visible-tree (:children node) expanded-ids (inc depth))))))
           nodes)))

(defn- dept-tree-sidebar []
  (let [dept-items @(rf/subscribe [:depts/tree])
        selected-dept-id @(rf/subscribe [:users/selected-dept-id])
        [expanded-ids set-expanded!] (hooks/use-state #{})
        toggle! (fn [dept-id]
                  (set-expanded! (fn [ids]
                                   (if (contains? ids dept-id)
                                     (disj ids dept-id)
                                     (conj ids dept-id)))))]
    [:div {:style {:width 200 :minWidth 200 :background "var(--ant-color-bg-container, #fff)"
                   :borderRadius 8 :border "1px solid #e8e8e8"
                   :padding 12 :display "flex" :flexDirection "column"}}
     [:div {:style {:fontWeight 600 :fontSize 14 :marginBottom 8
                    :paddingBottom 8 :borderBottom "1px solid #f0f0f0"}}
      "部门列表"]
     [:div {:style {:flex 1 :overflow "auto" :fontSize 13}}
      (for [d (flatten-visible-tree dept-items expanded-ids 0)]
        ^{:key (str "dept-" (:dept_id d) "-" (:_depth d))}
        [:div {:style {:display "flex" :alignItems "center"
                       :padding "4px 8px"
                       :paddingLeft (str (+ 8 (* (:_depth d) 16)) "px")
                       :cursor "pointer" :borderRadius 4
                       :background (if (= (:dept_id d) selected-dept-id) "#e6f7ff" "transparent")
                       :color (if (= (:dept_id d) selected-dept-id) "#1677ff" "#333")}
               :on-click #(do (toggle! (:dept_id d))
                              (rf/dispatch [:users/select-dept (:dept_id d)])
                              (rf/dispatch [:users/fetch {:dept_id (:dept_id d)}]))}
         ;; 展开/折叠箭头
         (if (seq (:children d))
           [:span {:style {:display "inline-flex" :width 14 :fontSize 10
                           :marginRight 2 :color "#999"
                           :transform (if (contains? expanded-ids (:dept_id d))
                                        "rotate(90deg)" "rotate(0deg)")
                           :transition "transform 0.2s"}}
            "▶"]
           [:span {:style {:display "inline-flex" :width 14 :marginRight 2}} ""])
         ;; 图标
         [:span {:style {:marginRight 4 :fontSize 12}}
          (if (seq (:children d)) "📁" "📄")]
         ;; 名称
         [:span (:dept_name d)]])]]))

(defn user-page []
  (hooks/use-effect
    (fn []
      (rf/dispatch [:depts/fetch {}])
      (rf/dispatch [:users/fetch {}])
      js/undefined)
    [])
  (let [items @(rf/subscribe [:users/items])
        total @(rf/subscribe [:users/total])
        loading? @(rf/subscribe [:users/loading?])
        selected-ids @(rf/subscribe [:users/selected-ids])
        selected-dept-id @(rf/subscribe [:users/selected-dept-id])
        page @(rf/subscribe [:users/page])
        page-size @(rf/subscribe [:users/page-size])]
    [:div {:style {:display "flex" :gap 8 :height "100%"}}
     ;; 左侧部门树
     [dept-tree-sidebar]
     ;; 右侧内容区
     [:div {:style {:flex 1 :overflow "auto"}}
      [search-form]
      [toolbar]
      [antd/table {:rowKey "user_id"
                   :columns (user-columns)
                   :dataSource (clj->js items)
                   :loading loading?
                   :rowSelection {:selectedRowKeys (clj->js selected-ids)
                                  :onChange (fn [keys]
                                              (rf/dispatch [:users/set-selected (js->clj keys)]))}
                   :pagination {:current page
                                :pageSize page-size
                                :total total
                                :showSizeChanger true
                                :showQuickJumper true
                                :showTotal (fn [total] (str "共 " total " 条"))
                                :onChange (fn [page pageSize]
                                            (rf/dispatch [:users/change-page page pageSize]))}}]
      [form-modal]
      [reset-password-modal]]]))
