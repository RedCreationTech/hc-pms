(ns com.ruoyi.frontend.pages.business.hrm
  "HRM 员工管理。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

(defn- columns []
  #js [#js {:title "工号" :dataIndex "emp_no" :key "emp_no" :width 110}
       #js {:title "姓名" :dataIndex "name" :key "name"}
       #js {:title "部门" :dataIndex "dept_name" :key "dept_name" :width 120}
       #js {:title "性别" :dataIndex "gender" :key "gender" :width 80
            :render (fn [v] (r/as-element (if (= v "1") "男" "女")))}
       #js {:title "电话" :dataIndex "phone" :key "phone" :width 130}
       #js {:title "入职日期" :dataIndex "hire_date" :key "hire_date" :width 110}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v] (r/as-element (if (= v "1") [antd/tag {:color "green"} "在职"] [antd/tag {:color "red"} "离职"])))}
       #js {:title "操作" :key "action" :width 90
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/button {:type "link" :danger true :size "small"
                                     :on-click #(rf/dispatch [:hrm/delete (.-employee_id ^js record)])}
                        "删除"]))}])

(defn- modal []
  (let [visible? @(rf/subscribe [:hrm/modal-visible?])
        form-data @(rf/subscribe [:hrm/form-data])
        [form] (antd/form-use-form)]
    (hooks/use-effect (fn [] (when visible? (.setFieldsValue form (clj->js form-data))) js/undefined) [visible? form-data])
    [antd/modal {:title "新增员工" :open visible? :onOk #(.submit form) :onCancel #(rf/dispatch [:hrm/close])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [v] (rf/dispatch [:hrm/submit (js->clj v :keywordize-keys true)]))}
      [antd/form-item {:label "工号" :name "emp_no" :rules [{:required true}]} [antd/input {:placeholder "工号"}]]
      [antd/form-item {:label "姓名" :name "name" :rules [{:required true}]} [antd/input {:placeholder "姓名"}]]
      [antd/form-item {:label "部门ID" :name "dept_id"} [antd/input-number {:style {:width "100%"} :min 0}]]
      [antd/form-item {:label "性别" :name "gender"} [antd/select {:style {:width "100%"}} [antd/select-option {:value "1"} "男"] [antd/select-option {:value "0"} "女"]]]
      [antd/form-item {:label "电话" :name "phone"} [antd/input {:placeholder "电话"}]]
      [antd/form-item {:label "入职日期" :name "hire_date"} [antd/input {:placeholder "YYYY-MM-DD"}]]]]))

(defn hrm-employee-page []
  (let [items @(rf/subscribe [:hrm/items]) total @(rf/subscribe [:hrm/total]) loading? @(rf/subscribe [:hrm/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:hrm/open]) :label "新增员工"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:hrm/fetch {}])}]]}]
     [antd/table {:rowKey "employee_id" :columns (columns) :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true :showTotal (fn [t] (str "共 " t " 条"))}}]
     [modal]]))
