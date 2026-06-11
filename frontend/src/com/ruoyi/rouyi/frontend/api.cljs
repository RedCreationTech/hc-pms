(ns com.ruoyi.rouyi.frontend.api
  "HTTP API 客户端封装。"
  (:require
    [ajax.core :as ajax]
    [re-frame.core :as rf]))

(def api-base "/api")

(defn- request
  "发起 HTTP 请求。"
  [{:keys [method uri params on-success on-error]}]
  (ajax/ajax-request
    {:method method
     :uri (str api-base uri)
     :params params
     :headers (when-let [token @(rf/subscribe [:auth/token])]
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
  (request {:method :post
            :uri "/auth/login"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn get-info
  "获取当前用户信息。"
  [on-success on-error]
  (request {:method :get
            :uri "/auth/getInfo"
            :on-success on-success
            :on-error on-error}))

(defn logout
  "用户登出。"
  [on-success on-error]
  (request {:method :post
            :uri "/auth/logout"
            :on-success on-success
            :on-error on-error}))

(defn list-users
  "查询用户列表。"
  [params on-success on-error]
  (request {:method :get
            :uri "/system/user"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn list-online-users
  "查询在线用户列表。"
  [params on-success on-error]
  (request {:method :get
            :uri "/system/online"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn force-logout
  "强退在线用户。"
  [token-id on-success on-error]
  (request {:method :delete
            :uri (str "/system/online/" token-id)
            :on-success on-success
            :on-error on-error}))

(defn list-jobs
  "查询定时任务列表。"
  [params on-success on-error]
  (request {:method :get
            :uri "/system/job"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn create-job
  "创建定时任务。"
  [params on-success on-error]
  (request {:method :post
            :uri "/system/job"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn get-job
  "获取定时任务详情。"
  [id on-success on-error]
  (request {:method :get
            :uri (str "/system/job/" id)
            :on-success on-success
            :on-error on-error}))

(defn update-job
  "更新定时任务。"
  [id params on-success on-error]
  (request {:method :put
            :uri (str "/system/job/" id)
            :params params
            :on-success on-success
            :on-error on-error}))

(defn delete-job
  "删除定时任务。"
  [id on-success on-error]
  (request {:method :delete
            :uri (str "/system/job/" id)
            :on-success on-success
            :on-error on-error}))

(defn list-job-logs
  "查询任务日志列表。"
  [params on-success on-error]
  (request {:method :get
            :uri "/system/job/log"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn get-profile
  "获取当前用户个人信息。"
  [on-success on-error]
  (request {:method :get
            :uri "/system/profile"
            :on-success on-success
            :on-error on-error}))

(defn update-profile
  "更新当前用户个人信息。"
  [params on-success on-error]
  (request {:method :put
            :uri "/system/profile"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn change-password
  "修改当前用户密码。"
  [params on-success on-error]
  (request {:method :put
            :uri "/system/profile/password"
            :params params
            :on-success on-success
            :on-error on-error}))

(defn upload-avatar
  "上传头像。"
  [form-data on-success on-error]
  (ajax/ajax-request
    {:method :post
     :uri (str api-base "/system/profile/avatar")
     :body form-data
     :headers (when-let [token @(rf/subscribe [:auth/token])]
                {"Authorization" (str "Bearer " token)})
     :response-format (ajax/json-response-format {:keywords? true})
     :handler (fn [[ok result]]
                (if ok
                  (on-success result)
                  (on-error result)))}))
