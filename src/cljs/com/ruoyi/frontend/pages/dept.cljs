(ns com.ruoyi.frontend.pages.dept
  "部门管理页面 — 树形表格、CRUD。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined DownloadOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-search :as page-search]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.dept-tree-select :refer [dept-tree-select]]))

;; ─── 辅助函数 ──────────────────────────────────────────────────────

(defn- build-dept-tree
  "将平铺部门列表转换为树形结构。"
  [items parent-id]
  (->> items
       (filter #(= parent-id (:parent_id %)))
       (mapv (fn [d]
               (let [children (build-dept-tree items (:dept_id d))]
                 (if (seq children)
                   (assoc d :children children)
                   d))))))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- search-bar []
  (let [[dept-name set-dept-name!] (hooks/use-state "")
        [status set-status!] (hooks/use-state nil)]
    [page-search/page-search {:visible? true}
     [page-search/search-row
      [page-search/search-item
       "部门名称"
       [antd/input {:placeholder "请输入部门名称"
                    :style page-search/input-style
                    :value dept-name
                    :onChange #(set-dept-name! (-> % .-target .-value))}]]
      [page-search/search-item
       "状态"
       [antd/select {:placeholder "部门状态"
                     :style page-search/select-style
                     :allowClear true
                     :value status
                     :onChange #(set-status! %)}
        [antd/select-option {:value "0"} "正常"]
        [antd/select-option {:value "1"} "停用"]]]
      [page-search/search-actions
       [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                    :on-click #(rf/dispatch [:depts/search {:dept_name dept-name :status status}])}]
       [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                   :on-click #(do (set-dept-name! "")
                                                  (set-status! nil)
                                                  (rf/dispatch [:depts/fetch {}]))}]]]]))

(defn- toolbar []
  [page-toolbar/page-toolbar
   {:left [page-toolbar/toolbar-left
           [page-toolbar/toolbar-button {:kind :add
                                         :icon (r/as-element [:> PlusOutlined])
                                         :on-click #(rf/dispatch [:depts/open-modal])
                                         :label "新增"}]
           [page-toolbar/toolbar-button {:kind :export
                                         :icon (r/as-element [:> DownloadOutlined])
                                         :on-click #(api/export-depts {})
                                         :label "导出"}]]
    :right [page-toolbar/toolbar-right
            [page-toolbar/round-tool-button {:title "搜索"
                                             :icon (r/as-element [:> SearchOutlined])
                                             :on-click #(rf/dispatch [:depts/search {}])}]
            [page-toolbar/round-tool-button {:title "刷新"
                                             :icon (r/as-element [:> ReloadOutlined])
                                             :on-click #(rf/dispatch [:depts/fetch {}])}]]}])

;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- dept-columns []
  #js [#js {:title "部门名称" :dataIndex "dept_name" :key "dept_name" :width 200}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 80}
       #js {:title "负责人" :dataIndex "leader" :key "leader" :width 120}
       #js {:title "电话" :dataIndex "phone" :key "phone" :width 150}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v ^js record]
                      (r/as-element
                       [antd/switch {:checked (= v "0")
                                     :checkedChildren "正常"
                                     :unCheckedChildren "停用"
                                     :on-change (fn [checked?]
                                                  (rf/dispatch [:depts/change-status
                                                                (.-dept_id record)
                                                                (if checked? "0" "1")]))}]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 220
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/space
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> PlusOutlined])
                                      :on-click #(rf/dispatch [:depts/open-modal {:parent_id (.-dept_id record)}])}
                         "新增"]
                        [antd/button {:icon (r/as-element [:> DownloadOutlined])
                                      :on-click #(api/export-depts {})}
                         "导出"]
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> EditOutlined])
                                      :on-click #(rf/dispatch [:depts/edit (js->clj record :keywordize-keys true)])}
                         "编辑"]
                        [antd/popconfirm {:title "确认删除该部门？"
                                          :onConfirm #(rf/dispatch [:depts/delete (.-dept_id record)])}
                         [antd/button {:type "link" :danger true :size "small"
                                       :icon (r/as-element [:> DeleteOutlined])}
                          "删除"]]]))}])

;; ─── 编辑弹窗 ──────────────────────────────────────────────────────

(defn- edit-modal []
  (let [visible? @(rf/subscribe [:depts/modal-visible?])
        editing @(rf/subscribe [:depts/editing])
        form-data @(rf/subscribe [:depts/form-data])
        [form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when visible?
         (.setFieldsValue form (clj->js (merge {:order_num 0 :status "0"} form-data))))
       js/undefined)
     [visible? form-data])
    [antd/modal {:title (if editing "修改部门" "新增部门")
                 :open visible?
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:depts/close-modal])
                 :destroyOnHidden true}
     [antd/form {:form form
                 :labelCol {:span 6}
                 :wrapperCol {:span 16}
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:depts/submit (js->clj values :keywordize-keys true)]))
                 :initialValues (clj->js (merge {:order_num 0 :status "0"} form-data))}
      [antd/form-item {:label "上级部门" :name "parent_id"}
       [dept-tree-select {:placeholder "选择上级部门（空为顶级）"
                          :allow-clear? true}]]
      [antd/form-item {:label "部门名称" :name "dept_name"
                       :rules [{:required true :message "请输入部门名称"}]}
       [antd/input {:placeholder "请输入部门名称"}]]
      [antd/form-item {:label "显示排序" :name "order_num"}
       [antd/input {:type "number" :placeholder "请输入显示排序"}]]
      [antd/form-item {:label "负责人" :name "leader"}
       [antd/input {:placeholder "请输入负责人"}]]
      [antd/form-item {:label "联系电话" :name "phone"}
       [antd/input {:placeholder "请输入联系电话"}]]
      [antd/form-item {:label "邮箱" :name "email"}
       [antd/input {:placeholder "请输入邮箱"}]]
      [antd/form-item {:label "状态" :name "status"}
       [antd/radio-group
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]]]))

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn dept-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:depts/fetch {}])
     js/undefined)
   [])
  (let [items @(rf/subscribe [:depts/items])
        loading? @(rf/subscribe [:depts/loading?])
        tree-data (build-dept-tree items 0)]
    [:div
     [search-bar]
     [toolbar]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "dept_id"
                  :loading loading?
                  :columns (dept-columns)
                  :dataSource (clj->js tree-data)
                  :pagination false
                  :defaultExpandAllRows true
                  :childrenColumnName "children"}]
     [edit-modal]]))
