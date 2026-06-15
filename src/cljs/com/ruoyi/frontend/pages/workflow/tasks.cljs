(ns com.ruoyi.frontend.pages.workflow.tasks
  "待办任务列表页面。"
  (:require [reagent.core :as r]
            [reagent.hooks :as hooks]
            [re-frame.core :as rf]
            ["@ant-design/icons" :refer [CheckOutlined]]
            [com.ruoyi.frontend.antd :as antd]))

(defn- columns [on-complete]
  #js [#js {:title "任务名称" :dataIndex "name" :key "name" :width 200}
       #js {:title "处理人" :dataIndex "assignee" :key "assignee" :width 120}
       #js {:title "创建时间" :dataIndex "createTime" :key "createTime" :width 180}
       #js {:title "流程实例" :dataIndex "processInstanceId" :key "processInstanceId"
            :width 150 :ellipsis true}
       #js {:title "操作" :key "action" :width 120
            :render (fn [_ _ row]
                      (let [r (js->clj row :keywordize-keys true)]
                        (r/as-element
                         [antd/space {:size 4}
                          [antd/button {:type "primary" :size "small"
                                        :icon (r/as-element [:> CheckOutlined])
                                        :onClick #(on-complete (:id r))}
           "完成"]])))}])

(defn tasks-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:workflow/fetch-tasks])
     js/undefined)
   [])
  (let [data @(rf/subscribe [:workflow/tasks])
        loading? @(rf/subscribe [:workflow/loading?])]
    [:div
     [antd/card {:title "待办任务" :size "small"}
      [antd/table {:dataSource (clj->js data)
                   :columns (columns
                             #(rf/dispatch [:workflow/complete-task %]))
                   :rowKey "id"
                   :loading loading?
                   :pagination false
                   :size "small"}]]]))
