(ns com.ruoyi.web.routes.auth
  "认证路由. 获取信息与退出与其他受保护接口使用同一令牌校验 (含撤销检查)."
  (:require
    [com.ruoyi.web.controllers.auth :as auth]
    [com.ruoyi.web.controllers.register :as register]
    [com.ruoyi.web.middleware.auth :as auth-mw]))


(defn auth-routes
  [{:keys [user-service log-service menu-service]}]
  ["/auth"
   {:swagger {:tags ["认证"]}}
   ["/login" {:post {:summary    "登录"
                     :description "用户名密码登录，返回 Token"
                     :handler    (partial auth/login {:user-service user-service :log-service log-service})}}]
   ["/loginConfig" {:get {:summary "登录页配置" :description "是否需要验证码, 是否开放注册"
                          :handler (partial auth/login-config {:user-service user-service})}}]
   ["/logout" {:post {:summary     "退出登录"
                      :description "撤销当前会话"
                      :middleware  [(auth-mw/auth-middleware {:required? true})]
                      :handler     (partial auth/logout)}}]
   ["/getInfo" {:get {:summary     "获取用户信息"
                      :description "获取当前用户信息（实时角色/权限/菜单）"
                      :middleware  [(auth-mw/auth-middleware {:required? true})]
                      :handler     (partial auth/get-info {:user-service user-service :menu-service menu-service})}}]
   ["/register" {:post {:summary "用户注册" :description "参数 sys.account.registerUser 为 true 时开放"
                        :handler (partial register/register {:user-service user-service})}}]])
