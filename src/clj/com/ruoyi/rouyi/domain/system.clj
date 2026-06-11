(ns com.ruoyi.rouyi.domain.system
  "系统管理 Integrant 组件注册。"
  (:require
    [integrant.core :as ig]))

(defmethod ig/init-key :app.system/user-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/role-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/menu-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/dept-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/post-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/dict-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/config-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defmethod ig/init-key :app.system/log-service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})
