(ns com.ruoyi.web.controllers.pms-delivery
  "交付业务的共享安全HTTP边界."
  (:require [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.web.controllers.pms-http :as http]))


(defn workspace
  "读取当前有权项目的交付事实工作台."
  [svc request]
  (http/invoke svc request #(delivery/workspace %1 %2 (http/project-id request))))


(defn command
  "执行固定业务资源的受控状态命令."
  [svc resource action request]
  (http/invoke svc request
    #(delivery/command! %1 %2 (http/project-id request) resource action
                         (http/param request :record_id) (:body-params request))))
