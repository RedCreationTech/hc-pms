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
    [com.ruoyi.rouyi.web.middleware.auth :as auth-mw]

    [malli.util :as mu]))

;; ── Shared Swagger schemas ──────────────────────────────────────────
(def PagingQuery [:map {:closed true}
                   [:page {:optional true} :int] [:size {:optional true} :int]
                   [:order_by {:optional true} :string] [:is_asc {:optional true} :string]])




(def PathId [:map [:id :string]])

;; ── Routes ──────────────────────────────────────────────────────────
(defn system-routes [{:keys [user-service role-service menu-service dept-service post-service dict-service config-service log-service online-service query-fn]}]
  ["/system"
   {:middleware [(auth-mw/auth-middleware {:required? true})]
    :swagger {:tags ["系统管理"]}}

   ["/user"
    ["" {:get  {:summary    "用户列表"
                :description "分页查询用户列表（支持搜索、数据权限过滤）"
                :parameters {:query PagingQuery}
            
                :handler    (partial user/list-users {:user-service user-service})}
         :post {:summary    "新增用户"
                :description "创建新用户（含角色分配）"
                :handler    (partial user/create-user {:user-service user-service})}}]
    ["/:id" {:get    {:summary "用户详情" :parameters {:path PathId}
                      :handler (partial user/get-user {:user-service user-service})}
             :put    {:summary "更新用户" :parameters {:path PathId}
                      :handler (partial user/update-user {:user-service user-service})}
             :delete {:summary "删除用户" :parameters {:path PathId}
                      :handler (partial user/delete-user {:user-service user-service})}}]
    ["/:id/status/:status" {:put {:summary "修改用户状态"
                                   :handler (partial user/change-status {:user-service user-service})}}]
    ["/:id/resetPwd"       {:put {:summary "重置用户密码"
                                   :handler (partial user/reset-password {:user-service user-service})}}]]

   ["/role"
    ["" {:get  {:summary "角色列表" :description "分页查询角色列表"
            
                :handler (partial role/list-roles {:role-service role-service})}
         :post {:summary "新增角色" :handler (partial role/create-role {:role-service role-service})}}]
    ["/:id" {:get    {:summary "角色详情" :parameters {:path PathId}
                      :handler (partial role/get-role {:role-service role-service})}
             :put    {:summary "更新角色" :parameters {:path PathId}
                      :handler (partial role/update-role {:role-service role-service})}
             :delete {:summary "删除角色" :parameters {:path PathId}
                      :handler (partial role/delete-role {:role-service role-service})}}]]

   ["/menu"
    ["" {:get  {:summary "菜单列表（树形）" :description "查询所有菜单（树形结构）"
                :handler (partial menu/list-menus {:menu-service menu-service})}
         :post {:summary "新增菜单" :handler (partial menu/create-menu {:menu-service menu-service})}}]
    ["/:id" {:get    {:summary "菜单详情" :parameters {:path PathId}
                      :handler (partial menu/get-menu {:menu-service menu-service})}
             :put    {:summary "更新菜单" :parameters {:path PathId}
                      :handler (partial menu/update-menu {:menu-service menu-service})}
             :delete {:summary "删除菜单" :parameters {:path PathId}
                      :handler (partial menu/delete-menu {:menu-service menu-service})}}]]

   ["/menu-tree"
    ["" {:get {:summary "菜单树选项" :description "返回菜单树（用于权限选择器）"
               :handler (partial menu/menu-tree {:menu-service menu-service})}}]]

   ["/dict/type"
    ["" {:get  {:summary "字典类型列表" :parameters {:query PagingQuery}
                :handler (partial dict/list-dict-types {:dict-service dict-service})}
         :post {:summary "新增字典类型" :handler (partial dict/create-dict-type {:dict-service dict-service})}}]
    ["/:id" {:get    {:summary "字典类型详情" :parameters {:path PathId}
                      :handler (partial dict/get-dict-type {:dict-service dict-service})}
             :put    {:summary "更新字典类型" :parameters {:path PathId}
                      :handler (partial dict/update-dict-type {:dict-service dict-service})}
             :delete {:summary "删除字典类型" :parameters {:path PathId}
                      :handler (partial dict/delete-dict-type {:dict-service dict-service})}}]]

   ["/dict/data"
    ["" {:get  {:summary "字典数据列表" :parameters {:query [:map {:closed true}
                                                   [:page {:optional true} :int] [:size {:optional true} :int]
                                                   [:order_by {:optional true} :string] [:is_asc {:optional true} :string]
                                                   [:dict_type {:optional true} :string]]}
                :handler (partial dict/list-dict-data {:dict-service dict-service})}
         :post {:summary "新增字典数据" :handler (partial dict/create-dict-data {:dict-service dict-service})}}]
    ["/:id" {:get    {:summary "字典数据详情" :parameters {:path PathId}
                      :handler (partial dict/get-dict-data {:dict-service dict-service})}
             :put    {:summary "更新字典数据" :parameters {:path PathId}
                      :handler (partial dict/update-dict-data {:dict-service dict-service})}
             :delete {:summary "删除字典数据" :parameters {:path PathId}
                      :handler (partial dict/delete-dict-data {:dict-service dict-service})}}]]

   ["/dept"
    ["" {:get  {:summary "部门列表（树形）" :description "查询所有部门树"
                :handler (partial dept/list-depts {:dept-service dept-service})}
         :post {:summary "新增部门" :handler (partial dept/create-dept {:dept-service dept-service})}}]
    ["/:id" {:get    {:summary "部门详情" :parameters {:path PathId}
                      :handler (partial dept/get-dept {:dept-service dept-service})}
             :put    {:summary "更新部门" :parameters {:path PathId}
                      :handler (partial dept/update-dept {:dept-service dept-service})}
             :delete {:summary "删除部门" :parameters {:path PathId}
                      :handler (partial dept/delete-dept {:dept-service dept-service})}}]]

   ["/post"
    ["" {:get  {:summary "岗位列表" :parameters {:query PagingQuery}
                :handler (partial post/list-posts {:post-service post-service})}
         :post {:summary "新增岗位" :handler (partial post/create-post {:post-service post-service})}}]
    ["/:id" {:get    {:summary "岗位详情" :parameters {:path PathId}
                      :handler (partial post/get-post {:post-service post-service})}
             :put    {:summary "更新岗位" :parameters {:path PathId}
                      :handler (partial post/update-post {:post-service post-service})}
             :delete {:summary "删除岗位" :parameters {:path PathId}
                      :handler (partial post/delete-post {:post-service post-service})}}]]

   ["/config"
    ["" {:get  {:summary "参数配置列表" :parameters {:query PagingQuery}
                :handler (partial config/list-configs {:config-service config-service})}
         :post {:summary "新增参数配置" :handler (partial config/create-config {:config-service config-service})}}]
    ["/:id" {:get    {:summary "参数详情" :parameters {:path PathId}
                      :handler (partial config/get-config {:config-service config-service})}
             :put    {:summary "更新参数" :parameters {:path PathId}
                      :handler (partial config/update-config {:config-service config-service})}
             :delete {:summary "删除参数" :parameters {:path PathId}
                      :handler (partial config/delete-config {:config-service config-service})}}]]

   ["/oper-log"
    ["" {:get    {:summary "操作日志列表" :description "分页查询操作日志（只读）"
              
                  :handler (partial log/list-oper-logs {:log-service log-service})}
         :delete {:summary "清空操作日志" :description "清空所有操作日志（需要确认）"
                  :handler (partial log/clear-oper-logs {:log-service log-service})}}]]

   ["/login-log"
    ["" {:get    {:summary "登录日志列表" :description "分页查询登录日志（只读）"
              
                  :handler (partial log/list-login-logs {:log-service log-service})}
         :delete {:summary "清空登录日志" :description "清空所有登录日志"
                  :handler (partial log/clear-login-logs {:log-service log-service})}}]]

   ["/online"
    ["" {:get {:summary "在线用户列表" :description "查询当前在线用户列表（只读）"
              :handler (partial online/list-online {:online-service online-service})}}]
    ["/:token-id" {:delete {:summary "强退用户" :description "强制踢出在线用户"
                             :parameters {:path [:map [:token-id :string]]}
                             :handler (partial online/force-logout {:online-service online-service})}}]]

   ["/profile"
    ["" {:get {:summary "个人信息" :description "获取当前登录用户信息"
              :handler (partial profile/get-profile {:user-service user-service})}
         :put {:summary "更新个人信息" :description "更新昵称/手机/邮箱/性别"
              :handler (partial profile/update-profile {:user-service user-service})}}]
    ["/password" {:put {:summary "修改密码" :description "修改当前用户登录密码"
                        :handler (partial profile/change-password {:user-service user-service})}}]
    ["/avatar"   {:post {:summary "上传头像" :description "上传用户头像文件"
                         :handler (partial profile/upload-avatar {:user-service user-service})}}]]

   ["/job"
    ["" {:get  {:summary "定时任务列表" :parameters {:query PagingQuery}
                :handler (partial job/list-jobs {:query-fn (:query-fn user-service)})}
         :post {:summary "新增定时任务" :handler (partial job/create-job {:query-fn (:query-fn user-service)})}}]
    ["/:id" {:get    {:summary "任务详情" :parameters {:path PathId}
                      :handler (partial job/get-job {:query-fn (:query-fn user-service)})}
             :put    {:summary "更新任务" :parameters {:path PathId}
                      :handler (partial job/update-job {:query-fn (:query-fn user-service)})}
             :delete {:summary "删除任务" :parameters {:path PathId}
                      :handler (partial job/delete-job {:query-fn (:query-fn user-service)})}}]]

   ["/job-log"
    ["" {:get {:summary "任务执行日志" :description "查询定时任务执行日志列表"
               :handler (partial job/list-job-logs {:query-fn (:query-fn user-service)})}}]]

   ["/server" {:get {:summary "服务器监控" :description "JVM/CPU/内存等系统信息"
                     :handler (partial monitor/server-info {})}}]
   ["/datasource" {:get {:summary "数据源监控" :description "数据库连接池状态"
                         :handler (partial monitor/datasource-info {:query-fn (:query-fn user-service)})}}]])
