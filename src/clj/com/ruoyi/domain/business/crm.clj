(ns com.ruoyi.domain.business.crm
  "CRM 客户管理领域服务."
  (:require
    [integrant.core :as ig]))


(defmethod ig/init-key :app.business/crm-service
  [_ {:keys [query-fn db]}]
  {:query-fn query-fn :db db})


(defn- page-params
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))


(defn customer-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:name (get params :name)
           :level (get params :level)
           :owner_id (when-let [o (get params :owner_id)] (Integer/parseInt (str o)))
           :page_size size :offset offset}]
    {:rows (query-fn :crm/customer-list p)
     :total (:total (query-fn :crm/customer-count p))}))


(defn customer-get
  [{:keys [query-fn]} id]
  (query-fn :crm/find-customer-by-id {:customer_id id}))


(defn customer-create
  [{:keys [query-fn]} params user]
  (query-fn :crm/insert-customer
            {:name (or (:name params) "")
             :phone (or (:phone params) "") :email (or (:email params) "")
             :company (or (:company params) "") :level (or (:level params) "1")
             :source (or (:source params) "") :owner_id (or (:owner_id params) 0)
             :status (or (:status params) "1") :remark (or (:remark params) "")
             :create_by (or user "")}))


(defn customer-update
  [{:keys [query-fn]} params user]
  (query-fn :crm/update-customer
            {:customer_id (:customer_id params) :name (or (:name params) "")
             :phone (or (:phone params) "") :email (or (:email params) "")
             :company (or (:company params) "") :level (or (:level params) "1")
             :source (or (:source params) "") :owner_id (or (:owner_id params) 0)
             :status (or (:status params) "1") :remark (or (:remark params) "")
             :update_by (or user "")}))


(defn customer-delete
  [{:keys [query-fn]} id]
  (query-fn :crm/delete-customer {:customer_id id}))
