(ns com.ruoyi.domain.system.user
  "用户领域服务,处理用户 CRUD,密码管理与角色关联."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.ruoyi.domain.system.data-scope :as data-scope]
    [com.ruoyi.infra.db :as db]
    [com.ruoyi.infra.security :as security]))


(defn- parse-long-safe
  "安全解析长整型,解析失败返回 nil."
  [v]
  (when (and (some? v) (not (str/blank? (str v))))
    (try
      (parse-long (str v))
      (catch Exception _ nil))))


(defn- blank->nil
  "将空字符串规整为 nil."
  [v]
  (let [s (some-> v str str/trim)]
    (when (seq s) s)))


(defn- end-of-day
  "将 yyyy-MM-dd 结束日期扩展到当天末尾."
  [v]
  (when-let [s (blank->nil v)]
    (if (= 10 (count s))
      (str s " 23:59:59")
      s)))


(defn- normalize-list-filters
  "把页面查询 (部门含下级) 与数据权限范围合并为 SQL 参数. 未提供 :scope 时只能看到空结果 (fail closed)."
  [query-fn params offset page-size]
  (let [query-dept-ids (when-let [d (parse-long-safe (:dept_id params))]
                         (vec (data-scope/descendant-ids (query-fn :list-all-depts {}) d)))
        scope (or (:scope params) {:all? false :dept-ids #{} :user-id nil})]
    (merge
      {:user_name (blank->nil (:user_name params))
       :phonenumber (blank->nil (:phonenumber params))
       :status (blank->nil (:status params))
       :begin_time (or (blank->nil (:begin_time params)) (blank->nil (:beginTime params)))
       :end_time (end-of-day (or (:end_time params) (:endTime params)))
       :dept_filter_enabled (if (seq query-dept-ids) 1 0)
       :dept_ids (if (seq query-dept-ids) query-dept-ids [-1])
       :offset offset
       :page_size page-size}
      (data-scope/sql-params scope))))


(defn list-users
  "查询用户列表,支持分页,时间范围,部门下级和数据权限筛选."
  [{:keys [query-fn]} params]
  (let [page-num (or (:page-num params) 1)
        page-size (or (:page-size params) 10)
        offset (* (dec page-num) page-size)
        filters (normalize-list-filters query-fn params offset page-size)
        rows (query-fn :list-users filters)
        total (query-fn :count-users filters)]
    {:rows rows :total (:total total)}))


(defn find-user-by-id
  "根据ID查询用户详情,包含部门,角色,岗位信息."
  [{:keys [query-fn]} user-id]
  (when-let [user (query-fn :find-user-by-id {:user_id user-id})]
    (assoc user
           :roles (query-fn :list-roles-by-user-id {:user_id user-id})
           :posts (query-fn :list-posts-by-user-id {:user_id user-id}))))


(defn find-user-by-name
  "根据用户名查询用户(用于登录)."
  [{:keys [query-fn]} user-name]
  (query-fn :find-user-by-name {:user_name user-name}))


(def super-admin-user-id
  "超级管理员用户 (若依 userId = 1), 只能由本人在个人中心维护."
  1)


(defn validate-password!
  "密码策略: 长度 5-20 (对应若依 UserConstants.PASSWORD_MIN/MAX_LENGTH)."
  [password]
  (let [p (str password)]
    (when-not (<= 5 (count p) 20)
      (throw (ex-info "密码长度必须在5到20个字符之间" {:status 400})))))


(defn check-user-allowed!
  "不允许经用户管理操作超级管理员用户 (修改, 停用, 重置密码, 分配角色, 删除)."
  [user-id]
  (when (= super-admin-user-id user-id)
    (throw (ex-info "不允许操作超级管理员用户" {:status 403}))))


(defn- admin-role-ids
  [query-fn]
  (set (map :role_id (filter #(= "admin" (:role_key %))
                             (query-fn :list-roles {:role_name nil :role_key nil :status nil})))))


(defn check-role-grant!
  "只有超级管理员可以授予或收回超级管理员角色."
  [{:keys [query-fn]} actor role-ids]
  (when (and (seq (set/intersection (admin-role-ids query-fn) (set (map #(if (string? %) (parse-long %) %) role-ids))))
             (not (:admin? actor)))
    (throw (ex-info "只有超级管理员可以授予超级管理员角色" {:status 403}))))


(defn public-user
  "对外返回的用户信息, 不含密码."
  [user]
  (some-> user (dissoc :password)))


(defn- ensure-unique!
  "按指定查询检查唯一性."
  [query-fn query-key param-key value current-user-id message]
  (when-let [v (blank->nil value)]
    (when-let [existing (query-fn query-key {param-key v})]
      (when (not= (:user_id existing) current-user-id)
        (throw (ex-info message {param-key v}))))))


(defn- ensure-unique-user!
  "检查用户账号,手机号,邮箱唯一."
  [{:keys [query-fn]} params current-user-id]
  (ensure-unique! query-fn :find-user-by-name :user_name (:user_name params) current-user-id "登录账号不能重复")
  (ensure-unique! query-fn :find-user-by-phone :phonenumber (:phonenumber params) current-user-id "手机号码不能重复")
  (ensure-unique! query-fn :find-user-by-email :email (:email params) current-user-id "邮箱账号不能重复"))


(defn create-user!
  "创建新用户,校验密码策略并加密密码."
  [{:keys [query-fn db]} {:keys [password roles posts] :as params}]
  (validate-password! password)
  (ensure-unique-user! {:query-fn query-fn} params nil)
  (let [hashed (security/hash-password password)]
    (let [user-id (db/insert-and-get-id! query-fn db :create-user!
                                         (-> params
                                             (assoc :password hashed)
                                             (dissoc :roles :posts)))]
      ;; 关联角色
      (doseq [role-id roles]
        (query-fn :insert-user-role! {:user_id user-id :role_id role-id}))
      ;; 关联岗位
      (doseq [post-id posts]
        (query-fn :insert-user-post! {:user_id user-id :post_id post-id}))
      user-id)))


(defn update-user!
  "更新用户信息. :password 只能是新的明文密码 (校验策略后加密); 不传则不改密码."
  [{:keys [query-fn]} {:keys [user-id password roles posts] :as params}]
  (ensure-unique-user! {:query-fn query-fn} params user-id)
  (when password (validate-password! password))
  (let [update-data (-> params
                        (dissoc :roles :posts :user-id :password)
                        (assoc :user_id user-id))
        update-data (merge {:dept_id nil :nick_name nil :user_type nil :email nil :phonenumber nil
                            :sex nil :avatar nil :status nil :remark nil :update_by nil}
                           update-data
                           {:password (when password (security/hash-password password))})]
    (query-fn :update-user! update-data)
    ;; 更新角色关联
    (when roles
      (query-fn :delete-user-roles! {:user_id user-id})
      (doseq [role-id roles]
        (query-fn :insert-user-role! {:user_id user-id :role_id role-id})))
    ;; 更新岗位关联
    (when posts
      (query-fn :delete-user-posts! {:user_id user-id})
      (doseq [post-id posts]
        (query-fn :insert-user-post! {:user_id user-id :post_id post-id})))
    user-id))


(defn delete-user!
  "逻辑删除单个用户,保护超级管理员用户."
  [{:keys [query-fn]} user-id]
  (let [user (query-fn :find-user-by-id {:user_id user-id})]
    (when (or (nil? user) (= super-admin-user-id user-id))
      (throw (ex-info "admin 用户不能删除" {:user_id user-id})))
    (query-fn :delete-user! {:user_id user-id})))


(defn delete-users!
  "批量逻辑删除用户,逐个执行 admin 保护."
  [service user-ids]
  (doseq [user-id user-ids]
    (delete-user! service user-id)))


(defn get-user-roles
  "获取用户角色列表."
  [{:keys [query-fn]} user-id]
  (query-fn :list-roles-by-user-id {:user_id user-id}))


(defn update-user-roles!
  "更新用户角色(先删后插)."
  [{:keys [query-fn]} {:keys [user-id role-ids]}]
  (query-fn :delete-user-roles! {:user_id user-id})
  (doseq [rid role-ids]
    (query-fn :insert-user-role! {:user_id user-id :role_id rid})))


(defn user-options
  "选人组件: 有效用户的编号, 账号, 昵称与部门."
  [{:keys [query-fn]}]
  (query-fn :user-options {}))
