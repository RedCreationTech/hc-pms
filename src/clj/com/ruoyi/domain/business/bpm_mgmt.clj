(ns com.ruoyi.domain.business.bpm-mgmt
  "BPM 管理套件领域服务:用户分组/流程监听器/流程表达式/流程设置 的通用 CRUD.
   4 个模块 CRUD 结构相同,用数据驱动配置复用."
  (:require
    [com.ruoyi.domain.business.paging :as paging]
    [integrant.core :as ig]))


(defmethod ig/init-key :app.business/bpm-mgmt-service
  [_ {:keys [query-fn db]}]
  {:query-fn query-fn :db db})


;; 各模块的 CRUD 配置(查询名/id 列/可写字段)
(def ^:private crud
  {:user-group {:list :bpmmgmt/group-list :count :bpmmgmt/group-count
                :find :bpmmgmt/find-group-by-id :insert :bpmmgmt/insert-group
                :update :bpmmgmt/update-group :delete :bpmmgmt/delete-group
                :id :group_id :name "name"
                :fields [:name :description :user_ids :status :remark]}
   :listener   {:list :bpmmgmt/listener-list :count :bpmmgmt/listener-count
                :find :bpmmgmt/find-listener-by-id :insert :bpmmgmt/insert-listener
                :update :bpmmgmt/update-listener :delete :bpmmgmt/delete-listener
                :id :listener_id :name "name"
                :fields [:name :type :event :listener :status :remark]}
   :expression {:list :bpmmgmt/expression-list :count :bpmmgmt/expression-count
                :find :bpmmgmt/find-expression-by-id :insert :bpmmgmt/insert-expression
                :update :bpmmgmt/update-expression :delete :bpmmgmt/delete-expression
                :id :expression_id :name "name"
                :fields [:name :format :expression :status :remark]}
   :settings   {:list :bpmmgmt/settings-list :count :bpmmgmt/settings-count
                :find :bpmmgmt/find-settings-by-id :insert :bpmmgmt/insert-settings
                :update :bpmmgmt/update-settings :delete :bpmmgmt/delete-settings
                :id :settings_id :name "name"
                :fields [:name :value :description :status :remark]}})


(defn- cfg
  "取模块的 CRUD 配置. 未知模块直接 404: 路由是 `/bpm/:module` 通配, 任何拼错的
   或方法不匹配的路径都会落到这里, 未校验时会拿 nil 去调 HugSQL, 抛出的错误里
   带着全部可用 query 键名 (信息泄露) 且报成 500."
  [module]
  (or (get crud module)
      (throw (ex-info (str "未知的 BPM 管理模块: " (name module))
                      {:module (name module) :status 404}))))


(defn list-items
  [{:keys [query-fn]} module params]
  (let [{:keys [offset size]} (paging/params params)
        c (cfg module)
        p {:name (get params :name) :type (get params :type)
           :page_size size :offset offset}
        rows (query-fn (:list c) p)
        ;; doseq + 不可变 assoc 的返回值会被丢弃, 改 mapv 才真正写回 :type
        rows (cond->> rows
               (= :listener module) (mapv (fn [r] (assoc r :type (get params :type)))))]
    {:rows rows :total (:total (query-fn (:count c) p))}))


(defn get-item
  [{:keys [query-fn]} module id]
  (let [c (cfg module)]
    (query-fn (:find c) {(keyword (name (:id c))) id})))


(defn create-item
  [{:keys [query-fn]} module params user]
  (let [c (cfg module)
        p (into {} (map (fn [f] [f (or (get params f) "")]) (:fields c)))]
    (query-fn (:insert c) (assoc p :create_by (or user "")))))


(defn update-item
  [{:keys [query-fn]} module id params user]
  (let [c (cfg module)
        p (into {} (map (fn [f] [f (or (get params f) "")]) (:fields c)))]
    (query-fn (:update c) (assoc p (:id c) id :update_by (or user "")))))


(defn delete-item
  [{:keys [query-fn]} module id]
  (let [c (cfg module)]
    (query-fn (:delete c) {(:id c) id})))
