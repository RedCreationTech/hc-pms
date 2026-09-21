(ns com.ruoyi.infra.scheduler
  "定时任务调度器.

  封装 Quartz 调度,支持从 sys_job 加载任务,新增,修改,删除,
  暂停/恢复以及立即执行一次."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.ruoyi.infra.cron :as cron])
  (:import
    (org.quartz
      Job
      JobBuilder
      JobExecutionContext
      JobKey
      TriggerBuilder
      TriggerKey)
    (org.quartz.spi
      JobFactory
      TriggerFiredBundle)))


(defonce ^:private scheduler-atom (atom nil))
(defonce ^:private query-fn-atom (atom nil))


(defn set-scheduler!
  "注入 Quartz scheduler 实例."
  [scheduler]
  (reset! scheduler-atom scheduler))


(defn set-query-fn!
  "注入数据库 query-fn."
  [query-fn]
  (reset! query-fn-atom query-fn))


(defn- query-fn
  []
  (if-let [q @query-fn-atom]
    q
    (throw (IllegalStateException. "scheduler query-fn not initialized"))))


(defn- scheduler
  []
  (if-let [s @scheduler-atom]
    s
    (throw (IllegalStateException. "scheduler not initialized"))))


;; ──────────── 任务执行 ────────────

(def ^:private dangerous-patterns
  #{"rmi:" "ldap:" "ldaps:" "http://" "https://"})


(def ^:private allowed-ns-prefix
  "com.ruoyi.task")


(defn invoke-target-allowed?
  "校验 invoke_target 不包含危险协议,且调用目标在允许命名空间内."
  [target]
  (and (not (some #(str/includes? target %) dangerous-patterns))
       (str/starts-with? target allowed-ns-prefix)))


(defn- parse-invoke-target
  "解析 invoke_target 字符串.

  支持两种形式:
  - task-ns/task-fn           (无参函数)
  - task-ns/task-fn('a', 1)   (带参函数,仅支持字符串与数字字面量)

  返回 {:ns :fn :args}"
  [target]
  (when (seq target)
    (let [[head args-str] (str/split target #"\(" 2)
          [ns-part fn-part] (if (str/includes? head "/")
                              (str/split head #"/" 2)
                              [allowed-ns-prefix head])]
      {:ns (symbol ns-part)
       :fn (symbol fn-part)
       :args (when args-str
               (-> args-str
                   (str/replace #"\)$" "")
                   (str/split #"\s*,\s*")))})))


(defn- parse-arg
  "将参数字符串解析为 Clojure 值."
  [s]
  (let [s (str/trim s)]
    (cond
      (re-matches #"^['\"].*['\"]$" s) (str/replace s #"^['\"]|['\"]$" "")
      (re-matches #"^-?\d+$" s) (parse-long s)
      (re-matches #"^-?\d+\.\d+$" s) (parse-double s)
      (= "true" s) true
      (= "false" s) false
      :else s)))


(defn- invoke-target!
  "执行 invoke_target 指向的函数."
  [target]
  (if-let [{:keys [ns fn args]} (parse-invoke-target target)]
    (do
      (require ns)
      (let [var (ns-resolve ns fn)
            parsed-args (mapv parse-arg args)]
        (if var
          (apply @var parsed-args)
          (throw (ex-info (str "Target function not found: " target) {})))))
    (throw (ex-info (str "Invalid invoke target: " target) {}))))


(defn- write-job-log!
  "记录任务执行日志."
  [job status message exception]
  (try
    ((query-fn) :create-job-log!
                {:job_name (:job_name job)
                 :job_group (:job_group job)
                 :invoke_target (:invoke_target job)
                 :job_message message
                 :status status
                 :exception_info (or (some-> exception Throwable->map str) "")})
    (catch Exception e
      (log/warn e "Failed to write job log"))))


(def ^Job ruoyi-job
  (proxy [Job] []
    (execute
      [^JobExecutionContext ctx]
      (let [data (.getMergedJobDataMap ctx)
            job {:job_id (.getString data "job_id")
                 :job_name (.getString data "job_name")
                 :job_group (.getString data "job_group")
                 :invoke_target (.getString data "invoke_target")}
            start (System/currentTimeMillis)]
        (try
          (invoke-target! (:invoke_target job))
          (write-job-log! job "0"
                          (str "任务执行成功，耗时 " (- (System/currentTimeMillis) start) " ms")
                          nil)
          (catch Exception e
            (log/warn e "Job execution failed" job)
            (write-job-log! job "1"
                            (str "任务执行失败，耗时 " (- (System/currentTimeMillis) start) " ms")
                            e)))
        nil))))


;; ──────────── 调度操作 ────────────

(defn- job-key
  [job-id job-group]
  (JobKey/jobKey (str "job_" job-id) (or job-group "DEFAULT")))


(defn- trigger-key
  [job-id job-group]
  (TriggerKey/triggerKey (str "trigger_" job-id) (or job-group "DEFAULT")))


(defn- build-job-detail
  [job]
  (-> (JobBuilder/newJob (class ruoyi-job))
      (.withIdentity (job-key (:job_id job) (:job_group job)))
      (.usingJobData "job_id" (str (:job_id job)))
      (.usingJobData "job_name" (str (:job_name job)))
      (.usingJobData "job_group" (str (:job_group job)))
      (.usingJobData "invoke_target" (str (:invoke_target job)))
      (.build)))


(defn- build-cron-trigger
  [job]
  (-> (TriggerBuilder/newTrigger)
      (.withIdentity (trigger-key (:job_id job) (:job_group job)))
      (.forJob (job-key (:job_id job) (:job_group job)))
      (.withSchedule (cron/cron-schedule (:cron_expression job)
                                         (:misfire_policy job)))
      (.build)))


(defn schedule-job!
  "将 sys_job 记录注册到 Quartz 调度器."
  [job]
  (when (and (seq (:cron_expression job)) (invoke-target-allowed? (:invoke_target job)))
    (let [s (scheduler)
          job-detail (build-job-detail job)
          trigger (build-cron-trigger job)]
      (.scheduleJob s job-detail trigger)
      (when (= "1" (:status job))
        (.pauseJob s (job-key (:job_id job) (:job_group job)))))))


(defn reschedule-job!
  "更新 Quartz 中的任务."
  [job]
  (let [s (scheduler)
        k (job-key (:job_id job) (:job_group job))]
    (when (.checkExists s k)
      (.deleteJob s k))
    (schedule-job! job)))


(defn unschedule-job!
  "从 Quartz 中删除任务."
  [job-id job-group]
  (let [s (scheduler)]
    (.deleteJob s (job-key job-id job-group))))


(defn pause-job!
  "暂停任务."
  [job-id job-group]
  (let [s (scheduler)]
    (.pauseJob s (job-key job-id job-group))))


(defn resume-job!
  "恢复任务."
  [job-id job-group]
  (let [s (scheduler)]
    (.resumeJob s (job-key job-id job-group))))


(defn trigger-job!
  "立即触发任务执行一次."
  [job-id job-group]
  (let [s (scheduler)]
    (.triggerJob s (job-key job-id job-group))))


(defn load-jobs!
  "从 sys_job 表加载所有正常任务到调度器."
  []
  (let [jobs ((query-fn) :list-jobs {:job_name nil :job_group nil :status nil})]
    (doseq [job jobs]
      (try
        (schedule-job! job)
        (catch Exception e
          (log/warn e "Failed to schedule job" job))))))


(defn- make-job-factory
  []
  (reify JobFactory
    (^Job newJob [_ ^TriggerFiredBundle bundle ^org.quartz.Scheduler scheduler]
      ruoyi-job)))


(defn init!
  "初始化调度器:注入并加载任务."
  [scheduler query-fn]
  (set-scheduler! scheduler)
  (set-query-fn! query-fn)
  (.setJobFactory ^org.quartz.Scheduler scheduler (make-job-factory))
  (load-jobs!))
