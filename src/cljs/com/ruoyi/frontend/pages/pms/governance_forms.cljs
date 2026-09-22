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
  "明确项目目标,范围与成功标准."
  [base options]
  {:title "编制项目章程" :path (str base "/charters")
   :fields [{:key :title :label "章程标题" :required? true}
            {:key :objective :label "项目目标" :type :textarea :required? true}
            {:key :scope :label "项目范围" :type :textarea :required? true}
            {:key :success_criteria :label "成功标准" :type :textarea :required? true}
            {:key :sponsor_id :label "发起人" :type :select :options (w/user-options (:users options)) :required? true}]})


(defn requirement-dialog
  "维护可验证的客户需求及新修订."
  [base options requirement]
  {:title (if requirement "修订URS需求" "新增URS需求")
   :path (str base "/requirements" (when requirement (str "/" (:id requirement) "/revisions")))
   :initial (if requirement (select-keys requirement [:code :text :category :priority :owner_id]) {:priority "required" :category "功能"})
   :fields [{:key :code :label "需求编号" :required? true}
            {:key :text :label "需求描述与验收标准" :type :textarea :required? true}
            {:key :category :label "需求类别" :required? true}
            {:key :priority :label "需求优先级" :type :select :options (w/choices ["required" "desired"]) :required? true}
            (owner-field (:users options))]})


(defn document-dialog
  "保存真实文本内容,由服务端生成SHA256与不可变版本."
  [base document]
  {:title (if document "新增证据文档版本" "登记证据文档")
   :path (str base "/documents" (when document (str "/" (:id document) "/revisions")))
   :description "录入实际文本证据,内容将形成独立版本和校验摘要.二进制附件请使用组织文档库并记录其引用."
   :initial (when document (select-keys document [:code :title :filename :content]))
   :fields [{:key :code :label "文档编号" :required? true}
            {:key :title :label "文档标题" :required? true}
            {:key :filename :label "文件名" :required? true :hint "例如 design-review.txt"}
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
            {:key :due_date :label "计划应对日期" :type :date :required? true}]})


(defn risk-review-dialog
  "风险复评带证据提交独立审核,关闭前核查已发生问题."
  [base options documents risk]
  {:title "提交风险复评" :path (str base "/risks/" (:id risk) "/review")
   :initial {:outcome "active"}
   :fields [{:key :outcome :label "复评结论" :type :select :required? true
             :options [{:value "active" :label "继续跟踪"} {:value "mitigated" :label "已采取缓解措施"} {:value "closed" :label "关闭风险"}]}
            {:key :review_note :label "复评依据" :type :textarea :required? true}
            {:key :next_review_date :label "下次复评日期" :type :date :hint "继续跟踪或缓解时必填,必须晚于今天.关闭风险时可留空."}
            (evidence-field documents) (reviewer-field options)]})


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


(defn meeting-dialog
  "记录实际会议结论与参会人."
  [base options]
  {:title "登记项目会议" :path (str base "/meetings")
   :fields [{:key :title :label "会议主题" :required? true}
            {:key :held_on :label "会议日期" :type :date :required? true}
            {:key :attendee_ids :label "参会人" :type :multi :options (w/user-options (:users options)) :required? true}
            {:key :minutes :label "会议纪要" :type :textarea :required? true}]})


(defn action-dialog
  "会议决定形成有负责人和到期日期的行动."
  [base options meeting]
  {:title "新增会议行动" :path (str base "/meetings/" (:id meeting) "/actions")
   :description (:title meeting)
   :fields [{:key :title :label "行动内容" :required? true} (owner-field (:users options))
            {:key :due_date :label "到期日期" :type :date :required? true}]})


(defn change-dialog
  "记录变更对五个工程维度的影响."
  [base]
  {:title "提出项目变更" :path (str base "/changes")
   :fields [{:key :title :label "变更标题" :required? true}
            {:key :reason :label "变更原因" :type :textarea :required? true}
            {:key :scope_impact :label "范围影响" :type :textarea :required? true}
            {:key :schedule_impact :label "进度影响" :type :textarea :required? true}
            {:key :cost_impact :label "成本影响" :type :textarea :required? true}
            {:key :quality_impact :label "质量影响" :type :textarea :required? true}
            {:key :resource_impact :label "资源影响" :type :textarea :required? true}]})


(defn template-dialog
  "将具体验收准则定义为逐项Gate检查."
  [base]
  {:title "建立Gate模板" :path (str base "/gate-templates") :initial {:stage "execution"}
   :transform (fn [data]
                (-> data (dissoc :check_titles) (assoc :required true
                                                       :checks (mapv (fn [i title] {:code (str "C" (inc i)) :title title :required true})
                                                                     (range) (remove str/blank? (str/split-lines (:check_titles data)))))))
   :fields [{:key :code :label "Gate编号" :required? true}
            {:key :title :label "Gate名称" :required? true}
            {:key :stage :label "控制阶段" :type :select :required? true
             :options [{:value "execution" :label "执行准入"} {:value "closure" :label "结项准出"}]}
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


(defn action-complete-dialog
  "会议行动完成须附结果说明, 真实证据与独立验证人, 关闭前经核验."
  [base options documents action]
  {:title "提交行动完成" :path (str base "/actions/" (:id action) "/complete")
   :description (str "行动: " (:title action))
   :fields [{:key :result :label "完成结果说明" :type :textarea :required? true}
            (evidence-field documents) (reviewer-field options)]})
