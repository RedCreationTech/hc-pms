(ns com.ruoyi.frontend.pages.business.bpm-model
  "流程模型管理 —— 查看/部署/在线设计(bpmn-js)。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reagent.hooks :as hooks]
   ["@ant-design/icons" :refer [ReloadOutlined PlayCircleOutlined EditOutlined SaveOutlined
                                UndoOutlined RedoOutlined ZoomInOutlined ZoomOutOutlined
                                CompressOutlined DownloadOutlined EyeOutlined FolderOpenOutlined
                                AlignLeftOutlined ClearOutlined]]
   [clojure.walk :as walk]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.bpmn-modeler :as bpmn]
   [com.ruoyi.frontend.components.bpm-flow-designer :as bpmfd]
   [com.ruoyi.frontend.components.form-render :as fr]))

;; 连线加号可追加的节点类型（对齐 yudao simple-process-design）
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
       #js {:title "操作" :key "action" :width 340
            :render (fn [_ ^js record]
                      (let [model (js->clj record :keywordize-keys true)
                            mid (:model_id model)
                            running? (= "1" (:status model))]
                        (r/as-element
                         [antd/space
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> EditOutlined])
                                        :on-click #(rf/dispatch [:bpm/model-open-designer model])}
                           "设计"]
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> PlayCircleOutlined])
                                        :on-click #(rf/dispatch [:bpm/model-deploy mid])}
                           "部署"]
                          [antd/button {:type "link" :size "small"
                                        :on-click #(rf/dispatch [:bpm/definition-open model])}
                           "历史"]
                          [antd/popconfirm {:title (if running? "确认挂起该流程？挂起后不可发起" "确认激活该流程？")
                                            :on-confirm #(rf/dispatch [:bpm/model-state mid (if running? "2" "1")])}
                           [antd/button {:type "link" :size "small"}
                            (if running? "挂起" "激活")]]
                          [antd/popconfirm {:title "确认复制该模型？"
                                            :on-confirm #(rf/dispatch [:bpm/model-copy mid])}
                           [antd/button {:type "link" :size "small"} "复制"]]
                          [antd/popconfirm {:title "确认清理该流程全部历史实例与部署？不可恢复"
                                            :ok-button-props #js {:danger true}
                                            :on-confirm #(rf/dispatch [:bpm/model-clean mid])}
                           [antd/button {:type "link" :size "small" :danger true} "清理"]]])))}])


