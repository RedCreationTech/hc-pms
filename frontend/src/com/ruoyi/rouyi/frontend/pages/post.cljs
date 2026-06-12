(ns com.ruoyi.rouyi.frontend.pages.post
  "岗位管理页面 — 搜索、CRUD。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [SearchOutlined ReloadOutlined PlusOutlined EditOutlined DeleteOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

;; ─── 搜索表单 ──────────────────────────────────────────────────────

(defn- search-form []
  (let [query-params @(rf/subscribe [:posts/query-params])]
    [:div {:style {:background "var(--ant-color-bg-container, #fff)" :padding 16 :marginBottom 12 :borderRadius 8 :border "1px solid var(--ant-color-border-secondary, #e8e8e8)"}}
     [:div {:style {:display "flex" :flexWrap "wrap" :gap 12}}
      [:div {:style {:display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "岗位编码"]
       [antd/input {:placeholder "请输入岗位编码"
                    :style {:width 200}
                    :value (:post_code query-params)
                    :on-change #(rf/dispatch [:posts/update-query :post_code (.. % -target -value)])}]]
      [:div {:style {:display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "岗位名称"]
       [antd/input {:placeholder "请输入岗位名称"
                    :style {:width 200}
                    :value (:post_name query-params)
                    :on-change #(rf/dispatch [:posts/update-query :post_name (.. % -target -value)])}]]
      [:div {:style {:display "flex" :alignItems "center" :gap 8}}
       [:span {:style {:whiteSpace "nowrap" :fontSize 13}} "状态"]
       [antd/select {:placeholder "状态" :style {:width 200} :value (:status query-params)
                     :allowClear true :on-change #(rf/dispatch [:posts/update-query :status %])}
        [antd/select-option {:value "0"} "正常"]
        [antd/select-option {:value "1"} "停用"]]]
      [:div {:style {:display "flex" :gap 8 :alignItems "flex-end"}}
       [antd/button {:type "primary" :icon (r/as-element [:> SearchOutlined])
                     :on-click #(rf/dispatch [:posts/fetch (:posts/query-params @re-frame.db/app-db)])}
        "搜索"]
       [antd/button {:icon (r/as-element [:> ReloadOutlined])
                     :on-click #(do (rf/dispatch [:posts/reset-query]) (rf/dispatch [:posts/fetch {}]))}
        "重置"]]]]))

;; ─── 工具栏 ────────────────────────────────────────────────────────

(defn- toolbar []
  [:div {:style {:display "flex" :gap 8 :marginBottom 16}}
   [antd/button {:type "primary" :icon (r/as-element [:> PlusOutlined])
                 :on-click #(rf/dispatch [:posts/open-modal])} "新增"]
   [antd/button {:icon (r/as-element [:> ReloadOutlined])
                 :on-click #(rf/dispatch [:posts/fetch {}])} "刷新"]])

;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- post-columns []
  #js [#js {:title "岗位编号" :dataIndex "post_id" :key "post_id" :width 80}
       #js {:title "岗位编码" :dataIndex "post_code" :key "post_code" :width 120}
       #js {:title "岗位名称" :dataIndex "post_name" :key "post_name"}
       #js {:title "排序" :dataIndex "post_sort" :key "post_sort" :width 80}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _] (r/as-element [antd/tag {:color (if (= v "0") "green" "red")} (if (= v "0") "正常" "停用")]))}
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

(defn- edit-modal []
  (let [visible? @(rf/subscribe [:posts/modal-visible?])
        editing? @(rf/subscribe [:posts/editing?])
        form-data @(rf/subscribe [:posts/form-data])]
    [antd/modal {:title (if editing? "修改岗位" "新增岗位")
                 :open visible? :onOk #(rf/dispatch [:posts/submit])
                 :onCancel #(rf/dispatch [:posts/close-modal]) :destroyOnHidden true}
     [antd/form {:labelCol {:span 6} :wrapperCol {:span 16}}
      [antd/form-item {:label "岗位编码" :required true}
       [antd/input {:value (:post_code form-data "") :on-change #(rf/dispatch [:posts/update-form :post_code (.. % -target -value)])}]]
      [antd/form-item {:label "岗位名称" :required true}
       [antd/input {:value (:post_name form-data "") :on-change #(rf/dispatch [:posts/update-form :post_name (.. % -target -value)])}]]
      [antd/form-item {:label "显示排序"}
       [antd/input {:type "number" :value (:post_sort form-data 0)
                    :on-change #(rf/dispatch [:posts/update-form :post_sort (js/parseInt (.. % -target -value) 10)])}]]
      [antd/form-item {:label "状态"}
       [antd/radio-group {:value (:status form-data "0") :on-change #(rf/dispatch [:posts/update-form :status (.. % -target -value)])}
        [antd/radio {:value "0"} "正常"] [antd/radio {:value "1"} "停用"]]]
      [antd/form-item {:label "备注"}
       [antd/text-area {:value (:remark form-data "") :rows 3
                        :on-change #(rf/dispatch [:posts/update-form :remark (.. % -target -value)])}]]]]))

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn post-page []
  (hooks/use-effect (fn [] (rf/dispatch [:posts/fetch {}]) js/undefined) [])
  (let [items @(rf/subscribe [:posts/items]) total @(rf/subscribe [:posts/total]) loading? @(rf/subscribe [:posts/loading?])]
    [:div
     [search-form]
     [toolbar]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "post_id" :loading loading? :columns (post-columns)
                  :dataSource (clj->js items)
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [edit-modal]]))
