(ns com.ruoyi.frontend.pages.business.bpm-model
  "流程模型管理 —— 查看/部署/在线设计(bpmn-js)。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [ReloadOutlined PlayCircleOutlined EditOutlined SaveOutlined
                                UndoOutlined RedoOutlined ZoomInOutlined ZoomOutOutlined
                                CompressOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.bpmn-modeler :as bpmn]))

(defn- model-columns []
  #js [#js {:title "ID" :dataIndex "model_id" :key "model_id" :width 70}
       #js {:title "流程key" :dataIndex "model_key" :key "model_key" :width 150}
       #js {:title "流程名称" :dataIndex "model_name" :key "model_name"}
       #js {:title "版本" :dataIndex "version" :key "version" :width 70}
       #js {:title "表单" :dataIndex "form_type" :key "form_type" :width 90
            :render (fn [v] (r/as-element (if (= v "1") [antd/tag {:color "green"} "动态表单"] "-")))}
       #js {:title "部署ID" :dataIndex "deployment_id" :key "deployment_id" :width 90
            :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v] (r/as-element (if (= v "1") [antd/tag {:color "green"} "启用"] [antd/tag {:color "red"} "停用"])))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 190
            :render (fn [_ ^js record]
                      (let [model (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/space
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> EditOutlined])
                                        :on-click #(rf/dispatch [:bpm/model-open-designer model])}
                           "设计"]
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> PlayCircleOutlined])
                                        :on-click #(rf/dispatch [:bpm/model-deploy (:model_id model)])}
                           "部署"]])))}])

