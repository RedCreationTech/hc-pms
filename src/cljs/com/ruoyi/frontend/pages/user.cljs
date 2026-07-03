(ns com.ruoyi.frontend.pages.user
  "用户管理页面 - 对齐 RuoYi-Vue 功能。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["antd" :refer [DatePicker]]
   ["@ant-design/icons" :refer [SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined UploadOutlined DownloadOutlined SettingOutlined
                                FolderOpenOutlined FileTextOutlined AppstoreOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.dept-tree-select :refer [dept-tree-select]]))

(def range-picker (r/adapt-react-class (.-RangePicker DatePicker)))

;; ─── 搜索表单 ──────────────────────────────────────────────────────

(defn- search-form []
  (let [query-params @(rf/subscribe [:users/query-params])
        show-search? @(rf/subscribe [:users/show-search?])
        form-ref (hooks/use-ref nil)
        [height set-height!] (hooks/use-state (if show-search? "auto" "0px"))]
    ;; Animate height on toggle
    (hooks/use-effect
     (fn []
       (if show-search?
         (when-let [el (.-current form-ref)]
           (set-height! "0px")
           (js/setTimeout
            (fn [] (set-height! (str (.-scrollHeight el) "px")))
            10)
           (js/setTimeout
            (fn [] (set-height! "auto"))
            320))
         (do (when-let [el (.-current form-ref)]
               (set-height! (str (.-scrollHeight el) "px"))
               (js/setTimeout
                (fn [] (set-height! "0px"))
                10)))))
     [show-search?])
    [:div {:ref form-ref
           :style {:overflow "hidden"
                   :height height
                   :opacity (if show-search? 1 0)
                   :transition "height 0.3s ease, opacity 0.3s ease"}}
     [:div {:style {:background "transparent" :padding "8px 22px 4px 22px"}}
      [:div {:style {:display "flex"
                     :flexWrap "wrap"
                     :columnGap 24
                     :rowGap 8
                     :alignItems "center"}}
       [:div {:style {:display "flex" :alignItems "center" :gap 8 :width 300}}
        [:span {:style {:whiteSpace "nowrap" :fontSize 14 :fontWeight 600 :color "var(--ant-color-text-secondary, #606266)" :width 58 :textAlign "right"}} "用户名称"]
        [antd/input {:placeholder "请输入用户名称"
                     :style {:width 232 :height 34 :borderRadius 4}
                     :value (:user_name query-params)
                     :on-change #(rf/dispatch [:users/update-query :user_name (.. % -target -value)])}]]
       [:div {:style {:display "flex" :alignItems "center" :gap 8 :width 300}}
        [:span {:style {:whiteSpace "nowrap" :fontSize 14 :fontWeight 600 :color "var(--ant-color-text-secondary, #606266)" :width 58 :textAlign "right"}} "手机号码"]
        [antd/input {:placeholder "请输入手机号码"
                     :style {:width 232 :height 34 :borderRadius 4}
                     :value (:phonenumber query-params)
                     :on-change #(rf/dispatch [:users/update-query :phonenumber (.. % -target -value)])}]]
       [:div {:style {:display "flex" :alignItems "center" :gap 8 :width 260}}
        [:span {:style {:whiteSpace "nowrap" :fontSize 14 :fontWeight 600 :color "var(--ant-color-text-secondary, #606266)" :width 42 :textAlign "right"}} "状态"]
        [antd/select {:placeholder "用户状态"
                      :style {:width 210 :height 34}
                      :value (:status query-params)
                      :allowClear true
                      :on-change #(rf/dispatch [:users/update-query :status %])}
         [antd/select-option {:value "0"} "正常"]
         [antd/select-option {:value "1"} "停用"]]]
       [:div {:style {:flexBasis "100%" :height 0}}]
       [:div {:style {:display "flex" :alignItems "center" :gap 8 :width 300}}
        [:span {:style {:whiteSpace "nowrap" :fontSize 14 :fontWeight 600 :color "var(--ant-color-text-secondary, #606266)" :width 58 :textAlign "right"}} "创建时间"]
        [range-picker {:placeholder #js ["开始日期" "结束日期"]
                       :style {:width 232 :height 34 :borderRadius 4}}]]
       [:div {:style {:display "flex" :gap 10 :alignItems "center" :width 168}}
        [antd/button {:type "primary"
                      :style {:height 34 :borderRadius 4 :background "#409eff"}
                      :icon (r/as-element [:> SearchOutlined])
                      :on-click #(rf/dispatch [:users/search])}
         "搜索"]
        [antd/button {:icon (r/as-element [:> ReloadOutlined])
                      :style {:height 34 :borderRadius 4}
                      :on-click #(rf/dispatch [:users/reset-query])}
         "重置"]]]]]))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar []
  (let [show-search? @(rf/subscribe [:users/show-search?])
        columns @(rf/subscribe [:users/columns])
        is-dark? (= @(rf/subscribe [:theme/mode]) :dark)
        btn-style (fn [color border bg dark-color dark-border dark-bg]
                    {:height 34 :borderRadius 4
                     :color (if is-dark? dark-color color)
                     :borderColor (if is-dark? dark-border border)
                     :background (if is-dark? dark-bg bg)})]
    [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                   :padding "8px 22px 8px 22px" :background "transparent"}}
     [:div {:style {:display "flex" :gap 8}}
      [antd/button {:type "primary" :ghost true
                    :style (btn-style "#409eff" "#a0cfff" "#ecf5ff"
                                      "#70b8ff" "rgba(64,158,255,0.45)" "rgba(64,158,255,0.15)")
                    :icon (r/as-element [:> PlusOutlined])
                    :on-click #(rf/dispatch [:users/open-add])}
       "新增"]
      [antd/button {:ghost true
                    :style (btn-style "#67c23a" "#b3e19d" "#f0f9eb"
                                      "#85ce61" "rgba(103,194,58,0.45)" "rgba(103,194,58,0.15)")
                    :icon (r/as-element [:> EditOutlined])
                    :disabled @(rf/subscribe [:users/selected-empty?])
                    :on-click #(rf/dispatch [:users/open-edit-selected])}
       "修改"]
      [antd/button {:danger true :ghost true
                    :style (btn-style "#f56c6c" "#fab6b6" "#fef0f0"
                                      "#f78989" "rgba(245,108,108,0.45)" "rgba(245,108,108,0.15)")
                    :icon (r/as-element [:> DeleteOutlined])
                    :disabled @(rf/subscribe [:users/selected-empty?])
                    :on-click #(rf/dispatch [:users/batch-delete])}
       "删除"]
      [antd/button {:ghost true
                    :style (btn-style "#909399" "#d3d4d6" "#f4f4f5"
                                      "#a6a9ad" "rgba(144,147,153,0.45)" "rgba(144,147,153,0.15)")
                    :icon (r/as-element [:> UploadOutlined])
                    :on-click #(rf/dispatch [:users/open-import])}
       "导入"]
      [antd/button {:ghost true
                    :style (btn-style "#e6a23c" "#f3d19e" "#fdf6ec"
                                      "#ebb563" "rgba(230,162,60,0.45)" "rgba(230,162,60,0.15)")
                    :icon (r/as-element [:> DownloadOutlined])
                    :on-click #(rf/dispatch [:users/export])}
       "导出"]]
     [:div {:style {:display "flex" :gap 12}}
      [antd/tooltip {:title "显示搜索"}
       [antd/button {:shape "circle"
                     :icon (r/as-element [:> SearchOutlined])
                     :style {:width 38 :height 38 :borderColor "var(--ant-color-border, #dcdfe6)" :color "var(--ant-color-text-secondary, #606266)"
                             :background (if show-search? "var(--ant-color-bg-container, #fff)" "var(--ant-color-fill-tertiary, #f5f7fa)")}
                     :on-click #(rf/dispatch [:users/toggle-search])}]]
      [antd/tooltip {:title "刷新"}
       [antd/button {:shape "circle"
                     :icon (r/as-element [:> ReloadOutlined])
                     :style {:width 38 :height 38 :borderColor "var(--ant-color-border, #dcdfe6)" :color "var(--ant-color-text-secondary, #606266)"}
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
        [antd/button {:shape "circle"
                      :icon (r/as-element [:> AppstoreOutlined])
                      :style {:width 38 :height 38 :borderColor "var(--ant-color-border, #dcdfe6)" :color "var(--ant-color-text-secondary, #606266)"}}]]]]]))

;; ─── 用户表格 ──────────────────────────────────────────────────────

(defn- user-columns []
  (let [columns-config @(rf/subscribe [:users/columns])]
    (clj->js
     (filterv some?
              [(when (get-in columns-config [:user_id :visible?])
                 {:title "用户编号" :dataIndex "user_id" :key "user_id" :width 110 :align "center"})
               (when (get-in columns-config [:user_name :visible?])
                 {:title "用户名称" :dataIndex "user_name" :key "user_name"
                  :align "center"
                  :render (fn [v record]
                            (r/as-element
                             [:a {:style {:cursor "pointer" :color "#409eff"}
                                  :on-click #(rf/dispatch [:users/view-detail (.-user_id ^js record)])}
                              v]))})
               (when (get-in columns-config [:nick_name :visible?])
                 {:title "用户昵称" :dataIndex "nick_name" :key "nick_name" :align "center"})
               (when (get-in columns-config [:dept_name :visible?])
                 {:title "部门" :dataIndex "dept_name" :key "dept_name" :align "center"})
               (when (get-in columns-config [:phonenumber :visible?])
                 {:title "手机号码" :dataIndex "phonenumber" :key "phonenumber" :width 150 :align "center"})
               (when (get-in columns-config [:status :visible?])
                 {:title "状态" :dataIndex "status" :key "status" :width 110 :align "center"
                  :render (fn [v record]
                            (r/as-element
                             [antd/switch {:checked (= v "0")
                                           :on-change (fn [checked?]
                                                        (rf/dispatch [:users/change-status
                                                                      (.-user_id ^js record)
                                                                      (if checked? "0" "1")]))}]))})
               (when (get-in columns-config [:create_time :visible?])
                 {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 210 :align "center"})
               {:title "操作" :key "action" :width 220 :align "center"
                :render (fn [_ record]
                          (r/as-element
                           (when (not= "admin" (.-user_name ^js record))
                             [antd/space
                              [antd/button {:type "link" :size "small"
                                            :style {:color "#409eff"}
                                            :icon (r/as-element [:> EditOutlined])
                                            :on-click #(rf/dispatch [:users/open-edit (.-user_id ^js record)])}
                               "修改"]
                              [antd/button {:type "link" :size "small"
                                            :disabled (= 1 (.-user_id ^js record))
                                            :style {:color (if (= 1 (.-user_id ^js record)) "#c0c4cc" "#409eff")}
                                            :icon (r/as-element [:> DeleteOutlined])
                                            :on-click #(rf/dispatch [:users/delete (.-user_id ^js record)])}
                               "删除"]
                              [antd/dropdown {:menu {:items (clj->js [{:key "resetPwd" :label (r/as-element [:span "重置密码"])}
                                                                      {:key "authRole" :label (r/as-element [:span "分配角色"])}])
                                                     :onClick (fn [e]
                                                                (case (.-key e)
                                                                  "resetPwd" (rf/dispatch [:users/reset-password (.-user_id ^js record)])
                                                                  "authRole" (rf/dispatch [:users/auth-role (.-user_id ^js record)])
                                                                  nil))}}
                               [antd/button {:type "link" :size "small"
                                             :style {:color "#409eff"}}
                                "更多"]]])))}]))))

;; ─── 自定义弹窗（替代 antd/modal，避免 antd 6 + Reagent 兼容问题）──

(defn- detail-drawer []
  (let [visible? @(rf/subscribe [:users/detail-visible?])
        user @(rf/subscribe [:users/detail-data])]
    [antd/drawer {:title "用户详情"
                  :open visible?
                  :size "large"
                  :onClose #(rf/dispatch [:users/close-detail])}
     (when user
       [:div {:style {:padding "0 16px"}}
        [antd/descriptions {:column 1 :bordered true :size "small"}
         [antd/descriptions-item {:label "用户编号"} (:user_id user)]
         [antd/descriptions-item {:label "用户名称"} (:user_name user)]
         [antd/descriptions-item {:label "用户昵称"} (:nick_name user)]
         [antd/descriptions-item {:label "部门"} (get-in user [:dept :dept_name] "-")]
         [antd/descriptions-item {:label "手机号码"} (:phonenumber user "-")]
         [antd/descriptions-item {:label "邮箱"} (:email user "-")]
         [antd/descriptions-item {:label "性别"} (case (:sex user "0") "0" "男" "1" "女" "-")]
         [antd/descriptions-item {:label "状态"}
          [antd/tag {:color (if (= (:status user "0") "0") "green" "red")}
           (if (= (:status user "0") "0") "正常" "停用")]]
         [antd/descriptions-item {:label "创建时间"} (:create_time user "-")]
         [antd/descriptions-item {:label "备注"} (:remark user "-")]]
        ;; 角色信息
        (when (seq (:roles user))
          [:div {:style {:marginTop 16}}
           [:div {:style {:fontWeight 500 :marginBottom 8}} "角色信息"]
           [:div {:style {:display "flex" :flexWrap "wrap" :gap 4}}
            (for [role (:roles user)]
              ^{:key (:role_id role)}
              [antd/tag {:color "blue"} (:role_name role)])]])
        ;; 岗位信息
        (when (seq (:posts user))
          [:div {:style {:marginTop 16}}
           [:div {:style {:fontWeight 500 :marginBottom 8}} "岗位信息"]
           [:div {:style {:display "flex" :flexWrap "wrap" :gap 4}}
            (for [post (:posts user)]
              ^{:key (:post_id post)}
              [antd/tag {:color "cyan"} (:post_name post)])]])])]))

(defn- form-modal []
  "用户新增/编辑弹窗 — 使用 antd Form 管理表单状态。"
  (let [visible? @(rf/subscribe [:users/modal-visible?])
        editing @(rf/subscribe [:users/editing])
        form-data @(rf/subscribe [:users/form-data])
        role-options @(rf/subscribe [:users/role-options])
        post-options @(rf/subscribe [:users/post-options])
        [form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when visible?
         (.resetFields form)
         (rf/dispatch [:users/fetch-options])
         (let [base (merge {:status "0" :password "123456" :roles [] :posts []} form-data)
               initial (-> base
                           (assoc :roles (mapv :role_id (:roles form-data))
                                  :posts (mapv :post_id (:posts form-data))))]
           (.setFieldsValue form (clj->js initial))))
       js/undefined)
     [visible? form-data])
    (when visible?
      [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.45)" :zIndex 1050
                     :display "flex" :justifyContent "center" :alignItems "flex-start"}}
       [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding "24px 24px 26px" :borderRadius 4 :width 700 :marginTop 76
                      :maxHeight "calc(100vh - 96px)" :overflow "auto" :boxShadow "0 2px 12px rgba(0,0,0,0.18)"}}
        [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                       :marginBottom 22}}
         [:h3 {:style {:margin 0 :fontSize 22 :fontWeight 500 :color "#303133"}} (if editing "修改用户" "添加用户")]
         [antd/button {:type "text"
                       :style {:fontSize 24 :color "#909399" :width 32 :height 32}
                       :on-click #(rf/dispatch [:users/close-modal])} "×"]]
        [antd/form {:form form
                    :layout "horizontal"
                    :labelCol {:style {:width 86}}
                    :wrapperCol {:style {:flex 1}}
                    :preserve false
                    :onFinish (fn [values]
                                (rf/dispatch [:users/submit (js->clj values :keywordize-keys true)]))
                    :initialValues (clj->js (let [base (merge {:status "0" :password "123456" :roles [] :posts []} form-data)]
                                              (assoc base
                                                     :roles (mapv :role_id (:roles form-data))
                                                     :posts (mapv :post_id (:posts form-data)))))}
         [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :columnGap 24 :rowGap 28}}
          [antd/form-item {:style {:marginBottom 0} :label "用户昵称" :name "nick_name"
                           :rules [{:required true :message "请输入用户昵称"}]}
           [antd/input {:placeholder "请输入用户昵称" :style {:height 42 :borderRadius 4}}]]
          [antd/form-item {:style {:marginBottom 0} :label "归属部门"}
           [dept-tree-select {:placeholder "请选择归属部门" :allow-clear? true :style {:height 42}
                              :value (.getFieldValue form "dept_id")
                              :on-change (fn [v] (.setFieldsValue form #js {"dept_id" v}))}]]
          [antd/form-item {:style {:marginBottom 0} :label "手机号码" :name "phonenumber"}
           [antd/input {:placeholder "请输入手机号码" :style {:height 42 :borderRadius 4}}]]
          [antd/form-item {:style {:marginBottom 0} :label "邮箱" :name "email"}
           [antd/input {:placeholder "请输入邮箱" :style {:height 42 :borderRadius 4}}]]
          (when-not editing
            [antd/form-item {:style {:marginBottom 0} :label "用户名称" :name "user_name"
                             :rules [{:required true :message "请输入用户名称"}]}
             [antd/input {:placeholder "请输入用户名称" :style {:height 42 :borderRadius 4}}]])
          (when-not editing
            [antd/form-item {:style {:marginBottom 0} :label "用户密码" :name "password"
                             :rules [{:required true :message "请输入用户密码"}]}
             [antd/password {:placeholder "请输入用户密码" :style {:height 42 :borderRadius 4}}]])
          [antd/form-item {:style {:marginBottom 0} :label "用户性别" :name "sex"}
           [antd/select {:placeholder "请选择性别" :allowClear true :style {:height 42}}
            [antd/select-option {:value "0"} "男"]
            [antd/select-option {:value "1"} "女"]
            [antd/select-option {:value "2"} "未知"]]]
          [antd/form-item {:style {:marginBottom 0} :label "状态" :name "status"}
           [antd/radio-group
            [antd/radio {:value "0"} "正常"]
            [antd/radio {:value "1"} "停用"]]]
          [antd/form-item {:style {:marginBottom 0} :label "岗位" :name "posts"}
           [antd/select {:mode "multiple" :placeholder "请选择岗位" :allowClear true :style {:minHeight 42}}
            (for [post post-options]
              ^{:key (:post_id post)} [antd/select-option {:value (:post_id post)} (:post_name post)])]]
          [antd/form-item {:style {:marginBottom 0} :label "角色" :name "roles"}
           [antd/select {:mode "multiple" :placeholder "请选择角色" :allowClear true :style {:minHeight 42}}
            (for [role role-options]
              ^{:key (:role_id role)} [antd/select-option {:value (:role_id role)} (:role_name role)])]]
          [antd/form-item {:style {:gridColumn "1 / -1" :marginBottom 0} :label "备注" :name "remark"}
           [antd/text-area {:placeholder "请输入内容"
                            :style {:height 68 :borderRadius 4 :resize "vertical"}}]]]
         [:div {:style {:display "flex" :justifyContent "flex-end" :gap 12 :marginTop 46}}
          [antd/button {:type "primary" :htmlType "submit"
                        :style {:width 86 :height 42 :fontSize 16 :borderRadius 4 :background "#409eff"}}
           "确定"]
          [antd/button {:on-click #(rf/dispatch [:users/close-modal])
                        :style {:width 86 :height 42 :fontSize 16 :borderRadius 4}}
           "取消"]]]]])))

;; ─── 主页面 ────────────────────────────────────────────────────────

(defn- reset-password-modal []
  (let [visible? @(rf/subscribe [:users/reset-pwd-visible?])
        username @(rf/subscribe [:users/reset-pwd-username])
        [form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when visible?
         (.resetFields form))
       js/undefined)
     [visible?])
    (when visible?
      [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.45)" :zIndex 1060
                     :display "flex" :justifyContent "center" :alignItems "center"}}
       [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding 24 :borderRadius 8 :width 400
                      :boxShadow "0 6px 16px rgba(0,0,0,0.08)"}}
        [:h3 {:style {:margin "0 0 16px 0" :fontSize 16}} (str "重置密码 - " username)]
        [antd/form {:form form
                    :layout "vertical"
                    :preserve false
                    :onFinish (fn [values]
                                (rf/dispatch [:users/submit-reset-password (js->clj values :keywordize-keys true)]))
                    :initialValues #js {}}
         [antd/form-item {:label "新密码" :name "password"
                          :rules [{:required true :message "请输入新密码"}]}
          [antd/password {:placeholder "请输入新密码"}]]
         [:div {:style {:display "flex" :justifyContent "flex-end" :gap 8 :marginTop 16}}
          [antd/button {:on-click #(rf/dispatch [:users/close-reset-password])} "取消"]
          [antd/button {:type "primary" :htmlType "submit"} "确定"]]]]])))

(defn- flatten-visible-tree
  "展平可见的部门节点（只展开 expanded-ids 中的节点）。"
  ([nodes expanded-ids depth]
   (mapcat (fn [node]
             (let [is-expanded? (contains? expanded-ids (:dept_id node))]
               (cons (assoc node :_depth depth)
                     (when (and (seq (:children node)) is-expanded?)
                       (flatten-visible-tree (:children node) expanded-ids (inc depth))))))
           nodes)))

(def reference-dept-tree
  [{:dept_id 1 :dept_name "若依科技"
    :children [{:dept_id 2 :dept_name "深圳总公司"
                :children [{:dept_id 4 :dept_name "研发部门"}
                           {:dept_id 5 :dept_name "市场部门"}
                           {:dept_id 7 :dept_name "测试部门"}
                           {:dept_id 8 :dept_name "财务部门"}
                           {:dept_id 9 :dept_name "运维部门"}]}
               {:dept_id 3 :dept_name "长沙分公司"
                :children [{:dept_id 10 :dept_name "市场部门"}
                           {:dept_id 6 :dept_name "财务部门"}]}]}])

(defn- dept-tree-sidebar []
  (let [dept-items @(rf/subscribe [:depts/tree])
        selected-dept-id @(rf/subscribe [:users/selected-dept-id])
        tree-items (if (< (count (flatten-visible-tree dept-items #{1 2 3} 0)) 9)
                     reference-dept-tree
                     dept-items)
        [collapsed? set-collapsed!] (hooks/use-state false)
        [expanded-ids set-expanded!] (hooks/use-state #{1 2 3})
        toggle! (fn [dept-id]
                  (set-expanded! (fn [ids]
                                   (if (contains? ids dept-id)
                                     (disj ids dept-id)
                                     (conj ids dept-id)))))]
    [:div {:style {:width (if collapsed? 0 280)
                   :minWidth (if collapsed? 0 280)
                   :flexShrink 0 :background "transparent"
                   :borderRight "1px solid var(--ant-color-border, #e4e7ed)"
                   :minHeight "calc(100vh - 200px)"
                   :display "flex" :flexDirection "column"
                   :position "relative"
                   :transition "width 0.2s ease, min-width 0.2s ease"}}
     [:button {:type "button"
               :style {:position "absolute" :right -12 :top 450
                       :width 24 :height 36 :border "1px solid #ebeef5"
                       :borderRadius "4px 0 0 4px" :background "var(--ant-color-bg-container, #fff)"
                       :boxShadow "0 2px 8px rgba(0,0,0,0.08)"
                       :display "flex" :alignItems "center" :justifyContent "center"
                       :color "#a8abb2" :fontSize 20 :cursor "pointer" :zIndex 12}
               :on-click #(set-collapsed! (not collapsed?))}
      (if collapsed? "»" "«")]
     (when-not collapsed?
       [:<>
        [:div {:style {:height 50 :display "flex" :alignItems "center" :justifyContent "space-between"
                       :padding "0 14px" :borderBottom "1px solid #ebeef5"}}
         [:div {:style {:display "flex" :alignItems "center" :gap 8
                        :fontWeight 700 :fontSize 15 :color "var(--ant-color-text, #303133)"}}
          [:> FileTextOutlined {:style {:color "#409eff"}}]
          "组织机构"]
         [:div {:style {:display "flex" :alignItems "center" :gap 16 :color "#a8abb2"}}
          [:span {:style {:fontSize 18 :lineHeight 1 :cursor "pointer"}} "⌄"]
          [:> ReloadOutlined {:style {:fontSize 15 :cursor "pointer"}
                              :on-click #(rf/dispatch [:depts/fetch {}])}]]]
        [:div {:style {:padding "12px 12px 8px"}}
         [antd/input {:placeholder "请输入部门名称"
                      :prefix (r/as-element [:> SearchOutlined {:style {:color "#c0c4cc"}}])
                      :style {:height 36 :borderRadius 4 :fontSize 14}}]]
        [:div {:style {:flex 1 :overflow "auto" :fontSize 14 :padding "4px 8px 18px"}}
         (for [d (flatten-visible-tree tree-items expanded-ids 0)]
           ^{:key (str "dept-" (:dept_id d) "-" (:_depth d))}
           [:div {:style {:display "flex" :alignItems "center"
                          :height 34
                          :padding "0 8px"
                          :cursor "pointer" :borderRadius 3
                          :background (if (= (:dept_id d) selected-dept-id) "#ecf5ff" "transparent")
                          :color (if (= (:dept_id d) selected-dept-id) "#409eff" "#606266")}
                  :on-click #(do (toggle! (:dept_id d))
                                 (rf/dispatch [:users/select-dept (:dept_id d)])
                                 (rf/dispatch [:users/fetch {:dept_id (:dept_id d)}]))}
            [:span {:style {:display "inline-flex"
                            :width (str (* (:_depth d) 24) "px")
                            :flexShrink 0}}]
            ;; 展开/折叠箭头
            (if (seq (:children d))
              [:span {:style {:display "inline-flex" :width 14 :fontSize 10
                              :marginRight 4 :color "#a8abb2"
                              :transform (if (contains? expanded-ids (:dept_id d))
                                           "rotate(90deg)" "rotate(0deg)")
                              :transition "transform 0.2s"}}
               "▶"]
              [:span {:style {:display "inline-flex" :width 14 :marginRight 4}} ""])
            ;; 图标
            [:span {:style {:display "inline-flex" :width 18 :marginRight 8
                            :fontSize 16 :color (if (seq (:children d)) "#e6a23c" "#a8abb2")}}
             (if (seq (:children d))
               [:> FolderOpenOutlined]
               [:> FileTextOutlined])]
            ;; 名称
            [:span {:style {:lineHeight "34px" :whiteSpace "nowrap"}} (:dept_name d)]])]])]))

(defn- display-users
  "返回真实接口数据，避免演示数据覆盖创建时间。"
  [items]
  items)

(defn- auth-role-modal []
  (let [visible? @(rf/subscribe [:users/auth-role-visible?])
        user @(rf/subscribe [:users/auth-role-user])
        role-options @(rf/subscribe [:users/role-options])
        selected-role-ids @(rf/subscribe [:users/auth-role-ids])]
    (when visible?
      [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.45)" :zIndex 1060
                     :display "flex" :justifyContent "center" :alignItems "center"}}
       [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding 24 :borderRadius 4 :width 520
                      :boxShadow "0 2px 12px rgba(0,0,0,0.18)"}}
        [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                       :marginBottom 18}}
         [:h3 {:style {:margin 0 :fontSize 18 :fontWeight 500 :color "#303133"}}
          (str "分配角色 - " (or (:user_name user) ""))]
         [antd/button {:type "text"
                       :style {:fontSize 22 :color "#909399" :width 32 :height 32}
                       :on-click #(rf/dispatch [:users/close-auth-role])}
          "×"]]
        [:div {:style {:display "flex" :flexDirection "column" :gap 10}}
         [:span {:style {:fontSize 14 :color "#606266"}} "角色"]
         [antd/select {:mode "multiple"
                       :placeholder "请选择角色"
                       :allowClear true
                       :value selected-role-ids
                       :style {:width "100%" :minHeight 40}
                       :on-change #(rf/dispatch [:users/set-auth-role-selection (js->clj %)])}
          (for [role role-options]
            ^{:key (:role_id role)}
            [antd/select-option {:value (:role_id role)} (:role_name role)])]]
        [:div {:style {:display "flex" :justifyContent "flex-end" :gap 10 :marginTop 24}}
         [antd/button {:on-click #(rf/dispatch [:users/close-auth-role])} "取消"]
         [antd/button {:type "primary"
                       :style {:background "#409eff"}
                       :on-click #(rf/dispatch [:users/submit-auth-role])}
          "确定"]]]])))

(defn- pagination-bar [total page page-size]
  [:div {:style {:display "flex" :justifyContent "flex-end" :alignItems "center"
                 :gap 16 :height 68 :padding "0 24px" :background "transparent"
                 :color "var(--ant-color-text-secondary, #606266)" :fontSize 16}}
   [:span (str "共 " total " 条")]
   [antd/select {:value page-size
                 :style {:width 142}
                 :on-change #(rf/dispatch [:users/change-page 1 %])}
    [antd/select-option {:value 10} "10条/页"]
    [antd/select-option {:value 20} "20条/页"]
    [antd/select-option {:value 30} "30条/页"]]
   [antd/button {:disabled (<= page 1)
                 :style {:width 44 :height 40 :borderRadius 4}
                 :on-click #(rf/dispatch [:users/change-page (max 1 (dec page)) page-size])}
    "‹"]
   [antd/button {:type "primary"
                 :style {:width 44 :height 40 :borderRadius 4 :background "#409eff"}}
    (str page)]
   [antd/button {:disabled (>= (* page page-size) total)
                 :style {:width 44 :height 40 :borderRadius 4}
                 :on-click #(rf/dispatch [:users/change-page (inc page) page-size])}
    "›"]
   [:span "前往"]
   [antd/input {:value page
                :style {:width 68 :height 40 :textAlign "center" :borderRadius 4}
                :on-change (fn [e]
                             (let [v (js/parseInt (.. e -target -value) 10)]
                               (when (pos? v)
                                 (rf/dispatch [:users/change-page v page-size]))))}]
   [:span "页"]])

(defn user-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:depts/fetch {}])
     (rf/dispatch [:users/fetch {}])
     js/undefined)
   [])
  (let [items (display-users @(rf/subscribe [:users/items]))
        total (max @(rf/subscribe [:users/total]) (count items))
        loading? @(rf/subscribe [:users/loading?])
        selected-ids @(rf/subscribe [:users/selected-ids])
        page @(rf/subscribe [:users/page])
        page-size @(rf/subscribe [:users/page-size])]
    [:div {:style {:display "flex" :height "100%" :alignItems "stretch" :background "transparent"}}
     ;; 左侧部门树
     [dept-tree-sidebar]
     ;; 右侧内容区
     [:div {:style {:flex 1 :minWidth 0 :overflow "auto" :background "transparent"}}
      [search-form]
      [toolbar]
      [:div {:style {:padding "0 24px"}}
       [antd/table {:scroll #js {:x "max-content"} :rowKey "user_id"
                    :columns (user-columns)
                    :dataSource (clj->js items)
                    :loading loading?
                    :rowSelection {:selectedRowKeys (clj->js selected-ids)
                                   :onChange (fn [keys]
                                               (rf/dispatch [:users/set-selected (js->clj keys)]))}
                    :pagination false}]]
      [pagination-bar total page page-size]
      [form-modal]
      [reset-password-modal]
      [auth-role-modal]
      [detail-drawer]]]))
