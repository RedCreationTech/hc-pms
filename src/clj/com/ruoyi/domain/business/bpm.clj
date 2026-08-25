(ns com.ruoyi.domain.business.bpm
  "BPM 业务领域服务。持有 Flowable 引擎 + 业务库 query-fn，
   提供流程分类/模型/表单/实例 的 CRUD 与流程运行操作。"
  (:require
   [cheshire.core :as json]
   [com.ruoyi.bpm.core :as bpm]
   [com.ruoyi.domain.business.bpm-flow :as bpm-flow]
   [integrant.core :as ig]))

;; ── Integrant 组件 ────────────────────────────────────────────────────
(defmethod ig/init-key :app.business/bpm-service
  [_ {:keys [engine query-fn db]}]
  {:engine engine :query-fn query-fn :db db})

;; ── 分页工具 ──────────────────────────────────────────────────────────
(defn- page-params
  "统一分页参数。"
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))

(defn- row->json
  "把表的 JSON 文本字段解析为 Clojure 数据。"
  [row ks]
  (reduce (fn [m k]
            (if-let [v (get row k)]
              (assoc m k (try (json/parse-string v true) (catch Exception _ v)))
              m))
          row ks))

;; ── 流程分类 ──────────────────────────────────────────────────────────
(defn category-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:name (get params :name) :page_size size :offset offset}]
    {:rows (query-fn :bpm/category-list p)
     :total (:total (query-fn :bpm/category-count p))}))

(defn category-get
  [{:keys [query-fn]} id]
  (query-fn :bpm/find-category-by-id {:category_id id}))

(defn category-create
  [{:keys [query-fn]} params user]
  (query-fn :bpm/insert-category
            {:name (:name params) :code (:code params)
             :sort (or (:sort params) 0) :status (or (:status params) "0")
             :create_by (or user "") :remark (or (:remark params) "")}))

(defn category-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-category
            {:category_id (:category_id params) :name (:name params)
             :code (:code params) :sort (or (:sort params) 0)
             :status (:status params) :update_by (or user "") :remark (:remark params)}))

(defn category-delete
  [{:keys [query-fn]} id]
  (query-fn :bpm/delete-category {:category_id id}))

;; ── 流程模型 ──────────────────────────────────────────────────────────
(defn model-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:model_name (get params :model_name) :category_id (get params :category_id)
           :page_size size :offset offset}]
    {:rows (mapv #(row->json % [:form_json :bpmn_xml])
                 (query-fn :bpm/model-list p))
     :total (:total (query-fn :bpm/model-count p))}))

(defn model-get
  [{:keys [query-fn]} id]
  (-> (query-fn :bpm/find-model-by-id {:model_id id})
      (row->json [:form_json :bpmn_xml])))

(defn model-get-by-key
  [{:keys [query-fn]} key]
  (-> (query-fn :bpm/find-model-by-key {:model_key key})
      (row->json [:form_json :bpmn_xml])))

(defn model-create
  [{:keys [query-fn]} params user]
  (query-fn :bpm/insert-model
            {:model_key (:model_key params) :model_name (:model_name params)
             :category_id (or (:category_id params) 0) :version 1
             :form_type (or (:form_type params) "0")
             :form_json (:form_json params) :bpmn_xml (:bpmn_xml params)
             :deployment_id (:deployment_id params) :status (or (:status params) "1")
             :create_by (or user "") :remark (or (:remark params) "")}))

(defn model-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-model
            {:model_id (:model_id params) :model_name (:model_name params)
             :category_id (or (:category_id params) 0) :form_type (:form_type params)
             :form_json (:form_json params) :bpmn_xml (:bpmn_xml params)
             :deployment_id (:deployment_id params) :status (:status params)
             :update_by (or user "") :remark (:remark params)}))

(defn model-delete
  [{:keys [engine query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (when-let [dep-id (:deployment_id m)]
      (try (bpm/delete-deployment! engine dep-id)
           (catch Exception _ nil)))
    (query-fn :bpm/delete-model {:model_id id})))

(defn model-deploy!
  "部署流程模型到 Flowable，并回写 deployment_id。返回新 deployment-id。"
  [{:keys [engine query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})
        _ (when-not m (throw (ex-info "流程模型不存在" {:model_id id})))
        _ (when-not (:bpmn_xml m) (throw (ex-info "模型未定义 BPMN" {:model_id id})))
        dep-id (bpm/deploy! engine (:bpmn_xml m) (:model_key m) (:model_name m))
        new-version (inc (or (:version m) 1))]
    (query-fn :bpm/update-model-deployment
              {:model_id id :deployment_id dep-id :version new-version :status "1"})
    {:deployment-id dep-id :version new-version}))

(defn model-tree
  "把模型 BPMN 转为流程节点树（HTML/flex 编辑器工作模型）。"
  [{:keys [query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (bpm-flow/bpmn->tree (:bpmn_xml m))))

(defn model-save-tree!
  "保存流程节点树：转回 BPMN XML 并更新模型。返回新 XML。"
  [{:keys [query-fn]} id tree user]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})
        xml (bpm-flow/tree->bpmn (clojure.walk/keywordize-keys tree))]
    (query-fn :bpm/update-model
              {:model_id id :model_name (:model_name m)
               :category_id (:category_id m) :form_type (:form_type m)
               :form_json (:form_json m) :bpmn_xml xml :deployment_id nil
               :status "1" :update_by (or user "") :remark (:remark m)})
    {:bpmn_xml xml}))

