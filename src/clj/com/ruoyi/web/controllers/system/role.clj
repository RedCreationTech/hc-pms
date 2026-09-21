(ns com.ruoyi.web.controllers.system.role
  "角色管理控制器."
  (:require
    [com.ruoyi.domain.system.role :as role-service]
    [ring.util.response :as response]))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- fail
  [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))


(defn list-roles
  "查询角色列表."
  [{:keys [role-service]} request]
  (let [params (:query-params request)]
    (ok (role-service/list-roles role-service params))))


(defn get-role
  "查询角色详情."
  [{:keys [role-service]} request]
  (let [role-id (parse-long (get-in request [:path-params :id]))]
    (if-let [role (role-service/find-role-by-id role-service role-id)]
      (ok role)
      (fail "角色不存在"))))


(defn create-role
  "新增角色."
  [{:keys [role-service]} request]
  (try
    (let [role-id (role-service/create-role! role-service (:body-params request))]
      (ok (str "创建成功: " role-id)))
    (catch Exception e (fail (.getMessage e)))))


(defn update-role
  "更新角色."
  [{:keys [role-service]} request]
  (try
    (let [role-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :role-id role-id)]
      (role-service/update-role! role-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))


(defn delete-role
  "删除角色."
  [{:keys [role-service]} request]
  (let [role-id (parse-long (get-in request [:path-params :id]))]
    (role-service/delete-role! role-service role-id)
    (ok "删除成功")))


(defn change-status
  "修改角色状态."
  [{:keys [role-service]} request]
  (let [role-id (parse-long (get-in request [:path-params :id]))
        status (get-in request [:body-params :status])]
    (role-service/update-role! role-service {:role-id role-id :status status})
    (ok "状态修改成功")))


(defn- parse-user-ids
  "将逗号分隔的用户ID字符串解析为long集合."
  [s]
  (when (seq s)
    (->> (clojure.string/split s #",")
         (map clojure.string/trim)
         (remove empty?)
         (map parse-long)
         (doall))))


(defn data-scope
  "设置角色数据权限范围."
  [{:keys [role-service]} request]
  (let [params (get-in request [:parameters :body])
        role-id (:role_id params)
        data-scope (:data_scope params)]
    (role-service/update-role! role-service {:role-id role-id :data_scope data-scope})
    (ok "数据权限设置成功")))


(defn option-select
  "获取角色选项列表(下拉框用)."
  [{:keys [role-service]} _]
  (ok (role-service/list-roles role-service {:limit 999 :offset 0})))


(defn allocated-list
  "查询角色已分配用户列表."
  [{:keys [role-service user-service]} request]
  (let [q (get-in request [:parameters :query])
        role-id (:role_id q)
        user-name (:user_name q)
        phonenumber (:phonenumber q)]
    (ok (role-service/list-allocated-users role-service
                                           {:role-id role-id
                                            :user-name user-name
                                            :phonenumber phonenumber}))))


(defn unallocated-list
  "查询角色未分配用户列表."
  [{:keys [role-service user-service]} request]
  (let [q (get-in request [:parameters :query])
        role-id (:role_id q)
        user-name (:user_name q)
        phonenumber (:phonenumber q)]
    (ok (role-service/list-unallocated-users role-service
                                             {:role-id role-id
                                              :user-name user-name
                                              :phonenumber phonenumber}))))


(defn cancel-auth-user
  "取消用户角色授权."
  [{:keys [role-service]} request]
  (let [params (get-in request [:parameters :body])
        role-id (:role_id params)
        user-id (:user_id params)]
    (role-service/cancel-auth-user! role-service {:role-id role-id :user-id user-id})
    (ok "取消成功")))


(defn cancel-auth-user-all
  "批量取消用户角色授权."
  [{:keys [role-service]} request]
  (let [q (get-in request [:parameters :query])
        role-id (:role_id q)
        user-ids (parse-user-ids (:user_ids q))]
    (role-service/cancel-auth-user-all! role-service {:role-id role-id :user-ids user-ids})
    (ok "批量取消成功")))


(defn select-auth-user-all
  "批量授权用户角色."
  [{:keys [role-service]} request]
  (let [q (get-in request [:parameters :query])
        role-id (:role_id q)
        user-ids (parse-user-ids (:user_ids q))]
    (role-service/select-auth-user-all! role-service {:role-id role-id :user-ids user-ids})
    (ok "批量授权成功")))


(defn dept-tree-by-role
  "获取角色部门树."
  [{:keys [dept-service role-service]} request]
  (let [role-id (parse-long (get-in request [:path-params :id]))]
    (ok (role-service/dept-tree-by-role role-service dept-service role-id))))
