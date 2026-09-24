(ns com.ruoyi.web.routes.pms-delivery
  "物料,装配,试验,发运与售后执行的受认证业务路由."
  (:require [com.ruoyi.web.controllers.pms-delivery :as c]))


(defn- command
  "构造不可扩展为任意类型CRUD的明确POST路径."
  [svc path resource action]
  [path {:post {:handler (partial c/command svc resource action)}}])


(defn delivery-routes
  "返回挂载在已认证/api/pms内的路由集合."
  [svc]
  [["/projects/:id/delivery"
    ["" {:get {:handler (partial c/workspace svc)}}]
    (command svc "/configuration" :configuration :update)
    (command svc "/material-requests" :material-requests :create)
    (command svc "/material-requests/:record_id/submit" :material-requests :submit)
    (command svc "/material-requests/:record_id/decision" :material-requests :decision)
    (command svc "/boms" :boms :create)
    (command svc "/boms/:record_id/freeze" :boms :freeze)
    (command svc "/boms/:record_id/decision" :boms :decision)
    (command svc "/boms/:record_id/kit" :boms :kit)
    (command svc "/assemblies" :assemblies :create)
    (command svc "/assemblies/:record_id/start" :assemblies :start)
    (command svc "/assemblies/:record_id/submit" :assemblies :submit)
    (command svc "/assemblies/:record_id/decision" :assemblies :decision)
    (command svc "/assemblies/:record_id/steps" :assemblies :steps)
    (command svc "/surveys" :surveys :create)
    (command svc "/surveys/:record_id/submit" :surveys :submit)
    (command svc "/surveys/:record_id/decision" :surveys :decision)
    (command svc "/shipments/:record_id/conditions" :shipments :conditions)
    (command svc "/handovers/:record_id/complete" :handovers :complete)
    (command svc "/site-tasks/:record_id/start" :site-tasks :start)
    (command svc "/site-tasks/:record_id/complete" :site-tasks :complete)
    (command svc "/tests" :tests :create)
    (command svc "/tests/:record_id/results" :tests :results)
    (command svc "/tests/:record_id/submit" :tests :submit)
    (command svc "/tests/:record_id/decision" :tests :decision)
    (command svc "/shipments" :shipments :create)
    (command svc "/shipments/:record_id/submit" :shipments :submit)
    (command svc "/shipments/:record_id/decision" :shipments :decision)
    (command svc "/shipments/:record_id/dispatch" :shipments :dispatch)
    (command svc "/shipments/:record_id/receipt" :shipments :receipt)
    (command svc "/service-cases" :service-cases :create)
    (command svc "/service-cases/:record_id/resolve" :service-cases :resolve)
    (command svc "/service-cases/:record_id/decision" :service-cases :decision)]])
