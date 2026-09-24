(ns com.ruoyi.domain.system.role
  "角色领域服务,处理角色 CRUD,菜单授权与数据权限."
  (:require
    [clojure.set :as set]
    [com.ruoyi.infra.db :as db]))


(defn list-roles
  "查询角色列表."
  [{:keys [query-fn]} params]
  (query-fn :list-roles (merge {:role_name nil :role_key nil :status nil} params)))


(defn find-role-by-id
  "根据ID查询角色详情,包含关联菜单ID列表."
  [{:keys [query-fn]} role-id]
  (when-let [role (query-fn :find-role-by-id {:role_id role-id})]
    (assoc role :menu-ids (mapv :menu_id (query-fn :list-menus-by-role-id {:role_id role-id})))))


(def role-create-defaults
  "创建角色时的默认字段,避免前端未提交隐藏字段导致 HugSQL 参数缺失."
  {:role_sort 0
   :data_scope "1"
   :menu_check_strictly true
   :dept_check_strictly true
   :status "0"
   :create_by ""
   :remark nil})


(defn- apply-create-defaults
  "补齐创建角色所需参数.必填默认字段为 nil 时也使用默认值,备注允许为空."
  [params]
  (let [params (dissoc params :menu-ids)]
    (reduce-kv (fn [acc k default-value]
                 (if (and (contains? acc k)
                          (or (= k :remark) (some? (get acc k))))
                   acc
                   (assoc acc k default-value)))
               params
               role-create-defaults)))


(def super-admin-role-key "admin")


(defn- load-role
  [query-fn role-id]
  (query-fn :find-role-by-id {:role_id role-id}))


(defn super-admin-role?
  [role]
  (or (= 1 (:role_id role)) (= super-admin-role-key (:role_key role))))


(defn check-role-allowed!
  "不允许修改, 停用, 删除超级管理员角色或调整其数据权限 (对应若依 checkRoleAllowed)."
  [{:keys [query-fn]} role-id]
  (when (super-admin-role? (load-role query-fn role-id))
    (throw (ex-info "不允许操作超级管理员角色" {:status 403}))))


