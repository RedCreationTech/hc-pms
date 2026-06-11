(ns com.ruoyi.rouyi.core
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [com.ruoyi.rouyi.config :as config]
   [com.ruoyi.rouyi.env :refer [defaults]]

    ;; Edges
   [kit.edge.db.sql.conman]
   [kit.edge.db.sql.migratus]
   [kit.edge.db.postgres]
   [kit.edge.db.mysql]
   [kit.edge.scheduling.quartz]
   [kit.edge.templating.selmer]
   [kit.edge.utils.nrepl]
   [kit.edge.server.undertow]
   [com.ruoyi.rouyi.web.handler]

    ;; Domain services
   [com.ruoyi.rouyi.domain.system]
   [com.ruoyi.rouyi.domain.system.user]
   [com.ruoyi.rouyi.domain.system.role]
   [com.ruoyi.rouyi.domain.system.menu]
   [com.ruoyi.rouyi.domain.system.dept]
   [com.ruoyi.rouyi.domain.system.post]
   [com.ruoyi.rouyi.domain.system.dict]
   [com.ruoyi.rouyi.domain.system.config]
   [com.ruoyi.rouyi.domain.system.log]
   [com.ruoyi.rouyi.domain.gen]

    ;; Middleware
   [com.ruoyi.rouyi.web.middleware.auth]

    ;; Routes
   [com.ruoyi.rouyi.web.routes.api]
   [com.ruoyi.rouyi.web.routes.auth]
   [com.ruoyi.rouyi.web.routes.system]
   [com.ruoyi.rouyi.web.routes.gen])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (fn [thread ex]
   (log/error {:what :uncaught-exception
               :exception ex
               :where (str "Uncaught exception on" (.getName thread))})))

(defonce system (atom nil))

(defn stop-app []
  ((or (:stop defaults) (fn [])))
  (some-> (deref system) (ig/halt!)))

(defn start-app [& [params]]
  ((or (:start params) (:start defaults) (fn [])))
  (->> (config/system-config (or (:opts params) (:opts defaults) {}))
       (ig/expand)
       (ig/init)
       (reset! system)))

(defn -main [& _]
  (start-app)
  (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop-app) (shutdown-agents))))
  ;; Keep main thread alive so JVM doesn't exit
  (while true (Thread/sleep 60000)))
