(ns com.ruoyi.domain.business.leave
  "请假申请领域服务 -- 业务记录 + BPM 审批流 的旗舰集成示例.
   请假单存 biz_oa_leave,审批流由内嵌 Flowable 驱动;
   process_instance_id 关联,status 随流程推进/结束自动同步."
  (:require
    [com.ruoyi.bpm.core :as bpm]
    [integrant.core :as ig]))


(def ^:const default-model-key "leaveApproval")


(defmethod ig/init-key :app.business/leave-service
  [_ {:keys [engine query-fn db]}]
  {:engine engine :query-fn query-fn :db db})


(defn- page-params
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))


(defn- running?
  "流程实例是否仍在运行."
  [{:keys [engine]} pid]
  (let [q (.createProcessInstanceQuery (.getRuntimeService engine))]
    (pos? (.count (.processInstanceId q pid)))))


(defn- rejected?
  "流程历史中是否出现过 rejectEnd 节点."
  [{:keys [engine]} pid]
  (boolean (some #(= "rejectEnd" (:activity-id %))
                 (bpm/history-of engine pid))))


(defn ensure-model-deployed!
  "确保默认请假审批模型已部署到 Flowable.返回 model."
  [{:keys [engine query-fn]}]
  (let [m (query-fn :bpm/find-model-by-key {:model_key default-model-key})]
    (when-not m
      (throw (ex-info "缺少内置请假审批模型，请重新迁移" {:key default-model-key})))
    (if-let [dep-id (:deployment_id m)]
      {:model m :deployment-id dep-id}
      (let [dep-id (bpm/deploy! engine (:bpmn_xml m) (:model_key m) (:model_name m))]
        (query-fn :bpm/update-model-deployment
                  {:model_id (:model_id m) :deployment_id dep-id
                   :version (inc (or (:version m) 1)) :status "1"})
        {:model m :deployment-id dep-id}))))


(defn leave-start!
  "发起请假申请:确保模型部署 -> 启动流程 -> 存业务记录(status=审批中)."
  [{:keys [engine query-fn] :as svc} user-id user-name days reason]
  (let [{:keys [model]} (ensure-model-deployed! svc)
        biz-key (str "leave-" (System/currentTimeMillis))
        started (bpm/start! engine default-model-key biz-key
                            {"days" days "reason" reason "userName" user-name})
        pid (:process-instance-id started)]
    (query-fn :oa/insert-leave
              {:user_id (or user-id 0) :user_name (or user-name "")
               :days (or days 0) :reason (or reason "")
               :process_instance_id pid :status "1"})
    ;; 同步写入流程实例映射,供 我的流程/流程图高亮 使用
    (query-fn :bpm/insert-instance
              {:process_instance_id pid :model_id (:model_id model)
               :model_key default-model-key :business_key biz-key
               :form_data_json "{}" :starter_id (or user-name "")
               :status "1" :current_task ""})
    {:leave-process-instance-id pid :business-key biz-key :model-key default-model-key}))


(defn sync-status!
  "按流程当前状态同步请假单 status:运行中=1, 已结束: 驳回=3 否则=2."
  [{:keys [query-fn] :as svc} pid]
  (let [status (cond
                 (running? svc pid) "1"
                 (rejected? svc pid) "3"
                 :else "2")]
    (query-fn :oa/update-leave-status {:process_instance_id pid :status status})
    status))


(defn leave-list
  [{:keys [query-fn] :as svc} params]
  (let [{:keys [offset size]} (page-params params)
        p {:user_id (when-let [u (get params :user_id)] (Integer/parseInt (str u)))
           :status (get params :status)
           :page_size size :offset offset}
        rows (query-fn :oa/leave-list p)]
    ;; 惰性同步:对每条运行中的请假单刷新状态
    (doseq [r rows
            :when (= "1" (:status r))
            :let [pid (:process_instance_id r)]]
      (sync-status! svc pid))
    {:rows (query-fn :oa/leave-list p)
     :total (:total (query-fn :oa/leave-count p))}))


(defn leave-get
  [{:keys [query-fn]} id]
  (query-fn :oa/find-leave-by-id {:leave_id id}))


(defn leave-delete
  [{:keys [query-fn]} id]
  (query-fn :oa/delete-leave {:leave_id id}))
