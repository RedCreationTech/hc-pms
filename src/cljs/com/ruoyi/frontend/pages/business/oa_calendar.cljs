(ns com.ruoyi.frontend.pages.business.oa-calendar
  "OA 日程管理。"
  (:require
    ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn- columns
  []
  #js [#js {:title "标题" :dataIndex "title" :key "title"}
       #js {:title "开始" :dataIndex "start_time" :key "start_time" :width 180}
       #js {:title "结束" :dataIndex "end_time" :key "end_time" :width 180}
       #js {:title "全天" :dataIndex "all_day" :key "all_day" :width 80
            :render (fn [v] (r/as-element (if (= v "1") "是" "否")))}
       #js {:title "操作" :key "action" :width 90
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/button {:type "link" :danger true :size "small"
                                      :on-click #(rf/dispatch [:oa-calendar/delete (.-calendar_id ^js record)])}
                         "删除"]))}])


(defn- modal
  []
  (let [visible? @(rf/subscribe [:oa-calendar/modal-visible?]) [form] (antd/form-use-form)]
    [antd/modal {:title "新增日程" :open visible? :onOk #(.submit form) :onCancel #(rf/dispatch [:oa-calendar/close])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [v] (rf/dispatch [:oa-calendar/submit (js->clj v :keywordize-keys true)]))}
      [antd/form-item {:label "标题" :name "title" :rules [{:required true}]} [antd/input {:placeholder "日程标题"}]]
      [antd/form-item {:label "开始时间" :name "start_time"} [antd/input {:placeholder "YYYY-MM-DD HH:mm:ss"}]]
      [antd/form-item {:label "结束时间" :name "end_time"} [antd/input {:placeholder "YYYY-MM-DD HH:mm:ss"}]]
      [antd/form-item {:label "全天" :name "all_day"} [antd/switch {:defaultChecked false}]]]]))


(defn oa-calendar-page
  []
  (let [items @(rf/subscribe [:oa-calendar/items]) total @(rf/subscribe [:oa-calendar/total]) loading? @(rf/subscribe [:oa-calendar/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:oa-calendar/open]) :label "新增日程"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:oa-calendar/fetch {}])}]]}]
     [antd/table {:rowKey "calendar_id" :columns (columns) :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true :showTotal (fn [t] (str "共 " t " 条"))}}]
     [modal]]))
