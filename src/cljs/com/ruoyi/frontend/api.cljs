(ns com.ruoyi.frontend.api
  "HTTP API 客户端封装。"
  (:require
    [ajax.core :as ajax]
    [re-frame.db :as rf-db]))


(def api-base "/api")


(defn- get-token
  []
  (get-in @rf-db/app-db [:auth :token]))


(defn- request
  "发起 HTTP 请求，从 re-frame app-db 读取 token。"
  [{:keys [method uri params on-success on-error]}]
  (ajax/ajax-request
    {:method method
     :uri (str api-base uri)
     :params params
     :headers (when-let [token (get-token)]
                {"Authorization" (str "Bearer " token)})
     :format (ajax/json-request-format)
     :response-format (ajax/json-response-format {:keywords? true})
     :handler (fn [[ok result]]
                (if ok
                  (on-success result)
                  (on-error result)))}))


(defn login
  "用户登录。"
  [params on-success on-error]
  (request {:method :post :uri "/auth/login" :params params
            :on-success on-success :on-error on-error}))


(defn get-info
  "获取当前用户信息。"
  [on-success on-error]
  (request {:method :get :uri "/auth/getInfo"
            :on-success on-success :on-error on-error}))


(defn list-users
  "获取用户列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/user" :params params
            :on-success on-success :on-error on-error}))


(defn get-user
  "获取用户详情。"
  [user-id on-success on-error]
  (request {:method :get :uri (str "/system/user/" user-id)
            :on-success on-success :on-error on-error}))


(defn create-user
  "新增用户。"
  [params on-success on-error]
  (request {:method :post :uri "/system/user" :params params
            :on-success on-success :on-error on-error}))


(defn update-user
  "更新用户。"
  [user-id params on-success on-error]
  (request {:method :put :uri (str "/system/user/" user-id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-user
  "删除用户。"
  [user-id on-success on-error]
  (request {:method :delete :uri (str "/system/user/" user-id)
            :on-success on-success :on-error on-error}))


(defn change-user-status
  "修改用户状态。"
  [user-id status on-success on-error]
  (request {:method :put :uri (str "/system/user/" user-id "/status/" status)
            :on-success on-success :on-error on-error}))


(defn reset-user-password
  "重置用户密码。"
  [user-id password on-success on-error]
  (request {:method :put :uri (str "/system/user/" user-id "/resetPwd")
            :params {:password password}
            :on-success on-success :on-error on-error}))

(defn get-user-roles
  "获取用户已分配角色。"
  [user-id on-success on-error]
  (request {:method :get :uri (str "/system/user/" user-id "/authRole")
            :on-success on-success :on-error on-error}))

(defn update-user-roles
  "更新用户角色。"
  [user-id role-ids on-success on-error]
  (request {:method :put :uri (str "/system/user/" user-id "/authRole")
            :params {:role_ids role-ids}
            :on-success on-success :on-error on-error}))


(defn export-users
  "导出用户数据。"
  [params]
  ;; 需要实现文件下载
  (js/console.log "导出用户" params))


;; ─── 角色管理 ──────────────────────────────────────────────────────

(defn list-roles
  "获取角色列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/role" :params params
            :on-success on-success :on-error on-error}))


(defn create-role
  "新增角色。"
  [params on-success on-error]
  (request {:method :post :uri "/system/role" :params params
            :on-success on-success :on-error on-error}))


(defn update-role
  "更新角色。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/role/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-role
  "删除角色。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/role/" id)
            :on-success on-success :on-error on-error}))


(defn change-role-status
  "修改角色状态。"
  [id status on-success on-error]
  (request {:method :put :uri (str "/system/role/" id) :params {:status status}
            :on-success on-success :on-error on-error}))


(defn get-role-dept-tree
  "获取角色部门树。"
  [role-id on-success on-error]
  (request {:method :get :uri (str "/system/role/deptTree/" role-id)
            :on-success on-success :on-error on-error}))


(defn set-role-data-scope
  "设置角色数据权限。"
  [params on-success on-error]
  (request {:method :put :uri "/system/role/dataScope" :params params
            :on-success on-success :on-error on-error}))


