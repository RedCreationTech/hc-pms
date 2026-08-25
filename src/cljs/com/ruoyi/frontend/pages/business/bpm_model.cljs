(ns com.ruoyi.frontend.pages.business.bpm-model
  "流程模型管理 —— 查看/部署/在线设计(bpmn-js)。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [ReloadOutlined PlayCircleOutlined EditOutlined SaveOutlined]]
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

;; 右侧属性面板：编辑选中审批节点的名称/审批人
(defn- props-panel
  [{:keys [selected sp name set-name! cand set-cand! on-apply]}]
  [:div {:style {:width 300 :border "1px solid #eee" :borderRadius 4 :padding 12 :background "#fff"}}
   (if (and selected (= "bpmn:UserTask" (:type sp)))
     [:div
      [:h4 {:style {:margin "0 0 12px"}} "审批节点属性"]
      [antd/form {:layout "vertical"}
       [antd/form-item {:label "节点名称"}
        [antd/input {:value name :placeholder "如: 部门经理审批"
                     :onChange (fn [e] (set-name! (-> e .-target .-value)))}]]
       [antd/form-item {:label "审批人 (登录名, 逗号分隔)"}
        [antd/input {:value cand :placeholder "如: admin,manager"
                     :onChange (fn [e] (set-cand! (-> e .-target .-value)))}]]
       [antd/button {:type "primary" :block true :on-click on-apply}
        "应用到节点"]]]
     [:div {:style {:color "#999" :textAlign "center" :paddingTop 40}}
      (if selected "请选择审批节点(UserTask)" "点击审批节点编辑属性")])])

(defn- designer-modal []
  (let [visible? @(rf/subscribe [:bpm-designer/visible?])
        current @(rf/subscribe [:bpm-designer/current])
        xml @(rf/subscribe [:bpm-designer/bpmn-xml])
        loading? @(rf/subscribe [:bpm-designer/loading?])
        modeler-ref (hooks/use-ref nil)
        [selected set-selected!] (hooks/use-state nil)
        [sp set-sp!] (hooks/use-state nil)
        [name set-name!] (hooks/use-state "")
        [cand set-cand!] (hooks/use-state "")]
    (hooks/use-effect
     (fn []
       (when selected
         (let [p (bpmn/selected-props selected)]
           (set-name! (or (:name p) ""))
           (set-cand! (or (:candidate-users p) ""))))
       js/undefined)
     [selected])
    [antd/modal {:title (str "流程设计 · " (:model_name current))
                 :open visible?
                 :width 1200
                 :destroyOnHidden true
                 :footer (r/as-element
                          [antd/space
                           [antd/button {:on-click #(rf/dispatch [:bpm/designer-close])} "取消"]
                           [antd/button {:type "primary"
                                         :icon (r/as-element [:> SaveOutlined])
                                         :on-click #(bpmn/save-bpmn! (.-current modeler-ref)
                                                                     (fn [x] (rf/dispatch [:bpm/designer-save x]))
                                                                     (fn [e] (antd/error! e)))}
                            "保存流程"]])
                 :onCancel #(rf/dispatch [:bpm/designer-close])}
     (if loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div {:style {:display "flex"}}
        [:div {:style {:flex 1 :marginRight 12}}
         [bpmn/bpmn-modeler {:xml xml :modeler-ref modeler-ref
                             :on-error (fn [e] (antd/error! e))
                             :on-select (fn [el]
                                          (set-selected! el)
                                          (set-sp! (when el (bpmn/selected-props el))))}]]
        [props-panel {:selected selected :sp sp :name name :set-name! set-name!
                      :cand cand :set-cand! set-cand!
                      :on-apply #(do (bpmn/update-selected! (.-current modeler-ref) selected
                                                             {:name name :candidate-users cand})
                                     (antd/success! "已应用到当前节点"))}]])]))

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
