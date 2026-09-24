(ns com.ruoyi.infra.security
  "安全工具模块,提供密码哈希与 JWT 令牌签发/验证功能."
  (:require
    [buddy.hashers :as hashers]
    [buddy.sign.jwt :as jwt]
    [clojure.string :as str]))


(def secret-key
  "JWT 签名密钥,生产环境应通过环境变量注入."
  (or (System/getenv "JWT_SECRET")
      "rouyi-default-jwt-secret-key-change-in-production"))


(defn hash-password
  "使用 bcrypt 对明文密码进行哈希."
  [plain-text]
  (hashers/derive plain-text {:alg :bcrypt+sha512}))


(defn verify-password
  "验证明文密码与哈希值是否匹配."
  [plain-text hashed]
  (hashers/check plain-text hashed))


(def token-lifetime-hours
  "令牌绝对有效期 (小时), 可用 JWT_EXPIRE_HOURS 覆盖."
  (or (some-> (System/getenv "JWT_EXPIRE_HOURS") parse-long) 24))


(defn generate-token
  "为用户生成 JWT 访问令牌. 标准声明按秒 (exp/iat), 另带会话编号 jti 与签发毫秒 issued-ms,
  用于强退, 退出与按用户撤销."
  [user-id user-name roles & {:keys [exp-hours]
                              :or {exp-hours token-lifetime-hours}}]
  (let [now (System/currentTimeMillis)
        claims {:user-id user-id
                :user-name user-name
                :roles roles
                :jti (str (java.util.UUID/randomUUID))
                :iat (quot now 1000)
                :issued-ms now
                :exp (+ (quot now 1000) (* exp-hours 60 60))}]
    (jwt/sign claims secret-key {:alg :hs256})))


(defn parse-token
  "解析并验证 JWT 令牌,成功返回 claims,失败返回 nil."
  [token]
  (try
    (jwt/unsign token secret-key {:alg :hs256})
    (catch Exception _
      nil)))


(defn extract-token
  "从 Authorization Header 中提取 Bearer Token."
  [request]
  (some-> (get-in request [:headers "authorization"])
          (str/replace-first #"(?i)^Bearer\s+" "")))
