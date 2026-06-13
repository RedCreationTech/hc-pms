(ns com.ruoyi.web.controllers.system.form-template
  "表单模板控制器。"
  (:require
   [clojure.string :as str]
   [com.ruoyi.domain.system.form-template :as form-template-service]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn- current-user-name [request]
  (get-in request [:identity :user-name] ""))

(defn- ->snake [params]
  "将查询参数键统一转为 snake_case。"
  (reduce-kv (fn [m k v]
               (assoc m
                      (keyword (str/replace (name k) #"([a-z])([A-Z])" "$1_$2"))
                      v))
             {}
             params))

(defn list-form-templates
  "查询表单模板列表。"
  [{:keys [form-template-service]} request]
  (let [params (->snake (:query-params request))]
    (ok (form-template-service/list-form-templates form-template-service params))))

(defn get-form-template
  "根据ID获取表单模板。"
  [{:keys [form-template-service]} request]
  (let [id (parse-long (get-in request [:path-params :id]))]
    (if-let [template (form-template-service/find-form-template-by-id form-template-service id)]
      (ok template)
      (fail "模板不存在"))))

(defn create-form-template
  "创建表单模板。"
  [{:keys [form-template-service]} request]
  (try
    (let [params (assoc (:body-params request) :create_by (current-user-name request))
          id (form-template-service/create-form-template! form-template-service params)]
      (ok (str "创建成功: " id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-form-template
  "更新表单模板。"
  [{:keys [form-template-service]} request]
  (try
    (let [id (parse-long (get-in request [:path-params :id]))
          params (-> (:body-params request)
                     (assoc :id id)
                     (assoc :update_by (current-user-name request)))]
      (form-template-service/update-form-template! form-template-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-form-template
  "删除表单模板。"
  [{:keys [form-template-service]} request]
  (let [id (parse-long (get-in request [:path-params :id]))]
    (form-template-service/delete-form-template! form-template-service id)
    (ok "删除成功")))
