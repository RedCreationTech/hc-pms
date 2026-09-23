(ns com.ruoyi.frontend.pages.pms.widgets
  "工作台通用业务表单与可追踪记录组件."
  (:require [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [reagent.core :as r]))

(def labels
  {"draft" "草稿" "submitted" "待审批" "approved" "已批准" "rejected" "已驳回"
   "in_review" "待评审" "completed" "已完成" "materialized" "已转问题" "mitigated" "已缓解"
   "todo" "未开始" "converted" "已转任务" "ready" "待提交" "open" "待处理" "in_progress" "处理中" "blocked" "受阻" "done" "已完成"
   "closed" "已关闭" "resolved" "待验证" "waived" "已豁免" "active" "有效" "discarded" "已作废"
   "required" "必需" "desired" "期望" "blocker" "阻断" "major" "严重" "minor" "一般"
   "summary" "汇总" "task" "任务" "milestone" "里程碑" "person" "人员" "equipment" "设备"})

(defn badge
  "统一呈现业务记录状态."
  [status]
  [antd/tag {:color (cond (contains? #{"approved" "done" "closed" "active"} status) "green"
                          (contains? #{"rejected" "blocked" "blocker" "discarded"} status) "red"
                          (contains? #{"submitted" "in_progress" "resolved"} status) "blue"
                          :else "default")}
   (get labels status (shared/display-value status))])

(defn options
  "把真实业务记录转换为关联选择项."
  [rows id-key label-key]
  (mapv #(hash-map :value (get % id-key) :label (str (or (get % label-key) (get % id-key)))) rows))

(defn choices
  "为有限状态生成专业可读选项."
  [values]
  (mapv #(hash-map :value % :label (get labels % %)) values))

(defn user-options
  "组织用户选择器的可读名称."
  [users]
  (mapv #(hash-map :value (:user_id %) :label (str (or (:nick_name %) (:user_name %)) " / " (:user_name %))) users))

(defn form-field
  "使用类型明确的业务输入组件."
  [{:keys [key label type options required? hint min max]}]
  [antd/form-item {:name (name key) :label label :extra hint
                   :rules (when required? [{:required true :message (str "请填写" label)}])}
   (case type
     :textarea [antd/text-area {:rows 3 :maxLength (or max 4000) :placeholder (str "填写" label)}]
     :select [antd/select {:options options :showSearch true :optionFilterProp "label"
                           :allowClear (not required?) :placeholder (str "选择" label)}]
     :multi [antd/select {:mode "multiple" :options options :optionFilterProp "label"
                          :placeholder (str "选择" label)}]
     :number [antd/input-number {:min (or min 0) :max max :style {:width "100%"}}]
     :date [antd/input {:type "date"}]
     [antd/input {:maxLength (or max 200) :placeholder (str "填写" label)}])])

(defn mutation-dialog
  "提交业务字段和项目版本,成功后刷新所有关联视图."
  [{:keys [title fields initial path method project on-close on-saved transform description]
    :or {method :post}}]
  (let [[form] (antd/form-use-form)
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title title :open true :onCancel on-close :onOk #(.submit form)
                 :okText "保存" :cancelText "返回" :confirmLoading busy? :destroyOnHidden true
                 :style {:maxWidth "calc(100vw - 32px)"} :width 640}
     (when description [:p {:style {:color "#718096" :lineHeight 1.8}} description])
     (when error [shared/error-panel error nil])
     [antd/form {:form form :layout "vertical" :initialValues initial :disabled busy?
                 :onFinish (fn [values]
                             (let [data (js->clj values :keywordize-keys true)]
                               (run! method path (assoc (if transform (transform data) data)
                                                        :version (:version project)) (str title "成功"))))}
      (for [field fields] ^{:key (:key field)} [form-field field])]]))

(defn record-table
  "显示服务端记录,窄屏支持横向滚动."
  [rows columns actions]
  [antd/table {:rowKey (fn [^js row index] (or (.-id row) (.-baseline_id row) (.-dependency_id row)
                                             (.-allocation_id row) (.-feedback_id row)
                                             (when (.-date row) (str (.-resource_id row) ":" (.-date row)))
                                             (.-task_id row) (.-resource_id row) (str "row-" index)))
               :size "small" :dataSource (clj->js (or rows [])) :pagination false :scroll {:x 720}
               :locale {:emptyText "暂无记录"}
               :columns (clj->js
                          (cond-> (vec columns)
                            actions (conj {:title "操作" :key "actions" :width 200
                                           :render (fn [_ row] (r/as-element (actions (js->clj row :keywordize-keys true))))})))}])

(defn text-column
  "普通业务字段列."
  [key title]
  {:title title :dataIndex (name key) :ellipsis true :render shared/display-value})

(defn state-column
  "带语义颜色的状态列."
  []
  {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [badge %])})

(defn edit-button
  "用于受权限控制的行内操作."
  [label on-click]
  [antd/button {:type "link" :size "small" :on-click on-click} label])

(defn refresh-boundary
  "刷新期间保留组件和页签状态,禁用依赖旧数据的操作."
  [loading? content]
  [:fieldset {:disabled (boolean loading?) :aria-busy (boolean loading?)
              :style {:border 0 :padding 0 :margin 0 :minWidth 0
                      :opacity (if loading? 0.65 1)
                      :pointerEvents (when loading? "none")}}
   content])

(defn resource-view
  "初次加载后保留数据,刷新完成前不允许操作旧模型."
  [{:keys [data loading? error refresh!]} render-data]
  (cond error [shared/error-panel error refresh!]
        (and loading? (nil? data)) [:div {:style {:padding 48 :textAlign "center"}} [antd/spin]]
        :else [refresh-boundary loading? (render-data data)]))

(defn section
  "工作台内部业务区块."
  [title subtitle action body]
  [shared/panel title subtitle action body])

(defn related-label
  "用真实关联记录解释业务标识."
  [rows id-key label-key id]
  (or (some #(when (= id (get % id-key)) (get % label-key)) rows) (shared/display-value id)))
