(ns com.ruoyi.rouyi.web.routes.api
  (:require
    [com.ruoyi.rouyi.web.controllers.health :as health]
    [com.ruoyi.rouyi.web.routes.auth :as auth]
    [com.ruoyi.rouyi.web.routes.system :as system]
    [com.ruoyi.rouyi.web.routes.gen :as gen]
    [com.ruoyi.rouyi.web.middleware.exception :as exception]
    [com.ruoyi.rouyi.web.middleware.formats :as formats]
    [integrant.core :as ig]
    [reitit.coercion.malli :as malli]
    [reitit.ring.coercion :as coercion]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.parameters :as parameters]
    [reitit.swagger :as swagger]))

(def route-data
  {:coercion   malli/coercion
   :muuntaja   formats/instance
   :swagger    {:id ::api}
   :middleware [parameters/parameters-middleware
                muuntaja/format-negotiate-middleware
                muuntaja/format-response-middleware
                coercion/coerce-exceptions-middleware
                muuntaja/format-request-middleware
                coercion/coerce-response-middleware
                coercion/coerce-request-middleware
                exception/wrap-exception]})

(defn api-routes [opts]
  [["/swagger.json"
    {:get {:no-doc  true
           :swagger {:info {:title "RouYi API"}}
           :handler (swagger/create-swagger-handler)}}]
   ["/health"
    {:get #'health/healthcheck!}]
   (auth/auth-routes opts)
   (system/system-routes opts)
   (gen/gen-routes opts)])

(derive :reitit.routes/api :reitit/routes)

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  (fn [] [base-path route-data (api-routes opts)]))
