(ns com.ruoyi.web.controllers.pms-planning
  "规划工作台HTTP适配,统一使用项目身份和安全响应."
  (:require [com.ruoyi.domain.pms.planning :as planning]
            [com.ruoyi.web.controllers.pms-http :as http]))

(defn read-plan
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/read-plan %1 %2 (http/project-id request))))

(defn read-baseline
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/read-baseline %1 %2 (http/project-id request) (http/param request :baseline_id))))

(defn baseline-diff
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/baseline-diff %1 %2 (http/project-id request) (http/param request :baseline_id))))

(defn create-task
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/create-task! %1 %2 (http/project-id request) (:body-params request))))

(defn update-task
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/update-task! %1 %2 (http/project-id request) (http/param request :task_id) (:body-params request))))

(defn delete-task
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/delete-task! %1 %2 (http/project-id request) (http/param request :task_id) (:body-params request))))

(defn create-dependency
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/create-dependency! %1 %2 (http/project-id request) (:body-params request))))

(defn delete-dependency
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/delete-dependency! %1 %2 (http/project-id request) (http/param request :dependency_id) (:body-params request))))

(defn task-feedback
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/task-feedback! %1 %2 (http/project-id request) (http/param request :task_id) (:body-params request))))

(defn update-calendar
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/update-calendar! %1 %2 (http/project-id request) (:body-params request))))

(defn create-resource
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/create-resource! %1 %2 (http/project-id request) (:body-params request))))

(defn update-resource
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/update-resource! %1 %2 (http/project-id request) (http/param request :resource_id) (:body-params request))))

(defn delete-resource
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/delete-resource! %1 %2 (http/project-id request) (http/param request :resource_id) (:body-params request))))

(defn set-capacity
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/set-capacity! %1 %2 (http/project-id request) (http/param request :resource_id) (:body-params request))))

(defn create-allocation
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/create-allocation! %1 %2 (http/project-id request) (:body-params request))))

(defn delete-allocation
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/delete-allocation! %1 %2 (http/project-id request) (http/param request :allocation_id) (:body-params request))))

(defn submit-plan
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/submit-plan! %1 %2 (http/project-id request) (:body-params request))))

(defn review-plan
  "调用计划领域服务并返回项目接口响应."
  [svc request]
  (http/invoke svc request #(planning/review-plan! %1 %2 (http/project-id request) (http/param request :baseline_id) (:body-params request))))


(defn derive-network
  "从模板阶段派生子项目/单机计划."
  [svc request]
  (http/invoke svc request #(planning/derive-network! %1 %2 (http/project-id request) (:body-params request))))

(defn reschedule-node
  "重排节点计划, 保留原基线."
  [svc request]
  (http/invoke svc request #(planning/reschedule-node! %1 %2 (http/project-id request) (http/param request :node_id) (:body-params request))))

(defn set-stage-weights
  "项目级阶段权重覆盖."
  [svc request]
  (http/invoke svc request #(planning/set-stage-weights! %1 %2 (http/project-id request) (:body-params request))))

(defn snapshot-now
  "手动生成进度快照与逾期提醒."
  [svc request]
  (http/invoke svc request #(planning/snapshot-now! %1 %2 (http/project-id request) (:body-params request))))
