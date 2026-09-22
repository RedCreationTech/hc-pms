(ns com.ruoyi.web.controllers.pms-finance
  "费用和工时HTTP适配, 路由只引用明确业务命令."
  (:require [com.ruoyi.web.controllers.pms-http :as http]))

(defn query
  "执行项目范围内只读业务查询."
  [svc f request]
  (http/invoke svc request #(f %1 %2 (http/project-id request))))

(defn command
  "按固定路由参数顺序执行类型化业务命令."
  [svc f parameter-keys request]
  (http/invoke svc request
    (fn [service actor]
      (apply f service actor (http/project-id request)
             (concat (map #(http/param request %) parameter-keys)
                     [(:body-params request)])))))
