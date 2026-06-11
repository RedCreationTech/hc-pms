(ns com.ruoyi.rouyi.frontend.api
  "HTTP API 客户端封装。"
  (:require
    [ajax.core :as ajax]
    [re-frame.db :as rf-db]))

(def api-base "/api")

(defn- get-token []
  (get-in @rf-db/app-db [:auth :token]))

(defn- request
  "发起 HTTP 请求，从 re-frame app-db 读取 token。"
  [{:keys [method uri params on-success on-error]}]
  (ajax/ajax-request
    {:method method
     :uri (str api-base uri)
     :params params
     :headers (when-let [token (get-token)]
                {"Authorization" (str "Bearer " token)})
     :format (ajax/json-request-format)
     :response-format (ajax/json-response-format {:keywords? true})
     :handler (fn [[ok result]]
                (if ok
                  (on-success result)
                  (on-error result)))}))

(defn login
  "用户登录。"
  [params on-success on-error]
  (request {:method :post :uri "/auth/login" :params params
            :on-success on-success :on-error on-error}))

(defn get-info
  "获取当前用户信息。"
  [on-success on-error]
  (request {:method :get :uri "/auth/getInfo"
            :on-success on-success :on-error on-error}))

(defn list-users
  "获取用户列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/user" :params params
            :on-success on-success :on-error on-error}))

(defn list-dict-types
  "获取字典类型列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/dict/type" :params params
            :on-success on-success :on-error on-error}))

(defn list-dict-data
  "获取字典数据列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/dict/data" :params params
            :on-success on-success :on-error on-error}))

(defn list-configs
  "获取参数配置列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/config" :params params
            :on-success on-success :on-error on-error}))

(defn list-oper-logs
  "获取操作日志列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/oper-log" :params params
            :on-success on-success :on-error on-error}))

(defn list-login-logs
  "获取登录日志列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/login-log" :params params
            :on-success on-success :on-error on-error}))

(defn list-online-users
  "获取在线用户列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/online" :params params
            :on-success on-success :on-error on-error}))

(defn list-jobs
  "获取定时任务列表。"
  [params on-success on-error]
  (request {:method :get :uri "/system/job" :params params
            :on-success on-success :on-error on-error}))

(defn list-job-logs
  "获取定时任务日志。"
  [params on-success on-error]
  (request {:method :get :uri "/system/job-log" :params params
            :on-success on-success :on-error on-error}))

(defn logout
  "用户登出。"
  [on-success on-error]
  (request {:method :post :uri "/auth/logout"
            :on-success on-success :on-error on-error}))
