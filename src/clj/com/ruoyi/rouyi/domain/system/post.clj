(ns com.ruoyi.rouyi.domain.system.post
  "岗位领域服务。")

(defn list-posts
  "查询岗位列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-posts (merge {:post_code nil :post_name nil :status nil} params)))

(defn find-post-by-id
  "根据ID查询岗位。"
  [{:keys [query-fn]} post-id]
  (query-fn :find-post-by-id {:post_id post-id}))

(defn create-post!
  "创建岗位。"
  [{:keys [query-fn]} params]
  (-> (query-fn :create-post! params)
      first
      :post_id))

(defn update-post!
  "更新岗位。"
  [{:keys [query-fn]} params]
  (query-fn :update-post! params))

(defn delete-post!
  "删除岗位。"
  [{:keys [query-fn]} post-id]
  (query-fn :delete-post! {:post_id post-id}))
