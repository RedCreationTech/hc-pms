(ns com.ruoyi.rouyi.test-utils
  (:require
   [com.ruoyi.rouyi.core :as core]
   [peridot.core :as p]
   [byte-streams :as bs]
   [clojure.data.json :as json]
   [integrant.repl.state :as state]))

(defn system-state
  []
  (or @core/system state/system))

(defn system-fixture
  []
  (fn [f]
    (when (nil? (system-state))
      (core/start-app {:opts {:profile :test}}))
    (f)
    (core/stop-app)))

(defn get-response [ctx]
  (-> ctx
      :response
      (update :body (fnil bs/to-string ""))))

(defn GET [app path params headers]
  (-> (p/session app)
      (p/request path
                 :request-method :get
                 :content-type "application/edn"
                 :headers headers
                 :params params)
      (get-response)))

(defn PUT [app path body headers]
  (-> (p/session app)
      (p/request path
                 :request-method :put
                 :content-type "application/json"
                 :headers headers
                 :body (json/write-str body))
      (get-response)))