(defn- designer-toolbar
  [{:keys [modeler-ref on-save on-close on-preview on-export on-import-file on-restart on-align zoom-text]}
   file-ref]
  (let [align-items (fn []
                      (clj->js [{:key "left" :label (r/as-element [:div {:on-click #(on-align "left")} [:span "向左对齐"]])}
                                {:key "right" :label (r/as-element [:div {:on-click #(on-align "right")} [:span "向右对齐"]])}
                                {:key "top" :label (r/as-element [:div {:on-click #(on-align "top")} [:span "向上对齐"]])}
                                {:key "bottom" :label (r/as-element [:div {:on-click #(on-align "bottom")} [:span "向下对齐"]])}
                                {:key "center" :label (r/as-element [:div {:on-click #(on-align "center")} [:span "水平居中"]])}
                                {:key "middle" :label (r/as-element [:div {:on-click #(on-align "middle")} [:span "垂直居中"]])}]))
        export-items (fn []
                       (clj->js [{:key "xml" :label (r/as-element [:div {:on-click #(on-export :xml)} [:span "下载为XML文件"]])}
                                 {:key "svg" :label (r/as-element [:div {:on-click #(on-export :svg)} [:span "下载为SVG文件"]])}
                                 {:key "bpmn" :label (r/as-element [:div {:on-click #(on-export :bpmn)} [:span "下载为BPMN文件"]])}]))
        preview-items (fn []
                        (clj->js [{:key "xml" :label (r/as-element [:div {:on-click #(on-preview :xml)} [:span "预览XML"]])}
                                  {:key "json" :label (r/as-element [:div {:on-click #(on-preview :json)} [:span "预览JSON"]])}]))]
    [:div {:style {:display "flex" :alignItems "center" :flexWrap "wrap" :gap 6 :padding "8px 12px"
                   :borderBottom "1px solid #eee" :background "#fafafa"}}
     ;; 文件控制：打开/下载/预览
     [antd/space {:size 4}
      [antd/button {:size "small" :icon (r/as-element [:> FolderOpenOutlined]) :title "打开文件"
                    :on-click #(when-let [^js f (some-> file-ref .-current)] (.click f))}]
      [antd/dropdown {:menu {:items (export-items)}}
       [antd/button {:size "small" :icon (r/as-element [:> DownloadOutlined]) :title "下载文件"}]]
      [antd/dropdown {:menu {:items (preview-items)}}
       [antd/button {:size "small" :icon (r/as-element [:> EyeOutlined]) :title "预览"}]]]
     [antd/divider {:type "vertical"}]
     ;; 对齐控制
     [antd/space {:size 4}
      [antd/button {:size "small" :icon (r/as-element [:> AlignLeftOutlined]) :title "向左对齐" :on-click #(on-align "left")}]
      [antd/button {:size "small" :icon (r/as-element [:> AlignLeftOutlined]) :title "向右对齐" :on-click #(on-align "right")}]
      [antd/button {:size "small" :icon (r/as-element [:> AlignLeftOutlined]) :title "向上对齐" :on-click #(on-align "top")}]
      [antd/button {:size "small" :icon (r/as-element [:> AlignLeftOutlined]) :title "向下对齐" :on-click #(on-align "bottom")}]
      [antd/button {:size "small" :icon (r/as-element [:> AlignLeftOutlined]) :title "水平居中" :on-click #(on-align "center")}]
      [antd/button {:size "small" :icon (r/as-element [:> AlignLeftOutlined]) :title "垂直居中" :on-click #(on-align "middle")}]]
     [antd/divider {:type "vertical"}]
     ;; 缩放控制
     [antd/space {:size 4}
      [antd/button {:size "small" :icon (r/as-element [:> ZoomOutOutlined]) :title "缩小视图" :on-click #(bpmn/zoom-out! (.-current modeler-ref))}]
      [:span {:style {:fontSize 12 :width 46 :textAlign "center"}} zoom-text]
      [antd/button {:size "small" :icon (r/as-element [:> ZoomInOutlined]) :title "放大视图" :on-click #(bpmn/zoom-in! (.-current modeler-ref))}]
      [antd/button {:size "small" :icon (r/as-element [:> CompressOutlined]) :title "重置视图并居中" :on-click #(bpmn/fit-viewport! (.-current modeler-ref))}]]
     [antd/divider {:type "vertical"}]
     ;; 撤销/恢复/重新绘制
     [antd/space {:size 4}
      [antd/button {:size "small" :icon (r/as-element [:> UndoOutlined]) :title "撤销" :on-click #(bpmn/undo! (.-current modeler-ref))}]
      [antd/button {:size "small" :icon (r/as-element [:> RedoOutlined]) :title "恢复" :on-click #(bpmn/redo! (.-current modeler-ref))}]
      [antd/button {:size "small" :icon (r/as-element [:> ClearOutlined]) :title "重新绘制" :on-click on-restart}]
      [antd/divider {:type "vertical"}]
      [antd/button {:size "small" :on-click on-close} "取消"]
      [antd/button {:size "small" :type "primary" :icon (r/as-element [:> SaveOutlined]) :on-click on-save} "保存流程"]]
     ;; 隐藏文件输入（打开本地文件）
     [:input {:type "file" :ref file-ref :accept ".xml,.bpmn" :style {:display "none"}
              :onChange (fn [e] (let [f (-> e .-target .-files (aget 0))]
                                   (when f (on-import-file f))))}]]))

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

(defn- form-design-tab
  "表单设计 Tab —— 对齐 vben form-design.vue：表单类型(无/动态/自定义) + 表单选择 + 只读预览。"
  [mform-type set-mform-type! mform-id set-mform-id! form-list
   mcustom-create set-mcustom-create! mcustom-view set-mcustom-view!
   mfields-perm set-mfields-perm!]
  (let [sel-form (first (filter #(= (:form_id %) mform-id) form-list))
        schema (or (when-let [j (:form_json sel-form)]
                     (if (string? j)
                       (js->clj (js/JSON.parse j) :keywordize-keys true)
                       (walk/keywordize-keys j)))
                   {:fields []})]
    [:div {:style {:padding 16}}
     [antd/form {:layout "vertical"}
      [antd/form-item {:label "表单类型"}
       [antd/radio-group {:value mform-type
                          :onChange (fn [e] (set-mform-type! (str (-> e .-target .-value))))}
        [antd/radio {:value "0"} "无表单"]
        [antd/radio {:value "1"} "动态表单"]
        [antd/radio {:value "2"} "自定义表单"]]]
      (when (= mform-type "1")
        [antd/form-item {:label "流程表单"}
         [antd/select {:value mform-id :style {:width "100%"} :allowClear true
                       :placeholder "请选择表单" :onChange set-mform-id!}
          (doall (for [f form-list] ^{:key (:form_id f)}
                   [antd/select-option {:value (:form_id f)} (:form_name f)]))]])
      (when (= mform-type "2")
        [:<> 
         [antd/form-item {:label "表单提交路由"}
          [antd/input {:value mcustom-create :placeholder "如 /bpm/oa/leave/create"
                       :onChange (fn [e] (set-mcustom-create! (-> e .-target .-value)))}]]
         [antd/form-item {:label "表单查看地址"}
          [antd/input {:value mcustom-view :placeholder "如 /bpm/oa/leave/detail"
                       :onChange (fn [e] (set-mcustom-view! (-> e .-target .-value)))}]]])
      (when (and (= mform-type "1") mform-id sel-form)
        [:div {:style {:border "1px solid #eee" :borderRadius 6 :padding 16 :marginTop 8}}
         [:div {:style {:display "flex" :alignItems "center" :marginBottom 12}}
          [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
          [:span {:style {:fontWeight 600}} "表单预览"]]
[fr/form-render {:schema schema :disabled? true}]])
      (when (and (= mform-type "1") (seq (:fields schema)))
        [:div {:style {:marginTop 16}}
         [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
          [:div {:style {:width 4 :height 16 :background "#626aef" :marginRight 8}}]
          [:span {:style {:fontWeight 600}} "字段权限"]]
         [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6}}
          (doall
           (for [f (:fields schema)]
             (let [field (:field f)]
               ^{:key (or field (str "f-" (random-uuid)))}
               [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                              :padding "6px 10px" :borderBottom "1px solid #f5f5f5"}}
                [:span (:title f)]
                [antd/select {:style {:width 110} :size "small"
                              :value (or (get mfields-perm field) "edit")
                              :onChange #(set-mfields-perm! (assoc mfields-perm field %))}
                 [antd/select-option {:value "edit"} "可编辑"]
                 [antd/select-option {:value "readonly"} "只读"]
                 [antd/select-option {:value "hidden"} "隐藏"]]])))]])]]))

(defn- process-design-tab
  [{:keys [model-id on-close]}]
  [:div
   [:div {:style {:display "flex" :justifyContent "flex-end" :marginBottom 8}}
    [antd/button {:size "small" :on-click on-close} "关闭"]]
   [bpmfd/bpm-flow-designer {:model-id model-id}]])

(def ^:private webhook-events
  [{:key "process_start" :label "流程发起 (process_start)"}
   {:key "process_end" :label "流程结束 (process_end)"}
   {:key "task_start" :label "任务创建 (task_start)"}
   {:key "task_end" :label "任务完成 (task_end)"}])

(defn- kv-editor
  "key-value 行编辑器（Webhook headers / bodyParams）。"
  [label rows on-change]
  [:div {:style {:marginTop 6}}
   [:div {:style {:fontSize 12 :color "#909399" :marginBottom 4}} label]
   (doall
    (for [[i row] (map-indexed vector (or rows []))]
      ^{:key i}
      [:div {:style {:display "flex" :gap 6 :marginBottom 4}}
       [:input {:style {:flex 1 :padding "4px 8px" :border "1px solid #d9d9d9" :borderRadius 4}
                :placeholder "参数名" :value (:key row)
                :onChange #(on-change (assoc (vec (or rows [])) i
                                              (assoc row :key (-> % .-target .-value))))}]
       [:input {:style {:flex 1 :padding "4px 8px" :border "1px solid #d9d9d9" :borderRadius 4}
                :placeholder "值（支持 ${字段}）" :value (:value row)
                :onChange #(on-change (assoc (vec (or rows [])) i
                                              (assoc row :value (-> % .-target .-value))))}]
       [:a {:style {:color "#f56c6c" :fontSize 12}
            :on-click #(on-change (vec (keep-indexed (fn [j r] (when (not= j i) r)) (or rows []))))}
        "删除"]]))
   [:a {:style {:fontSize 12 :color "#409eff"}
        :on-click #(on-change (conj (vec (or rows [])) {:key "" :value ""}))}
    "＋ 添加一行"]])

(defn- extra-tab
  "更多设置：备注 + Phase 3/4 治理能力（编号规则/自动去重/标题规则/摘要字段/打印模板/Webhook）。"
  [{:keys [mwebhooks set-mwebhooks! mremark set-mremark! mauto-type set-mauto-type! mname-rule set-mname-rule!
           mprocess-rule set-mprocess-rule! msummary-fields set-msummary-fields!
           mprint-enable set-mprint-enable! mprint-html set-mprint-html!
           form-fields]}]
  (let [rule-enabled? (boolean (:enable mprocess-rule))
        upd-rule! (fn [k v] (set-mprocess-rule! (assoc mprocess-rule k v)))
        section (fn [title] [:div {:style {:display "flex" :alignItems "center" :margin "16px 0 8px"}}
                             [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
                             [:span {:style {:fontWeight 600}} title]])]
    [:div {:style {:padding 16 :maxWidth 640}}
     [antd/form {:layout "vertical"}
      [antd/form-item {:label "备注"}
       [antd/text-area {:value mremark :rows 3 :onChange (fn [e] (set-mremark! (-> e .-target .-value)))}]]
      (section "流程编号规则")
      [:div {:style {:marginBottom 12}}
       [antd/space {:align "center"}
        [antd/switch {:checked rule-enabled? :onChange #(upd-rule! :enable (boolean %))}]
        [:span {:style {:color "#606266"}} "启用流程单号（前缀+日期中缀+当日递增流水号）"]]]
      (when rule-enabled?
        [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 12}}
         [antd/form-item {:label "前缀"}
          [antd/input {:value (or (:prefix mprocess-rule) "") :placeholder "如 CG-"
                       :onChange #(upd-rule! :prefix (-> % .-target .-value))}]]
         [antd/form-item {:label "后缀"}
          [antd/input {:value (or (:suffix mprocess-rule) "") :placeholder "如 -"
                       :onChange #(upd-rule! :suffix (-> % .-target .-value))}]]
         [antd/form-item {:label "日期中缀"}
          [antd/select {:value (or (:infix mprocess-rule) "DAY") :style {:width "100%"}
                        :onChange #(upd-rule! :infix %)}
           [antd/select-option {:value "NONE"} "无"]
           [antd/select-option {:value "DAY"} "日(yyyyMMdd)"]
           [antd/select-option {:value "HOUR"} "时(yyyyMMddHH)"]
           [antd/select-option {:value "MINUTE"} "分(yyyyMMddHHmm)"]
           [antd/select-option {:value "SECOND"} "秒(yyyyMMddHHmmss)"]]]
         [antd/form-item {:label "流水号长度(≥5)"}
          [antd/input-number {:value (or (:length mprocess-rule) 5) :min 5 :max 10
                              :style {:width "100%"}
                              :onChange #(upd-rule! :length (js/Number %))}]]])
      (section "自动去重")
      [antd/form-item {:label "同一审批人重复出现时"}
       [antd/select {:value (or mauto-type "NONE") :style {:width "100%"}
                     :onChange set-mauto-type!}
        [antd/select-option {:value "NONE"} "不处理"]
        [antd/select-option {:value "APPROVE_ONCE"} "只审一次（后续节点自动通过）"]
        [antd/select-option {:value "CONSECUTIVE"} "连续重复节点自动通过"]]]
      (section "自定义标题")
      [antd/form-item {:label "标题模板（支持 {字段id}、{发起人}、{发起时间}、{流程名称}）"}
       [antd/input {:value (or mname-rule "") :placeholder "如 {发起人}的{days}天请假申请"
                    :onChange #(set-mname-rule! (-> % .-target .-value))}]]
      (section "摘要字段")
      [antd/form-item {:label "实例/待办/抄送列表展示的摘要（需绑定动态表单）"}
       [antd/select {:value (clj->js (or msummary-fields [])) :mode "multiple"
                     :style {:width "100%"} :placeholder "选择表单字段"
                     :onChange #(set-msummary-fields! (vec (js->clj %)))}
        (doall (for [f form-fields]
                 ^{:key (:field f)}
                 [antd/select-option {:value (:field f)} (:title f)]))]]
      (section "流程 Webhook（HTTP 回调）")
      (doall
       (for [{:keys [key label]} webhook-events]
         (let [hook (get mwebhooks (keyword key) {})
               enabled? (boolean (:enable hook))
               upd! (fn [k v] (set-mwebhooks! (assoc mwebhooks (keyword key) (assoc hook k v))))]
           ^{:key key}
           [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6 :padding 10 :marginBottom 8}}
            [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
             [antd/switch {:size "small" :checked enabled?
                           :onChange #(upd! :enable (boolean %))}]
             [:span {:style {:fontSize 13 :fontWeight 500}} label]]
            (when enabled?
              [:div {:style {:marginTop 8}}
               [:input {:style {:width "100%" :padding "5px 8px" :border "1px solid #d9d9d9" :borderRadius 4}
                        :placeholder "回调 URL（POST）" :value (or (:url hook) "")
                        :onChange #(upd! :url (-> % .-target .-value))}]
               [kv-editor "Headers" (:headers hook) #(upd! :headers %)]
               [kv-editor "Body 参数" (:bodyParams hook) #(upd! :bodyParams %)]])])))
      (section "打印模板")
      [:div {:style {:marginBottom 12}}
       [antd/space {:align "center"}
        [antd/switch {:checked mprint-enable :onChange #(set-mprint-enable! (boolean %))}]
        [:span {:style {:color "#606266"}} "启用打印模板（占位符：{{字段}}、{{流程记录}}）"]]]
      (when mprint-enable
        [antd/form-item {:label "打印 HTML 模板"}
         [antd/text-area {:value (or mprint-html "") :rows 8
                          :placeholder "<h2>{{reason}} 审批单</h2>..."
                          :onChange #(set-mprint-html! (-> % .-target .-value))}]])]]))

(defn- designer-modal []
  (let [visible? @(rf/subscribe [:bpm-designer/visible?])
        current @(rf/subscribe [:bpm-designer/current])
        xml @(rf/subscribe [:bpm-designer/bpmn-xml])
        loading? @(rf/subscribe [:bpm-designer/loading?])
        modeler-ref (hooks/use-ref nil)
        file-ref (hooks/use-ref nil)
        [tab set-tab!] (hooks/use-state "basic")
        [selected set-selected!] (hooks/use-state nil)
        [sp set-sp!] (hooks/use-state nil)
        [nname set-nname!] (hooks/use-state "")
        [cand set-cand!] (hooks/use-state "")
        [cond set-cond!] (hooks/use-state "")
        [preview-open set-preview-open!] (hooks/use-state false)
        [preview-content set-preview-content!] (hooks/use-state "")
        [preview-type set-preview-type!] (hooks/use-state :xml)
        [zoom-text set-zoom-text!] (hooks/use-state "100%")
        [mname set-mname!] (hooks/use-state "")
        [mkey set-mkey!] (hooks/use-state "")
        [mcat set-mcat!] (hooks/use-state "")
        [mform-type set-mform-type!] (hooks/use-state "0")
        [mform-id set-mform-id!] (hooks/use-state nil)
        [mform-json set-mform-json!] (hooks/use-state "")
        [mcustom-create set-mcustom-create!] (hooks/use-state "")
        [mcustom-view set-mcustom-view!] (hooks/use-state "")
        [form-list set-form-list!] (hooks/use-state [])
        [mremark set-mremark!] (hooks/use-state "")
        [mfields-perm set-mfields-perm!] (hooks/use-state {})
        ;; Phase 3 治理能力设置
        [mauto-type set-mauto-type!] (hooks/use-state "NONE")
        [mname-rule set-mname-rule!] (hooks/use-state "")
        [mprocess-rule set-mprocess-rule!] (hooks/use-state {:enable false :prefix "" :infix "DAY" :suffix "" :length 5})
        [msummary-fields set-msummary-fields!] (hooks/use-state [])
        [mprint-enable set-mprint-enable!] (hooks/use-state false)
        [mprint-html set-mprint-html!] (hooks/use-state "")
        [mwebhooks set-mwebhooks!] (hooks/use-state {})
        form-fields (let [sel-form (first (filter #(= (:form_id %) mform-id) form-list))
                          schema (or (when-let [j (:form_json sel-form)]
                                       (if (string? j)
                                         (js->clj (js/JSON.parse j) :keywordize-keys true)
                                         (walk/keywordize-keys j)))
                                     {:fields []})]
                      (filter :field (:fields schema)))
        tabs [{:key "basic" :label "基本信息"} {:key "form" :label "表单设计"}
              {:key "process" :label "流程设计"} {:key "extra" :label "更多设置"}]
        save-model (fn []
                     (rf/dispatch [:bpm/designer-save
                                   {:model_name mname :model_key mkey
                                    :category_id (some-> mcat js/Number) :form_type mform-type
                                    :form_id (some-> mform-id js/Number)
                                    :form_custom_create_path mcustom-create
                                    :form_custom_view_path mcustom-view
                                    :form_json mform-json :remark mremark
                                    :fields_permission (js/JSON.stringify (clj->js mfields-perm))
                                    :auto_approval_type mauto-type
                                    :name_rule mname-rule
                                    :process_id_rule (js/JSON.stringify (clj->js mprocess-rule))
                                    :summary_fields (js/JSON.stringify (clj->js msummary-fields))
                                    :print_template_enable (if mprint-enable "1" "0")
                                    :print_template_html mprint-html
                                    :webhooks (js/JSON.stringify (clj->js mwebhooks))}]))
        ;; 连线加号浮层菜单回调：直接插入节点并重建浮层（对齐 vben，无需居中 Modal）
        add-handle (atom nil)
        _ (reset! add-handle
                  (fn [conn type name]
                    (bpmn/insert-node! (.-current modeler-ref) conn type name
                                       (fn [e] (antd/error! e))
                                       #(do (bpmn/add-plus-overlays! (.-current modeler-ref) @add-handle)
                                            (bpmn/style-nodes! (.-current modeler-ref))))
                    (antd/success! (str "已添加" name))))
        ;; 导入/重绘/插入后：重建浮层并重新着色
        after-import (fn []
                       (bpmn/add-plus-overlays! (.-current modeler-ref) @add-handle)
                       (bpmn/style-nodes! (.-current modeler-ref)))
        handle-preview (fn [ptype]
                         (set-preview-type! ptype)
                         (bpmn/save-bpmn! (.-current modeler-ref)
                                          (fn [xml]
                                            (set-preview-content!
                                             (if (= ptype :json)
                                               (let [blocks (map second (re-seq #"<(startEvent|endEvent|userTask|exclusiveGateway|parallelGateway|inclusiveGateway|serviceTask|subProcess|callActivity)[^>]*id=\"([a-zA-Z0-9_]+)\"" xml))
                                                     flows (map second (re-seq #"<sequenceFlow[^>]*id=\"([a-zA-Z0-9_]+)\"" xml))
                                                     grouped (-> {:nodes blocks :flows flows}
                                                                 (update :nodes (fn [v] (remove nil? v)))
                                                                 (update :flows (fn [v] (remove nil? v))))]
                                                 (js/JSON.stringify (clj->js grouped) nil 2))
                                               xml))
                                            (set-preview-open! true))
                                          (fn [e] (antd/error! e))))
        handle-align (fn [align]
                       (if (bpmn/align-elements! (.-current modeler-ref) align)
                         (antd/success! "已对齐")
                         (antd/warning! "请按住 Shift 键选择多个元素对齐")))
        handle-export (fn [type]
                        (bpmn/export-bpmn! (.-current modeler-ref) type
                                           (fn [e] (antd/error! e))))
        handle-import-file (fn [file]
                             (bpmn/import-local-file! (.-current modeler-ref) file
                                                      #(after-import)
                                                      (fn [e] (antd/error! e))))
        handle-restart (fn []
                         (bpmn/new-diagram! (.-current modeler-ref)
                                            #(after-import)
                                            (fn [e] (antd/error! e))))]
    (hooks/use-effect
     (fn []
       (when (and current (seq (:model_name current)))
         (set-mname! (or (:model_name current) ""))
         (set-mkey! (or (:model_key current) ""))
         (set-mcat! (or (:category_id current) ""))
         (set-mform-type! (or (:form_type current) "0"))
         (set-mform-id! (or (:form_id current) nil))
         (set-mform-json! (or (:form_json current) ""))
         (set-mcustom-create! (or (:form_custom_create_path current) ""))
         (set-mcustom-view! (or (:form_custom_view_path current) ""))
         (set-mremark! (or (:remark current) ""))
         (set-mfields-perm!
          (or (when-let [fp (:fields_permission current)]
                (if (string? fp) (js->clj (js/JSON.parse fp) :keywordize-keys true)
                    (walk/keywordize-keys fp)))
              {}))
         (set-mauto-type! (or (:auto_approval_type current) "NONE"))
         (set-mname-rule! (or (:name_rule current) ""))
         (set-mprocess-rule!
          (or (when-let [pr (:process_id_rule current)]
                (if (string? pr) (js->clj (js/JSON.parse pr) :keywordize-keys true)
                    (walk/keywordize-keys pr)))
              {:enable false :prefix "" :infix "DAY" :suffix "" :length 5}))
         (set-msummary-fields!
          (or (when-let [sf (:summary_fields current)]
                (if (string? sf) (js->clj (js/JSON.parse sf)) (walk/keywordize-keys sf)))
              []))
         (set-mprint-enable! (= "1" (str (:print_template_enable current))))
         (set-mprint-html! (or (:print_template_html current) ""))
         (set-mwebhooks! (or (when-let [w (:webhooks current)]
                               (if (string? w)
                                 (js->clj (js/JSON.parse w) :keywordize-keys true)
                                 (walk/keywordize-keys w)))
                             {}))))
     [visible?])
    (hooks/use-effect
     (fn []
       (when visible?
         (api/bpmmgmt-list "form" {:page 1 :size 1000}
                           #(set-form-list! (walk/keywordize-keys (get-in % [:data :rows])))
                           #())))
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
    [:div
     [antd/modal {:title (str "流程模型 · " (:model_name current))
                  :open visible? :width 1300 :destroyOnHidden true :footer nil
                  :onCancel #(rf/dispatch [:bpm/designer-close])}
     (if loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div
        [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                       :padding "0 12px" :borderBottom "1px solid #eee"}}
         [:div {:style {:display "flex" :gap 4}}
          (doall
           (for [{:keys [key label]} tabs]
             ^{:key key}
             [:div {:style {:padding "10px 16px" :cursor "pointer" :fontSize 14
                            :borderBottom (if (= key tab) "2px solid #409eff" "2px solid transparent")
                            :color (if (= key tab) "#409eff" "#666")}
                    :on-click #(set-tab! key)}
              label]))]
         [antd/button {:type "primary" :size "small" :on-click save-model} "保存"]]
        (case tab
          "basic" [basic-info-tab mname set-mname! mkey set-mkey! mcat set-mcat! mform-type set-mform-type!]
          "form" [form-design-tab mform-type set-mform-type! mform-id set-mform-id! form-list
                       mcustom-create set-mcustom-create! mcustom-view set-mcustom-view!
                       mfields-perm set-mfields-perm!]
          "process" [process-design-tab {:model-id (:model_id current)
                                         :on-close #(rf/dispatch [:bpm/designer-close])}]
          "extra" [extra-tab {:mwebhooks mwebhooks :set-mwebhooks! set-mwebhooks!
                              :mremark mremark :set-mremark! set-mremark!
                              :mauto-type mauto-type :set-mauto-type! set-mauto-type!
                              :mname-rule mname-rule :set-mname-rule! set-mname-rule!
                              :mprocess-rule mprocess-rule :set-mprocess-rule! set-mprocess-rule!
                              :msummary-fields msummary-fields :set-msummary-fields! set-msummary-fields!
                              :mprint-enable mprint-enable :set-mprint-enable! set-mprint-enable!
                              :mprint-html mprint-html :set-mprint-html! set-mprint-html!
                              :form-fields form-fields}])])]
     ;; 预览弹窗（对齐 vben 预览 XML/JSON）
     [antd/modal {:title (if (= preview-type :json) "预览JSON" "预览XML")
                  :open preview-open :width 900 :destroyOnHidden true :footer nil
                  :onCancel #(set-preview-open! false)}
      [:pre {:style {:background "#f6f8fa" :padding 12 :borderRadius 4 :maxHeight 520
                     :overflow "auto" :fontSize 12 :lineHeight "1.6"}}
       [:code {:style {:fontFamily "monospace" :whiteSpace "pre-wrap" :wordBreak "break-all"}}
        preview-content]]]]))

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