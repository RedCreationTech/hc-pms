(ns com.ruoyi.rouyi.frontend.pages.layout
  "主布局页面，包含多Tab支持。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["antd" :refer [Layout Menu Button Space Badge Avatar Dropdown]]
   ["@ant-design/icons" :refer [DashboardOutlined SettingOutlined
                                FileTextOutlined UserOutlined
                                SunOutlined MoonOutlined
                                CloseOutlined HomeOutlined
                                TeamOutlined SafetyOutlined
                                ApartmentOutlined TagOutlined
                                BookOutlined ToolOutlined
                                MonitorOutlined ScheduleOutlined
                                DatabaseOutlined CloudOutlined
                                CodeOutlined FormOutlined
                                ProfileOutlined BellOutlined
                                ContainerOutlined KeyOutlined
                                SearchOutlined GithubOutlined
                                QuestionCircleOutlined ExpandOutlined
                                CompressOutlined LogoutOutlined
                                MenuFoldOutlined MenuUnfoldOutlined]]
   [com.ruoyi.rouyi.frontend.components.theme-switcher :as theme-switcher]
   [com.ruoyi.rouyi.frontend.pages.dashboard :as dashboard]
   [com.ruoyi.rouyi.frontend.pages.user :as user]
   [com.ruoyi.rouyi.frontend.pages.role :as role]
   [com.ruoyi.rouyi.frontend.pages.menu :as menu]
   [com.ruoyi.rouyi.frontend.pages.dept :as dept]
   [com.ruoyi.rouyi.frontend.pages.post :as post]
   [com.ruoyi.rouyi.frontend.pages.notice :as notice]
   [com.ruoyi.rouyi.frontend.pages.online :as online]
   [com.ruoyi.rouyi.frontend.pages.job :as job]
   [com.ruoyi.rouyi.frontend.pages.profile :as profile]
   [com.ruoyi.rouyi.frontend.pages.dict :as dict]
   [com.ruoyi.rouyi.frontend.pages.config :as config]
   [com.ruoyi.rouyi.frontend.pages.oper-log :as oper-log]
   [com.ruoyi.rouyi.frontend.pages.login-log :as login-log]
   [com.ruoyi.rouyi.frontend.pages.server :as server]
   [com.ruoyi.rouyi.frontend.pages.cache :as cache]
   [com.ruoyi.rouyi.frontend.pages.gen :as gen]
   [com.ruoyi.rouyi.frontend.pages.form-builder :as form-builder]
   [com.ruoyi.rouyi.frontend.pages.file-manager :as file-manager]
   [com.ruoyi.rouyi.frontend.components.icon-picker :as icon-picker]))

;; ─── Tab 组件 ──────────────────────────────────────────────────────

