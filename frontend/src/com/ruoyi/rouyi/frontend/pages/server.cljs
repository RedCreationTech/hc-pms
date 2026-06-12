(ns com.ruoyi.rouyi.frontend.pages.server
  "服务器监控页面。"
  (:require
    [reagent.hooks :as hooks]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- progress-item [{:keys [label used total color]}]
  (let [percent (if (and total (pos? total))
                  (Math/round (* 100 (/ used total)))
                  0)]
    [:div {:style {:marginBottom 16}}
     [:div {:style {:display "flex" :justifyContent "space-between" :marginBottom 4}}
      [:span {:style {:fontWeight 500}} label]
      [:span {:style {:color "#666"}} (str used " / " total " MB")]]
     [:div {:style {:background "var(--ant-color-bg-layout, #f5f5f5)" :borderRadius 4 :height 20 :overflow "hidden"}}
      [:div {:style {:background (or color "#1677ff")
                     :width (str percent "%")
                     :height "100%"
                     :borderRadius 4
                     :transition "width 0.3s"}}]]]))

(defn- info-card [{:keys [title children]}]
  [:div {:style {:background "var(--ant-color-bg-container, #fff)"
                 :borderRadius 8
                 :padding 24
                 :marginBottom 16
                 :boxShadow "0 1px 2px rgba(0,0,0,0.1)"}}
   [:h4 {:style {:margin "0 0 16px 0" :fontSize 16 :fontWeight 600}} title]
   children])

(defn- server-info-section [server-data]
  (let [os (:os server-data)
        arch (:arch server-data)
        java-version (:javaVersion server-data)
        java-vm (:javaVm server-data)
        processors (:processors server-data)]
    [info-card {:title "服务器信息"
                :children [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 16}}
                           [:div [:span {:style {:color "#666"}} "操作系统："] [:span os]]
                           [:div [:span {:style {:color "#666"}} "系统架构："] [:span arch]]
                           [:div [:span {:style {:color "#666"}} "Java版本："] [:span java-version]]
                           [:div [:span {:style {:color "#666"}} "Java虚拟机："] [:span java-vm]]
                           [:div [:span {:style {:color "#666"}} "处理器核心："] [:span (str processors " 核")]]]}]))

(defn- memory-section [server-data]
  (let [max-memory (:maxMemory server-data)
        total-memory (:totalMemory server-data)
        free-memory (:freeMemory server-data)
        used-memory (- total-memory free-memory)]
    [info-card {:title "内存信息"
                :children [:div
                           [progress-item {:label "已用内存" :used used-memory :total total-memory :color "#ff4d4f"}]
                           [progress-item {:label "剩余内存" :used free-memory :total total-memory :color "#52c41a"}]
                           [:div {:style {:marginTop 16 :color "#666"}}
                            (str "最大内存：" max-memory " MB | 总内存：" total-memory " MB | 已用：" used-memory " MB")]]}]))

(defn- jvm-section [server-data]
  (let [max-memory (:maxMemory server-data)
        total-memory (:totalMemory server-data)
        free-memory (:freeMemory server-data)
        used-memory (- total-memory free-memory)]
    [info-card {:title "JVM信息"
                :children [:div
                           [progress-item {:label "JVM内存" :used used-memory :total max-memory :color "#1677ff"}]
                           [:div {:style {:marginTop 16 :color "#666"}}
                            (str "JVM最大内存：" max-memory " MB | 已分配：" total-memory " MB | 剩余：" free-memory " MB")]]}]))

(defn server-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:server/fetch])
     (fn []))
   [])
  (let [server-data @(rf/subscribe [:server/data])
        loading? @(rf/subscribe [:server/loading?])]
    [:div
     (if loading?
       [:div {:style {:textAlign "center" :padding 48}}
        [antd/button {:loading true} "加载中..."]]
       (when server-data
         [:div
          [server-info-section server-data]
          [memory-section server-data]
          [jvm-section server-data]]))]))
