(ns com.ruoyi.frontend.pages.cache
  "缓存监控页面，按 RuoYi-Vue 缓存监控布局展示 Redis 基本信息、命令统计和内存信息。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [DashboardOutlined DeleteOutlined PieChartOutlined ReloadOutlined]]
   ["antd" :refer [Modal Spin]]
   [com.ruoyi.frontend.antd :as antd]))

(def card-style
  {:background "#fff"
   :border "1px solid #e6ebf5"
   :borderRadius 4
   :boxShadow "0 2px 12px 0 rgba(0,0,0,0.06)"})

(defn- monitor-card
  "渲染 RuoYi 风格监控卡片。"
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

(defn- info-grid
  "渲染基本信息的四列三行表格。"
  [items]
  [:table {:style {:width "100%"
                   :borderCollapse "collapse"
                   :tableLayout "fixed"
                   :fontSize 14
                   :color "#606266"}}
   [:tbody
    (for [[idx row] (map-indexed vector (partition 4 4 nil items))]
      ^{:key idx}
      [:tr
       (for [[cell-idx item] (map-indexed vector row)]
         ^{:key cell-idx}
         [:td {:style {:height 56
                       :padding "0 18px"
                       :borderBottom "1px solid #ebeef5"
                       :verticalAlign "middle"}}
          [:div {:style {:fontWeight 500 :color "#606266"}} (:label item)]
          [:div {:style {:marginTop 4 :color "#909399"}} (or (:value item) "")]])])]])

(defn- command-rows
  "把命令统计转换为柱状图数据。"
  [stats]
  (let [rows (or (seq stats)
                 [{:name "get" :value 0}
                  {:name "hit" :value 0}
                  {:name "miss" :value 0}
                  {:name "clear" :value 0}])
        max-value (max 1 (apply max (map #(or (:value %) 0) rows)))]
    (mapv #(assoc % :percent (* 100 (/ (or (:value %) 0) max-value))) rows)))

(defn- command-chart
  "用轻量 SVG/HTML 复刻 RuoYi 命令统计图区域。"
  [stats]
  (let [rows (command-rows stats)]
    [:div {:style {:height 360 :padding "22px 34px 28px"}}
     [:div {:style {:height 280
                    :display "flex"
                    :alignItems "flex-end"
                    :justifyContent "center"
                    :gap 44
                    :borderBottom "1px solid #dcdfe6"
                    :borderLeft "1px solid #dcdfe6"}}
      (for [{:keys [name value percent]} rows]
        ^{:key name}
        [:div {:style {:width 58 :display "flex" :flexDirection "column" :alignItems "center"}}
         [:div {:style {:fontSize 12 :color "#606266" :marginBottom 6}} value]
         [:div {:style {:width 28
                        :height (max 4 (* 2.35 percent))
                        :background "#409eff"
                        :borderRadius "2px 2px 0 0"}}]
         [:div {:style {:fontSize 12 :color "#606266" :marginTop 8}} name]])]
     [:div {:style {:textAlign "center" :fontSize 12 :color "#909399" :marginTop 12}} "命令"]]))

(defn- donut-chart
  "用 SVG 复刻内存占用环形图。"
  [{:keys [memoryUsed memoryMax]}]
  (let [used (or memoryUsed 0)
        total (max 1 (or memoryMax 1))
        percent (min 100 (* 100 (/ used total)))
        dash (* 2.64 percent)]
    [:div {:style {:height 360
                   :display "flex"
                   :alignItems "center"
                   :justifyContent "center"
                   :flexDirection "column"}}
     [:svg {:width 240 :height 240 :viewBox "0 0 240 240"}
      [:circle {:cx 120 :cy 120 :r 84 :fill "none" :stroke "#e4e7ed" :strokeWidth 30}]
      [:circle {:cx 120 :cy 120 :r 84 :fill "none" :stroke "#67c23a" :strokeWidth 30
                :strokeLinecap "round"
                :strokeDasharray (str dash " 264")
                :transform "rotate(-90 120 120)"}]
      [:text {:x 120 :y 112 :textAnchor "middle" :fontSize 28 :fill "#303133" :fontWeight 600}
       (str (.toFixed (js/Number. percent) 1) "%")]
      [:text {:x 120 :y 140 :textAnchor "middle" :fontSize 13 :fill "#909399"}
       (str used "M / " total "M")]]
     [:div {:style {:fontSize 12 :color "#909399"}} "内存使用率"]]))

(defn- basic-items
  "生成 RuoYi 缓存基本信息字段。"
  [cache-data]
  [{:label "Redis版本" :value (or (:redisVersion cache-data) (:type cache-data) "Clojure Atom")}
   {:label "运行模式" :value (or (:redisMode cache-data) "standalone")}
   {:label "端口" :value (or (:tcpPort cache-data) "-")}
   {:label "客户端数" :value (or (:connectedClients cache-data) 0)}
   {:label "运行时间(天)" :value (or (:uptimeInDays cache-data) 0)}
   {:label "使用内存" :value (str (or (:memoryUsed cache-data) 0) "M")}
   {:label "使用CPU" :value (or (:usedCpu cache-data) "-")}
   {:label "内存配置" :value (str (or (:memoryMax cache-data) 0) "M")}
   {:label "缓存名称" :value (or (:name cache-data) "Memory Cache")}
   {:label "AOF是否开启" :value (or (:aofEnabled cache-data) "否")}
   {:label "RDB是否成功" :value (or (:rdbLastSaveStatus cache-data) "是")}
   {:label "Key数量" :value (or (:keysCount cache-data) 0)}
   {:label "网络入口/出口" :value (or (:networkIo cache-data) "-")}])

(defn cache-page
  "缓存监控入口组件，加载并展示缓存运行状态。"
  []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:cache/fetch-info])
     (rf/dispatch [:cache/fetch-names])
     js/undefined)
   [])
  (let [cache-data @(rf/subscribe [:cache/data])
        value @(rf/subscribe [:cache/value])
        value-visible? @(rf/subscribe [:cache/value-visible?])
        loading? @(rf/subscribe [:cache/loading?])
        command-stats (or (:commandStats cache-data) [])]
    [:div {:style {:padding 16 :background "#f5f7fa" :minHeight "calc(100vh - 112px)"}}
     [:div {:style {:display "flex" :justifyContent "flex-end" :gap 8 :marginBottom 12}}
      [antd/button {:icon (r/as-element [:> ReloadOutlined])
                    :onClick #(do (rf/dispatch [:cache/fetch-info])
                                  (rf/dispatch [:cache/fetch-names]))}
       "刷新"]
      [antd/popconfirm
       {:title "确认清空全部缓存？"
        :onConfirm #(rf/dispatch [:cache/clear])}
       [antd/button {:danger true :icon (r/as-element [:> DeleteOutlined])}
        "清空"]]]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48 :background "#fff"}}
        [:> Spin {:size "large"}]]
       [:div {:style {:display "flex" :flexDirection "column" :gap 14}}
        [monitor-card {:title "基本信息" :icon (r/as-element [:> DashboardOutlined])}
         [info-grid (basic-items cache-data)]]
        [:div {:style {:display "grid"
                       :gridTemplateColumns "repeat(auto-fit, minmax(420px, 1fr))"
                       :gap 14}}
         [monitor-card {:title "命令统计" :icon (r/as-element [:> PieChartOutlined])}
          [command-chart command-stats]]
         [monitor-card {:title "内存信息" :icon (r/as-element [:> PieChartOutlined])}
          [donut-chart cache-data]]]])
     [:> Modal {:title "缓存值"
                :open value-visible?
                :footer nil
                :onCancel #(rf/dispatch [:cache/close-value])
                :style {:width 560}}
      [:pre {:style {:background "#f6f8fa"
                     :padding 16
                     :borderRadius 4
                     :maxHeight 400
                     :overflow "auto"
                     :margin 0
                     :fontSize 13
                     :whiteSpace "pre-wrap"
                     :wordBreak "break-all"}}
       (or value "")]]]))
