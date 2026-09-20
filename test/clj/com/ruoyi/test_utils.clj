(ns com.ruoyi.test-utils
  (:require
    [byte-streams :as bs]
    [clojure.data.json :as json]
    [com.ruoyi.core :as core]
    [integrant.repl.state :as state]
    [peridot.core :as p]))


(defn system-state
  []
  @core/system)


(defn system-fixture
  []
  (fn [f]
    (when (nil? (system-state))
      (core/start-app {:opts {:profile :test}}))
    (f)
    (core/stop-app)))


(defn get-response
  [ctx]
  (-> ctx
      :response
      (update :body (fnil bs/to-string ""))))


(defn GET
  [app path params headers]
  (-> (p/session app)
      (p/request path
                 :request-method :get
                 :content-type "application/edn"
                 :headers headers
                 :params params)
      (get-response)))


(defn PUT
  [app path body headers]
  (-> (p/session app)
      (p/request path
                 :request-method :put
                 :content-type "application/json"
                 :headers headers
                 :body (json/write-str body))
      (get-response)))
