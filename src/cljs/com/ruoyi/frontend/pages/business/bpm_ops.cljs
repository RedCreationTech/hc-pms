(ns com.ruoyi.frontend.pages.business.bpm-ops
  "BPM 实例管理 / 任务管理 / 实例运维."
  (:require
    ["@ant-design/icons" :refer [ReloadOutlined EyeOutlined PauseCircleOutlined PlayCircleOutlined StopOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.bpmn-viewer :as bpmn-viewer]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn- status-tag
  [v]
  (let [[label color] (case v
                        "1" ["审批中" "processing"]
                        "2" ["已结束" "success"]
                        "3" ["已驳回" "error"]
                        "CANCELED" ["已取消" "warning"]
                        ["未知" "default"])]
    [antd/tag {:color color} label]))


(defn- ops-columns
  []
  #js [#js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id" :width 100
            :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "模型" :dataIndex "model_name" :key "model_name" :width 130}
       #js {:title "业务键" :dataIndex "business_key" :key "business_key" :width 130}
       #js {:title "发起人" :dataIndex "starter_id" :key "starter_id" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 90
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "发起时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "运维操作" :key "ops" :width 200
            :render (fn [_ ^js record]
                      (let [pid (.-process_instance_id ^js record)]
                        (r/as-element
                          [antd/space
                           [antd/button {:type "link" :size "small" :icon (r/as-element [:> EyeOutlined])
                                         :on-click #(rf/dispatch [:bpm/diagram-open pid])} "流程图"]
                           [antd/button {:type "link" :size "small" :icon (r/as-element [:> PauseCircleOutlined])
                                         :on-click #(rf/dispatch [:bpm/instance-op pid "suspend"])} "挂起"]
                           [antd/button {:type "link" :size "small" :icon (r/as-element [:> PlayCircleOutlined])
                                         :on-click #(rf/dispatch [:bpm/instance-op pid "activate"])} "激活"]
                           [antd/button {:danger true :type "link" :size "small" :icon (r/as-element [:> StopOutlined])
                                         :on-click #(rf/dispatch [:bpm/instance-op pid "terminate"])} "终止"]
                           [antd/popconfirm {:title "确认取消该流程实例?"
                                             :on-confirm #(rf/dispatch [:bpm/instance-cancel pid "管理员取消"])}
                            [antd/button {:type "link" :size "small" :danger true
                                          :icon (r/as-element [:> StopOutlined])}
                             "取消"]]])))}])


(defn- task-columns
  []
  #js [#js {:title "任务" :dataIndex "name" :key "name"}
       #js {:title "办理人" :dataIndex "assignee" :key "assignee" :width 100}
       #js {:title "流程实例" :dataIndex "process-instance-id" :key "process-instance-id" :width 100}
       #js {:title "流程定义" :dataIndex "process-definition-id" :key "process-definition-id" :width 180}
       #js {:title "创建时间" :dataIndex "create-time" :key "create-time" :width 180}])


(defn- diagram-modal
  []
  (let [visible? @(rf/subscribe [:bpm-diagram/visible?])
        data @(rf/subscribe [:bpm-diagram/data])
        loading? @(rf/subscribe [:bpm-diagram/loading?])]
    [antd/modal {:title (str "流程进度 · " (:model-name data))
                 :open visible? :width 900 :footer nil
                 :onCancel #(rf/dispatch [:bpm/diagram-close])}
     (if loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [bpmn-viewer/bpmn-viewer
        {:xml (:bpmn-xml data)
         :active-ids (vec (:active-activity-ids data))
         :completed-ids (vec (:completed-activity-ids data))
         :on-error (fn [e] (antd/error! e))}])]))


(defn- page-shell
  [title items total loading? toolbar-left]
  [:div
   [page-toolbar/page-toolbar
    {:left [page-toolbar/toolbar-left [:div {:style {:fontSize 15 :fontWeight 600}} title]]
     :right [page-toolbar/toolbar-right
             [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                              :on-click (:refresh toolbar-left)}]]}]
   [:div
    [antd/table {:rowKey "instance_id" :columns (ops-columns)
                 :dataSource (clj->js items) :loading loading?
                 :pagination {:total total :pageSize 10 :showSizeChanger true
                              :showTotal (fn [t] (str "共 " t " 条"))}}]]])


(defn instance-manager-page
  []
  (let [items @(rf/subscribe [:bpm-instance/items])
        total @(rf/subscribe [:bpm-instance/total])
        loading? @(rf/subscribe [:bpm-instance/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left [:div {:style {:fontSize 15 :fontWeight 600}} "流程实例管理"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/instance-fetch {}])}]]}]
     [antd/table {:rowKey "instance_id" :columns (ops-columns)
                  :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]
     [diagram-modal]]))


(defn instance-ops-page
  []
  (let [items @(rf/subscribe [:bpm-instance/items])
        total @(rf/subscribe [:bpm-instance/total])
        loading? @(rf/subscribe [:bpm-instance/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left [:div {:style {:fontSize 15 :fontWeight 600}} "流程实例运维"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/instance-fetch {}])}]]}]
     [antd/table {:rowKey "instance_id" :columns (ops-columns)
                  :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]
     [diagram-modal]]))


(defn task-manager-page
  []
  (let [items @(rf/subscribe [:bpm-all-tasks/items])
        total @(rf/subscribe [:bpm-all-tasks/total])
        loading? @(rf/subscribe [:bpm-all-tasks/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left [:div {:style {:fontSize 15 :fontWeight 600}} "流程任务管理"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/all-tasks-fetch])}]]}]
     [antd/table {:rowKey "task-id" :columns (task-columns)
                  :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]]))