(defn list-role-allocated-users
  "获取角色已分配用户。"
  [params on-success on-error]
  (request {:method :get :uri "/system/role/authUser/allocatedList" :params params
            :on-success on-success :on-error on-error}))


(defn list-role-unallocated-users
  "获取角色未分配用户。"
  [params on-success on-error]
  (request {:method :get :uri "/system/role/authUser/unallocatedList" :params params
            :on-success on-success :on-error on-error}))


(defn cancel-role-auth-user
  "取消用户角色授权。"
  [params on-success on-error]
  (request {:method :put :uri "/system/role/authUser/cancel" :params params
            :on-success on-success :on-error on-error}))


(defn cancel-role-auth-user-all
  "批量取消用户角色授权。"
  [params on-success on-error]
  (request {:method :put :uri "/system/role/authUser/cancelAll" :params params
            :on-success on-success :on-error on-error}))


(defn select-role-auth-user-all
  "批量授权用户角色。"
  [params on-success on-error]
  (request {:method :put :uri "/system/role/authUser/selectAll" :params params
            :on-success on-success :on-error on-error}))


;; ─── 菜单管理 ──────────────────────────────────────────────────────

(defn list-menus
  "获取菜单列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/menu" :params params
            :on-success on-success :on-error on-error}))


(defn create-menu
  "新增菜单。"
  [params on-success on-error]
  (request {:method :post :uri "/system/menu" :params params
            :on-success on-success :on-error on-error}))


(defn update-menu
  "更新菜单。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/menu/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-menu
  "删除菜单。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/menu/" id)
            :on-success on-success :on-error on-error}))


(defn change-menu-status
  "修改菜单状态。"
  [id status on-success on-error]
  (request {:method :put :uri (str "/system/menu/" id) :params {:status status}
            :on-success on-success :on-error on-error}))


;; ─── 部门管理 ──────────────────────────────────────────────────────

(defn list-depts
  "获取部门列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/dept" :params params
            :on-success on-success :on-error on-error}))


(defn create-dept
  "新增部门。"
  [params on-success on-error]
  (request {:method :post :uri "/system/dept" :params params
            :on-success on-success :on-error on-error}))


(defn update-dept
  "更新部门。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/dept/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-dept
  "删除部门。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/dept/" id)
            :on-success on-success :on-error on-error}))


(defn change-dept-status
  "修改部门状态。"
  [id status on-success on-error]
  (request {:method :put :uri (str "/system/dept/" id) :params {:status status}
            :on-success on-success :on-error on-error}))


;; ─── 岗位管理 ──────────────────────────────────────────────────────

(defn list-posts
  "获取岗位列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/post" :params params
            :on-success on-success :on-error on-error}))


(defn create-post
  "新增岗位。"
  [params on-success on-error]
  (request {:method :post :uri "/system/post" :params params
            :on-success on-success :on-error on-error}))


(defn update-post
  "更新岗位。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/post/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-post
  "删除岗位。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/post/" id)
            :on-success on-success :on-error on-error}))


(defn change-post-status
  "修改岗位状态。"
  [id status on-success on-error]
  (request {:method :put :uri (str "/system/post/" id) :params {:status status}
            :on-success on-success :on-error on-error}))


(defn list-dict-types
  "获取字典类型列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/dict/type" :params params
            :on-success on-success :on-error on-error}))


(defn list-dict-data
  "获取字典数据列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/dict/data" :params params
            :on-success on-success :on-error on-error}))


(defn list-configs
  "获取参数配置列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/config" :params params
            :on-success on-success :on-error on-error}))


(defn list-oper-logs
  "获取操作日志列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/oper-log" :params params
            :on-success on-success :on-error on-error}))


(defn list-login-logs
  "获取登录日志列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/login-log" :params params
            :on-success on-success :on-error on-error}))


(defn list-online-users
  "获取在线用户列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/online" :params params
            :on-success on-success :on-error on-error}))


