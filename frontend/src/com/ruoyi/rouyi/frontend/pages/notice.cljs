(ns com.ruoyi.rouyi.frontend.pages.notice
  "通知公告管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- notice-columns []
  #js [#js {:title "ID" :dataIndex "notice_id" :key "notice_id" :width 80}
       #js {:title "公告标题" :dataIndex "notice_name" :key "notice_name"}
       #js {:title "类型" :dataIndex "notice_type" :key "notice_type"
            :width 100
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "1") "blue" "green")}
                         (if (= v "1") "通知" "公告")]))}
       #js {:title "状态" :dataIndex "status" :key "status"
            :width 100
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "关闭")]))}
       #js {:title "创建者" :dataIndex "create_by" :key "create_by" :width 120}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 150
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"
                                       :on-click #(rf/dispatch [:notices/edit (js->clj record :keywordize-keys true)])}
                          "编辑"]
                         [antd/button {:type "link" :danger true :size "small"
                                       :on-click #(rf/dispatch [:notices/delete (.-notice_id ^js record)])}
                          "删除"]]))}])

(defn- notice-modal []
  (let [visible? @(rf/subscribe [:notices/modal-visible?])
        editing? @(rf/subscribe [:notices/editing?])
        form-data @(rf/subscribe [:notices/form-data])]
    [antd/modal {:title (if editing? "编辑通知公告" "新增通知公告")
                 :open visible?
                 :onOk #(rf/dispatch [:notices/submit])
                 :onCancel #(rf/dispatch [:notices/close-modal])}
     [antd/form {:layout "vertical"}
      [antd/form-item {:label "标题" :required true}
       [antd/input {:value (:notice_name form-data)
                    :on-change #(rf/dispatch [:notices/update-form :notice_name (.. % -target -value)])}]]
      [antd/form-item {:label "类型" :required true}
       [antd/select {:value (:notice_type form-data "1")
                     :on-change #(rf/dispatch [:notices/update-form :notice_type %])}
        [antd/select-option {:value "1"} "通知"]
        [antd/select-option {:value "2"} "公告"]]]
      [antd/form-item {:label "状态"}
       [antd/radio-group {:value (:status form-data "0")
                          :on-change #(rf/dispatch [:notices/update-form :status (.. % -target -value)])}
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "关闭"]]]
      [antd/form-item {:label "备注"}
       [antd/text-area {:value (:remark form-data "")
                        :rows 4
                        :on-change #(rf/dispatch [:notices/update-form :remark (.. % -target -value)])}]]]]))

(defn notice-page []
  (let [items @(rf/subscribe [:notices/items])
        total @(rf/subscribe [:notices/total])
        loading? @(rf/subscribe [:notices/loading?])]
    [:div
     [:h3 "通知公告"]
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:type "primary"
                    :on-click #(rf/dispatch [:notices/open-modal])}
       "新增通知"]]
     [antd/table {:rowKey "notice_id"
                  :columns (notice-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total
                               :pageSize 10
                               :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [notice-modal]]))
