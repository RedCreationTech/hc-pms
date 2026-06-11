(ns com.ruoyi.rouyi.frontend.pages.layout
  "主布局页面，包含侧边栏、顶部栏和内容区。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]
    [com.ruoyi.rouyi.frontend.pages.dashboard :as dashboard]
    [com.ruoyi.rouyi.frontend.pages.user :as user]
    [com.ruoyi.rouyi.frontend.pages.online :as online]
    [com.ruoyi.rouyi.frontend.pages.job :as job]
    [com.ruoyi.rouyi.frontend.pages.profile :as profile]
    [com.ruoyi.rouyi.frontend.pages.dict :as dict]
    [com.ruoyi.rouyi.frontend.pages.config :as config]
    [com.ruoyi.rouyi.frontend.pages.oper-log :as oper-log]
    [com.ruoyi.rouyi.frontend.pages.login-log :as login-log]))

(defn- menu-items []
  #js [{:key "dashboard" :icon (r/as-element [antd/dashboard-icon]) :label "首页"}
       {:key "system" :icon (r/as-element [antd/setting-icon]) :label "系统管理"
        :children #js [{:key "user" :label "用户管理"}
                        {:key "role" :label "角色管理"}
                        {:key "menu" :label "菜单管理"}
                        {:key "dept" :label "部门管理"}
                        {:key "post" :label "岗位管理"}
                        {:key "dict" :label "字典管理"}
                        {:key "config" :label "参数管理"}]}
       {:key "monitor" :icon (r/as-element [antd/file-text-icon]) :label "系统监控"
        :children #js [{:key "oper-log" :label "操作日志"}
                        {:key "login-log" :label "登录日志"}
                        {:key "online" :label "在线用户"}
                        {:key "job" :label "定时任务"}]}
       {:key "profile" :icon (r/as-element [antd/user-icon]) :label "个人中心"}])

(defn main-layout []
  (let [collapsed (r/atom false)
        page @(rf/subscribe [:page])
        user @(rf/subscribe [:auth/user])]
    (fn []
      [antd/layout {:style {:minHeight "100vh"}}
       [antd/layout-sider {:collapsible true :collapsed @collapsed
                           :onCollapse #(reset! collapsed %)}
        [:div {:style {:height 32 :margin 16 :background "rgba(255,255,255,0.2)"
                       :color "#fff" :textAlign "center" :lineHeight "32px"
                       :overflow "hidden"}}
         (if @collapsed "RY" "若依管理系统")]
        [antd/menu {:theme "dark" :mode "inline"
                    :defaultSelectedKeys #js ["dashboard"]
                    :items (menu-items)
                    :onClick (fn [e]
                               (let [key (.-key e)]
                                 (rf/dispatch [:navigate (keyword key)])))}]]
       [antd/layout
        [antd/layout-header {:style {:background "#fff" :padding "0 24px"
                                     :display "flex" :justifyContent "space-between"
                                     :alignItems "center"}}
         [:span "若依管理系统"]
         [antd/space
          [:span (get-in user [:user :nick_name] "管理员")]
          [antd/button {:type "link" :onClick #(rf/dispatch [:auth/logout])}
           "退出"]]]
        [antd/layout-content {:style {:margin 16 :padding 24 :background "#fff"}}
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
           [:div "页面建设中"])]]])))
