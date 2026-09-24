(ns com.ruoyi.web.controllers.system.user
  "用户管理控制器. 列表, 详情与全部写操作按当前用户的数据权限范围校验 (越权返回 403)."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.system.data-scope :as data-scope]
    [com.ruoyi.domain.system.user :as user-service]
    [com.ruoyi.infra.online :as online]
    [ring.util.response :as response]))


(def editable-fields
  "用户管理可维护的字段 (账号创建后不可改名, 密码只经重置密码修改)."
  [:dept_id :nick_name :email :phonenumber :sex :status :remark])


(defn- init-password
  "重置与导入的默认密码: 读取参数 sys.user.initPassword."
  [user-service]
  (or (some-> ((:query-fn user-service) :find-config-by-key {:config_key "sys.user.initPassword"}) :config_value not-empty)
      "123456"))


(defn- revoke!
  "撤销该用户已签发的令牌 (停用, 删除, 重置密码)."
  [user-service user-id]
  (when-let [user ((:query-fn user-service) :find-user-by-id {:user_id user-id})]
    (online/revoke-user! user-id (:user_name user))))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- parse-int
  [v]
  (when v (Integer/parseInt v)))


(defn- parse-id-list
  "解析 RuoYi 风格逗号分隔用户 ID."
  [ids]
  (->> (str/split (str ids) #",")
       (map str/trim)
       (remove str/blank?)
       (mapv parse-long)))


(defn- scope
  "当前实时身份 (权限中间件附加的 :actor) 的数据范围."
  [user-service request]
  (data-scope/scope-of (:query-fn user-service) (:actor request)))


(defn- check-user!
  "目标用户必须在当前用户的数据范围内."
  [user-service request user-id]
  (data-scope/check-user! (:query-fn user-service) (scope user-service request) user-id))


(defn- user-list-query
  "把 HTTP 字符串查询参数转换为用户列表领域查询参数."
  [raw scope]
  {:page-num (or (parse-int (get raw "page")) 1)
   :page-size (or (parse-int (get raw "size")) 10)
   :user_name (get raw "user_name")
   :phonenumber (get raw "phonenumber")
   :status (get raw "status")
   :dept_id (get raw "dept_id")
   :beginTime (get raw "beginTime")
   :endTime (get raw "endTime")
   :scope scope})


(defn- fail
  "失败响应: 字符串为 500; 异常取 ex-data 中的整数 :status (如 403 越权, 404 不存在)."
  [e]
  (let [status (when (instance? Throwable e) (:status (ex-data e)))
        msg (if (instance? Throwable e) (.getMessage ^Throwable e) (str e))]
    (-> (response/response {:code (if (integer? status) status 500) :msg msg})
        (response/content-type "application/json"))))


(defn list-users
  "查询用户列表(带时间范围,部门下级和数据权限过滤)."
  [{:keys [user-service]} request]
  (let [raw (:query-params request)
        params (user-list-query raw (scope user-service request))
        result (user-service/list-users user-service params)]
    (ok {:total (:total result) :rows (:rows result)})))


(defn get-user
  "获取用户详情."
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))]
      (check-user! user-service request user-id)
      (ok (user-service/public-user (user-service/find-user-by-id user-service user-id))))
    (catch Exception e (fail e))))


(defn user-options
  "选人组件: 有效用户的编号, 账号, 昵称与部门."
  [{:keys [user-service]} _]
  (ok (user-service/user-options user-service)))


