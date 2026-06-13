(ns com.ruoyi.domain.business
  "业务系统 Integrant 组件注册。"
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :app.business/service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})
