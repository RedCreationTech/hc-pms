(ns com.ruoyi.domain.system.user
  "用户领域服务，处理用户 CRUD、密码管理与角色关联。"
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.ruoyi.infra.db :as db]
    [com.ruoyi.infra.security :as security]))


(defn- parse-long-safe
  "安全解析长整型，解析失败返回 nil。"
  [v]
  (when (and (some? v) (not (str/blank? (str v))))
    (try
      (parse-long (str v))
      (catch Exception _ nil))))


(defn- blank->nil
  "将空字符串规整为 nil。"
  [v]
  (let [s (some-> v str str/trim)]
    (when (seq s) s)))


(defn- end-of-day
  "将 yyyy-MM-dd 结束日期扩展到当天末尾。"
  [v]
  (when-let [s (blank->nil v)]
    (if (= 10 (count s))
      (str s " 23:59:59")
      s)))


(defn- descendants-of
  "返回部门本身及其可识别下级部门 ID。"
  [depts dept-id]
  (let [dept-id (parse-long-safe dept-id)
        by-parent (group-by :parent_id depts)
        child-ids (fn child-ids
                    [id]
                    (mapcat (fn [d]
                              (cons (:dept_id d) (child-ids (:dept_id d))))
                            (get by-parent id [])))
        ancestor-hit? (fn [d]
                        (let [ancestors (str "," (or (:ancestors d) "") ",")]
                          (str/includes? ancestors (str "," dept-id ","))))]
    (when dept-id
      (->> (concat [dept-id]
                   (child-ids dept-id)
                   (map :dept_id (filter ancestor-hit? depts)))
           (remove nil?)
           distinct
           vec))))