(defn list-jobs
  "获取定时任务列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/job" :params params
            :on-success on-success :on-error on-error}))


(defn list-job-logs
  "获取定时任务日志。"
  [params on-success on-error]
  (request {:method :get :uri "/system/job-log" :params params
            :on-success on-success :on-error on-error}))


(defn logout
  "用户登出。"
  [on-success on-error]
  (request {:method :post :uri "/auth/logout"
            :on-success on-success :on-error on-error}))


;; ─── 服务器监控 ──────────────────────────────────────────────────────

(defn get-datasource
  "获取数据源监控信息。"
  [on-success on-error]
  (request {:method :get :uri "/system/datasource"
            :on-success on-success :on-error on-error}))


(defn get-server-info
  "获取服务器信息。"
  [on-success on-error]
  (request {:method :get :uri "/system/server"
            :on-success on-success :on-error on-error}))

(defn get-dashboard-stats
  "获取首页仪表盘统计数据。"
  [on-success on-error]
  (request {:method :get :uri "/system/dashboard/stats"
            :on-success on-success :on-error on-error}))

(defn get-integrant-info
  "获取 Integrant 配置、依赖图与运行时系统摘要。"
  [on-success on-error]
  (request {:method :get :uri "/system/integrant"
            :on-success on-success :on-error on-error}))

(defn set-integrant-trace
  "开启/关闭某个 Integrant 函数组件的调用追踪。"
  [key enabled? on-success on-error]
  (request {:method :post :uri (str "/system/integrant/trace/" key)
            :params {:enabled enabled?}
            :on-success on-success :on-error on-error}))

(defn get-integrant-trace-logs
  "获取某个 Integrant 函数组件的追踪日志。"
  [key on-success on-error]
  (request {:method :get :uri (str "/system/integrant/trace/" key)
            :on-success on-success :on-error on-error}))


;; ─── 缓存监控 ──────────────────────────────────────────────────────

(defn get-cache-info
  "获取缓存信息。"
  [on-success on-error]
  (request {:method :get :uri "/system/cache"
            :on-success on-success :on-error on-error}))


(defn get-cache-keys
  "获取缓存键列表。"
  [on-success on-error]
  (request {:method :get :uri "/system/cache/keys"
            :on-success on-success :on-error on-error}))


(defn clear-cache
  "清空缓存。"
  [on-success on-error]
  (request {:method :delete :uri "/system/cache"
            :on-success on-success :on-error on-error}))


(defn get-cache-names
  "获取缓存名称列表。"
  [on-success on-error]
  (request {:method :get :uri "/system/cache/getNames"
            :on-success on-success :on-error on-error}))


(defn get-cache-keys-by-name
  "获取指定缓存名称的键列表。"
  [cache-name on-success on-error]
  (request {:method :get :uri (str "/system/cache/getKeys/" cache-name)
            :on-success on-success :on-error on-error}))


(defn get-cache-value
  "获取缓存值。"
  [cache-name cache-key on-success on-error]
  (request {:method :get :uri (str "/system/cache/getValue/" cache-name "/" cache-key)
            :on-success on-success :on-error on-error}))


(defn clear-cache-name
  "清除指定缓存。"
  [cache-name on-success on-error]
  (request {:method :delete :uri (str "/system/cache/clearCacheName/" cache-name)
            :on-success on-success :on-error on-error}))


(defn clear-cache-key
  "清除指定缓存键。"
  [cache-name cache-key on-success on-error]
  (request {:method :delete :uri (str "/system/cache/clearCacheKey/" cache-name "/" cache-key)
            :on-success on-success :on-error on-error}))


;; ─── 通知公告 ──────────────────────────────────────────────────────

(defn list-notices
  "获取通知公告列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/notice" :params params
            :on-success on-success :on-error on-error}))


(defn create-notice
  "新增通知公告。"
  [params on-success on-error]
  (request {:method :post :uri "/system/notice" :params params
            :on-success on-success :on-error on-error}))


(defn update-notice
  "更新通知公告。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/notice/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-notice
  "删除通知公告。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/notice/" id)
            :on-success on-success :on-error on-error}))


