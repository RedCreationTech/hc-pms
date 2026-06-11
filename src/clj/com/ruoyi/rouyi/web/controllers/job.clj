(ns com.ruoyi.rouyi.web.controllers.job
  "定时任务管理控制器。"
  (:require
    [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-jobs
  [{:keys [query-fn]} request]
  (ok (query-fn :list-jobs (:query-params request))))

(defn get-job
  [{:keys [query-fn]} request]
  (let [job-id (parse-long (get-in request [:path-params :id]))]
    (if-let [job (query-fn :find-job-by-id {:job_id job-id})]
      (ok job)
      (fail "任务不存在"))))

(defn create-job
  [{:keys [query-fn]} request]
  (try
    (let [job-id (-> (query-fn :create-job! (:body-params request))
                    first
                    :job_id)]
      (ok (str "创建成功: " job-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-job
  [{:keys [query-fn]} request]
  (try
    (let [job-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :job_id job-id)]
      (query-fn :update-job! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-job
  [{:keys [query-fn]} request]
  (let [job-id (parse-long (get-in request [:path-params :id]))]
    (query-fn :delete-job! {:job_id job-id})
    (ok "删除成功")))

(defn list-job-logs
  [{:keys [query-fn]} request]
  (let [params (:query-params request)
        page-num (or (parse-long (:page-num params)) 1)
        page-size (or (parse-long (:page-size params)) 10)
        offset (* (dec page-num) page-size)
        filters (-> params
                    (dissoc :page-num :page-size)
                    (assoc :offset offset :page_size page-size))
        rows (query-fn :list-job-logs filters)
        total (query-fn :count-job-logs filters)]
    (ok {:total (:total total) :rows rows})))
