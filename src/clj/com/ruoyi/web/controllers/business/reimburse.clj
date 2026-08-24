(ns com.ruoyi.web.controllers.business.reimburse
  "报销申请控制器 —— 业务 + BPM 集成。"
  (:require
   [com.ruoyi.domain.business.reimburse :as reimburse]
   [ring.util.response :as response]
   [com.ruoyi.web.controllers.business.util :as bu]))

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

(defn- wrap-err [f]
  (try (f) (catch Exception e (fail (.getMessage e)))))

(defn- parse-id [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))

(defn- current-user [request]
  (get-in request [:identity :user-name]))

(defn- current-user-id [request]
  (get-in request [:identity :user-id]))

(defn list-reimburses [{:keys [reimburse-service]} request]
  (wrap-err #(ok (reimburse/reimburse-list reimburse-service (bu/kquery request)))))

(defn get-reimburse [{:keys [reimburse-service]} request]
  (wrap-err #(if-let [r (reimburse/reimburse-get reimburse-service (parse-id request))]
               (ok r) (fail 404 "报销单不存在"))))

(defn start-reimburse
  "发起报销申请。body: {:amount x :reason y}"
  [{:keys [reimburse-service]} request]
  (wrap-err #(let [{:keys [amount reason]} (:body-params request)]
               (ok (reimburse/reimburse-start! reimburse-service
                                               (current-user-id request)
                                               (current-user request)
                                               amount reason)))))

(defn delete-reimburse [{:keys [reimburse-service]} request]
  (wrap-err #(do (reimburse/reimburse-delete reimburse-service (parse-id request)) (ok nil))))
