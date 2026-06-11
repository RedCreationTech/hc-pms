(ns com.ruoyi.rouyi.frontend.pages.layout
  "主布局页面"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    ["antd" :refer [Layout Menu Button Space]]
    ["@ant-design/icons" :refer [DashboardOutlined SettingOutlined
                                 FileTextOutlined UserOutlined
                                 SunOutlined MoonOutlined]]
    [com.ruoyi.rouyi.frontend.pages.dashboard :as dashboard]
    [com.ruoyi.rouyi.frontend.pages.user :as user]
    [com.ruoyi.rouyi.frontend.pages.online :as online]
    [com.ruoyi.rouyi.frontend.pages.job :as job]
    [com.ruoyi.rouyi.frontend.pages.profile :as profile]
    [com.ruoyi.rouyi.frontend.pages.dict :as dict]
    [com.ruoyi.rouyi.frontend.pages.config :as config]
    [com.ruoyi.rouyi.frontend.pages.oper-log :as oper-log]
    [com.ruoyi.rouyi.frontend.pages.login-log :as login-log]))

(defn main-layout []
  (let [collapsed (r/atom false)]
    (fn []
      (let [theme-mode @(rf/subscribe [:theme/mode])
            user @(rf/subscribe [:auth/user])
            page @(rf/subscribe [:page])
            menu-items #js [#js {:key "dashboard" :icon (r/as-element [:> DashboardOutlined]) :label "首页"}
                           #js {:key "system" :icon (r/as-element [:> SettingOutlined]) :label "系统管理"
                                :children #js [#js {:key "user" :label "用户管理"}
                                               #js {:key "role" :label "角色管理"}
                                               #js {:key "menu" :label "菜单管理"}
                                               #js {:key "dept" :label "部门管理"}
                                               #js {:key "post" :label "岗位管理"}
                                               #js {:key "dict" :label "字典管理"}
                                               #js {:key "config" :label "参数管理"}]}
                           #js {:key "monitor" :icon (r/as-element [:> FileTextOutlined]) :label "系统监控"
                                :children #js [#js {:key "oper-log" :label "操作日志"}
                                               #js {:key "login-log" :label "登录日志"}
                                               #js {:key "online" :label "在线用户"}
                                               #js {:key "job" :label "定时任务"}]}
                           #js {:key "profile" :icon (r/as-element [:> UserOutlined]) :label "个人中心"}]]
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
                    :defaultOpenKeys #js ["system" "monitor"]
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
          [:> Layout.Content {:style {:margin 24} :key (name page)}
           (case page
             :dashboard [dashboard/dashboard-page]
             :user [user/user-page]
             :online [online/online-page]
             :job [job/job-page]
             :profile [profile/profile-page]
             :dict [dict/dict-page]
             :config [config/config-page]
             :oper-log [oper-log/oper-log-page]
             :login-log [login-log/login-log-page]
             [:div {:style {:padding 48 :textAlign "center" :color "#999" :fontSize 16}}
              "页面建设中"])]]]))))
