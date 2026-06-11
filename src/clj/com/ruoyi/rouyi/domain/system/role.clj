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
  (query-fn :update-role! (-> params
                              (dissoc :menu-ids :role-id)
                              (assoc :role_id role-id)))
  (when menu-ids
    (query-fn :delete-role-menus! {:role_id role-id})
    (doseq [m-id menu-ids]
      (query-fn :insert-role-menu! {:role_id role-id :menu_id m-id})))
  role-id)

(defn delete-role!
  "逻辑删除角色。"
  [{:keys [query-fn]} role-id]
  (query-fn :delete-role! {:role_id role-id}))

(defn get-role-perms
  "获取角色的所有权限标识。"
  [{:keys [query-fn]} role-id]
  (->> (query-fn :list-menus-by-role-id {:role_id role-id})
       (map :perms)
       (remove nil?)
       (remove empty?)
       (into #{})))
