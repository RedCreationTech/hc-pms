(ns com.ruoyi.web.routes.pms-closure
  "收尾, 遗留移交和经验复用路由, 包含独立关闭审批."
  (:require [com.ruoyi.domain.pms.closure :as closure]
            [com.ruoyi.domain.pms.lifecycle :as lifecycle]
            [com.ruoyi.web.controllers.pms-finance :as http]))

(defn- command
  "绑定明确命令及固定路径参数."
  [svc f keys]
  {:handler (partial http/command svc f keys)})

(defn- create-item
  "为收尾事项类型提供确定的命令签名."
  [kind]
  (fn [svc actor id body] (closure/create-item! svc actor id kind body)))

(defn- complete-item
  "为事项完成绑定类型, 防止不同业务对象混用."
  [kind]
  (fn [svc actor id item-id body] (closure/complete-item! svc actor id kind item-id body)))

(defn closure-routes
  "返回项目收尾及团队撤销接口."
  [svc]
  [["/projects/:id/closure" {:get {:handler (partial http/query svc closure/overview)}}]
   ["/projects/:id/closure/checks" {:post (command svc (create-item "check") [])}]
   ["/projects/:id/closure/checks/:item_id/complete" {:post (command svc (complete-item "check") [:item_id])}]
   ["/projects/:id/closure/handoffs" {:post (command svc (create-item "handoff") [])}]
   ["/projects/:id/closure/handoffs/:item_id/complete" {:post (command svc (complete-item "handoff") [:item_id])}]
   ["/projects/:id/closure/lessons" {:post (command svc closure/create-lesson! [])}]
   ["/projects/:id/closure/submit" {:post (command svc closure/submit! [])}]
   ["/projects/:id/closure/review" {:post (command svc closure/review! [])}]
   ["/projects/:id/members/:user_id" {:delete (command svc lifecycle/remove-member! [:user_id])}]])
