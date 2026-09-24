(ns com.ruoyi.domain.pms.governance.collaboration
  "项目风险问题的验证关闭及会议行动转真实计划任务."
  (:require
    [com.ruoyi.domain.pms.governance.reviews :as reviews]
    [com.ruoyi.domain.pms.governance.risk-assessment :as ra]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(def risk-response-strategies
  "风险应对策略枚举: 规避/转移/减轻/接受."
  #{"avoid" "transfer" "mitigate" "accept"})


(defn- insert-risk!
  "写入风险记录: 统一按概率 x 影响评分, 达阈值自动标记超阈值升级, 可选携带阶段与风险库来源信息; 评分与升级判定共用 risk-assessment 纯函数."
  [q project actor fields]
  (let [probability (ra/score! (:probability fields))
        impact (ra/score! (:impact fields))]
    (s/insert! q project actor "risk"
               (into (ra/assessment probability impact)
                     (cond-> {:title (s/text! fields :title 200)
                              :owner_id (k/user! q project (:owner_id fields) "负责人")
                              :mitigation (s/text! fields :mitigation)
                              :due_date (s/date! fields :due_date)}
                       (:stage fields) (assoc :stage (:stage fields))
                       (:source_key fields) (assoc :source_key (:source_key fields))
                       (:source_category fields) (assoc :source_category (:source_category fields))
                       (:response_strategy fields) (assoc :response_strategy
                                                          (s/enum! (:response_strategy fields) risk-response-strategies "应对策略"))))
               {:status "open"})))


(defn create-risk!
  "登记有明确责任人与预防措施的风险; 评分超阈值时自动标记升级待独立确认."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.created"
             (fn [q project]
               (s/input! body [:title :probability :impact :owner_id :mitigation :due_date :response_strategy])
               (insert-risk! q project actor body))))


(defn mitigate!
  "记录风险措施实际执行证据并保留其后转问题的能力; 超阈值升级未确认前不得自行缓解."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.mitigated"
             (fn [q project]
               (s/input! body [:mitigation :evidence_ids])
               (let [risk (s/record! q project "risk" rid)]
                 (s/status! risk #{"open" "mitigated"})
                 (when (and (:escalated risk) (= "pending" (:escalation_state risk)))
                   (r/fail! 409 "该风险已超阈值升级, 请先由独立质量审批人确认处置措施再缓解"))
                 (s/change! q project risk "mitigated"
                            {:mitigation (s/text! body :mitigation)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)})))))


(defn acknowledge-escalation!
  "由独立质量审批人确认超阈值风险的升级处置, 批准责成处置或经评估豁免, 记录后方可继续缓解."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "risk.escalation-acknowledged" {:write? false}
             (fn [q project]
               (s/input! body [:decision :note])
               (let [risk (s/record! q project "risk" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
                     note (s/text! body :note 500)]
                 (when-not (:escalated risk) (r/fail! 409 "该风险未触发升级, 无需确认"))
                 (when-not (= "pending" (:escalation_state risk)) (r/fail! 409 "风险升级已确认, 请勿重复处理"))
                 (when (= (:user_id actor) (:created_by risk)) (r/fail! 403 "升级确认不得由风险登记人本人完成"))
                 (s/change! q project risk (:status risk)
                            {:escalation_state (if (= "approved" decision) "acknowledged" "waived")
                             :escalation_decision decision :escalation_note note
                             :escalation_ack_by (:user_id actor) :escalation_ack_on (str (LocalDate/now))
                             :workflow_history (conj (vec (:workflow_history risk))
                                                     {:action "escalation_acknowledged" :actor_id (:user_id actor)
                                                      :on (str (LocalDate/now)) :decision decision})})))))


(def risk-library
  "内置典型风险库: 沉淀常见风险的标准类别, 概率, 影响, 应对措施与适用阶段, 供项目一键实例化并复用超阈值升级门控."
  [{:key "schedule-delay" :category "schedule" :title "关键路径进度延误"
    :probability 4 :impact 4 :mitigation "预留进度缓冲, 按周跟踪关键路径并及早纠偏" :stage "执行"}
   {:key "supply-outage" :category "supply" :title "关键物料断供"
    :probability 5 :impact 5 :mitigation "启用备选供应商并加严来料检验, 提前锁定安全库存" :stage "采购"}
   {:key "tech-uncertainty" :category "technical" :title "关键技术方案不成熟"
    :probability 3 :impact 3 :mitigation "先做技术验证原型, 预留备选技术方案" :stage "设计"}
   {:key "cost-overrun" :category "cost" :title "项目成本超支"
    :probability 4 :impact 5 :mitigation "建立挣值监控, 变更须走成本影响评估审批" :stage "执行"}
   {:key "staff-turnover" :category "other" :title "关键人员流失"
    :probability 2 :impact 3 :mitigation "关键岗位设置 AB 角并做好知识文档化" :stage "全周期"}])


(defn- library-entry
  "按 key 查找内置风险库条目, 未命中返回 404."
  [template-key]
  (or (first (filter #(= (:key %) template-key) risk-library))
      (r/fail! 404 "风险库中不存在该典型风险")))


(defn from-library!
  "从内置典型风险库选用一条, 按当前责任人与期限实例化为真实风险, 继承评分并复用超阈值自动升级."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.created"
             (fn [q project]
               (s/input! body [:template_key :owner_id :due_date])
               (let [entry (library-entry (:template_key body))]
                 (insert-risk! q project actor
                               (assoc entry :owner_id (:owner_id body) :due_date (:due_date body)
                                      :source_key (:key entry)
                                      :source_category (:category entry)))))))


(defn- issue-fields!
  "校验问题内容,严重度,责任人与解决期限."
  [q project body]
  (s/input! body [:title :severity :owner_id :due_date])
  {:title (s/text! body :title 200)
   :severity (s/enum! (:severity body) #{"blocker" "major" "minor"} "severity")
   :owner_id (k/user! q project (:owner_id body) "负责人") :due_date (s/date! body :due_date)})


(defn- issue-escalation
  "按严重度与到期日计算问题自动升级处置: 阻断级(blocker)问题登记即升级到经理层, 若登记时已逾期则升到管理层; 其它严重度不触发升级(返回 nil, 不写任何 escalation 键, 与既有问题用例兼容)."
  [{:keys [severity due_date]}]
  (when (= "blocker" severity)
    (let [overdue? (and due_date (not (.isAfter (LocalDate/parse due_date) (LocalDate/now))))
          level (if overdue? "steering" "management")]
      {:escalated true
       :escalation_state "pending"
       :escalation_level level
       :escalation_reason (str "阻断级问题" (when overdue? "且登记时已逾期")
                               ", 须由独立质量审批人确认" (if (= level "steering") "管理层" "经理层") "处置后方可提交解决.")})))


(defn create-issue!
  "创建需经过独立验证才能关闭的问题; 阻断级问题登记即自动升级, 待独立质量审批人确认处置后方可提交解决."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.created"
             (fn [q project]
               (let [fields (issue-fields! q project body)]
                 (s/insert! q project actor "issue" (merge fields (issue-escalation fields)) {:status "open"})))))


(defn materialize!
  "风险发生时幂等生成问题并保留双向来源关联."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.materialized"
             (fn [q project]
               (s/input! body [:title])
               (let [risk (s/record! q project "risk" rid)]
                 (if-let [target (:issue_id risk)]
                   (s/record! q project "issue" target)
                   (do
                     (s/status! risk #{"open" "mitigated"})
                     (let [issue (s/insert! q project actor "issue"
                                            {:title (or (not-empty (s/optional-text! body :title 200)) (:title risk))
                                             :owner_id (:owner_id risk) :due_date (:due_date risk)
                                             :severity (if (>= (:impact risk) 4) "blocker" "major")
                                             :source_risk_id rid} {:status "open"})]
                       (s/change! q project risk "materialized" {:issue_id (:id issue)})
                       issue)))))))


(defn resolve!
  "提交整改内容和确切证据版本,指定独立验证人."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.resolution-submitted"
             (fn [q project]
               (s/input! body [:resolution :evidence_ids :reviewer_id])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (when (and (:escalated issue) (= "pending" (:escalation_state issue)))
                   (r/fail! 409 "该问题已超阈值升级, 请先由独立质量审批人确认处置措施再提交解决"))
                 (s/change! q project issue "in_review"
                            {:review_action "closure" :resolution (s/text! body :resolution)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)
                             :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)})))))


