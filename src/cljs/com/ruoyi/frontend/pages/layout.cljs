(ns com.ruoyi.frontend.pages.layout
  "主布局页面，包含多Tab支持。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   [com.ruoyi.frontend.antd :as antd]
   ["antd" :refer [Layout Menu Button Space Badge Avatar Dropdown Divider]]
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
                                LeftOutlined RightOutlined
                                ReloadOutlined DownOutlined
                                CompressOutlined LogoutOutlined
                                MenuFoldOutlined MenuUnfoldOutlined]]
   [com.ruoyi.frontend.router :as router]
   [com.ruoyi.frontend.components.theme-switcher :as theme-switcher]
   [com.ruoyi.frontend.pages.dashboard :as dashboard]
   [com.ruoyi.frontend.pages.user :as user]
   [com.ruoyi.frontend.pages.role :as role]
   [com.ruoyi.frontend.pages.menu :as menu]
   [com.ruoyi.frontend.pages.dept :as dept]
   [com.ruoyi.frontend.pages.post :as post]
   [com.ruoyi.frontend.pages.notice :as notice]
   [com.ruoyi.frontend.pages.online :as online]
   [com.ruoyi.frontend.pages.job :as job]
   [com.ruoyi.frontend.pages.profile :as profile]
   [com.ruoyi.frontend.pages.dict :as dict]
   [com.ruoyi.frontend.pages.config :as config]
   [com.ruoyi.frontend.pages.oper-log :as oper-log]
   [com.ruoyi.frontend.pages.login-log :as login-log]
   [com.ruoyi.frontend.pages.server :as server]
   [com.ruoyi.frontend.pages.cache :as cache]
   [com.ruoyi.frontend.pages.datasource :as datasource]
   [com.ruoyi.frontend.pages.gen :as gen]
   [com.ruoyi.frontend.pages.swagger :as swagger]
   [com.ruoyi.frontend.pages.form-builder :as form-builder]
   [com.ruoyi.frontend.pages.file-manager :as file-manager]
   [com.ruoyi.frontend.components.icon-picker :as icon-picker]))

;; ─── Tab 组件 ──────────────────────────────────────────────────────

(defn- tab-context-menu
  "标签页右键菜单项"
  [key has-others? has-right?]
  (clj->js
   [{:key "close-current" :label "关闭当前" :disabled (= key :dashboard)}
    {:key "close-others" :label "关闭其他" :disabled (not has-others?)}
    {:key "close-right" :label "关闭右侧" :disabled (not has-right?)}
    {:key "close-all" :label "关闭全部"}
    {:type "divider"}
    {:key "fullscreen" :label "全屏显示"}
    {:key "refresh" :label "刷新当前页"}]))

