(ns com.ruoyi.domain.system.config
  "参数配置领域服务."
  (:require
    [com.ruoyi.infra.db :as db]))


(defn list-configs
  "查询参数配置列表."
  [{:keys [query-fn]} params]
  (query-fn :list-configs (merge {:config_name nil :config_key nil :config_type nil} params)))


(defn find-config-by-id
  "根据ID查询配置."
  [{:keys [query-fn]} config-id]
  (query-fn :find-config-by-id {:config_id config-id}))


(defn find-config-by-key
  "根据键名查询配置值."
  [{:keys [query-fn]} config-key]
  (query-fn :find-config-by-key {:config_key config-key}))


(defn create-config!
  "创建参数配置."
  [{:keys [query-fn db]} params]
  (db/insert-and-get-id! query-fn db :create-config!
                         (merge {:config_name nil :config_key nil :config_value nil :config_type nil :remark nil :create_by nil} params)))


(defn update-config!
  "更新参数配置."
  [{:keys [query-fn]} params]
  (query-fn :update-config! (merge {:config_id nil :config_name nil :config_key nil :config_value nil :config_type nil :remark nil :update_by nil} params)))


(defn delete-config!
  "删除参数配置."
  [{:keys [query-fn]} config-id]
  (query-fn :delete-config! {:config_id config-id}))
