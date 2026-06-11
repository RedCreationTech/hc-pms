(ns com.ruoyi.rouyi.web.routes.auth
  "认证路由。"
  (:require
   [com.ruoyi.rouyi.web.controllers.auth :as auth]))

(defn auth-routes [{:keys [user-service log-service menu-service]}]
  ["/auth"
   {:swagger {:tags ["认证"]}}
   ["/login" {:post {:summary    "登录"
                     :description "用户名密码登录，返回 Token"
                     :handler    (partial auth/login {:user-service user-service :log-service log-service})}}]
   ["/logout" {:post {:summary    "退出登录"
                      :description "清除当前用户会话"
                      :handler    (partial auth/logout {})}}]
   ["/getInfo" {:get {:summary    "获取用户信息"
                      :description "获取当前用户信息（角色/权限/菜单）"
                      :handler    (partial auth/get-info {:user-service user-service :menu-service menu-service})}}]])
