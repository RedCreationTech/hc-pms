(ns com.ruoyi.frontend.pages.business.bpm-start
  "发起流程 —— 选择模型 → 动态表单渲染 → 提交（对齐 vben 流程中心发起）。"
  (:require
    ["@ant-design/icons" :refer [ReloadOutlined PlayCircleOutlined]]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.form-render :as form-render]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [reagent.core :as r]))


(defn- model-columns
  [open-start]
  #js [#js {:title "流程名称" :dataIndex "model_name" :key "model_name"
            :render (fn [v] (r/as-element [:span {:style {:fontWeight 600}} v]))}
       #js {:title "流程Key" :dataIndex "model_key" :key "model_key" :width 200}
       #js {:title "版本" :dataIndex "version" :key "version" :width 70}
       #js {:title "表单" :dataIndex "form_type" :key "form_type" :width 110
            :render (fn [v]
                      (r/as-element
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


(defn- validate-fields
  "前端校验表单字段（必填 + 正则 pattern）。返回错误消息或 nil。"
  [fields values]
  (first (keep (fn [f]
                 (let [v (get values (:field f))
                       vstr (if (nil? v) "" (str v))]
                   (cond
                     (and (some :required (or (:validate f) [])) (str/blank? vstr))
                     (str "请填写" (:title f))
                     (some :pattern (or (:validate f) []))
                     (let [rule (first (filter :pattern (:validate f)))
                           pattern (re-pattern (:pattern rule))]
                       (when-not (re-matches pattern vstr)
                         (or (:message rule) "格式不正确")))
                     (some :min (or (:validate f) []))
                     (let [rule (first (filter :min (:validate f)))
                           min (or (:min rule) 0)]
                       (when (< (count vstr) min)
                         (or (:message rule) (str "长度不能小于" min))))
                     (some :max (or (:validate f) []))
                     (let [rule (first (filter :max (:validate f)))
                           max (or (:max rule) 0)]
                       (when (> (count vstr) max)
                         (or (:message rule) (str "长度不能超过" max)))))))
               fields)))


(defn- build-dept-tree
  "部门列表 → antd tree-data（两级，含 key）。"
  [depts]
  (mapv (fn [d]
          {:key (:dept_id d) :title (:dept_name d) :value (:dept_id d)
           :children (mapv (fn [c]
                             {:key (:dept_id c) :title (:dept_name c) :value (:dept_id c)})
                           (filter #(= (:dept_id d) (:parent_id %)) depts))})
        (filter #(= 0 (:parent_id %)) depts)))


(defn- fill-field-data
  "为 tree-select/dict-select 字段注入数据源。"
  [schema field-type data-key data]
  (update schema :fields
          (fn [fs]
            (mapv (fn [f]
                    (if (= field-type (:type f))
                      (assoc-in f [:props data-key] data)
                      f))
                  fs))))


(defn- collect-start-select
  "遍历流程树收集 START_USER_SELECT 节点（发起人自选审批人）。"
  [node]
  (let [cfg (:config node)
        me (when (and cfg (= "START_USER_SELECT" (get-in cfg [:candidate-strategy])))
             [{:id (:id node) :name (:name node)}])
        child (when-let [c (:child-node node)] (collect-start-select c))
        conds (mapcat collect-start-select (or (:condition-nodes node) []))]
    (vec (concat me child conds))))


(defn- render-start-select
  "发起人自选审批人：用户多选。"
  [{:keys [users sel-value set-value!]}]
  [:div {:style {:marginBottom 12}}
   [:div.bpm-f-label "审批人自选"]
   [antd/select {:mode "multiple" :style {:width "100%"} :placeholder "请选择审批人"
                 :value sel-value :onChange set-value!}
    (doall (for [u users] ^{:key (:user_id u)}
                [antd/select-option {:value (:user_id u)} (:nick_name u)]))]])


(defn bpm-start-page
  []
  (r/with-let [models (r/atom [])
               total (r/atom 0)
               loading? (r/atom true)
               start-model (r/atom nil)
               form-schema (r/atom nil)
               form-loading? (r/atom false)
               values (r/atom {})
               business-key (r/atom "")
               submitting? (r/atom false)
               start-select-nodes (r/atom [])
               start-select-value (r/atom [])
               users (r/atom [])
               fields-perm (r/atom {})
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
                            (reset! start-select-nodes [])
                            (reset! start-select-value [])
                            (reset! fields-perm {})
                            (api/bpm-get-model (:model_id model)
                                               (fn [res]
                                                 (let [fp (:fields_permission (:data res))]
                                                   (when fp
                                                     (reset! fields-perm
                                                             (if (string? fp)
                                                               (js->clj (js/JSON.parse fp))
                                                               fp)))))
                                               (fn [_] nil))
                            (api/bpm-model-tree (:model_id model)
                                                (fn [res]
                                                  (let [nodes (collect-start-select (:data res))]
                                                    (when (seq nodes)
                                                      (reset! start-select-nodes nodes)
                                                      (when (empty? @users)
                                                        (api/list-users {:page 1 :size 1000}
                                                                        (fn [r] (reset! users (or (:rows (:data r)) [])))
                                                                        (fn [_] nil))))))
                                                (fn [_] nil))
                            (let [parse-schema (fn [j]
                                                 (if (string? j)
                                                   (js->clj (js/JSON.parse j) :keywordize-keys true)
                                                   (walk/keywordize-keys j)))
                                  apply-schema!
                                  (fn [schema]
                                    ;; tree-select/dict-select 字段数据源注入（与表单记录路径共用）
                                    (let [tree-fields (filter #(= "tree-select" (:type %)) (:fields schema))
                                          dict-fields (filter #(= "dict-select" (:type %)) (:fields schema))]
                                      (when (seq tree-fields)
                                        (api/list-depts {:parent_id 0}
                                                        (fn [dr]
                                                          (reset! form-schema
                                                                  (fill-field-data schema "tree-select" :tree-data
                                                                                   (build-dept-tree (walk/keywordize-keys (or (:rows (:data dr)) (:data dr) []))))))
                                                        #()))
                                      (doseq [df dict-fields]
                                        (when-let [dt (get-in df [:props :dict-type])]
                                          (api/list-dict-data {:dict_type dt}
                                                              (fn [dr]
                                                                (let [opts (mapv (fn [it] {:label (:dict_label it) :value (:dict_value it)})
                                                                                 (walk/keywordize-keys (or (:data dr) [])))
                                                                      base (or @form-schema schema)]
                                                                  (reset! form-schema (fill-field-data base "dict-select" :options opts))))
                                                              #()))))
                                    (reset! form-schema schema)
                                    (reset! form-loading? false))]
                              (when (= "1" (:form_type model))
                                (reset! form-loading? true)
                                (if (pos? (long (or (:form_id model) 0)))
                                  ;; form_id>0：走动态表单记录
                                  (api/bpm-get-form (:form_id model)
                                                    (fn [res] (apply-schema! (parse-schema (:form_json (:data res)))))
                                                    (fn [_] (reset! form-loading? false) (antd/error! "加载表单失败")))
                                  ;; form_id=0/空：回退直接解析模型内嵌 form_json
                                  (if-let [fj (:form_json model)]
                                    (apply-schema! (parse-schema fj))
                                    (reset! form-loading? false))))))
               submit (fn []
                        (if-let [err (validate-fields (:fields @form-schema) @values)]
                          (antd/error! err)
                          (when-let [model @start-model]
                            (reset! submitting? true)
                            (api/bpm-start-instance {:model_id (:model_id model)
                                                     :business_key (str "start-" (js/Date.now))
                                                     :form_data (assoc @values :startUserSelected @start-select-value)}
                                                    (fn [_]
                                                      (reset! submitting? false)
                                                      (antd/success! "流程发起成功")
                                                      (reset! start-model nil))
                                                    (fn [e]
                                                      (reset! submitting? false)
                                                      (antd/error! (str "发起失败: " e)))))))]
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
                         (when (seq @start-select-nodes)
                           [render-start-select {:users @users :sel-value @start-select-value
                                                 :set-value! #(reset! start-select-value (vec %))}])
                         [form-render/form-render {:schema schema
                                                   :values @values
                                                   :field-permissions @fields-perm
                                                   :layout (or (get-in schema [:conf :form :layout]) "vertical")
                                                   :on-change (fn [v] (reset! values v))}]
                         [:div {:style {:marginTop 8}}
                          [:div.bpm-f-label "业务备注"]
                          [antd/input {:placeholder "业务备注(可选)" :value @business-key
                                       :onChange (fn [e] (reset! business-key (-> e .-target .-value)))}]]]
                        [:div {:style {:padding 48 :textAlign "center" :color "#909399"}}
                         (when (seq @start-select-nodes)
                           [render-start-select {:users @users :sel-value @start-select-value
                                                 :set-value! #(reset! start-select-value (vec %))}])
                         "该模型未配置动态表单，将直接发起"]))))]]))
