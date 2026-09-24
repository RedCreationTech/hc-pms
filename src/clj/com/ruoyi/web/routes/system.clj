(ns com.ruoyi.web.routes.system
  "系统管理路由聚合."
  (:require
    [com.ruoyi.web.controllers.job :as job]
    [com.ruoyi.web.controllers.monitor :as monitor]
    [com.ruoyi.web.controllers.system.cache :as cache]
    [com.ruoyi.web.controllers.system.config :as config]
    [com.ruoyi.web.controllers.system.dept :as dept]
    [com.ruoyi.web.controllers.system.dict :as dict]
    [com.ruoyi.web.controllers.system.import-export :as im]
    [com.ruoyi.web.controllers.system.log :as log]
    [com.ruoyi.web.controllers.system.menu :as menu]
    [com.ruoyi.web.controllers.system.notice :as notice]
    [com.ruoyi.web.controllers.system.online :as online]
    [com.ruoyi.web.controllers.system.post :as post]
    [com.ruoyi.web.controllers.system.profile :as profile]
    [com.ruoyi.web.controllers.system.role :as role]
    [com.ruoyi.web.controllers.system.user :as user]
    [com.ruoyi.web.middleware.auth :as auth-mw]
    [com.ruoyi.web.middleware.authz :as authz]
    [malli.util :as mu]))


;; ── Shared Swagger schemas ──────────────────────────────────────────
(def PagingQuery
  [:map {:closed true}
   [:page {:optional true} :int] [:size {:optional true} :int]
   [:order_by {:optional true} :string] [:is_asc {:optional true} :string]])


(def RoleUserQuery
  [:map {:closed true}
   [:role_id :int]
   [:user_name {:optional true} :string]
   [:phonenumber {:optional true} :string]
   [:page {:optional true} :int] [:size {:optional true} :int]
   [:order_by {:optional true} :string] [:is_asc {:optional true} :string]])


