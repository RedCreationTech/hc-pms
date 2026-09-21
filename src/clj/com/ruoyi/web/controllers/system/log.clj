(ns com.ruoyi.web.controllers.system.log
  "日志审计控制器."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.system.log :as log-service]
    [ring.util.response :as response]))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- parse-ids
  [s]
  (->> (str/split (str s) #",")
       (map str/trim)
       (remove str/blank?)
       (map #(Long/parseLong %))))


(defn list-oper-logs
  "查询操作日志列表."
  [{:keys [log-service]} request]
  (let [result (log-service/list-oper-logs log-service (:query-params request))]
    (ok {:total (:total result) :rows (:rows result)})))


(defn clear-oper-logs
  "清空操作日志."
  [{:keys [log-service]} request]
  (let [params (or (:query-params request) {})]
    (log-service/clear-oper-logs! log-service
                                  (merge {:begin_time nil :end_time nil} params))
    (ok "清空成功")))


(defn delete-oper-logs
  "删除操作日志."
  [{:keys [log-service]} request]
  (log-service/delete-oper-logs! log-service (parse-ids (get-in request [:path-params :ids])))
  (ok "删除成功"))


(defn list-login-logs
  "查询登录日志列表."
  [{:keys [log-service]} request]
  (let [result (log-service/list-login-logs log-service (:query-params request))]
    (ok {:total (:total result) :rows (:rows result)})))


(defn clear-login-logs
  "清空登录日志."
  [{:keys [log-service]} request]
  (let [params (or (:query-params request) {})]
    (log-service/clear-login-logs! log-service
                                   (merge {:begin_time nil :end_time nil} params))
    (ok "清空成功")))


(defn delete-login-logs
  "删除登录日志."
  [{:keys [log-service]} request]
  (log-service/delete-login-logs! log-service (parse-ids (get-in request [:path-params :ids])))
  (ok "删除成功"))


(defn list-online-users
  "查询在线用户列表."
  [{:keys [log-service]} request]
  (let [result (log-service/list-online-users log-service (:query-params request))]
    (ok {:total (:total result) :rows (:rows result)})))


(defn kick-online-user
  "强退在线用户."
  [{:keys [log-service]} request]
  (let [session-id (get-in request [:path-params :id])]
    (log-service/delete-online-user! log-service session-id)
    (ok "强退成功")))
