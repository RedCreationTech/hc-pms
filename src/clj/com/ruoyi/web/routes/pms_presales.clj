(ns com.ruoyi.web.routes.pms-presales
  "A04 售前资料交接路由, 挂在 /api/pms 下."
  (:require
    [com.ruoyi.domain.pms.presales :as presales]
    [com.ruoyi.web.controllers.pms-http :as http]))


(defn- command
  "按项目范围调用明确业务命令, 不接受任意类型 CRUD."
  [svc command-fn]
  (fn [request]
    (http/invoke svc request #(command-fn %1 %2 (http/project-id request)
                                          (get-in request [:path-params :record_id])
                                          (:body-params request)))))


(defn- create
  [svc request]
  (http/invoke svc request #(presales/create! %1 %2 (http/project-id request) (:body-params request))))


(defn- workspace
  [svc request]
  (http/invoke svc request #(presales/workspace %1 %2 (http/project-id request))))


(def ^:private perms
  "交接维护与读取统一要求项目查看/编辑权限, 审批另由领域层校验质量审批权限."
  ["pms:project:list" "pms:project:query" "pms:project:edit"])


(defn presales-routes
  "返回售前资料交接与产品线应交清单路由."
  [svc]
  [["/projects/:id/presales"
    ["" {:get {:summary "项目售前资料交接与适用应交清单" :perms perms
               :handler (partial workspace svc)}
         :post {:summary "建立售前资料交接" :perms perms
                :handler (partial create svc)}}]
    ["/:record_id" {:post {:summary "维护交接资料" :perms perms
                           :handler (command svc presales/update!)}}]
    ["/:record_id/submit" {:post {:summary "提交交接评审 (必交项缺料 409)" :perms perms
                                  :handler (command svc presales/submit!)}}]
    ["/:record_id/return" {:post {:summary "缺资料退回" :perms perms
                                  :handler (command svc presales/return!)}}]
    ["/:record_id/approve" {:post {:summary "独立审核批准交接" :perms perms
                                   :handler (command svc presales/approve!)}}]]])
