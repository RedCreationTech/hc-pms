(ns com.ruoyi.web.routes.api
  (:require
   [com.ruoyi.web.controllers.health :as health]
   [com.ruoyi.web.routes.auth :as auth]
   [com.ruoyi.web.routes.system :as system]
   [com.ruoyi.web.routes.gen :as gen]
   [com.ruoyi.web.routes.captcha :as captcha]
   [com.ruoyi.web.routes.common :as common]
   [com.ruoyi.web.routes.business :as business]
   [com.ruoyi.web.routes.workflow :as workflow]
   [com.ruoyi.web.middleware.exception :as exception]
   [com.ruoyi.web.middleware.formats :as formats]
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
   (common/common-routes opts)
   (business/business-routes opts)
   (gen/gen-routes opts)
   (captcha/captcha-routes opts)
   (workflow/routes)])

(derive :reitit.routes/api :reitit/routes)

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  (fn [] [base-path route-data (api-routes opts)]))
