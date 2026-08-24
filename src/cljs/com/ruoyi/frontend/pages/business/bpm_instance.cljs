(ns com.ruoyi.frontend.pages.business.bpm-instance
  "我的流程。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- status-tag [v]
  (let [[label color] (case v
                        "1" ["审批中" "processing"]
                        "2" ["已结束" "success"]
                        "3" ["已驳回" "error"]
                        ["未知" "default"])]
    [antd/tag {:color color} label]))

(defn- instance-columns []
  #js [#js {:title "模型" :dataIndex "model_name" :key "model_name" :width 140}
       #js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id"
            :width 90 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "业务键" :dataIndex "business_key" :key "business_key" :width 130}
       #js {:title "发起人" :dataIndex "starter_id" :key "starter_id" :width 100}
       #js {:title "当前任务" :dataIndex "current_task" :key "current_task"}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "发起时间" :dataIndex "create_time" :key "create_time" :width 170}])

(defn bpm-instance-page []
  (let [items @(rf/subscribe [:bpm-instance/items])
        total @(rf/subscribe [:bpm-instance/total])
        loading? @(rf/subscribe [:bpm-instance/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [:div {:style {:fontSize 15 :fontWeight 600}} "我的流程"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/instance-fetch {}])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "instance_id"
                  :columns (instance-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]]))
