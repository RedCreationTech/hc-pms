(ns com.ruoyi.rouyi.web.middleware.auth
  "认证与授权中间件，提供 JWT 校验和权限拦截。"
  (:require
    [com.ruoyi.rouyi.infra.security :as security]
    [ring.util.response :as response]))

(defn wrap-jwt-auth
  "为请求附加当前认证用户。如果令牌无效，继续执行但 :identity 为 nil。"
  [handler]
  (fn [request]
    (let [token (security/extract-token request)
          claims (when token (security/parse-token token))
          request (if claims
                    (assoc request :identity claims)
                    request)]
      (handler request))))

(defn require-auth
  "要求请求必须通过认证，否则返回 401。"
  [handler]
  (fn [request]
    (if (:identity request)
      (handler request)
      (-> (response/response {:code 401 :msg "未登录或令牌已过期"})
          (response/status 401)
          (response/content-type "application/json")))))

(defn require-perms
  "要求当前用户拥有指定权限中的任意一个，否则返回 403。"
  [perms]
  (let [required (set (if (sequential? perms) perms [perms]))]
    (fn [handler]
      (fn [request]
        (let [user-perms (set (get-in request [:identity :perms] []))]
          (if (some required user-perms)
            (handler request)
            (-> (response/response {:code 403 :msg "没有操作权限"})
                (response/status 403)
                (response/content-type "application/json"))))))))

(defn auth-middleware
  "组合中间件：JWT 解析 + 可选认证要求。"
  ([] (auth-middleware {}))
  ([{:keys [required? perms]}]
   (fn [handler]
     (let [h (wrap-jwt-auth handler)
           h (if required? (require-auth h) h)
           h (if perms ((require-perms perms) h) h)]
       h))))
