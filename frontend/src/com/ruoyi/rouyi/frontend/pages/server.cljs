(ns com.ruoyi.rouyi.frontend.pages.server
  "服务器监控页面 — RuoYi 风格。"
  (:require
    [reagent.core :as r]
    [reagent.hooks :as hooks]
    [re-frame.core :as rf]
    ["@ant-design/icons" :refer [ReloadOutlined]]
    ["antd" :refer [Progress Table Spin]]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

;; ─── 进度条组件 ──────────────────────────────────────────────────────

(defn- usage-bar [{:keys [label used total unit color]}]
  (let [percent (if (and total (pos? total))
                  (min 100 (Math/round (* 100 (/ used total))))
                  0)
        color (or color
                  (cond
                    (> percent 80) "#ff4d4f"
                    (> percent 60) "#faad14"
                    :else "#52c41a"))]
    [:div {:style {:marginBottom 20}}
     [:div {:style {:display "flex" :justifyContent "space-between" :marginBottom 8}}
      [:span {:style {:fontSize 14 :fontWeight 500}} label]
      [:span {:style {:fontSize 14 :color "#666"}} (str percent "%")]]
     [:> Progress {:percent percent :strokeColor color :showInfo false
                     :strokeWidth 10 :trailColor "#f0f0f0"}]
     [:div {:style {:display "flex" :justifyContent "space-between" :marginTop 4 :fontSize 12 :color "#999"}}
      [:span (str "已用: " (if unit (unit used) used))]
      [:span (str "总计: " (if unit (unit total) total))]]]))

;; ─── 信息行组件 ──────────────────────────────────────────────────────

(defn- info-row [{:keys [label value]}]
  [:div {:style {:display "flex" :justifyContent "space-between" :padding "12px 0"
                 :borderBottom "1px solid var(--ant-color-split, #f0f0f0)"}}
   [:span {:style {:color "#666"}} label]
   [:span {:style {:fontWeight 500 :color "#333"}} (or value "-")]])

;; ─── 格式化函数 ──────────────────────────────────────────────────────

(defn- format-mb [mb]
  (if (> mb 1024)
    (str (Math/round (/ mb 1024)) " GB")
    (str (Math/round mb) " MB")))

(defn- format-bytes [bytes]
  (cond
    (> bytes 1073741824) (str (Math/round (/ bytes 1073741824)) " GB")
    (> bytes 1048576) (str (Math/round (/ bytes 1048576)) " MB")
    (> bytes 1024) (str (Math/round (/ bytes 1024)) " KB")
    :else (str bytes " B")))

(defn- format-runtime [ms]
  (let [seconds (quot ms 1000)
        minutes (quot seconds 60)
        hours (quot minutes 60)
        days (quot hours 24)]
    (cond
      (pos? days) (str days "天" (mod hours 24) "小时" (mod minutes 60) "分钟")
      (pos? hours) (str hours "小时" (mod minutes 60) "分钟")
      :else (str minutes "分钟"))))

;; ─── CPU 区域 ──────────────────────────────────────────────────────

(defn- cpu-section [{:keys [cpu]}]
  (let [cpu-num (:cpuNum cpu 0)
        used (:used cpu 0)
        sys (:sys cpu 0)
        free (:free cpu 0)
        wait (:wait cpu 0)]
    [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 20}}
      [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600 :display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:display "inline-block" :width 4 :height 20 :background "#1677ff" :borderRadius 2}}]
       "CPU"]
      [:span {:style {:fontSize 14 :color "#666"}} (str "核心数: " cpu-num)]]
     [usage-bar {:label "总使用率" :used used :total 100 :unit #(str % "%")}]
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr 1fr" :gap 16 :marginTop 16}}
      [:div {:style {:textAlign "center" :padding 12 :background "#f6ffed" :borderRadius 8}}
       [:div {:style {:fontSize 24 :fontWeight 600 :color "#52c41a"}} (str (Math/round free) "%")]
       [:div {:style {:fontSize 12 :color "#999" :marginTop 4}} "空闲率"]]
      [:div {:style {:textAlign "center" :padding 12 :background "#e6f7ff" :borderRadius 8}}
       [:div {:style {:fontSize 24 :fontWeight 600 :color "#1677ff"}} (str (Math/round used) "%")]
       [:div {:style {:fontSize 12 :color "#999" :marginTop 4}} "用户使用率"]]
      [:div {:style {:textAlign "center" :padding 12 :background "#fff7e6" :borderRadius 8}}
       [:div {:style {:fontSize 24 :fontWeight 600 :color "#faad14"}} (str (Math/round sys) "%")]
       [:div {:style {:fontSize 12 :color "#999" :marginTop 4}} "系统使用率"]]
      [:div {:style {:textAlign "center" :padding 12 :background "#fff1f0" :borderRadius 8}}
       [:div {:style {:fontSize 24 :fontWeight 600 :color "#ff4d4f"}} (str (Math/round wait) "%")]
       [:div {:style {:fontSize 12 :color "#999" :marginTop 4}} "等待率"]]]]))

;; ─── 内存区域 ──────────────────────────────────────────────────────