(defn verify!
  "指定独立人员验证整改关闭,或批准有理由的受控重开."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "issue.verified" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [issue (s/record! q project "issue" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! issue #{"in_review"})
                 (s/decision-actor! actor issue)
                 (if (= "reopen" (:review_action issue))
                   (reviews/decide-reopening! q project actor issue body)
                   (do
                     (s/evidence! q project (:evidence_ids issue) true)
                     (s/change! q project issue (if (= decision "approved") "closed" "rejected")
                                {:verification_reason (s/text! body :reason) :verified_by (:user_id actor)})))))))


(defn reassign-issue!
  "转派问题责任人, 保留原责任人与转派原因供审计, 新责任人须为当前项目成员."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.reassigned"
             (fn [q project]
               (s/input! body [:owner_id :reason])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (s/change! q project issue (:status issue)
                            {:owner_id (k/user! q project (:owner_id body) "新责任人")
                             :reassigned_from (:owner_id issue)
                             :reassign_reason (s/text! body :reason 500)
                             :reassigned_by (:user_id actor)})))))


(defn acknowledge-issue-escalation!
  "由独立质量审批人确认阻断级问题的升级处置, 批准责成处置或经评估豁免, 记录后方可提交解决."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "issue.escalation-acknowledged" {:write? false}
             (fn [q project]
               (s/input! body [:decision :note])
               (let [issue (s/record! q project "issue" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
                     note (s/text! body :note 500)]
                 (when-not (:escalated issue) (r/fail! 409 "该问题未触发升级, 无需确认"))
                 (when-not (= "pending" (:escalation_state issue)) (r/fail! 409 "问题升级已确认, 请勿重复处理"))
                 (when (= (:user_id actor) (:created_by issue)) (r/fail! 403 "升级确认不得由问题登记人本人完成"))
                 (s/change! q project issue (:status issue)
                            {:escalation_state (if (= "approved" decision) "acknowledged" "waived")
                             :escalation_decision decision :escalation_note note
                             :escalation_ack_by (:user_id actor) :escalation_ack_on (str (LocalDate/now))
                             :workflow_history (conj (vec (:workflow_history issue))
                                                     {:action "escalation_acknowledged" :actor_id (:user_id actor)
                                                      :on (str (LocalDate/now)) :decision decision})})))))


