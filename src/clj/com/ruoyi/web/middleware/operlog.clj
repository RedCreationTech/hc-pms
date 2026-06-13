(ns com.ruoyi.web.middleware.operlog
  "操作日志中间件：自动记录所有 API 请求到 sys_oper_log 表。"
  (:require
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ring.util.response :as response]))

(def ^:private skip-paths
  "不记录日志的路径"
  #{"/api/auth/login" "/api/health" "/api/user/profile"})

(def ^:private get-methods
  "GET 请求不记录日志（仅 health 和 login 特殊处理）"
  #{:get :head :options})

(defn- format-params
  "格式化请求参数，过长时截断。"
  [params]
  (let [s (if (instance? String params)
            params
            (try (json/generate-string params)
                 (catch Exception _ (str params))))]
    (if (> (count s) 200)
      (str (subs s 0 200) "...")
      s)))

(defn wrap-oper-log
  "操作日志中间件包装器。
  调用 query-fn 写入 sys_oper_log 表。"
  [handler]
  (fn [request]
    (let [uri (:uri request)
          start (System/currentTimeMillis)
          response (handler request)
          cost-ms (- (System/currentTimeMillis) start)]
      (when (and (str/starts-with? uri "/api/")
                 (not (skip-paths uri))
                 (not (contains? get-methods (:request-method request))))
        (let [identity (:identity request)
              log-entry {:title       (str (name (:request-method request)) " " uri)
                         :business_type 0
                         :method      ""
                         :request_method (name (:request-method request))
                         :operator_type 1
                         :oper_name   (or (:user-name identity) "anonymous")
                         :dept_name   ""
                         :oper_url    uri
                         :oper_ip     (get-in request [:headers "x-forwarded-for"]
                                              (:remote-addr request "127.0.0.1"))
                         :oper_location ""
                         :oper_param  (format-params (:params request))
                         :json_result (str (:status response))
                         :status      (if (>= (:status response) 400) 1 0)
                         :error_msg   ""
                         :cost_time   cost-ms}]
          (try
            (when-let [query-fn (get-in request [:components :query-fn])]
              (query-fn :create-oper-log! log-entry))
            (catch Exception e
              (log/warn e "Failed to write operation log")))))
      response)))