(defn- tab-item
  "单个Tab项组件"
  [{:keys [key label closable active?]}]
  [:div {:style {:display "inline-flex"
                 :alignItems "center"
                 :padding "6px 16px"
                 :margin "0 2px"
                 :background (if active? "var(--ant-color-primary, #1677ff)" "var(--ant-color-bg-elevated, #f5f5f5)")
                 :color (if active? "#fff" "var(--ant-color-text, #666)")
                 :borderRadius "4px 4px 0 0"
                 :cursor "pointer"
                 :fontSize 13
                 :transition "all 0.2s"
                 :border (when active? (str "1px solid var(--ant-color-primary, #1677ff)"))
                 :borderBottom (when active? "1px solid var(--ant-color-bg-layout, #fff)")
                 :whiteSpace "nowrap"}
         :on-click #(do (rf/dispatch [:tabs/activate key]) (rf/dispatch [:navigate (keyword key)]))}
   (when (= key :dashboard)
     [:> HomeOutlined {:style {:marginRight 6 :fontSize 12}}])
   [:span label]
   (when (and closable (not= key :dashboard))
     [:> CloseOutlined {:style {:marginLeft 8 :fontSize 10 :opacity 0.6}
                        :on-click (fn [e]
                                    (.stopPropagation e)
                                    (rf/dispatch [:tabs/close key]))}])])

(defn- tab-bar
  "Tab栏组件"
  []
  (let [tabs @(rf/subscribe [:tabs/items])
        active @(rf/subscribe [:tabs/active])]
    [:div {:style {
                   :borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"
                   :padding "8px 16px 0"
                   :display "flex"
                   :alignItems "flex-end"
                   :overflowX "auto"
                   :whiteSpace "nowrap"
                   :minHeight 44}}
     (for [tab tabs]
       ^{:key (:key tab)}
       [tab-item (assoc tab :active? (= (:key tab) active))])]))

;; ─── 动态菜单构建 ──────────────────────────────────────────────────────

(defn- filter-visible-menus
  "过滤掉 F 类型（按钮权限）菜单，只保留 M 目录和 C 菜单。"
  [menus]
  (->> menus
       (filter #(contains? #{"M" "C"} (:menu_type %)))
       (mapv (fn [m]
               (if (seq (:children m))
                 (assoc m :children (filter-visible-menus (:children m)))
                 m)))))

(defn- menu->antd-items
  "将后端菜单树转换为 antd Menu 的 items 结构。"
  [menus]
  (clj->js
   (mapv (fn [m]
           (let [item {:key (:path m)
                       :label (:menu_name m)}
                 icon-el (when (and (:icon m) (not= (:icon m) "#"))
                           (icon-picker/icon-element (:icon m) {:style {:fontSize 14}}))]
             (cond-> item
               icon-el
               (assoc :icon (r/as-element icon-el))
               (seq (:children m))
               (assoc :children (menu->antd-items (:children m))))))
         menus)))

(defn- page-labels
  "从菜单树递归提取页面路径到标签的映射。"
  [menus]
  (reduce (fn [acc m]
            (let [acc (assoc acc (keyword (:path m)) (:menu_name m))]
              (if (seq (:children m))
                (merge acc (page-labels (:children m)))
                acc)))
          {}
          menus))

;; ─── 主布局 ────────────────────────────────────────────────────────

(defn main-layout []
  (let [collapsed (r/atom false)]
    (fn []
      (let [theme-mode @(rf/subscribe [:theme/mode])
            user @(rf/subscribe [:auth/user])
            page @(rf/subscribe [:page])
            user-menus (or (seq (:menus user))
                                   [{:path "dashboard" :menu_name "首页" :icon "dashboard"}])
            filtered-menus (filter-visible-menus user-menus)
            menu-items (menu->antd-items filtered-menus)
            labels (page-labels user-menus)]
        [:> Layout {:style {:minHeight "100vh"}}
         [:> Layout.Sider {:collapsible true
                           :collapsed @collapsed
                           :onCollapse (fn [v] (reset! collapsed v))
                           :theme (if (= theme-mode :dark) "dark" "light")
                           :width 220}
          [:div {:style {:height 64 :display "flex" :alignItems "center"
                         :justifyContent "center" :fontSize 18 :fontWeight 600
                         :color (if (= theme-mode :dark) "#fff" "#000")
                         :borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"}}
           (if @collapsed "RY" "若依管理系统")]
          [:> Menu {:theme (if (= theme-mode :dark) "dark" "light")
                    :mode "inline"
                    :inlineCollapsed @collapsed
                    :selectedKeys (clj->js [(name page)])
                    :defaultOpenKeys #js ["system" "monitor" "tool"]
                    :items menu-items
                    :onClick (fn [e]
                               (let [k (.-key e)
                                     page (keyword k)]
                                 (rf/dispatch [:navigate page])
                                 (rf/dispatch [:tabs/add page (get labels page "页面")])))}]]
         ;; Main area
         [:> Layout
          [:> Layout.Header {:style {:padding "0 24px"
                                     :display "flex" :justifyContent "space-between"
                                     :alignItems "center" :height 64
                                     :borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"}}
           [:span {:style {:fontSize 16 :fontWeight 500}} "若依管理系统"]
           [:div {:style {:display "flex" :alignItems "center" :gap 4}}
            ;; 搜索
            [:> Button {:type "text" :icon (r/as-element [:> SearchOutlined])}]
            ;; GitHub
            [:> Button {:type "text" :icon (r/as-element [:> GithubOutlined])
                        :onClick #(js/window.open "https://github.com/RedCreationTech/rouyi_clojure" "_blank")}]
            ;; 文档
            [:> Button {:type "text" :icon (r/as-element [:> QuestionCircleOutlined])}]
            ;; 全屏
            [:> Button {:type "text" :icon (r/as-element [:> ExpandOutlined])
                        :onClick #(let [doc js/document.documentElement]
                                    (if (.-fullscreenElement js/document)
                                      (.exitFullscreen js/document)
                                      (.requestFullscreen doc)))}]
            ;; 主题设置
            [theme-switcher/theme-switcher-button]
            ;; 通知
            [:> Badge {:count 0 :size "small"}
             [:> Button {:type "text" :icon (r/as-element [:> BellOutlined])}]]
            ;; 头像 + 下拉菜单
            [:> Dropdown {:menu {:items (clj->js [{:key "profile" :label "个人中心"}
                                                   {:key "logout" :label "退出登录" :danger true}])
                                 :onClick (fn [e]
                                            (case (.-key e)
                                              "profile" (rf/dispatch [:navigate :profile])
                                              "logout" (rf/dispatch [:auth/logout])
                                              nil))}}
             [:div {:style {:display "flex" :alignItems "center" :gap 8 :cursor "pointer" :padding "0 8px"}}
              [:> Avatar {:size 28 :icon (r/as-element [:> UserOutlined])}]
              [:span {:style {:fontSize 14}} (get-in user [:user :nick_name] "管理员")]]]]]
          ;; Tab 栏
          [tab-bar]
          ;; 内容区
          [:> Layout.Content {:style {:margin 24} :key (name page)}
           (case page
             :dashboard [dashboard/dashboard-page]
             :user [user/user-page]
             :role [role/role-page]
             :menu [menu/menu-page]
             :dept [dept/dept-page]
             :post [post/post-page]
             :notice [notice/notice-page]
             :online [online/online-page]
             :job [job/job-page]
             :profile [profile/profile-page]
             :dict [dict/dict-page]
             :config [config/config-page]
             :oper-log [oper-log/oper-log-page]
             :login-log [login-log/login-log-page]
             :server [server/server-page]
             :cache [cache/cache-page]
             :gen [gen/gen-page]
             :build [form-builder/form-builder-page]
             :file [file-manager/file-manager-page]
             [:div {:style {:padding 48 :textAlign "center" :color "#999" :fontSize 16}}
              "页面建设中"])]]]))))
