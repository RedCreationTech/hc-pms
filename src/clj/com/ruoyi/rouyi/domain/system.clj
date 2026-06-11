(ns com.ruoyi.rouyi.domain.system
  "系统管理 Integrant 组件注册。"
  (:require
    [integrant.core :as ig]
    [com.ruoyi.rouyi.domain.gen :as gen]
    [com.ruoyi.rouyi.infra.online :as online]))

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

(defmethod ig/init-key :app.system/online-service
  [_ _opts]
  "在线用户服务组件，包装 infra/online 原子缓存 API。"
  {:list-online   (fn [params]
                    (apply online/list-online
                      (mapcat (fn [[k v]] [(keyword (name k)) v]) params)))
   :force-logout  (fn [token-id]
                    (online/force-logout! token-id))})

(defmethod ig/init-key :app.system/gen-service
  [_ {:keys [query-fn]}]
  "代码生成器服务组件，封装 gen 领域服务。"
  {:query-fn query-fn
   :list-tables (partial gen/list-tables {:query-fn query-fn})
   :table-columns (partial gen/table-columns {:query-fn query-fn})
   :generate-code (partial gen/generate-code {:query-fn query-fn})})
