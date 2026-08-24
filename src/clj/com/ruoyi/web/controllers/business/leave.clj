(ns com.ruoyi.web.controllers.business.leave
  "请假申请控制器 —— 业务 + BPM 集成。"
  (:require
   [com.ruoyi.domain.business.leave :as leave]
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

(defn- wrap-err [f]
  (try (f) (catch Exception e (fail (.getMessage e)))))

(defn- parse-id [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))

(defn- current-user [request]
  (get-in request [:identity :user-name]))

(defn- current-user-id [request]
  (get-in request [:identity :user-id]))

(defn list-leaves [{:keys [leave-service]} request]
  (wrap-err #(ok (leave/leave-list leave-service (:query-params request)))))

(defn get-leave [{:keys [leave-service]} request]
  (wrap-err #(if-let [l (leave/leave-get leave-service (parse-id request))]
               (ok l) (fail 404 "请假单不存在"))))

(defn start-leave
  "发起请假申请：入流程并进入审批。body: {:days x :reason y}"
  [{:keys [leave-service]} request]
  (wrap-err #(let [{:keys [days reason]} (:body-params request)]
               (ok (leave/leave-start! leave-service
                                       (current-user-id request)
                                       (current-user request)
                                       days reason)))))

(defn delete-leave [{:keys [leave-service]} request]
  (wrap-err #(do (leave/leave-delete leave-service (parse-id request)) (ok nil))))
