(ns com.ruoyi.domain.pms.governance
  "项目治理工作台的只读模型,类型化命令入口及生命周期条件."
  (:require
    [com.ruoyi.domain.pms.governance.appointment :as appointment]
    [com.ruoyi.domain.pms.governance.approval :as approval]
    [com.ruoyi.domain.pms.governance.collaboration :as collab]
    [com.ruoyi.domain.pms.governance.evidence :as evidence]
    [com.ruoyi.domain.pms.governance.gates :as gates]
    [com.ruoyi.domain.pms.governance.lifecycle :as lifecycle]
    [com.ruoyi.domain.pms.governance.reviews :as reviews]
    [com.ruoyi.domain.pms.governance.stakeholders :as stakeholders]
    [com.ruoyi.domain.pms.governance.store :as store]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r]))


(def sections
  "工作台集合与持久化对象类型的明确映射."
  {:charters "charter" :requirements "requirement" :documents "document" :traces "trace"
   :risks "risk" :issues "issue" :meetings "meeting" :actions "action" :changes "change"
   :gate_templates "gate-template" :gates "gate"
   :stakeholders "stakeholder" :raci "raci" :comm_plans "comm-plan"})


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
                                                     (= kind "change") approval/change-read-model)
                                                  (store/records q project kind))]))
                   actions-by-meeting (group-by :meeting_id (:actions data))
                   raci-loads (stakeholders/raci-r-loads (:raci data))
                   owner-loads (collab/owner-workloads (:issues data) (:risks data) (:actions data))
                   traceability (evidence/traceability-report (:requirements data) (:traces data))]
               (-> data
                   (update :meetings collab/enrich-meetings actions-by-meeting)
                   (update :raci #(mapv (partial stakeholders/raci-read-model raci-loads) %))
                   (update :issues #(mapv (partial collab/owner-workload-read-model owner-loads) %))
                   (update :risks #(mapv (partial collab/owner-workload-read-model owner-loads) %))
                   (update :actions #(mapv (partial collab/owner-workload-read-model owner-loads) %))
                   (collab/enrich-risk-issue-links)
                   (assoc :project_version (:version project) :blockers (blockers q project)
                          :appointments (appointment/list-summaries q project)
                          :raci_conflicts (stakeholders/conflicts q project)
                          :traceability traceability
                          :trace_summary (evidence/trace-summary traceability)
                          :document_collection (evidence/document-collection (:documents data))
                          :verification_coverage (evidence/verification-coverage (:requirements data))
                          :release_coverage (evidence/release-coverage (:documents data))
                          :risk_library collab/risk-library))))))


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
   [:issues :create] (creating collab/create-issue!)
   [:issues :reopen] reviews/reopen-issue!
   [:issues :reassign] collab/reassign-issue!
   [:issues :resolve] collab/resolve! [:issues :decision] collab/verify!
   [:issues :escalate] collab/acknowledge-issue-escalation!
   [:meetings :create] (creating collab/create-meeting!)
   [:meetings :actions] collab/create-action! [:actions :task] collab/materialize-action!
   [:actions :complete] collab/complete-action! [:actions :verify] collab/verify-action!
   [:gate-templates :create] (creating gates/create-template!)
   [:gates :create] (creating gates/create!) [:gates :checks] gates/checks!
   [:gates :submit] gates/submit! [:gates :decision] gates/decide!
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
   [:risks :from-library] (creating collab/from-library!)})


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
