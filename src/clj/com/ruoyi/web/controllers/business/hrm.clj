(ns com.ruoyi.web.controllers.business.hrm
  "HRM 人力资源控制器。"
  (:require
   [com.ruoyi.domain.business.hrm :as hrm]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail
  ([msg] (fail 500 msg))
  ([code msg]
   (-> (response/response {:code code :msg msg})
       (response/content-type "application/json"))))

(defn- wrap-err
  [f]
  (try (f) (catch Exception e (fail (.getMessage e)))))

(defn- parse-id [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))

(defn- current-user [request]
  (get-in request [:identity :user-name]))

(defn list-employees [{:keys [hrm-service]} request]
  (wrap-err #(ok (hrm/employee-list hrm-service (:query-params request)))))

(defn get-employee [{:keys [hrm-service]} request]
  (wrap-err #(if-let [e (hrm/employee-get hrm-service (parse-id request))]
               (ok e) (fail 404 "员工不存在"))))

(defn create-employee [{:keys [hrm-service]} request]
  (wrap-err #(do (hrm/employee-create hrm-service (:body-params request) (current-user request))
                 (ok nil))))

(defn update-employee [{:keys [hrm-service]} request]
  (wrap-err #(do (hrm/employee-update hrm-service (assoc (:body-params request) :employee_id (parse-id request)) (current-user request))
                 (ok nil))))

(defn delete-employee [{:keys [hrm-service]} request]
  (wrap-err #(do (hrm/employee-delete hrm-service (parse-id request)) (ok nil))))