(defn- designer-toolbar
  [{:keys [modeler-ref on-save on-close]}]
  [:div {:style {:display "flex" :alignItems "center" :gap 8 :padding "8px 12px"
                 :borderBottom "1px solid #eee" :background "#fafafa"}}
   [antd/space {:size 4}
    [antd/button {:size "small" :icon (r/as-element [:> UndoOutlined]) :on-click #(bpmn/undo! (.-current modeler-ref))}]
    [antd/button {:size "small" :icon (r/as-element [:> RedoOutlined]) :on-click #(bpmn/redo! (.-current modeler-ref))}]
    [antd/divider {:type "vertical"}]
    [antd/button {:size "small" :icon (r/as-element [:> ZoomInOutlined]) :on-click #(bpmn/zoom-in! (.-current modeler-ref))}]
    [antd/button {:size "small" :icon (r/as-element [:> ZoomOutOutlined]) :on-click #(bpmn/zoom-out! (.-current modeler-ref))}]
    [antd/button {:size "small" :icon (r/as-element [:> CompressOutlined]) :on-click #(bpmn/fit-viewport! (.-current modeler-ref))}]
    [antd/divider {:type "vertical"}]
    [antd/button {:size "small" :on-click on-close} "取消"]
    [antd/button {:size "small" :type "primary" :icon (r/as-element [:> SaveOutlined]) :on-click on-save} "保存流程"]]])

(defn- props-panel
  [{:keys [selected sp name set-name! cand set-cand! cond set-cond! on-apply]}]
  [:div {:style {:width 340 :border "1px solid #eee" :borderRadius 4 :padding 12 :background "#fff" :overflow "auto"}}
   (if selected
     [:div
      [:h4 {:style {:margin "0 0 12px"}} "元素属性"]
      [antd/form {:layout "vertical"}
       [antd/form-item {:label "节点名称"}
        [antd/input {:value name :placeholder "节点名称" :onChange (fn [e] (set-name! (-> e .-target .-value)))}]]
       [antd/form-item {:label "节点ID"} [:div {:style {:color "#999" :fontSize 13}} (:id sp)]]
       (when (= "bpmn:UserTask" (:type sp))
         [antd/form-item {:label "审批人 (登录名, 逗号分隔)"}
          [antd/input {:value cand :placeholder "如: admin,manager" :onChange (fn [e] (set-cand! (-> e .-target .-value)))}]])
       (when (= "bpmn:SequenceFlow" (:type sp))
         [antd/form-item {:label "流转条件表达式"}
          [antd/input {:value cond :placeholder "如: ${approved == true}" :onChange (fn [e] (set-cond! (-> e .-target .-value)))}]])
       [antd/button {:type "primary" :block true :on-click on-apply} "应用到节点"]]]
     [:div {:style {:color "#999" :textAlign "center" :paddingTop 40}} "点击节点/连线编辑属性"])])

;; Tab 页内容组件（避免深层嵌套，拆成独立函数）
(defn- basic-info-tab [mname set-mname! mkey set-mkey! mcat set-mcat! mform-type set-mform-type!]
  [:div {:style {:padding 16 :maxWidth 500}}
   [antd/form {:layout "vertical"}
    [antd/form-item {:label "流程名称"}
     [antd/input {:value mname :onChange (fn [e] (set-mname! (-> e .-target .-value)))}]]
    [antd/form-item {:label "流程Key"}
     [antd/input {:value mkey :onChange (fn [e] (set-mkey! (-> e .-target .-value)))}]]
    [antd/form-item {:label "分类ID"}
     [antd/input-number {:value (some-> mcat js/Number) :style {:width "100%"}}
                        ;; onChange 需手动绑定 value
                        ]]
    [antd/form-item {:label "表单类型"}
     [antd/select {:value mform-type :style {:width "100%"} :onChange (fn [v] (set-mform-type! (str v)))}
      [antd/select-option {:value "0"} "无表单"]
      [antd/select-option {:value "1"} "动态表单"]]]]])

(defn- form-design-tab [mform-json set-mform-json!]
  [:div {:style {:padding 16}}
   [antd/form {:layout "vertical"}
    [antd/form-item {:label "表单 JSON (动态表单字段定义)"}
     [antd/text-area {:value mform-json :rows 16 :style {:fontFamily "monospace"}
                      :onChange (fn [e] (set-mform-json! (-> e .-target .-value)))}]]]])

(defn- process-design-tab
  [{:keys [xml modeler-ref on-save on-close on-select selected sp nname set-nname! cand set-cand! cond set-cond!]}]
  [:div {:style {:display "flex" :flexDirection "column"}}
   [designer-toolbar {:modeler-ref modeler-ref :on-save on-save :on-close on-close}]
   [:div {:style {:display "flex" :marginTop 8}}
    [:div {:style {:flex 1 :marginRight 12}}
     [bpmn/bpmn-modeler {:xml xml :modeler-ref modeler-ref :on-error (fn [e] (antd/error! e))
                         :on-select on-select}]]
    [props-panel {:selected selected :sp sp :name nname :set-name! set-nname!
                  :cand cand :set-cand! set-cand! :cond cond :set-cond! set-cond!
                  :on-apply #(do (bpmn/update-selected! (.-current modeler-ref) selected
                                                         {:name nname :candidate-users cand :condition cond})
                                 (antd/success! "已应用到当前节点"))}]]])

(defn- extra-tab [mremark set-mremark!]
  [:div {:style {:padding 16 :maxWidth 500}}
   [antd/form {:layout "vertical"}
    [antd/form-item {:label "备注"}
     [antd/text-area {:value mremark :rows 6 :onChange (fn [e] (set-mremark! (-> e .-target .-value)))}]]]])

(defn- designer-modal []
  (let [visible? @(rf/subscribe [:bpm-designer/visible?])
        current @(rf/subscribe [:bpm-designer/current])
        xml @(rf/subscribe [:bpm-designer/bpmn-xml])
        loading? @(rf/subscribe [:bpm-designer/loading?])
        modeler-ref (hooks/use-ref nil)
        [tab set-tab!] (hooks/use-state "basic")
        [selected set-selected!] (hooks/use-state nil)
        [sp set-sp!] (hooks/use-state nil)
        [nname set-nname!] (hooks/use-state "")
        [cand set-cand!] (hooks/use-state "")
        [cond set-cond!] (hooks/use-state "")
        [mname set-mname!] (hooks/use-state "")
        [mkey set-mkey!] (hooks/use-state "")
        [mcat set-mcat!] (hooks/use-state "")
        [mform-type set-mform-type!] (hooks/use-state "0")
        [mform-json set-mform-json!] (hooks/use-state "")
        [mremark set-mremark!] (hooks/use-state "")
        tabs [{:key "basic" :label "基本信息"} {:key "form" :label "表单设计"}
              {:key "process" :label "流程设计"} {:key "extra" :label "更多设置"}]
        save-model (fn []
                     (bpmn/save-bpmn! (.-current modeler-ref)
                                      (fn [x]
                                        (rf/dispatch [:bpm/designer-save
                                                      {:model_name mname :model_key mkey
                                                       :category_id (some-> mcat js/Number) :form_type mform-type
                                                       :form_json mform-json :remark mremark
                                                       :bpmn_xml x}]))
                                      (fn [e] (antd/error! e))))]
    (hooks/use-effect
     (fn []
       (when (and current (seq (:model_name current)))
         (set-mname! (or (:model_name current) ""))
         (set-mkey! (or (:model_key current) ""))
         (set-mcat! (or (:category_id current) ""))
         (set-mform-type! (or (:form_type current) "0"))
         (set-mform-json! (or (:form_json current) ""))
         (set-mremark! (or (:remark current) ""))))
     [visible?])
    (hooks/use-effect
     (fn []
       (when selected
         (let [p (bpmn/selected-props selected)]
           (set-nname! (or (:name p) ""))
           (set-cand! (or (:candidate-users p) ""))
           (set-cond! (or (:condition p) ""))))
       js/undefined)
     [selected])
    [antd/modal {:title (str "流程模型 · " (:model_name current))
                 :open visible? :width 1300 :destroyOnHidden true :footer nil
                 :onCancel #(rf/dispatch [:bpm/designer-close])}
     (if loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div
        [:div {:style {:display "flex" :gap 4 :padding "0 12px" :borderBottom "1px solid #eee"}}
         (doall
          (for [{:keys [key label]} tabs]
            ^{:key key}
            [:div {:style {:padding "10px 16px" :cursor "pointer" :fontSize 14
                           :borderBottom (if (= key tab) "2px solid #409eff" "2px solid transparent")
                           :color (if (= key tab) "#409eff" "#666")}
                   :on-click #(set-tab! key)}
             label]))]
        (case tab
          "basic" [basic-info-tab mname set-mname! mkey set-mkey! mcat set-mcat! mform-type set-mform-type!]
          "form" [form-design-tab mform-json set-mform-json!]
          "process" [process-design-tab {:xml xml :modeler-ref modeler-ref :on-save save-model
                                         :on-close #(rf/dispatch [:bpm/designer-close])
                                         :on-select (fn [el] (set-selected! el)
                                                              (set-sp! (when el (bpmn/selected-props el))))
                                         :selected selected :sp sp :nname nname :set-nname! set-nname!
                                         :cand cand :set-cand! set-cand! :cond cond :set-cond! set-cond!}]
          "extra" [extra-tab mremark set-mremark!])])]))

(defn bpm-model-page []
  (let [items @(rf/subscribe [:bpm-model/items])
        total @(rf/subscribe [:bpm-model/total])
        loading? @(rf/subscribe [:bpm-model/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [:div {:style {:fontSize 15 :fontWeight 600}} "流程模型"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/model-fetch {}])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "model_id"
                  :columns (model-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [designer-modal]]))
