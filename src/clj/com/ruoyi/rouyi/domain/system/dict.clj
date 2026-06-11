(ns com.ruoyi.rouyi.domain.system.dict
  "字典领域服务。")

(defn list-dict-types
  "查询字典类型列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-dict-types params))

(defn find-dict-type-by-id
  "根据ID查询字典类型。"
  [{:keys [query-fn]} dict-id]
  (query-fn :find-dict-type-by-id {:dict_id dict-id}))

(defn create-dict-type!
  "创建字典类型。"
  [{:keys [query-fn]} params]
  (-> (query-fn :create-dict-type! params)
      first
      :dict_id))

(defn update-dict-type!
  "更新字典类型。"
  [{:keys [query-fn]} params]
  (query-fn :update-dict-type! params))

(defn delete-dict-type!
  "删除字典类型。"
  [{:keys [query-fn]} dict-id]
  (query-fn :delete-dict-type! {:dict_id dict-id}))

(defn list-dict-data
  "查询字典数据列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-dict-data params))

(defn find-dict-data-by-id
  "根据ID查询字典数据。"
  [{:keys [query-fn]} dict-code]
  (query-fn :find-dict-data-by-id {:dict_code dict-code}))

(defn create-dict-data!
  "创建字典数据。"
  [{:keys [query-fn]} params]
  (-> (query-fn :create-dict-data! params)
      first
      :dict_code))

(defn update-dict-data!
  "更新字典数据。"
  [{:keys [query-fn]} params]
  (query-fn :update-dict-data! params))

(defn delete-dict-data!
  "删除字典数据。"
  [{:keys [query-fn]} dict-code]
  (query-fn :delete-dict-data! {:dict_code dict-code}))
