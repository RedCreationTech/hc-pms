(ns com.ruoyi.rouyi.frontend.pages.layout
  "主布局页面，包含多Tab支持。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    ["antd" :refer [Layout Menu Button Space]]
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
                                 ContainerOutlined KeyOutlined]]
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
    [com.ruoyi.rouyi.frontend.pages.cache :as cache]))

;; ─── Tab 组件 ──────────────────────────────────────────────────────

(defn- tab-item
  "单个Tab项组件"
  [{:keys [key label closable active?]}]
  [:div {:style {:display "inline-flex"
                 :alignItems "center"
                 :padding "6px 16px"
                 :margin "0 2px"
                 :background (if active? "#1677ff" "#f5f5f5")
                 :color (if active? "#fff" "#666")
                 :borderRadius "4px 4px 0 0"
                 :cursor "pointer"
                 :fontSize 13
                 :transition "all 0.2s"
                 :border (when active? "1px solid #1677ff")
                 :borderBottom (when active? "1px solid #fff")
                 :whiteSpace "nowrap"}
          :on-click #(rf/dispatch [:tabs/activate key])}
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
    [:div {:style {:background "#fff"
                   :borderBottom "1px solid #f0f0f0"
                   :padding "8px 16px 0"
                   :display "flex"
                   :alignItems "flex-end"
                   :overflowX "auto"
                   :whiteSpace "nowrap"
                   :minHeight 44}}
     (for [tab tabs]
       ^{:key (:key tab)}
       [tab-item (assoc tab :active? (= (:key tab) active))])]))

;; ─── 主布局 ────────────────────────────────────────────────────────

(defn main-layout []
  (let [collapsed (r/atom false)]
    (fn []
      (let [theme-mode @(rf/subscribe [:theme/mode])
            user @(rf/subscribe [:auth/user])
            page @(rf/subscribe [:page])
            menu-items #js [#js {:key "dashboard" :icon (r/as-element [:> DashboardOutlined]) :label "首页"}
                           #js {:key "system" :icon (r/as-element [:> SettingOutlined]) :label "系统管理"
                                :children #js [#js {:key "user" :icon (r/as-element [:> UserOutlined]) :label "用户管理"}
                                               #js {:key "role" :icon (r/as-element [:> SafetyOutlined]) :label "角色管理"}
                                               #js {:key "menu" :icon (r/as-element [:> BookOutlined]) :label "菜单管理"}
                                               #js {:key "dept" :icon (r/as-element [:> ApartmentOutlined]) :label "部门管理"}
                                               #js {:key "post" :icon (r/as-element [:> ContainerOutlined]) :label "岗位管理"}
                                               #js {:key "dict" :icon (r/as-element [:> TagOutlined]) :label "字典管理"}
                                               #js {:key "config" :icon (r/as-element [:> ToolOutlined]) :label "参数管理"}
                                               #js {:key "notice" :icon (r/as-element [:> BellOutlined]) :label "通知公告"}]}
                           #js {:key "monitor" :icon (r/as-element [:> MonitorOutlined]) :label "系统监控"
                                :children #js [#js {:key "oper-log" :icon (r/as-element [:> FileTextOutlined]) :label "操作日志"}
                                               #js {:key "login-log" :icon (r/as-element [:> KeyOutlined]) :label "登录日志"}
                                               #js {:key "online" :icon (r/as-element [:> TeamOutlined]) :label "在线用户"}
                                               #js {:key "job" :icon (r/as-element [:> ScheduleOutlined]) :label "定时任务"}
                                               #js {:key "server" :icon (r/as-element [:> CloudOutlined]) :label "服务监控"}
                                               #js {:key "cache" :icon (r/as-element [:> DatabaseOutlined]) :label "缓存监控"}]}
                           #js {:key "tool" :icon (r/as-element [:> ToolOutlined]) :label "系统工具"
                                :children #js [#js {:key "gen" :icon (r/as-element [:> CodeOutlined]) :label "代码生成"}
                                               #js {:key "build" :icon (r/as-element [:> FormOutlined]) :label "表单构建"}]}
                           #js {:key "profile" :icon (r/as-element [:> ProfileOutlined]) :label "个人中心"}]]
        [:> Layout {:style {:minHeight "100vh"}}
         [:> Layout.Sider {:collapsible true
                           :collapsed @collapsed
                           :onCollapse (fn [v] (reset! collapsed v))
                           :theme (if (= theme-mode :dark) "dark" "light")
                           :width 220}
          [:div {:style {:height 64 :display "flex" :alignItems "center"
                         :justifyContent "center" :fontSize 18 :fontWeight 600
                         :color (if (= theme-mode :dark) "#fff" "#000")
                         :borderBottom (if (= theme-mode :dark) "1px solid #303030" "1px solid #f0f0f0")}}
           (if @collapsed "RY" "若依管理系统")]
          [:> Menu {:theme (if (= theme-mode :dark) "dark" "light")
                    :mode "inline"
                    :inlineCollapsed @collapsed
                    :selectedKeys (clj->js [(name page)])
                    :defaultOpenKeys #js ["system" "monitor" "tool"]
                    :items menu-items
                    :onClick (fn [e]
                               (let [k (.-key e)]
                                 (rf/dispatch [:navigate (keyword k)])))}]]
         ;; Main area
         [:> Layout
          [:> Layout.Header {:style {:background "#fff" :padding "0 24px"
                                     :display "flex" :justifyContent "space-between"
                                     :alignItems "center" :height 64
                                     :borderBottom "1px solid #f0f0f0"}}
           [:span {:style {:fontSize 16 :fontWeight 500}} "若依管理系统"]
           [:> Space
            [:> Button {:type "text"
                        :icon (r/as-element (if (= theme-mode :dark)
                                              [:> SunOutlined]
                                              [:> MoonOutlined]))
                        :onClick (fn [] (rf/dispatch [:theme/toggle-mode]))}]
            [:span (get-in user [:user :nick_name] "管理员")]
            [:> Button {:type "link" :onClick (fn [] (rf/dispatch [:auth/logout]))} "退出"]]]
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
             [:div {:style {:padding 48 :textAlign "center" :color "#999" :fontSize 16}}
              "页面建设中"])]]]))))