;; ─── 个人信息 ──────────────────────────────────────────────────────

(defn get-profile
  "获取个人信息。"
  [on-success on-error]
  (request {:method :get :uri "/system/profile"
            :on-success on-success :on-error on-error}))


(defn update-profile
  "更新个人信息。"
  [params on-success on-error]
  (request {:method :put :uri "/system/profile" :params params
            :on-success on-success :on-error on-error}))


(defn change-password
  "修改密码。"
  [params on-success on-error]
  (request {:method :put :uri "/system/profile/password" :params params
            :on-success on-success :on-error on-error}))


(defn upload-avatar
  "上传头像。"
  [form-data on-success on-error]
  (ajax/ajax-request
    {:method :post
     :uri (str api-base "/system/profile/avatar")
     :body form-data
     :headers (when-let [token (get-token)]
                {"Authorization" (str "Bearer " token)})
     :response-format (ajax/json-response-format {:keywords? true})
     :handler (fn [[ok result]]
                (if ok
                  (on-success result)
                  (on-error result)))}))


;; ─── 菜单树 ──────────────────────────────────────────────────────

(defn menu-tree
  "获取菜单树（用于角色权限分配）。"
  [on-success on-error]
  (request {:method :get :uri "/system/menu/treeselect"
            :on-success on-success :on-error on-error}))


;; ─── 配置管理 ──────────────────────────────────────────────────────

(defn create-config
  "新增参数配置。"
  [params on-success on-error]
  (request {:method :post :uri "/system/config" :params params
            :on-success on-success :on-error on-error}))


(defn update-config
  "更新参数配置。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/config/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-config
  "删除参数配置。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/config/" id)
            :on-success on-success :on-error on-error}))


;; ─── 表单模板 ──────────────────────────────────────────────────────

(defn list-form-templates
  "获取表单模板列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/form-template" :params params
            :on-success on-success :on-error on-error}))


(defn get-form-template
  "获取表单模板详情。"
  [id on-success on-error]
  (request {:method :get :uri (str "/system/form-template/" id)
            :on-success on-success :on-error on-error}))


(defn save-form-template
  "保存表单模板。"
  [params on-success on-error]
  (request {:method :post :uri "/system/form-template" :params params
            :on-success on-success :on-error on-error}))


(defn update-form-template
  "更新表单模板。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/form-template/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-form-template
  "删除表单模板。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/form-template/" id)
            :on-success on-success :on-error on-error}))


;; ─── 操作日志 ──────────────────────────────────────────────────────

(defn clear-oper-logs
  "清空操作日志。"
  [on-success on-error]
  (request {:method :delete :uri "/system/oper-log"
            :on-success on-success :on-error on-error}))

(defn delete-oper-logs
  "删除操作日志。"
  [ids on-success on-error]
  (request {:method :delete :uri (str "/system/oper-log/" ids)
            :on-success on-success :on-error on-error}))


;; ─── 登录日志 ──────────────────────────────────────────────────────

(defn clear-login-logs
  "清空登录日志。"
  [on-success on-error]
  (request {:method :delete :uri "/system/login-log"
            :on-success on-success :on-error on-error}))

(defn delete-login-logs
  "删除登录日志。"
  [ids on-success on-error]
  (request {:method :delete :uri (str "/system/login-log/" ids)
            :on-success on-success :on-error on-error}))


;; ─── 在线用户 ──────────────────────────────────────────────────────

(defn force-logout
  "强制登出用户。"
  [token-id on-success on-error]
  (request {:method :delete
            :uri (str "/system/online/" (js/encodeURIComponent (str token-id)))
            :on-success on-success :on-error on-error}))


;; ─── 导入导出 ──────────────────────────────────────────────────────

(defn export-users-csv
  "导出用户CSV。"
  [params on-success on-error]
  (ajax/ajax-request
    {:method :get
     :uri (str api-base "/system/user/export")
     :params params
     :headers (when-let [token (get-token)]
                {"Authorization" (str "Bearer " token)})
     :response-format {:content-type "text/csv"
                       :description "CSV"
                       :read (fn [xhrio] (.-responseText xhrio))
                       :type :text}
     :handler (fn [[ok result]]
                (if ok
                  (on-success (:body result))
                  (on-error result)))}))


