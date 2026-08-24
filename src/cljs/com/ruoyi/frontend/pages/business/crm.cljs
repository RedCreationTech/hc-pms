(ns com.ruoyi.frontend.pages.business.crm
  "CRM 客户管理。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- columns []
  #js [#js {:title "客户名" :dataIndex "name" :key "name"}
       #js {:title "公司" :dataIndex "company" :key "company" :width 160}
       #js {:title "电话" :dataIndex "phone" :key "phone" :width 140}
       #js {:title "等级" :dataIndex "level" :key "level" :width 80
            :render (fn [v] (r/as-element (if (= v "1") [antd/tag {:color "gold"} "A"] [antd/tag {:color "blue"} "B"])))}
       #js {:title "操作" :key "action" :width 140
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/space
                        [antd/button {:type "link" :size "small"
                                      :on-click #(rf/dispatch [:crm/edit (js->clj record :keywordize-keys true)])}
                         "编辑"]
                        [antd/button {:type "link" :danger true :size "small"
                                      :on-click #(rf/dispatch [:crm/delete (.-customer_id ^js record)])}
                         "删除"]]))}])

(defn- modal []
  (let [visible? @(rf/subscribe [:crm/modal-visible?])
        form-data @(rf/subscribe [:crm/form-data])
        [form] (antd/form-use-form)]
    (hooks/use-effect (fn [] (when visible? (.setFieldsValue form (clj->js form-data))) js/undefined) [visible? form-data])
    [antd/modal {:title "客户" :open visible? :onOk #(.submit form) :onCancel #(rf/dispatch [:crm/close])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [v] (rf/dispatch [:crm/submit (js->clj v :keywordize-keys true)]))}
      [antd/form-item {:label "客户名" :name "name" :rules [{:required true}]} [antd/input {:placeholder "客户名"}]]
      [antd/form-item {:label "公司" :name "company"} [antd/input {:placeholder "公司"}]]
      [antd/form-item {:label "电话" :name "phone"} [antd/input {:placeholder "电话"}]]
      [antd/form-item {:label "等级" :name "level"} [antd/select {:style {:width "100%"}} [antd/select-option {:value "1"} "A"] [antd/select-option {:value "2"} "B"]]]
      [antd/form-item {:label "备注" :name "remark"} [antd/text-area {:placeholder "备注" :rows 3}]]]]))

(defn crm-customer-page []
  (let [items @(rf/subscribe [:crm/items]) total @(rf/subscribe [:crm/total]) loading? @(rf/subscribe [:crm/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:crm/open]) :label "新增客户"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:crm/fetch {}])}]]}]
     [antd/table {:rowKey "customer_id" :columns (columns) :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true :showTotal (fn [t] (str "共 " t " 条"))}}]
     [modal]]))
