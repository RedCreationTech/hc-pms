(ns com.ruoyi.rouyi.web.middleware.operlog
  "操作日志中间件：自动记录所有 API 请求的日志。"
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as str]
    [cheshire.core :as json]
    [ring.util.response :as response]))

(def ^:private skip-paths
  "不记录日志的路径"
  #{"/api/auth/login" "/api/health" "/api/user/profile"})

(def ^:private get-methods
  "GET 请求作为查询日志（不记录参数细节）"
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

(defn- build-oper-log
  "构建操作日志记录。"
  [request {:keys [status body] :or {status 200 body ""}} cost-ms]
  (let [uri (:uri request)
        method (:request-method request)
        identity (:identity request)]
    {:title        (str (name method) " " uri)
     :oper-url     uri
     :oper-method  (str (name method))
     :request-param (format-params (:params request))
     :json-result  (format-params body)
     :status       (if (>= status 400) 1 0)
     :oper-name    (or (:user-name identity) "anonymous")
     :dept-name    ""
     :oper-ip      (get-in request [:headers "x-forwarded-for"]
                   (:remote-addr request "127.0.0.1"))
     :cost-ms      cost-ms
     :create-time  (java.time.LocalDateTime/now)}))

(defn wrap-oper-log
  "操作日志中间件包装器。
  记录每次 API 调用的耗时和结果。"
  [handler]
  (fn [request]
    (let [uri (:uri request)
          _   (when (and (not (str/starts-with? uri "/api/health"))
                         (not (str/includes? uri "favicon")))
                (log/debug "▶" (:request-method request) uri))
          start (System/currentTimeMillis)
          response (handler request)
          cost-ms (- (System/currentTimeMillis) start)]
      ;; 记录非 GET 和非 skip 路径
      (when (and (str/starts-with? uri "/api/")
                 (not (skip-paths uri))
                 (not (contains? get-methods (:request-method request))))
        (log/info (str "[" (:request-method request) "] " uri " → "
                       (:status response) " " cost-ms "ms")))
      response)))
