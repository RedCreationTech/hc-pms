(ns com.ruoyi.rouyi.web.routes.gen
  "代码生成器路由。"
  (:require
    [com.ruoyi.rouyi.web.controllers.gen :as gen]
    [com.ruoyi.rouyi.web.middleware.auth :as auth-mw]))

(defn gen-routes [{:keys [gen-service]}]
  ["/tool"
   {:middleware [((auth-mw/auth-middleware {:required? true}))]}
   ["/gen"
    ["/tables" {:get {:handler (partial gen/list-tables {:gen-service gen-service})}}]
    ["/preview" {:get {:handler (partial gen/preview-code {:gen-service gen-service})}}]]])
