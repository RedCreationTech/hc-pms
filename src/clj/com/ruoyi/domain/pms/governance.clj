(ns com.ruoyi.domain.pms.governance
  "项目治理工作台的只读模型,类型化命令入口及生命周期条件."
  (:require
    [com.ruoyi.domain.pms.config.catalog :as catalog]
    [com.ruoyi.domain.pms.governance.appointment :as appointment]
    [com.ruoyi.domain.pms.governance.approval :as approval]
    [com.ruoyi.domain.pms.governance.collaboration :as collab]
    [com.ruoyi.domain.pms.governance.evidence :as evidence]
    [com.ruoyi.domain.pms.governance.gates :as gates]
    [com.ruoyi.domain.pms.governance.lifecycle :as lifecycle]
    [com.ruoyi.domain.pms.governance.quality :as quality]
    [com.ruoyi.domain.pms.governance.reviews :as reviews]
    [com.ruoyi.domain.pms.governance.stakeholders :as stakeholders]
    [com.ruoyi.domain.pms.governance.store :as store]
    [com.ruoyi.domain.pms.governance.templates :as templates]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r]))


(def sections
  "工作台集合与持久化对象类型的明确映射."
  {:charters "charter" :requirements "requirement" :documents "document" :traces "trace"
   :risks "risk" :issues "issue" :meetings "meeting" :actions "action" :changes "change"
   :gate_templates "gate-template" :gates "gate"
   :stakeholders "stakeholder" :raci "raci" :comm_plans "comm-plan"
   :template_instances "template-instance" :dqs "dq" :node_pauses "node-pause"})


(defn execution-ready!
  "供项目状态机验证正式章程和执行关口."
  [q project]
  (gates/execution-ready! q project))


(defn closure-ready!
  "供项目状态机验证验收关口和阻塞问题."
  [q project]
  (gates/closure-ready! q project))


(defn evidence-version!
  "提供项目范围内的真实不可变文本附件版本,用于正式验收."
  [q project id]
  (let [document (store/record! q project "document" id)]
    (when-not (and (= "registered" (:status document)) (seq (:sha256 document)))
      (r/fail! 409 "证据版本尚未登记"))
    document))


(defn approved-change!
  "供计划发布引用已独立批准的正式变更."
  [q project id]
  (approval/approved-change! q project id))


(defn blockers
  "返回生命周期前置缺口,不将未知异常伪装成业务条件."
  [q project]
  (into {} (for [[stage check] [[:execution execution-ready!] [:closure closure-ready!]]]
             [stage (try (check q project) []
                         (catch clojure.lang.ExceptionInfo e
                           (if (:pms-error (ex-data e)) [(.getMessage e)] (throw e))))])))


