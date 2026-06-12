(ns com.ruoyi.rouyi.frontend.api
  "HTTP API 客户端封装。"
  (:require
   [ajax.core :as ajax]
   [re-frame.db :as rf-db]))

(def api-base "/api")

(defn- get-token []
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
  (request {:method :get :uri "/system/menu-tree"
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

;; ─── 操作日志 ──────────────────────────────────────────────────────

(defn clear-oper-logs
  "清空操作日志。"
  [on-success on-error]
  (request {:method :delete :uri "/system/oper-log"
            :on-success on-success :on-error on-error}))

;; ─── 登录日志 ──────────────────────────────────────────────────────

(defn clear-login-logs
  "清空登录日志。"
  [on-success on-error]
  (request {:method :delete :uri "/system/login-log"
            :on-success on-success :on-error on-error}))

;; ─── 在线用户 ──────────────────────────────────────────────────────

(defn force-logout
  "强制登出用户。"
  [token-id on-success on-error]
  (request {:method :delete :uri (str "/system/online/" token-id)
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
                      :read (fn [xhrio] (.getResponseText xhrio))
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

;; ─── 字典类型 CRUD ────────────────────────────────────────────────────────────

(defn create-dict-type
  "新增字典类型。"
  [params on-success on-error]
  (request {:method :post :uri "/api/system/dict/type" :params params
            :on-success on-success :on-error on-error}))

(defn update-dict-type
  "更新字典类型。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/api/system/dict/type/" id) :params params
            :on-success on-success :on-error on-error}))

(defn delete-dict-type
  "删除字典类型。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/api/system/dict/type/" id)
            :on-success on-success :on-error on-error}))

;; ─── 字典数据 CRUD ────────────────────────────────────────────────────────────

(defn create-dict-data
  "新增字典数据。"
  [params on-success on-error]
  (request {:method :post :uri "/api/system/dict/data" :params params
            :on-success on-success :on-error on-error}))

(defn update-dict-data
  "更新字典数据。"
  [id params on-success on-error]
  (request {:method :put :uri (str "/api/system/dict/data/" id) :params params
            :on-success on-success :on-error on-error}))

(defn delete-dict-data
  "删除字典数据。"
  [id on-success on-error]
  (request {:method :delete :uri (str "/api/system/dict/data/" id)
            :on-success on-success :on-error on-error}))
