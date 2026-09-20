(ns com.ruoyi.frontend.pages.dict
  "字典管理页面 — 完整 CRUD。两级联动：类型列表 → 数据列表。"
  (:require
    ["@ant-design/icons" :refer [PlusOutlined DownloadOutlined EditOutlined DeleteOutlined SearchOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


;; ─── 字典类型 ─────────────────────────────────────────────────────────────────

(defn- type-form-modal
  [{:keys [visible? editing on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when visible?
          (.resetFields form)
          (.setFieldsValue form (clj->js (merge {:status "0"} editing))))
        js/undefined)
      [visible? editing])
    [antd/modal {:open visible?
                 :title (if editing "编辑字典类型" "新增字典类型")
                 :onOk #(.submit form)
                 :onCancel on-cancel
                 :okText "确定" :cancelText "取消"}
     [antd/form {:form form
                 :layout "vertical"
                 :preserve false
                 :onFinish (fn [values]
                             (on-ok (js->clj values :keywordize-keys true)))
                 :initialValues (clj->js (merge {:status "0"} editing))}
      [antd/form-item {:label "字典名称" :name "dict_name"
                       :rules [{:required true :message "请输入字典名称"}]}
       [antd/input {:placeholder "请输入字典名称"}]]
      [antd/form-item {:label "字典类型" :name "dict_type"
                       :rules [{:required true :message "请输入字典类型"}]}
       [antd/input {:placeholder "请输入字典类型"}]]
      [antd/form-item {:label "状态" :name "status"}
       [antd/radio-group
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      [antd/form-item {:label "备注" :name "remark"}
       [antd/text-area {:placeholder "请输入备注" :rows 3}]]]]))


(defn- type-columns
  [on-select on-edit on-delete]
  #js [#js {:title "字典编号" :dataIndex "dict_id" :key "dict_id" :width 80}
       #js {:title "字典名称" :dataIndex "dict_name" :key "dict_name"}
       #js {:title "字典类型" :dataIndex "dict_type" :key "dict_type"}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 180 :fixed "right"
            :render (fn [record _]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                          [antd/space
                           [antd/button {:type "link" :size "small"
                                         :onClick #(on-select row)}
                            "字典数据"]
                           [antd/button {:type "link" :size "small"
                                         :onClick #(on-edit row)}
                            "编辑"]
                           [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                             :on-confirm #(on-delete (:dict_id row))}
                            [antd/button {:type "link" :danger true :size "small"} "删除"]]])))}])


(defn- type-section
  []
  (let [types @(rf/subscribe [:dicts/types])
        loading? @(rf/subscribe [:dicts/loading?])
        [dict-name set-dict-name!] (hooks/use-state "")
        [dict-type set-dict-type!] (hooks/use-state "")
        [modal-visible? set-modal-visible!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)]
    (hooks/use-effect (fn [] (rf/dispatch [:dicts/fetch-types {}]) js/undefined) [])
    [:div
     [page-search/page-search {:visible? true}
      [page-search/search-row
       [page-search/search-item
        "字典名称"
        [antd/input {:placeholder "请输入字典名称"
                     :style page-search/input-style
                     :value dict-name
                     :onChange #(set-dict-name! (-> % .-target .-value))}]]
       [page-search/search-item
        "字典类型"
        [antd/input {:placeholder "请输入字典类型"
                     :style page-search/input-style
                     :value dict-type
                     :onChange #(set-dict-type! (-> % .-target .-value))}]]
       [page-search/search-actions
        [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                     :on-click #(rf/dispatch [:dicts/search {:dict_name dict-name :dict_type dict-type}])}]
        [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                    :on-click #(do (set-dict-name! "")
                                                   (set-dict-type! "")
                                                   (rf/dispatch [:dicts/fetch-types {}]))}]]]]
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add
                                            :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(do (set-editing! nil) (set-modal-visible! true))
                                            :label "新增"}]
              [page-toolbar/toolbar-button {:kind :export
                                            :icon (r/as-element [:> DownloadOutlined])
                                            :on-click #(api/export-dicts {})
                                            :label "导出"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "搜索"
                                                :icon (r/as-element [:> SearchOutlined])
                                                :on-click #(rf/dispatch [:dicts/search {:dict_name dict-name :dict_type dict-type}])}]
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:dicts/fetch-types {}])}]]}]
     [antd/table {:rowKey "dict_id" :loading loading? :scroll #js {:x 700}
                  :rowSelection #js {}
                  :columns (type-columns
                             #(rf/dispatch [:dicts/select-type %])
                             #(do (set-editing! %) (set-modal-visible! true))
                             #(rf/dispatch [:dicts/delete-type %]))
                  :dataSource (clj->js types)
                  :pagination {:pageSize 10 :show-total (fn [t] (str "共 " t " 条"))}}]
     [type-form-modal
      {:visible? modal-visible?
       :editing editing
       :on-ok (fn [form]
                (let [form (merge editing form)]
                  (if (:dict_id form)
                    (rf/dispatch [:dicts/update-type (:dict_id form) form])
                    (rf/dispatch [:dicts/create-type form]))
                  (set-modal-visible! false)
                  (set-editing! nil)))
       :on-cancel #(do (set-modal-visible! false) (set-editing! nil))}]]))


;; ─── 字典数据 ─────────────────────────────────────────────────────────────────

(defn- data-form-modal
  [{:keys [visible? editing dict-type on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when visible?
          (.resetFields form)
          (.setFieldsValue form (clj->js (merge {:status "0" :dict_sort 0} editing))))
        js/undefined)
      [visible? editing])
    [antd/modal {:open visible?
                 :title (if editing "编辑字典数据" "新增字典数据")
                 :onOk #(.submit form)
                 :onCancel on-cancel
                 :okText "确定" :cancelText "取消"}
     [antd/form {:form form
                 :layout "vertical"
                 :preserve false
                 :onFinish (fn [values]
                             (let [values (js->clj values :keywordize-keys true)]
                               (on-ok (update values :dict_sort #(if (number? %) % (js/parseInt % 10))))))
                 :initialValues (clj->js (merge {:status "0" :dict_sort 0} editing))}
      [antd/form-item {:label "字典标签" :name "dict_label"
                       :rules [{:required true :message "请输入字典标签"}]}
       [antd/input {:placeholder "请输入字典标签"}]]
      [antd/form-item {:label "字典键值" :name "dict_value"
                       :rules [{:required true :message "请输入字典键值"}]}
       [antd/input {:placeholder "请输入字典键值"}]]
      [antd/form-item {:label "排序" :name "dict_sort"}
       [antd/input-number {:placeholder "请输入排序" :min 0}]]
      [antd/form-item {:label "状态" :name "status"}
       [antd/radio-group
        [antd/radio {:value "0"} "正常"]
        [antd/radio {:value "1"} "停用"]]]
      [antd/form-item {:label "备注" :name "remark"}
       [antd/text-area {:placeholder "请输入备注" :rows 2}]]]]))


(defn- data-columns
  [on-edit on-delete]
  #js [#js {:title "字典编码" :dataIndex "dict_code" :key "dict_code" :width 80}
       #js {:title "字典标签" :dataIndex "dict_label" :key "dict_label"}
       #js {:title "字典键值" :dataIndex "dict_value" :key "dict_value"}
       #js {:title "排序" :dataIndex "dict_sort" :key "dict_sort" :width 60}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "操作" :key "action" :width 120 :fixed "right"
            :render (fn [record _]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                          [antd/space
                           [antd/button {:type "link" :size "small"
                                         :onClick #(on-edit row)}
                            "编辑"]
                           [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                             :on-confirm #(on-delete (:dict_code row))}
                            [antd/button {:type "link" :danger true :size "small"} "删除"]]])))}])


(defn- data-section
  []
  (let [dict-type @(rf/subscribe [:dicts/selected-type])
        data @(rf/subscribe [:dicts/data])
        loading? @(rf/subscribe [:dicts/loading?])
        [modal-visible? set-modal-visible!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn []
        (when dict-type
          (rf/dispatch [:dicts/fetch-data {:dict_type (:dict_type dict-type)}]))
        js/undefined)
      #js [(:dict_type dict-type)])
    (if dict-type
      [:div {:style {:marginTop 24}}
       [:div {:style {:display "flex" :justifyContent "space-between"
                      :alignItems "center" :marginBottom 16}}
        [:h4 {:style {:margin 0}} (str "字典数据 — " (:dict_name dict-type) " (" (:dict_type dict-type) ")")]
        [antd/button {:type "link" :onClick #(rf/dispatch [:dicts/clear-selected-type])}
         "返回类型列表"]]
       [page-toolbar/page-toolbar
        {:left [page-toolbar/toolbar-left
                [page-toolbar/toolbar-button {:kind :add
                                              :icon (r/as-element [:> PlusOutlined])
                                              :on-click #(do (set-editing! nil) (set-modal-visible! true))
                                              :label "新增"}]]
         :right [page-toolbar/toolbar-right
                 [page-toolbar/round-tool-button {:title "刷新"
                                                  :icon (r/as-element [:> ReloadOutlined])
                                                  :on-click #(rf/dispatch [:dicts/fetch-data {:dict_type (:dict_type dict-type)}])}]]}]
       [antd/table {:rowKey "dict_code" :loading loading? :scroll #js {:x 600}
                    :rowSelection #js {}
                    :columns (data-columns
                               #(do (set-editing! %) (set-modal-visible! true))
                               #(rf/dispatch [:dicts/delete-data %]))
                    :dataSource (clj->js data)
                    :pagination {:pageSize 10 :show-total (fn [t] (str "共 " t " 条"))}}]
       [data-form-modal
        {:visible? modal-visible?
         :editing editing
         :dict-type dict-type
         :on-ok (fn [form]
                  (let [form (merge editing form)
                        params (assoc form :dict_type (:dict_type dict-type))]
                    (if (:dict_code params)
                      (rf/dispatch [:dicts/update-data (:dict_code params) params])
                      (rf/dispatch [:dicts/create-data params]))
                    (set-modal-visible! false)
                    (set-editing! nil)))
         :on-cancel #(do (set-modal-visible! false) (set-editing! nil))}]]
      [:div])))


;; ─── 主页面 ───────────────────────────────────────────────────────────────────

(defn dict-page
  []
  [:div
   [type-section]
   [data-section]])
