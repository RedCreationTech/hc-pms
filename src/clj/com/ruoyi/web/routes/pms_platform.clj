(ns com.ruoyi.web.routes.pms-platform
  "平台能力与兼容基线路由, 挂在 /api/pms 下."
  (:require
    [com.ruoyi.domain.pms.notify :as notify]
    [com.ruoyi.domain.pms.platform :as platform]
    [com.ruoyi.domain.pms.readiness :as readiness]
    [com.ruoyi.web.controllers.pms-http :as http]))


(defn- capabilities
  "按实时身份读取平台能力; 浏览器判定取自本次请求的 User-Agent."
  [svc request]
  (http/invoke svc request (fn [service actor] (platform/capabilities service actor (:headers request)))))


(defn- readiness-check
  "H19 运行就绪核对: 实时连接探测 + 迁移对账 + 关键配置在位; 只读且不回显配置取值."
  [svc request]
  (http/invoke svc request (fn [service actor]
                             (let [env (into {} (System/getenv))]
                               (readiness/readiness service actor env)))))


(defn- inbox
  "本地通知收件箱, 可按状态与项目筛选."
  [svc request]
  (http/invoke svc request #(notify/inbox %1 %2 (:query-params request))))


(defn- mark
  "标记通知为已读/已处理/已作废."
  [svc request]
  (http/invoke svc request #(notify/mark! %1 %2 (get-in request [:path-params :id])
                                          (get-in request [:body-params :status]))))


(defn- retry-many
  "重试本地投递失败的通知."
  [svc request]
  (http/invoke svc request notify/retry-failures!))


(defn- fail-many
  "把通知登记为投递失败, 等待重试."
  [svc request]
  (http/invoke svc request #(notify/mark-delivery-failed! %1 %2 (get-in request [:path-params :id]))))


(defn platform-routes
  "返回平台能力只读路由与本地通知收件箱路由."
  [svc]
  [["/platform/capabilities" {:get {:summary "平台能力与兼容基线"
                                    :handler (partial capabilities svc)}}]
   ["/platform/readiness" {:get {:summary "运行就绪核对 (只读)"
                                 :perms ["pms:project:list"]
                                 :handler (partial readiness-check svc)}}]
   ["/notifications" {:get {:summary "本地通知收件箱"
                            :perms ["pms:project:list" "pms:project:query"]
                            :handler (partial inbox svc)}}]
   ["/notifications/retry" {:post {:summary "重试本地投递失败的通知"
                                   :perms ["pms:project:list" "pms:project:query"]
                                   :handler (partial retry-many svc)}}]
   ["/notifications/:id/status" {:post {:summary "标记通知为已读/已处理/已作废"
                                        :perms ["pms:project:list" "pms:project:query"]
                                        :handler (partial mark svc)}}]
   ["/notifications/:id/delivery-failed" {:post {:summary "登记通知本地投递失败"
                                                 :perms ["pms:project:list" "pms:project:query"]
                                                 :handler (partial fail-many svc)}}]])