(defn import-users-csv
  "导入用户CSV。"
  [file on-success on-error]
  (let [form-data (js/FormData.)]
    (.append form-data "file" file)
    (ajax/ajax-request
      {:method :post
       :uri (str api-base "/system/user/import")
       :body form-data
       :headers (when-let [token (get-token)] {"Authorization" (str "Bearer " token)})
       :response-format (ajax/json-response-format {:keywords? true})
       :handler (fn [[ok result]]
                  (if ok (on-success result) (on-error result)))})))


;; ─── 定时任务 ──────────────────────────────────────────────────────

(defn create-job
  "新增定时任务。"
  [params on-success on-error]
  (request {:method :post :uri "/system/job" :params params
            :on-success on-success :on-error on-error}))


(defn update-job
  "更新定时任务。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/job/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-job
  "删除定时任务。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/job/" id)
            :on-success on-success :on-error on-error}))


(defn run-job-once
  "立即执行一次定时任务。"
  [job-id on-success on-error]
  (request {:method :put :uri (str "/system/job/" job-id "/run")
            :on-success on-success :on-error on-error}))


;; ─── 代码生成 ──────────────────────────────────────────────────────

;; ─── 文件管理 ──────────────────────────────────────────────────────

(defn file-list
  "获取文件列表。"
  [on-success on-error]
  (request {:method :get :uri "/system/file"
            :on-success on-success :on-error on-error}))


(defn file-upload
  "上传文件。"
  [file on-success on-error]
  (let [form-data (js/FormData.)]
    (.append form-data "file" file)
    (ajax/ajax-request
      {:method :post :uri (str api-base "/system/file") :body form-data
       :headers (when-let [token (get-token)] {"Authorization" (str "Bearer " token)})
       :response-format (ajax/json-response-format {:keywords? true})
       :handler (fn [[ok result]] (if ok (on-success result) (on-error result)))})))


(defn file-download
  "下载文件。"
  [filename]
  (let [url (str api-base "/system/file/" filename)
        token (get-token)]
    (if token
      (let [link (.createElement js/document "a")]
        (set! (.-href link) (str url "?token=" (js/encodeURIComponent token)))
        (.setAttribute link "download" filename)
        (.appendChild js/document.body link) (.click link) (.removeChild js/document.body link))
      (set! (.-location js/window) url))))


(defn file-delete
  "删除文件。"
  [filename on-success on-error]
  (request {:method :delete :uri (str "/system/file/" filename)
            :on-success on-success :on-error on-error}))


(defn gen-tables
  "获取数据库表列表。"
  [on-success on-error]
  (request {:method :get :uri "/tool/gen/tables"
            :on-success on-success :on-error on-error}))


(defn gen-columns
  "获取表列信息。"
  [table-name on-success on-error]
  (request {:method :get :uri "/tool/gen/columns"
            :params {:tableName table-name}
            :on-success on-success :on-error on-error}))


(defn gen-preview
  "预览生成的代码。"
  [table-name on-success on-error]
  (request {:method :get :uri "/tool/gen/preview"
            :params {:tableName table-name}
            :on-success on-success :on-error on-error}))


(defn gen-generate
  "批量生成代码。"
  [tables on-success on-error]
  (request {:method :post :uri "/tool/gen/generate"
            :params {:tables tables}
            :on-success on-success :on-error on-error}))


(defn gen-download
  "下载生成的代码 ZIP。"
  [tables]
  (let [token (get-token)
        headers (if token {"Authorization" (str "Bearer " token)} {})]
    (-> (js/fetch (str api-base "/tool/gen/download")
                  (clj->js {:method "POST"
                            :headers (clj->js (assoc headers "Content-Type" "application/json"))
                            :body (js/JSON.stringify (clj->js {:tables tables}))}))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.blob resp)
                   (throw (js/Error. (str "Download failed: " (.-status resp)))))))
        (.then (fn [blob]
                 (let [url (js/URL.createObjectURL blob)
                       a (js/document.createElement "a")]
                   (set! (.-href a) url)
                   (set! (.-download a) "gen-code.zip")
                   (.appendChild (.-body js/document) a)
                   (.click a)
                   (.removeChild (.-body js/document) a)
                   (js/URL.revokeObjectURL url)))))))


