(ns com.ruoyi.frontend.components.icon-picker
  "图标选择器与图标解析组件."
  (:require
    ["@ant-design/icons" :refer [AppstoreOutlined MenuOutlined FunctionOutlined
                                 DashboardOutlined SettingOutlined UserOutlined TeamOutlined
                                 SafetyOutlined ApartmentOutlined TagOutlined BookOutlined
                                 ToolOutlined MonitorOutlined ScheduleOutlined DatabaseOutlined
                                 CloudOutlined CodeOutlined FormOutlined ProfileOutlined
                                 BellOutlined ContainerOutlined KeyOutlined FileTextOutlined
                                 HomeOutlined MailOutlined LinkOutlined PushpinOutlined
                                 StarOutlined HeartOutlined LockOutlined UnlockOutlined
                                 ShopOutlined ShoppingOutlined TrophyOutlined BugOutlined
                                 ThunderboltOutlined FireOutlined ExperimentOutlined
                                 PieChartOutlined BarChartOutlined LineChartOutlined
                                 TableOutlined ProjectOutlined FolderOpenOutlined
                                 PictureOutlined DeploymentUnitOutlined ClusterOutlined
                                 AuditOutlined HistoryOutlined RocketOutlined
                                 CalendarOutlined CustomerServiceOutlined
                                 MoneyCollectOutlined]]
    [clojure.string :as str]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


;; ─── 图标映射 ──────────────────────────────────────────────────────

(def ^:private icon-name->component
  "图标名称到 React 组件类的映射."
  {"AppstoreOutlined" AppstoreOutlined
   "MenuOutlined" MenuOutlined
   "FunctionOutlined" FunctionOutlined
   "DashboardOutlined" DashboardOutlined
   "SettingOutlined" SettingOutlined
   "UserOutlined" UserOutlined
   "TeamOutlined" TeamOutlined
   "SafetyOutlined" SafetyOutlined
   "ApartmentOutlined" ApartmentOutlined
   "TagOutlined" TagOutlined
   "BookOutlined" BookOutlined
   "ToolOutlined" ToolOutlined
   "MonitorOutlined" MonitorOutlined
   "ScheduleOutlined" ScheduleOutlined
   "DatabaseOutlined" DatabaseOutlined
   "CloudOutlined" CloudOutlined
   "CodeOutlined" CodeOutlined
   "FormOutlined" FormOutlined
   "ProfileOutlined" ProfileOutlined
   "BellOutlined" BellOutlined
   "ContainerOutlined" ContainerOutlined
   "KeyOutlined" KeyOutlined
   "FileTextOutlined" FileTextOutlined
   "HomeOutlined" HomeOutlined
   "MailOutlined" MailOutlined
   "LinkOutlined" LinkOutlined
   "PushpinOutlined" PushpinOutlined
   "StarOutlined" StarOutlined
   "HeartOutlined" HeartOutlined
   "LockOutlined" LockOutlined
   "UnlockOutlined" UnlockOutlined
   "ShopOutlined" ShopOutlined
   "ShoppingOutlined" ShoppingOutlined
   "TrophyOutlined" TrophyOutlined
   "BugOutlined" BugOutlined
   "ThunderboltOutlined" ThunderboltOutlined
   "FireOutlined" FireOutlined
   "ExperimentOutlined" ExperimentOutlined
   "PieChartOutlined" PieChartOutlined
   "BarChartOutlined" BarChartOutlined
   "LineChartOutlined" LineChartOutlined
   "TableOutlined" TableOutlined
   "ProjectOutlined" ProjectOutlined
   "FolderOpenOutlined" FolderOpenOutlined
   "PictureOutlined" PictureOutlined
   "DeploymentUnitOutlined" DeploymentUnitOutlined
   "ClusterOutlined" ClusterOutlined
   "AuditOutlined" AuditOutlined
   "HistoryOutlined" HistoryOutlined
   "RocketOutlined" RocketOutlined
   "CalendarOutlined" CalendarOutlined
   "CustomerServiceOutlined" CustomerServiceOutlined
   "MoneyCollectOutlined" MoneyCollectOutlined})


(def ruoyi-icon-names
  "RuoYi-Vue src/assets/icons/svg 下的图标名称."
  ["404" "bell" "bug" "build" "button" "cascader" "chart" "checkbox" "clipboard" "code" "color" "component" "dashboard" "date" "date-range" "dict" "documentation" "download" "drag" "druid" "edit" "education" "email" "enter" "example" "excel" "exit-fullscreen" "eye" "eye-open" "form" "fullscreen" "github" "guide" "icon" "input" "international" "job" "language" "link" "list" "lock" "log" "logininfor" "message" "money" "monitor" "more-up" "nested" "number" "online" "password" "pdf" "people" "peoples" "phone" "post" "qq" "question" "radio" "rate" "redis" "redis-list" "row" "search" "select" "server" "shopping" "size" "skill" "slider" "star" "swagger" "switch" "system" "tab" "table" "textarea" "theme" "time" "time-range" "tool" "tree" "tree-table" "upload" "user" "validCode" "wechat" "zip"])


(def ^:private ruoyi-icon-set (set ruoyi-icon-names))


(def icon-options
  "图标选择器展示的图标选项."
  (mapv (fn [name] {:name name}) ruoyi-icon-names))


(def ^:private alias->name
  "常用图标别名到标准组件名称的映射."
  {"system" "SettingOutlined"
   "monitor" "MonitorOutlined"
   "tool" "ToolOutlined"
   "user" "UserOutlined"
   "role" "SafetyOutlined"
   "menu" "BookOutlined"
   "dept" "ApartmentOutlined"
   "post" "ContainerOutlined"
   "dict" "TagOutlined"
   "config" "ToolOutlined"
   "notice" "BellOutlined"
   "oper-log" "FileTextOutlined"
   "login-log" "KeyOutlined"
   "online" "TeamOutlined"
   "job" "ScheduleOutlined"
   "server" "CloudOutlined"
   "cache" "DatabaseOutlined"
   "gen" "CodeOutlined"
   "build" "FormOutlined"
   "profile" "ProfileOutlined"
   "dashboard" "DashboardOutlined"
   ;; RuoYi-Vue 兼容别名
   "peoples" "TeamOutlined"
   "tree-table" "TableOutlined"
   "tree" "ApartmentOutlined"
   "logininfor" "KeyOutlined"
   "edit" "FormOutlined"
   "message" "MailOutlined"
   "swagger" "LinkOutlined"
   "list" "TableOutlined"
   "chart" "PieChartOutlined"
   "form" "FormOutlined"
   "code" "CodeOutlined"})


(defn normalize-icon-name
  "规范化图标名称,支持 RuoYi svg 名称,标准 AntD 名称或常用别名."
  [name]
  (when (and name (not= name "#") (seq name))
    (let [s (str/trim name)]
      (or (when (contains? ruoyi-icon-set s) s)
          (get alias->name s)
          (when (contains? icon-name->component s) s)
          (when (re-matches #"[A-Za-z]+Outlined" s) s)))))


(defn icon-component
  "根据名称返回图标 React 组件类,未找到时返回 nil."
  [name]
  (when-let [n (normalize-icon-name name)]
    (get icon-name->component n)))


(defn- icon-url
  [name]
  (str "/icons/svg/" name ".svg"))


(defn icon-element
  "根据名称返回图标 React 元素,未找到时返回 nil."
  ([name]
   (icon-element name {}))
  ([name props]
   (when-let [n (normalize-icon-name name)]
     (if (contains? ruoyi-icon-set n)
       (let [url (icon-url n)
             base-style {:width 16
                         :height 16
                         :display "inline-block"
                         :verticalAlign "-3px"
                         :backgroundColor "currentColor"
                         :WebkitMaskImage (str "url(" url ")")
                         :maskImage (str "url(" url ")")
                         :WebkitMaskRepeat "no-repeat"
                         :maskRepeat "no-repeat"
                         :WebkitMaskPosition "center"
                         :maskPosition "center"
                         :WebkitMaskSize "contain"
                         :maskSize "contain"
                         :flexShrink 0}
             props (assoc props :style (merge base-style (:style props)))]
         (r/as-element [:span (merge {:role "img"
                                      :aria-label n
                                      :title n}
                                     props)]))
       (when-let [Icon (get icon-name->component n)]
         (r/as-element [:> Icon props]))))))


;; ─── 图标选择器 ──────────────────────────────────────────────────────

(defn icon-picker
  "图标选择器组件 -- 对齐 RuoYi-Vue IconSelect.
   参数::value 当前选中的图标名称,:on-change/:onChange 选择回调,:placeholder 占位文本."
  [{:keys [value on-change onChange placeholder]}]
  (let [on-change (or on-change onChange)
        placeholder (or placeholder "点击选择图标")
        [keyword set-keyword!] (hooks/use-state "")
        filtered (if (seq keyword)
                   (filterv #(str/includes? % keyword) ruoyi-icon-names)
                   ruoyi-icon-names)]
    [com.ruoyi.frontend.antd/popover
     {:trigger "click"
      :placement "bottomLeft"
      :styles {:body {:width 460 :padding 10}}
      :content
      (r/as-element
        [:div {:style {:width "100%"}}
         [com.ruoyi.frontend.antd/input {:placeholder "请输入图标名称"
                                         :allowClear true
                                         :value keyword
                                         :style {:marginBottom 5}
                                         :onChange #(set-keyword! (.. % -target -value))}]
         [:div {:style {:height 200 :overflow "auto"}}
          [:div {:style {:display "flex" :flexWrap "wrap"}}
           (for [item filtered]
             ^{:key item}
             [:div {:style {:width "33.3333%"
                            :height 25
                            :lineHeight "25px"
                            :cursor "pointer"
                            :display "flex"}
                    :on-click #(do
                                 (when on-change (on-change item))
                                 (.click js/document.body))}
              [:div {:style {:display "flex"
                             :alignItems "center"
                             :maxWidth "100%"
                             :height "100%"
                             :padding "0 5px"
                             :borderRadius 5
                             :background (when (= value item) "#ececec")}}
               [icon-element item {:style {:width 16 :height 25 :flexShrink 0}}]
               [:span {:style {:display "inline-block"
                               :paddingLeft 2
                               :overflow "hidden"
                               :textOverflow "ellipsis"
                               :whiteSpace "nowrap"}}
                item]]])]]])}
     [com.ruoyi.frontend.antd/input
      {:readOnly true
       :value (or value "")
       :placeholder placeholder
       :prefix (when (seq (or value "")) (icon-element value {:style {:width 25 :height 16}}))}]]))
