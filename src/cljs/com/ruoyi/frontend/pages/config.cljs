(ns com.ruoyi.frontend.pages.config
  "参数配置管理页面 -- 完整 CRUD."
  (:require
    ["@ant-design/icons" :refer [PlusOutlined DownloadOutlined SearchOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn- search-bar
  []
  (let [[keyword set-keyword!] (hooks/use-state "")]
    [page-search/page-search {:visible? true}
     [page-search/search-row
      [page-search/search-item
       "参数名称"
       [antd/input {:placeholder "请输入参数名称"
                    :style page-search/input-style
                    :value keyword
                    :on-change #(set-keyword! (.. % -target -value))}]]
      [page-search/search-actions
       [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                    :on-click #(rf/dispatch [:configs/fetch {:config_name keyword}])}]
       [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                   :on-click #(do (set-keyword! "")
                                                  (rf/dispatch [:configs/fetch {}]))}]]]]))


(defn- config-columns
  [on-edit on-delete]
  #js [#js {:title "参数ID" :dataIndex "config_id" :key "config_id" :width 80}
       #js {:title "参数名称" :dataIndex "config_name" :key "config_name"}
       #js {:title "参数键名" :dataIndex "config_key" :key "config_key"}
       #js {:title "参数键值" :dataIndex "config_value" :key "config_value"}
       #js {:title "系统内置" :dataIndex "config_type" :key "config_type" :width 100
            :render (fn [v]
                      (r/as-element
                        [antd/tag {:color (if (= v "Y") "blue" "default")}
                         (if (= v "Y") "是" "否")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 160 :fixed "right"
            :render (fn [_ record]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                          [antd/space
                           [antd/button {:type "link" :size "small"
                                         :onClick #(on-edit row)}
                            "编辑"]
                           [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                             :on-confirm #(on-delete (:config_id row))}
                            [antd/button {:type "link" :danger true :size "small"} "删除"]]])))}])


(defn- config-modal
  [{:keys [visible? editing on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when visible?
          (.resetFields form)
          (.setFieldsValue form (clj->js (merge {:config_type "Y"} editing))))
        js/undefined)
      [visible? editing])
    [antd/modal {:open visible?
                 :title (if editing "编辑参数" "新增参数")
                 :onOk #(.submit form)
                 :onCancel on-cancel
                 :okText "确定" :cancelText "取消"}
     [antd/form {:form form
                 :layout "vertical"
                 :preserve false
                 :onFinish (fn [values]
                             (on-ok (js->clj values :keywordize-keys true)))
                 :initialValues (clj->js (merge {:config_type "Y"} editing))}
      [antd/form-item {:label "参数名称" :name "config_name"
                       :rules [{:required true :message "请输入参数名称"}]}
       [antd/input {:placeholder "请输入参数名称"}]]
      [antd/form-item {:label "参数键名" :name "config_key"
                       :rules [{:required true :message "请输入参数键名"}]}
       [antd/input {:placeholder "请输入参数键名"}]]
      [antd/form-item {:label "参数键值" :name "config_value"
                       :rules [{:required true :message "请输入参数键值"}]}
       [antd/input {:placeholder "请输入参数键值"}]]
      [antd/form-item {:label "系统内置" :name "config_type"}
       [antd/radio-group
        [antd/radio {:value "Y"} "是"]
        [antd/radio {:value "N"} "否"]]]
      [antd/form-item {:label "备注" :name "remark"}
       [antd/text-area {:placeholder "请输入备注" :rows 3}]]]]))


(defn config-page
  []
  (let [items @(rf/subscribe [:configs/items])
        total @(rf/subscribe [:configs/total])
        loading? @(rf/subscribe [:configs/loading?])
        [modal-visible? set-modal-visible!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn [] (rf/dispatch [:configs/fetch {}]) js/undefined)
      [])
    [:div
     [search-bar]
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:perms "system:config:add" :kind :add
                                            :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(do (set-editing! nil) (set-modal-visible! true))
                                            :label "新增"}]
              [page-toolbar/toolbar-button {:perms "system:config:export" :kind :export
                                            :icon (r/as-element [:> DownloadOutlined])
                                            :on-click #(api/export-configs {})
                                            :label "导出"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "搜索"
                                                :icon (r/as-element [:> SearchOutlined])
                                                :on-click #(rf/dispatch [:configs/fetch {}])}]
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:configs/refetch])}]]}]
     [antd/table {:rowKey "config_id" :loading loading? :scroll #js {:x 800}
                  :rowSelection #js {}
                  :columns (config-columns
                             #(do (set-editing! %) (set-modal-visible! true))
                             #(rf/dispatch [:configs/delete %]))
                  :dataSource (clj->js items)
                  :pagination {:pageSize 10 :total total
                               :show-total (fn [t] (str "共 " t " 条"))}}]
     [config-modal
      {:visible? modal-visible?
       :editing editing
       :on-ok (fn [form]
                (let [form (merge editing form)]
                  (if (:config_id form)
                    (rf/dispatch [:configs/update (:config_id form) form])
                    (rf/dispatch [:configs/create form]))
                  (set-modal-visible! false)
                  (set-editing! nil)))
       :on-cancel #(do (set-modal-visible! false) (set-editing! nil))}]]))
