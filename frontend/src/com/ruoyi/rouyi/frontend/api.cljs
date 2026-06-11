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
