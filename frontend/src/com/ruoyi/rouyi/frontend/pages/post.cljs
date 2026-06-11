(ns com.ruoyi.rouyi.frontend.pages.post
  "岗位管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- post-columns []
  #js [#js {:title "岗位ID" :dataIndex "post_id" :key "post_id" :width 80}
       #js {:title "岗位编码" :dataIndex "post_code" :key "post_code"}
       #js {:title "岗位名称" :dataIndex "post_name" :key "post_name"}
       #js {:title "排序" :dataIndex "post_sort" :key "post_sort" :width 100}
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

(defn post-page []
  (let [items @(rf/subscribe [:posts/items])
        total @(rf/subscribe [:posts/total])
        loading? @(rf/subscribe [:posts/loading?])]
    [:div
     [:h3 "岗位管理"]
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary"} "新增岗位"]]
     [antd/table {:rowKey "post_id"
                  :columns (post-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total
                               :pageSize 10
                               :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]]))
