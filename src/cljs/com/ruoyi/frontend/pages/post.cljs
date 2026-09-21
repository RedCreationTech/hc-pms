(ns com.ruoyi.frontend.pages.post
  "岗位管理页面 -- 搜索,CRUD."
  (:require
    ["@ant-design/icons" :refer [DownloadOutlined SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


;; ─── 搜索表单 ──────────────────────────────────────────────────────

(defn- search-form
  []
  (let [query-params @(rf/subscribe [:posts/query-params])]
    [page-search/page-search {:visible? true}
     [page-search/search-row
      [page-search/search-item
       "岗位编码"
       [antd/input {:placeholder "请输入岗位编码"
                    :style page-search/input-style
                    :value (:post_code query-params)
                    :on-change #(rf/dispatch [:posts/update-query :post_code (.. % -target -value)])}]]
      [page-search/search-item
       "岗位名称"
       [antd/input {:placeholder "请输入岗位名称"
                    :style page-search/input-style
                    :value (:post_name query-params)
                    :on-change #(rf/dispatch [:posts/update-query :post_name (.. % -target -value)])}]]
      [page-search/search-item
       "状态"
       [antd/select {:placeholder "状态" :style page-search/select-style :value (:status query-params)
                     :allowClear true :on-change #(rf/dispatch [:posts/update-query :status %])}
        [antd/select-option {:value "0"} "正常"]
        [antd/select-option {:value "1"} "停用"]]]
      [page-search/search-actions
       [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                    :on-click #(rf/dispatch [:posts/fetch (:posts/query-params @re-frame.db/app-db)])}]
       [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                   :on-click #(do (rf/dispatch [:posts/reset-query])
                                                  (rf/dispatch [:posts/fetch {}]))}]]]]))


;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar
  []
  [page-toolbar/page-toolbar
   {:left [page-toolbar/toolbar-left
           [page-toolbar/toolbar-button {:kind :add
                                         :icon (r/as-element [:> PlusOutlined])
                                         :on-click #(rf/dispatch [:posts/open-modal])
                                         :label "新增"}]
           [page-toolbar/toolbar-button {:kind :edit
                                         :icon (r/as-element [:> EditOutlined])
                                         :disabled? true
                                         :label "修改"}]
           [page-toolbar/toolbar-button {:kind :delete
                                         :icon (r/as-element [:> DeleteOutlined])
                                         :disabled? true
                                         :label "删除"}]
           [page-toolbar/toolbar-button {:kind :export
                                         :icon (r/as-element [:> DownloadOutlined])
                                         :on-click #(api/export-posts {})
                                         :label "导出"}]]
    :right [page-toolbar/toolbar-right
            [page-toolbar/round-tool-button {:title "搜索"
                                             :icon (r/as-element [:> SearchOutlined])
                                             :on-click #(rf/dispatch [:posts/fetch (:posts/query-params @re-frame.db/app-db)])}]
            [page-toolbar/round-tool-button {:title "刷新"
                                             :icon (r/as-element [:> ReloadOutlined])
                                             :on-click #(rf/dispatch [:posts/fetch {}])}]]}])


;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- post-columns
  []
  #js [#js {:title "岗位编号" :dataIndex "post_id" :key "post_id" :width 80}
       #js {:title "岗位编码" :dataIndex "post_code" :key "post_code" :width 120}
       #js {:title "岗位名称" :dataIndex "post_name" :key "post_name"}
       #js {:title "岗位排序" :dataIndex "post_sort" :key "post_sort" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:className "ruoyi-status-tag"}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 180}
       #js {:title "操作" :key "action" :width 180
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small" :icon (r/as-element [:> EditOutlined])
                                       :on-click #(rf/dispatch [:posts/edit (js->clj record :keywordize-keys true)])} "编辑"]
                         [antd/popconfirm {:title "确认删除该岗位？"
                                           :onConfirm #(rf/dispatch [:posts/delete (.-post_id record)])}
                          [antd/button {:type "link" :danger true :size "small" :icon (r/as-element [:> DeleteOutlined])} "删除"]]]))}])


;; ─── 编辑弹窗 ──────────────────────────────────────────────────────

(defn- edit-modal
  []
  (let [visible? @(rf/subscribe [:posts/modal-visible?])
        editing @(rf/subscribe [:posts/editing])
        form-data @(rf/subscribe [:posts/form-data])
        [form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when visible?
          (.setFieldsValue form (clj->js (merge {:post_sort 0 :status "0"} form-data))))
        js/undefined)
      [visible? form-data])
    [antd/modal {:title (if editing "修改岗位" "新增岗位")
                 :open visible? :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:posts/close-modal]) :destroyOnHidden true}
     [antd/form {:form form
                 :labelCol {:span 6} :wrapperCol {:span 16}
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:posts/submit (js->clj values :keywordize-keys true)]))
                 :initialValues (clj->js (merge {:post_sort 0 :status "0"} form-data))}
      [antd/form-item {:label "岗位编码" :name "post_code" :required true}
       [antd/input {:placeholder "请输入岗位编码"}]]
      [antd/form-item {:label "岗位名称" :name "post_name" :required true}
       [antd/input {:placeholder "请输入岗位名称"}]]
      [antd/form-item {:label "显示排序" :name "post_sort"
                       :get-value-from-event #(let [v (.. % -target -value)]
                                                (if (seq v) (js/parseInt v 10) 0))}
       [antd/input {:type "number"}]]
      [antd/form-item {:label "状态" :name "status"}
       [antd/radio-group
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      [antd/form-item {:label "备注" :name "remark"}
       [antd/text-area {:placeholder "请输入备注" :rows 3}]]]]))


;; ─── 主页面 ──────────────────────────────────────────────────────

(defn post-page
  []
  (hooks/use-effect (fn [] (rf/dispatch [:posts/fetch {}]) js/undefined) [])
  (let [items @(rf/subscribe [:posts/items])
        total @(rf/subscribe [:posts/total])
        loading? @(rf/subscribe [:posts/loading?])
        [selected-ids set-selected-ids!] (hooks/use-state [])]
    [:div
     [search-form]
     [toolbar]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "post_id" :loading loading? :columns (post-columns)
                  :rowSelection #js {:selectedRowKeys (clj->js selected-ids)
                                     :onChange (fn [keys _]
                                                 (set-selected-ids! (js->clj keys)))}
                  :dataSource (clj->js items)
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [edit-modal]]))
