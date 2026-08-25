(ns com.ruoyi.frontend.pages.business.bpm-start
  "发起流程 —— 选择模型 → 动态表单渲染 → 提交（对齐 vben 流程中心发起）。"
  (:require
   [clojure.walk :as walk]
   [reagent.core :as r]
   ["@ant-design/icons" :refer [ReloadOutlined PlayCircleOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.form-render :as form-render]))

(defn- model-columns [open-start]
  #js [#js {:title "流程名称" :dataIndex "model_name" :key "model_name"
            :render (fn [v] (r/as-element [:span {:style {:fontWeight 600}} v]))}
       #js {:title "流程Key" :dataIndex "model_key" :key "model_key" :width 200}
       #js {:title "版本" :dataIndex "version" :key "version" :width 70}
       #js {:title "表单" :dataIndex "form_type" :key "form_type" :width 110
            :render (fn [v] (r/as-element
                              (case v
                                "1" [antd/tag {:color "blue"} "动态表单"]
                                "2" [antd/tag {:color "purple"} "自定义表单"]
                                [antd/tag "无表单"])))}
       #js {:title "操作" :key "action" :width 120
            :render (fn [_ ^js record]
                      (let [model (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/button {:type "primary" :size "small"
                                       :icon (r/as-element [:> PlayCircleOutlined])
                                       :on-click #(open-start model)}
                          "发起"])))}])

(defn bpm-start-page []
  (r/with-let [models (r/atom [])
               total (r/atom 0)
               loading? (r/atom true)
               start-model (r/atom nil)
               form-schema (r/atom nil)
               form-loading? (r/atom false)
               values (r/atom {})
               business-key (r/atom "")
               submitting? (r/atom false)
               refresh (fn []
                         (reset! loading? true)
                         (api/bpm-list-models {:page 1 :size 1000}
                                              (fn [res]
                                                (reset! models (or (:rows (:data res)) []))
                                                (reset! total (:total (:data res) 0))
                                                (reset! loading? false))
                                              (fn [_] (reset! loading? false) (antd/error! "加载模型列表失败"))))
               _ (refresh)
               open-start (fn [model]
                            (reset! start-model model)
                            (reset! values {})
                            (reset! business-key "")
                            (reset! form-schema nil)
                            (when (and (= "1" (:form_type model)) (:form_id model))
                              (reset! form-loading? true)
                              (api/bpm-get-form (:form_id model)
                                                 (fn [res]
                                                   (let [d (:data res)
                                                         j (:form_json d)
                                                         schema (if (string? j)
                                                                  (js->clj (js/JSON.parse j) :keywordize-keys true)
                                                                  (walk/keywordize-keys j))]
                                                     (reset! form-schema schema)
                                                     (reset! form-loading? false)))
                                                 (fn [_] (reset! form-loading? false) (antd/error! "加载表单失败")))))
               submit (fn []
                        (when-let [model @start-model]
                          (reset! submitting? true)
                          (api/bpm-start-instance {:model_id (:model_id model)
                                                   :business_key (str "start-" (js/Date.now))
                                                   :form_data @values}
                                                  (fn [_]
                                                    (reset! submitting? false)
                                                    (antd/success! "流程发起成功")
                                                    (reset! start-model nil))
                                                  (fn [e]
                                                    (reset! submitting? false)
                                                    (antd/error! (str "发起失败: " e))))))]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left [:div {:style {:fontSize 15 :fontWeight 600}} "发起流程"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click refresh}]]}]
     [:div
      [antd/table {:rowKey "model_id" :columns (model-columns open-start)
                   :dataSource (clj->js @models) :loading @loading?
                   :pagination {:total @total :pageSize 10 :showSizeChanger true
                                :showTotal (fn [t] (str "共 " t " 条"))}}]]
     ;; 发起弹窗
     [antd/modal {:title (str "发起流程 · " (:model_name @start-model))
                  :open (boolean @start-model)
                  :confirmLoading @submitting?
                  :width 600
                  :onOk submit
                  :onCancel #(reset! start-model nil)}
      (when-let [model @start-model]
        (if (= "2" (:form_type model))
          [:div {:style {:padding 24 :textAlign "center" :color "#909399"}}
           "该模型使用自定义表单，请前往对应业务页面发起"]
          (if @form-loading?
            [:div {:style {:padding 48 :textAlign "center"}} "表单加载中..."]
            (if-let [schema @form-schema]
              [:div {:style {:padding 8}}
               [form-render/form-render {:schema schema
                                         :values @values
                                         :on-change (fn [v] (reset! values v))}]
               [:div {:style {:marginTop 8}}
                [:div.bpm-f-label "业务备注"]
                [antd/input {:placeholder "业务备注(可选)" :value @business-key
                             :onChange (fn [e] (reset! business-key (-> e .-target .-value)))}]]]
              [:div {:style {:padding 48 :textAlign "center" :color "#909399"}}
               "该模型未配置动态表单，将直接发起"]))))]]))
