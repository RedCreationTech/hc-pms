(ns com.ruoyi.rouyi.frontend.pages.user
  "用户管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- user-columns []
  #js [#js {:title "用户ID" :dataIndex "user_id" :key "user_id"}
       #js {:title "用户名" :dataIndex "user_name" :key "user_name"}
       #js {:title "昵称" :dataIndex "nick_name" :key "nick_name"}
       #js {:title "部门" :dataIndex "dept_name" :key "dept_name"}
       #js {:title "手机" :dataIndex "phonenumber" :key "phonenumber"}
       #js {:title "状态" :dataIndex "status" :key "status"
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time"}
       #js {:title "操作" :key "action"
            :render (fn [_ record]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"} "编辑"]
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]))}])

(defn user-page []
  (let [items @(rf/subscribe [:users/items])
        total @(rf/subscribe [:users/total])
        loading? @(rf/subscribe [:users/loading?])]
    [:div
     [:h3 "用户管理"]
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary"} "新增用户"]]
     [antd/table {:rowKey "user_id"
                  :columns (user-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination #js {:total total :pageSize 10 :showSizeChanger true}}]]))
