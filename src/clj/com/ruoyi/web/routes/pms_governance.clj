(ns com.ruoyi.web.routes.pms-governance
  "项目治理资源与受控命令的明确路由."
  (:require
    [com.ruoyi.web.controllers.pms-governance :as controller]))


(defn- command-route
  "构造固定业务资源的POST命令."
  [svc path resource action]
  [path {:post {:handler (partial controller/command svc resource action)}}])


(defn governance-routes
  "挂载在已认证的/api/pms内,所有命令仍执行领域权限检查."
  [svc]
  [["/projects/:id/governance"
    ["" {:get {:handler (partial controller/workspace svc)}}]
    (command-route svc "/charters" :charters :create)
    (command-route svc "/charters/:record_id/revisions" :charters :revisions)
    (command-route svc "/charters/:record_id/submit" :charters :submit)
    (command-route svc "/charters/:record_id/decision" :charters :decision)
    (command-route svc "/requirements" :requirements :create)
    ["/requirements/preview" {:post {:handler (partial controller/preview svc)}}]
    (command-route svc "/requirements/import" :requirements :import)
    (command-route svc "/requirements/:record_id/revisions" :requirements :revisions)
    (command-route svc "/requirements/:record_id/discard" :requirements :discard)
    (command-route svc "/requirements/:record_id/restore" :requirements :restore)
    ["/requirements/:record_id/discard-preview" {:get {:handler (partial controller/discard-preview svc "requirement")}}]
    (command-route svc "/documents" :documents :create)
    (command-route svc "/documents/:record_id/revisions" :documents :revisions)
    (command-route svc "/documents/:record_id/submit" :documents :submit)
    (command-route svc "/documents/:record_id/decision" :documents :decision)
    (command-route svc "/documents/:record_id/discard" :documents :discard)
    (command-route svc "/documents/:record_id/restore" :documents :restore)
    ["/documents/:record_id/discard-preview" {:get {:handler (partial controller/discard-preview svc "document")}}]
    ["/documents/:record_id/content" {:get {:handler (partial controller/content svc)}}]
    ["/documents/:record_id/download" {:get {:handler (partial controller/download svc)}}]
    ["/documents/:record_id/preview" {:get {:handler (partial controller/preview svc)}}]
    ["/documents/:record_id/upload-revision" {:post {:handler (partial controller/upload-revision svc)}}]
    ["/documents/upload" {:post {:handler (partial controller/upload svc)}}]
    ["/documents/batch-download" {:post {:handler (partial controller/batch-download svc)}}]
    (command-route svc "/traces" :traces :create)
    (command-route svc "/risks" :risks :create)
    (command-route svc "/risks/:record_id/review" :risks :review)
    (command-route svc "/risks/:record_id/decision" :risks :decision)
    (command-route svc "/risks/:record_id/mitigate" :risks :mitigate)
    (command-route svc "/risks/:record_id/escalate" :risks :escalate)
    (command-route svc "/risks/:record_id/materialize" :risks :materialize)
    (command-route svc "/risks/from-library" :risks :from-library)
    (command-route svc "/issues" :issues :create)
    (command-route svc "/issues/:record_id/resolve" :issues :resolve)
    (command-route svc "/issues/:record_id/reopen" :issues :reopen)
    (command-route svc "/issues/:record_id/reassign" :issues :reassign)
    (command-route svc "/issues/:record_id/decision" :issues :decision)
    (command-route svc "/issues/:record_id/escalate" :issues :escalate)
    (command-route svc "/issues/:record_id/escalate-overdue" :issues :escalate-overdue)
    (command-route svc "/meetings" :meetings :create)
    (command-route svc "/meetings/:record_id/actions" :meetings :actions)
    (command-route svc "/actions/:record_id/task" :actions :task)
    (command-route svc "/actions/:record_id/complete" :actions :complete)
    (command-route svc "/actions/:record_id/verify" :actions :verify)
    (command-route svc "/changes" :changes :create)
    (command-route svc "/changes/:record_id/revisions" :changes :revisions)
    (command-route svc "/changes/:record_id/submit" :changes :submit)
    (command-route svc "/changes/:record_id/decision" :changes :decision)
    (command-route svc "/gate-templates" :gate-templates :create)
    (command-route svc "/gate-templates/from-catalog" :gate-templates :from-catalog)
    (command-route svc "/template-instances" :template-instances :create)
    (command-route svc "/dqs" :dqs :create)
    (command-route svc "/dqs/:record_id/checks" :dqs :checks)
    (command-route svc "/dqs/:record_id/submit" :dqs :submit)
    (command-route svc "/dqs/:record_id/decision" :dqs :decision)
    (command-route svc "/node-pauses" :node-pauses :create)
    (command-route svc "/node-pauses/:record_id/resume" :node-pauses :resume)
    (command-route svc "/gates" :gates :create)
    (command-route svc "/gates/:record_id/checks" :gates :checks)
    (command-route svc "/gates/:record_id/submit" :gates :submit)
    (command-route svc "/gates/:record_id/decision" :gates :decision)
    (command-route svc "/stakeholders" :stakeholders :create)
    (command-route svc "/stakeholders/:record_id/revisions" :stakeholders :revisions)
    (command-route svc "/stakeholders/:record_id/discard" :stakeholders :discard)
    (command-route svc "/stakeholders/:record_id/restore" :stakeholders :restore)
    ["/stakeholders/:record_id/discard-preview" {:get {:handler (partial controller/discard-preview svc "stakeholder")}}]
    (command-route svc "/raci" :raci :create)
    (command-route svc "/comm-plans" :comm-plans :create)
    (command-route svc "/comm-plans/:record_id/revisions" :comm-plans :revisions)
    (command-route svc "/comm-plans/:record_id/meeting" :comm-plans :meeting)
    (command-route svc "/comm-plans/:record_id/log" :comm-plans :log)
    (command-route svc "/appointments" :appointments :create)
    ["/appointments/:record_id/content" {:get {:handler (partial controller/appointment-content svc)}}]
    ["/appointments/:record_id/download" {:get {:handler (partial controller/appointment-download svc)}}]]])
