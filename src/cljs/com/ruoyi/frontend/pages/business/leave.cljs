(ns com.ruoyi.frontend.pages.business.leave
  "请假申请页面 -- 业务 + BPM 审批流."
  (:require
    ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn- status-tag
  [v]
  (let [[label color] (case v
                        "1" ["审批中" "processing"]
                        "2" ["已通过" "success"]
                        "3" ["已驳回" "error"]
                        ["未知" "default"])]
    [antd/tag {:color color} label]))


(defn- leave-columns
  []
  #js [#js {:title "ID" :dataIndex "leave_id" :key "leave_id" :width 70}
       #js {:title "请假人" :dataIndex "user_name" :key "user_name" :width 110}
       #js {:title "天数" :dataIndex "days" :key "days" :width 80}
       #js {:title "原因" :dataIndex "reason" :key "reason"}
       #js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id" :width 90
            :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "申请时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 90
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/button {:type "link" :danger true :size "small"
                                      :on-click #(rf/dispatch [:leave/delete (.-leave_id ^js record)])}
                         "删除"]))}])


(defn- leave-modal
  []
  (let [visible? @(rf/subscribe [:leave/modal-visible?])
        submitting? @(rf/subscribe [:leave/submitting?])
        [form] (antd/form-use-form)]
    [antd/modal {:title "发起请假申请" :open visible?
                 :confirmLoading submitting?
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:leave/close-modal])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:leave/submit (js->clj values :keywordize-keys true)]))}
      [antd/form-item {:label "请假天数" :name "days"
                       :rules [{:required true :message "请输入请假天数"}]}
       [antd/input-number {:placeholder "请输入请假天数" :min 0.5 :style {:width "100%"}}]]
      [antd/form-item {:label "请假原因" :name "reason"
                       :rules [{:required true :message "请输入请假原因"}]}
       [antd/text-area {:placeholder "请输入请假原因" :rows 4}]]]]))


(defn leave-page
  []
  (let [items @(rf/subscribe [:leave/items])
        total @(rf/subscribe [:leave/total])
        loading? @(rf/subscribe [:leave/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add
                                            :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:leave/open-modal])
                                            :label "发起请假"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:leave/fetch {}])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "leave_id"
                  :columns (leave-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [leave-modal]]))
