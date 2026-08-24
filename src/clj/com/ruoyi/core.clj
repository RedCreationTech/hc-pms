(ns com.ruoyi.core
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [com.ruoyi.config :as config]
   [com.ruoyi.env :refer [defaults]]
   [com.ruoyi.integrant.state :as integrant-state]

    ;; Edges
   [kit.edge.db.sql.conman]
   [kit.edge.db.sql.migratus]
   [kit.edge.db.postgres]
   [kit.edge.db.mysql]
   [kit.edge.scheduling.quartz]
   [kit.edge.templating.selmer]
   [kit.edge.utils.nrepl]
   [kit.edge.server.undertow]
   [com.ruoyi.web.handler]
   [com.ruoyi.integrant.trace]

    ;; BPM engine
   [com.ruoyi.bpm.engine]
   [com.ruoyi.bpm.core]

    ;; Domain services
   [com.ruoyi.domain.system]
   [com.ruoyi.domain.business.bpm]
   [com.ruoyi.domain.business.hrm]
   [com.ruoyi.domain.business.oa]
   [com.ruoyi.domain.system.user]
   [com.ruoyi.domain.system.role]
   [com.ruoyi.domain.system.menu]
   [com.ruoyi.domain.system.dept]
   [com.ruoyi.domain.system.post]
   [com.ruoyi.domain.system.dict]
   [com.ruoyi.domain.system.config]
   [com.ruoyi.domain.system.log]

    ;; Middleware
   [com.ruoyi.web.middleware.auth]

    ;; Routes
   [com.ruoyi.web.routes.api]
   [com.ruoyi.web.routes.auth]
   [com.ruoyi.web.routes.business]
   [com.ruoyi.web.routes.system])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (fn [thread ex]
   (log/error {:what :uncaught-exception
               :exception ex
               :where (str "Uncaught exception on" (.getName thread))})))

(def system integrant-state/system)

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
