(ns com.ruoyi.web.controllers.system.config
  "参数配置控制器."
  (:require
    [com.ruoyi.domain.system.config :as config-service]
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


(defn- current-user-name
  [request]
  (get-in request [:identity :user-name] ""))


(defn list-configs
  "查询参数列表. 查询参数为字符串键 (config_name / config_key / config_type), 空值视为不过滤."
  [{:keys [config-service]} request]
  (let [q (:query-params request)
        param (fn [k] (not-empty (str (or (get q k) (get q (keyword k)) ""))))]
    (ok (config-service/list-configs config-service {:config_name (param "config_name")
                                                     :config_key (param "config_key")
                                                     :config_type (param "config_type")}))))


(defn get-config
  [{:keys [config-service]} request]
  (let [config-id (parse-long (get-in request [:path-params :id]))]
    (if-let [cfg (config-service/find-config-by-id config-service config-id)]
      (ok cfg)
      (fail "配置不存在"))))


(defn create-config
  [{:keys [config-service]} request]
  (try
    (let [params (assoc (:body-params request) :create_by (current-user-name request))
          config-id (config-service/create-config! config-service params)]
      (ok (str "创建成功: " config-id)))
    (catch Exception e (fail (.getMessage e)))))


(defn update-config
  [{:keys [config-service]} request]
  (try
    (let [config-id (parse-long (get-in request [:path-params :id]))
          params (-> (:body-params request)
                     (assoc :config_id config-id)
                     (assoc :update_by (current-user-name request)))]
      (config-service/update-config! config-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))


(defn delete-config
  [{:keys [config-service]} request]
  (let [config-id (parse-long (get-in request [:path-params :id]))]
    (config-service/delete-config! config-service config-id)
    (ok "删除成功")))