(def meeting-types
  "会议类型: 常规/启动会/评审会/FAT启动会/FAT总结会."
  #{"regular" "kickoff" "review" "fat-kickoff" "fat-summary"})


(defn flag-meeting-baselines
  "只读标注会议引用的基线是否仍是当前最新已批准基线 (引用版本失效校验), 不改状态."
  [meetings baselines]
  (let [approved (last (sort-by :plan_revision (filter #(= "approved" (:status %)) baselines)))
        by-id (into {} (map (juxt :baseline_id identity) baselines))]
    (mapv (fn [meeting]
            (if-let [bid (:baseline_id meeting)]
              (let [current (get by-id bid)]
                (assoc meeting :baseline_current_status (:status current)
                       :baseline_stale (boolean (and approved (not= bid (:baseline_id approved))))))
              meeting))
          meetings)))


(defn create-meeting!
  "持久化项目会议纪要,有效参会人员,可选会前资料(真实文档版本), 会议类型与主计划基线引用."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "meeting.recorded"
             (fn [q project]
               (s/input! body [:title :held_on :minutes :attendee_ids :material_ids :meeting_type :baseline_id])
               (when-not (and (vector? (:attendee_ids body)) (<= 1 (count (:attendee_ids body)) 100))
                 (r/fail! 400 "参会人员必须为1到100人的数组"))
               (let [type (s/enum! (or (:meeting_type body) "regular") meeting-types "meeting_type")
                     materials (s/evidence! q project (or (:material_ids body) []) false)
                     baseline (when (seq (:baseline_id body))
                                (or (q :planning/baseline {:project_id (:project_id project) :baseline_id (:baseline_id body)})
                                    (r/fail! 404 "计划基线不存在或不属于本项目")))]
                 ;; B05: 启动会必须携带会前包 (售前/需求资料版本) 并引用主计划基线, 形成强制关联.
                 (when (= "kickoff" type)
                   (when (empty? materials) (r/fail! 409 "启动会必须绑定售前/需求资料作为会前包"))
                   (when-not baseline (r/fail! 409 "启动会必须引用主计划基线")))
                 (s/insert! q project actor "meeting"
                            (cond-> {:title (s/text! body :title 200) :held_on (s/date! body :held_on)
                                     :minutes (s/text! body :minutes 20000) :meeting_type type
                                     :attendee_ids (vec (distinct (map #(s/user! q %) (:attendee_ids body))))
                                     :material_ids materials}
                              baseline (assoc :baseline_id (:baseline_id baseline) :baseline_revision (:plan_revision baseline)
                                              :baseline_status (:status baseline)))
                            {:status "recorded"})))))


(defn create-action!
  "从确定会议派生有责任人和期限的行动项."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.created"
             (fn [q project]
               (s/input! body [:title :owner_id :due_date])
               (s/record! q project "meeting" rid)
               (s/insert! q project actor "action"
                          {:title (s/text! body :title 200) :owner_id (k/user! q project (:owner_id body) "负责人")
                           :due_date (s/date! body :due_date) :meeting_id rid}
                          {:status "open"}))))


(defn materialize-action!
  "在同一事务中幂等创建真实WBS任务,保留来源及目标ID."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.task-created"
             (fn [q project]
               (s/input! body [:start_date :duration_days :wbs_code])
               (let [action (s/record! q project "action" rid)]
                 (if-let [target (:target_task_id action)]
                   (do
                     (when-not (q :planning/task {:project_id (:project_id project) :task_id target})
                       (r/fail! 409 "已关联任务不存在,需要修复任务引用"))
                     {:target_task_id target :action action})
                   (let [task (planning/create-task-record!
                                q project actor
                                (cond-> {:name (:title action) :owner_id (:owner_id action)
                                         :start_date (or (:start_date body) (:due_date action))
                                         :duration_days (or (:duration_days body) 1)
                                         :description (str "会议行动项 " rid)
                                         :source_type "meeting_action" :source_id rid}
                                  (:wbs_code body) (assoc :wbs_code (:wbs_code body))))
                         target (:task_id task)]
                     (when-not target (r/fail! 500 "任务服务未返回有效任务ID"))
                     {:target_task_id target
                      :action (s/change! q project action "converted" {:target_task_id target})}))))))


(defn complete-action!
  "会议行动完成须提交结果说明与真实证据并指定独立验证人, 保留未转任务行动的追踪闭环."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.completion-submitted"
             (fn [q project]
               (s/input! body [:result :evidence_ids :reviewer_id])
               (let [action (s/record! q project "action" rid)]
                 (s/status! action #{"open" "rejected"})
                 (s/change! q project action "in_review"
                            {:review_action "action_closure"
                             :result (s/text! body :result 2000)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)
                             :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)})))))


(defn verify-action!
  "由指定独立审核人核验会议行动完成, 批准关闭或驳回退回负责人."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "action.verified" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [action (s/record! q project "action" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! action #{"in_review"})
                 (s/decision-actor! actor action)
                 (s/evidence! q project (:evidence_ids action) true)
                 (s/change! q project action (if (= decision "approved") "closed" "rejected")
                            {:verification_reason (s/text! body :reason 2000)
                             :verified_by (:user_id actor)})))))


(defn action-overdue?
  "行动存在到期日且未关闭未转任务且到期日不晚于服务器当天即视为逾期."
  [action]
  (boolean (and (:due_date action)
                (not (contains? #{"closed" "converted"} (:status action)))
                (not (.isAfter (LocalDate/parse (:due_date action)) (LocalDate/now))))))


(def due-soon-days
  "未决事项到期日距服务器当天不超过该天数(不含当天)即视为临期, 供到期倒计时提前关注."
  3)


(defn- days-until
  "到期日相对服务器当天的剩余天数; 负值表示已逾期天数, 空日期返回 nil. 只读派生不落库."
  [due]
  (when (some? due) (- (.toEpochDay (LocalDate/parse due)) (.toEpochDay (LocalDate/now)))))


(defn action-read-model
  "以服务器日期展示会议行动是否逾期未完成及剩余到期天数; 已关闭或已转真实任务的行动不再计逾期与倒计时."
  [action]
  (let [done? (contains? #{"closed" "converted"} (:status action))
        days (when-not done? (days-until (:due_date action)))]
    (assoc action
           :action_overdue (action-overdue? action)
           :action_due_in_days days
           :action_due_soon (boolean (and (some? days) (<= 1 days due-soon-days))))))


(defn enrich-meetings
  "为会议行汇总其派生行动的闭环情况: 行动总数/未完成(排除已关闭与已转真实任务)/其中逾期, 只读计算不改状态."
  [meetings actions-by-meeting]
  (mapv (fn [meeting]
          (let [acts (get actions-by-meeting (:id meeting) [])
                open (filterv #(not (contains? #{"closed" "converted"} (:status %))) acts)
                overdue (filterv action-overdue? open)]
            (assoc meeting :meeting_action_total (count acts)
                             :meeting_open_actions (count open)
                             :meeting_overdue_actions (count overdue))))
        meetings))


(defn issue-read-model
  "以服务器日期展示问题是否逾期未关闭, 标记阻断级严重度, 并给出剩余到期天数与临期提示供台账倒计时."
  [issue]
  (let [closed? (= "closed" (:status issue))
        days (when-not closed? (days-until (:due_date issue)))]
    (assoc issue
           :issue_overdue (boolean (and (:due_date issue)
                                        (not closed?)
                                        (not (.isAfter (LocalDate/parse (:due_date issue)) (LocalDate/now)))))
           :issue_critical (= "blocker" (:severity issue))
           :issue_due_in_days days
           :issue_due_soon (boolean (and (some? days) (<= 1 days due-soon-days))))))


(def owner-workload-threshold
  "同一责任人跨问题/风险/行动承担的未关闭事项数达到该值即视为负载过重."
  4)


(defn owner-workloads
  "跨问题/风险/行动统计每位责任人当前未关闭的事项数(问题与风险排除 closed, 行动排除 closed/converted), 供负载与过载预警. 只读派生不落库."
  [issues risks actions]
  (frequencies
    (keep :owner_id
          (concat
            (remove #(= "closed" (:status %)) issues)
            (remove #(= "closed" (:status %)) risks)
            (remove #(contains? #{"closed" "converted"} (:status %)) actions)))))


(defn owner-workload-read-model
  "为问题/风险/行动行补充其责任人跨类未关闭负载与是否过载; 只读计算不改状态, 无责任人则负载 0 且不过载."
  [loads row]
  (let [load (get loads (:owner_id row) 0)]
    (assoc row
           :owner_open_load load
           :owner_overloaded (boolean (and (:owner_id row) (>= load owner-workload-threshold))))))


(defn enrich-risk-issue-links
  "读取时把已持久化的风险<->问题双向来源关联互相标注对方标题, 供台账可见; 只读派生不落库.
   issue.source_risk_id -> issue_source_risk_id/issue_source_risk_title; risk.issue_id -> risk_issue_id/risk_issue_title; 对端记录缺失时标题为 nil."
  [data]
  (let [risk-by-id (into {} (map (juxt :id identity)) (:risks data))
        issue-by-id (into {} (map (juxt :id identity)) (:issues data))]
    (-> data
        (update :issues #(mapv (fn [issue]
                                 (if-let [rid (:source_risk_id issue)]
                                   (assoc issue :issue_source_risk_id rid
                                                  :issue_source_risk_title (:title (get risk-by-id rid)))
                                   issue))
                               %))
        (update :risks #(mapv (fn [risk]
                                (if-let [iid (:issue_id risk)]
                                  (assoc risk :risk_issue_id iid
                                                 :risk_issue_title (:title (get issue-by-id iid)))
                                  risk))
                              %)))))
