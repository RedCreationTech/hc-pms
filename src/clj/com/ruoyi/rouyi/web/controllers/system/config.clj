(ns com.ruoyi.rouyi.web.controllers.system.config
  "参数配置控制器。"
  (:require
    [com.ruoyi.rouyi.domain.system.config :as config-service]
    [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-configs
  [{:keys [config-service]} request]
  (ok (config-service/list-configs config-service (:query-params request))))

(defn get-config
  [{:keys [config-service]} request]
  (let [config-id (parse-long (get-in request [:path-params :id]))]
    (if-let [cfg (config-service/find-config-by-id config-service config-id)]
      (ok cfg)
      (fail "配置不存在"))))

(defn create-config
  [{:keys [config-service]} request]
  (try
    (let [config-id (config-service/create-config! config-service (:body-params request))]
      (ok (str "创建成功: " config-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-config
  [{:keys [config-service]} request]
  (try
    (let [config-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :config_id config-id)]
      (config-service/update-config! config-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-config
  [{:keys [config-service]} request]
  (let [config-id (parse-long (get-in request [:path-params :id]))]
    (config-service/delete-config! config-service config-id)
    (ok "删除成功")))
