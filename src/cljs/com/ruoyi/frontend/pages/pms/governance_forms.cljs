(ns com.ruoyi.frontend.pages.pms.governance-forms
  "章程,需求,证据与工程治理的业务表单."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.pages.pms.widgets :as w]))


(defn owner-field
  "选择真实组织责任人."
  [users]
  {:key :owner_id :label "责任人" :type :select :required? true :options (w/user-options users)})


(defn reviewer-field
  "要求选择当前用户以外的独立审批人."
  [options]
  {:key :reviewer_id :label "独立审批人" :type :select :required? true
   :options (w/user-options (remove #(= (:user_id %) (:currentUserId options)) (:users options)))})


(defn evidence-field
  "证据绑定具体文档版本,支持多项."
  [documents]
  {:key :evidence_ids :label "证据版本" :type :multi :required? true
   :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / " (:title %) " / V" (:revision %))) documents)})


(defn charter-dialog
  "明确项目目标,范围与成功标准; 初始预算与授权项目经理均为可选并随版本不可变冻结."
  [base options]
  {:title "编制项目章程" :path (str base "/charters")
   :description "初始预算与授权项目经理均为可选字段: 留空表示章程不含该项; 预算金额最多两位小数并由服务端规范化, 币种缺省CNY; 授权项目经理须为有效成员. 填写项随内容版本不可变冻结."
   :transform (fn [data]
                (reduce (fn [m k] (let [v (get data k)] (if (or (nil? v) (= "" v)) (dissoc m k) m)))
                        data [:initial_budget :budget_currency :authorized_pm_id]))
   :fields [{:key :title :label "章程标题" :required? true}
            {:key :objective :label "项目目标" :type :textarea :required? true}
            {:key :scope :label "项目范围" :type :textarea :required? true}
            {:key :success_criteria :label "成功标准" :type :textarea :required? true}
            {:key :sponsor_id :label "发起人" :type :select :options (w/user-options (:users options)) :required? true}
            {:key :initial_budget :label "初始预算" :hint "例如 120000.00, 可留空; 服务端规范化为两位小数"}
            {:key :budget_currency :label "预算币种" :type :select :options (w/choices ["CNY" "USD" "EUR" "GBP" "HKD"])
             :hint "填写预算时可留空, 缺省CNY"}
            {:key :authorized_pm_id :label "授权项目经理" :type :select :options (w/user-options (:users options))
             :hint "可留空; 显式记录被授权的项目经理并随章程版本冻结, 留空则由项目管理者隐含承载"}]})


(defn requirement-dialog
  "维护可验证的客户需求及新修订."
  [base options requirement]
  {:title (if requirement "修订URS需求" "新增URS需求")
   :path (str base "/requirements" (when requirement (str "/" (:id requirement) "/revisions")))
   :initial (if requirement (select-keys requirement [:code :text :category :priority :owner_id :verification_method]) {:priority "required" :category "功能"})
   :fields [{:key :code :label "需求编号" :required? true}
            {:key :text :label "需求描述与验收标准" :type :textarea :required? true}
            {:key :category :label "需求类别" :required? true}
            {:key :priority :label "需求优先级" :type :select :options (w/choices ["required" "desired"]) :required? true}
            {:key :verification_method :label "验证方式" :type :select
             :options [{:value "test" :label "测试"} {:value "inspection" :label "检验"}
                       {:value "demonstration" :label "演示"} {:value "analysis" :label "分析"}]}
            (owner-field (:users options))]})


(defn document-dialog
  "保存真实文本内容,由服务端生成SHA256与不可变版本; 密级/阶段/结构节点用于项目内归集追踪."
  [base document]
  {:title (if document "新增证据文档版本" "登记证据文档")
   :path (str base "/documents" (when document (str "/" (:id document) "/revisions")))
   :description "录入实际文本证据,内容将形成独立版本和校验摘要.密级/阶段/结构节点仅用于项目内归集与追踪,不替代项目授权.二进制附件请使用组织文档库并记录其引用."
   :initial (when document (select-keys document [:code :title :filename :content :classification :stage :structure_node]))
   :transform (fn [data]
                (reduce (fn [m k] (let [v (get data k)] (if (or (nil? v) (= "" v)) (dissoc m k) m)))
                        data [:classification :stage :structure_node]))
   :fields [{:key :code :label "文档编号" :required? true}
            {:key :title :label "文档标题" :required? true}
            {:key :filename :label "文件名" :required? true :hint "例如 design-review.txt"}
            {:key :classification :label "密级" :type :select
             :options [{:value "public" :label "公开"} {:value "internal" :label "内部"} {:value "confidential" :label "机密"}]
             :hint "未选择时服务端记为内部"}
            {:key :stage :label "所属阶段" :hint "例如 设计/DQ/制造/验证/过程, 可留空"}
            {:key :structure_node :label "结构节点" :hint "例如 主机/控制柜, 可留空"}
            {:key :content :label "文档正文" :type :textarea :max 1048576 :required? true}]})


(defn trace-dialog
  "建立URS需求与文档版本或WBS任务的明确追踪关系."
  [base model planning]
  {:title "建立需求追踪" :path (str base "/traces") :initial {:relation "verifies"}
   :transform (fn [data]
                (let [[kind id] (str/split (:target data) #":" 2)]
                  (-> data (dissoc :target) (assoc :target_kind kind :target_id id))))
   :fields [{:key :requirement_id :label "URS需求版本" :type :select :required? true
             :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / V" (:revision %) " / " (:text %))) (:requirements model))}
            {:key :target :label "关联交付物" :type :select :required? true
             :options (vec (concat (map #(hash-map :value (str "document:" (:id %)) :label (str "证据 / " (:title %) " V" (:revision %))) (:documents model))
                                   (map #(hash-map :value (str "task:" (:task_id %)) :label (str "任务 / " (:name %))) (:tasks planning))))}
            {:key :relation :label "追踪关系" :type :select :required? true
             :options [{:value "satisfies" :label "满足需求"} {:value "verifies" :label "验证需求"}]}]})


(defn risk-dialog
  "按概率和影响评估风险,指定应对责任."
  [base options]
  {:title "登记项目风险" :path (str base "/risks") :initial {:probability 3 :impact 3}
   :fields [{:key :title :label "风险描述" :required? true}
            {:key :probability :label "发生概率(1-5)" :type :number :min 1 :max 5 :required? true}
            {:key :impact :label "影响程度(1-5)" :type :number :min 1 :max 5 :required? true}
            (owner-field (:users options))
            {:key :mitigation :label "应对措施" :type :textarea :required? true}
            {:key :due_date :label "计划应对日期" :type :date :required? true}
            {:key :response_strategy :label "应对策略" :type :select
             :options [{:value "avoid" :label "规避"} {:value "transfer" :label "转移"}
                       {:value "mitigate" :label "减轻"} {:value "accept" :label "接受"}]}]})


(defn risk-library-options
  "把内置典型风险库转为下拉选项, 标签并列类别与标准概率x影响评分."
  [library]
  (mapv (fn [entry]
          {:value (:key entry)
           :label (str "[" (:category entry) "] " (:title entry) " — "
                        (:probability entry) "×" (:impact entry) "=" (* (:probability entry) (:impact entry)))})
        library))


(defn risk-library-dialog
  "从内置典型风险库一键实例化真实风险, 继承标准评分与应对措施, 仅需指定责任人与期限."
  [base library options]
  {:title "从典型风险库选用" :path (str base "/risks/from-library")
   :description "选择项目面临的典型风险, 服务端按库中标准概率×影响自动评分并套用应对措施与适用阶段; 达到升级阈值的条目将自动进入超阈值升级待独立确认门控."
   :fields [{:key :template_key :label "典型风险" :type :select :required? true :options (risk-library-options library)}
            (owner-field (:users options))
            {:key :due_date :label "计划应对日期" :type :date :required? true}]})


(defn risk-review-dialog
  "风险复评带证据提交独立审核,关闭前核查已发生问题."
  [base options documents risk]
  {:title "提交风险复评" :path (str base "/risks/" (:id risk) "/review")
   :description "如需根据最新情况重新评估风险, 可同时填写新的发生概率与影响程度 (须成对填写); 审批通过后系统按新评分重算并重新触发超阈值升级门控, 留空则维持原评分."
   :initial {:outcome "active"}
   :fields [{:key :outcome :label "复评结论" :type :select :required? true
             :options [{:value "active" :label "继续跟踪"} {:value "mitigated" :label "已采取缓解措施"} {:value "closed" :label "关闭风险"}]}
            {:key :review_note :label "复评依据" :type :textarea :required? true}
            {:key :probability :label "新发生概率(1-5,选填)" :type :number :min 1 :max 5 :hint "留空维持原评分; 填此项须同时填写新影响程度."}
            {:key :impact :label "新影响程度(1-5,选填)" :type :number :min 1 :max 5 :hint "留空维持原评分; 填此项须同时填写新发生概率."}
            {:key :next_review_date :label "下次复评日期" :type :date :hint "继续跟踪或缓解时必填,必须晚于今天.关闭风险时可留空."}
            (evidence-field documents) (reviewer-field options)]})


(defn risk-escalation-dialog
  "独立质量审批人确认超阈值风险的升级处置, 批准责成处置或经评估豁免."
  [base risk]
  {:title "确认风险升级" :path (str base "/risks/" (:id risk) "/escalate")
   :initial {:decision "approved"}
   :description (str (:escalation_reason risk)
                     " 须由登记人之外的独立质量审批人确认后方可继续缓解: 批准=按升级责成处置; 驳回=经评估可在责任层处置并解除升级门控.")
   :fields [{:key :decision :label "升级处置决定" :type :select :required? true
             :options [{:value "approved" :label "确认升级并责成处置"} {:value "rejected" :label "评估后可在现层处置"}]}
            {:key :note :label "处置意见" :type :textarea :required? true}]})


(defn issue-escalation-dialog
  "独立质量审批人确认阻断级问题的升级处置, 批准责成处置或经评估豁免."
  [base issue]
  {:title "确认问题升级" :path (str base "/issues/" (:id issue) "/escalate")
   :initial {:decision "approved"}
   :description (str (:escalation_reason issue)
                     " 须由登记人之外的独立质量审批人确认后方可提交解决: 批准=按升级责成处置; 驳回=经评估可在现层处置并解除升级门控.")
   :fields [{:key :decision :label "升级处置决定" :type :select :required? true
             :options [{:value "approved" :label "确认升级并责成处置"} {:value "rejected" :label "评估后可在现层处置"}]}
            {:key :note :label "处置意见" :type :textarea :required? true}]})


(defn issue-reopen-dialog
  "已关闭问题重开须明确新证据与独立责任人."
  [base options documents issue]
  {:title "申请问题重开" :path (str base "/issues/" (:id issue) "/reopen")
   :fields [{:key :reason :label "重开依据" :type :textarea :required? true}
            (evidence-field documents) (reviewer-field options)]})


(defn issue-dialog
  "登记阻碍交付的问题与解决责任."
  [base options]
  {:title "登记项目问题" :path (str base "/issues") :initial {:severity "major"}
   :fields [{:key :title :label "问题描述" :required? true}
            {:key :severity :label "严重程度" :type :select :options (w/choices ["blocker" "major" "minor"]) :required? true}
            (owner-field (:users options)) {:key :due_date :label "计划解决日期" :type :date :required? true}]})


(defn issue-reassign-dialog
  "转派问题责任人并说明原因, 保留原责任人供审计."
  [base options issue]
  {:title "转派问题责任人" :path (str base "/issues/" (:id issue) "/reassign")
   :description "新责任人须为当前项目成员, 转派保留原责任人与原因."
   :fields [(owner-field (:users options))
            {:key :reason :label "转派原因" :type :textarea :required? true}]})


(def meeting-type-labels
  {"regular" "常规会议" "kickoff" "项目启动会" "review" "评审会" "fat-kickoff" "FAT启动会" "fat-summary" "FAT总结会"})

(defn meeting-dialog
  "记录实际会议结论,参会人,会议类型,主计划基线引用与可选会前资料(引用项目内真实文档版本)."
  [base options documents baselines]
  {:title "登记项目会议" :path (str base "/meetings") :initial {:meeting_type "regular"}
   :transform (fn [data] (cond-> data (str/blank? (:baseline_id data)) (dissoc :baseline_id)))
   :fields [{:key :title :label "会议主题" :required? true}
            {:key :meeting_type :label "会议类型" :type :select :required? true
             :options (mapv (fn [[v l]] {:value v :label l}) meeting-type-labels)
             :hint "启动会必须绑定会前资料并引用主计划基线."}
            {:key :held_on :label "会议日期" :type :date :required? true}
            {:key :attendee_ids :label "参会人" :type :multi :options (w/user-options (:users options)) :required? true}
            {:key :minutes :label "会议纪要" :type :textarea :required? true}
            {:key :material_ids :label "会前资料" :type :multi
             :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / " (:title %) " / V" (:revision %))) documents)
             :hint "选择项目内已登记的证据文档版本作为会前资料 (售前资料/需求), 可留空"}
            {:key :baseline_id :label "引用主计划基线" :type :select
             :options (mapv #(hash-map :value (:baseline_id %) :label (str "计划修订 " (:plan_revision %) " · " (get w/labels (:status %) (:status %)))) baselines)}]})

(defn dq-dialog
  "建立 DQ 关键任务: 检查清单逐行, 交付件绑定确定文档版本."
  [base options documents planning]
  {:title "建立DQ关键任务" :path (str base "/dqs")
   :transform (fn [data]
                (-> data (dissoc :check_titles)
                    (assoc :checklist (mapv (fn [i title] {:code (str "D" (inc i)) :title title :required true})
                                            (range) (remove str/blank? (str/split-lines (:check_titles data)))))
                    (cond-> (str/blank? (:task_id data)) (dissoc :task_id))))
   :fields [{:key :code :label "DQ编号" :required? true} {:key :title :label "DQ任务" :required? true}
            (owner-field (:users options))
            {:key :check_titles :label "检查清单" :type :textarea :required? true :hint "每行一项检查, 均为必需项."}
            {:key :deliverable_ids :label "确定版本交付件" :type :multi :required? true
             :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / " (:title %) " / V" (:revision %))) documents)}
            {:key :task_id :label "关联WBS任务" :type :select :options (w/options (:tasks planning) :task_id :name)}]})

(defn dq-check-dialog
  "逐项登记 DQ 检查结果."
  [base dq]
  {:title "填写DQ检查结果" :path (str base "/dqs/" (:id dq) "/checks")
   :transform (fn [data]
                {:results (mapv (fn [item] {:code (:code item) :passed (= "passed" (get data (keyword (str "passed_" (:code item)))))
                                            :note (or (get data (keyword (str "note_" (:code item)))) "")}) (:checklist dq))})
   :fields (vec (mapcat (fn [item]
                          [{:key (keyword (str "passed_" (:code item))) :label (str (:code item) " / " (:title item)) :type :select :required? true
                            :options [{:value "passed" :label "检查通过"} {:value "failed" :label "检查未通过"}]}
                           {:key (keyword (str "note_" (:code item))) :label "检查说明"}]) (:checklist dq)))})

(defn node-pause-dialog
  "对子项目/单机发起局部暂停."
  [base nodes]
  {:title "局部暂停单机/子项目" :path (str base "/node-pauses")
   :description "暂停期间该节点及其下属单机的任务禁止进度反馈, 不影响无关单机; 恢复时记录重排影响."
   :fields [{:key :node_id :label "结构节点" :type :select :required? true
             :options (mapv #(hash-map :value (:node_id %) :label (str (:node_code %) " · " (:name %))) (remove #(= "main" (:node_type %)) nodes))}
            {:key :reason :label "暂停原因" :type :textarea :required? true}]})

(defn node-resume-dialog
  [base pause]
  {:title "恢复节点执行" :path (str base "/node-pauses/" (:id pause) "/resume")
   :fields [{:key :impact_note :label "恢复条件与重排影响" :type :textarea :required? true}]})


(defn action-dialog
  "会议决定形成有负责人和到期日期的行动."
  [base options meeting]
  {:title "新增会议行动" :path (str base "/meetings/" (:id meeting) "/actions")
   :description (:title meeting)
   :fields [{:key :title :label "行动内容" :required? true} (owner-field (:users options))
            {:key :due_date :label "到期日期" :type :date :required? true}]})


(defn change-dialog
  "记录变更对五个工程维度的影响; 可选量化工期与成本影响用于高影响判定."
  [base]
  {:title "提出项目变更" :path (str base "/changes")
   :transform (fn [data]
                (reduce (fn [m k] (let [v (get data k)] (if (or (nil? v) (= "" v)) (dissoc m k) m)))
                        data [:schedule_impact_days :cost_impact_amount]))
   :fields [{:key :title :label "变更标题" :required? true}
            {:key :reason :label "变更原因" :type :textarea :required? true}
            {:key :scope_impact :label "范围影响" :type :textarea :required? true}
            {:key :schedule_impact :label "进度影响" :type :textarea :required? true}
            {:key :cost_impact :label "成本影响" :type :textarea :required? true}
            {:key :quality_impact :label "质量影响" :type :textarea :required? true}
            {:key :resource_impact :label "资源影响" :type :textarea :required? true}
            {:key :schedule_impact_days :label "工期影响(天)" :type :number :min 0 :max 3650
             :hint "可选, 0-3650整数天; 用于量化影响分析与高影响判定"}
            {:key :cost_impact_amount :label "成本影响金额"
             :hint "可选, 最多两位小数, 如 150000.00; 用于量化影响分析与高影响判定"}]})


(defn template-dialog
  "将具体验收准则定义为逐项Gate检查."
  [base]
  {:title "建立Gate模板" :path (str base "/gate-templates") :initial {:stage "execution" :gate_type "generic" :blocks [] :require_released false}
   :transform (fn [data]
                (-> data (dissoc :check_titles :require_released)
                    (assoc :required true
                           :blocks (vec (or (:blocks data) []))
                           :checks (mapv (fn [i title] {:code (str "C" (inc i)) :title title :required true
                                                         :require_released (true? (:require_released data))})
                                         (range) (remove str/blank? (str/split-lines (:check_titles data)))))))
   :fields [{:key :code :label "Gate编号" :required? true}
            {:key :title :label "Gate名称" :required? true}
            {:key :gate_type :label "关口类型" :type :select :required? true
             :options [{:value "generic" :label "通用"} {:value "requirement-confirm" :label "需求确认"} {:value "host-summary" :label "主机汇总"}
                       {:value "attachment-summary" :label "附件汇总"} {:value "kitting" :label "零件齐套(G4)"}
                       {:value "assembly-test-handover" :label "装配测试交接(G5)"} {:value "fat-confirm" :label "FAT确认(G6)"}
                       {:value "handover" :label "项目交底(G7)"} {:value "sat-confirm" :label "SAT确认(G8)"}]}
            {:key :stage :label "控制阶段" :type :select :required? true
             :options [{:value "execution" :label "执行准入"} {:value "closure" :label "结项准出"} {:value "design" :label "设计阶段"}
                       {:value "manufacturing" :label "制造阶段"} {:value "delivery" :label "交付阶段"} {:value "site" :label "现场阶段"}]}
            {:key :blocks :label "阻断的交付检查点" :type :multi
             :options [{:value "assembly.start" :label "装配开工"} {:value "test.SIT" :label "SIT试验"} {:value "test.FAT" :label "FAT试验"}
                       {:value "test.SAT" :label "SAT试验"} {:value "shipment.dispatch" :label "发运"}]
             :hint "未通过该关口前, 对应交付命令被拒绝."}
            {:key :require_released :label "检查证据须已发布" :type :select
             :options [{:value false :label "登记版本即可"} {:value true :label "须经独立发布审批"}]}
            {:key :check_titles :label "必需检查项" :type :textarea :required? true :hint "每行一项具体、可验证的验收标准."}]})


(defn gate-dialog
  "基于模板创建带独立评审人的Gate."
  [base model options]
  {:title "发起Gate检查" :path (str base "/gates")
   :fields [{:key :template_id :label "Gate模板" :type :select :options (w/options (:gate_templates model) :id :title) :required? true}
            {:key :title :label "检查名称" :required? true} (reviewer-field options)]})


(defn gate-check-dialog
  "逐项选择通过结果及具体证据版本."
  [base model gate]
  (let [template (some #(when (= (:id %) (:template_id gate)) %) (:gate_templates model))
        checks (:checks template)]
    {:title "填写Gate检查结果" :path (str base "/gates/" (:id gate) "/checks")
     :transform (fn [data]
                  {:checks (mapv (fn [check]
                                   {:code (:code check)
                                    :passed (= "passed" (get data (keyword (str "passed_" (:code check)))))
                                    :evidence_ids (get data (keyword (str "evidence_" (:code check))) [])}) checks)})
     :fields (vec (mapcat (fn [check]
                            [{:key (keyword (str "passed_" (:code check))) :label (str (:code check) " / " (:title check))
                              :type :select :required? true :options [{:value "passed" :label "检查通过"} {:value "failed" :label "检查未通过"}]}
                             (assoc (evidence-field (:documents model)) :key (keyword (str "evidence_" (:code check))) :label "对应证据版本")]) checks))}))


(defn decision-dialog
  "记录独立审批结果与不可省略的决策依据."
  [path decision title]
  {:title title :path path :transform #(assoc % :decision decision)
   :fields [{:key :reason :label "决策意见" :type :textarea :required? true}]})


(defn discard-dialog
  "受控作废: 只软置为已作废并保留审计, 仍被引用的记录由服务端拒绝."
  [path label]
  {:title (str "作废" label) :path path
   :description "作废不是删除, 记录将标记为已作废并保留可追溯的审计痕迹; 若仍被其它对象引用会被拒绝."
   :fields [{:key :reason :label "作废原因" :type :textarea :required? true}]})


(defn restore-dialog
  "受控撤销作废: 把已作废记录恢复到作废前状态并保留审计."
  [path label]
  {:title (str "恢复" label) :path path
   :description "恢复不是新建, 记录将退回作废前的状态并保留可追溯的审计痕迹; 仅对已作废记录可用."
   :fields [{:key :reason :label "恢复原因" :type :textarea :required? true}]})


(defn stakeholder-options
  "把有效干系人转为下拉选项, 编号与名称并列."
  [stakeholders]
  (mapv #(hash-map :value (:id %) :label (str (:code %) " / " (:name %))) stakeholders))


(defn stakeholder-dialog
  "登记干系人首版或保留编号的不可变修订."
  [base options stakeholder]
  {:title (if stakeholder "修订干系人" "登记干系人")
   :path (str base "/stakeholders" (when stakeholder (str "/" (:id stakeholder) "/revisions")))
   :initial (if stakeholder (select-keys stakeholder [:code :name :role :category :interest :influence :owner_id])
                {:category "internal" :interest "medium" :influence "medium"})
   :fields [{:key :code :label "干系人编号" :required? true}
            {:key :name :label "名称" :required? true}
            {:key :role :label "职责角色" :required? true}
            {:key :category :label "分类" :type :select :required? true
             :options [{:value "internal" :label "内部"} {:value "external" :label "外部"}
                       {:value "supplier" :label "供应商"} {:value "customer" :label "客户"} {:value "regulator" :label "监管方"}]}
            {:key :interest :label "关注度" :type :select :required? true :options (w/choices ["high" "medium" "low"])}
            {:key :influence :label "影响力" :type :select :required? true :options (w/choices ["high" "medium" "low"])}
            {:key :owner_id :label "关联项目成员(可选)" :type :select :options (w/user-options (:users options))}]})


(defn raci-dialog
  "为具体活动指派确定的RACI职责."
  [base model]
  {:title "指派RACI职责" :path (str base "/raci")
   :initial {:responsibility "R"}
   :fields [{:key :activity :label "活动或交付物" :required? true}
            {:key :stakeholder_id :label "干系人" :type :select :required? true :options (stakeholder-options (:stakeholders model))}
            {:key :responsibility :label "职责" :type :select :required? true
             :options [{:value "R" :label "执行 R"} {:value "A" :label "负责 A"} {:value "C" :label "咨询 C"} {:value "I" :label "知会 I"}]}]})


(defn comm-plan-dialog
  "登记或修订沟通计划的渠道, 节奏与受众."
  [base model options plan]
  {:title (if plan "修订沟通计划" "登记沟通计划")
   :path (str base "/comm-plans" (when plan (str "/" (:id plan) "/revisions")))
   :initial (if plan (select-keys plan [:code :objective :channel :frequency :audience :next_date :owner_id])
                {:channel "meeting" :frequency "weekly"})
   :fields [{:key :code :label "计划编号" :required? true}
            {:key :objective :label "沟通目标" :type :textarea :required? true}
            {:key :channel :label "沟通渠道" :type :select :required? true
             :options [{:value "meeting" :label "会议"} {:value "email" :label "邮件"} {:value "dashboard" :label "看板"}
                       {:value "report" :label "报告"} {:value "review" :label "评审"}]}
            {:key :frequency :label "沟通频率" :type :select :required? true :options (w/choices ["daily" "weekly" "biweekly" "monthly" "quarterly"])}
            {:key :audience :label "沟通受众" :type :multi :required? true :options (stakeholder-options (:stakeholders model))}
            {:key :next_date :label "下次沟通日期" :type :date :required? true}
            {:key :owner_id :label "责任人(可选)" :type :select :options (w/user-options (:users options))}]})


(defn comm-plan-meeting-dialog
  "由最新版本沟通计划生成一次受控会议."
  [base plan]
  {:title "生成沟通计划会议" :path (str base "/comm-plans/" (:id plan) "/meeting")
   :description (str "由 " (:code plan) " / V" (:revision plan) " 生成, 参会人取自受众干系人已绑定的项目成员.")
   :fields [{:key :held_on :label "会议日期(可选)" :type :date :hint "留空则采用计划的下次沟通日期."}]})


(defn comm-plan-log-dialog
  "记录沟通计划一次实际沟通, 服务端按既定频率顺延下次沟通日期并留痕."
  [base plan]
  {:title "标记已沟通" :path (str base "/comm-plans/" (:id plan) "/log")
   :description (str "记录 " (:code plan) " 的一次实际沟通; 系统按该计划的频率自动顺延下次沟通日期, 并保留可审计的沟通留痕.")
   :transform (fn [data]
                (reduce (fn [m k] (let [v (get data k)] (if (or (nil? v) (= "" v)) (dissoc m k) m)))
                        data [:on :note]))
   :fields [{:key :on :label "实际沟通日期(可选)" :type :date :hint "留空则采用今天"}
            {:key :note :label "沟通纪要" :type :textarea :hint "本次沟通结论或要点, 可留空"}]})


(defn action-complete-dialog
  "会议行动完成须附结果说明, 真实证据与独立验证人, 关闭前经核验."
  [base options documents action]
  {:title "提交行动完成" :path (str base "/actions/" (:id action) "/complete")
   :description (str "行动: " (:title action))
   :fields [{:key :result :label "完成结果说明" :type :textarea :required? true}
            (evidence-field documents) (reviewer-field options)]})
