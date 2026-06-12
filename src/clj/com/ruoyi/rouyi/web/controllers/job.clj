(ns com.ruoyi.rouyi.web.controllers.job
  "定时任务控制器。"
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [com.ruoyi.rouyi.infra.scheduler :as scheduler-core]
   [com.ruoyi.rouyi.infra.cron :as cron]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn- current-user-name [request]
  (get-in request [:identity :user-name] ""))

(defn- body-params [request]
  (walk/keywordize-keys (:body-params request {})))

(defn list-jobs
  [{:keys [query-fn]} request]
  (ok (query-fn :list-jobs (merge {:job_name nil :job_group nil :status nil}
                                  (:query-params request)))))

(defn get-job
  [{:keys [query-fn]} request]
  (let [job-id (parse-long (get-in request [:path-params :id]))]
    (if-let [job (query-fn :find-job-by-id {:job_id job-id})]
      (ok job)
      (fail "任务不存在"))))

(defn- validate-job! [job]
  (when-not (cron/valid? (:cron_expression job))
    (throw (ex-info "cron 表达式不合法" {})))
  (when-not (scheduler-core/invoke-target-allowed? (:invoke_target job))
    (throw (ex-info "调用目标不合法或不在允许命名空间内" {}))))

(defn create-job
  [{:keys [query-fn]} request]
  (try
    (let [params (-> {:job_name nil :job_group nil :invoke_target nil :cron_expression nil
                     :misfire_policy nil :concurrent nil :status nil :remark nil :create_by nil}
                     (merge (body-params request))
                     (assoc :create_by (current-user-name request)))
          _ (validate-job! params)
          _ (query-fn :create-job! params)
          id (:job_id (query-fn :last-insert-job-id {}))]
      (when-let [job (query-fn :find-job-by-id {:job_id id})]
        (scheduler-core/schedule-job! job))
      (ok {:job_id id}))
    (catch Exception e
      (fail (.getMessage e)))))

(defn update-job
  [{:keys [query-fn]} request]
  (try
    (let [job-id (parse-long (get-in request [:path-params :id]))
          params (-> {:job_name nil :job_group nil :invoke_target nil :cron_expression nil
                     :misfire_policy nil :concurrent nil :status nil :remark nil :update_by nil}
                     (merge (body-params request))
                     (assoc :job_id job-id)
                     (assoc :update_by (current-user-name request)))
          _ (validate-job! params)]
      (query-fn :update-job! params)
      (when-let [job (query-fn :find-job-by-id {:job_id job-id})]
        (scheduler-core/reschedule-job! job))
      (ok "更新成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn delete-job
  [{:keys [query-fn]} request]
  (try
    (let [job-id (parse-long (get-in request [:path-params :id]))
          job (query-fn :find-job-by-id {:job_id job-id})]
      (query-fn :delete-job! {:job_id job-id})
      (when job
        (scheduler-core/unschedule-job! job-id (:job_group job)))
      (ok "删除成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn list-job-logs
  [{:keys [query-fn]} request]
  (let [params (:query-params request)
        ->kw (fn [k] (keyword (str/replace (name k) #"-" "_")))
        norm (->> params
                  (map (fn [[k v]] [(->kw k) v]))
                  (into {}))
        page-num (or (some-> (:page_num norm) parse-long) 1)
        page-size (or (some-> (:page_size norm) parse-long) 10)
        offset (* (dec page-num) page-size)
        filters (merge {:job_name nil :job_group nil :status nil}
                       (-> norm
                           (dissoc :page_num :page_size)
                           (assoc :offset offset :page_size page-size)))]
    (ok {:rows (query-fn :list-job-logs filters)
         :total (-> (query-fn :count-job-logs filters) first :total)})))

(defn execute-job
  [{:keys [query-fn]} request]
  (try
    (let [job-id (parse-long (get-in request [:path-params :id]))]
      (query-fn :execute-job! {:job_id job-id})
      (ok "执行成功"))
    (catch Exception e
      (fail (.getMessage e)))))

(defn change-status
  "修改任务状态。"
  [{:keys [query-fn]} request]
  (let [job-id (parse-long (get-in request [:path-params :id]))
        status (:status (body-params request))
        job (query-fn :find-job-by-id {:job_id job-id})]
    (query-fn :update-job! (merge {:job_id nil :job_name nil :job_group nil :invoke_target nil
                                   :cron_expression nil :misfire_policy nil :concurrent nil
                                   :status nil :remark nil :update_by nil}
                                  {:job_id job-id :status status :update_by (current-user-name request)}))
    (when job
      (if (= "0" status)
        (scheduler-core/resume-job! job-id (:job_group job))
        (scheduler-core/pause-job! job-id (:job_group job))))
    (ok "状态修改成功")))

(defn run-once
  "立即执行一次任务。"
  [_ request]
  (let [job-id (parse-long (get-in request [:path-params :id]))]
    (scheduler-core/trigger-job! job-id "DEFAULT")
    (ok (str "任务 " job-id " 已触发执行"))))

(defn clean-logs
  "清空任务日志。"
  [{:keys [query-fn]} _]
  (query-fn :clean-job-logs! {})
  (ok "日志已清空"))
