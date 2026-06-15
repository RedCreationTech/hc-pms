(ns com.ruoyi.web.routes.workflow
  "流程引擎 API 路由。"
  (:require [com.ruoyi.web.controllers.workflow :as wf]))

(defn routes []
  ["/workflow"
   ;; 流程定义
   ["/definitions" {:get {:summary "查询流程定义" :handler wf/list-definitions}}]
   ["/deploy" {:post {:summary "部署 BPMN" :handler wf/deploy}}]
   ["/definitions/:id/bpmn" {:get {:summary "获取 BPMN XML" :handler wf/get-definition-bpmn}}]
   ["/deployments/:id" {:delete {:summary "删除部署" :handler wf/delete-deployment}}]
   
   ;; 流程实例
   ["/instances" {:get {:summary "查询流程实例" :handler wf/list-instances}
                  :post {:summary "启动流程实例" :handler wf/start-instance}}]
   ["/instances/:id" {:delete {:summary "删除流程实例" :handler wf/delete-instance}}]
   
   ;; 任务
   ["/tasks" {:get {:summary "查询待办任务" :handler wf/list-tasks}}]
   ["/tasks/complete" {:post {:summary "完成任务" :handler wf/complete-task}}]
   ["/tasks/claim" {:post {:summary "签收任务" :handler wf/claim-task}}]
   ["/tasks/delegate" {:post {:summary "委托任务" :handler wf/delegate-task}}]])
