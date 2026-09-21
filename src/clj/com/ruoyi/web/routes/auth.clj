(ns com.ruoyi.web.routes.auth
  "认证路由."
  (:require
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.controllers.auth :as auth]
    [com.ruoyi.web.controllers.register :as register]
    [com.ruoyi.web.middleware.auth :as auth-mw]
    [ring.util.response :as response]))


(defn- wrap-parse-token
  "简单的 JWT 解析中间件,不依赖 auth-middleware 的复杂逻辑."
  [handler]
  (fn [request]
    (let [token (some-> (get-in request [:headers "authorization"])
                        (clojure.string/replace-first #"(?i)Bearer\s+" ""))
          claims (when token (security/parse-token token))
          request (if claims (assoc request :identity claims) request)]
      (handler request))))


(defn- require-identity
  [handler]
  (fn [request]
    (if (:identity request)
      (handler request)
      (-> (response/response {:code 401 :msg "未登录或令牌已过期"})
          (response/status 401)
          (response/content-type "application/json")))))


(defn auth-routes
  [{:keys [user-service log-service menu-service]}]
  ["/auth"
   {:swagger {:tags ["认证"]}}
   ["/login" {:post {:summary    "登录"
                     :description "用户名密码登录，返回 Token"
                     :handler    (partial auth/login {:user-service user-service :log-service log-service})}}]
   ["/logout" {:post {:summary     "退出登录"
                      :description "清除当前用户会话"
                      :middleware  [wrap-parse-token require-identity]
                      :handler     (partial auth/logout {})}}]
   ["/getInfo" {:get {:summary     "获取用户信息"
                      :description "获取当前用户信息（角色/权限/菜单）"
                      :middleware  [wrap-parse-token require-identity]
                      :handler     (partial auth/get-info {:user-service user-service :menu-service menu-service})}}]
   ["/register" {:post {:summary "用户注册" :handler (partial register/register {:user-service user-service})}}]])