(defn- admin-user?
  "判断当前用户是否为超级管理员。"
  [user]
  (or (= 1 (:user_id user))
      (= "admin" (:user_name user))
      (some #(or (= 1 (:role_id %)) (= "admin" (:role_key %))) (:roles user))))


(defn- strongest-scope
  "从用户角色中推导可用的数据权限范围。"
  [roles]
  (let [scopes (set (map #(str (or (:data_scope %) (:data-scope %) "5")) roles))]
    (cond
      (contains? scopes "1") :all
      (contains? scopes "4") :dept-child
      (contains? scopes "2") :dept-child
      (contains? scopes "3") :dept
      :else :self)))


(defn- data-scope-filter
  "根据当前用户生成数据范围过滤参数。"
  [query-fn current-user]
  (cond
    (or (nil? current-user) (admin-user? current-user))
    {:dept-ids nil :user-id nil}

    (= :all (strongest-scope (:roles current-user)))
    {:dept-ids nil :user-id nil}

    (= :self (strongest-scope (:roles current-user)))
    {:dept-ids nil :user-id (:user_id current-user)}

    :else
    (let [scope (strongest-scope (:roles current-user))
          dept-id (:dept_id current-user)
          depts (query-fn :list-depts {:status nil :dept_name nil})]
      {:dept-ids (if (= :dept scope)
                   (when dept-id [dept-id])
                   (descendants-of depts dept-id))
       :user-id nil})))


(defn- normalize-list-filters
  "把页面查询、部门过滤和数据权限合并为 SQL 参数。"
  [query-fn params offset page-size]
  (let [depts (query-fn :list-depts {:status nil :dept_name nil})
        query-dept-ids (descendants-of depts (:dept_id params))
        scope (data-scope-filter query-fn (:current-user params))
        scope-dept-ids (:dept-ids scope)
        final-dept-ids (cond
                         (and (seq query-dept-ids) (seq scope-dept-ids))
                         (vec (set/intersection (set query-dept-ids) (set scope-dept-ids)))

                         (seq query-dept-ids) query-dept-ids
                         (seq scope-dept-ids) scope-dept-ids
                         :else nil)]
    {:user_name (blank->nil (:user_name params))
     :phonenumber (blank->nil (:phonenumber params))
     :status (blank->nil (:status params))
     :begin_time (or (blank->nil (:begin_time params)) (blank->nil (:beginTime params)))
     :end_time (end-of-day (or (:end_time params) (:endTime params)))
     :dept_filter_enabled (if (seq final-dept-ids) 1 0)
     :dept_ids (if (seq final-dept-ids) final-dept-ids [-1])
     :data_user_id (:user-id scope)
     :offset offset
     :page_size page-size}))


(defn list-users
  "查询用户列表，支持分页、时间范围、部门下级和数据权限筛选。"
  [{:keys [query-fn]} params]
  (let [page-num (or (:page-num params) 1)
        page-size (or (:page-size params) 10)
        offset (* (dec page-num) page-size)
        filters (normalize-list-filters query-fn params offset page-size)
        rows (query-fn :list-users filters)
        total (query-fn :count-users filters)]
    {:rows rows :total (:total total)}))


(defn find-user-by-id
  "根据ID查询用户详情，包含部门、角色、岗位信息。"
  [{:keys [query-fn]} user-id]
  (when-let [user (query-fn :find-user-by-id {:user_id user-id})]
    (assoc user
           :roles (query-fn :list-roles-by-user-id {:user_id user-id})
           :posts (query-fn :list-posts-by-user-id {:user_id user-id}))))


(defn find-user-by-name
  "根据用户名查询用户（用于登录）。"
  [{:keys [query-fn]} user-name]
  (query-fn :find-user-by-name {:user_name user-name}))


(defn- ensure-unique!
  "按指定查询检查唯一性。"
  [query-fn query-key param-key value current-user-id message]
  (when-let [v (blank->nil value)]
    (when-let [existing (query-fn query-key {param-key v})]
      (when (not= (:user_id existing) current-user-id)
        (throw (ex-info message {param-key v}))))))


(defn- ensure-unique-user!
  "检查用户账号、手机号、邮箱唯一。"
  [{:keys [query-fn]} params current-user-id]
  (ensure-unique! query-fn :find-user-by-name :user_name (:user_name params) current-user-id "登录账号不能重复")
  (ensure-unique! query-fn :find-user-by-phone :phonenumber (:phonenumber params) current-user-id "手机号码不能重复")
  (ensure-unique! query-fn :find-user-by-email :email (:email params) current-user-id "邮箱账号不能重复"))


(defn create-user!
  "创建新用户，自动加密密码。"
  [{:keys [query-fn db]} {:keys [password roles posts] :as params}]
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
  "更新用户信息，可选更新密码。"
  [{:keys [query-fn]} {:keys [user-id password roles posts] :as params}]
  (ensure-unique-user! {:query-fn query-fn} params user-id)
  (let [update-data (-> params
                        (dissoc :roles :posts :user-id)
                        (assoc :user_id user-id))
        update-data (if password
                      (assoc update-data :password (security/hash-password password))
                      update-data)]
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
  "逻辑删除单个用户，保护 admin 用户。"
  [{:keys [query-fn]} user-id]
  (let [user (query-fn :find-user-by-id {:user_id user-id})]
    (when (or (nil? user) (= 1 user-id) (= "admin" (:user_name user)))
      (throw (ex-info "admin 用户不能删除" {:user_id user-id})))
    (query-fn :delete-user! {:user_id user-id})))


(defn delete-users!
  "批量逻辑删除用户，逐个执行 admin 保护。"
  [service user-ids]
  (doseq [user-id user-ids]
    (delete-user! service user-id)))


(defn get-user-roles
  "获取用户角色列表。"
  [{:keys [query-fn]} user-id]
  (query-fn :list-roles-by-user-id {:user_id user-id}))


(defn update-user-roles!
  "更新用户角色（先删后插）。"
  [{:keys [query-fn]} {:keys [user-id role-ids]}]
  (query-fn :delete-user-roles! {:user_id user-id})
  (doseq [rid role-ids]
    (query-fn :insert-user-role! {:user_id user-id :role_id rid})))
