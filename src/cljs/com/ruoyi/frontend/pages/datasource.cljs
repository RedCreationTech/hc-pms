(ns com.ruoyi.frontend.pages.datasource
  "连接池监视页面，按 RuoYi 数据监控入口展示 HikariCP 连接池状态。"
  (:require
    ["@ant-design/icons" :refer [DatabaseOutlined ReloadOutlined]]
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
  "渲染 RuoYi 风格连接池监控卡片。"
  [{:keys [icon title]} & children]
  (into
    [:div {:style card-style}
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


(defn- metric-rows
  "把连接池状态转换成表格行。"
  [data]
  [{:name "数据库名称" :value (or (:db_name data) "-") :remark "当前 JDBC 数据源"}
   {:name "数据库版本" :value (or (:db_version data) "-") :remark "驱动或数据库版本"}
   {:name "活跃连接数" :value (or (:active_connections data) 0) :remark "正在使用的连接"}
   {:name "空闲连接数" :value (or (:idle_connections data) 0) :remark "连接池中可用连接"}
   {:name "总连接数" :value (or (:total_connections data) 0) :remark "当前池内连接总数"}
   {:name "等待线程数" :value (or (:threads_awaiting_connection data) 0) :remark "等待获取连接的线程"}
   {:name "最大连接数" :value (or (:max_connections data) "-") :remark "maximumPoolSize"}
   {:name "最小空闲数" :value (or (:min_idle data) "-") :remark "minimumIdle"}
   {:name "连接超时" :value (str (or (:connection_timeout data) "-") " ms") :remark "connectionTimeout"}
   {:name "空闲超时" :value (str (or (:idle_timeout data) "-") " ms") :remark "idleTimeout"}
   {:name "最大生命周期" :value (str (or (:max_lifetime data) "-") " ms") :remark "maxLifetime"}])


(defn- pool-table
  "渲染连接池状态表格。"
  [data]
  [:> Table {:size "small"
             :pagination false
             :rowKey "name"
             :dataSource (clj->js (metric-rows data))
             :columns (clj->js
                        [{:title "监控项" :dataIndex "name" :key "name" :width 180}
                         {:title "当前值" :dataIndex "value" :key "value"
                          :render (fn [v record]
                                    (let [danger? (and (= "等待线程数" (.-name record))
                                                       (pos? (or v 0)))]
                                      (r/as-element
                                        [:span {:style {:color (if danger? "#f56c6c" "#606266")}}
                                         v])))}
                         {:title "说明" :dataIndex "remark" :key "remark"}])}])


(defn datasource-page
  "连接池监视入口组件,加载并展示数据源连接池运行状态."
  []
  (hooks/use-effect
    (fn []
      (rf/dispatch [:server/fetch-datasource])
      js/undefined)
    [])
  (let [data @(rf/subscribe [:server/datasource])
        loading? @(rf/subscribe [:server/datasource-loading?])]
    [:div {:style {:padding 16 :background "#f5f7fa" :minHeight "calc(100vh - 112px)"}}
     [:div {:style {:display "flex" :justifyContent "flex-end" :marginBottom 12}}
      [antd/button {:icon (r/as-element [:> ReloadOutlined])
                    :onClick #(rf/dispatch [:server/fetch-datasource])}
       "刷新"]]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48 :background "#fff"}}
        [:> Spin {:size "large"}]]
       [monitor-card {:title "连接池信息" :icon (r/as-element [:> DatabaseOutlined])}
        (if data
          [pool-table data]
          [:div {:style {:padding 48 :textAlign "center" :color "#909399"}} "暂无数据"])])]))
