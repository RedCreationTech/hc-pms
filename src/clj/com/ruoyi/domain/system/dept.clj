(ns com.ruoyi.domain.system.dept
  "部门领域服务。"
  (:require
   [clojure.string :as str]))

(defn list-depts
  "查询部门列表。"
  [{:keys [query-fn]} params]
  (query-fn :list-depts (merge {:status nil :dept_name nil} params)))

(defn find-dept-by-id
  "根据ID查询部门。"
  [{:keys [query-fn]} dept-id]
  (query-fn :find-dept-by-id {:dept_id dept-id}))

(defn- compute-ancestors
  "根据父部门计算 ancestors 路径。"
  [{:keys [query-fn]} parent-id]
  (if (and parent-id (pos? parent-id))
    (if-let [parent (query-fn :find-dept-by-id {:dept_id parent-id})]
      (let [parent-anc (or (:ancestors parent) "0")]
        (if (seq parent-anc)
          (str parent-anc "," parent-id)
          (str "0," parent-id)))
      (str "0," parent-id))
    "0"))

(defn- update-descendants-ancestors!
  "递归更新子部门的 ancestors。"
  [{:keys [query-fn]} dept-id ancestors]
  (let [children (query-fn :list-depts-by-parent {:parent_id dept-id})]
    (doseq [child children]
      (let [child-id (:dept_id child)
            child-ancestors (str ancestors "," child-id)]
        (query-fn :update-dept-ancestors! {:dept_id child-id :ancestors child-ancestors})
        (update-descendants-ancestors! {:query-fn query-fn} child-id child-ancestors)))))

(defn create-dept!
  "创建部门。"
  [{:keys [query-fn] :as ctx} params]
  (let [parent-id (:parent_id params 0)
        params (-> {:parent_id nil :ancestors nil :dept_name nil :order_num nil
                    :leader nil :phone nil :email nil :status nil :create_by nil}
                   (merge params)
                   (assoc :parent_id parent-id
                          :ancestors (compute-ancestors ctx parent-id)))]
    (query-fn :create-dept! params)
    (get (query-fn :last-insert-rowid {}) (keyword "last_insert_rowid()"))))

(defn update-dept!
  "更新部门。"
  [{:keys [query-fn] :as ctx} params]
  (let [dept-id (:dept_id params)
        parent-id (:parent_id params)
        params (merge {:parent_id nil :ancestors nil :dept_name nil :order_num nil
                       :leader nil :phone nil :email nil :status nil :update_by nil}
                      params)]
    (if (some? parent-id)
      (let [ancestors (compute-ancestors ctx parent-id)]
        (query-fn :update-dept! (assoc params :ancestors ancestors))
        (update-descendants-ancestors! ctx dept-id ancestors))
      (query-fn :update-dept! params))))

(defn delete-dept!
  "逻辑删除部门。"
  [{:keys [query-fn]} dept-id]
  (query-fn :delete-dept! {:dept_id dept-id}))
