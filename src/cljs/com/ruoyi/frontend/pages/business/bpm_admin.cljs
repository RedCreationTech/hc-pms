(ns com.ruoyi.frontend.pages.business.bpm-admin
  "BPM 管理套件通用 CRUD 页面（流程表单/分类/用户分组/监听器/表达式/设置）。
   form 模块额外支持表单设计器（对齐 vben @form-create 设计器）。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [PlusOutlined ReloadOutlined EditOutlined DeleteOutlined]]
   [clojure.walk :as walk]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.form-designer :as fdm]))

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

(defn- columns-for
  "构建列定义。form 模块附加操作列（设计/编辑/删除）。"
  [cfg {:keys [on-design on-edit on-delete]}]
  (let [base (mapv (fn [[title key w]]
                     (if (= key "status")
                       (clj->js {:title title :dataIndex key :key key :width w
                                 :render (fn [v] (r/as-element (status-tag v)))})
                       (clj->js {:title title :dataIndex key :key key :width w})))
                   (:columns cfg))]
    (into-array
     (if on-design
       (conj base
             (clj->js {:title "操作" :key "action" :width 170
                       :render (fn [_ record]
                                 (let [m (js->clj record :keywordize-keys true)
                                       id (:form_id m)]
                                   (r/as-element
                                    [antd/space {:size 2}
                                     [antd/button {:type "link" :size "small" :on-click #(on-design m)} "设计"]
                                     [antd/button {:type "link" :size "small" :on-click #(on-edit m)} "编辑"]
                                     [antd/button {:type "link" :size "small" :danger true
                                                   :on-click #(on-delete id)} "删除"]])))}))
       base))))

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

;; 表单设计器弹窗（form 模块专用，加载/保存 conf+fields）
(defn- designer-modal [{:keys [record set-record! on-saved]}]
  (let [[schema set-schema!] (hooks/use-state nil)
        [loading set-loading!] (hooks/use-state false)
        id (:form_id record)]
    (hooks/use-effect
     (fn []
       (when id
         (set-loading! true)
         (api/bpmmgmt-get "form" id
                          (fn [res]
                            (let [d (:data res)]
                              (set-schema!
                               (merge {:form-name (or (:form_name d) "")}
                                      (or (when-let [j (:form_json d)]
                                            (if (string? j)
                                              (js->clj (js/JSON.parse j) :keywordize-keys true)
                                              (walk/keywordize-keys j)))
                                          {:fields []})))
                              (set-loading! false)))
                          (fn [_] (set-loading! false) (antd/error! "加载表单失败")))))
     [id])
    [antd/modal {:title (str "表单设计 · " (:form_name record)) :open (boolean record)
                 :width 1180 :destroyOnHidden true :footer nil
                 :onCancel #(set-record! nil)}
     (if loading
       [:div {:style {:padding 40 :textAlign "center"}} "加载中..."]
       [fdm/form-designer
        {:schema schema
         :on-save (fn [s]
                    (api/bpmmgmt-update "form" id
                                        (merge (select-keys record [:form_key :status :remark])
                                               {:form_name (:form-name s)
                                                :form_json (js/JSON.stringify (clj->js (dissoc s :form-name)))})
                                        (fn [_] (antd/success! "表单已保存")
                                          (set-record! nil) (on-saved))
                                        (fn [e] (antd/error! (str "保存失败: " e)))))}])]))

(defn bpm-admin-page [{:keys [module]}]
  (let [cfg (get admin-config module)
        items @(rf/subscribe [:bpmmgmt/items module])
        total @(rf/subscribe [:bpmmgmt/total module])
        loading? @(rf/subscribe [:bpmmgmt/loading? module])
        [designer-record set-designer-record!] (hooks/use-state nil)
        refresh (fn [] (rf/dispatch [:bpmmgmt/fetch module {}]))]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [page-toolbar/toolbar-button {:kind :add :icon (r/as-element [:> PlusOutlined])
                                            :on-click #(rf/dispatch [:bpmmgmt/open module])
                                            :label (str "新增" (:title cfg))}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click refresh}]]}]
     [antd/table {:rowKey (:id cfg)
                  :columns (columns-for cfg
                                        (when (= module "form")
                                          {:on-design set-designer-record!
                                           :on-edit #(rf/dispatch [:bpmmgmt/edit module %])
                                           :on-delete (fn [id]
                                                        (antd/modal-confirm!
                                                         (fn [] (api/bpmmgmt-delete "form" id
                                                                                     (fn [_] (antd/success! "已删除") (refresh))
                                                                                     (fn [e] (antd/error! e))))))}))
                  :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]
     [form-modal {:module module :cfg cfg}]
     (when (= module "form")
       [designer-modal {:record designer-record :set-record! set-designer-record!
                        :on-saved refresh}])]))
