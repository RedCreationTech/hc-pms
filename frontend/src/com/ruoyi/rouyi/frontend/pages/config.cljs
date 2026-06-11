(ns com.ruoyi.rouyi.frontend.pages.config
  "参数管理页面。"
  (:require
    [reagent.core :as r]
    [reagent.hooks :as hooks]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defonce modal-state (r/atom {:open? false :record nil}))
(defonce form-ref (r/atom nil))

(defn- open-create-modal []
  (reset! modal-state {:open? true :record nil}))

(defn- open-edit-modal [record]
  (reset! modal-state {:open? true :record record}))

(defn- close-modal []
  (reset! modal-state {:open? false :record nil}))

(defn- config-columns []
  #js [#js {:title "参数ID" :dataIndex "config_id" :key "config_id" :width 80}
       #js {:title "参数名称" :dataIndex "config_name" :key "config_name"}
       #js {:title "参数键名" :dataIndex "config_key" :key "config_key"}
       #js {:title "参数键值" :dataIndex "config_value" :key "config_value"}
       #js {:title "系统内置" :dataIndex "config_type" :key "config_type"
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "Y") "blue" "default")}
                         (if (= v "Y") "是" "否")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time"}
       #js {:title "操作" :key "action" :width 150
            :render (fn [_ record]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"
                                       :onClick #(open-edit-modal record)}
                          [antd/edit-icon] "编辑"]
                         (when (not= (.-config_type record) "Y")
                           [antd/popconfirm {:title "确认删除?"
                                             :onConfirm #(rf/dispatch [:configs/delete (.-config_id record)])}
                            [antd/button {:type "link" :danger true :size "small"}
                             [antd/delete-icon] "删除"]])]))}])

(defn- form-modal []
  (let [record (:record @modal-state)
        editing? (boolean record)]
    (fn []
      [antd/modal {:title (if editing? "编辑参数" "新增参数")
                   :open (:open? @modal-state)
                   :onOk (fn []
                           (when-let [form-instance @form-ref]
                             (-> form-instance (.validateFields)
                                 (.then (fn [values]
                                          (let [v (js->clj values :keywordize-keys true)]
                                            (if editing?
                                              (rf/dispatch [:configs/update (.-config_id record) v])
                                              (rf/dispatch [:configs/create v]))
                                            (close-modal))))
                                 (.catch (fn [_])))))
                   :onCancel close-modal
                   :destroyOnClose true}
       [antd/form {:ref #(reset! form-ref %)
                   :labelCol {:span 6}
                   :wrapperCol {:span 16}
                   :initialValues (when editing?
                                    #js {:config_name (.-config_name record)
                                         :config_key (.-config_key record)
                                         :config_value (.-config_value record)
                                         :config_type (.-config_type record)
                                         :remark (.-remark record)})}
        [antd/form-item {:label "参数名称" :name "config_name"
                         :rules #js [#js {:required true :message "请输入参数名称"}]}
         [antd/input]]
        [antd/form-item {:label "参数键名" :name "config_key"
                         :rules #js [#js {:required true :message "请输入参数键名"}]}
         [antd/input]]
        [antd/form-item {:label "参数键值" :name "config_value"
                         :rules #js [#js {:required true :message "请输入参数键值"}]}
         [antd/input]]
        [antd/form-item {:label "系统内置" :name "config_type"}
         [antd/select {:style {:width "100%"}
                       :options #js [#js {:label "是" :value "Y"}
                                     #js {:label "否" :value "N"}]}]]
        [antd/form-item {:label "备注" :name "remark"}
         [antd/input]]]])))

(defn config-page []
  (hooks/use-effect (fn []
                      (rf/dispatch [:configs/fetch {}])
                      js/undefined)
                    [])
  (let [items @(rf/subscribe [:configs/items])
        total @(rf/subscribe [:configs/total])
        loading? @(rf/subscribe [:configs/loading?])]
    (fn []
      [:div
       [:h3 "参数管理"]
       [antd/space {:style {:marginBottom 16}}
        [antd/button {:type "primary" :onClick open-create-modal}
         [antd/plus-icon] "新增参数"]]
       [antd/table {:rowKey "config_id"
                    :loading loading?
                    :columns (config-columns)
                    :dataSource (clj->js items)
                    :pagination {:pageSize 10 :total total}}]
       [form-modal]])))
