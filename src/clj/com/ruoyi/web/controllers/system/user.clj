(ns com.ruoyi.web.controllers.system.user
  "用户管理控制器，支持数据权限过滤。"
  (:require
   [com.ruoyi.domain.system.user :as user-service]
   [com.ruoyi.infra.data-perm :as data-perm]
   [clojure.string :as str]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- parse-int [v]
  (when v (Integer/parseInt v)))

(defn- parse-id-list
  "解析 RuoYi 风格逗号分隔用户 ID。"
  [ids]
  (->> (str/split (str ids) #",")
       (map str/trim)
       (remove str/blank?)
       (mapv parse-long)))

(defn- current-user
  "读取当前登录用户详情，用于列表数据权限判断。"
  [user-service identity]
  (when-let [user-id (:user-id identity)]
    (user-service/find-user-by-id user-service user-id)))

(defn- user-list-query
  "把 HTTP 字符串查询参数转换为用户列表领域查询参数。"
  [raw current-user]
  {:page-num (or (parse-int (get raw "page")) 1)
   :page-size (or (parse-int (get raw "size")) 10)
   :user_name (get raw "user_name")
   :phonenumber (get raw "phonenumber")
   :status (get raw "status")
   :dept_id (get raw "dept_id")
   :beginTime (get raw "beginTime")
   :endTime (get raw "endTime")
   :current-user current-user})

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-users
  "查询用户列表（带时间范围、部门下级和数据权限过滤）。"
  [{:keys [user-service]} request]
  (let [raw (:query-params request)
        identity (:identity request)
        params (user-list-query raw (current-user user-service identity))
        result (user-service/list-users user-service params)]
    (ok {:total (:total result) :rows (:rows result)})))

(defn get-user
  "获取用户详情。"
  [{:keys [user-service]} request]
  (let [user-id (parse-long (get-in request [:path-params :id]))]
    (if-let [user (user-service/find-user-by-id user-service user-id)]
      (ok user)
      (fail "用户不存在"))))

(defn create-user
  "创建用户。"
  [{:keys [user-service]} request]
  (try
    (let [body (:body-params request)
          identity (:identity request)
          params (merge {:dept_id 1 :user_type "00" :sex "0" :status "0"
                         :email "" :phonenumber "" :avatar "" :remark ""
                         :create_by (:user_name identity "")
                         :roles [] :posts []}
                        body)
          ;; 空字符串表单值会被 muuntaja 解析为 nil，需 or 兜底避免覆盖默认值
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
      (let [user-id (user-service/create-user! user-service params)]
        (ok (str "创建成功: " user-id))))
    (catch Exception e
      (fail (.getMessage e)))))

(defn update-user
  "更新用户。"
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          body (:body-params request)
          identity (:identity request)
          existing (user-service/find-user-by-id user-service user-id)
          params (merge (select-keys existing [:dept_id :user_name :nick_name :user_type
                                               :email :phonenumber :sex :avatar
                                               :status :remark :password])
                        (dissoc body :roles :posts)
                        {:user-id user-id :update_by (:user_name identity "")}
                        (when (:roles body) {:roles (:roles body)})
                        (when (:posts body) {:posts (:posts body)}))]
      (user-service/update-user! user-service params)
      (ok "更新成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn delete-user
  "删除一个或多个用户，路径参数兼容逗号分隔 ID。"
  [{:keys [user-service]} request]
  (try
    (let [user-ids (parse-id-list (get-in request [:path-params :id]))]
      (when (empty? user-ids)
        (throw (Exception. "请选择要删除的用户")))
      (user-service/delete-users! user-service user-ids)
      (ok "删除成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn change-status
  "修改用户状态。"
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          status (get-in request [:path-params :status])
          existing (user-service/find-user-by-id user-service user-id)
          identity (:identity request)
          params (merge (select-keys existing [:dept_id :user_name :nick_name :user_type
                                               :email :phonenumber :sex :avatar
                                               :status :remark :password])
                        {:user-id user-id :status status :update_by (:user_name identity "")})]
      (user-service/update-user! user-service params)
      (ok "状态修改成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn reset-password
  "重置用户密码。"
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          password (get-in request [:body-params :password] "123456")
          existing (user-service/find-user-by-id user-service user-id)
          identity (:identity request)
          params (merge (select-keys existing [:dept_id :user_name :nick_name :user_type
                                               :email :phonenumber :sex :avatar
                                               :status :remark])
                        {:user-id user-id :password password :update_by (:user_name identity "")})]
      (user-service/update-user! user-service params)
      (ok "密码重置成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn import-users
  "导入用户。"
  [{:keys [user-service]} request]
  (try
    (let [body (:body-params request)
          rows (:rows body)
          identity (:identity request)
          results (mapv (fn [row]
                          (try
                            (let [params (merge {:dept_id 1 :user_type "00" :sex "0" :status "0"
                                                 :email "" :phonenumber "" :avatar "" :remark ""
                                                 :create_by (:user_name identity "") :password "123456"
                                                 :roles [] :posts []}
                                                row)]
                              (when (:user_name params)
                                (user-service/create-user! user-service params))
                              {:user_name (:user_name params) :status "success"})
                            (catch Exception e
                              {:user_name (:user_name row) :status "failed" :msg (.getMessage e)})))
                        rows)]
      (ok {:total (count results)
           :success (count (filter #(= "success" (:status %)) results))
           :failed (count (filter #(= "failed" (:status %)) results))
           :details results}))
    (catch Exception e
      (fail (.getMessage e)))))

(defn export-users
  "导出用户CSV。"
  [{:keys [user-service]} request]
  (try
    (let [identity (:identity request)
          raw (:query-params request)
          data-perm-filter (data-perm/data-perm-filter identity "default" :alias "u")
          params (merge {:page-num 1 :page-size 10000}
                        (dissoc raw "page" "size")
                        (:params data-perm-filter))
          result (user-service/list-users user-service params)
          rows (:rows result)
          csv-header "user_name,nick_name,email,phonenumber,sex,status,dept_id,remark"
          csv-rows (mapv (fn [r]
                           (str (:user_name r) "," (:nick_name r) "," (:email r) ","
                                (:phonenumber r) "," (:sex r) "," (:status r) ","
                                (:dept_id r) "," (:remark r)))
                         rows)
          csv (str csv-header "\n" (clojure.string/join "\n" csv-rows))]
      (-> (response/response csv)
          (response/header "Content-Type" "text/csv; charset=utf-8")
          (response/header "Content-Disposition" "attachment; filename=users.csv")))
    (catch Exception e
      (fail (.getMessage e)))))

(defn auth-role
  "获取用户角色列表。"
  [{:keys [user-service]} request]
  (let [user-id (parse-long (get-in request [:path-params :id]))]
    (ok (user-service/get-user-roles user-service user-id))))

(defn update-auth-role
  "分配用户角色。"
  [{:keys [user-service]} request]
  (let [user-id (parse-long (get-in request [:path-params :id]))
        role-ids (get-in request [:body-params :role_ids])]
    (user-service/update-user-roles! user-service {:user-id user-id :role-ids role-ids})
    (ok "角色分配成功")))

(defn import-template
  "下载用户导入模板。"
  [_ _]
  (let [csv "user_name,nick_name,email,phonenumber,sex,status,dept_id,remark\n,张三,,13800138000,0,0,,\n"]
    (-> (response/response csv)
        (response/header "Content-Type" "text/csv; charset=utf-8")
        (response/header "Content-Disposition" "attachment; filename=user_import_template.csv"))))
