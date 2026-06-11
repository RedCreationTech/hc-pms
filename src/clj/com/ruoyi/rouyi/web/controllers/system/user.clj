(ns com.ruoyi.rouyi.web.controllers.system.user
  "用户管理控制器。"
  (:require
    [com.ruoyi.rouyi.domain.system.user :as user-service]
    [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-users
  "查询用户列表。"
  [{:keys [user-service]} request]
  (let [params (:query-params request)
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
    (let [user-id (user-service/create-user! user-service (:body-params request))]
      (ok (str "创建成功: " user-id)))
    (catch Exception e
      (fail (.getMessage e)))))

(defn update-user
  "更新用户。"
  [{:keys [user-service]} request]
  (try
    (let [user-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :user-id user-id)]
      (user-service/update-user! user-service params)
      (ok "更新成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn delete-user
  "删除用户。"
  [{:keys [user-service]} request]
  (let [user-id (parse-long (get-in request [:path-params :id]))]
    (user-service/delete-user! user-service user-id)
    (ok "删除成功")))
