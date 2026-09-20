(ns com.ruoyi.domain.business.hrm
  "HRM 人力资源领域服务。"
  (:require
    [integrant.core :as ig]))


(defmethod ig/init-key :app.business/hrm-service
  [_ {:keys [query-fn db]}]
  {:query-fn query-fn :db db})


(defn- page-params
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))


(defn employee-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:name (get params :name)
           :dept_id (when-let [d (get params :dept_id)] (Integer/parseInt (str d)))
           :status (get params :status)
           :page_size size :offset offset}]
    {:rows (query-fn :hrm/employee-list p)
     :total (:total (query-fn :hrm/employee-count p))}))


(defn employee-get
  [{:keys [query-fn]} id]
  (query-fn :hrm/find-employee-by-id {:employee_id id}))


(defn employee-create
  [{:keys [query-fn]} params user]
  (query-fn :hrm/insert-employee
            {:emp_no (or (:emp_no params) "")
             :name (or (:name params) "")
             :dept_id (or (:dept_id params) 0)
             :post_id (or (:post_id params) 0)
             :gender (or (:gender params) "0")
             :phone (or (:phone params) "")
             :email (or (:email params) "")
             :id_card (or (:id_card params) "")
             :hire_date (:hire_date params)
             :status (or (:status params) "1")
             :salary_base (or (:salary_base params) 0)
             :remark (or (:remark params) "")
             :create_by (or user "")}))


(defn employee-update
  [{:keys [query-fn]} params user]
  (query-fn :hrm/update-employee
            {:employee_id (:employee_id params)
             :emp_no (or (:emp_no params) "")
             :name (or (:name params) "")
             :dept_id (or (:dept_id params) 0)
             :post_id (or (:post_id params) 0)
             :gender (or (:gender params) "0")
             :phone (or (:phone params) "")
             :email (or (:email params) "")
             :id_card (or (:id_card params) "")
             :hire_date (:hire_date params)
             :status (or (:status params) "1")
             :salary_base (or (:salary_base params) 0)
             :remark (or (:remark params) "")
             :update_by (or user "")}))


(defn employee-delete
  [{:keys [query-fn]} id]
  (query-fn :hrm/delete-employee {:employee_id id}))
