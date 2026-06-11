(ns com.ruoyi.rouyi.web.middleware.core
  (:require
    [com.ruoyi.rouyi.env :as env]
    [ring.middleware.defaults :as defaults]
    [ring.middleware.session.cookie :as cookie]))

(defn- wrap-cors
  "允许跨域请求，支持前端开发服务器访问。"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
          (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, PUT, DELETE, OPTIONS")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type, Authorization")
          (assoc-in [:headers "Access-Control-Allow-Credentials"] "true")))))

(defn- handle-preflight
  "处理 CORS 预检请求。"
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"
                 "Access-Control-Allow-Credentials" "true"}
       :body ""}
      (handler request))))

(defn wrap-base
  [{:keys [metrics site-defaults-config cookie-secret] :as opts}]
  (let [cookie-store (cookie/cookie-store {:key (.getBytes ^String cookie-secret)})]
    (fn [handler]
      (-> ((:middleware env/defaults) handler opts)
          (defaults/wrap-defaults
            (assoc-in site-defaults-config [:session :store] cookie-store))
          wrap-cors
          handle-preflight))))
