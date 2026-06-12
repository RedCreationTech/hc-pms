(ns com.ruoyi.rouyi.frontend.components.icon-picker
  "图标选择器与图标解析组件。"
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
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
                                TableOutlined]]))

;; ─── 图标映射 ──────────────────────────────────────────────────────

(def ^:private icon-name->component
  "图标名称到 React 组件类的映射。"
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
   "TableOutlined" TableOutlined})

(def icon-options
  "图标选择器展示的图标选项。"
  (mapv (fn [[name component]] {:name name :icon component})
        icon-name->component))

(def ^:private alias->name
  "常用图标别名到标准组件名称的映射。"
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
   "dashboard" "DashboardOutlined"})

(defn normalize-icon-name
  "规范化图标名称，支持标准名称或常用别名。"
  [name]
  (when (and name (not= name "#") (seq name))
    (let [s (str/trim name)]
      (or (get alias->name s)
          (when (contains? icon-name->component s) s)
          (when (re-matches #"[A-Za-z]+Outlined" s) s)))))

(defn icon-component
  "根据名称返回图标 React 组件类，未找到时返回 nil。"
  [name]
  (when-let [n (normalize-icon-name name)]
    (get icon-name->component n)))

(defn icon-element
  "根据名称返回图标 React 元素，未找到时返回 nil。"
  ([name]
   (icon-element name {}))
  ([name props]
   (when-let [Icon (icon-component name)]
     (r/as-element [:> Icon props]))))

;; ─── 图标选择器 ──────────────────────────────────────────────────────

(defn icon-picker
  "图标选择器组件 — 网格展示常用图标。
   参数：:value 当前选中的图标名称，:on-change 选择回调，:placeholder 占位文本。"
  [{:keys [value on-change placeholder]}]
  (let [placeholder (or placeholder "选择图标")]
    [com.ruoyi.rouyi.frontend.antd/popover
     {:trigger "click"
      :content
      (r/as-element
       [:div {:style {:display "grid"
                      :gridTemplateColumns "repeat(8, 1fr)"
                      :gap 4
                      :maxHeight 300
                      :overflow "auto"
                      :width 380
                      :padding 8}}
        (for [item icon-options]
          ^{:key (:name item)}
          [com.ruoyi.rouyi.frontend.antd/tooltip {:title (:name item)}
           [:div {:style {:display "flex"
                          :alignItems "center"
                          :justifyContent "center"
                          :width 36 :height 36
                          :borderRadius 4
                          :cursor "pointer"
                          :border (if (= value (:name item))
                                    "2px solid #1677ff"
                                    "1px solid #f0f0f0")
                          :background (when (= value (:name item)) "#e6f4ff")}
                  :on-click #(on-change (:name item))}
            [:> (:icon item) {:style {:fontSize 16}}]]])])}
     [com.ruoyi.rouyi.frontend.antd/button
      {:style {:width "100%" :textAlign "left"}}
      (if-let [Icon (icon-component value)]
        (r/as-element
         [com.ruoyi.rouyi.frontend.antd/space
          [:> Icon]
          [:span value]])
        placeholder)]]))