(defn check-role-unique!
  "角色名称与权限字符唯一; admin 权限字符保留给超级管理员角色."
  [{:keys [query-fn]} {:keys [role_name role_key]} role-id]
  (let [roles (query-fn :list-roles {:role_name nil :role_key nil :status nil})
        other? #(not= role-id (:role_id %))]
    (when (and role_name (some #(and (other? %) (= role_name (:role_name %))) roles))
      (throw (ex-info (str "角色名称'" role_name "'已存在") {:status 400})))
    (when (and role_key (some #(and (other? %) (= role_key (:role_key %))) roles))
      (throw (ex-info (str "角色权限'" role_key "'已存在") {:status 400})))
    (when (and (= super-admin-role-key role_key) (not= 1 role-id))
      (throw (ex-info "权限字符 admin 保留给超级管理员角色" {:status 400})))))


(defn check-admin-assignment!
  "只有超级管理员可以把用户加入或移出超级管理员角色, 且超级管理员用户不能移出."
  [{:keys [query-fn]} actor role-id user-ids]
  (when (super-admin-role? (load-role query-fn role-id))
    (when-not (:admin? actor)
      (throw (ex-info "只有超级管理员可以授予超级管理员角色" {:status 403})))
    (when (some #{1} user-ids)
      (throw (ex-info "不允许操作超级管理员用户" {:status 403})))))


(defn check-role-unused!
  "已分配给用户的角色不能删除."
  [{:keys [query-fn]} role-id]
  (when (seq (query-fn :list-users-by-role {:role_id role-id :user_name nil :phonenumber nil}))
    (throw (ex-info "角色已分配给用户, 不能删除" {:status 400}))))


(defn create-role!
  "创建角色并绑定菜单权限."
  [{:keys [query-fn db]} {:keys [menu-ids] :as params}]
  (let [role-id (db/insert-and-get-id! query-fn db :create-role! (apply-create-defaults params))]
    (doseq [m-id menu-ids]
      (query-fn :insert-role-menu! {:role_id role-id :menu_id m-id}))
    role-id))


(defn update-role!
  "更新角色及菜单权限."
  [{:keys [query-fn]} {:keys [role-id menu-ids] :as params}]
  ;; 更新角色基本信息(只保留实际字段)
  (let [role-params (merge {:role_name nil :role_key nil :role_sort nil :data_scope nil
                            :menu_check_strictly nil :dept_check_strictly nil
                            :status nil :remark nil}
                           (select-keys params [:role_name :role_key :role_sort :data_scope
                                                :menu_check_strictly :dept_check_strictly
                                                :status :remark])
                           {:role_id role-id})]
    (query-fn :update-role! role-params))
  ;; 更新菜单权限
  (when menu-ids
    (query-fn :delete-role-menus! {:role_id role-id})
    (doseq [m-id menu-ids]
      (query-fn :insert-role-menu! {:role_id role-id :menu_id (if (string? m-id) (parse-long m-id) m-id)})))
  role-id)


(defn delete-role!
  "逻辑删除角色."
  [{:keys [query-fn]} role-id]
  (query-fn :delete-role! {:role_id role-id}))


(defn list-allocated-users
  "查询已分配该角色的用户列表."
  [{:keys [query-fn]} {:keys [role-id user-name phonenumber]}]
  (query-fn :list-users-by-role {:role_id role-id :user_name user-name :phonenumber phonenumber}))


(defn list-unallocated-users
  "查询未分配该角色的用户列表."
  [{:keys [query-fn]} {:keys [role-id user-name phonenumber]}]
  (query-fn :list-users-not-in-role {:role_id role-id :user_name user-name :phonenumber phonenumber}))


(defn cancel-auth-user!
  "取消用户角色授权."
  [{:keys [query-fn]} {:keys [role-id user-id]}]
  (query-fn :delete-user-role! {:role_id role-id :user_id user-id}))


(defn cancel-auth-user-all!
  "批量取消用户角色授权."
  [{:keys [query-fn]} {:keys [role-id user-ids]}]
  (doseq [uid user-ids]
    (query-fn :delete-user-role! {:role_id role-id :user_id uid})))


(defn select-auth-user-all!
  "批量授权用户角色(批量插入)."
  [{:keys [query-fn]} {:keys [role-id user-ids]}]
  (doseq [uid user-ids]
    (query-fn :insert-user-role! {:role_id role-id :user_id uid})))


(defn dept-tree-by-role
  "角色数据权限弹窗: 全部有效部门 + 该角色已选的自定义部门."
  [{:keys [query-fn]} role-id]
  {:depts (query-fn :dept-options {})
   :checked-keys (mapv :dept_id (query-fn :list-role-dept-ids {:role_id role-id}))})


(def data-scopes
  "数据权限范围: 1 全部, 2 自定义, 3 本部门, 4 本部门及以下, 5 仅本人."
  #{"1" "2" "3" "4" "5"})


(defn update-data-scope!
  "保存角色数据权限范围; 自定义范围时替换 sys_role_dept, 其他范围清空自定义部门."
  [{:keys [query-fn]} role-id data-scope dept-ids]
  (let [data-scope (str data-scope)
        dept-ids (->> dept-ids
                      (map #(if (string? %) (parse-long %) %))
                      (remove nil?)
                      distinct)]
    (when-not (contains? data-scopes data-scope)
      (throw (ex-info "数据权限范围无效" {:status 400})))
    (when (and (= "2" data-scope) (empty? dept-ids))
      (throw (ex-info "自定义数据权限需要至少选择一个部门" {:status 400})))
    (query-fn :update-role! {:role_id role-id :role_name nil :role_key nil :role_sort nil :data_scope data-scope
                             :menu_check_strictly nil :dept_check_strictly nil :status nil :remark nil})
    (query-fn :delete-role-depts! {:role_id role-id})
    (when (= "2" data-scope)
      (doseq [d dept-ids]
        (query-fn :insert-role-dept! {:role_id role-id :dept_id d})))
    role-id))


(defn get-role-perms
  "获取角色的所有权限标识."
  [{:keys [query-fn]} role-id]
  (->> (query-fn :list-menus-by-role-id {:role_id role-id})
       (map :perms)
       (remove nil?)
       (remove empty?)
       (into #{})))
