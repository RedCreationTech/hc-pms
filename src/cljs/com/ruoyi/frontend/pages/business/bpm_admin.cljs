(ns com.ruoyi.frontend.pages.business.bpm-admin
  "BPM 管理套件通用 CRUD 页面（流程表单/分类/用户分组/监听器/表达式/设置）。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

;; 各模块配置：标题 / 列 / 表单字段
(def ^:private admin-config
  {"form"      {:title "流程表单"
                :id "form_id"
                :columns [["ID" "form_id" 70] ["表单名称" "form_name"] ["表单Key" "form_key" 150]
                          ["状态" "status" 90] ["创建时间" "create_time" 170]]
                :fields [["表单名称" "form_name" "input"] ["表单Key" "form_key" "input"]
                         ["表单JSON" "form_json" "textarea"] ["状态" "status" "select"]]}
   "category"  {:title "流程分类"
                :id "category_id"
                :columns [["ID" "category_id" 70] ["分类名称" "name"] ["编码" "code" 120]
                          ["排序" "sort" 80] ["状态" "status" 90] ["创建时间" "create_time" 170]]
                :fields [["分类名称" "name" "input"] ["编码" "code" "input"]
                         ["排序" "sort" "number"] ["状态" "status" "select"]]}
   "user-group" {:title "用户分组"
                 :id "group_id"
                 :columns [["ID" "group_id" 70] ["分组名称" "name"] ["描述" "description"]
                           ["用户IDs" "user_ids" 150] ["状态" "status" 90] ["创建时间" "create_time" 170]]
                 :fields [["分组名称" "name" "input"] ["描述" "description" "input"]
                          ["用户IDs(逗号)" "user_ids" "input"] ["状态" "status" "select"]]}
   "listener"  {:title "流程监听器"
                :id "listener_id"
                :columns [["ID" "listener_id" 70] ["名称" "name"] ["类型" "type" 90]
                          ["事件" "event" 120] ["监听源" "listener"] ["状态" "status" 90]]
                :fields [["名称" "name" "input"] ["类型(1执行/2任务)" "type" "input"]
                         ["事件" "event" "input"] ["监听源" "listener" "input"] ["状态" "status" "select"]]}
   "expression" {:title "流程表达式"
                 :id "expression_id"
                 :columns [["ID" "expression_id" 70] ["名称" "name"] ["格式" "format" 90]
                           ["表达式" "expression"] ["状态" "status" 90]]
                 :fields [["名称" "name" "input"] ["格式" "format" "input"]
                          ["表达式" "expression" "textarea"] ["状态" "status" "select"]]}
   "settings"  {:title "流程设置"
                :id "settings_id"
                :columns [["ID" "settings_id" 70] ["设置名" "name"] ["值" "value"]
                          ["说明" "description"] ["状态" "status" 90]]
                :fields [["设置名" "name" "input"] ["值" "value" "input"]
                         ["说明" "description" "textarea"] ["状态" "status" "select"]]}})

(defn- status-tag [v]
  (if (= v "1")
    [antd/tag {:color "green"} "启用"]
    [antd/tag {:color "red"} "停用"]))

(defn- columns-for [cfg]
  (into-array
   (mapv (fn [[title key w]]
           (if (= key "status")
             (clj->js {:title title :dataIndex key :key key :width w
                       :render (fn [v] (r/as-element (status-tag v)))})
             (clj->js {:title title :dataIndex key :key key :width w})))
         (:columns cfg))))

(defn- form-modal [{:keys [module cfg]}]
  (let [visible? @(rf/subscribe [:bpmmgmt/modal-visible? module])
        form-data @(rf/subscribe [:bpmmgmt/form-data module])
        [form] (antd/form-use-form)]
    (hooks/use-effect
     (fn [] (when visible? (.setFieldsValue form (clj->js (merge {:status "0"} form-data)))) js/undefined)
     [visible? form-data])
    [antd/modal {:title (str (:title cfg)) :open visible?
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:bpmmgmt/close module])}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [v] (rf/dispatch [:bpmmgmt/submit module (js->clj v :keywordize-keys true)]))}
      (doall
       (for [[label key ftype] (:fields cfg)]
         (if (= ftype "select")
           ^{:key key}
           [antd/form-item {:label label :name key}
            [antd/select {:style {:width "100%"}}
             [antd/select-option {:value "0"} "停用"]
             [antd/select-option {:value "1"} "启用"]]]
           ^{:key key}
           [antd/form-item {:label label :name key}
            (if (= ftype "textarea")
              [antd/text-area {:placeholder label :rows 3}]
              [antd/input {:placeholder label}])])))]]))

(defn bpm-admin-page [{:keys [module]}]
  (let [cfg (get admin-config module)
        items @(rf/subscribe [:bpmmgmt/items module])
        total @(rf/subscribe [:bpmmgmt/total module])
        loading? @(rf/subscribe [:bpmmgmt/loading? module])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:bpmmgmt/open module])
                                            :label (str "新增" (:title cfg))}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpmmgmt/fetch module {}])}]]}]
     [antd/table {:rowKey (:id cfg) :columns (columns-for cfg)
                  :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]
     [form-modal {:module module :cfg cfg}]]))
