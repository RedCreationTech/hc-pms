(ns com.ruoyi.frontend.pages.pms.delivery-forms
  "交付执行的追踪表单和逐行物料,齐套与试验结果录入."
  (:require ["antd" :refer [Form]]
            [clojure.string :as str]
            [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.governance-forms :as governance]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]))

(def form-list "支持增删业务明细行的Ant Design表单列表." (r/adapt-react-class (.-List Form)))

(defn choice-field
  "构造绑定真实记录的单选或多选字段."
  [key label rows id-key label-key multiple?]
  {:key key :label label :type (if multiple? :multi :select) :required? true
   :options (w/options rows id-key label-key)})

(defn identity-fields
  "交付记录编号与业务标题."
  []
  [{:key :code :label "业务编号" :required? true} {:key :title :label "标题" :required? true}])

(defn trace-fields
  "每份交付对象绑定真实WBS与明确URS版本."
  [planning governance]
  [(choice-field :task_id "关联WBS任务" (:tasks planning) :task_id :name false)
   {:key :requirement_ids :label "关联URS版本" :type :multi :required? true
    :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / V" (:revision %) " / " (:text %)))
                   (:requirements governance))}])

(defn material-dialog
  "录入备料申请及逐行实物数量,不暗示已采购."
  [{:keys [base options planning governance]}]
  {:title "新建备料申请" :path (str base "/material-requests")
   :initial {:request_type "standard" :items [{:code "" :name "" :quantity 1 :unit "件"}]}
   :fields (vec (concat (identity-fields)
                       [{:key :request_type :label "申请类型" :type :select :required? true
                         :options [{:value "standard" :label "标准备料"} {:value "long_lead" :label "长周期物料"}
                                   {:value "raw_material" :label "原材料预投"} {:value "direct_ship" :label "直发物料"}
                                   {:value "packaging" :label "包材申请"}]}
                        {:key :packaging_spec :label "包装规格 (包材申请必填)" :type :textarea :hint "包材申请须填写包装规格/熏蒸/尺寸等要求."}
                        {:key :node_id :label "目标子项目/单机" :type :select
                         :options (mapv #(hash-map :value (:node_id %) :label (str (:node_code %) " · " (:name %))) (:nodes planning))}
                        (governance/owner-field (:users options)) {:key :needed_on :label "需求日期" :type :date :required? true}]
                       (trace-fields planning governance)))
   :transform (fn [data] (cond-> data (str/blank? (:packaging_spec data)) (dissoc :packaging_spec)
                                      (str/blank? (:node_id data)) (dissoc :node_id)))
   :rows-key :items :rows-title "物料明细" :editable-rows? true :new-row {:quantity 1 :unit "件"}
   :rows-fields [{:key :code :label "物料编号" :required? true} {:key :name :label "物料名称" :required? true}
                 {:key :quantity :label "数量" :type :number :min 1 :required? true} {:key :unit :label "计量单位" :required? true}]})

(defn bom-dialog
  "仅从已批准申请复制冻结BOM来源."
  [{:keys [base model]}]
  {:title "建立BOM" :path (str base "/boms")
   :description "物料行和追踪版本从已批准备料申请复制,后续齐套登记不会改写冻结数量."
   :fields (conj (identity-fields)
                 (choice-field :material_request_id "已批准备料申请" (filter #(= "approved" (:status %)) (:material_requests model)) :id :title false))})

(defn assembly-dialog
  "选择实际齐套的BOM建立装配记录."
  [{:keys [base model options planning governance]}]
  {:title "建立装配任务" :path (str base "/assemblies")
   :fields (vec (concat (identity-fields)
                       [(choice-field :bom_id "已齐套BOM" (filter #(= "ready" (:status %)) (:boms model)) :id :title false)
                        (governance/owner-field (:users options))] (trace-fields planning governance)))})

(defn test-dialog
  "定义试验类别及逐项可验证验收准则."
  [{:keys [base model options planning governance]}]
  {:title "建立质量试验" :path (str base "/tests")
   :initial {:test_type "SIT" :criteria [{:code "C01" :title "" :required true}]}
   :fields (vec (concat (identity-fields)
                       [(choice-field :assembly_id "关联装配" (:assemblies model) :id :title false)
                        {:key :test_type :label "试验类别" :type :select :required? true :options (w/choices ["SIT" "FAT" "SAT"])}
                        (governance/owner-field (:users options))] (trace-fields planning governance)))
   :rows-key :criteria :rows-title "验收检查项" :editable-rows? true :new-row {:required true}
   :rows-fields [{:key :code :label "检查编号" :required? true} {:key :title :label "检查标准" :required? true}
                 {:key :required :label "必需项" :type :boolean}]})

(defn shipment-dialog
  "发运计划记录实际收货信息及独立放行签收责任人."
  [{:keys [base model options planning governance]}]
  {:title "建立发运计划" :path (str base "/shipments")
   :fields (vec (concat (identity-fields)
                       [(choice-field :assembly_ids "发运装配批次" (filter #(= "approved" (:status %)) (:assemblies model)) :id :title true)
                        {:key :consignee :label "收货方" :required? true} {:key :delivery_address :label "交付地址" :type :textarea :required? true}
                        {:key :planned_date :label "计划发运日期" :type :date :required? true}
                        (governance/reviewer-field options)] (trace-fields planning governance)))})

(defn service-dialog
  "为真实发运单登记售后异常并保留追踪."
  [{:keys [base model options planning governance]}]
  {:title "登记售后异常" :path (str base "/service-cases")
   :fields (vec (concat (identity-fields)
                       [(choice-field :shipment_id "实际发运单" (filter #(contains? #{"shipped" "received" "conditional" "returned"} (:status %)) (:shipments model)) :id :title false)
                        (governance/owner-field (:users options)) {:key :due_date :label "处理期限" :type :date :required? true}]
                       (trace-fields planning governance)))})

(defn submit-dialog
  "提交冻结证据并选择独立审核人."
  [{:keys [base options governance]} collection row action reviewer? title]
  {:title title :path (str base "/" collection "/" (:id row) "/" action)
   :fields (cond-> [(governance/evidence-field (:documents governance))]
             reviewer? (conj (governance/reviewer-field options)))})

(defn decision-dialog
  "独立批准或退回已冻结的交付记录."
  [{:keys [base]} collection row decision]
  {:title (if (= decision "approved") "批准交付评审" "驳回交付评审")
   :path (str base "/" collection "/" (:id row) "/decision")
   :transform #(assoc % :decision decision)
   :fields [{:key :reason :label "审核结论与理由" :type :textarea :required? true}]})

(defn kit-dialog
  "按冻结BOM逐项登记实际可用数量,不混加计量单位."
  [{:keys [base governance]} bom]
  {:title "登记实际齐套" :path (str base "/boms/" (:id bom) "/kit")
   :description "逐项填写实际可用数量,超过冻结数量将被拒绝. 部分齐套不能开工."
   :fields [(governance/evidence-field (:documents governance))]
   :initial {:items (let [available (into {} (map (juxt :code :available_quantity) (:availability bom)))]
                      (mapv #(hash-map :code (:code %) :available_quantity (get available (:code %) 0)) (:items bom)))}
   :row-labels (mapv #(str (:name %) ",冻结数量 " (:quantity %) " " (:unit %)) (:items bom))
   :rows-key :items :rows-title "冻结BOM逐项齐套"
   :rows-fields [{:key :code :label "物料编号" :required? true :disabled? true}
                 {:key :available_quantity :label "实际可用数量" :type :number :min 0 :required? true}]})

(defn results-dialog
  "每个试验检查项填写真实结果及对应证据."
  [{:keys [base governance]} test]
  (let [prior (into {} (map (juxt :code identity) (:checks test)))]
    {:title "登记试验结果" :path (str base "/tests/" (:id test) "/results")
     :description "必需项失败会建立阻断整改问题. 复验保留之前的问题关联,关闭问题后才可提交批准."
     :fields [{:key :due_date :label "失败整改期限" :type :date :required? true}]
     :initial {:checks (mapv #(merge {:code (:code %) :passed false :actual "" :evidence_ids []}
                                    (select-keys (prior (:code %)) [:code :passed :actual :evidence_ids])) (:criteria test))}
     :rows-key :checks :rows-title "逐项实际结果"
     :row-labels (mapv #(str (:title %) (when (:required %) " (必需项)")) (:criteria test))
     :rows-fields [{:key :code :label "检查编号" :disabled? true :required? true}
                   {:key :passed :label "已通过" :type :boolean}
                   {:key :actual :label "实际结果" :type :textarea :required? true}
                   (governance/evidence-field (:documents governance))]}))

(defn dispatch-dialog
  "保留实际发运日期,物流编号与发运证据."
  [{:keys [base governance]} shipment]
  {:title "登记实际发运" :path (str base "/shipments/" (:id shipment) "/dispatch")
   :fields [{:key :shipped_on :label "实际发运日期" :type :date :required? true}
            {:key :tracking_no :label "物流或运单编号" :required? true}
            (governance/evidence-field (:documents governance))]})

(defn receipt-dialog
  "指定独立人员验证真实签收证据并登记必要异常."
  [{:keys [base options governance]} shipment]
  {:title "验证客户签收" :path (str base "/shipments/" (:id shipment) "/receipt")
   :description "条件接收或拒收必须填写异常原因,处理负责人及期限. 旧异常关闭后才能确认完整接受."
   :initial {:acceptance "accepted"}
   :fields [{:key :received_on :label "实际签收日期" :type :date :required? true}
            {:key :receiver_name :label "实际收货人" :required? true}
            {:key :acceptance :label "签收结果" :type :select :required? true
             :options [{:value "accepted" :label "完整接受"} {:value "conditional" :label "条件接收"} {:value "rejected" :label "拒收"}]}
            (governance/evidence-field (:documents governance))
            {:key :exception_reason :label "异常原因" :type :textarea}
            (assoc (governance/owner-field (:users options)) :required? false)
            {:key :due_date :label "异常处理期限" :type :date}]})

(defn resolve-dialog
  "售后解决证据进入独立验证后才能关闭."
  [{:keys [base options governance]} record]
  {:title "提交售后解决验证" :path (str base "/service-cases/" (:id record) "/resolve")
   :fields [{:key :resolution :label "解决方案与验证结果" :type :textarea :required? true}
            (governance/evidence-field (:documents governance)) (governance/reviewer-field options)]})

(defn configuration-dialog
  "在执行前明确本项目适用交付环节, 试验, 工勘次数, 发货前条件与交底/现场期限."
  [{:keys [base model]}]
  {:title "配置交付验收要求" :path (str base "/configuration")
   :initial (select-keys (:configuration model) [:required_stages :required_test_types :required_survey_visits :pre_ship_conditions
                                                 :handover_deadline_days :site_lag_days :handover_required])
   :fields [{:key :required_stages :label "必需交付环节" :type :multi :required? true
             :options [{:value "materials" :label "备料与BOM"} {:value "assembly" :label "装配交检"}
                       {:value "quality" :label "质量试验"} {:value "shipment" :label "发运签收"}]}
            {:key :required_test_types :label "必需试验类别" :type :multi :required? true :options (w/choices ["SIT" "FAT" "SAT"])}
            {:key :required_survey_visits :label "必需工勘次数" :type :number :min 0 :max 10 :hint "按项目适用性配置, 不默认强制三次; 0 表示不要求."}
            {:key :pre_ship_conditions :label "发货前本地条件" :type :multi
             :options [{:value "warehouse_in" :label "入库/装箱已确认"} {:value "payment" :label "提货款条件已确认"}]}
            {:key :handover_deadline_days :label "发货后交底截止 (自然日)" :type :number :min 0 :max 30}
            {:key :handover_required :label "交底未完成是否阻塞收尾" :type :select
             :options [{:value true :label "阻塞收尾"} {:value false :label "仅提示"}]}
            {:key :site_lag_days :label "交底后现场任务滞后 (自然日)" :type :number :min 0 :max 30}
            {:key :reason :label "配置依据" :type :textarea :required? true}]})

(defn survey-dialog
  "按适用性登记一次工勘."
  [{:keys [base options planning]}]
  {:title "登记工勘任务" :path (str base "/surveys") :initial {:visit_no 1}
   :fields (vec (concat (identity-fields)
                       [{:key :visit_no :label "工勘次序" :type :number :min 1 :max 20 :required? true}
                        (governance/owner-field (:users options)) {:key :planned_date :label "计划日期" :type :date :required? true}
                        {:key :deliverable :label "交付物要求" :type :textarea :required? true}
                        {:key :task_id :label "关联WBS任务" :type :select :options (w/options (:tasks planning) :task_id :name)}]))
   :transform (fn [data] (cond-> data (str/blank? (:task_id data)) (dissoc :task_id)))})

(defn survey-submit-dialog
  "以实际日期与交付证据提交工勘确认."
  [{:keys [base options governance]} row]
  {:title "提交工勘确认" :path (str base "/surveys/" (:id row) "/submit")
   :fields [{:key :actual_date :label "实际工勘日期" :type :date :required? true}
            {:key :findings :label "勘察结论" :type :textarea}
            (governance/evidence-field (:documents governance)) (governance/reviewer-field options)]})

(def step-order
  ["on_island" "assembling" "unit_inspection" "wiring_inspection" "off_island" "handover"])

(def step-labels
  {"on_island" "上岛" "assembling" "装配" "unit_inspection" "单机交检" "wiring_inspection" "连线交检" "off_island" "下岛" "handover" "交接"})

(defn step-dialog
  "登记装配执行明细的下一步骤."
  [{:keys [base governance]} row]
  {:title "登记装配步骤" :path (str base "/assemblies/" (:id row) "/steps")
   :initial {:step (:next_step row)}
   :fields [{:key :step :label "步骤" :type :select :required? true
             :options (mapv (fn [[v l]] {:value v :label l}) step-labels)}
            {:key :actual_date :label "实际日期" :type :date :required? true}
            {:key :note :label "说明"}
            (assoc (governance/evidence-field (:documents governance)) :required? false)]})

(defn conditions-dialog
  "登记发货前本地事实 (入库/提货款), 不冒充外部回执."
  [{:keys [base governance]} row]
  {:title "确认发货前条件" :path (str base "/shipments/" (:id row) "/conditions")
   :initial (select-keys (:preconditions row) [:warehouse_in_confirmed :warehouse_note :payment_confirmed :payment_note])
   :fields [{:key :warehouse_in_confirmed :label "入库/装箱已确认" :type :select
             :options [{:value true :label "已确认"} {:value false :label "未确认"}]}
            {:key :warehouse_note :label "入库依据 (如 WMS 单号)"}
            {:key :payment_confirmed :label "提货款条件已确认" :type :select
             :options [{:value true :label "已确认"} {:value false :label "未确认"}]}
            {:key :payment_note :label "提货款依据 (如财务确认)"}
            (assoc (governance/evidence-field (:documents governance)) :required? false)]})

(defn handover-dialog
  "在期限内完成交底资料签交."
  [{:keys [base governance]} row]
  {:title "完成项目交底" :path (str base "/handovers/" (:id row) "/complete")
   :fields [{:key :completed_on :label "交底完成日期" :type :date :required? true}
            {:key :checklist_note :label "检查清单版本说明"}
            (assoc (governance/evidence-field (:documents governance)) :key :document_ids :label "交底文件清单 (文档版本)")]})

(defn site-start-dialog
  [{:keys [base]} row]
  {:title "开始现场任务" :path (str base "/site-tasks/" (:id row) "/start")
   :fields [{:key :actual_start :label "实际开始日期" :type :date :required? true} {:key :note :label "说明"}]})

(defn site-complete-dialog
  [{:keys [base governance]} row]
  {:title "完成现场任务" :path (str base "/site-tasks/" (:id row) "/complete")
   :fields [{:key :actual_end :label "实际完成日期" :type :date :required? true}
            {:key :result :label "现场结果" :type :textarea}
            (governance/evidence-field (:documents governance))]})

(defn- nested-field
  "在业务明细行中渲染具名字段,布尔值使用明确开关."
  [index {:keys [key label type options required? min disabled?]}]
  [antd/form-item {:name [index (name key)] :label label :valuePropName (if (= type :boolean) "checked" "value")
                   :rules (when required? [{:required true :message (str "请填写" label)}])}
   (case type
     :boolean [antd/switch {:checkedChildren "是" :unCheckedChildren "否"}]
     :number [antd/input-number {:min (or min 0) :precision 0 :disabled disabled? :style {:width "100%"}}]
     :multi [antd/select {:mode "multiple" :options options :optionFilterProp "label" :placeholder (str "选择" label)}]
     :textarea [antd/text-area {:rows 2 :maxLength 4000 :placeholder (str "填写" label)}]
     [antd/input {:disabled disabled? :maxLength 200 :placeholder (str "填写" label)}])])

(defn- row-editor
  "保留冻结明细行,仅创建类表单允许添加或移除行."
  [{:keys [rows-key rows-title rows-fields editable-rows? new-row row-labels]}]
  [form-list {:name (name rows-key)}
   (fn [fields operations]
     (r/as-element
       [:section
        [:h4 rows-title]
        (for [field (array-seq fields)]
          ^{:key (.-key field)}
          [:div {:style {:padding 16 :marginBottom 12 :border "1px solid #e4e8ee" :borderRadius 8}}
           [:div {:style {:display "flex" :justifyContent "space-between" :marginBottom 8}}
            [:strong (str "第 " (inc (.-name field)) " 行"
                          (when-let [label (get row-labels (.-name field))] (str " / " label)))]
            (when editable-rows? [antd/button {:type "link" :danger true :on-click #(.remove operations (.-name field))} "移除此行"])]
           (for [spec rows-fields] ^{:key (:key spec)} [nested-field (.-name field) spec])])
        (when editable-rows? [antd/button {:type "dashed" :on-click #(.add operations (clj->js (or new-row {})))} "添加明细行"])]))])

(defn- rows-dialog
  "使用结构化列表提交逐行物料与质量数据."
  [{:keys [title fields initial path method project on-close on-saved rows-key description transform] :as config}]
  (let [[form] (antd/form-use-form)
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title title :open true :onCancel on-close :onOk #(.submit form) :width 760
                   :okText "保存" :cancelText "返回" :confirmLoading busy? :destroyOnHidden true
                   :style {:maxWidth "calc(100vw - 32px)"}}
       (when description [:p {:style {:lineHeight 1.8 :color "#718096"}} description])
       (when error [shared/error-panel error nil])
       [antd/form {:form form :layout "vertical" :initialValues initial :disabled busy?
                    :onFinish (fn [values]
                                (let [data (js->clj values :keywordize-keys true)]
                                  (run! (or method :post) path
                                        (assoc (if transform (transform data) data) :version (:version project))
                                        (str title "成功"))))}
        (for [field fields] ^{:key (:key field)} [w/form-field field])
        [row-editor config]]]))

(defn dialog
  "按表单内容选择通用命令或逐行业务录入组件."
  [config]
  (if (:rows-key config) [rows-dialog config] [w/mutation-dialog config]))
