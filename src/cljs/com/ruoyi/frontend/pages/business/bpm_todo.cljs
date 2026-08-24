(ns com.ruoyi.frontend.pages.business.bpm-todo
  "我的待办 —— 审批面板。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined CheckOutlined CloseOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- task-columns []
  #js [#js {:title "任务" :dataIndex "name" :key "name"}
       #js {:title "流程定义" :dataIndex "process-definition-id" :key "process-definition-id"
            :width 220 :ellipsis true}
       #js {:title "流程实例" :dataIndex "process-instance-id" :key "process-instance-id"
            :width 90 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "创建时间" :dataIndex "create-time" :key "create-time" :width 180}
       #js {:title "操作" :key "action" :width 160
            :render (fn [_ ^js record]
                      (let [task (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/space
                          [antd/button {:type "primary" :size "small"
                                        :icon (r/as-element [:> CheckOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-approve task])}
                           "通过"]
                          [antd/button {:danger true :size "small"
                                        :icon (r/as-element [:> CloseOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-reject task])}
                           "驳回"]])))}])

(defn- approve-modal []
  (let [visible? @(rf/subscribe [:bpm/todo-modal-visible?])
        current @(rf/subscribe [:bpm/todo-current])
        action @(rf/subscribe [:bpm/todo-action])
        submitting? @(rf/subscribe [:bpm/todo-submitting?])
        [form] (antd/form-use-form)]
    [antd/modal {:title (str (if (= action "approve") "审批通过" "审批驳回") " · " (:name current))
                 :open visible? :confirmLoading submitting?
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:bpm/todo-close])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:bpm/todo-submit (:comment values)]))}
      [antd/form-item {:label "审批意见" :name "comment"}
       [antd/text-area {:placeholder "请输入审批意见(可选)" :rows 4}]]]]))

(defn bpm-todo-page []
  (let [items @(rf/subscribe [:bpm-todo/items])
        total @(rf/subscribe [:bpm-todo/total])
        loading? @(rf/subscribe [:bpm-todo/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [:div {:style {:fontSize 15 :fontWeight 600}} "我的待办"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/todo-fetch])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "task-id"
                  :columns (task-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [approve-modal]]))
