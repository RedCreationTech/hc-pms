(ns com.ruoyi.frontend.pages.online
  "在线用户页面。"
  (:require
   [goog.object :as gobj]
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [SearchOutlined ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-search :as page-search]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- token-id-from-row
  "Antd render 第一个参数可能是文本/记录；兼容取 token-id。"
  [r1 r2]
  (or (when (object? r1) (gobj/get r1 "token-id"))
      (when (object? r2) (gobj/get r2 "token-id"))))

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
       #js {:title "操作" :key "action" :dataIndex "token-id"
            :render (fn [v ^js record]
                      (r/as-element
                       [antd/button {:type "link" :danger true :size "small"
                                     :onClick #(when-let [tid (token-id-from-row v record)]
                                                 (rf/dispatch [:online-users/force-logout tid]))}
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
     [page-search/page-search {:visible? true}
      [page-search/search-row
       [page-search/search-item
        "用户名称"
        [antd/input {:placeholder "请输入用户名称"
                     :style page-search/input-style
                     :value uname
                     :onChange #(set-uname! (-> % .-target .-value))}]]
       [page-search/search-actions
        [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                     :on-click #(rf/dispatch [:online-users/search {:user_name uname}])}]
        [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                    :on-click #(do (set-uname! "")
                                                   (rf/dispatch [:online-users/fetch {}]))}]]]]
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "搜索"
                                                :icon (r/as-element [:> SearchOutlined])
                                                :on-click #(rf/dispatch [:online-users/search {:user_name uname}])}]
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:online-users/fetch {}])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "token-id"
                  :loading loading?
                  :columns (online-columns)
                  :dataSource (clj->js items)
                  :pagination {:pageSize 10 :total total}}]]))