;; ─── 字典类型 CRUD ────────────────────────────────────────────────────────────

(defn create-dict-type
  "新增字典类型。"
  [params on-success on-error]
  (request {:method :post :uri "/system/dict/type" :params params
            :on-success on-success :on-error on-error}))


(defn update-dict-type
  "更新字典类型。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/dict/type/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-dict-type
  "删除字典类型。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/dict/type/" id)
            :on-success on-success :on-error on-error}))


;; ─── 字典数据 CRUD ────────────────────────────────────────────────────────────

(defn create-dict-data
  "新增字典数据。"
  [params on-success on-error]
  (request {:method :post :uri "/system/dict/data" :params params
            :on-success on-success :on-error on-error}))


(defn update-dict-data
  "更新字典数据。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/system/dict/data/" id) :params params
            :on-success on-success :on-error on-error}))


(defn delete-dict-data
  "删除字典数据。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/system/dict/data/" id)
            :on-success on-success :on-error on-error}))


;; ─── 通用导出函数 ──────────────────────────────────────────────────────

(defn export-generic-csv
  "通用导出CSV。"
  [url filename params]
  (let [token (get-token)
        headers (if token {"Authorization" (str "Bearer " token)} {})
        query-str (when (seq params)
                    (str "?" (clojure.string/join "&"
                                                  (map (fn [[k v]] (str (name k) "=" (js/encodeURIComponent (str v))))
                                                       params))))]
    (-> (js/fetch (str api-base url (or query-str ""))
                  (clj->js {:method "GET"
                            :headers (clj->js headers)}))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.blob resp)
                   (throw (js/Error. (str "Export failed: " (.-status resp)))))))
        (.then (fn [blob]
                 (let [url (js/URL.createObjectURL blob)
                       a (js/document.createElement "a")]
                   (set! (.-href a) url)
                   (set! (.-download a) (or filename "export.csv"))
                   (.appendChild (.-body js/document) a)
                   (.click a)
                   (.removeChild (.-body js/document) a)
                   (js/URL.revokeObjectURL url)))))))


(defn export-roles
  [params]
  (export-generic-csv "/system/role/export" "角色数据.csv" params))


(defn export-menus
  [params]
  (export-generic-csv "/system/menu/export" "菜单数据.csv" params))


(defn export-depts
  [params]
  (export-generic-csv "/system/dept/export" "部门数据.csv" params))


(defn export-posts
  [params]
  (export-generic-csv "/system/post/export" "岗位数据.csv" params))


(defn export-dicts
  [params]
  (export-generic-csv "/system/dict/type/export" "字典数据.csv" params))


(defn export-configs
  [params]
  (export-generic-csv "/system/config/export" "参数数据.csv" params))


(defn export-operlogs
  [params]
  (export-generic-csv "/monitor/operlog/export" "操作日志.csv" params))


(defn export-loginlogs
  [params]
  (export-generic-csv "/monitor/logininfor/export" "登录日志.csv" params))


;; ─── 代码生成部署 ──────────────────────────────────────────────────────

(defn gen-deploy
  "部署生成的代码到项目。"
  [table-name on-success on-error]
  (request {:method :post :uri "/tool/gen/deploy"
            :params {:tableName table-name}
            :on-success on-success :on-error on-error}))


(defn get-role
  "获取角色详情。"
  [id on-success on-error]
  (request {:method :get :uri (str "/system/role/" id)
            :on-success on-success :on-error on-error}))


;; ─── 工程方案与资源管理业务 ──────────────────────────────────────

(defn list-engineerings
  [params on-success on-error]
  (request {:method :get :uri "/business/engineering" :params params :on-success on-success :on-error on-error}))