(defn create-user
  "创建用户."
  [{:keys [user-service]} request]
  (try
    (let [body (:body-params request)
          identity (:identity request)
          params (merge {:dept_id 1 :user_type "00" :sex "0" :status "0"
                         :email "" :phonenumber "" :avatar "" :remark ""
                         :create_by (:user_name identity "")
                         :roles [] :posts []}
                        (select-keys body (into [:user_name :password :roles :posts] editable-fields)))
          ;; 空字符串表单值会被 muuntaja 解析为 nil,需 or 兜底避免覆盖默认值
          params (-> params
                     (update :email #(or % ""))
                     (update :phonenumber #(or % ""))
                     (update :avatar #(or % ""))
                     (update :remark #(or % ""))
                     (update :user_type #(or % "00"))
                     (update :sex #(or % "0"))
                     (update :status #(or % "0")))]
      (when-not (:user_name params)
        (throw (Exception. "用户名不能为空")))
      (when-not (:nick_name params)
        (throw (Exception. "用户昵称不能为空")))
      (when-not (:password params)
        (throw (Exception. "密码不能为空")))
      (data-scope/check-dept! (scope user-service request) (:dept_id params))
      (user-service/check-role-grant! user-service (:actor request) (:roles params))
      (let [user-id (user-service/create-user! user-service params)]
        (ok (str "创建成功: " user-id))))
    (catch Exception e
      (fail e))))


(defn update-user
  "更新用户 (不含账号与密码). 超级管理员用户不可在此修改; 停用会撤销其令牌."
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          body (:body-params request)
          identity (:identity request)
          existing (user-service/find-user-by-id user-service user-id)]
      (when-not existing (throw (Exception. "用户不存在")))
      (user-service/check-user-allowed! user-id)
      (check-user! user-service request user-id)
      (when (some? (:dept_id body))
        (data-scope/check-dept! (scope user-service request) (:dept_id body)))
      (when (contains? body :roles)
        (user-service/check-role-grant! user-service (:actor request)
                                        (into (vec (:roles body)) (map :role_id (:roles existing)))))
      (user-service/update-user! user-service
                                 (merge (select-keys body editable-fields)
                                        {:user-id user-id :update_by (:user_name identity "")}
                                        (when (contains? body :roles) {:roles (vec (:roles body))})
                                        (when (contains? body :posts) {:posts (vec (:posts body))})))
      (when (= "1" (str (:status body))) (revoke! user-service user-id))
      (ok "更新成功"))
    (catch Exception e
      (fail e))))


(defn delete-user
  "删除一个或多个用户,路径参数兼容逗号分隔 ID."
  [{:keys [user-service]} request]
  (try
    (let [user-ids (parse-id-list (get-in request [:path-params :id]))]
      (when (empty? user-ids)
        (throw (Exception. "请选择要删除的用户")))
      (when (some #{(get-in request [:identity :user-id])} user-ids)
        (throw (Exception. "当前用户不能删除")))
      (doseq [id user-ids]
        (user-service/check-user-allowed! id)
        (check-user! user-service request id))
      (user-service/delete-users! user-service user-ids)
      (doseq [id user-ids] (online/revoke-user! id nil))
      (ok "删除成功"))
    (catch Exception e
      (fail e))))


(defn change-status
  "修改用户状态; 停用立即撤销其令牌."
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          status (get-in request [:path-params :status])
          identity (:identity request)]
      (user-service/check-user-allowed! user-id)
      (check-user! user-service request user-id)
      (when-not (#{"0" "1"} status) (throw (Exception. "状态只能为 0 或 1")))
      (when (and (= "1" status) (= user-id (:user-id identity)))
        (throw (Exception. "不能停用当前用户")))
      (user-service/update-user! user-service {:user-id user-id :status status :update_by (:user_name identity "")})
      (when (= "1" status) (revoke! user-service user-id))
      (ok "状态修改成功"))
    (catch Exception e
      (fail e))))


(defn reset-password
  "重置用户密码 (未提供时使用参数 sys.user.initPassword), 并撤销其已签发的令牌."
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          password (or (not-empty (str (get-in request [:body-params :password] ""))) (init-password user-service))
          identity (:identity request)]
      (user-service/check-user-allowed! user-id)
      (check-user! user-service request user-id)
      (user-service/update-user! user-service {:user-id user-id :password password :update_by (:user_name identity "")})
      (revoke! user-service user-id)
      (ok "密码重置成功"))
    (catch Exception e
      (fail e))))


(defn auth-role
  "获取用户角色列表."
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))]
      (check-user! user-service request user-id)
      (ok (user-service/get-user-roles user-service user-id)))
    (catch Exception e (fail e))))


(defn update-auth-role
  "分配用户角色."
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          role-ids (vec (get-in request [:body-params :role_ids]))]
      (user-service/check-user-allowed! user-id)
      (check-user! user-service request user-id)
      (user-service/check-role-grant! user-service (:actor request)
                                      (into role-ids (map :role_id (user-service/get-user-roles user-service user-id))))
      (user-service/update-user-roles! user-service {:user-id user-id :role-ids role-ids})
      (ok "角色分配成功"))
    (catch Exception e
      (fail e))))
