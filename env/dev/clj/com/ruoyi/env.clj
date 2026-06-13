(ns com.ruoyi.env
  (:require
   [clojure.tools.logging :as log]
   [com.ruoyi.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[rouyi starting using the development or test profile]=-"))
   :start      (fn []
                 (log/info "\n-=[rouyi started successfully using the development or test profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[rouyi has shut down successfully]=-"))
   :middleware wrap-dev
   :opts       {:profile       :dev}})