(defn workspace
  "返回当前项目治理对象版本列表及需求追踪矩阵,附件正文须经独立下载接口读取."
  [svc actor id]
  (k/read! svc actor id "pms:project:query"
           (fn [q project]
             (let [data (into {} (for [[section kind] sections]
                                   [section (mapv #(cond-> (dissoc % :content)
                                                     (= kind "risk") reviews/risk-read-model
                                                     (= kind "action") collab/action-read-model
                                                     (= kind "issue") collab/issue-read-model
                                                     (= kind "comm-plan") stakeholders/comm-plan-read-model
                                                     (= kind "stakeholder") stakeholders/stakeholder-read-model
                                                     (= kind "change") approval/change-read-model
                                         (= kind "gate") gates/gate-read-model)
                                                  (store/records q project kind))]))
                   actions-by-meeting (group-by :meeting_id (:actions data))
                   raci-loads (stakeholders/raci-r-loads (:raci data))
                   owner-loads (collab/owner-workloads (:issues data) (:risks data) (:actions data))
                   mitigation-rollup (collab/mitigation-rollup-by-risk (:actions data))
                   remediation-rollup (collab/remediation-rollup-by-dq (:actions data))
                   gate-remediation-rollup (collab/remediation-rollup-by-gate (:actions data))
                   docs-by-id (into {} (map (juxt :id identity)) (:documents data))
                   voided-codes (evidence/voided-document-codes docs-by-id)
                   traces-by-req (group-by :requirement_id (:traces data))
                   members (q :pms/members {:project_id (:project_id project)})
                   owner-name (into {} (map (fn [m] [(:user_id m) (or (not-empty (:nick_name m)) (not-empty (:user_name m)) (str "用户" (:user_id m)))]) members))
                   traceability (evidence/traceability-report (:requirements data) (:traces data))]
               (-> data
                   (update :meetings collab/enrich-meetings actions-by-meeting)
                   (update :meetings collab/flag-meeting-baselines (q :planning/baselines {:project_id (:project_id project)}))
                   (update :dqs #(mapv (partial quality/dq-read-model (:documents data)) %))
                   (update :dqs #(mapv (partial quality/dq-deliverable-voided-model voided-codes docs-by-id) %))
                   (update :dqs #(mapv (partial collab/dq-remediation-read-model remediation-rollup) %))
                   quality/attach-dq-summary
                   (update :raci #(mapv (partial stakeholders/raci-read-model raci-loads) %))
                   (update :issues #(mapv (partial collab/owner-workload-read-model owner-loads) %))
                   (update :risks #(mapv (partial collab/owner-workload-read-model owner-loads) %))
                   (update :risks #(mapv (partial collab/mitigation-read-model mitigation-rollup) %))
                   (update :actions #(mapv (partial collab/owner-workload-read-model owner-loads) %))
                   (update :traces #(mapv (partial evidence/trace-read-model docs-by-id) %))
                   (update :requirements #(mapv (partial evidence/requirement-trace-model traces-by-req docs-by-id) %))
                   (update :gates #(mapv (partial gates/gate-evidence-voided-model voided-codes docs-by-id) %))
                   (update :gates #(mapv (partial gates/gate-evidence-release-model docs-by-id) %))
                   (update :gates #(mapv (partial collab/gate-remediation-read-model gate-remediation-rollup) %))
                   gates/attach-gate-closure-summary
                   gates/attach-gate-exception-summary
                   gates/attach-gate-velocity-summary
                   collab/attach-issue-closure-summary
                   (collab/enrich-risk-issue-links)
                   (collab/enrich-action-source-links)
                   (assoc :project_version (:version project) :blockers (blockers q project)
                          :appointments (appointment/list-summaries q project)
                          :raci_conflicts (stakeholders/conflicts q project)
                          :traceability traceability
                          :trace_summary (evidence/trace-summary traceability)
                          :document_collection (evidence/document-collection (:documents data))
                          :document_tree (evidence/document-tree (:documents data))
                          :verification_coverage (evidence/verification-coverage (:requirements data))
                          :requirement_priority_distribution (evidence/requirement-priority-distribution (:requirements data))
                          :verification_evidence_alignment (evidence/verification-evidence-alignment (:requirements data) (:traces data) docs-by-id)
                          :release_coverage (evidence/release-coverage (:documents data))
                          :document_classification_distribution (evidence/document-classification-distribution (:documents data))
                          :risk_response_coverage (collab/risk-response-coverage (:risks data))
                          :risk_category_coverage (collab/risk-category-coverage (:risks data))
                          :stakeholder_engagement_coverage (stakeholders/engagement-coverage (:stakeholders data))
                          :stakeholder_engagement_matrix (stakeholders/engagement-assessment-matrix (:stakeholders data))
                          :issue_resolution_coverage (collab/issue-resolution-coverage (:issues data))
                          :risk_escalation_summary (collab/risk-escalation-disposition-summary (:risks data))
                          :risk_score_distribution (collab/risk-score-distribution (:risks data))
                          :risk_stage_distribution (collab/risk-stage-distribution (:risks data))
                          :risk_review_frequency_distribution (collab/risk-review-frequency-distribution (:risks data))
                          :risk_review_cadence (collab/risk-review-cadence-summary (:risks data))
                          :comm_cadence_summary (collab/comm-cadence-summary (:comm_plans data))
                          :comm_audience_coverage (stakeholders/comm-audience-coverage (:stakeholders data) (:comm_plans data))
                          :comm_execution_coverage (stakeholders/comm-execution-coverage (:comm_plans data))
                          :comm_channel_usage (stakeholders/comm-channel-usage (:comm_plans data))
                          :comm_audience_breadth (stakeholders/comm-plan-audience-breadth (:comm_plans data))
                          :stakeholder_category_distribution (stakeholders/stakeholder-category-distribution (:stakeholders data))
                          :stakeholder_quadrant_distribution (stakeholders/stakeholder-quadrant-distribution (:stakeholders data))
                          :stakeholder_engagement_distribution (stakeholders/stakeholder-engagement-distribution (:stakeholders data))
                          :raci_assignment_coverage (stakeholders/raci-assignment-completeness (:raci data))
                          :raci_engagement_coverage (stakeholders/raci-engagement-coverage (:raci data))
                          :raci_role_distribution (stakeholders/raci-role-distribution (:raci data))
                          :issue_escalation_summary (collab/issue-escalation-disposition-summary (:issues data))
                          :action_closure (collab/action-closure-summary (:actions data))
                          :action_priority_distribution (collab/action-priority-distribution (:actions data))
                          :project_remediation_overview (collab/project-remediation-overview (:actions data) (:issues data))
                          :due_workload_overview (collab/due-workload-overview (:risks data) (:issues data) (:actions data))
                          :owner_due_pressure (collab/owner-due-pressure (:risks data) (:issues data) (:actions data) owner-name)
                          :meeting_release_coverage (collab/meeting-release-coverage (:meetings data))
                          :meeting_material_readiness (collab/meeting-material-readiness (:meetings data) docs-by-id)
                          :meeting_attendance_summary (collab/meeting-attendance-summary (:meetings data) members)
                          :meeting_cadence (collab/meeting-cadence-summary (:meetings data))
                          :meeting_type_distribution (collab/meeting-type-distribution (:meetings data))
                          :node_pause_summary (quality/node-pause-summary (:node_pauses data) nil)
                          :change_closure_summary (approval/change-closure-summary (:changes data))
                          :ccb_participation (approval/ccb-participation-summary (:changes data))
                          :change_type_coverage (approval/change-type-coverage (:changes data))
                          :change_impact_coverage (approval/change-impact-coverage (:changes data))
                          :change_impact_pattern (approval/change-impact-pattern (:changes data))
                          :change_impact_magnitude (approval/change-impact-magnitude (:changes data))
                          :gate_progress (gates/gate-progress (:gate_templates data) (:gates data))
                          :gate_catalog (mapv #(select-keys % [:gate_type :title :stage :page :blocks]) catalog/gate-types)
                          :risk_library collab/risk-library
                          :risk_templates (collab/risk-templates q project)))))))


(defn- creating
  "将创建服务适配为统一的明确命令签名."
  [f]
  (fn [svc actor id _ body] (f svc actor id body)))


(defn- approval-command
  "为固定对象类型适配章程或变更命令."
  [f kind create?]
  (fn [svc actor id rid body]
    (if create? (f svc actor id kind body) (f svc actor id kind rid body))))


(def commands
  "仅开放已实现状态机命令,不暴露任意类型CRUD."
  {[:charters :create] (approval-command approval/create! "charter" true)
   [:charters :revisions] (approval-command approval/revise! "charter" false)
   [:charters :submit] (approval-command approval/submit! "charter" false)
   [:charters :decision] (approval-command approval/decide! "charter" false)
   [:changes :create] (approval-command approval/create! "change" true)
   [:changes :revisions] (approval-command approval/revise! "change" false)
   [:changes :submit] (approval-command approval/submit! "change" false)
   [:changes :decision] (approval-command approval/decide! "change" false)
   [:changes :escalation] approval/acknowledge-change-escalation!
   [:changes :ccb] approval/set-ccb!
   [:changes :ballot] approval/cast-ccb-ballot!
   [:requirements :create] (approval-command evidence/create! "requirement" true)
   [:requirements :revisions] (approval-command evidence/revise! "requirement" false)
   [:requirements :import] (creating evidence/import!)
   [:requirements :discard] (approval-command lifecycle/discard! "requirement" false)
   [:requirements :restore] (approval-command lifecycle/restore! "requirement" false)
   [:documents :create] (approval-command evidence/create! "document" true)
   [:documents :revisions] (approval-command evidence/revise! "document" false)
   [:documents :submit] evidence/submit-release! [:documents :decision] evidence/decide-release!
   [:documents :discard] (approval-command lifecycle/discard! "document" false)
   [:documents :restore] (approval-command lifecycle/restore! "document" false)
   [:traces :create] (creating evidence/trace!)
   [:risks :create] (creating collab/create-risk!)
   [:risks :review] reviews/submit-risk-review! [:risks :decision] reviews/decide-risk-review!
   [:risks :mitigate] collab/mitigate!
   [:risks :escalate] collab/acknowledge-escalation!
   [:risks :materialize] collab/materialize!
   [:risks :mitigation-action] collab/mitigation-action!
   [:issues :create] (creating collab/create-issue!)
   [:issues :reopen] reviews/reopen-issue!
   [:issues :reassign] collab/reassign-issue!
   [:issues :resolve] collab/resolve! [:issues :decision] collab/verify!
   [:issues :escalate] collab/acknowledge-issue-escalation!
   [:issues :escalate-overdue] collab/escalate-overdue!
   [:meetings :create] (creating collab/create-meeting!)
   [:meetings :submit] collab/submit-meeting!
   [:meetings :decision] collab/decide-meeting!
   [:meetings :discard] (approval-command lifecycle/discard! "meeting" false)
   [:meetings :restore] (approval-command lifecycle/restore! "meeting" false)
   [:meetings :actions] collab/create-action! [:actions :task] collab/materialize-action!
   [:actions :complete] collab/complete-action! [:actions :verify] collab/verify-action!
   [:actions :reopen] collab/reopen-action!
   [:gate-templates :create] (creating gates/create-template!)
   [:gate-templates :from-catalog] (creating gates/from-catalog!)
   [:template-instances :create] (creating templates/instantiate!)
   [:dqs :create] (creating quality/create-dq!) [:dqs :checks] quality/check-dq!
   [:dqs :submit] quality/submit-dq! [:dqs :decision] quality/decide-dq!
   [:dqs :remediation-action] quality/remediation-action!
   [:dqs :remediation-actions] quality/remediation-actions!
   [:node-pauses :create] (creating quality/pause-node!) [:node-pauses :resume] quality/resume-node!
   [:gates :create] (creating gates/create!) [:gates :checks] gates/checks!
   [:gates :submit] gates/submit! [:gates :decision] gates/decide!
   [:gates :remediation-action] gates/remediation-action!
   [:gates :remediation-actions] gates/remediation-actions!
   [:appointments :create] (creating appointment/issue!)
   [:stakeholders :create] (creating stakeholders/create-stakeholder!)
   [:stakeholders :revisions] stakeholders/revise-stakeholder!
   [:stakeholders :discard] (approval-command lifecycle/discard! "stakeholder" false)
   [:stakeholders :restore] (approval-command lifecycle/restore! "stakeholder" false)
   [:raci :create] (creating stakeholders/create-raci!)
   [:comm-plans :create] (creating stakeholders/create-comm-plan!)
   [:comm-plans :revisions] stakeholders/revise-comm-plan!
   [:comm-plans :meeting] stakeholders/materialize-meeting!
   [:comm-plans :log] stakeholders/log-communication!
   [:risks :from-library] (creating collab/from-library!)
   [:risk-templates :create] (creating collab/create-risk-template!)
   [:risk-templates :update] collab/update-risk-template!
   [:risk-templates :discard] collab/discard-risk-template!
   [:risks :from-custom-template] (creating collab/from-custom-template!)
   [:actions :from-variance] (creating collab/variance-action!)})


(defn command!
  "执行路由白名单中的业务命令."
  [svc actor id resource action rid body]
  (if-let [f (get commands [resource action])]
    (f svc actor id rid body)
    (r/fail! 404 "治理命令不存在")))


(defn preview
  "返回CSV逐行预检结果且不写入."
  [svc actor id body]
  (evidence/preview svc actor id body))


(defn document-content
  "读取实际存储的确定文档版本内容."
  [svc actor id rid]
  (evidence/content svc actor id rid))


(defn document-batch
  "读取同项目多个确定文档版本正文, 供HTTP层打包批量下载."
  [svc actor id body]
  (evidence/batch-content svc actor id body))


(defn document-upload
  "以 multipart 上传的真实文件登记证据文档首版 (C05 二进制附件)."
  [svc actor id body file]
  (evidence/upload! svc actor id body file))


(defn document-upload-revision
  "以 multipart 上传的真实文件新增不可变修订."
  [svc actor id rid body file]
  (evidence/upload-revision! svc actor id rid body file))


(defn document-bytes
  "读取确定文档版本的完整字节 (二进制证据复核 SHA256), 供HTTP层下载/预览."
  [svc document]
  (evidence/file-bytes svc document))


(defn discard-preview
  "只读预览对某记录发起受控作废将命中的状态门控与级联引用清单, 不改变任何状态."
  [svc actor id kind rid]
  (lifecycle/discard-preview svc actor id kind rid))


(defn appointment-content
  "读取确定任命书版本的完整正文与团队快照."
  [svc actor id rid]
  (appointment/content svc actor id rid))


(defn member-removal-blockers
  "撤销成员前检查待办审批和开放问题的责任,避免失去处理资格."
  [q project user-id]
  (let [reviews (mapcat #(store/records q project %) ["charter" "change" "issue" "gate" "risk"])
        issues (store/records q project "issue")]
    (cond-> []
      (some #(and (= user-id (:reviewer_id %))
                  (or (= "in_review" (:status %))
                      (and (= "gate" (:kind %)) (contains? #{"draft" "ready" "rejected"} (:status %))))) reviews)
      (conj "该成员仍是待办审核或未完成Gate的指定审核人")
      (some #(and (= user-id (:owner_id %)) (not= "closed" (:status %))) issues)
      (conj "该成员仍负责未验证关闭的问题"))))
