(ns com.ruoyi.web.routes.pms-platform
  "平台能力与兼容基线路由, 挂在 /api/pms 下."
  (:require
    [com.ruoyi.domain.pms.platform :as platform]
    [com.ruoyi.web.controllers.pms-http :as http]))


(defn- capabilities
  "按实时身份读取平台能力; 浏览器判定取自本次请求的 User-Agent."
  [svc request]
  (http/invoke svc request (fn [service actor] (platform/capabilities service actor (:headers request)))))


(defn platform-routes
  "返回平台能力只读路由."
  [svc]
  [["/platform/capabilities" {:get {:summary "平台能力与兼容基线"
                                    :handler (partial capabilities svc)}}]])
