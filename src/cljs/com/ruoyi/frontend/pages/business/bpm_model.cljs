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

;; 设计器顶部工具栏
(defn- designer-toolbar
  [{:keys [modeler-ref on-save on-close]}]
  [:div {:style {:display "flex" :alignItems "center" :gap 8 :padding "8px 12px"
                 :borderBottom "1px solid #eee" :background "#fafafa" :borderRadius "4px 4px 0 0"}}
   [antd/space {:size 4}
    [antd/button {:size "small" :icon (r/as-element [:> UndoOutlined])
                  :on-click #(bpmn/undo! (.-current modeler-ref))}]
    [antd/button {:size "small" :icon (r/as-element [:> RedoOutlined])
                  :on-click #(bpmn/redo! (.-current modeler-ref))}]
    [antd/divider {:type "vertical"}]
    [antd/button {:size "small" :icon (r/as-element [:> ZoomInOutlined])
                  :on-click #(bpmn/zoom-in! (.-current modeler-ref))}]
    [antd/button {:size "small" :icon (r/as-element [:> ZoomOutOutlined])
                  :on-click #(bpmn/zoom-out! (.-current modeler-ref))}]
    [antd/button {:size "small" :icon (r/as-element [:> CompressOutlined])
                  :on-click #(bpmn/fit-viewport! (.-current modeler-ref))}]
    [antd/divider {:type "vertical"}]
    [antd/button {:size "small" :on-click on-close} "取消"]
    [antd/button {:size "small" :type "primary" :icon (r/as-element [:> SaveOutlined]) :on-click on-save}
     "保存流程"]]])

;; 右侧属性面板：按元素类型分组显示 常规/审批人/流转条件
(defn- props-panel
  [{:keys [selected sp name set-name! cand set-cand! cond set-cond! on-apply]}]
  [:div {:style {:width 340 :border "1px solid #eee" :borderRadius 4 :padding 12 :background "#fff"
                 :overflow "auto"}}
   (if selected
     [:div
      [:h4 {:style {:margin "0 0 12px"}} "元素属性"]
      [antd/form {:layout "vertical"}
       ;; 常规
       [antd/form-item {:label "节点名称"}
        [antd/input {:value name :placeholder "节点名称"
                     :onChange (fn [e] (set-name! (-> e .-target .-value)))}]]
       [antd/form-item {:label "节点ID"}
        [:div {:style {:color "#999" :fontSize 13}} (:id sp)]]
       ;; 审批人（用户任务）
       (when (= "bpmn:UserTask" (:type sp))
         [antd/form-item {:label "审批人 (登录名, 逗号分隔)"}
          [antd/input {:value cand :placeholder "如: admin,manager"
                       :onChange (fn [e] (set-cand! (-> e .-target .-value)))}]])
       ;; 流转条件（序列流）
       (when (= "bpmn:SequenceFlow" (:type sp))
         [antd/form-item {:label "流转条件表达式"}
          [antd/input {:value cond :placeholder "如: ${approved == true}"
                       :onChange (fn [e] (set-cond! (-> e .-target .-value)))}]])
       [antd/button {:type "primary" :block true :on-click on-apply}
        "应用到节点"]]]
     [:div {:style {:color "#999" :textAlign "center" :paddingTop 40}}
      "点击节点/连线编辑属性"])])

(defn- designer-modal []
  (let [visible? @(rf/subscribe [:bpm-designer/visible?])
        current @(rf/subscribe [:bpm-designer/current])
        xml @(rf/subscribe [:bpm-designer/bpmn-xml])
        loading? @(rf/subscribe [:bpm-designer/loading?])
        modeler-ref (hooks/use-ref nil)
        [selected set-selected!] (hooks/use-state nil)
        [sp set-sp!] (hooks/use-state nil)
        [name set-name!] (hooks/use-state "")
        [cand set-cand!] (hooks/use-state "")
        [cond set-cond!] (hooks/use-state "")
        save-xml (fn []
                   (bpmn/save-bpmn! (.-current modeler-ref)
                                    (fn [x] (rf/dispatch [:bpm/designer-save x]))
                                    (fn [e] (antd/error! e))))]
    (hooks/use-effect
     (fn []
       (when selected
         (let [p (bpmn/selected-props selected)]
           (set-name! (or (:name p) ""))
           (set-cand! (or (:candidate-users p) ""))
           (set-cond! (or (:condition p) ""))))
       js/undefined)
     [selected])
    [antd/modal {:title (str "流程设计 · " (:model_name current))
                 :open visible?
                 :width 1250
                 :destroyOnHidden true
                 :footer nil
                 :onCancel #(rf/dispatch [:bpm/designer-close])}
     (if loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div {:style {:display "flex" :flexDirection "column"}}
        [designer-toolbar {:modeler-ref modeler-ref :on-save save-xml
                           :on-close #(rf/dispatch [:bpm/designer-close])}]
        [:div {:style {:display "flex" :marginTop 8}}
         [:div {:style {:flex 1 :marginRight 12}}
          [bpmn/bpmn-modeler {:xml xml :modeler-ref modeler-ref
                              :on-error (fn [e] (antd/error! e))
                              :on-select (fn [el]
                                           (set-selected! el)
                                           (set-sp! (when el (bpmn/selected-props el))))}]]
         [props-panel {:selected selected :sp sp :name name :set-name! set-name!
                       :cand cand :set-cand! set-cand! :cond cond :set-cond! set-cond!
                       :on-apply #(do (bpmn/update-selected! (.-current modeler-ref) selected
                                                             {:name name :candidate-users cand :condition cond})
(antd/success!"已应用到当前节点"))}]]])]))

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
