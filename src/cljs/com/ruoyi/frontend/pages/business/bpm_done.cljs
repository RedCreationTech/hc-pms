(ns com.ruoyi.frontend.pages.business.bpm-done
  "我的已办。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined RollbackOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- done-columns []
  #js [#js {:title "任务" :dataIndex "name" :key "name"}
       #js {:title "办理人" :dataIndex "assignee" :key "assignee" :width 120}
       #js {:title "流程实例" :dataIndex "process-instance-id" :key "process-instance-id"
            :width 90 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "开始时间" :dataIndex "start-time" :key "start-time" :width 180}
       #js {:title "结束时间" :dataIndex "end-time" :key "end-time" :width 180}
       #js {:title "操作" :key "action" :width 100
            :render (fn [_ ^js record]
                      (let [task (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/popconfirm {:title "确认撤回该已办任务?"
                                           :on-confirm #(rf/dispatch [:bpm/done-withdraw (:task-id task)])}
                          [antd/button {:size "small" :icon (r/as-element [:> RollbackOutlined])}
                           "撤回"]])))}])

(defn bpm-done-page []
  (let [items @(rf/subscribe [:bpm-done/items])
        total @(rf/subscribe [:bpm-done/total])
        loading? @(rf/subscribe [:bpm-done/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [:div {:style {:fontSize 15 :fontWeight 600}} "我的已办"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/done-fetch])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "task-id"
                  :columns (done-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]]))
