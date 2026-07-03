(ns com.ruoyi.frontend.pages.layout
  "主布局页面，包含多Tab支持。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   [com.ruoyi.frontend.antd :as antd]
   ["antd" :refer [Layout Menu Button Space Badge Avatar Dropdown Divider Popover Segmented]]
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
                                CloseCircleOutlined ArrowRightOutlined
                                CompressOutlined LogoutOutlined
                                MenuFoldOutlined MenuUnfoldOutlined
                                FontSizeOutlined TranslationOutlined]]
   [com.ruoyi.frontend.router :as router]
   [com.ruoyi.frontend.components.theme-switcher :as theme-switcher]
   [com.ruoyi.frontend.components.layout-settings :as layout-settings]
   [com.ruoyi.frontend.components.error-boundary :as error-boundary]
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
   [com.ruoyi.frontend.pages.swagger :as swagger]
   [com.ruoyi.frontend.pages.integrant :as integrant]
   [com.ruoyi.frontend.components.icon-picker :as icon-picker]))

;; ─── Tab 组件 ──────────────────────────────────────────────────────

(defn- tab-context-menu
  "标签页右键菜单项。"
  [key has-others? has-right?]
  (clj->js
   [{:key "refresh"
     :label "刷新页面"
     :icon (r/as-element [:> ReloadOutlined])}
    {:key "close-current"
     :label "关闭当前"
     :icon (r/as-element [:> CloseOutlined])
     :disabled (= key :dashboard)}
    {:key "close-others"
     :label "关闭其他"
     :icon (r/as-element [:> CloseCircleOutlined])
     :disabled (not has-others?)}
    {:key "close-right"
     :label "关闭右侧"
     :icon (r/as-element [:> ArrowRightOutlined])
     :disabled (not has-right?)}
    {:key "close-all"
     :label "全部关闭"
     :icon (r/as-element [:> CloseCircleOutlined])}]))

