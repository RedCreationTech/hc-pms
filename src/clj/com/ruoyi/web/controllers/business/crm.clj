(ns com.ruoyi.web.controllers.business.crm
  "CRM 客户管理控制器。"
  (:require
    [com.ruoyi.domain.business.crm :as crm]
    [com.ruoyi.web.controllers.business.util :as bu]
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


(defn- parse-id
  [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))


(defn- current-user
  [request]
  (get-in request [:identity :user-name]))


(defn list-customers
  [{:keys [crm-service]} request]
  (wrap-err #(ok (crm/customer-list crm-service (bu/kquery request)))))


(defn get-customer
  [{:keys [crm-service]} request]
  (wrap-err #(if-let [c (crm/customer-get crm-service (parse-id request))]
               (ok c) (fail 404 "客户不存在"))))


(defn create-customer
  [{:keys [crm-service]} request]
  (wrap-err #(do (crm/customer-create crm-service (:body-params request) (current-user request)) (ok nil))))


(defn update-customer
  [{:keys [crm-service]} request]
  (wrap-err #(do (crm/customer-update crm-service (assoc (:body-params request) :customer_id (parse-id request)) (current-user request)) (ok nil))))


(defn delete-customer
  [{:keys [crm-service]} request]
  (wrap-err #(do (crm/customer-delete crm-service (parse-id request)) (ok nil))))
