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
                                MenuFoldOutlined MenuUnfoldOutlined
                                FontSizeOutlined TranslationOutlined]]
   [com.ruoyi.frontend.router :as router]
   [com.ruoyi.frontend.components.theme-switcher :as theme-switcher]
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
   [com.ruoyi.frontend.pages.gen :as gen]
   [com.ruoyi.frontend.pages.swagger :as swagger]
   [com.ruoyi.frontend.pages.form-builder :as form-builder]
   [com.ruoyi.frontend.pages.file-manager :as file-manager]
   [com.ruoyi.frontend.pages.integrant :as integrant]
   [com.ruoyi.frontend.pages.business :as business]
   [com.ruoyi.frontend.pages.project :as project]
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
                    :flex "0 0 auto"
                    :alignItems "center"
                    :height 30
                    :padding "0 14px"
                    :marginRight 2
                    :background (if active? "#e8f3ff" "#fff")
                    :color (if active? "#409eff" "#606266")
                    :borderRadius "12px 12px 0 0"
                    :cursor "pointer"
                    :fontSize 13
                    :transition "background 0.2s, color 0.2s"
                    :border "1px solid #e4e7ed"
                    :borderBottom (if active? "1px solid #e8f3ff" "1px solid #e4e7ed")
                    :boxShadow "none"
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
                       :background "#409eff"
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
    [:div {:style {:borderBottom "1px solid #dcdfe6"
                   :padding "0 0 0 0"
                   :display "flex"
                   :alignItems "center"
                   :height 40
                   :background "#fff"}}
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
                      :borderRight "1px solid #ebeef5"
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
                    :paddingLeft 8
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
                      :borderLeft "1px solid #ebeef5"
                      :color (if can-right "var(--ant-color-text-secondary, #666)" "var(--ant-color-border, #ccc)")
                      :fontSize 16
                      :userSelect "none"}
              :on-click #(when can-right (scroll-tabs container-ref 1))}
        [:> RightOutlined {:style {:fontSize 12}}]])
     ;; 操作按钮组
     [:div {:style {:display "flex" :alignItems "center" :marginLeft 0 :height 40 :borderLeft "1px solid #ebeef5"}}
      [antd/tooltip {:title "向左滚动"}
       [:> LeftOutlined {:style {:cursor "pointer" :color "#909399"
                                 :fontSize 13 :padding "13px 12px"
                                 :borderRight "1px solid #ebeef5"}
                         :on-click #(scroll-tabs container-ref -1)}]]
      [antd/tooltip {:title "向右滚动"}
       [:> RightOutlined {:style {:cursor "pointer" :color "#909399"
                                  :fontSize 13 :padding "13px 12px"
                                  :borderRight "1px solid #ebeef5"}
                          :on-click #(scroll-tabs container-ref 1)}]]
      [antd/tooltip {:title "刷新当前页"}
       [:> ReloadOutlined {:style {:cursor "pointer" :color "#909399"
                                   :fontSize 14 :padding "13px 12px"
                                   :borderRight "1px solid #ebeef5"}
                           :on-click #(.reload js/location)}]]
      [antd/dropdown {:menu {:items (clj->js [{:key "close-others" :label "关闭其他"}
                                               {:key "close-right" :label "关闭右侧"}
                                               {:key "close-all" :label "关闭全部"}
                                               {:type "divider"}
                                               {:key "refresh" :label "刷新当前页"}])
                             :onClick (fn [e]
                                        (let [active-tab @(rf/subscribe [:tabs/active])]
                                          (case (.-key e)
                                            "close-others" (rf/dispatch [:tabs/remove-others active-tab])
                                            "close-right" (rf/dispatch [:tabs/remove-right active-tab])
                                            "close-all" (rf/dispatch [:tabs/remove-all])
                                            "refresh" (.reload js/location)
                                            nil)))}}
       [:> DownOutlined {:style {:cursor "pointer" :color "#909399"
                                 :fontSize 12 :padding "14px 12px"}}]]]]))

;; ─── 页面关键词到菜单路径映射 ─────────────────────────────────────────
(def page->menu-key
  "将路由关键词映射到菜单的 key（完整路径）。"
  {:solution-home "solution"
   :project-info "project/info"
   :resource-standard "resource/standard"
   :resource-vector-kb "resource/vector-kb"
   :resource-structured-kb "resource/structured-kb"
   :resource-case "resource/case"
   :resource-atlas "resource/atlas"
   :user "system/user"
   :role "system/role"
   :menu "system/menu"
   :dept "system/dept"
   :post "system/post"
   :file "system/file"
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
   :integrant "monitor/integrant"
   :gen "monitor/gen"
   :swagger "monitor/swagger"
   :build "tool/build"
   :profile "system/user/profile"
   :dashboard "dashboard"})

(def standard-menu-tree
  [{:path "dashboard" :menu_name "首页" :menu_type "C" :icon "dashboard"}
   {:path "ai-chat" :menu_name "AI对话" :menu_type "C" :icon "user"}
   {:path "system" :menu_name "系统管理" :menu_type "M" :icon "system"
    :children [{:path "user" :menu_name "用户管理" :menu_type "C" :icon "user"}
               {:path "role" :menu_name "角色管理" :menu_type "C" :icon "peoples"}
               {:path "menu" :menu_name "菜单管理" :menu_type "C" :icon "tree-table"}
               {:path "dept" :menu_name "部门管理" :menu_type "C" :icon "tree"}
               {:path "post" :menu_name "岗位管理" :menu_type "C" :icon "post"}
               {:path "dict" :menu_name "字典管理" :menu_type "C" :icon "dict"}
               {:path "config" :menu_name "参数设置" :menu_type "C" :icon "edit"}
               {:path "notice" :menu_name "通知公告" :menu_type "C" :icon "message"}
               {:path "operlog" :menu_name "日志管理" :menu_type "M" :icon "form"
                :children [{:path "operlog" :menu_name "操作日志" :menu_type "C" :icon "form"}
                           {:path "logininfor" :menu_name "登录日志" :menu_type "C" :icon "logininfor"}]}]}
   {:path "monitor" :menu_name "系统监控" :menu_type "M" :icon "monitor"
    :children [{:path "online" :menu_name "在线用户" :menu_type "C" :icon "online"}
               {:path "job" :menu_name "定时任务" :menu_type "C" :icon "job"}
               {:path "server" :menu_name "服务监控" :menu_type "C" :icon "server"}
               {:path "cache" :menu_name "缓存监控" :menu_type "C" :icon "cache"}
               {:path "datasource" :menu_name "连接池监视" :menu_type "C" :icon "DatabaseOutlined"}]}
   {:path "tool" :menu_name "系统工具" :menu_type "M" :icon "tool"
    :children [{:path "build" :menu_name "表单构建" :menu_type "C" :icon "build"}
               {:path "gen" :menu_name "代码生成" :menu_type "C" :icon "code"}
               {:path "swagger" :menu_name "系统接口" :menu_type "C" :icon "swagger"}]}
   {:path "https://ruoyi.vip" :menu_name "若依官网" :menu_type "C" :icon "LinkOutlined"}])

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
   :build ["首页" "系统工具" "表单构建"]
   :gen ["首页" "系统工具" "代码生成"]
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
   :build "build"
   :gen "code"
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

(defn- inject-integrant-menu [menus]
  "在「系统监控」目录下动态注入 Integrant 依赖菜单（演示用）。"
  (mapv (fn [m]
          (if (= "monitor" (:path m))
            (update m :children (fnil conj [])
                    {:path "integrant"
                     :menu_name "Integrant 依赖"
                     :menu_type "C"
                     :visible "0"
                     :status "0"
                     :icon "FunctionOutlined"})
            (if (seq (:children m))
              (update m :children inject-integrant-menu)
              m)))
        menus))

(defn- inject-file-menu [menus]
  "在「系统管理」目录下动态注入文件管理菜单。"
  (mapv (fn [m]
          (if (= "system" (:path m))
            (update m :children (fnil conj [])
                    {:path "file"
                     :menu_name "文件管理"
                     :menu_type "C"
                     :visible "0"
                     :status "0"
                     :icon "FileTextOutlined"})
            (if (seq (:children m))
              (update m :children inject-file-menu)
              m)))
        menus))

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
  (let [[collapsed set-collapsed!] (hooks/use-state false)
        user @(rf/subscribe [:auth/user])
        page @(rf/subscribe [:page])
        sider-width 220
        collapsed-width 64
        menus-with-integrant standard-menu-tree
        filtered-menus (filter-visible-menus menus-with-integrant)
        menu-items (menu->antd-items filtered-menus)
        labels (merge (page-labels menus-with-integrant) route-labels)
        icons (merge (page-icons menus-with-integrant) route-icons)
        breadcrumbs (get page-breadcrumbs page ["首页"])]
    [:> Layout {:style {:minHeight "100vh"
                        :background "#fff"
                        :fontFamily "\"Helvetica Neue\", Helvetica, \"PingFang SC\", \"Hiragino Sans GB\", \"Microsoft YaHei\", Arial, sans-serif"
                        :fontSize 14}}
         ;; Tab 动画样式
         [tab-animation-styles]
         [:> Layout.Sider {:collapsible true
                           :collapsed collapsed
                           :onCollapse set-collapsed!
                           :theme "dark"
                           :width sider-width
                           :collapsedWidth collapsed-width
                           :trigger nil
                           :style {:background "#172033"
                                   :boxShadow "2px 0 8px rgba(0,0,0,0.18)"}}
          [:div {:style {:height 56 :display "flex" :alignItems "center"
                         :justifyContent "center" :gap 10 :fontSize 17 :fontWeight 700
                         :color "#fff"
                         :background "#172033"}}
           [:div {:style {:width 28 :height 28 :borderRadius "50%"
                          :display "flex" :alignItems "center" :justifyContent "center"
                          :color "#79e0c2" :fontSize 20 :fontWeight 300}}
            "⌁"]
           (when-not collapsed [:span "若依管理系统"])]
          [:> Menu {:theme "dark"
                    :mode "inline"
                    :inlineCollapsed collapsed
                    :style {:background "#172033"
                            :fontSize 14
                            :borderInlineEnd "none"}
                    :selectedKeys (clj->js [(or (page->menu-key page) (name page))])
                    :defaultOpenKeys #js ["system"]
                    :items menu-items
                    :onClick (fn [e]
                               (let [k (.-key e)
                                     ;; Look up the route keyword from the path
                                     matched (router/match-route (str "/" k))
                                     page (or (:handler matched) (keyword k))
                                     _ (js/console.log "Page:" (str page))]
                                 (when (:handler matched)
                                   (rf/dispatch [:navigate page])
                                   (rf/dispatch [:tabs/add page (get labels page "页面") (get icons page)]))))}]]
         ;; Main area
         [:> Layout {:style {:background "#fff"}}
          [:> Layout.Header {:style {:padding "0 16px"
                                     :display "flex" :justifyContent "space-between"
                                     :alignItems "center" :height 56
                                     :background "#fff"
                                     :borderBottom "1px solid #e4e7ed"
                                     :boxShadow "0 1px 4px rgba(0,21,41,0.08)"}}
           ;; Left: hamburger + breadcrumb
           [:div {:style {:display "flex" :alignItems "center" :gap 12}}
            ;; Hamburger toggle button
            [:div {:style {:cursor "pointer" :padding "0 6px" :fontSize 21
                           :display "flex" :alignItems "center"
                           :color "#303133"
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
                  [:span {:style {:color "#c0c4cc"}} "/"])
                [:span {:style {:color (if (= idx (dec (count breadcrumbs))) "#97a8be" "#303133")
                                :fontWeight (if (= idx (dec (count breadcrumbs))) 400 500)}}
                 crumb]])]]
           [:div {:style {:display "flex" :alignItems "center" :gap 6}}
            ;; 搜索
            [:> Button {:type "text" :style {:fontSize 18 :color "#606266"} :icon (r/as-element [:> SearchOutlined])}]
            ;; GitHub
            [:> Button {:type "text" :style {:fontSize 18 :color "#606266"} :icon (r/as-element [:> GithubOutlined])
                        :onClick #(js/window.open "https://github.com/RedCreationTech/rouyi_clojure" "_blank")}]
            ;; 文档
            [:> Button {:type "text" :style {:fontSize 18 :color "#606266"} :icon (r/as-element [:> QuestionCircleOutlined])}]
            ;; 全屏
            [:> Button {:type "text" :style {:fontSize 18 :color "#606266"} :icon (r/as-element [:> ExpandOutlined])
                        :onClick #(let [doc js/document.documentElement]
                                    (if (.-fullscreenElement js/document)
                                      (.exitFullscreen js/document)
                                      (.requestFullscreen doc)))}]
            [:> Button {:type "text" :style {:fontSize 18 :color "#606266"} :icon (r/as-element [:> FontSizeOutlined])}]
            ;; 通知
            [:> Badge {:count 3 :size "small"}
             [:> Button {:type "text" :style {:fontSize 18 :color "#606266"} :icon (r/as-element [:> BellOutlined])}]]
            ;; 头像 + 下拉菜单
            [:> Dropdown {:menu {:items (clj->js [{:key "profile" :label "个人中心"}
                                                  {:key "logout" :label "退出登录" :danger true}])
                                 :onClick (fn [e]
                                            (case (.-key e)
                                              "profile" (rf/dispatch [:navigate :profile])
                                              "logout" (rf/dispatch [:auth/logout])
                                              nil))}}
             [:div {:style {:display "flex" :alignItems "center" :gap 8 :cursor "pointer" :padding "0 6px"}}
              [:> Avatar {:size 32
                          :style {:background "linear-gradient(135deg,#f7d7c4,#9bc9ff)"
                                  :color "#fff"
                                  :fontWeight 700}}
               "若"]
              [:span {:style {:fontSize 14 :fontWeight 600 :color "#303133"}} "若依"]]]]]
          ;; Tab 栏
          [tab-bar]
          ;; 内容区（加 Error Boundary，避免单个页面崩溃导致整个布局白屏）
          [:> Layout.Content {:style {:margin 0
                                      :padding 0
                                      :background "#fff"
                                      :minHeight "calc(100vh - 96px)"
                                      :paddingBottom 52
                                      :position "relative"}
                              :key (name page)
                              :class "tab-content-enter"}
           [error-boundary/boundary
            (case page
              :dashboard [dashboard/dashboard-page]
             :solution-home [business/solution-home-page]
             :project-info [project/project-page]
             :resource-standard [business/resource-page :standard]
             :resource-vector-kb [business/resource-page :vector-kb]
             :resource-structured-kb [business/resource-page :structured-kb]
             :resource-case [business/resource-page :case]
             :resource-atlas [business/resource-page :atlas]
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
             :gen [gen/gen-page]
             :swagger [swagger/swagger-page]
             :build [form-builder/form-builder-page]
             :file [file-manager/file-manager-page]
             [:div {:style {:padding 48 :textAlign "center" :color "#999" :fontSize 16}}
              "页面建设中"])]
           [:div {:style {:position "fixed" :right 16 :bottom 78
                          :width 44 :height 44 :borderRadius "50%"
                          :background "#e989aa" :color "#fff"
                          :display "flex" :alignItems "center" :justifyContent "center"
                          :fontSize 18 :fontWeight 700
                          :boxShadow "0 4px 12px rgba(233,137,170,0.35)"
                          :zIndex 20}}
            "LA"]
           [:div {:style {:position "fixed" :left (if collapsed collapsed-width sider-width) :right 0 :bottom 0
                          :height 52 :display "flex" :alignItems "center" :justifyContent "flex-end"
                          :padding "0 26px" :borderTop "1px solid #ebeef5"
                          :color "#808080" :fontSize 16 :background "#fff" :zIndex 10}}
            "Copyright © 2018-2026 RuoYi. All Rights Reserved."]]]]))