(defn create-engineering
  [params on-success on-error]
  (request {:method :post :uri "/business/engineering" :params params :on-success on-success :on-error on-error}))


(defn update-engineering
  [id params on-success on-error]
  (request {:method :put :uri (str "/business/engineering/" id) :params params :on-success on-success :on-error on-error}))


(defn delete-engineering
  [id on-success on-error]
  (request {:method :delete :uri (str "/business/engineering/" id) :on-success on-success :on-error on-error}))


(defn list-projects
  [params on-success on-error]
  (request {:method :get :uri "/business/project" :params params :on-success on-success :on-error on-error}))


(defn get-project
  [id on-success on-error]
  (request {:method :get :uri (str "/business/project/" id) :on-success on-success :on-error on-error}))


(defn create-project
  [params on-success on-error]
  (request {:method :post :uri "/business/project" :params params :on-success on-success :on-error on-error}))


(defn update-project
  [id params on-success on-error]
  (request {:method :put :uri (str "/business/project/" id) :params params :on-success on-success :on-error on-error}))


(defn delete-project
  [id on-success on-error]
  (request {:method :delete :uri (str "/business/project/" id) :on-success on-success :on-error on-error}))


(defn create-subcontract-team
  [project-id params on-success on-error]
  (request {:method :post :uri (str "/business/project/" project-id "/subcontract-team") :params params :on-success on-success :on-error on-error}))


(defn list-solutions
  [params on-success on-error]
  (request {:method :get :uri "/business/solution" :params params :on-success on-success :on-error on-error}))


(defn create-solution
  [params on-success on-error]
  (request {:method :post :uri "/business/solution" :params params :on-success on-success :on-error on-error}))


(defn update-solution
  [id params on-success on-error]
  (request {:method :put :uri (str "/business/solution/" id) :params params :on-success on-success :on-error on-error}))


(defn delete-solution
  [id on-success on-error]
  (request {:method :delete :uri (str "/business/solution/" id) :on-success on-success :on-error on-error}))


(defn get-solution-section
  [solution-id section-key on-success on-error]
  (request {:method :get :uri (str "/business/solution/" solution-id "/sections/" section-key) :on-success on-success :on-error on-error}))


(defn save-solution-section
  [solution-id section-key params on-success on-error]
  (request {:method :put :uri (str "/business/solution/" solution-id "/sections/" section-key) :params params :on-success on-success :on-error on-error}))


(defn list-resources
  [resource-type params on-success on-error]
  (request {:method :get :uri (str "/business/resource/" resource-type) :params params :on-success on-success :on-error on-error}))


(defn get-resource
  [resource-type id on-success on-error]
  (request {:method :get :uri (str "/business/resource/" resource-type "/" id) :on-success on-success :on-error on-error}))


(defn create-resource
  [resource-type params on-success on-error]
  (request {:method :post :uri (str "/business/resource/" resource-type) :params params :on-success on-success :on-error on-error}))


(defn update-resource
  [resource-type id params on-success on-error]
  (request {:method :put :uri (str "/business/resource/" resource-type "/" id) :params params :on-success on-success :on-error on-error}))


(defn delete-resource
  [resource-type id on-success on-error]
  (request {:method :delete :uri (str "/business/resource/" resource-type "/" id) :on-success on-success :on-error on-error}))


(defn list-business-attachments
  [params on-success on-error]
  (request {:method :get :uri "/business/attachments" :params params :on-success on-success :on-error on-error}))


(defn delete-business-attachment
  [id on-success on-error]
  (request {:method :delete :uri (str "/business/attachments/" id) :on-success on-success :on-error on-error}))


(defn list-gallery-items
  [atlas-id on-success on-error]
  (request {:method :get :uri (str "/business/resource/atlas/" atlas-id "/gallery") :on-success on-success :on-error on-error}))


(defn create-gallery-item
  [atlas-id params on-success on-error]
  (request {:method :post :uri (str "/business/resource/atlas/" atlas-id "/gallery") :params params :on-success on-success :on-error on-error}))


