(ns com.ruoyi.domain.system.user
  "用户领域服务，处理用户 CRUD、密码管理与角色关联。"
  (:require
   [com.ruoyi.infra.security :as security]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.ruoyi.infra.db :as db]))

(defn list-users
  "查询用户列表，支持分页和条件筛选。"
  [{:keys [query-fn]} params]
  (let [page-num (or (:page-num params) 1)
        page-size (or (:page-size params) 10)
        offset (* (dec page-num) page-size)
        filters (merge {:user_name nil :phonenumber nil :status nil
                        :dept_id nil :params nil}
                       (-> params
                           (dissoc :page-num :page-size))
                       {:offset offset :page_size page-size})
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

(defn create-user!
  "创建新用户，自动加密密码。"
  [{:keys [query-fn db]} {:keys [password roles posts] :as params}]
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
  "逻辑删除用户。"
  [{:keys [query-fn]} user-id]
  (query-fn :delete-user! {:user_id user-id}))

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