(defn- tab-item
  "单个Tab项组件"
  [{:keys [key label icon closable active?]}]
  (let [tabs @(rf/subscribe [:tabs/items])
        idx (.indexOf (clj->js (mapv :key tabs)) key)
        has-others? (> (count tabs) 1)
        has-right? (< idx (dec (count tabs)))
        tab-ref (hooks/use-ref nil)]
    [:> Dropdown {:menu {:items (tab-context-menu key has-others? has-right?)
                         :onClick (fn [e]
                                    (case (.-key e)
                                      "close-current" (rf/dispatch [:tabs/close key])
                                      "close-others" (rf/dispatch [:tabs/remove-others key])
                                      "close-right" (rf/dispatch [:tabs/remove-right key])
                                      "close-all" (rf/dispatch [:tabs/remove-all])
                                      "fullscreen" (rf/dispatch [:tabs/fullscreen])
                                      "refresh" (.reload js/location)
                                      nil))}
                  :trigger (clj->js ["contextMenu"])}
     [:div {:ref tab-ref
            :class (str "tab-item" (when active? " tab-item-active"))
            :style {:display "inline-flex"
                    :alignItems "center"
                    :height 30
                    :padding "0 12px"
                    :marginRight 4
                    :background (if active?
                                  "var(--ant-color-primary, #1677ff)"
                                  "var(--ant-color-bg-container, #fff)")
                    :color (if active? "#fff" "var(--ant-color-text-secondary, #666)")
                    :borderRadius 6
                    :cursor "pointer"
                    :fontSize 13
                    :transition "all 0.3s cubic-bezier(0.645, 0.045, 0.355, 1)"
                    :transform (when active? "scale(1.05)")
                    :border (if active?
                              "1px solid var(--ant-color-primary, #1677ff)"
                              "1px solid var(--ant-color-border, #d9d9d9)")
                    :boxShadow (if active?
                                 "0 2px 8px rgba(24,144,255,0.35)"
                                 "0 1px 2px rgba(0,0,0,0.03)")
                    :whiteSpace "nowrap"
                    :position "relative"
                    :overflow "hidden"}
            :on-click #(do (rf/dispatch [:tabs/activate key]) (rf/dispatch [:navigate (keyword key)]))}
      ;; Active indicator line
      (when active?
        [:div {:style {:position "absolute"
                       :bottom 0
                       :left "50%"
                       :transform "translateX(-50%)"
                       :width "60%"
                       :height 2
                       :background "#fff"
                       :borderRadius 1
                       :transition "all 0.3s"}}])
      ;; 图标
      (if icon
        (when-let [icon-el (icon-picker/icon-element icon {:style {:marginRight 6 :fontSize 12}})]
          icon-el)
        (when (= key :dashboard)
          [:> HomeOutlined {:style {:marginRight 6 :fontSize 12}}]))
      [:span label]
      (when (and closable (not= key :dashboard))
        [:> CloseOutlined {:style {:marginLeft 8 :fontSize 10
                                   :opacity (if active? 0.8 0.4)
                                   :transition "opacity 0.2s"}
                           :on-click (fn [e]
                                       (.stopPropagation e)
                                       (rf/dispatch [:tabs/close key]))}])]]))

(defn- scroll-tabs
  "左右滚动标签页"
  [container-ref direction]
  (when-let [el (.-current container-ref)]
    (let [scroll-amount 200]
      (.scrollBy el #js {:left (* direction scroll-amount) :behavior "smooth"}))))

(defn- tab-bar
  "Tab栏组件 — RuoYi 风格"
  []
  (let [container-ref (hooks/use-ref nil)
        tabs @(rf/subscribe [:tabs/items])
        active @(rf/subscribe [:tabs/active])
        ;; 检查是否有滚动条
        [show-scroll set-show-scroll!] (hooks/use-state false)]
    (hooks/use-effect
     (fn []
       (when-let [el (.-current container-ref)]
         (let [check-scroll #(set-show-scroll! (or (>= (.-scrollWidth el) (.-clientWidth el))))]
           (check-scroll)
           (.addEventListener el "resize" check-scroll)
           (fn [] (.removeEventListener el "resize" check-scroll)))))
     [(count tabs)])
    [:div {:style {:borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"
                   :padding "6px 12px 0"
                   :display "flex"
                   :alignItems "center"
                   :height 40
                   :background "var(--ant-color-bg-container, #fff)"}}
     ;; 左滚动按钮
     (when show-scroll
       [:div {:style {:cursor "pointer" :padding "0 4px" :color "var(--ant-color-text-secondary, #999)"
                      :fontSize 16 :userSelect "none"}
              :on-click #(scroll-tabs container-ref -1)}
        [:> LeftOutlined {:style {:fontSize 12}}]])
     ;; Tab 容器
     [:div {:ref container-ref
            :style {:flex 1
                    :display "flex"
                    :alignItems "flex-end"
                    :overflowX "auto"
                    :overflowY "hidden"
                    :whiteSpace "nowrap"
                    :scrollbarWidth "none"
                    ::WebkitOverflowScrolling "touch"
                    :msOverflowStyle "none"}}
      (for [tab tabs]
        ^{:key (:key tab)}
        [tab-item (assoc tab :active? (= (:key tab) active))])]
     ;; 右滚动按钮
     (when show-scroll
       [:div {:style {:cursor "pointer" :padding "0 4px" :color "var(--ant-color-text-secondary, #999)"
                      :fontSize 16 :userSelect "none"}
              :on-click #(scroll-tabs container-ref 1)}
        [:> RightOutlined {:style {:fontSize 12}}]])
     ;; 操作按钮组
     [:div {:style {:display "flex" :alignItems "center" :marginLeft 8 :gap 4}}
      [antd/tooltip {:title "刷新当前页"}
       [:> ReloadOutlined {:style {:cursor "pointer" :color "var(--ant-color-text-secondary, #999)"
                                   :fontSize 14 :padding "4px"}
                           :on-click #(.reload js/location)}]]
      [antd/tooltip {:title "全屏显示"}
       [:> ExpandOutlined {:style {:cursor "pointer" :color "var(--ant-color-text-secondary, #999)"
                                   :fontSize 14 :padding "4px"}
                           :on-click #(rf/dispatch [:tabs/fullscreen])}]]
      [antd/dropdown {:menu {:items (clj->js [{:key "close-others" :label "关闭其他"
                                               {:key "close-right" :label "关闭右侧"}
                                               {:key "close-all" :label "关闭全部"}
                                               {:type "divider"}
                                               {:key "refresh" :label "刷新当前页"}}])
                             :onClick (fn [e]
                                        (let [active-tab @(rf/subscribe [:tabs/active])]
                                          (case (.-key e)
                                            "close-others" (rf/dispatch [:tabs/remove-others active-tab])
                                            "close-right" (rf/dispatch [:tabs/remove-right active-tab])
                                            "close-all" (rf/dispatch [:tabs/remove-all])
                                            "refresh" (.reload js/location)
                                            nil)))}}
       [:> DownOutlined {:style {:cursor "pointer" :color "var(--ant-color-text-secondary, #999)"
                                 :fontSize 12 :padding "4px"}}]]]]))

;; ─── 页面关键词到菜单路径映射 ─────────────────────────────────────────
(def page->menu-key
  "将路由关键词映射到菜单的 key（完整路径）。"
  {:user "system/user"
   :role "system/role"
   :menu "system/menu"
   :dept "system/dept"
   :post "system/post"
   :dict "system/dict"
   :config "system/config"
   :notice "system/notice"
   :oper-log "monitor/operlog"
   :login-log "monitor/logininfor"
   :online "monitor/online"
   :job "monitor/job"
   :server "monitor/server"
   :cache "monitor/cache"
   :datasource "monitor/datasource"
   :gen "monitor/gen"
   :swagger "monitor/swagger"
   :build "tool/build"
   :profile "system/user/profile"
   :dashboard "dashboard"})

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
  ([menus] (menu->antd-items menus ""))
  ([menus parent-path]
   (clj->js
    (mapv (fn [m]
            (let [full-path (if (seq parent-path)
                              (str parent-path "/" (:path m))
                              (:path m))
                  item {:key full-path
                        :label (:menu_name m)}
                  icon-el (when (and (:icon m) (not= (:icon m) "#"))
                            (icon-picker/icon-element (:icon m) {:style {:fontSize 14}}))]
              (cond-> item
                icon-el
                (assoc :icon (r/as-element icon-el))
                (seq (:children m))
                (assoc :children (menu->antd-items (:children m) full-path)))))
          menus))))

(defn- page-labels
  "从菜单树递归提取页面路径到标签的映射。"
  ([menus] (page-labels menus ""))
  ([menus parent-path]
   (reduce (fn [acc m]
             (let [full-path (if (seq parent-path)
                               (str parent-path "/" (:path m))
                               (:path m))
                   ;; 查找路由关键词，如 system/user -> :user
                   matched (router/match-route (str "/" full-path))
                   route-key (:handler matched)
                   acc (if route-key
                         (assoc acc route-key (:menu_name m))
                         acc)]
               (if (seq (:children m))
                 (merge acc (page-labels (:children m) full-path))
                 acc)))
           {}
           menus)))

(defn- page-icons
  "从菜单树递归提取页面路径到图标的映射。"
  ([menus] (page-icons menus ""))
  ([menus parent-path]
   (reduce (fn [acc m]
             (let [full-path (if (seq parent-path)
                               (str parent-path "/" (:path m))
                               (:path m))
                   matched (router/match-route (str "/" full-path))
                   route-key (:handler matched)
                   icon (:icon m)
                   acc (if (and route-key (seq icon))
                         (assoc acc route-key icon)
                         acc)]
               (if (seq (:children m))
                 (merge acc (page-icons (:children m) full-path))
                 acc)))
           {}
           menus)))

;; ─── 主布局 ────────────────────────────────────────────────────────

;; ─── Tab 动画样式 ──────────────────────────────────────────────────────

(defn- tab-animation-styles []
  [:style
   "
@keyframes tabSlideIn {
  from {
    opacity: 0;
    transform: translateX(20px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes tabFadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes tabPulse {
  0% { transform: scale(1); }
  50% { transform: scale(1.08); }
  100% { transform: scale(1.05); }
}

.tab-item {
  animation: tabSlideIn 0.3s ease-out;
}

.tab-item-active {
  animation: tabPulse 0.3s ease-out;
}

.tab-content-enter {
  animation: tabFadeIn 0.3s ease-out;
}
"])

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
            labels (page-labels user-menus)
            icons (page-icons user-menus)]
        [:> Layout {:style {:minHeight "100vh"}}
         ;; Tab 动画样式
         [tab-animation-styles]
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
                    :selectedKeys (clj->js [(or (page->menu-key page) (name page))])
                    :defaultOpenKeys #js ["system" "monitor" "tool"]
                    :items menu-items
                    :onClick (fn [e]
                               (let [k (.-key e)
                                     ;; Look up the route keyword from the path
                                     matched (router/match-route (str "/" k))
                                     page (or (:handler matched) (keyword k))
                                     _ (js/console.log "Page:" (str page))]
                                 (rf/dispatch [:navigate page])
                                 (rf/dispatch [:tabs/add page (get labels page "页面") (get icons page)])))}]]
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
          [:> Layout.Content {:style {:margin 24}
                              :key (name page)
                              :class "tab-content-enter"}
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
             :datasource [datasource/datasource-page]
             :gen [gen/gen-page]
             :swagger [swagger/swagger-page]
             :build [form-builder/form-builder-page]
             :file [file-manager/file-manager-page]
             [:div {:style {:padding 48 :textAlign "center" :color "#999" :fontSize 16}}
              "页面建设中"])]]]))))
