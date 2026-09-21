(ns com.ruoyi.domain.business.reimburse
  "报销申请领域服务 -- 复用'业务记录 + BPM 审批流'模式."
  (:require
    [com.ruoyi.bpm.core :as bpm]
    [integrant.core :as ig]))


(def ^:const default-model-key "reimburseApproval")


(defmethod ig/init-key :app.business/reimburse-service
  [_ {:keys [engine query-fn db]}]
  {:engine engine :query-fn query-fn :db db})


(defn- page-params
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))


(defn- running?
  [{:keys [engine]} pid]
  (let [q (.createProcessInstanceQuery (.getRuntimeService engine))]
    (pos? (.count (.processInstanceId q pid)))))


(defn- rejected?
  [{:keys [engine]} pid]
  (boolean (some #(= "rejectEnd" (:activity-id %))
                 (bpm/history-of engine pid))))


(defn ensure-model-deployed!
  [{:keys [engine query-fn]}]
  (let [m (query-fn :bpm/find-model-by-key {:model_key default-model-key})]
    (when-not m
      (throw (ex-info "缺少内置报销审批模型，请重新迁移" {:key default-model-key})))
    (if-let [dep-id (:deployment_id m)]
      {:model m :deployment-id dep-id}
      (let [dep-id (bpm/deploy! engine (:bpmn_xml m) (:model_key m) (:model_name m))]
        (query-fn :bpm/update-model-deployment
                  {:model_id (:model_id m) :deployment_id dep-id
                   :version (inc (or (:version m) 1)) :status "1"})
        {:model m :deployment-id dep-id}))))


(defn reimburse-start!
  [{:keys [engine query-fn] :as svc} user-id user-name amount reason]
  (let [{:keys [model]} (ensure-model-deployed! svc)
        biz-key (str "reimburse-" (System/currentTimeMillis))
        started (bpm/start! engine default-model-key biz-key
                            {"amount" amount "reason" reason "userName" user-name})
        pid (:process-instance-id started)]
    (query-fn :oa/insert-reimburse
              {:user_id (or user-id 0) :user_name (or user-name "")
               :amount (or amount 0) :reason (or reason "")
               :process_instance_id pid :status "1"})
    (query-fn :bpm/insert-instance
              {:process_instance_id pid :model_id (:model_id model)
               :model_key default-model-key :business_key biz-key
               :form_data_json "{}" :starter_id (or user-name "")
               :status "1" :current_task ""})
    {:process-instance-id pid :business-key biz-key :model-key default-model-key}))


(defn sync-status!
  [{:keys [query-fn] :as svc} pid]
  (let [status (cond
                 (running? svc pid) "1"
                 (rejected? svc pid) "3"
                 :else "2")]
    (query-fn :oa/update-reimburse-status {:process_instance_id pid :status status})
    status))


(defn reimburse-list
  [{:keys [query-fn] :as svc} params]
  (let [{:keys [offset size]} (page-params params)
        p {:user_id (when-let [u (get params :user_id)] (Integer/parseInt (str u)))
           :status (get params :status)
           :page_size size :offset offset}
        rows (query-fn :oa/reimburse-list p)]
    (doseq [r rows
            :when (= "1" (:status r))
            :let [pid (:process_instance_id r)]]
      (sync-status! svc pid))
    {:rows (query-fn :oa/reimburse-list p)
     :total (:total (query-fn :oa/reimburse-count p))}))


(defn reimburse-get
  [{:keys [query-fn]} id]
  (query-fn :oa/find-reimburse-by-id {:reimburse_id id}))


(defn reimburse-delete
  [{:keys [query-fn]} id]
  (query-fn :oa/delete-reimburse {:reimburse_id id}))
