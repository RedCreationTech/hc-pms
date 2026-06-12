(ns com.ruoyi.rouyi.domain.system.role
  "角色领域服务，处理角色 CRUD、菜单授权与数据权限。"
  (:require
   [clojure.set :as set]))

(defn list-roles
  "查询角色列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-roles (merge {:role_name nil :role_key nil :status nil} params)))

(defn find-role-by-id
  "根据ID查询角色详情，包含关联菜单ID列表。"
  [{:keys [query-fn]} role-id]
  (when-let [role (query-fn :find-role-by-id {:role_id role-id})]
    (assoc role :menu-ids (mapv :menu_id (query-fn :list-menus-by-role-id {:role_id role-id})))))

(defn create-role!
  "创建角色并绑定菜单权限。"
  [{:keys [query-fn]} {:keys [menu-ids] :as params}]
  (let [role-id (-> (query-fn :create-role! (dissoc params :menu-ids))
                    first
                    :role_id)]
    (doseq [m-id menu-ids]
      (query-fn :insert-role-menu! {:role_id role-id :menu_id m-id}))
    role-id))

(defn update-role!
  "更新角色及菜单权限。"
  [{:keys [query-fn]} {:keys [role-id menu-ids] :as params}]
  ;; 只有当有非menu-ids的参数时才更新角色基本信息
  (let [role-params (dissoc params :menu-ids :role-id :role_name :role_key :role_sort :data_scope :menu_check_strictly :dept_check_strictly :status :remark)]
    (when (seq role-params)
      (query-fn :update-role! (merge {:role_name nil :role_key nil :role_sort nil :data_scope nil :menu_check_strictly nil :dept_check_strictly nil :status nil :remark nil}
                                     (assoc role-params :role_id role-id)))))
  ;; 更新菜单权限
  (when menu-ids
    (query-fn :delete-role-menus! {:role_id role-id})
    (doseq [m-id menu-ids]
      (query-fn :insert-role-menu! {:role_id role-id :menu_id (if (string? m-id) (parse-long m-id) m-id)})))
  role-id)

(defn delete-role!
  "逻辑删除角色。"
  [{:keys [query-fn]} role-id]
  (query-fn :delete-role! {:role_id role-id}))

(defn list-allocated-users
  "查询已分配该角色的用户列表。"
  [{:keys [query-fn]} {:keys [role-id user-name phonenumber]}]
  (query-fn :list-users-by-role {:role_id role-id :user_name user-name :phonenumber phonenumber}))

(defn list-unallocated-users
  "查询未分配该角色的用户列表。"
  [{:keys [query-fn]} {:keys [role-id user-name phonenumber]}]
  (query-fn :list-users-not-in-role {:role_id role-id :user_name user-name :phonenumber phonenumber}))

(defn cancel-auth-user!
  "取消用户角色授权。"
  [{:keys [query-fn]} {:keys [role-id user-id]}]
  (query-fn :delete-user-role! {:role_id role-id :user_id user-id}))

(defn cancel-auth-user-all!
  "批量取消用户角色授权。"
  [{:keys [query-fn]} {:keys [role-id user-ids]}]
  (doseq [uid user-ids]
    (query-fn :delete-user-role! {:role_id role-id :user_id uid})))

(defn select-auth-user-all!
  "批量授权用户角色（批量插入）。"
  [{:keys [query-fn]} {:keys [role-id user-ids]}]
  (doseq [uid user-ids]
    (query-fn :insert-user-role! {:role_id role-id :user_id uid})))

(defn dept-tree-by-role
  "获取角色关联的部门树。"
  [{:keys [query-fn]} dept-service role-id]
  (let [role (query-fn :find-role-by-id {:role_id role-id})
        depts (dept-service (:list-depts dept-service))]
    {:depts depts :checked-keys (when role [(:dept_ids role)])}))

(defn get-role-perms
  "获取角色的所有权限标识。"
  [{:keys [query-fn]} role-id]
  (->> (query-fn :list-menus-by-role-id {:role_id role-id})
       (map :perms)
       (remove nil?)
       (remove empty?)
       (into #{})))
