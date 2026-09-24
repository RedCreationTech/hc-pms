(ns com.ruoyi.frontend.pages.pms.delivery
  "备料,装配,质量试验,发运签收及售后闭环工作台."
  (:require ["antd" :refer [Alert]]
            [clojure.string :as str]
            [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.delivery-forms :as forms]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]
            [reagent.hooks :as hooks]))

(def labels
  "交付记录的业务状态与配置名称."
  {"frozen" "已冻结" "partial" "部分齐套" "ready" "已就绪" "released" "已放行"
   "accepted" "完整接受" "shipped" "已发运" "received" "已完整接受" "conditional" "条件接收" "returned" "已拒收"
   "materials" "备料与BOM" "assembly" "装配交检" "quality" "质量试验" "shipment" "发运签收"
   "engineering_default" "工程默认要求" "project_configuration" "项目已配置要求"})

(defn- state-column
  "以明确业务名称展示状态."
  []
  {:title "状态" :dataIndex "status" :width 110
   :render #(r/as-element [antd/tag {:color (cond (contains? #{"approved" "frozen" "ready" "received" "closed"} %) "green"
                                                  (contains? #{"rejected" "returned" "conditional"} %) "orange"
                                                  :else "blue")}
                          (get labels % (get w/labels % %))])})

(defn- action
  "打开对应的受控业务命令表单."
  [{:keys [open!]} label config]
  [w/edit-button label #(open! config)])

(defn- review-actions
  "只有指定独立审核人可以决定冻结记录."
  [{:keys [options approve?] :as context} collection row]
  (when (and approve? (= "in_review" (:status row))
             (= (:currentUserId options) (:reviewer_id row))
             (not= (:currentUserId options) (:submitted_by row)))
    [:<>
     [action context "批准" (forms/decision-dialog context collection row "approved")]
     [action context "驳回" (forms/decision-dialog context collection row "rejected")]]))

(defn- material-actions
  "提交备料申请并冻结实际证据."
  [{:keys [editable?] :as context} row]
  (when (and editable? (contains? #{"draft" "rejected"} (:status row)))
    [action context "提交审批" (forms/submit-dialog context "material-requests" row "submit" true "提交备料申请")]))

(defn- bom-actions
  "冻结BOM后按实际物料逐项登记齐套."
  [{:keys [editable? executing?] :as context} row]
  [antd/space
   (when (and editable? (contains? #{"draft" "rejected"} (:status row)))
     [action context "申请冻结" (forms/submit-dialog context "boms" row "freeze" true "申请BOM冻结")])
   (when (and executing? (contains? #{"frozen" "partial" "ready"} (:status row)))
     [action context "登记齐套" (forms/kit-dialog context row)])])

(defn- assembly-actions
  "实际开工和交检分别保留证据与责任; 上岛/装配/交检/连线/下岛/交接步骤逐项登记."
  [{:keys [executing?] :as context} row]
  [antd/space
   (when (and executing? (contains? #{"draft" "rejected"} (:status row)))
     [action context "登记开工" (forms/submit-dialog context "assemblies" row "start" false "登记装配开工")])
   (when (and executing? (= "in_progress" (:status row)))
     [action context "提交交检" (forms/submit-dialog context "assemblies" row "submit" true "提交装配交检")])
   (when (and executing? (contains? #{"in_progress" "in_review" "approved"} (:status row)) (:next_step row))
     [action context "登记步骤" (forms/step-dialog context row)])])

(defn- survey-actions
  "工勘完成后提交独立确认."
  [{:keys [editable?] :as context} row]
  (when (and editable? (contains? #{"draft" "rejected"} (:status row)))
    [action context "提交工勘确认" (forms/survey-submit-dialog context row)]))

(defn- handover-actions
  [{:keys [executing?] :as context} row]
  (when (and executing? (= "open" (:status row)))
    [action context "完成交底" (forms/handover-dialog context row)]))

(defn- site-task-actions
  [{:keys [executing?] :as context} row]
  [antd/space
   (when (and executing? (= "draft" (:status row))) [action context "开始" (forms/site-start-dialog context row)])
   (when (and executing? (= "in_progress" (:status row))) [action context "完成" (forms/site-complete-dialog context row)])])

(defn- test-actions
  "试验结果逐项登记后提交独立评审."
  [{:keys [executing?] :as context} row]
  [antd/space
   (when (and executing? (contains? #{"draft" "ready" "rejected"} (:status row)))
     [action context "登记结果" (forms/results-dialog context row)])
   (when (and executing? (= "ready" (:status row)))
     [action context "提交试验评审" (forms/submit-dialog context "tests" row "submit" true "提交质量试验评审")])])

(defn- shipment-actions
  "放行,实际发运与独立签收验证保持职责分离."
  [{:keys [editable? executing? approve? options project] :as context} row]
  (let [current (:currentUserId options)]
    [antd/space
     (when (and editable? (contains? #{"draft" "rejected" "released"} (:status row)))
       [action context "发货前条件" (forms/conditions-dialog context row)])
     (when (and editable? (contains? #{"draft" "rejected"} (:status row)))
       [action context "申请放行" (forms/submit-dialog context "shipments" row "submit" false "提交发运放行")])
     (when (and executing? (= "released" (:status row)) (not= current (:reviewer_id row)))
       [action context "登记发运" (forms/dispatch-dialog context row)])
     (when (and approve? (contains? #{"execution" "closing"} (:status project))
                (contains? #{"shipped" "conditional" "returned"} (:status row))
                (= current (:reviewer_id row)) (not= current (:shipped_by row)) (not= current (:submitted_by row)))
       [action context "验证签收" (forms/receipt-dialog context row)])]))

(defn- service-actions
  "售后解决后由独立人员验证关闭."
  [{:keys [executing?] :as context} row]
  (when (and executing? (contains? #{"open" "rejected"} (:status row)))
    [action context "提交解决验证" (forms/resolve-dialog context row)]))

(defn- record-actions
  "按业务类型显示必要的下一步及明细."
  [{:keys [inspect!] :as context} collection row]
  [antd/space {:wrap true}
   [w/edit-button "查看明细" #(inspect! (assoc row :collection collection))]
   [(case collection "material-requests" material-actions "boms" bom-actions "assemblies" assembly-actions
                     "tests" test-actions "shipments" shipment-actions "service-cases" service-actions
                     "surveys" survey-actions "handovers" handover-actions "site-tasks" site-task-actions) context row]
   [review-actions context collection row]])

(defn- record-section
  "统一业务台账,每条记录对应真实服务端命令."
  [context key collection title subtitle create-label create-form columns]
  [shared/panel title subtitle
   (when (:editable? context) [antd/button {:type "primary" :on-click #((:open! context) (create-form context))} create-label])
   [w/record-table (get (:model context) key)
    (vec (concat [(w/text-column :code "编号") (w/text-column :title "标题")] columns [(state-column)]))
    #(record-actions context collection %)]])

(def request-type-labels
  {"standard" "标准备料" "long_lead" "长周期物料" "raw_material" "原材料预投" "direct_ship" "直发物料" "packaging" "包材申请"})

(defn- kitting-rollup-section
  "D06 齐套率按主/子/单机多层卷积并可下钻缺件清单 (读取时派生)."
  [{:keys [model options]}]
  (let [rollup (:kitting_rollup model) node-labels {"main" "主项目" "sub" "子项目" "machine" "单机"}]
    [shared/panel "齐套率多层视图" "冻结BOM按关联任务所属结构节点归集: 分子=齐套行数, 分母=冻结行数; 缺件清单可定位责任人"
     [antd/space {:wrap true}
      [antd/tag {:color "blue"} (str "整体齐套率 " (:kit_percent rollup) "%")]
      [antd/tag (str "冻结BOM " (:bom_count rollup))]
      [antd/tag (str "齐套行 " (:complete_lines rollup) "/" (:required_lines rollup))]
      (when (pos? (or (:unassigned_bom_count rollup) 0)) [antd/tag {:color "orange"} (str "未映射节点BOM " (:unassigned_bom_count rollup))])]
     [:div {:style {:display "grid" :gap 16}}
      [antd/table {:rowKey "node_id" :size "small" :pagination false :dataSource (clj->js (:nodes rollup))
                   :columns (clj->js [{:title "层级" :dataIndex "node_type" :width 90 :render (fn [v] (get node-labels v v))}
                                      {:title "节点编号" :dataIndex "node_code" :width 160} {:title "名称" :dataIndex "name"}
                                      {:title "BOM数" :dataIndex "bom_count" :width 80} {:title "齐套行/冻结行" :key "lines" :width 130
                                                                                        :render (fn [_ row] (str (aget row "complete_lines") "/" (aget row "required_lines")))}
                                      {:title "缺件行" :dataIndex "shortage_lines" :width 90}
                                      {:title "齐套率" :dataIndex "kit_percent" :width 220
                                       :render (fn [v] (r/as-element [antd/progress {:percent (or v 0) :size "small" :style {:width 180}}]))}])}]
      [:details
       [:summary (str "缺件清单 " (count (:shortages rollup)) " 行")]
       [w/record-table (:shortages rollup)
        [(w/text-column :bom_code "BOM") (w/text-column :code "物料编号") (w/text-column :name "物料名称")
         (w/text-column :quantity "冻结需求") (w/text-column :available_quantity "实际可用") (w/text-column :shortage "缺口")
         {:title "责任人" :dataIndex "owner_id" :render #(w/related-label (:users options) :user_id :nick_name %)}
         {:title "齐套登记" :dataIndex "kit_recorded" :render #(if % "已登记" "未登记")}] nil]]]]))

(defn- materials-section
  "备料审批与冻结BOM保留清晰来源关系."
  [context]
  [:div {:style {:display "grid" :gap 20}}
   [record-section context :material_requests "material-requests" "备料申请" "审批申请形成BOM来源 (含长周期/原材预投/直发/包材), 不把手工登记标记为已采购"
    "新建备料申请" forms/material-dialog [{:title "类型" :dataIndex "request_type" :width 110 :render #(get request-type-labels % %)}
                                          (w/text-column :needed_on "需求日期")
                                          {:title "目标节点" :dataIndex "node_id" :width 120 :render #(w/related-label (get-in context [:planning :nodes]) :node_id :node_code %)}]]
   [record-section context :boms "boms" "BOM与齐套" "冻结清单保持不变,齐套率按满足数量的物料行计算"
    "建立BOM" forms/bom-dialog [(w/text-column :complete_line_count "齐套行数")
                             (w/text-column :required_line_count "总行数") (w/text-column :kit_percent "齐套率%")]]
   [kitting-rollup-section context]])

(defn- step-timeline
  "装配执行明细: 上岛/装配/单机交检/连线交检/下岛/交接."
  [row]
  (let [done (into {} (map (juxt :step identity) (:steps row)))]
    [antd/space {:wrap true}
     (for [step forms/step-order] ^{:key step}
       [antd/tag {:color (if (get done step) "green" "default")}
        (str (get forms/step-labels step step) (when-let [d (get done step)] (str " " (:actual_date d))))])]))

(defn- fieldwork-section
  "B07 工勘, E07 交底时限与 E08/E09 现场任务 (本地事实, 外部回传未配置)."
  [context]
  [:div {:style {:display "grid" :gap 20}}
   [record-section context :surveys "surveys" "工勘任务" "按项目适用性登记各次工勘, 责任/日期/交付物明确, 完成须证据并独立确认"
    "登记工勘任务" forms/survey-dialog [(w/text-column :visit_no "次序") (w/text-column :planned_date "计划日期")
                                       (w/text-column :actual_date "实际日期") (w/text-column :deliverable "交付物")]]
   [shared/panel "发货后交底" "发运登记后自动生成, 截止期 = 发运日 + 配置天数 (自然日); 文件清单推CRM未配置, 仅本地签交" nil
    [w/record-table (:handovers (:model context))
     [(w/text-column :code "编号") (w/text-column :shipped_on "发运日期") (w/text-column :deadline "截止日期")
      {:title "时限" :key "days" :width 140
       :render (fn [_ row] (let [left (aget row "handover_days_left") overdue (true? (aget row "handover_overdue")) late (true? (aget row "completed_late"))]
                             (r/as-element (cond overdue [antd/tag {:color "red"} (str "已逾期 " (- left) " 天")]
                                                 (some? left) [antd/tag {:color "gold"} (str "剩 " left " 天")]
                                                 late [antd/tag {:color "orange"} "逾期完成"]
                                                 :else [antd/tag {:color "green"} "按期完成"]))))}
      (w/text-column :completed_on "完成日期") (w/text-column :checklist_note "清单说明")
      {:title "CRM推送" :dataIndex "crm_sync_status" :width 100 :render (fn [v] (if (= v "not_configured") "未配置" v))}
      (state-column)]
     #(record-actions context "handovers" %)]]
   [shared/panel "现场任务 (定位/安装/调试/SAT)" "交底完成后按配置滞后期自动生成, 顺序执行不可乱序; ERP下发与CRM回传未配置" nil
    [w/record-table (sort-by (juxt :handover_id :sequence) (:site_tasks (:model context)))
     [(w/text-column :sequence "序") (w/text-column :title "任务") (w/text-column :planned_start "计划开始")
      {:title "延误" :key "delay" :width 110
       :render (fn [_ row] (r/as-element (if (true? (aget row "site_delayed")) [antd/tag {:color "red"} "计划开始已过"]
                                             (if-let [d (aget row "site_days_to_start")] [antd/tag (str "距开始 " d " 天")] [:span "—"]))))}
      (w/text-column :actual_start "实际开始") (w/text-column :actual_end "实际完成") (w/text-column :result "结果") (state-column)]
     #(record-actions context "site-tasks" %)]]])

(defn- assemblies-section
  "装配台账展示实际开工, 执行步骤明细与独立交检."
  [context]
  [record-section context :assemblies "assemblies" "装配与交检" "齐套后实际开工 (齐套Gate阻断时拒绝), 上岛/装配/交检/连线/下岛/交接逐步登记, 完工后提交独立交检"
   "建立装配任务" forms/assembly-dialog
   [{:title "BOM来源" :dataIndex "bom_id" :render #(w/related-label (get-in context [:model :boms]) :id :title %)}
    {:title "执行步骤" :dataIndex "steps" :width 420 :render (fn [_ row] (r/as-element [step-timeline (js->clj row :keywordize-keys true)]))}]])

(defn- tests-section
  "质量试验明确类别,逐项记录证据和失败整改."
  [context]
  [record-section context :tests "tests" "SIT / FAT / SAT试验" "必需检查项失败自动形成阻断问题,整改关闭后才能独立批准"
   "建立质量试验" forms/test-dialog [(w/text-column :test_type "试验类别")
                                     {:title "装配来源" :dataIndex "assembly_id"
                                      :render #(w/related-label (get-in context [:model :assemblies]) :id :title %)}]])

(defn- shipments-section
  "记录放行,真实物流和客户签收验证."
  [context]
  [record-section context :shipments "shipments" "发运与签收" "质量合格后放行,独立审核人依据实际证据验证客户签收"
   "建立发运计划" forms/shipment-dialog [(w/text-column :consignee "收货方") (w/text-column :planned_date "计划日期")
                                        {:title "发货前条件" :dataIndex "preship_checklist" :width 260
                                         :render (fn [v] (r/as-element (into [antd/space {:wrap true}]
                                                                             (map (fn [c] [antd/tag {:color (if (:satisfied c) "green" "red")} (:label c)])
                                                                                  (js->clj v :keywordize-keys true)))))}
                                        (w/text-column :tracking_no "运单编号") (w/text-column :received_on "签收日期")]])

(defn- services-section
  "发运异常和售后处理需要解决证据与独立关闭."
  [context]
  [record-section context :service_cases "service-cases" "售后异常闭环" "条件接收或拒收保留异常链,解决后再次确认完整接受"
   "登记售后异常" forms/service-dialog [(w/text-column :due_date "处理期限") (w/text-column :resolution "解决说明")]])

(defn- overview
  "展示项目要求,尚未完成事项与外部系统状态."
  [{:keys [model editable? project open!] :as context}]
  (let [config (:configuration model)]
    [shared/panel "交付验收要求" (get labels (:source config) "项目交付要求")
     (when (and editable? (contains? #{"draft" "initiated" "planning"} (:status project)))
       [antd/button {:on-click #(open! (forms/configuration-dialog context))} "配置交付要求"])
     [:div {:style {:display "flex" :gap 8 :flexWrap "wrap" :marginBottom 16}}
      (for [stage (:required_stages config)] ^{:key stage} [antd/tag (get labels stage stage)])
      (for [test (:required_test_types config)] ^{:key test} [antd/tag {:color "blue"} test])
      [antd/tag {:color "purple"} (str "工勘 " (or (:required_survey_visits config) 0) " 次")]
      (for [c (:pre_ship_conditions config)] ^{:key c} [antd/tag {:color "orange"} (get {"warehouse_in" "发货前须入库确认" "payment" "发货前须提货款确认"} c c)])
      [antd/tag (str "交底截止 +" (or (:handover_deadline_days config) 2) " 天")]
      [antd/tag (str "现场滞后 +" (or (:site_lag_days config) 2) " 天")]]
     (when (seq (:blockers model))
       [:> Alert {:type "warning" :showIcon true :message "交付尚有待完成事项"
                    :description (r/as-element [:ul {:style {:paddingLeft 20 :margin 0}}
                                                (for [text (:blockers model)] ^{:key text} [:li text])])}])
     [:p {:style {:marginBottom 0 :color "#718096" :fontSize 12}}
      (if (= "not_configured" (:external_sync_status model)) "外部系统未配置,此页显示项目内登记与审批记录." "外部同步状态以实际回执为准.")]]))

(def detail-fields
  "交付明细中可读的业务字段."
  [[:code "编号"] [:title "标题"] [:needed_on "需求日期"] [:consignee "收货方"] [:delivery_address "交付地址"]
   [:planned_date "计划发运日期"] [:shipped_on "实际发运日期"] [:tracking_no "运单编号"]
   [:received_on "签收日期"] [:receiver_name "实际收货人"] [:due_date "处理期限"]
   [:exception_reason "异常原因"] [:resolution "解决记录"] [:decision_reason "审核结论"]])

(defn- evidence-text
  "用确切文档版本的名称解释证据列表."
  [documents ids]
  (str/join ", " (map #(w/related-label documents :id :title %) (js->clj ids))))

(defn- check-table
  "展示实际试验结果及真实整改问题名称."
  [governance checks]
  [w/record-table checks
   [(w/text-column :code "检查编号") {:title "结果" :dataIndex "passed" :render #(if % "通过" "失败")}
    (w/text-column :actual "实际结果")
    {:title "证据版本" :dataIndex "evidence_ids" :render #(evidence-text (:documents governance) %)}
    {:title "整改问题" :dataIndex "issue_id" :render #(w/related-label (:issues governance) :id :title %)}] nil])

(defn- record-history
  "保留可展开的历次复验,签收和各阶段证据."
  [governance row]
  [:div
   (for [[key label] [[:evidence_ids "审批证据"] [:kit_evidence_ids "齐套证据"] [:start_evidence_ids "开工证据"]
                      [:dispatch_evidence_ids "发运证据"] [:receipt_evidence_ids "签收证据"]]
         :when (seq (get row key))]
     ^{:key key} [:p (str label ": " (evidence-text (:documents governance) (get row key)))])
   (for [[index entry] (map-indexed vector (:result_history row))]
     ^{:key index} [:details {:style {:marginTop 12}}
                   [:summary (str "第 " (inc index) " 次试验记录 / 项目版本 " (:project_version entry))]
                   [check-table governance (:checks entry)]])
   (when (seq (:receipt_history row))
     [:details {:style {:marginTop 12}}
      [:summary "历次签收验证"]
      [w/record-table (:receipt_history row)
       [(w/text-column :received_on "签收日期") (w/text-column :receiver_name "实际收货人")
        {:title "结果" :dataIndex "acceptance" :render #(get labels % (get w/labels % %))}
        {:title "证据版本" :dataIndex "receipt_evidence_ids" :render #(evidence-text (:documents governance) %)}] nil]])])

(defn- record-details
  "逐行展示冻结物料,质量准则和实际结果,不输出原始JSON."
  [{:keys [governance planning]} row on-close]
  [antd/modal {:title (str "交付明细 / " (:title row)) :open true :footer nil :onCancel on-close :width 1000 :destroyOnHidden true}
   [:dl {:style {:display "grid" :gridTemplateColumns "130px 1fr" :gap 10}}
    (for [[key label] detail-fields :when (some? (get row key))]
      ^{:key key} [:<> [:dt {:style {:color "#718096"}} label] [:dd {:style {:margin 0}} (shared/display-value (get row key))]])
    [:dt "关联任务"] [:dd (w/related-label (:tasks planning) :task_id :name (:task_id row))]
    [:dt "URS版本"] [:dd (str/join ", " (map #(w/related-label (:requirements governance) :id :code %) (:requirement_ids row)))]]
   (when (seq (:items row)) [w/record-table (or (seq (:availability row)) (:items row))
                            [(w/text-column :code "物料编号") (w/text-column :name "物料名称")
                             (w/text-column :quantity "冻结数量") (w/text-column :unit "单位")
                             (w/text-column :available_quantity "实际可用量")] nil])
   (when (seq (:criteria row)) [w/record-table (:criteria row)
                               [(w/text-column :code "检查编号") (w/text-column :title "验收标准")
                                {:title "必需项" :dataIndex "required" :render #(if % "是" "否")}] nil])
   (when (seq (:checks row)) [check-table governance (:checks row)])
   [record-history governance row]])

(defn- content
  "按交付链环节组织可执行的工作台页签."
  [context]
  [:div {:style {:display "grid" :gap 20}}
   [overview context]
   [antd/tabs {:items (mapv (fn [[key label component]]
                             {:key key :label label :children (r/as-element [component context])})
                           [["materials" "备料与BOM" materials-section] ["assemblies" "装配交检" assemblies-section]
                            ["tests" "质量试验" tests-section] ["shipments" "发运签收" shipments-section]
                            ["fieldwork" "工勘与现场" fieldwork-section]
                            ["services" "售后闭环" services-section]])}]])

(defn delivery-workspace
  "加载交付,需求证据与任务,成功写入后刷新项目统一版本."
  [project revision options changed!]
  (let [project-base (str "/projects/" (:project_id project)) base (str project-base "/delivery")
        resource (shared/use-resource base {} [revision])
        governance (shared/use-resource (str project-base "/governance") {} [revision])
        planning (shared/use-resource (str project-base "/planning") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil) [detail set-detail!] (hooks/use-state nil)
        edit? (shared/use-permission "pms:project:edit") review? (shared/use-permission "pms:quality:approve")
        writable? (not (contains? #{"closed" "cancelled" "paused"} (:status project)))
        context {:base base :model (:data resource) :governance (:data governance) :planning (:data planning)
                 :options options :project project :editable? (and edit? writable?) :approve? (and review? writable?)
                 :executing? (and edit? (contains? #{"execution" "closing"} (:status project)))
                 :open! set-dialog! :inspect! set-detail!}
        loading? (some #(and (:loading? %) (nil? (:data %))) [resource governance planning])
        error (some :error [resource governance planning])]
    [:div
     [w/resource-view (assoc resource :error error :loading? loading? :data (when-not loading? (:data resource)))
      (fn [_] [content context])]
     (when dialog [forms/dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                               :on-saved (fn [_] (set-dialog! nil) (changed!))})])
     (when detail [record-details context detail #(set-detail! nil)])]))