(def PathId [:map [:id [:re #"\d+"]]])
(def UserIds [:map [:id [:re #"\d+(,\d+)*"]]])


;; ── Routes ──────────────────────────────────────────────────────────
(defn system-routes
  [{:keys [user-service role-service menu-service dept-service post-service dict-service config-service log-service online-service query-fn datasource]}]
  ["/system"
   {:middleware [(auth-mw/auth-middleware {:required? true}) (authz/perms-middleware query-fn)]
    :swagger {:tags ["系统管理"]}}

   ["/user"
    ["" {:get  {:perms "system:user:list" :summary "用户列表"
                :description "分页查询用户列表（支持搜索、数据权限过滤）"
                :parameters {:query PagingQuery}

                :handler    (partial user/list-users {:user-service user-service})}
         :post {:perms "system:user:add" :summary "新增用户"
                :description "创建新用户（含角色分配）"
                :handler    (partial user/create-user {:user-service user-service})}}]
    ["/export" {:get {:perms "system:user:export" :summary "导出用户" :description "导出用户数据为CSV文件"
                      :handler (partial im/export-users {:user-service user-service})}}]
    ["/importTemplate" {:get {:perms "system:user:import" :summary "下载导入模板" :description "下载CSV导入模板文件"
                              :handler (partial im/import-template {})}}]
    ["/options" {:get {:perms :login :summary "选人组件" :description "有效用户的编号, 账号, 昵称与部门 (供审批, 表单等选人使用)"
                       :handler (partial user/user-options {:user-service user-service})}}]
    ["/deptTree" {:get {:perms ["system:user:list" "system:user:add" "system:user:edit"] :summary "部门树" :description "获取部门树（用于选择）"
                        :handler (partial dept/dept-tree {:dept-service dept-service})}}]
    ["/import" {:post {:perms "system:user:import" :summary "导入用户" :description "从CSV文件批量导入用户"
                       :handler (partial im/import-users {:user-service user-service})}}]
    ["/:id/authRole" {:get {:perms "system:user:query" :summary "用户已分配角色"
                            :handler (partial user/auth-role {:user-service user-service})}
                      :put {:perms "system:user:edit" :summary "分配角色" :description "分配用户角色"
                            :handler (partial user/update-auth-role {:user-service user-service})}}]
    ["/:id" {:get    {:perms "system:user:query" :summary "用户详情" :parameters {:path PathId}
                      :handler (partial user/get-user {:user-service user-service})}
             :put    {:perms "system:user:edit" :summary "更新用户" :parameters {:path PathId}
                      :handler (partial user/update-user {:user-service user-service})}
             :delete {:perms "system:user:remove" :summary "删除用户" :parameters {:path UserIds}
                      :handler (partial user/delete-user {:user-service user-service})}}]
    ["/:id/status/:status" {:put {:perms "system:user:edit" :summary "修改用户状态"
                                  :handler (partial user/change-status {:user-service user-service})}}]
    ["/:id/resetPwd"       {:put {:perms "system:user:resetPwd" :summary "重置用户密码"
                                  :handler (partial user/reset-password {:user-service user-service})}}]]

   ["/role"
    ["" {:get  {:perms "system:role:list" :summary "角色列表" :description "分页查询角色列表"
                :handler (partial role/list-roles {:role-service role-service})}
         :post {:perms "system:role:add" :summary "新增角色" :handler (partial role/create-role {:role-service role-service})}}]
    ["/export" {:get {:perms "system:role:export" :summary "导出角色" :description "导出角色数据为CSV文件"
                      :handler (partial im/export-roles {:role-service role-service})}}]
    ["/optionselect" {:get {:perms :login :summary "角色选项"
                            :handler (partial role/option-select {:role-service role-service})}}]
    ["/authUser/allocatedList" {:get {:perms "system:role:list" :summary "角色已分配用户" :parameters {:query RoleUserQuery}
                                      :handler (partial role/allocated-list {:role-service role-service :user-service user-service})}}]
    ["/authUser/unallocatedList" {:get {:perms "system:role:list" :summary "角色未分配用户" :parameters {:query RoleUserQuery}
                                        :handler (partial role/unallocated-list {:role-service role-service :user-service user-service})}}]
    ["/authUser/cancel" {:put {:perms "system:role:edit" :summary "取消用户角色"
                               :parameters {:body [:map [:role_id :int] [:user_id :int]]}
                               :handler (partial role/cancel-auth-user {:role-service role-service})}}]
    ["/authUser/cancelAll" {:put {:perms "system:role:edit" :summary "批量取消角色"
                                  :parameters {:query [:map [:role_id :int] [:user_ids :string]]}
                                  :handler (partial role/cancel-auth-user-all {:role-service role-service})}}]
    ["/authUser/selectAll" {:put {:perms "system:role:edit" :summary "批量授权角色"
                                  :parameters {:query [:map [:role_id :int] [:user_ids :string]]}
                                  :handler (partial role/select-auth-user-all {:role-service role-service})}}]
    ["/deptTree/:id" {:get {:perms "system:role:query" :summary "角色部门树"
                            :handler (partial role/dept-tree-by-role {:role-service role-service :dept-service dept-service})}}]
    ["/dataScope" {:put {:perms "system:role:edit" :summary "数据权限分配"
                         :parameters {:body [:map [:role_id :int] [:data_scope :string] [:dept_ids {:optional true} :string]]}
                         :handler (partial role/data-scope {:role-service role-service})}}]
    ["/:id" {:get    {:perms "system:role:query" :summary "角色详情" :parameters {:path PathId}
                      :handler (partial role/get-role {:role-service role-service})}
             :put    {:perms "system:role:edit" :summary "更新角色" :parameters {:path PathId}
                      :handler (partial role/update-role {:role-service role-service})}
             :delete {:perms "system:role:remove" :summary "删除角色" :parameters {:path PathId}
                      :handler (partial role/delete-role {:role-service role-service})}}]]

   ["/menu"
    ["" {:get  {:perms "system:menu:list" :summary "菜单列表（树形）" :description "查询所有菜单（树形结构）"
                :handler (partial menu/list-menus {:menu-service menu-service})}
         :post {:perms "system:menu:add" :summary "新增菜单" :handler (partial menu/create-menu {:menu-service menu-service})}}]
    ["/export" {:get {:perms "system:menu:list" :summary "导出菜单" :description "导出菜单数据为CSV文件"
                      :handler (partial im/export-menus {:menu-service menu-service})}}]
    ["/treeselect" {:get {:perms ["system:menu:list" "system:role:query" "system:role:add" "system:role:edit"] :summary "菜单树选项" :description "获取菜单树（用于角色权限选择）"
                          :handler (partial menu/menu-tree {:menu-service menu-service})}}]
    ["/sort" {:put {:perms "system:menu:edit" :summary "保存菜单排序" :description "批量保存菜单拖拽排序后的 order_num"
                    :handler (partial menu/save-sort {:menu-service menu-service})}}]
    ["/:id" {:get    {:perms "system:menu:query" :summary "菜单详情" :parameters {:path PathId}
                      :handler (partial menu/get-menu {:menu-service menu-service})}
             :put    {:perms "system:menu:edit" :summary "更新菜单" :parameters {:path PathId}
                      :handler (partial menu/update-menu {:menu-service menu-service})}
             :delete {:perms "system:menu:remove" :summary "删除菜单" :parameters {:path PathId}
                      :handler (partial menu/delete-menu {:menu-service menu-service})}}]]

   ["/dict/type"
    ["" {:get  {:perms "system:dict:list" :summary "字典类型列表" :parameters {:query PagingQuery}
                :handler (partial dict/list-dict-types {:dict-service dict-service})}
         :post {:perms "system:dict:add" :summary "新增字典类型" :handler (partial dict/create-dict-type {:dict-service dict-service})}}]
    ["/export" {:get {:perms "system:dict:export" :summary "导出字典类型" :description "导出字典类型数据为CSV文件"
                      :handler (partial im/export-dict-types {:dict-service dict-service})}}]
    ["/:id" {:get    {:perms "system:dict:query" :summary "字典类型详情" :parameters {:path PathId}
                      :handler (partial dict/get-dict-type {:dict-service dict-service})}
             :put    {:perms "system:dict:edit" :summary "更新字典类型" :parameters {:path PathId}
                      :handler (partial dict/update-dict-type {:dict-service dict-service})}
             :delete {:perms "system:dict:remove" :summary "删除字典类型" :parameters {:path PathId}
                      :handler (partial dict/delete-dict-type {:dict-service dict-service})}}]
    ["/optionselect" {:get {:perms :login :summary "字典类型选项" :handler (partial dict/option-select {:dict-service dict-service})}}]
    ["/refreshCache" {:delete {:perms "system:dict:remove" :summary "刷新字典缓存" :handler (partial dict/refresh-cache {})}}]]

   ["/dict/data"
    ["" {:get  {:perms :login :summary "字典数据列表" :parameters {:query [:map {:closed true}
                                                       [:page {:optional true} :int] [:size {:optional true} :int]
                                                       [:order_by {:optional true} :string] [:is_asc {:optional true} :string]
                                                       [:dict_type {:optional true} :string]]}
                :handler (partial dict/list-dict-data {:dict-service dict-service})}
         :post {:perms "system:dict:add" :summary "新增字典数据" :handler (partial dict/create-dict-data {:dict-service dict-service})}}]
    ["/export" {:get {:perms "system:dict:export" :summary "导出字典数据" :description "导出字典数据为CSV文件"
                      :handler (partial im/export-dict-data {:dict-service dict-service})}}]
    ["/:id" {:get    {:perms "system:dict:query" :summary "字典数据详情" :parameters {:path PathId}
                      :handler (partial dict/get-dict-data {:dict-service dict-service})}
             :put    {:perms "system:dict:edit" :summary "更新字典数据" :parameters {:path PathId}
                      :handler (partial dict/update-dict-data {:dict-service dict-service})}
             :delete {:perms "system:dict:remove" :summary "删除字典数据" :parameters {:path PathId}
                      :handler (partial dict/delete-dict-data {:dict-service dict-service})}}]]

   ["/dept"
    ["" {:get  {:perms "system:dept:list" :summary "部门列表（树形）" :description "查询所有部门树"
                :handler (partial dept/list-depts {:dept-service dept-service})}
         :post {:perms "system:dept:add" :summary "新增部门" :handler (partial dept/create-dept {:dept-service dept-service})}}]
    ["/export" {:get {:perms "system:dept:list" :summary "导出部门" :description "导出部门数据为CSV文件"
                      :handler (partial im/export-depts {:dept-service dept-service})}}]
    ["/options" {:get {:perms :login :summary "选部门组件" :description "有效部门的编号, 上级与名称"
                       :handler (partial dept/dept-options {:dept-service dept-service})}}]
    ["/tree" {:get {:perms ["system:dept:list" "system:user:list" "system:role:query"] :summary "部门树选项" :description "获取部门树（用于选择）"
                    :handler (partial dept/dept-tree {:dept-service dept-service})}}]
    ["/:id" {:get    {:perms "system:dept:query" :summary "部门详情" :parameters {:path PathId}
                      :handler (partial dept/get-dept {:dept-service dept-service})}
             :put    {:perms "system:dept:edit" :summary "更新部门" :parameters {:path PathId}
                      :handler (partial dept/update-dept {:dept-service dept-service})}
             :delete {:perms "system:dept:remove" :summary "删除部门" :parameters {:path PathId}
                      :handler (partial dept/delete-dept {:dept-service dept-service})}}]]

   ["/post"
    ["/options" {:get {:perms :login :summary "选岗位组件" :description "有效岗位的编号, 编码与名称"
                       :handler (partial post/post-options {:post-service post-service})}}]
    ["" {:get  {:perms ["system:post:list" "system:user:add" "system:user:edit"] :summary "岗位列表" :parameters {:query PagingQuery}
                :handler (partial post/list-posts {:post-service post-service})}
         :post {:perms "system:post:add" :summary "新增岗位" :handler (partial post/create-post {:post-service post-service})}}]
    ["/export" {:get {:perms "system:post:export" :summary "导出岗位" :description "导出岗位数据为CSV文件"
                      :handler (partial im/export-posts {:post-service post-service})}}]
    ["/:id" {:get    {:perms "system:post:query" :summary "岗位详情" :parameters {:path PathId}
                      :handler (partial post/get-post {:post-service post-service})}
             :put    {:perms "system:post:edit" :summary "更新岗位" :parameters {:path PathId}
                      :handler (partial post/update-post {:post-service post-service})}
             :delete {:perms "system:post:remove" :summary "删除岗位" :parameters {:path PathId}
                      :handler (partial post/delete-post {:post-service post-service})}}]]

   ["/config"
    ["" {:get  {:perms "system:config:list" :summary "参数配置列表" :parameters {:query PagingQuery}
                :handler (partial config/list-configs {:config-service config-service})}
         :post {:perms "system:config:add" :summary "新增参数配置" :handler (partial config/create-config {:config-service config-service})}}]
    ["/export" {:get {:perms "system:config:export" :summary "导出参数" :description "导出参数配置数据为CSV文件"
                      :handler (partial im/export-configs {:config-service config-service})}}]
    ["/:id" {:get    {:perms "system:config:query" :summary "参数详情" :parameters {:path PathId}
                      :handler (partial config/get-config {:config-service config-service})}
             :put    {:perms "system:config:edit" :summary "更新参数" :parameters {:path PathId}
                      :handler (partial config/update-config {:config-service config-service})}
             :delete {:perms "system:config:remove" :summary "删除参数" :parameters {:path PathId}
                      :handler (partial config/delete-config {:config-service config-service})}}]]

   ["/oper-log"
    ["" {:get    {:perms "monitor:operlog:list" :summary "操作日志列表" :description "分页查询操作日志（只读）"

                  :handler (partial log/list-oper-logs {:log-service log-service})}
         :delete {:perms "monitor:operlog:remove" :summary "清空操作日志" :description "清空所有操作日志（需要确认）"
                  :handler (partial log/clear-oper-logs {:log-service log-service})}}]
    ["/:ids" {:delete {:perms "monitor:operlog:remove" :summary "删除操作日志" :description "删除指定操作日志"
                       :handler (partial log/delete-oper-logs {:log-service log-service})}}]]

   ["/login-log"
    ["" {:get    {:perms "monitor:logininfor:list" :summary "登录日志列表" :description "分页查询登录日志（只读）"

                  :handler (partial log/list-login-logs {:log-service log-service})}
         :delete {:perms "monitor:logininfor:remove" :summary "清空登录日志" :description "清空所有登录日志"
                  :handler (partial log/clear-login-logs {:log-service log-service})}}]
    ["/unlock/:user_name" {:put {:perms "monitor:logininfor:unlock" :summary "账户解锁" :description "清除登录失败锁定"
                                 :handler (partial log/unlock-user {})}}]
    ["/:ids" {:delete {:perms "monitor:logininfor:remove" :summary "删除登录日志" :description "删除指定登录日志"
                       :handler (partial log/delete-login-logs {:log-service log-service})}}]]

   ["/online"
    ["" {:get {:perms "monitor:online:list" :summary "在线用户列表" :description "查询当前在线用户列表（只读）"
               :handler (partial online/list-online {:online-service online-service})}}]
    ["/:token-id" {:delete {:perms "monitor:online:forceLogout" :summary "强退用户" :description "强制踢出在线用户"
                            :parameters {:path [:map [:token-id :string]]}
                            :handler (partial online/force-logout {:online-service online-service})}}]]

   ["/profile"
    ["" {:get {:perms :login :summary "个人信息" :description "获取当前登录用户信息"
               :handler (partial profile/get-profile {:user-service user-service})}
         :put {:perms :login :summary "更新个人信息" :description "更新昵称/手机/邮箱/性别"
               :handler (partial profile/update-profile {:user-service user-service})}}]
    ["/password" {:put {:perms :login :summary "修改密码" :description "修改当前用户登录密码"
                        :handler (partial profile/change-password {:user-service user-service})}}]
    ["/avatar"   {:post {:perms :login :summary "上传头像" :description "上传用户头像文件"
                         :handler (partial profile/upload-avatar {:user-service user-service})}}]]

   ["/job"
    ["" {:get  {:perms "monitor:job:list" :summary "定时任务列表" :parameters {:query PagingQuery}
                :handler (partial job/list-jobs {:query-fn (:query-fn user-service) :db datasource})}
         :post {:perms "monitor:job:add" :summary "新增定时任务" :handler (partial job/create-job {:query-fn (:query-fn user-service) :db datasource})}}]
    ["/:id" {:get    {:perms "monitor:job:query" :summary "任务详情" :parameters {:path PathId}
                      :handler (partial job/get-job {:query-fn (:query-fn user-service) :db datasource})}
             :put    {:perms "monitor:job:edit" :summary "更新任务" :parameters {:path PathId}
                      :handler (partial job/update-job {:query-fn (:query-fn user-service) :db datasource})}
             :delete {:perms "monitor:job:remove" :summary "删除任务" :parameters {:path PathId}
                      :handler (partial job/delete-job {:query-fn (:query-fn user-service) :db datasource})}}]
    ["/:id/changeStatus" {:put {:perms "monitor:job:changeStatus" :summary "修改任务状态" :handler (partial job/change-status {:query-fn (:query-fn user-service) :db datasource})}}]
    ["/:id/run" {:put {:perms "monitor:job:changeStatus" :summary "执行一次" :handler (partial job/run-once {:query-fn (:query-fn user-service) :db datasource})}}]]

   ["/job-log"
    ["" {:get {:perms "monitor:job:list" :summary "任务执行日志" :description "查询定时任务执行日志列表"
               :handler (partial job/list-job-logs {:query-fn (:query-fn user-service) :db datasource})}
         :delete {:perms "monitor:job:remove" :summary "清空日志" :description "清空所有任务执行日志"
                  :handler (partial job/clean-logs {:query-fn (:query-fn user-service) :db datasource})}}]]

   ["/notice"
    ["" {:get  {:perms "system:notice:list" :summary "通知公告列表" :parameters {:query PagingQuery}
                :handler (partial notice/list-notices {:query-fn query-fn})}
         :post {:perms "system:notice:add" :summary "新增通知公告" :handler (partial notice/create-notice {:query-fn query-fn})}}]
    ["/:id" {:get    {:perms "system:notice:query" :summary "通知公告详情" :parameters {:path PathId}
                      :handler (partial notice/get-notice {:query-fn query-fn})}
             :put    {:perms "system:notice:edit" :summary "更新通知公告" :parameters {:path PathId}
                      :handler (partial notice/update-notice {:query-fn query-fn})}
             :delete {:perms "system:notice:remove" :summary "删除通知公告" :parameters {:path PathId}
                      :handler (partial notice/delete-notice {:query-fn query-fn})}}]]

   ["/dashboard/stats" {:get {:perms :login :summary "首页仪表盘统计" :description "聚合用户数、在线数、日志数、任务数、最近操作和系统信息"
                              :handler (partial monitor/dashboard-stats {:query-fn query-fn})}}]

   ["/server" {:get {:perms "monitor:server:list" :summary "服务器监控" :description "JVM/CPU/内存等系统信息"
                     :handler (partial monitor/server-info {})}}]
   ["/datasource" {:get {:perms "monitor:datasource:list" :summary "数据源监控" :description "数据库连接池状态"
                         :handler (partial monitor/datasource-info {:datasource datasource})}}]

   ["/integrant" {:get {:perms "monitor:integrant:list" :summary "Integrant 依赖" :description "Integrant 配置、依赖图与运行时系统摘要"
                        :handler (partial monitor/integrant-info {})}}]

   ["/integrant/trace/*key"
    {:get {:perms "monitor:integrant:list" :summary "Integrant 函数追踪日志" :handler (partial monitor/integrant-trace-logs {})}
     :post {:perms "monitor:integrant:list" :summary "开启/关闭 Integrant 函数追踪" :handler (partial monitor/integrant-trace {})}}]

   ["/cache"
    ["" {:get {:perms "monitor:cache:list" :summary "缓存信息" :description "获取缓存名称、类型、键数量等"
               :handler (partial cache/cache-info {})}}]
    ["/keys" {:get {:perms "monitor:cache:list" :summary "缓存键列表" :description "获取所有缓存键名"
                    :handler (partial cache/cache-keys {})}}]
    ["/getNames" {:get {:perms "monitor:cache:list" :summary "缓存名称" :description "获取所有缓存名称"
                        :handler (partial cache/cache-names {})}}]
    ["/getKeys/:cacheName" {:get {:perms "monitor:cache:list" :summary "缓存键" :description "获取缓存键列表"
                                  :handler (partial cache/cache-keys-by-name {})}}]
    ["/getValue/:cacheName/:cacheKey" {:get {:perms "monitor:cache:list" :summary "缓存值" :description "获取缓存值"
                                             :handler (partial cache/cache-value {})}}]
    ["/clear" {:delete {:perms "monitor:cache:list" :summary "清空缓存" :description "清空所有缓存数据"
                        :handler (partial cache/clear-cache {})}}]
    ["/clearCacheName/:cacheName" {:delete {:perms "monitor:cache:list" :summary "清除指定缓存" :handler (partial cache/clear-cache-name {})}}]
    ["/clearCacheKey/:cacheName/:cacheKey" {:delete {:perms "monitor:cache:list" :summary "清除指定键" :handler (partial cache/clear-cache-key {})}}]
    ["/clearCacheAll" {:delete {:perms "monitor:cache:list" :summary "清除所有缓存" :handler (partial cache/clear-cache-all {})}}]]])
