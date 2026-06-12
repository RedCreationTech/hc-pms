(ns com.ruoyi.rouyi.web.middleware.exception
  (:require
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [reitit.ring.middleware.exception :as exception]))

(defn handler [message status exception request]
  (when (>= status 500)
    (log/error exception "Exception:" (.getMessage exception)))
  {:status  status
   :headers {"content-type" "application/json;charset=utf-8"}
   :body    (json/generate-string
             {:message   message
              :exception (.getClass exception)
              :data      (ex-data exception)
              :uri       (:uri request)})})

(def wrap-exception
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {:system.exception/internal     (partial handler "internal exception" 500)
     :system.exception/business     (partial handler "bad request" 400)
     :system.exception/not-found    (partial handler "not found" 404)
     :system.exception/unauthorized (partial handler "unauthorized" 401)
     :system.exception/forbidden    (partial handler "forbidden" 403)

       ;; override the default handler
     ::exception/default            (partial handler "default" 500)

       ;; print stack-traces for all exceptions
     ::exception/wrap               (fn [handler e request]
                                      (handler e request))})))
