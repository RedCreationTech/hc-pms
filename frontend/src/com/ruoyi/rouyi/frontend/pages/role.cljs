(ns com.ruoyi.rouyi.frontend.pages.role
  "角色管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- role-columns []
  #js [#js {:title "角色ID" :dataIndex "role_id" :key "role_id" :width 80}
       #js {:title "角色名称" :dataIndex "role_name" :key "role_name"}
       #js {:title "权限字符" :dataIndex "role_key" :key "role_key"}
       #js {:title "显示顺序" :dataIndex "role_sort" :key "role_sort" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 150
            :render (fn [_ _]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"} "编辑"]
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]))}])

(defn role-page []
  (let [items @(rf/subscribe [:roles/items])
        total @(rf/subscribe [:roles/total])
        loading? @(rf/subscribe [:roles/loading?])]
    [:div
     [:h3 "角色管理"]
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary"} "新增角色"]]
     [antd/table {:rowKey "role_id"
                  :columns (role-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total
                               :pageSize 10
                               :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]]))
