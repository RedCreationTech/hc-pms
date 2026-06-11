(ns com.ruoyi.rouyi.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[rouyi starting]=-"))
   :start      (fn []
                 (log/info "\n-=[rouyi started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[rouyi has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})
