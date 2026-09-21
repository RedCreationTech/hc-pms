(ns com.ruoyi.web.controllers.business.bpm-mgmt
  "BPM 管理套件控制器(用户分组/监听器/表达式/设置 通用 CRUD)."
  (:require
    [com.ruoyi.domain.business.bpm-mgmt :as mgmt]
    [com.ruoyi.web.controllers.business.util :as bu]
    [ring.util.response :as response]))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- fail
  [msg]
  (-> (response/response {:code 500 :msg msg}) (response/content-type "application/json")))


(defn- wrap-err
  [f]
  (try (f) (catch Exception e (fail (.getMessage e)))))


(defn- module-of
  [request]
  (keyword (get-in request [:path-params :module])))


(defn- parse-id
  [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))


(defn- current-user
  [request]
  (get-in request [:identity :user-name]))


(defn list-items
  [{:keys [mgmt-service]} request]
  (wrap-err #(ok (mgmt/list-items mgmt-service (module-of request) (bu/kquery request)))))


(defn get-item
  [{:keys [mgmt-service]} request]
  (wrap-err #(ok (mgmt/get-item mgmt-service (module-of request) (parse-id request)))))


(defn create-item
  [{:keys [mgmt-service]} request]
  (wrap-err #(do (mgmt/create-item mgmt-service (module-of request) (:body-params request) (current-user request))
                 (ok nil))))


(defn update-item
  [{:keys [mgmt-service]} request]
  (wrap-err #(do (mgmt/update-item mgmt-service (module-of request) (parse-id request) (:body-params request) (current-user request))
                 (ok nil))))


(defn delete-item
  [{:keys [mgmt-service]} request]
  (wrap-err #(do (mgmt/delete-item mgmt-service (module-of request) (parse-id request)) (ok nil))))
