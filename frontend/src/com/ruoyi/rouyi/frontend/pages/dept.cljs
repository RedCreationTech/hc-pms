(ns com.ruoyi.rouyi.frontend.pages.dept
  "部门管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- dept-columns []
  #js [#js {:title "部门名称" :dataIndex "dept_name" :key "dept_name" :width 200}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 200
            :render (fn [_ _]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"} "新增"]
                         [antd/button {:type "link" :size "small"} "编辑"]
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]))}])

(defn dept-page []
  (let [items @(rf/subscribe [:depts/items])
        loading? @(rf/subscribe [:depts/loading?])]
    [:div
     [:h3 "部门管理"]
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary"} "新增部门"]
      [antd/button {:type "default"} "展开/折叠"]]
     [antd/table {:rowKey "dept_id"
                  :columns (dept-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination false
                  :defaultExpandAllRows true}]]))
