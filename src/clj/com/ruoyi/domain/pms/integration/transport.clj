(ns com.ruoyi.domain.pms.integration.transport
  "由部署环境配置的HTTPS消息交付,必须取得匹配业务回执才算成功."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn connectors
  "从服务注入或部署环境读取连接器,不在数据库或UI保存认证令牌."
  [svc]
  (or (:integration-connectors svc)
      (when-let [config (not-empty (System/getenv "PMS_CONNECTORS_JSON"))]
        (try (json/parse-string config true)
             (catch Exception _ (r/fail! 503 "接口部署配置无效")))) {}))

(defn connection!
  "要求服务端配置可信目标,只允许HTTPS或测试显式启用的本地HTTP."
  [svc target]
  (let [config (get (connectors svc) (keyword target))
        uri (try (URI. (str (:url config))) (catch Exception _ nil))
        local? (and (:allow_loopback config) uri (= "http" (.getScheme uri))
                    (contains? #{"127.0.0.1" "localhost" "::1"} (.getHost uri)))]
    (when-not (and config uri (.getHost uri) (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                   (or (= "https" (.getScheme uri)) local?))
      (r/fail! 503 "目标连接器尚未配置有效HTTPS地址"))
    (when (and (:token_env config) (not (seq (System/getenv (:token_env config)))))
      (r/fail! 503 "目标连接器认证环境变量未设置"))
    (assoc config :uri uri)))

(defn- receipt
  "2xx响应还须返回accepted,对应message_id和非空receipt_id."
  [row status text]
  (let [body (try (json/parse-string text true) (catch Exception _ nil))
        rid (:receipt_id body)
        accepted? (and (<= 200 status 299) (true? (:accepted body))
                       (= (:message_id row) (:message_id body)) (string? rid) (<= 1 (count rid) 150))]
    {:accepted? (boolean accepted?) :http_status status
     :receipt_id (when accepted? rid) :receipt_hash (money/digest text)
     :error_code (when-not accepted? (if (<= 200 status 299) "receipt_mismatch" "http_rejected"))}))

(def ^:dynamic *response-timeout-ms*
  "覆盖连接,响应头及完整正文的总等待上限,测试可缩短."
  10000)

(defn- bounded-subscriber
  "以有限缓冲读取回执,收到超限正文立即取消订阅."
  [subscription]
  (let [result (java.util.concurrent.CompletableFuture.)
        buffer (java.io.ByteArrayOutputStream.)]
    (reify java.net.http.HttpResponse$BodySubscriber
      (getBody [_] result)
      (onSubscribe [_ sub] (reset! subscription sub) (.request sub 1))
      (onNext [_ chunks]
        (try
          (doseq [chunk chunks]
            (let [size (.remaining ^java.nio.ByteBuffer chunk)]
              (when (> (+ (.size buffer) size) 65536)
                (throw (ex-info "receipt_too_large" {:transport-code "receipt_too_large"})))
              (let [bytes (byte-array size)] (.get ^java.nio.ByteBuffer chunk bytes) (.write buffer bytes))))
          (.request ^java.util.concurrent.Flow$Subscription @subscription 1)
          (catch Exception e
            (.cancel ^java.util.concurrent.Flow$Subscription @subscription)
            (.completeExceptionally result e))))
      (onError [_ error] (.completeExceptionally result error))
      (onComplete [_] (.complete result (.toByteArray buffer))))))

(defn- response!
  "完整响应受同一个截止时间约束,超时取消网络和正文订阅."
  [client request]
  (let [subscription (atom nil)
        handler (reify java.net.http.HttpResponse$BodyHandler
                  (apply [_ _] (bounded-subscriber subscription)))
        pending (.sendAsync ^HttpClient client request handler)]
    (try
      (.get pending (long *response-timeout-ms*) java.util.concurrent.TimeUnit/MILLISECONDS)
      (catch Exception e
        (when-let [sub @subscription] (.cancel ^java.util.concurrent.Flow$Subscription sub))
        (.cancel pending true)
        (throw e)))))

(defn send!
  "禁止重定向,限制完整响应时间和大小,只有匹配的业务回执才表示交付成功."
  [config row]
  (try
    (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) .build)
          builder (-> (HttpRequest/newBuilder ^URI (:uri config))
                      (.timeout (Duration/ofSeconds 10))
                      (.header "Content-Type" "application/json")
                      (.header "Idempotency-Key" (:message_id row))
                      (.POST (HttpRequest$BodyPublishers/ofString (:payload_json row))))
          _ (when-let [env (:token_env config)] (.header builder "Authorization" (str "Bearer " (System/getenv env))))
          response (response! client (.build builder))]
      (receipt row (.statusCode response) (String. ^bytes (.body response) "UTF-8")))
    (catch Exception _ {:accepted? false :error_code "transport_unavailable"})))
