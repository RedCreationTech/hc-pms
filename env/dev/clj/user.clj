(ns user
  "Userspace functions for REPL-driven development.
   Supports hot-reloading via clj-nrepl-eval.

   Usage:
     clj-nrepl-eval -p 7000 '(user/reload-domain)'      ;; Reload domain services
     clj-nrepl-eval -p 7000 '(user/reload-routes)'       ;; Reload route definitions
     clj-nrepl-eval -p 7000 '(user/reload-all)'          ;; Reload everything
     clj-nrepl-eval -p 7000 '(user/reset-system)'        ;; Full Integrant reset
     clj-nrepl-eval -p 7000 '(user/rr)'                  ;; Short alias for reset-system"
  (:require
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.repl :as repl]
   [criterium.core :as c]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [integrant.repl :refer [clear go halt prep init reset reset-all]]
   [integrant.repl.state :as state]
   [kit.api :as kit]
   [lambdaisland.classpath :as licp]
   [com.ruoyi.core :refer [start-app]]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))
(add-tap (bound-fn* clojure.pprint/pprint))

;; ── Integrant lifecycle ──────────────────────────────────────────

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (com.ruoyi.config/system-config {:profile :dev})
                                  (ig/expand)))))

(defn test-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (com.ruoyi.config/system-config {:profile :test})
                                  (ig/expand)))))

(dev-prep!)
(repl/set-refresh-dirs "src/clj")

(def refresh repl/refresh)

;; ── Migration helpers ─────────────────────────────────────────────

(defn reset-db []
  (migratus.core/reset (:db.sql/migrations state/system)))

(defn rollback []
  (migratus.core/rollback (:db.sql/migrations state/system)))

(defn migrate []
  (migratus.core/migrate (:db.sql/migrations state/system)))

(def query-fn (:db.sql/query-fn state/system))

;; ── Database hot-swap ───────────────────────────────────────────────

(defn swap-db!
  "热切换数据库。无需重启 JVM。用法: (swap-db! jdbc-url :migration-dir dir :pool-size n)"
  [jdbc-url & opts]
  (require 'com.ruoyi.infra.db :reload)
  (let [swap-fn (resolve 'com.ruoyi.infra.db/swap-db!)]
    (swap-fn state/system jdbc-url (apply hash-map opts))))

;; ── Classpath ─────────────────────────────────────────────────────

(defn update-deps
  "Refresh classpath to pick up deps.edn changes."
  []
  (licp/update-classpath! {:aliases [:dev :test]}))

;; ══════════════════════════════════════════════════════════════════
;; HOT-RELOAD HELPERS
;; Call these from clj-nrepl-eval after editing source files.
;; ══════════════════════════════════════════════════════════════════

(defn reload-domain
  "Reload all domain service namespaces (user, role, menu, dept, etc.)"
  []
  (log/info "Reloading domain services...")
  (require 'com.ruoyi.domain.system :reload)
  (require 'com.ruoyi.domain.system.user :reload)
  (require 'com.ruoyi.domain.system.role :reload)
  (require 'com.ruoyi.domain.system.menu :reload)
  (require 'com.ruoyi.domain.system.dept :reload)
  (require 'com.ruoyi.domain.system.post :reload)
  (require 'com.ruoyi.domain.system.dict :reload)
  (require 'com.ruoyi.domain.system.config :reload)
  (require 'com.ruoyi.domain.system.log :reload)
  (require 'com.ruoyi.domain.gen :reload)
  (log/info "Domain services reloaded."))

(defn reload-middleware
  "Reload middleware namespaces."
  []
  (log/info "Reloading middleware...")
  (require 'com.ruoyi.web.middleware.core :reload)
  (require 'com.ruoyi.web.middleware.auth :reload)
  (require 'com.ruoyi.web.middleware.exception :reload)
  (require 'com.ruoyi.web.middleware.operlog :reload)
  (log/info "Middleware reloaded."))

(defn reload-routes
  "Reload route definitions."
  []
  (log/info "Reloading routes...")
  (require 'com.ruoyi.web.routes.auth :reload)
  (require 'com.ruoyi.web.routes.system :reload)
  (require 'com.ruoyi.web.routes.gen :reload)
  (require 'com.ruoyi.web.routes.api :reload)
  (require 'com.ruoyi.web.handler :reload)
  (log/info "Routes reloaded. Run (user/reset-system) to apply."))

(defn reload-controllers
  "Reload web controller namespaces."
  []
  (log/info "Reloading controllers...")
  (require 'com.ruoyi.web.controllers.auth :reload)
  (require 'com.ruoyi.web.controllers.job :reload)
  (require 'com.ruoyi.web.controllers.monitor :reload)
  (require 'com.ruoyi.web.controllers.gen :reload)
  (require 'com.ruoyi.web.controllers.system.user :reload)
  (require 'com.ruoyi.web.controllers.system.role :reload)
  (require 'com.ruoyi.web.controllers.system.menu :reload)
  (require 'com.ruoyi.web.controllers.system.dept :reload)
  (require 'com.ruoyi.web.controllers.system.post :reload)
  (require 'com.ruoyi.web.controllers.system.dict :reload)
  (require 'com.ruoyi.web.controllers.system.config :reload)
  (require 'com.ruoyi.web.controllers.system.log :reload)
  (require 'com.ruoyi.web.controllers.system.online :reload)
  (require 'com.ruoyi.web.controllers.system.profile :reload)
  (log/info "Controllers reloaded."))

(defn reload-infra
  "Reload infrastructure namespaces (security, online, data-perm)."
  []
  (log/info "Reloading infra...")
  (require 'com.ruoyi.infra.security :reload)
  (require 'com.ruoyi.infra.online :reload)
  (require 'com.ruoyi.infra.data-perm :reload)
  (log/info "Infra reloaded."))

(defn reload-all
  "Reload all application namespaces (domain + middleware + routes + controllers + infra)."
  []
  (reload-domain)
  (reload-infra)
  (reload-middleware)
  (reload-controllers)
  (reload-routes)
  (log/info "All namespaces reloaded."))

(defn reload-system
  "Integrant reset: halt + re-prep + go. Full system restart."
  []
  (log/info "Resetting Integrant system...")
  (integrant.repl/reset))

(def rr
  "Short alias for reload-system."
  reload-system)

;; Short aliases for quick iteration
(def rd reload-domain)
(def rroutes reload-routes)
(def ra reload-all)
(def rm reload-middleware)

(comment
  ;; Hot-reload workflow:
  ;; 1. Edit a .clj file
  ;; 2. Run one of:
  (rd)        ;; Reload domain only
  (rroutes)   ;; Reload routes only
  (rm)        ;; Reload middleware only
  (ra)        ;; Reload all namespaces
  (rr)        ;; Full Integrant reset (halt + go)
  )
