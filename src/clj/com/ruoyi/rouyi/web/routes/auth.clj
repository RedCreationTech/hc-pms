(ns com.ruoyi.rouyi.web.routes.auth
  "认证路由。"
  (:require
    [com.ruoyi.rouyi.web.controllers.auth :as auth]))

(defn auth-routes [{:keys [user-service log-service menu-service]}]
  ["/auth"
   ["/login" {:post {:handler (partial auth/login {:user-service user-service :log-service log-service})}}]
   ["/logout" {:post {:handler (partial auth/logout {})}}]
   ["/getInfo" {:get {:handler (partial auth/get-info {:user-service user-service :menu-service menu-service})}}]])
