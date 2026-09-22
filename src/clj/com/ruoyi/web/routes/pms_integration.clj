(ns com.ruoyi.web.routes.pms-integration
  "项目接口运维和受控重开路由,由根路由统一执行JWT认证."
  (:require [com.ruoyi.domain.pms.integration :as integration]
            [com.ruoyi.domain.pms.integration.inbox :as inbox]
            [com.ruoyi.domain.pms.integration.outbox :as outbox]
            [com.ruoyi.domain.pms.integration.dispatch :as dispatch]
            [com.ruoyi.domain.pms.reopen :as reopen]
            [com.ruoyi.web.controllers.pms-finance :as http]
            [com.ruoyi.web.controllers.pms-http :as common]))

(defn- command
  "绑定固定命令参数,不开放动态执行名称."
  [svc f keys]
  {:handler (partial http/command svc f keys)})

(defn- detail
  "读取有单独敏感数据授权的消息正文."
  [svc request]
  (common/invoke svc request
    (fn [service actor]
      (outbox/detail service actor (common/project-id request) (common/param request :message_id)))))

(defn integration-routes
  "返回接口运维和重开申请的子路由集合."
  [svc]
  [["/projects/:id/integration" {:get {:handler (partial http/query svc integration/workspace)}}]
   ["/projects/:id/integration/inbox" {:post (command svc inbox/receive! [])}]
   ["/projects/:id/integration/outbox" {:post (command svc outbox/enqueue! [])}]
   ["/projects/:id/integration/outbox/:message_id" {:get {:handler (partial detail svc)}}]
   ["/projects/:id/integration/outbox/:message_id/deliver" {:post (command svc dispatch/deliver! [:message_id])}]
   ["/projects/:id/integration/outbox/:message_id/retry" {:post (command svc dispatch/retry! [:message_id])}]
   ["/projects/:id/reopen-requests" {:post (command svc reopen/request! [])}]
   ["/projects/:id/reopen-requests/:request_id/review" {:post (command svc reopen/review! [:request_id])}]])
