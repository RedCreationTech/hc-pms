(ns com.ruoyi.rouyi.web.controllers.auth
  "认证控制器，处理登录、登出及当前用户信息获取。"
  (:require
   [com.ruoyi.rouyi.domain.system.user :as user-service]
   [com.ruoyi.rouyi.domain.system.role :as role-service]
   [com.ruoyi.rouyi.domain.system.menu :as menu-service]
   [com.ruoyi.rouyi.infra.security :as security]
   [com.ruoyi.rouyi.infra.online :as online]
    [com.ruoyi.rouyi.web.controllers.captcha :as captcha]
   [com.ruoyi.rouyi.domain.system.log :as log-domain]
   [ring.util.response :as response]
   [clojure.string :as str]))

(defn- success
  "构造成功响应。"
  [data]
  (-> (response/response {:code 200 :msg "操作成功" :data data})
      (response/content-type "application/json")))

(defn- error
  "构造错误响应。"
  [code msg]
  (-> (response/response {:code code :msg msg})
      (response/status (if (>= code 500) 500 200))
      (response/content-type "application/json")))

(defn login
  "用户登录，验证密码后签发 JWT，并注册在线用户。"
  [{:keys [user-service log-service]} request]
  (let [{:keys [username password captcha uuid]} (:body-params request)
        login-ip (get-in request [:headers "x-forwarded-for"] (:remote-addr request "127.0.0.1"))]
    ;; 验证码校验
    (when (and uuid captcha)
      (let [stored (get @captcha/captcha-store uuid)]
        (when (or (nil? stored)
                  (> (System/currentTimeMillis) (:expire stored))
                  (not= (.toUpperCase captcha) (.toUpperCase (:code stored))))
          (swap! captcha/captcha-store dissoc uuid)
          (throw (ex-message "验证码错误或已过期")))))
    (when uuid (swap! captcha/captcha-store dissoc uuid))
    (if (or (str/blank? username) (str/blank? password))
      (error 400 "用户名和密码不能为空")
      (if-let [user (user-service/find-user-by-name user-service username)]
        (if (security/verify-password password (:password user))
          (if (= "0" (:status user))
            (let [roles (user-service/find-user-by-id user-service (:user_id user))
                  role-ids (mapv :role_id (:roles roles))
                  token (security/generate-token (:user_id user) (:user_name user) role-ids)
                  _ (online/register! token (:user_id user) (:user_name user) login-ip)
                  ;; 记录登录日志
                  _ (log-domain/create-login-log! user-service
                                                  {:user_name username :ipaddr login-ip :login_location ""
                                                   :browser "" :os "" :status "0" :msg "登录成功"})]
              (success {:token token}))
            (error 403 "用户已被停用"))
          (do
            (log-domain/create-login-log! user-service
                                          {:user_name username :ipaddr login-ip :login_location ""
                                           :browser "" :os "" :status "1" :msg "密码错误"})
            (error 400 "密码错误")))
        (error 400 "用户不存在")))))

(defn get-info
  "获取当前登录用户信息及权限菜单。"
  [{:keys [user-service menu-service]} request]
  (let [identity (:identity request)
        user-id (:user-id identity)]
    (if-let [user (user-service/find-user-by-id user-service user-id)]
      (let [roles (:roles user)
            role-ids (mapv :role_id roles)
            perms (->> (mapcat #(role-service/get-role-perms {:query-fn (:query-fn user-service)} %) role-ids)
                       (into #{})
                       (vec))
            menus (menu-service/menu-tree-by-roles menu-service role-ids)]
        (success {:user (select-keys user [:user_id :user_name :nick_name :avatar :email :phonenumber :sex])
                  :roles (mapv :role_key roles)
                  :permissions perms
                  :menus menus}))
      (error 401 "用户不存在"))))

(defn logout
  "用户登出，清除在线记录。"
  [request]
  (when-let [token (security/extract-token request)]
    (online/unregister! token))
  (success {}))
