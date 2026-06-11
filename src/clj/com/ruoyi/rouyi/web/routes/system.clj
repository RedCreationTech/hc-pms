(ns com.ruoyi.rouyi.web.routes.system
  "系统管理路由聚合。"
  (:require
    [com.ruoyi.rouyi.web.controllers.system.user :as user]
    [com.ruoyi.rouyi.web.controllers.system.role :as role]
    [com.ruoyi.rouyi.web.controllers.system.menu :as menu]
    [com.ruoyi.rouyi.web.controllers.system.dept :as dept]
    [com.ruoyi.rouyi.web.controllers.system.post :as post]
    [com.ruoyi.rouyi.web.controllers.system.dict :as dict]
    [com.ruoyi.rouyi.web.controllers.system.config :as config]
    [com.ruoyi.rouyi.web.controllers.system.log :as log]
    [com.ruoyi.rouyi.web.controllers.system.online :as online]
    [com.ruoyi.rouyi.web.controllers.job :as job]
    [com.ruoyi.rouyi.web.controllers.system.profile :as profile]
    [com.ruoyi.rouyi.web.controllers.monitor :as monitor]
    [com.ruoyi.rouyi.web.middleware.auth :as auth-mw]))

(defn system-routes [{:keys [user-service role-service menu-service dept-service post-service dict-service config-service log-service online-service query-fn]}]
  ["/system"
   {:middleware [((auth-mw/auth-middleware {:required? true}))]}

   ["/user"
    ["" {:get {:handler (partial user/list-users {:user-service user-service})}
         :post {:handler (partial user/create-user {:user-service user-service})}}]
    ["/:id" {:get {:handler (partial user/get-user {:user-service user-service})}
             :put {:handler (partial user/update-user {:user-service user-service})}
             :delete {:handler (partial user/delete-user {:user-service user-service})}}]]

   ["/role"
    ["" {:get {:handler (partial role/list-roles {:role-service role-service})}
         :post {:handler (partial role/create-role {:role-service role-service})}}]
    ["/:id" {:get {:handler (partial role/get-role {:role-service role-service})}
             :put {:handler (partial role/update-role {:role-service role-service})}
             :delete {:handler (partial role/delete-role {:role-service role-service})}}]]

   ["/menu"
    ["" {:get {:handler (partial menu/list-menus {:menu-service menu-service})}
         :post {:handler (partial menu/create-menu {:menu-service menu-service})}}]
    ["/tree" {:get {:handler (partial menu/menu-tree {:menu-service menu-service})}}]
    ["/:id" {:get {:handler (partial menu/get-menu {:menu-service menu-service})}
             :put {:handler (partial menu/update-menu {:menu-service menu-service})}
             :delete {:handler (partial menu/delete-menu {:menu-service menu-service})}}]]

   ["/dept"
    ["" {:get {:handler (partial dept/list-depts {:dept-service dept-service})}
         :post {:handler (partial dept/create-dept {:dept-service dept-service})}}]
    ["/:id" {:get {:handler (partial dept/get-dept {:dept-service dept-service})}
             :put {:handler (partial dept/update-dept {:dept-service dept-service})}
             :delete {:handler (partial dept/delete-dept {:dept-service dept-service})}}]]

   ["/post"
    ["" {:get {:handler (partial post/list-posts {:post-service post-service})}
         :post {:handler (partial post/create-post {:post-service post-service})}}]
    ["/:id" {:get {:handler (partial post/get-post {:post-service post-service})}
             :put {:handler (partial post/update-post {:post-service post-service})}
             :delete {:handler (partial post/delete-post {:post-service post-service})}}]]

   ["/dict/type"
    ["" {:get {:handler (partial dict/list-dict-types {:dict-service dict-service})}
         :post {:handler (partial dict/create-dict-type {:dict-service dict-service})}}]
    ["/:id" {:get {:handler (partial dict/get-dict-type {:dict-service dict-service})}
             :put {:handler (partial dict/update-dict-type {:dict-service dict-service})}
             :delete {:handler (partial dict/delete-dict-type {:dict-service dict-service})}}]]

   ["/dict/data"
    ["" {:get {:handler (partial dict/list-dict-data {:dict-service dict-service})}
         :post {:handler (partial dict/create-dict-data {:dict-service dict-service})}}]
    ["/:id" {:get {:handler (partial dict/get-dict-data {:dict-service dict-service})}
             :put {:handler (partial dict/update-dict-data {:dict-service dict-service})}
             :delete {:handler (partial dict/delete-dict-data {:dict-service dict-service})}}]]

   ["/config"
    ["" {:get {:handler (partial config/list-configs {:config-service config-service})}
         :post {:handler (partial config/create-config {:config-service config-service})}}]
    ["/:id" {:get {:handler (partial config/get-config {:config-service config-service})}
             :put {:handler (partial config/update-config {:config-service config-service})}
             :delete {:handler (partial config/delete-config {:config-service config-service})}}]]

   ["/oper-log"
    ["" {:get {:handler (partial log/list-oper-logs {:log-service log-service})}
         :delete {:handler (partial log/clear-oper-logs {:log-service log-service})}}]]

   ["/login-log"
    ["" {:get {:handler (partial log/list-login-logs {:log-service log-service})}
         :delete {:handler (partial log/clear-login-logs {:log-service log-service})}}]]

   ["/online"
    ["" {:get {:handler (partial online/list-online {:online-service online-service})}}]
    ["/:token-id" {:delete {:handler (partial online/force-logout {:online-service online-service})}}]]

   ["/profile"
    ["" {:get {:handler (partial profile/get-profile {:user-service user-service})}
         :put {:handler (partial profile/update-profile {:user-service user-service})}}]
    ["/password" {:put {:handler (partial profile/change-password {:user-service user-service})}}]
    ["/avatar" {:post {:handler (partial profile/upload-avatar {:user-service user-service})}}]]

   ["/job"
    ["" {:get {:handler (partial job/list-jobs {:query-fn (:query-fn user-service)})}
         :post {:handler (partial job/create-job {:query-fn (:query-fn user-service)})}}]
    ["/:id" {:get {:handler (partial job/get-job {:query-fn (:query-fn user-service)})}
             :put {:handler (partial job/update-job {:query-fn (:query-fn user-service)})}
             :delete {:handler (partial job/delete-job {:query-fn (:query-fn user-service)})}}]
    ["/log" {:get {:handler (partial job/list-job-logs {:query-fn (:query-fn user-service)})}}]]

   ["/server" {:get {:handler (partial monitor/server-info {})}}]
   ["/datasource" {:get {:handler (partial monitor/datasource-info {:query-fn (:query-fn user-service)})}}]])
