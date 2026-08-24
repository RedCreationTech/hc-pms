(ns com.ruoyi.frontend.pages.business.oa-meeting
  "OA 会议管理。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- columns []
  #js [#js {:title "主题" :dataIndex "subject" :key "subject"}
       #js {:title "地点" :dataIndex "location" :key "location" :width 130}
       #js {:title "开始" :dataIndex "start_time" :key "start_time" :width 180}
       #js {:title "参与人" :dataIndex "participants" :key "participants" :width 180 :ellipsis true}
       #js {:title "操作" :key "action" :width 90
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/button {:type "link" :danger true :size "small"
                                     :on-click #(rf/dispatch [:oa-meeting/delete (.-meeting_id ^js record)])}
                        "删除"]))}])

(defn- modal []
  (let [visible? @(rf/subscribe [:oa-meeting/modal-visible?]) [form] (antd/form-use-form)]
    [antd/modal {:title "新增会议" :open visible? :onOk #(.submit form) :onCancel #(rf/dispatch [:oa-meeting/close])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [v] (rf/dispatch [:oa-meeting/submit (js->clj v :keywordize-keys true)]))}
      [antd/form-item {:label "主题" :name "subject" :rules [{:required true}]} [antd/input {:placeholder "会议主题"}]]
      [antd/form-item {:label "地点" :name "location"} [antd/input {:placeholder "会议地点"}]]
      [antd/form-item {:label "开始时间" :name "start_time"} [antd/input {:placeholder "YYYY-MM-DD HH:mm:ss"}]]
      [antd/form-item {:label "参与人" :name "participants"} [antd/input {:placeholder "张三,李四"}]]
      [antd/form-item {:label "内容" :name "content"} [antd/text-area {:placeholder "会议内容" :rows 3}]]]]))

(defn oa-meeting-page []
  (let [items @(rf/subscribe [:oa-meeting/items]) total @(rf/subscribe [:oa-meeting/total]) loading? @(rf/subscribe [:oa-meeting/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:oa-meeting/open]) :label "新增会议"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:oa-meeting/fetch {}])}]]}]
     [antd/table {:rowKey "meeting_id" :columns (columns) :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true :showTotal (fn [t] (str "共 " t " 条"))}}]
     [modal]]))
