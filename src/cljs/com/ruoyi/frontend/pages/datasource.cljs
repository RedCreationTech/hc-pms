(ns com.ruoyi.frontend.pages.datasource
  "数据源监控页面 — HikariCP 连接池状态。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined]]
   ["antd" :refer [Table Spin Descriptions]]
   [com.ruoyi.frontend.antd :as antd]))

(defn- info-card [{:keys [title value unit color]}]
  [:div {:style {:textAlign "center" :padding 20
                 :background "var(--ant-color-bg-container, #fff)"
                 :borderRadius 8}}
   [:div {:style {:fontSize 32 :fontWeight 600 :color (or color "#1677ff")}}
    (str (or value 0) (or unit ""))]
   [:div {:style {:fontSize 13 :color "#999" :marginTop 6}} title]])

(defn datasource-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:server/fetch-datasource])
     js/undefined)
   [])
  (let [data @(rf/subscribe [:server/datasource])
        loading? false]
    [:div
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
      [:h3 {:style {:margin 0}} "数据监控"]
      [antd/button {:icon (r/as-element [:> ReloadOutlined])
                    :onClick #(rf/dispatch [:server/fetch-datasource])}
       "刷新"]]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48}}
        [:> Spin {:size "large"}]]
       (if (nil? data)
         [:div {:style {:textAlign "center" :padding 48 :color "#999"}} "暂无数据"]
         [:div
          ;; 连接统计
          [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr 1fr" :gap 16 :marginBottom 16}}
           [info-card {:title "活跃连接" :value (:active_connections data) :color "#1677ff"}]
           [info-card {:title "空闲连接" :value (:idle_connections data) :color "#52c41a"}]
           [info-card {:title "总连接数" :value (:total_connections data) :color "#faad14"}]
           [info-card {:title "等待线程" :value (:threads_awaiting_connection data) :color "#ff4d4f"}]]
          ;; 基本信息
          [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
           [:h4 {:style {:margin "0 0 16px 0" :fontSize 16 :fontWeight 600}} "连接池信息"]
           [:> Descriptions {:bordered true :size "small" :column 2}
            [:> (.-Item Descriptions) {:label "数据库名称"} (or (:db_name data) "-")]
            [:> (.-Item Descriptions) {:label "数据库版本"} (or (:db_version data) "-")]
            [:> (.-Item Descriptions) {:label "最大连接数"} (or (:max_connections data) "-")]
            [:> (.-Item Descriptions) {:label "最小空闲"} (or (:min_idle data) "-")]
            [:> (.-Item Descriptions) {:label "连接超时(ms)"} (or (:connection_timeout data) "-")]
            [:> (.-Item Descriptions) {:label "空闲超时(ms)"} (or (:idle_timeout data) "-")]
            [:> (.-Item Descriptions) {:label "最大生命周期(ms)"} (or (:max_lifetime data) "-")]]]]))]))