(defn- memory-section [{:keys [mem jvm]}]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 16 :marginBottom 16}}
   ;; 系统内存
   [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24}}
    [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 20}}
     [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600 :display "flex" :alignItems "center" :gap 8}}
      [:span {:style {:display "inline-block" :width 4 :height 20 :background "#52c41a" :borderRadius 2}}]
      "内存"]
     [:span {:style {:fontSize 14 :color "#666"}} (str "总计: " (format-mb (:total mem 0)))]]
    [usage-bar {:label "使用率" :used (:used mem 0) :total (:total mem 1) :unit format-mb}]
    [:div {:style {:display "flex" :justifyContent "space-between" :marginTop 8 :fontSize 13 :color "#999"}}
     [:span (str "已用: " (format-mb (:used mem 0)))]
     [:span (str "剩余: " (format-mb (:free mem 0)))]]]
   ;; JVM 内存
   [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24}}
    [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 20}}
     [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600 :display "flex" :alignItems "center" :gap 8}}
      [:span {:style {:display "inline-block" :width 4 :height 20 :background "#722ed1" :borderRadius 2}}]
      "JVM"]
     [:span {:style {:fontSize 14 :color "#666"}} (str "最大: " (format-mb (:max jvm 0)))]]
    [usage-bar {:label "使用率" :used (:used jvm 0) :total (:max jvm 1) :unit format-mb}]
    [:div {:style {:display "flex" :justifyContent "space-between" :marginTop 8 :fontSize 13 :color "#999"}}
     [:span (str "已用: " (format-mb (:used jvm 0)))]
     [:span (str "剩余: " (format-mb (max 0 (- (:max jvm 0) (:used jvm 0)))))]]]])

;; ─── 服务器信息区域 ──────────────────────────────────────────────────────

(defn- server-info-section [{:keys [sys]}]
  [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 20}}
    [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600 :display "flex" :alignItems "center" :gap 8}}
     [:span {:style {:display "inline-block" :width 4 :height 20 :background "#13c2c2" :borderRadius 2}}]
     "服务器信息"]]
   [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 0}}
    [info-row {:label "服务器名称" :value (:computerName sys)}]
    [info-row {:label "操作系统" :value (:osName sys)}]
    [info-row {:label "服务器IP" :value (:computerIp sys)}]
    [info-row {:label "系统架构" :value (:osArch sys)}]]])

;; ─── Java 虚拟机信息区域 ──────────────────────────────────────────────────────

(defn- jvm-info-section [{:keys [jvm sys]}]
  [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 20}}
    [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600 :display "flex" :alignItems "center" :gap 8}}
     [:span {:style {:display "inline-block" :width 4 :height 20 :background "#eb2f96" :borderRadius 2}}]
     "Java虚拟机信息"]]
   [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 0}}
    [info-row {:label "Java名称" :value (:jvmName jvm)}]
    [info-row {:label "Java版本" :value (:jvmVersion jvm)}]
    [info-row {:label "启动时间" :value (:startTime jvm)}]
    [info-row {:label "运行时长" :value (:runTime jvm)}]
    [info-row {:label "安装路径" :value (:jvmHome jvm)}]
    [info-row {:label "项目路径" :value (:userDir sys)}]]])

;; ─── 磁盘信息区域 ──────────────────────────────────────────────────────

(defn- disk-section [{:keys [disk]}]
  [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 20}}
    [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600 :display "flex" :alignItems "center" :gap 8}}
     [:span {:style {:display "inline-block" :width 4 :height 20 :background "#faad14" :borderRadius 2}}]
     "磁盘状态"]]
   [:> Table {:size "small" :pagination false :rowKey "dirName"
                :dataSource (clj->js (or disk []))
                :columns (clj->js
                          [{:title "盘符路径" :dataIndex "dirName" :key "dirName"}
                           {:title "文件系统" :dataIndex "sysTypeName" :key "sysTypeName"}
                           {:title "总大小" :dataIndex "total" :key "total"
                            :render (fn [v] (r/as-element [:span (format-bytes v)]))}
                           {:title "可用大小" :dataIndex "free" :key "free"
                            :render (fn [v] (r/as-element [:span (format-bytes v)]))}
                           {:title "已用大小" :dataIndex "used" :key "used"
                            :render (fn [v] (r/as-element [:span (format-bytes v)]))}
                           {:title "已用百分比" :dataIndex "usage" :key "usage"
                            :render (fn [v]
                                      (r/as-element
                                       [:> Progress {:percent (Math/round v) :size "small"
                                                       :strokeColor (cond
                                                                      (> v 80) "#ff4d4f"
                                                                      (> v 60) "#faad14"
                                                                      :else "#52c41a")}]))}])}]])

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn server-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:server/fetch])
     js/undefined)
   [])
  (let [server-data @(rf/subscribe [:server/data])
        loading? @(rf/subscribe [:server/loading?])]
    [:div
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
      [:h3 {:style {:margin 0}} "服务监控"]
      [antd/button {:icon (r/as-element [:> ReloadOutlined])
                    :onClick #(rf/dispatch [:server/fetch])}
       "刷新"]]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48}}
        [:> Spin {:size "large"}]]
       (when server-data
         [:div
          [cpu-section server-data]
          [memory-section server-data]
          [server-info-section server-data]
          [jvm-info-section server-data]
          [disk-section server-data]]))]))
