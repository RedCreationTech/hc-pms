(ns com.ruoyi.web.controllers.auth
  "认证控制器,处理登录,登出及当前用户信息获取."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.system.log :as log-domain]
    [com.ruoyi.domain.system.menu :as menu-service]
    [com.ruoyi.domain.system.user :as user-service]
    [com.ruoyi.infra.login-guard :as login-guard]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.middleware.authz :as authz]
    [com.ruoyi.web.controllers.captcha :as captcha]
    [ring.util.response :as response]))


(defn- success
  "构造成功响应."
  [data]
  (-> (response/response {:code 200 :msg "操作成功" :data data})
      (response/content-type "application/json")))


(defn- error
  "构造错误响应."
  [code msg]
  (-> (response/response {:code code :msg msg})
      (response/status (if (>= code 500) 500 200))
      (response/content-type "application/json")))


(defn- config-enabled?
  "读取布尔参数 (sys_config), 未配置时取默认值."
  [query-fn config-key default]
  (let [v (some-> (query-fn :find-config-by-key {:config_key config-key}) :config_value str/trim str/lower-case)]
    (if (str/blank? v) default (= "true" v))))


(defn captcha-enabled?
  [query-fn]
  (config-enabled? query-fn "sys.account.captchaEnabled" false))


(defn register-enabled?
  [query-fn]
  (config-enabled? query-fn "sys.account.registerUser" false))


(defn login-config
  "登录页公开配置: 是否需要验证码, 是否开放注册."
  [{:keys [user-service]} _]
  (let [q (:query-fn user-service)]
    (success {:captchaEnabled (captcha-enabled? q) :registerEnabled (register-enabled? q)})))


(defn- login-log!
  [user-service username ip status msg]
  (log-domain/create-login-log! user-service
                                {:user_name username :ipaddr ip :login_location ""
                                 :browser "" :os "" :status status :msg msg}))


(defn- captcha-valid?
  [uuid captcha]
  (let [stored (get @captcha/captcha-store uuid)]
    (boolean (and stored (seq captcha)
                  (<= (System/currentTimeMillis) (:expire stored))
                  (= (str/upper-case captcha) (str/upper-case (:code stored)))))))


(def ^:private dummy-hash
  "账号不存在时也做一次同等代价的密码校验, 避免按响应时间区分账号是否存在."
  (delay (security/hash-password (str (java.util.UUID/randomUUID)))))


(defn login
  "用户登录: 按参数校验验证码, 失败锁定, 统一的用户名或密码错误提示, 签发带会话编号的令牌并登记在线会话."
  [{:keys [user-service]} request]
  (let [{:keys [username password captcha uuid]} (:body-params request)
        username (some-> username str str/trim)
        login-ip (get-in request [:headers "x-forwarded-for"] (:remote-addr request "127.0.0.1"))
        need-captcha? (captcha-enabled? (:query-fn user-service))
        captcha-ok? (or (not need-captcha?) (and (seq uuid) (captcha-valid? uuid captcha)))]
    (when (seq uuid) (swap! captcha/captcha-store dissoc uuid))
    (cond
      (not captcha-ok?)
      (error 400 "验证码错误或已过期")

      (or (str/blank? username) (str/blank? password))
      (error 400 "用户名和密码不能为空")

      (login-guard/locked? username)
      (do (login-log! user-service username login-ip "1" (login-guard/lock-message))
          (error 400 (login-guard/lock-message)))

      :else
      (let [user (user-service/find-user-by-name user-service username)]
        (if (if user
              (security/verify-password password (:password user))
              (do (security/verify-password password @dummy-hash) false))
          (if (= "0" (:status user))
            (let [roles (user-service/get-user-roles user-service (:user_id user))
                  token (security/generate-token (:user_id user) (:user_name user) (mapv :role_id roles))]
              (login-guard/clear! username)
              (online/register! token (:user_name user) login-ip)
              (login-log! user-service username login-ip "0" "登录成功")
              (success {:token token}))
            (do (login-log! user-service username login-ip "1" "用户已被停用")
                (error 403 "用户已被停用")))
          (let [locked? (login-guard/record-failure! username)]
            (login-log! user-service username login-ip "1" (if locked? (login-guard/lock-message) "用户名或密码错误"))
            (error 400 (if locked? (login-guard/lock-message) "用户名或密码错误"))))))))


(defn get-info
  "获取当前登录用户信息, 实时角色, 权限与菜单 (超级管理员拥有全部启用菜单与 *:*:*)."
  [{:keys [user-service menu-service]} request]
  (let [q (:query-fn user-service)
        user-id (get-in request [:identity :user-id])]
    (if-let [actor (authz/load-actor q user-id)]
      (let [user (user-service/find-user-by-id user-service user-id)]
        (success {:user (select-keys user [:user_id :user_name :nick_name :avatar :email :phonenumber :sex :dept_id])
                  :roles (vec (sort (:role_keys actor)))
                  :permissions (vec (sort (:permissions actor)))
                  :menus (menu-service/menu-tree-for-actor menu-service actor)}))
      (error 401 "用户不存在或已停用"))))


(defn logout
  "用户登出: 撤销当前会话并清除在线记录."
  [request]
  (when-let [token (security/extract-token request)]
    (online/unregister! token))
  (success {}))
