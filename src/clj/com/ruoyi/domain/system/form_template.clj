(ns com.ruoyi.domain.system.form-template
  "表单模板领域服务。"
  (:require
   [com.ruoyi.infra.db :as db]))

(defn list-form-templates
  "查询表单模板列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-form-templates (merge {:form_name nil :form_key nil} params)))

(defn find-form-template-by-id
  "根据ID查询表单模板。"
  [{:keys [query-fn]} id]
  (query-fn :find-form-template-by-id {:id id}))

(defn find-form-template-by-key
  "根据form_key查询表单模板。"
  [{:keys [query-fn]} form-key]
  (query-fn :find-form-template-by-key {:form_key form-key}))

(defn create-form-template!
  "创建表单模板，返回自增ID。"
  [{:keys [query-fn db]} params]
  (when (find-form-template-by-key {:query-fn query-fn} (:form_key params))
    (throw (ex-info "表单key已存在" {:form_key (:form_key params)})))
  (db/insert-and-get-id! query-fn db :create-form-template!
                         (merge {:form_name nil :form_key nil :schema_json nil :remark nil :create_by nil} params)))

(defn update-form-template!
  "更新表单模板。"
  [{:keys [query-fn]} params]
  (let [id (:id params)
        new-key (:form_key params)
        existing (when new-key (find-form-template-by-key {:query-fn query-fn} new-key))]
    (when (and existing (not= (:id existing) id))
      (throw (ex-info "表单key已存在" {:form_key new-key})))
    (query-fn :update-form-template!
              (merge {:id id :form_name nil :form_key nil :schema_json nil :remark nil :update_by nil} params))))

(defn delete-form-template!
  "删除表单模板。"
  [{:keys [query-fn]} id]
  (query-fn :delete-form-template! {:id id}))