(defn upload-business-attachment
  [params file on-success on-error]
  (let [form-data (js/FormData.)]
    (doseq [[k v] params]
      (when (some? v) (.append form-data (name k) v)))
    (.append form-data "file" file)
    (ajax/ajax-request
      {:method :post :uri (str api-base "/business/attachments") :body form-data
       :headers (when-let [token (get-token)] {"Authorization" (str "Bearer " token)})
       :response-format (ajax/json-response-format {:keywords? true})
       :handler (fn [[ok result]] (if ok (on-success result) (on-error result)))})))

;; ========== 方案生成 ==========

(defn generate-solution
  "触发方案AI生成。"
  [solution-id on-success on-error]
  (request {:method :post :uri (str "/business/solution/" solution-id "/generate") :on-success on-success :on-error on-error}))

(defn get-generate-status
  "查询方案生成进度。"
  [solution-id on-success on-error]
  (request {:method :get :uri (str "/business/solution/" solution-id "/generate-status") :on-success on-success :on-error on-error}))

(defn check-generating
  "检查当前用户是否有正在生成的方案。"
  [on-success on-error]
  (request {:method :get :uri "/business/solution/generating" :on-success on-success :on-error on-error}))

(defn expire-check-solutions
  "检测并标记超时方案为失败。"
  [on-success on-error]
  (request {:method :post :uri "/business/solution/expire-check" :on-success on-success :on-error on-error}))

;; ========== 资源关联 ==========

(defn list-resource-relations
  "查询资源的关联关系。"
  [resource-type resource-id on-success on-error]
  (request {:method :get :uri (str "/business/resource/" resource-type "/" resource-id "/relations") :on-success on-success :on-error on-error}))

(defn save-resource-relations
  "保存资源的关联关系。"
  [resource-type resource-id relations on-success on-error]
  (request {:method :post :uri (str "/business/resource/" resource-type "/" resource-id "/relations")
            :params {:relations relations} :on-success on-success :on-error on-error}))

(defn find-resources-by-route
  "根据方案类型或省份检索关联资源。"
  [params on-success on-error]
  (request {:method :get :uri "/business/resource/match" :params params :on-success on-success :on-error on-error}))

;; ========== 知识库搜索 ==========

(defn search-knowledge
  "全文搜索知识库。"
  [keyword on-success on-error]
  (request {:method :get :uri "/business/knowledge/search" :params {:keyword keyword} :on-success on-success :on-error on-error}))

;; ========== AI对话 ==========

(defn get-or-create-chat-session
  "获取或创建AI对话会话。"
  [solution-id on-success on-error]
  (request {:method :get :uri (str "/business/solution/" solution-id "/chat/session") :on-success on-success :on-error on-error}))

(defn list-chat-messages
  "获取对话消息列表。"
  [session-id on-success on-error]
  (request {:method :get :uri (str "/business/chat/" session-id "/messages") :on-success on-success :on-error on-error}))

(defn save-chat-message
  "保存对话消息。"
  [session-id params on-success on-error]
  (request {:method :post :uri (str "/business/chat/" session-id "/messages") :params params :on-success on-success :on-error on-error}))

;; ========== 方案文件版本 ==========

(defn list-solution-files
  "查询方案文件版本列表。"
  [solution-id on-success on-error]
  (request {:method :get :uri (str "/business/solution/" solution-id "/files") :on-success on-success :on-error on-error}))

(defn create-solution-file
  "创建方案文件版本记录。"
  [solution-id params on-success on-error]
  (request {:method :post :uri (str "/business/solution/" solution-id "/files") :params params :on-success on-success :on-error on-error}))

(defn download-solution-file
  "下载方案文件。"
  [solution-id file-id]
  (js/window.open (str api-base "/business/solution/" solution-id "/files/" file-id "/download") "_blank"))

;; ========== 方案导出 ==========

(defn export-solution-html
  "导出方案HTML。"
  [solution-id on-success on-error]
  (request {:method :get :uri (str "/business/solution/" solution-id "/export") :on-success on-success :on-error on-error}))
