(ns com.ruoyi.rouyi.domain.system.dept
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

(defn create-dept!
  "创建部门。"
  [{:keys [query-fn]} params]
  (-> (query-fn :create-dept! params)
      first
      :dept_id))

(defn update-dept!
  "更新部门。"
  [{:keys [query-fn]} params]
  (query-fn :update-dept! params))

(defn delete-dept!
  "逻辑删除部门。"
  [{:keys [query-fn]} dept-id]
  (query-fn :delete-dept! {:dept_id dept-id}))
