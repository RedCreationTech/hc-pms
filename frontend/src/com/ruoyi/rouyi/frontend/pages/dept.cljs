(ns com.ruoyi.rouyi.frontend.pages.dept
  "部门管理页面 — 树形表格、CRUD。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]
   [com.ruoyi.rouyi.frontend.components.dept-tree-select :refer [dept-tree-select]]))

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

(defn- toolbar []
  [:div {:style {:display "flex" :gap 8 :marginBottom 16}}
   [antd/button {:type "primary"
                 :icon (r/as-element [:> PlusOutlined])
                 :on-click #(rf/dispatch [:depts/open-modal])}
    "新增部门"]
   [antd/button {:icon (r/as-element [:> ReloadOutlined])
                 :on-click #(rf/dispatch [:depts/fetch {}])}
    "刷新"]])

;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- dept-columns []
  #js [#js {:title "部门名称" :dataIndex "dept_name" :key "dept_name" :width 200}
       #js {:title "排序" :dataIndex "order_num" :key "order_num" :width 80}
       #js {:title "负责人" :dataIndex "leader" :key "leader" :width 120}
       #js {:title "电话" :dataIndex "phone" :key "phone" :width 150}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:color (if (= v "0") "green" "red")}
                        (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 220
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/space
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> PlusOutlined])
                                      :on-click #(do (rf/dispatch [:depts/update-form :parent_id (.-dept_id record)])
                                                     (rf/dispatch [:depts/open-modal]))}
                         "新增"]
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
        editing? @(rf/subscribe [:depts/editing?])
        form-data @(rf/subscribe [:depts/form-data])]
    [antd/modal {:title (if editing? "修改部门" "新增部门")
                 :open visible?
                 :onOk #(rf/dispatch [:depts/submit])
                 :onCancel #(rf/dispatch [:depts/close-modal])
                 :destroyOnHidden true}
     [antd/form {:labelCol {:span 6} :wrapperCol {:span 16}}
      [antd/form-item {:label "上级部门"}
       [dept-tree-select {:value (:parent_id form-data)
                          :placeholder "选择上级部门（空为顶级）"
                          :allow-clear? true
                          :on-change #(rf/dispatch [:depts/update-form :parent_id %])}]]
      [antd/form-item {:label "部门名称" :required true}
       [antd/input {:value (:dept_name form-data "")
                    :on-change #(rf/dispatch [:depts/update-form :dept_name (.. % -target -value)])}]]
      [antd/form-item {:label "显示排序"}
       [antd/input {:type "number" :value (:order_num form-data 0)
                    :on-change #(rf/dispatch [:depts/update-form :order_num (js/parseInt (.. % -target -value) 10)])}]]
      [antd/form-item {:label "负责人"}
       [antd/input {:value (:leader form-data "")
                    :on-change #(rf/dispatch [:depts/update-form :leader (.. % -target -value)])}]]
      [antd/form-item {:label "联系电话"}
       [antd/input {:value (:phone form-data "")
                    :on-change #(rf/dispatch [:depts/update-form :phone (.. % -target -value)])}]]
      [antd/form-item {:label "邮箱"}
       [antd/input {:value (:email form-data "")
                    :on-change #(rf/dispatch [:depts/update-form :email (.. % -target -value)])}]]
      [antd/form-item {:label "状态"}
       [antd/radio-group {:value (:status form-data "0")
                          :on-change #(rf/dispatch [:depts/update-form :status (.. % -target -value)])}
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
    (fn []
      [:div
       [toolbar]
       [antd/table {:rowKey "dept_id"
                    :loading loading?
                    :columns (dept-columns)
                    :dataSource (clj->js tree-data)
                    :pagination false
                    :defaultExpandAllRows true
                    :childrenColumnName "children"}]
       [edit-modal]])))
