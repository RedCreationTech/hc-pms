(ns com.ruoyi.web.middleware.auth
  "认证中间件,提供 JWT 校验 (含撤销检查) 与在线心跳; 功能权限见 authz 命名空间."
  (:require
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [ring.util.response :as response]))


(defn wrap-jwt-auth
  "为请求附加当前认证用户,并更新在线心跳.
  令牌无效, 已过期或已被撤销 (退出, 强退, 停用, 改密) 时继续执行但 :identity 为 nil."
  [handler]
  (fn [request]
    (let [claims (online/valid-claims (security/extract-token request))
          _ (when claims (online/heartbeat! claims))
          request (if claims
                    (assoc request :identity claims)
                    (dissoc request :identity))]
      (handler request))))


(defn require-auth
  "要求请求必须通过认证,否则返回 401."
  [handler]
  (fn [request]
    (if (:identity request)
      (handler request)
      (-> (response/response {:code 401 :msg "未登录或令牌已过期"})
          (response/status 401)
          (response/content-type "application/json")))))


(defn auth-middleware
  "组合中间件:JWT 解析 + 在线心跳 + 可选认证要求.
  接口级功能权限由 com.ruoyi.web.middleware.authz 按路由数据 :perms 校验."
  ([] (auth-middleware {}))
  ([{:keys [required?]}]
   (fn [handler]
     (let [h (if required? (require-auth handler) handler)
           h (wrap-jwt-auth h)]
       h))))
