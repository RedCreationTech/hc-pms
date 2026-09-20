(ns com.ruoyi.frontend.pages.business.reimburse
  "报销申请页面 —— 业务 + BPM 审批流。"
  (:require
    ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn- status-tag
  [v]
  (let [[label color] (case v
                        "1" ["审批中" "processing"]
                        "2" ["已通过" "success"]
                        "3" ["已驳回" "error"]
                        ["未知" "default"])]
    [antd/tag {:color color} label]))


(defn- columns
  []
  #js [#js {:title "ID" :dataIndex "reimburse_id" :key "reimburse_id" :width 70}
       #js {:title "报销人" :dataIndex "user_name" :key "user_name" :width 110}
       #js {:title "金额(元)" :dataIndex "amount" :key "amount" :width 110}
       #js {:title "事由" :dataIndex "reason" :key "reason"}
       #js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id" :width 90
            :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "申请时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 90
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/button {:type "link" :danger true :size "small"
                                      :on-click #(rf/dispatch [:reimburse/delete (.-reimburse_id ^js record)])}
                         "删除"]))}])


(defn- modal
  []
  (let [visible? @(rf/subscribe [:reimburse/modal-visible?])
        submitting? @(rf/subscribe [:reimburse/submitting?])
        [form] (antd/form-use-form)]
    [antd/modal {:title "发起报销申请" :open visible? :confirmLoading submitting?
                 :onOk #(.submit form) :onCancel #(rf/dispatch [:reimburse/close])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [v] (rf/dispatch [:reimburse/submit (js->clj v :keywordize-keys true)]))}
      [antd/form-item {:label "报销金额(元)" :name "amount" :rules [{:required true}]}
       [antd/input-number {:placeholder "请输入报销金额" :min 0 :style {:width "100%"}}]]
      [antd/form-item {:label "报销事由" :name "reason" :rules [{:required true}]}
       [antd/text-area {:placeholder "请输入报销事由" :rows 4}]]]]))


(defn reimburse-page
  []
  (let [items @(rf/subscribe [:reimburse/items]) total @(rf/subscribe [:reimburse/total]) loading? @(rf/subscribe [:reimburse/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:reimburse/open]) :label "发起报销"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:reimburse/fetch {}])}]]}]
     [antd/table {:rowKey "reimburse_id" :columns (columns) :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true :showTotal (fn [t] (str "共 " t " 条"))}}]
     [modal]]))
