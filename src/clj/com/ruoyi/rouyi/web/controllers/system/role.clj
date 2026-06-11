(ns com.ruoyi.rouyi.web.controllers.system.role
  "角色管理控制器。"
  (:require
    [com.ruoyi.rouyi.domain.system.role :as role-service]
    [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-roles
  [{:keys [role-service]} request]
  (ok (role-service/list-roles role-service (:query-params request))))

(defn get-role
  [{:keys [role-service]} request]
  (let [role-id (parse-long (get-in request [:path-params :id]))]
    (if-let [role (role-service/find-role-by-id role-service role-id)]
      (ok role)
      (fail "角色不存在"))))

(defn create-role
  [{:keys [role-service]} request]
  (try
    (let [role-id (role-service/create-role! role-service (:body-params request))]
      (ok (str "创建成功: " role-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-role
  [{:keys [role-service]} request]
  (try
    (let [role-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :role-id role-id)]
      (role-service/update-role! role-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-role
  [{:keys [role-service]} request]
  (let [role-id (parse-long (get-in request [:path-params :id]))]
    (role-service/delete-role! role-service role-id)
    (ok "删除成功")))
