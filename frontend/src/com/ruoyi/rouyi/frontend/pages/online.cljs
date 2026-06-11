(ns com.ruoyi.rouyi.frontend.pages.online
  "在线用户页面。"
  (:require
    [reagent.core :as r]
    [reagent.hooks :as hooks]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- online-columns []
  #js [#js {:title "用户ID" :dataIndex "user_id" :key "user_id"}
       #js {:title "用户名" :dataIndex "user_name" :key "user_name"}
       #js {:title "登录IP" :dataIndex "login_ip" :key "login_ip"}
       #js {:title "登录时间" :dataIndex "login_time" :key "login_time"
            :render (fn [v]
                      (r/as-element
                        [:span (when v (js/Date. v))]))}
       #js {:title "最后访问" :dataIndex "last_access" :key "last_access"
            :render (fn [v]
                      (r/as-element
                        [:span (when v (js/Date. v))]))}
       #js {:title "操作" :key "action"
            :render (fn [_ record]
                      (r/as-element
                        [antd/button {:type "link" :danger true :size "small"
                                      :onClick (fn [_]
                                                 (rf/dispatch [:online-users/force-logout (:token_id record)]))}
                         "强退"]))}])

(defn online-page []
  (let [poll-interval (r/atom nil)]
    (hooks/use-effect
      (fn []
        (rf/dispatch [:online-users/fetch {}])
        (reset! poll-interval
          (js/setInterval #(rf/dispatch [:online-users/fetch {}]) 30000))
        (fn []
          (when @poll-interval
            (js/clearInterval @poll-interval))))
      [])
    (let [items @(rf/subscribe [:online-users/items])
          total @(rf/subscribe [:online-users/total])
          loading? @(rf/subscribe [:online-users/loading?])]
      [:div
       [:h3 "在线用户"]
       [antd/table {:rowKey "token_id"
                    :loading loading?
                    :columns (online-columns)
                    :dataSource (clj->js items)
                    :pagination {:pageSize 10 :total total}}]])))
