(ns com.ruoyi.domain.system.dict
  "字典领域服务。")

(defn list-dict-types
  "查询字典类型列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-dict-types (merge {:dict_name nil :dict_type nil :status nil} params)))

(defn find-dict-type-by-id
  "根据ID查询字典类型。"
  [{:keys [query-fn]} dict-id]
  (query-fn :find-dict-type-by-id {:dict_id dict-id}))

(defn create-dict-type!
  "创建字典类型。"
  [{:keys [query-fn]} params]
  (query-fn :create-dict-type! (merge {:dict_name nil :dict_type nil :status nil :remark nil :create_by nil} params))
  (get (query-fn :last-insert-rowid {}) (keyword "last_insert_rowid()")))

(defn update-dict-type!
  "更新字典类型。"
  [{:keys [query-fn]} params]
  (query-fn :update-dict-type! (merge {:dict_id nil :dict_name nil :dict_type nil :status nil :remark nil :update_by nil} params)))

(defn delete-dict-type!
  "删除字典类型。"
  [{:keys [query-fn]} dict-id]
  (query-fn :delete-dict-type! {:dict_id dict-id}))

(defn list-dict-data
  "查询字典数据列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-dict-data (merge {:dict_type nil :dict_label nil :status nil} params)))

(defn find-dict-data-by-id
  "根据ID查询字典数据。"
  [{:keys [query-fn]} dict-code]
  (query-fn :find-dict-data-by-id {:dict_code dict-code}))

(defn create-dict-data!
  "创建字典数据。"
  [{:keys [query-fn]} params]
  (query-fn :create-dict-data! (merge {:dict_sort nil :dict_label nil :dict_value nil :dict_type nil
                                       :css_class nil :list_class nil :is_default nil :status nil
                                       :remark nil :create_by nil} params))
  (get (query-fn :last-insert-rowid {}) (keyword "last_insert_rowid()")))

(defn update-dict-data!
  "更新字典数据。"
  [{:keys [query-fn]} params]
  (query-fn :update-dict-data! (merge {:dict_code nil :dict_sort nil :dict_label nil :dict_value nil :dict_type nil
                                       :css_class nil :list_class nil :is_default nil :status nil
                                       :remark nil :update_by nil} params)))

(defn delete-dict-data!
  "删除字典数据。"
  [{:keys [query-fn]} dict-code]
  (query-fn :delete-dict-data! {:dict_code dict-code}))