(defn- tab-item
  "单个Tab项组件"
  [{:keys [key label icon closable active?]}]
  (let [tabs @(rf/subscribe [:tabs/items])
        layout-settings @(rf/subscribe [:layout/settings])
        theme-mode @(rf/subscribe [:theme/mode])
        is-dark? (= theme-mode :dark)
        show-icon? (get layout-settings :show-tab-icon? true)
        card-style? (= "card" (get layout-settings :tab-style "google"))
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
                                      "refresh" (.reload js/location)
                                      nil))}
                  :trigger (clj->js ["contextMenu"])}
     [:div {:ref tab-ref
            :class (str "tab-item" (when active? " tab-item-active"))
            :style {:display "inline-flex"
                    :flex "0 0 auto"
                    :alignItems "center"
                    :height (if card-style? 34 38)
                    :padding (if card-style? "0 16px" "0 18px")
                    :marginRight (if card-style? 6 2)
                    :background (if active? (if is-dark? "rgba(255,255,255,0.08)" "#e8f4ff") (if is-dark? "#141414" "#fff"))
                    :color (if active? "#409eff" (if is-dark? "rgba(255,255,255,0.65)" "#606266"))
                    :borderRadius (cond
                                    card-style? 4
                                    active? "14px 14px 0 0"
                                    :else "0")
                    :cursor "pointer"
                    :fontSize 14
                    :transition "background 0.2s, color 0.2s"
                    :border (str "1px solid " (if is-dark? "#303030" "#ebeef5"))
                    :borderBottom (str "1px solid " (if active? (if is-dark? "rgba(255,255,255,0.08)" "#e8f4ff") (if is-dark? "#303030" "#ebeef5")))
                    :boxShadow (if (and card-style? active?) "0 1px 4px rgba(64,158,255,0.18)" "none")
                    :whiteSpace "nowrap"
                    :position "relative"
                    :overflow "hidden"}
            :on-click #(do (rf/dispatch [:tabs/activate key]) (rf/dispatch [:navigate (keyword key)]))}
      ;; 图标
      (when show-icon?
        (if icon
          (when-let [icon-el (icon-picker/icon-element icon {:style {:marginRight 6 :fontSize 12}})]
            icon-el)
          (when (= key :dashboard)
            [:> HomeOutlined {:style {:marginRight 6 :fontSize 12}}])))
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
  "Tab栏组件 — RuoYi 风格，支持左右滚动"
  []
  (let [container-ref (hooks/use-ref nil)
        tabs @(rf/subscribe [:tabs/items])
        active @(rf/subscribe [:tabs/active])
        [show-scroll set-show-scroll!] (hooks/use-state false)
        [can-left set-can-left!] (hooks/use-state false)
        [can-right set-can-right!] (hooks/use-state false)
        check-scroll (fn []
                       (when-let [el (.-current container-ref)]
                         (let [sw (.-scrollWidth el)
                               cw (.-clientWidth el)
                               left (.-scrollLeft el)]
                           (set-show-scroll! (> sw cw))
                           (set-can-left! (> left 0))
                           (set-can-right! (> (- sw cw left) 1)))))]
    ;; 监听容器尺寸变化，更新滚动状态
    (hooks/use-effect
     (fn []
       (when-let [el (.-current container-ref)]
         (check-scroll)
         (if (exists? js/ResizeObserver)
           (let [ro (js/ResizeObserver. (fn [_] (check-scroll)))]
             (.observe ro el)
             (fn [] (.disconnect ro)))
           (do (.addEventListener js/window "resize" check-scroll)
               (fn [] (.removeEventListener js/window "resize" check-scroll))))))
     [(count tabs)])
    ;; 激活标签自动滚动到可视区域
    (hooks/use-effect
     (fn []
       (when-let [el (.-current container-ref)]
         (let [active-el (.querySelector el ".tab-item-active")]
           (when active-el
             (let [el-left (.-offsetLeft active-el)
                   el-width (.-offsetWidth active-el)
                   scroll (.-scrollLeft el)
                   cw (.-clientWidth el)]
               (cond
                 (< el-left scroll)
                 (set! (.-scrollLeft el) el-left)

                 (> (+ el-left el-width) (+ scroll cw))
                 (set! (.-scrollLeft el) (- (+ el-left el-width) cw)))))))
       js/undefined)
     [active])
    (let [theme-mode @(rf/subscribe [:theme/mode])
          is-dark? (= theme-mode :dark)
          bg-base (if is-dark? "#141414" "#fff")
          border-color (if is-dark? "#303030" "#dcdfe6")]
    [:div {:class "app-tab-bar"
           :style {:borderBottom (str "1px solid " border-color)
                   :padding "0 0 0 0"
                   :display "flex"
                   :alignItems "center"
                   :height 40
                   :background bg-base
                   :boxShadow (if is-dark? "none" "0 1px 2px rgba(0,0,0,0.04)")}}
     ;; 左滚动按钮
     (when show-scroll
       [:div {:class "tab-scroll-btn tab-scroll-left"
              :style {:flex "0 0 auto"
                      :cursor (if can-left "pointer" "not-allowed")
                      :width 32
                      :height 40
                      :display "flex"
                      :alignItems "center"
                      :justifyContent "center"
                      :borderRight (str "1px solid " border-color)
                      :color (if can-left "var(--ant-color-text-secondary, #666)" "var(--ant-color-border, #ccc)")
                      :fontSize 16
                      :userSelect "none"}
              :on-click #(when can-left (scroll-tabs container-ref -1))}
        [:> LeftOutlined {:style {:fontSize 12}}]])
     ;; Tab 容器
     [:div {:ref container-ref
            :style {:flex 1
                    :display "flex"
                    :alignItems "flex-end"
                    :height 40
                    :paddingLeft 0
                    :overflowX "auto"
                    :overflowY "hidden"
                    :whiteSpace "nowrap"
                    :scrollbarWidth "none"
                    ::WebkitOverflowScrolling "touch"
                    :msOverflowStyle "none"}
            :on-scroll check-scroll}
      (for [tab tabs]
        ^{:key (:key tab)}
        [tab-item (assoc tab :active? (= (:key tab) active))])]
     ;; 右滚动按钮
     (when show-scroll
       [:div {:class "tab-scroll-btn tab-scroll-right"
              :style {:flex "0 0 auto"
                      :cursor (if can-right "pointer" "not-allowed")
                      :width 32
                      :height 40
                      :display "flex"
                      :alignItems "center"
                      :justifyContent "center"
                      :borderLeft (str "1px solid " border-color)
                      :color (if can-right "var(--ant-color-text-secondary, #666)" "var(--ant-color-border, #ccc)")
                      :fontSize 16
                      :userSelect "none"}
              :on-click #(when can-right (scroll-tabs container-ref 1))}
        [:> RightOutlined {:style {:fontSize 12}}]])
     ;; 操作按钮组
     [:div {:style {:display "flex" :alignItems "center" :marginLeft 0 :height 40 :borderLeft (str "1px solid " border-color)}}
      [antd/tooltip {:title "向左滚动"}
       [:> LeftOutlined {:style {:cursor "pointer" :color (if is-dark? "rgba(255,255,255,0.65)" "#909399")
                                 :fontSize 13 :padding "13px 12px"
                                 :borderRight (str "1px solid " border-color)}
                         :on-click #(scroll-tabs container-ref -1)}]]
      [antd/tooltip {:title "向右滚动"}
       [:> RightOutlined {:style {:cursor "pointer" :color (if is-dark? "rgba(255,255,255,0.65)" "#909399")
                                  :fontSize 13 :padding "13px 12px"
                                  :borderRight (str "1px solid " border-color)}
                          :on-click #(scroll-tabs container-ref 1)}]]
      [antd/tooltip {:title "刷新当前页"}
       [:> ReloadOutlined {:style {:cursor "pointer" :color (if is-dark? "rgba(255,255,255,0.65)" "#909399")
                                   :fontSize 14 :padding "13px 12px"
                                   :borderRight (str "1px solid " border-color)}
                           :on-click #(.reload js/location)}]]
      [antd/dropdown {:menu {:items (let [active-idx (.indexOf (clj->js (mapv :key tabs)) active)]
                                      (tab-context-menu active
                                                        (> (count tabs) 1)
                                                        (< active-idx (dec (count tabs)))))
                             :onClick (fn [e]
                                        (case (.-key e)
                                          "refresh" (.reload js/location)
                                          "close-current" (rf/dispatch [:tabs/close active])
                                          "close-others" (rf/dispatch [:tabs/remove-others active])
                                          "close-right" (rf/dispatch [:tabs/remove-right active])
                                          "close-all" (rf/dispatch [:tabs/remove-all])
                                          nil))}}
       [:> DownOutlined {:style {:cursor "pointer" :color (if is-dark? "rgba(255,255,255,0.65)" "#909399")
                                 :fontSize 12 :padding "14px 12px"}}]]]])))

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
   :oper-log "system/operlog/operlog"
   :login-log "system/operlog/logininfor"
   :online "monitor/online"
   :job "monitor/job"
   :server "monitor/server"
   :cache "monitor/cache"
   :datasource "monitor/datasource"
   :integrant "monitor/integrant"
   :swagger "monitor/swagger"
   :profile "system/user/profile"
   :dashboard "dashboard"})

(def page-breadcrumbs
  {:dashboard ["首页"]
   :user ["首页" "系统管理" "用户管理"]
   :role ["首页" "系统管理" "角色管理"]
   :menu ["首页" "系统管理" "菜单管理"]
   :dept ["首页" "系统管理" "部门管理"]
   :post ["首页" "系统管理" "岗位管理"]
   :dict ["首页" "系统管理" "字典管理"]
   :config ["首页" "系统管理" "参数设置"]
   :notice ["首页" "系统管理" "通知公告"]
   :oper-log ["首页" "系统管理" "日志管理" "操作日志"]
   :login-log ["首页" "系统管理" "日志管理" "登录日志"]
   :online ["首页" "系统监控" "在线用户"]
   :job ["首页" "系统监控" "定时任务"]
   :server ["首页" "系统监控" "服务监控"]
   :cache ["首页" "系统监控" "缓存监控"]
   :datasource ["首页" "系统监控" "连接池监视"]
   :swagger ["首页" "系统工具" "系统接口"]
   :profile ["首页" "个人中心"]})

(def route-labels
  (into {} (map (fn [[k xs]] [k (last xs)]) page-breadcrumbs)))

(def route-icons
  {:dashboard "dashboard"
   :user "user"
   :role "peoples"
   :menu "tree-table"
   :dept "tree"
   :post "post"
   :dict "dict"
   :config "edit"
   :notice "message"
   :oper-log "form"
   :login-log "logininfor"
   :online "online"
   :job "job"
   :server "server"
   :cache "cache"
   :datasource "database"
   :swagger "swagger"
   :profile "profile"})

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
            (let [path (:path m)
                  full-path (cond
                              (not (seq path)) parent-path
                              (seq parent-path) (str parent-path "/" path)
                              :else path)
                  item-key (if (seq full-path)
                             full-path
                             (str "menu-" (:menu_id m)))
                  item {:key item-key
                        :label (:menu_name m)}
                  icon-name (or (and (seq (:icon m)) (not= (:icon m) "#") (:icon m))
                                "ContainerOutlined")
                  icon-el (icon-picker/icon-element icon-name {:style {:fontSize 14}})]
              (cond-> item
                icon-el
                (assoc :icon icon-el)
                (seq (:children m))
                (assoc :children (menu->antd-items (:children m) full-path)))))
          menus))))

(defn- page-labels
  "从菜单树递归提取页面路径到标签的映射。"
  ([menus] (page-labels menus ""))
  ([menus parent-path]
   (reduce (fn [acc m]
             (let [path (:path m)
                   full-path (cond
                               (not (seq path)) parent-path
                               (seq parent-path) (str parent-path "/" path)
                               :else path)
                   ;; 查找路由关键词，如 system/user -> :user
                   matched (when (seq full-path)
                             (router/match-route (str "/" full-path)))
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
             (let [path (:path m)
                   full-path (cond
                               (not (seq path)) parent-path
                               (seq parent-path) (str parent-path "/" path)
                               :else path)
                   matched (when (seq full-path)
                             (router/match-route (str "/" full-path)))
                   route-key (:handler matched)
                   icon (or (and (seq (:icon m)) (not= (:icon m) "#") (:icon m))
                            "ContainerOutlined")
                   acc (if route-key
                         (assoc acc route-key icon)
                         acc)]
               (if (seq (:children m))
                 (merge acc (page-icons (:children m) full-path))
                 acc)))
           {}
           menus)))

(defn- menu-open-keys
  "从接口返回的菜单树中提取所有有子菜单的 Menu key，用于动态菜单到达后默认展开。"
  ([menus] (menu-open-keys menus ""))
  ([menus parent-path]
   (->> menus
        (mapcat (fn [m]
                  (let [path (:path m)
                        full-path (cond
                                    (not (seq path)) parent-path
                                    (seq parent-path) (str parent-path "/" path)
                                    :else path)
                        item-key (if (seq full-path)
                                   full-path
                                   (str "menu-" (:menu_id m)))
                        children (:children m)]
                    (if (seq children)
                      (cons item-key (menu-open-keys children full-path))
                      []))))
        (remove empty?)
        vec)))

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

(defn- display-settings-panel
  "字号按钮弹出的显示设置面板。"
  []
  (let [component-size @(rf/subscribe [:theme/component-size])
        font-size @(rf/subscribe [:theme/font-size])]
    [:div {:style {:width 220 :padding 4}}
     [:div {:style {:fontSize 14 :fontWeight 600 :color "#303133" :margin "0 0 12px"}}
      "显示设置"]
     [:div {:style {:marginBottom 14}}
      [:div {:style {:fontSize 13 :color "#606266" :marginBottom 8}} "布局密度"]
      [:> Segmented {:block true
                     :value component-size
                     :onChange #(rf/dispatch [:theme/set-density %])
                     :options #js [#js {:label "紧凑" :value "small"}
                                   #js {:label "默认" :value "middle"}
                                   #js {:label "宽松" :value "large"}]}]]
     [:div
      [:div {:style {:fontSize 13 :color "#606266" :marginBottom 8}} "字体大小"]
      [:> Segmented {:block true
                     :value font-size
                     :onChange #(rf/dispatch [:theme/set-font-size %])
                     :options #js [#js {:label "小" :value "small"}
                                   #js {:label "中" :value "middle"}
                                   #js {:label "大" :value "large"}]}]]]))

(defn- display-settings-button
  "右上角显示设置按钮。"
  []
  [:> Popover {:content (r/as-element [display-settings-panel])
               :trigger "click"
               :placement "bottomRight"}
   [:> Button {:type "text"
               :style {:fontSize 18 :color "#606266"}
               :icon (r/as-element [:> FontSizeOutlined])}]])

(defn main-layout []
  (let [[collapsed set-collapsed!] (hooks/use-state false)
        [settings-open? set-settings-open!] (hooks/use-state false)
        user @(rf/subscribe [:auth/user])
        page @(rf/subscribe [:page])
        layout-settings @(rf/subscribe [:layout/settings])
        sider-width 196
        collapsed-width 56
        auth-menus (vec (or (:menus user) []))
        filtered-menus (filter-visible-menus auth-menus)
        menu-items (menu->antd-items filtered-menus)
        open-menu-keys (menu-open-keys filtered-menus)
        menu-instance-key (str "permission-menu-" (hash filtered-menus))
        selected-menu-key (or (page->menu-key page) (name page))
        labels (merge route-labels (page-labels filtered-menus))
        icons (merge route-icons (page-icons filtered-menus))
        breadcrumbs (get page-breadcrumbs page ["首页"])
        nav-mode (get layout-settings :nav-mode "side")
        top-nav? (= nav-mode "top")
        content-left (if top-nav? 0 (if collapsed collapsed-width sider-width))
        theme-mode @(rf/subscribe [:theme/mode])
        is-dark? (= theme-mode :dark)
        bg-base (if is-dark? "#141414" "#fff")
        bg-layout (if is-dark? "#000" "#f5f5f5")
        bg-content (if is-dark? "#000" "#fff")
        bg-header (if is-dark? "#141414" "#fff")
        text-primary (if is-dark? "rgba(255,255,255,0.88)" "#303133")
        text-secondary (if is-dark? "rgba(255,255,255,0.65)" "#606266")
        border-color (if is-dark? "#303030" "#e4e7ed")
        handle-menu-click (fn [e]
                            (let [k (.-key e)
                                  matched (router/match-route (str "/" k))
                                  page (or (:handler matched) (keyword k))]
                              (when (:handler matched)
                                (rf/dispatch [:navigate page])
                                (rf/dispatch [:tabs/add page (get labels page "页面") (get icons page)]))))]
    (hooks/use-effect
     (fn []
       (set! (.-title js/document)
             (if (get layout-settings :dynamic-title? true)
               (str (last breadcrumbs) " - 若依管理系统")
               "若依管理系统"))
       js/undefined)
     [page (get layout-settings :dynamic-title? true)])
    [:> Layout {:style {:minHeight "100vh"
                        :background bg-layout
                        :fontFamily "\"Helvetica Neue\", Helvetica, \"PingFang SC\", \"Hiragino Sans GB\", \"Microsoft YaHei\", Arial, sans-serif"
                        :fontSize 14}}
         ;; Tab 动画样式
     [tab-animation-styles]
     (when-not top-nav?
       [:> Layout.Sider {:collapsible true
                         :collapsed collapsed
                         :onCollapse set-collapsed!
                         :theme "dark"
                         :width sider-width
                         :collapsedWidth collapsed-width
                         :trigger nil
                         :style {:background "#172033"
                                 :boxShadow "2px 0 8px rgba(0,0,0,0.18)"}}
        (when (get layout-settings :show-logo? true)
          [:div {:style {:height 56 :display "flex" :alignItems "center"
                         :justifyContent "center" :gap 8 :fontSize 16 :fontWeight 700
                         :color "#fff"
                         :background "#172033"}}
           [:div {:style {:width 24 :height 24 :borderRadius "50%"
                          :display "flex" :alignItems "center" :justifyContent "center"
                          :color "#79e0c2" :fontSize 20 :fontWeight 300}}
            "⌁"]
           (when-not collapsed [:span "若依管理系统"])])
        [:> Menu {:key (str "side-" menu-instance-key)
                  :theme "dark"
                  :mode "inline"
                  :inlineCollapsed collapsed
                  :style {:background "#172033"
                          :fontSize 14
                          :borderInlineEnd "none"}
                  :selectedKeys (clj->js [selected-menu-key])
                  :defaultOpenKeys (clj->js open-menu-keys)
                  :items menu-items
                  :onClick handle-menu-click}]])
         ;; Main area
     [:> Layout {:style {:background bg-content}}
      [:> Layout.Header {:style {:padding "0 16px"
                                 :display "flex" :justifyContent "space-between"
                                 :alignItems "center" :height 56
                                 :background bg-header
                                 :borderBottom (str "1px solid " border-color)
                                 :boxShadow "0 1px 4px rgba(0,21,41,0.08)"
                                 :position (when (get layout-settings :fixed-header? true) "sticky")
                                 :top 0
                                 :zIndex 30}}
           ;; Left: navigation or breadcrumb
       [:div {:style {:display "flex" :alignItems "center" :gap 12 :flex 1 :minWidth 0}}
        (if top-nav?
          [:<>
           (when (get layout-settings :show-logo? true)
             [:div {:style {:display "flex" :alignItems "center" :gap 8
                            :height 56 :paddingRight 16 :fontSize 16 :fontWeight 700
                            :color "#172033" :whiteSpace "nowrap"}}
              [:div {:style {:width 24 :height 24 :borderRadius "50%"
                             :display "flex" :alignItems "center" :justifyContent "center"
                             :color "#23b99a" :fontSize 20 :fontWeight 300}}
               "⌁"]
              [:span "若依管理系统"]])
           [:> Menu {:key (str "top-" menu-instance-key)
                     :mode "horizontal"
                     :selectedKeys (clj->js [selected-menu-key])
                     :items menu-items
                     :onClick handle-menu-click
                     :style {:flex 1 :minWidth 0 :height 56 :lineHeight "56px"
                             :borderBottom "none" :fontSize 14}}]]
          [:<>
               ;; Hamburger toggle button
           [:div {:style {:cursor "pointer" :padding "0 6px" :fontSize 21
                          :display "flex" :alignItems "center"
                          :color text-primary
                          :transition "color 0.3s"}
                  :on-click #(set-collapsed! (not collapsed))}
            (if collapsed
              [:> MenuUnfoldOutlined]
              [:> MenuFoldOutlined])]
           [:div {:style {:display "flex" :alignItems "center" :gap 10 :fontSize 14}}
            (for [[idx crumb] (map-indexed vector breadcrumbs)]
              ^{:key (str "crumb-" idx)}
              [:<>
               (when (pos? idx)
                 [:span {:style {:color text-secondary}} "/"])
               [:span {:style {:color (if (= idx (dec (count breadcrumbs))) text-secondary text-primary)
                               :fontWeight (if (= idx (dec (count breadcrumbs))) 400 500)}}
                crumb]])]])]
       [:div {:style {:display "flex" :alignItems "center" :gap 6}}
            ;; 搜索
        [:> Button {:type "text" :style {:fontSize 18 :color text-secondary} :icon (r/as-element [:> SearchOutlined])}]
            ;; GitHub
        [:> Button {:type "text" :style {:fontSize 18 :color text-secondary} :icon (r/as-element [:> GithubOutlined])
                    :onClick #(js/window.open "https://github.com/RedCreationTech/rouyi_clojure" "_blank")}]
            ;; 文档
        [:> Button {:type "text" :style {:fontSize 18 :color text-secondary} :icon (r/as-element [:> QuestionCircleOutlined])}]
            ;; 全屏
        [:> Button {:type "text" :style {:fontSize 18 :color text-secondary} :icon (r/as-element [:> ExpandOutlined])
                    :onClick #(let [doc js/document.documentElement]
                                (if (.-fullscreenElement js/document)
                                  (.exitFullscreen js/document)
                                  (.requestFullscreen doc)))}]
        [display-settings-button]
            ;; 通知
        [:> Badge {:count 3 :size "small"}
         [:> Button {:type "text" :style {:fontSize 18 :color text-secondary} :icon (r/as-element [:> BellOutlined])}]]
            ;; 头像 + 下拉菜单
        [:> Dropdown {:menu {:items (clj->js [{:key "profile" :label "个人中心"}
                                              {:key "layout-settings" :label "布局设置"}
                                              {:type "divider"}
                                              {:key "logout" :label "退出登录" :danger true}])
                             :onClick (fn [e]
                                        (case (.-key e)
                                          "profile" (rf/dispatch [:navigate :profile])
                                          "layout-settings" (set-settings-open! true)
                                          "logout" (rf/dispatch [:auth/logout])
                                          nil))}
                      :trigger (clj->js ["click"])}
         [:div {:style {:display "flex" :alignItems "center" :gap 8 :cursor "pointer" :padding "0 6px"}}
          [:> Avatar {:size 32
                      :style {:background "linear-gradient(135deg,#f7d7c4,#9bc9ff)"
                              :color "#fff"
                              :fontWeight 700}}
           "若"]
          [:span {:style {:fontSize 14 :fontWeight 600 :color text-primary}} "若依"]]]]
       [layout-settings/layout-settings-drawer {:open? settings-open?
                                                :on-close #(set-settings-open! false)}]]
          ;; Tab 栏
      (when (get layout-settings :open-tags? true)
        [tab-bar])
          ;; 内容区（加 Error Boundary，避免单个页面崩溃导致整个布局白屏）
      [:> Layout.Content {:style {:margin 0
                                  :padding 0
                                  :background bg-content
                                  :minHeight (if (get layout-settings :open-tags? true)
                                               "calc(100vh - 96px)"
                                               "calc(100vh - 56px)")
                                  :paddingBottom (if (get layout-settings :show-footer? true) 36 0)
                                  :position "relative"}
                          :key (name page)
                          :class "tab-content-enter"}
       [error-boundary/boundary
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
          :integrant [integrant/integrant-page]
          :swagger [swagger/swagger-page]
          [:div {:style {:padding 48 :textAlign "center" :color "#999" :fontSize 16}}
           "页面建设中"])]
       [:div {:class "app-layout-float"
              :style {:position "fixed" :right 14 :bottom 54
                      :width 42 :height 42 :borderRadius "50%"
                      :background "#e989aa" :color "#fff"
                      :display "flex" :alignItems "center" :justifyContent "center"
                      :fontSize 18 :fontWeight 700
                      :boxShadow "0 4px 12px rgba(233,137,170,0.35)"
                      :zIndex 20}}
       "LA"]
       (when (get layout-settings :show-footer? true)
         [:div {:class "app-layout-footer"
                :style {:position "fixed" :left content-left :right 0 :bottom 0
                        :height 36 :display "flex" :alignItems "center" :justifyContent "flex-end"
                        :padding "0 20px" :borderTop (str "1px solid " border-color)
                        :color text-secondary :fontSize 14 :background bg-header :zIndex 10}}
          "Copyright © 2018-2026 RuoYi. All Rights Reserved."])]]]))