;; ── 动态表单 ──────────────────────────────────────────────────────────
(defn form-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:form_name (get params :form_name) :page_size size :offset offset}]
    {:rows (mapv #(row->json % [:form_json]) (query-fn :bpm/form-list p))
     :total (:total (query-fn :bpm/form-count p))}))

(defn form-get
  [{:keys [query-fn]} id]
  (-> (query-fn :bpm/find-form-by-id {:form_id id})
      (row->json [:form_json])))

(defn form-create
  [{:keys [query-fn]} params user]
  (query-fn :bpm/insert-form
            {:form_name (:form_name params) :form_key (:form_key params)
             :form_json (:form_json params) :status (or (:status params) "0")
             :create_by (or user "") :remark (or (:remark params) "")}))

(defn form-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-form
            {:form_id (:form_id params) :form_name (:form_name params)
             :form_key (:form_key params) :form_json (:form_json params)
             :status (:status params) :update_by (or user "") :remark (:remark params)}))

(defn form-delete
  [{:keys [query-fn]} id]
  (query-fn :bpm/delete-form {:form_id id}))

;; ── 流程实例（发起 + 运行）────────────────────────────────────────────
(defn instance-start!
  "发起流程：用模型部署的 key 启动 Flowable 实例，写入 biz_bpm_instance。"
  [{:keys [engine query-fn]} model-id business-key form-data starter]
  (let [m (query-fn :bpm/find-model-by-id {:model_id model-id})
        _ (when-not m (throw (ex-info "流程模型不存在" {:model_id model-id})))
        _ (when-not (:deployment_id m)
            (throw (ex-info "模型未部署，请先部署" {:model_id model-id :key (:model_key m)})))
        biz-key (or business-key (str "biz-" (System/currentTimeMillis)))
        started (bpm/start! engine (:model_key m) biz-key
                            {"formData" (json/generate-string (or form-data {}))})
        pid (:process-instance-id started)]
    (query-fn :bpm/insert-instance
              {:process_instance_id pid :model_id model-id :model_key (:model_key m)
               :business_key biz-key
               :form_data_json (json/generate-string (or form-data {}))
               :starter_id (or starter "") :status "1"
               :current_task (-> (first (bpm/todo-list engine (or starter ""))) :name (or ""))})
    {:process-instance-id pid :business-key biz-key}))

(defn instance-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:starter_id (get params :starter_id) :model_key (get params :model_key)
           :page_size size :offset offset}]
    {:rows (mapv #(row->json % [:form_data_json]) (query-fn :bpm/instance-list p))
     :total (:total (query-fn :bpm/instance-count p))}))

(defn instance-history
  "流程实例的完整历史轨迹（引擎侧 + 业务侧）。"
  [{:keys [engine query-fn]} pid]
  (let [biz (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})]
    {:instance (row->json biz [:form_data_json])
     :activities (bpm/history-of engine pid)
     :running? (pos? (bpm/todo-count engine (or (:starter_id biz) "")))}))

(defn instance-diagram
  "流程实例的图示数据：BPMN XML + 进行中/已完成节点 id，供前端 bpmn-js 高亮。"
  [{:keys [engine query-fn]} pid]
  (let [biz (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        _ (when-not biz (throw (ex-info "流程实例不存在" {:pid pid})))
        model (query-fn :bpm/find-model-by-id {:model_id (:model_id biz)})
        active (bpm/active-activity-ids engine pid)
        completed (bpm/completed-activity-ids engine pid)]
    {:process-instance-id pid
     :model-name (:model_name model)
     :bpmn-xml (:bpmn_xml model)
     :active-activity-ids active
     :completed-activity-ids completed
     :running? (seq active)}))

(defn office-stats
  "办公一体化统计看板数据：请假/报销/流程/员工/客户。"
  [{:keys [engine query-fn]} user]
  (let [leave-status (query-fn :stats/leave-by-status {})
        reimburse-status (query-fn :stats/reimburse-by-status {})
        leave-total (get (query-fn :stats/leave-total {}) :total 0)
        reimburse-total (get (query-fn :stats/reimburse-total {}) :total 0)
        employee-total (get (query-fn :stats/employee-total {}) :total 0)
        customer-total (get (query-fn :stats/customer-total {}) :total 0)
        st (fn [rows k] (or (some #(= k (:status %)) rows) 0))
        cnt (fn [rows k] (:cnt (first (filter #(= k (:status %)) rows)) 0))
        reimb-amount (fn [rows k] (or (:total_amount (first (filter #(= k (:status %)) rows))) 0))
        total-amount (reduce + (map #(or (:total_amount %) 0) reimburse-status))]
    {:leave {:total leave-total
             :pending (cnt leave-status "1")
             :approved (cnt leave-status "2")
             :rejected (cnt leave-status "3")}
     :reimburse {:total reimburse-total
                 :pending (cnt reimburse-status "1")
                 :approved (cnt reimburse-status "2")
                 :rejected (cnt reimburse-status "3")
                 :total-amount total-amount
                 :pending-amount (reimb-amount reimburse-status "1")
                 :approved-amount (reimb-amount reimburse-status "2")}
     :process {:running (bpm/instance-count engine)
               :definitions (count (bpm/definitions engine))
               :my-todo (bpm/todo-count engine (or user ""))}
     :hrm {:employee-total employee-total}
     :crm {:customer-total customer-total}}))
