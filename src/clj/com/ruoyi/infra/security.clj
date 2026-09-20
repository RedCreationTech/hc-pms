(ns com.ruoyi.infra.security
  "安全工具模块，提供密码哈希与 JWT 令牌签发/验证功能。"
  (:require
    [buddy.hashers :as hashers]
    [buddy.sign.jwt :as jwt]
    [clojure.string :as str]))


(def secret-key
  "JWT 签名密钥，生产环境应通过环境变量注入。"
  (or (System/getenv "JWT_SECRET")
      "rouyi-default-jwt-secret-key-change-in-production"))


(defn hash-password
  "使用 bcrypt 对明文密码进行哈希。"
  [plain-text]
  (hashers/derive plain-text {:alg :bcrypt+sha512}))


(defn verify-password
  "验证明文密码与哈希值是否匹配。"
  [plain-text hashed]
  (hashers/check plain-text hashed))


(defn generate-token
  "为用户生成 JWT 访问令牌，包含用户ID、用户名和角色列表。"
  [user-id user-name roles & {:keys [exp-hours]
                              :or {exp-hours 24}}]
  (let [claims {:user-id user-id
                :user-name user-name
                :roles roles
                :exp (+ (System/currentTimeMillis)
                        (* exp-hours 60 60 1000))}]
    (jwt/sign claims secret-key {:alg :hs256})))


(defn parse-token
  "解析并验证 JWT 令牌，成功返回 claims，失败返回 nil。"
  [token]
  (try
    (jwt/unsign token secret-key {:alg :hs256})
    (catch Exception _
      nil)))


(defn extract-token
  "从 Authorization Header 中提取 Bearer Token。"
  [request]
  (some-> (get-in request [:headers "authorization"])
          (str/replace-first #"(?i)^Bearer\s+" "")))
