(ns com.ruoyi.rouyi.frontend.pages.login-log
  "登录日志页面。只读 Table + 清空。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- login-log-columns []
  #js [#js {:title "访问编号" :dataIndex "info_id" :key "info_id" :width 80}
       #js {:title "用户名称" :dataIndex "user_name" :key "user_name"}
       #js {:title "登录地址" :dataIndex "ipaddr" :key "ipaddr"}
       #js {:title "登录地点" :dataIndex "login_location" :key "login_location"}
       #js {:title "浏览器" :dataIndex "browser" :key "browser"}
       #js {:title "操作系统" :dataIndex "os" :key "os"}
       #js {:title "登录状态" :dataIndex "status" :key "status"
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:color (if (= v "0") "green" "red")}
                        (if (= v "0") "成功" "失败")]))}
       #js {:title "操作信息" :dataIndex "msg" :key "msg"}
       #js {:title "登录时间" :dataIndex "login_time" :key "login_time"}])

(defn login-log-page []
  (hooks/use-effect (fn []
                      (rf/dispatch [:login-logs/fetch {}])
                      js/undefined)
                    [])
  (let [items @(rf/subscribe [:login-logs/items])
        total @(rf/subscribe [:login-logs/total])
        loading? @(rf/subscribe [:login-logs/loading?])]
    [:div
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary" :danger true
                    :onClick #(rf/dispatch [:login-logs/clear])}
       "清空"]]
     [antd/table {:rowKey "info_id"
                  :loading loading?
                  :columns (login-log-columns)
                  :dataSource (clj->js items)
                  :pagination {:pageSize 10 :total total}}]]))
