(ns com.ruoyi.frontend.pages.online
  "在线用户页面。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [SearchOutlined ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]))

(defn- online-columns []
  #js [#js {:title "用户ID" :dataIndex "user-id" :key "user-id"}
       #js {:title "用户名" :dataIndex "user-name" :key "user-name"}
       #js {:title "登录IP" :dataIndex "login-ip" :key "login-ip"}
       #js {:title "登录时间" :dataIndex "login-time" :key "login-time"
            :render (fn [v]
                      (r/as-element
                       [:span (when v (.toLocaleString (js/Date. v)))]))}
       #js {:title "最后访问" :dataIndex "last-access" :key "last-access"
            :render (fn [v]
                      (r/as-element
                       [:span (when v (.toLocaleString (js/Date. v)))]))}
       #js {:title "操作" :key "action"
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/button {:type "link" :danger true :size "small"
                                     :onClick #(rf/dispatch [:online-users/force-logout (.-token-id record)])}
                        "强退"]))}])

(defn online-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:online-users/fetch {}])
     (let [interval (js/setInterval #(rf/dispatch [:online-users/fetch {}]) 30000)]
       (fn [] (js/clearInterval interval))))
   [])
  (let [items @(rf/subscribe [:online-users/items])
        [uname set-uname!] (hooks/use-state "")
        total @(rf/subscribe [:online-users/total])
        loading? @(rf/subscribe [:online-users/loading?])]
    [:div
     ;; 搜索栏
     [:div {:style {:display "flex" :gap 8 :marginBottom 12 :flexWrap "wrap" :alignItems "center"}}
      [antd/input {:placeholder "用户名" :style {:width 200}
                   :value uname :onChange #(set-uname! (-> % .-target .-value))}]
      [antd/button {:type "primary" :icon (r/as-element [:> SearchOutlined])
                    :on-click #(rf/dispatch [:online-users/search {:user_name uname}])}
       "搜索"]
      [antd/button {:icon (r/as-element [:> ReloadOutlined])
                    :on-click #(do (set-uname! "") (rf/dispatch [:online-users/fetch {}]))}
       "重置"]]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "token-id"
                  :loading loading?
                  :columns (online-columns)
                  :dataSource (clj->js items)
                  :pagination {:pageSize 10 :total total}}]]))
