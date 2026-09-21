(ns com.ruoyi.frontend.pages.server
  "服务器监控页面，按 RuoYi-Vue 服务监控布局展示 CPU、内存、JVM 与磁盘状态。"
  (:require
    ["@ant-design/icons" :refer [CloudServerOutlined DatabaseOutlined DesktopOutlined HddOutlined LaptopOutlined ReloadOutlined]]
    ["antd" :refer [Spin Table]]
    [com.ruoyi.frontend.antd :as antd]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def card-style
  {:background "#fff"
   :border "1px solid #e6ebf5"
   :borderRadius 4
   :boxShadow "0 2px 12px 0 rgba(0,0,0,0.06)"})


(defn- monitor-card
  "渲染 RuoYi 监控页面的白底卡片。"
  [{:keys [icon title style]} & children]
  (into
    [:div {:style (merge card-style style)}
     [:div {:style {:height 48
                    :display "flex"
                    :alignItems "center"
                    :gap 8
                    :padding "0 18px"
                    :borderBottom "1px solid #ebeef5"
                    :fontSize 16
                    :fontWeight 600
                    :color "#303133"}}
      icon
      [:span title]]]
    children))


(defn- value-cell
  "渲染表格值单元格，超过阈值时使用 RuoYi 风险红色。"
  ([value] (value-cell value false))
  ([value danger?]
   [:span {:style {:color (if danger? "#f56c6c" "#606266")}} (or value "-")]))


(defn- percent
  "格式化百分比数字。"
  [n]
  (str (.toFixed (js/Number. (or n 0)) 2) "%"))


(defn- format-mb
  "把 MB 数值格式化为 RuoYi 常用容量展示。"
  [mb]
  (let [n (js/Number. (or mb 0))]
    (if (>= n 1024)
      (str (.toFixed (/ n 1024) 2) "G")
      (str (.toFixed n 1) "M"))))


(defn- format-bytes
  "把字节数格式化为磁盘容量。"
  [bytes]
  (let [n (js/Number. (or bytes 0))]
    (cond
      (>= n 1073741824) (str (.toFixed (/ n 1073741824) 1) " GB")
      (>= n 1048576) (str (.toFixed (/ n 1048576) 1) " MB")
      (>= n 1024) (str (.toFixed (/ n 1024) 1) " KB")
      :else (str n " B"))))


(defn- info-table
  "用普通表格复刻 Element table 的细线和密度。"
  [headers rows]
  [:table {:style {:width "100%"
                   :borderCollapse "collapse"
                   :tableLayout "fixed"
                   :fontSize 14
                   :color "#606266"}}
   [:thead
    [:tr
     (for [[idx h] (map-indexed vector headers)]
       ^{:key idx}
       [:th {:style {:height 44
                     :padding "0 18px"
                     :borderBottom "1px solid #ebeef5"
                     :textAlign "left"
                     :fontWeight 600
                     :color "#909399"}}
        h])]]
   [:tbody
    (for [[idx row] (map-indexed vector rows)]
      ^{:key idx}
      [:tr
       (for [[cell-idx cell] (map-indexed vector row)]
         ^{:key cell-idx}
         [:td {:style {:height 45
                       :padding "0 18px"
                       :borderBottom "1px solid #ebeef5"
                       :verticalAlign "middle"
                       :wordBreak "break-all"
                       :lineHeight "22px"}}
          cell])])]])


(defn- cpu-card
  "渲染 CPU 信息卡片。"
  [{:keys [cpu]}]
  [monitor-card {:title "CPU" :icon (r/as-element [:> DesktopOutlined])}
   [info-table ["属性" "值"]
    [["核心数" [value-cell (:cpuNum cpu)]]
     ["用户使用率" [value-cell (percent (:used cpu)) (> (or (:used cpu) 0) 80)]]
     ["系统使用率" [value-cell (percent (:sys cpu)) (> (or (:sys cpu) 0) 80)]]
     ["当前空闲率" [value-cell (percent (:free cpu))]]]]])


(defn- memory-card
  "渲染内存与 JVM 内存卡片。"
  [{:keys [mem jvm]}]
  (let [mem-usage (:usage mem 0)
        jvm-usage (if (pos? (or (:max jvm) 0))
                    (* 100 (/ (or (:used jvm) 0) (:max jvm)))
                    0)]
    [monitor-card {:title "内存" :icon (r/as-element [:> DatabaseOutlined])}
     [info-table ["属性" "内存" "JVM"]
      [["总内存" [value-cell (format-mb (:total mem))] [value-cell (format-mb (:max jvm))]]
       ["已用内存" [value-cell (format-mb (:used mem))] [value-cell (format-mb (:used jvm))]]
       ["剩余内存" [value-cell (format-mb (:free mem))] [value-cell (format-mb (:free jvm))]]
       ["使用率" [value-cell (percent mem-usage) (> mem-usage 80)] [value-cell (percent jvm-usage) (> jvm-usage 80)]]]]]))


(defn- server-info-card
  "渲染服务器基本信息。"
  [{:keys [sys]}]
  [monitor-card {:title "服务器信息" :icon (r/as-element [:> LaptopOutlined])}
   [info-table ["" "" "" ""]
    [["服务器名称" [value-cell (:computerName sys)] "操作系统" [value-cell (:osName sys)]]
     ["服务器IP" [value-cell (:computerIp sys)] "系统架构" [value-cell (:osArch sys)]]]]])


(defn- jvm-info-card
  "渲染 Java 虚拟机信息。"
  [{:keys [jvm sys]}]
  [monitor-card {:title "Java虚拟机信息" :icon (r/as-element [:> CloudServerOutlined])}
   [info-table ["" "" "" ""]
    [["Java名称" [value-cell (:jvmName jvm)] "Java版本" [value-cell (:jvmVersion jvm)]]
     ["启动时间" [value-cell (:startTime jvm)] "运行时长" [value-cell (:runTime jvm)]]
     ["安装路径" [value-cell (:jvmHome jvm)] "" ""]
     ["项目路径" [value-cell (:userDir sys)] "" ""]
     ["运行参数" [:span {:style {:color "#606266" :lineHeight "22px"}} (or (:inputArgs jvm) "-")] "" ""]]]])


(defn- disk-card
  "渲染磁盘状态表格。"
  [{:keys [disk]}]
  [monitor-card {:title "磁盘状态" :icon (r/as-element [:> HddOutlined])}
   [:> Table {:size "small"
              :pagination false
              :rowKey "dirName"
              :dataSource (clj->js (or disk []))
              :columns (clj->js
                         [{:title "盘符路径" :dataIndex "dirName" :key "dirName"}
                          {:title "文件系统" :dataIndex "sysTypeName" :key "sysTypeName"}
                          {:title "盘符类型" :dataIndex "typeName" :key "typeName"}
                          {:title "总大小" :dataIndex "total" :key "total"
                           :render (fn [v] (r/as-element [value-cell (format-bytes v)]))}
                          {:title "可用大小" :dataIndex "free" :key "free"
                           :render (fn [v] (r/as-element [value-cell (format-bytes v)]))}
                          {:title "已用大小" :dataIndex "used" :key "used"
                           :render (fn [v] (r/as-element [value-cell (format-bytes v)]))}
                          {:title "已用百分比" :dataIndex "usage" :key "usage"
                           :render (fn [v] (r/as-element [value-cell (percent v) (> (or v 0) 80)]))}])}]])


(defn server-page
  "服务器监控入口组件,加载并展示服务运行状态."
  []
  (hooks/use-effect
    (fn []
      (rf/dispatch [:server/fetch])
      js/undefined)
    [])
  (let [server-data @(rf/subscribe [:server/data])
        loading? @(rf/subscribe [:server/loading?])]
    [:div {:style {:padding 16 :background "#f5f7fa" :minHeight "calc(100vh - 112px)"}}
     [antd/button {:icon (r/as-element [:> ReloadOutlined])
                   :style {:float "right" :marginBottom 12}
                   :onClick #(rf/dispatch [:server/fetch])}
      "刷新"]
     [:div {:style {:clear "both"}}]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48 :background "#fff"}}
        [:> Spin {:size "large"}]]
       (when server-data
         [:div {:style {:display "flex" :flexDirection "column" :gap 14}}
          [:div {:style {:display "grid"
                         :gridTemplateColumns "repeat(auto-fit, minmax(420px, 1fr))"
                         :gap 14}}
           [cpu-card server-data]
           [memory-card server-data]]
          [server-info-card server-data]
          [jvm-info-card server-data]
          [disk-card server-data]]))]))
