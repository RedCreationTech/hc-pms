(ns com.ruoyi.web.routes.pms-planning
  "可挂载在/api/pms下的规划功能路由."
  (:require [com.ruoyi.web.controllers.pms-planning :as controller]))

(defn- endpoint
  "注入共享PMS服务到控制器."
  [svc handler]
  {:handler (partial handler svc)})

(defn planning-routes
  "返回可拼接的任务,资源,日历与基线审批路由."
  [svc]
  [["/projects/:id/planning" {:get (endpoint svc controller/read-plan)}]
   ["/projects/:id/planning/submit" {:post (endpoint svc controller/submit-plan)}]
   ["/projects/:id/planning/baselines/:baseline_id" {:get (endpoint svc controller/read-baseline)}]
   ["/projects/:id/planning/baselines/:baseline_id/diff" {:get (endpoint svc controller/baseline-diff)}]
   ["/projects/:id/planning/baselines/:baseline_id/review" {:post (endpoint svc controller/review-plan)}]
   ["/projects/:id/planning/derive" {:post (endpoint svc controller/derive-network)}]
   ["/projects/:id/planning/nodes/:node_id/reschedule" {:post (endpoint svc controller/reschedule-node)}]
   ["/projects/:id/planning/stage-weights" {:post (endpoint svc controller/set-stage-weights)}]
   ["/projects/:id/planning/snapshot" {:post (endpoint svc controller/snapshot-now)}]
   ["/projects/:id/tasks" {:post (endpoint svc controller/create-task)}]
   ["/projects/:id/tasks/:task_id" {:put (endpoint svc controller/update-task) :delete (endpoint svc controller/delete-task)}]
   ["/projects/:id/tasks/:task_id/feedback" {:post (endpoint svc controller/task-feedback)}]
   ["/projects/:id/dependencies" {:post (endpoint svc controller/create-dependency)}]
   ["/projects/:id/dependencies/:dependency_id" {:delete (endpoint svc controller/delete-dependency)}]
   ["/projects/:id/calendar" {:put (endpoint svc controller/update-calendar)}]
   ["/projects/:id/resources" {:post (endpoint svc controller/create-resource)}]
   ["/projects/:id/resources/:resource_id" {:put (endpoint svc controller/update-resource) :delete (endpoint svc controller/delete-resource)}]
   ["/projects/:id/resources/:resource_id/capacity" {:put (endpoint svc controller/set-capacity)}]
   ["/projects/:id/allocations" {:post (endpoint svc controller/create-allocation)}]
   ["/projects/:id/allocations/:allocation_id" {:delete (endpoint svc controller/delete-allocation)}]])
