(ns com.ruoyi.domain.pms.scan
  "本地定时进度扫描 (B19/H06): 对每个执行中项目生成当日进度快照 (PV/EV/AC 趋势) 与逾期提醒记录 (只写本地待办, 不投递外部消息).
   由 sys_job 'com.ruoyi.task/pms-progress-scan' 每日触发, 也可由管理员手动触发; 幂等: 同日快照覆盖更新, 同一对象只保留一条未关闭提醒."
  (:require
    [com.ruoyi.domain.pms.delivery.store :as d]
    [com.ruoyi.domain.pms.governance.store :as gov]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.planning.earned-value :as ev]
    [com.ruoyi.domain.pms.planning.network :as network]
    [com.ruoyi.domain.pms.planning.progress :as progress]
    [com.ruoyi.domain.pms.planning.store :as store]
    [com.ruoyi.domain.pms.rules :as rules])
  (:import
    (java.time
      LocalDate)
    (java.time.temporal
      ChronoUnit)))


(defn- days-late
  [date due]
  (.between ChronoUnit/DAYS (LocalDate/parse due) (LocalDate/parse date)))


(defn snapshot!
  "生成或更新当日进度快照记录 (code = 日期), 内容为挣值指标 + 卷积总进度 + 计数."
  [q project actor date]
  (let [snapshot (store/snapshot q project)
        tasks (store/rows q project :planning/tasks)
        nodes (vec (q :pms/nodes {:project_id (:project_id project)}))
        entries (q :finance/times {:project_id (:project_id project)})
        stages (or (network/effective-stages q project) [])
        rollup (progress/rollup tasks nodes stages)
        metrics (ev/earned-value tasks (:schedule snapshot) (:calendar snapshot) entries nodes date)
        issues (gov/records q project "issue")
        counts {:open_issues (count (remove #(contains? #{"closed" "converted" "discarded"} (:status %)) issues))
                :conflict_count (count (network/conflicts tasks (:schedule snapshot) nodes))
                :leaf_count (count (progress/leaf-rows tasks))}
        payload (ev/snapshot-payload date metrics rollup counts)
        existing (first (filter #(= date (:code %)) (gov/records q project "progress-snapshot")))]
    (if existing
      (gov/change! q project existing "recorded" (dissoc payload :code))
      (gov/insert! q project actor "progress-snapshot" payload {:status "recorded"}))))


(defn- overdue-targets
  "扫描逾期对象: 叶子任务 (排程完成日已过且未完成), 问题/行动 (到期日已过且未关闭), 交底 (截止已过且未完成), 现场任务 (计划开始已过且未开始)."
  [q project date]
  (let [snapshot (store/snapshot q project)
        rows (into {} (map (juxt :task_id identity) (get-in snapshot [:schedule :tasks])))
        tasks (filter #(and (not= "summary" (:task_type %)) (not= "done" (:status %))) (store/rows q project :planning/tasks))
        open-gov (fn [kind] (filter #(not (contains? #{"closed" "converted" "discarded" "resolved"} (:status %))) (gov/records q project kind)))]
    (concat
      (for [t tasks :let [end (:end_date (rows (:task_id t)))] :when (and end (neg? (compare end date)))]
        {:target_kind "task" :target_id (:task_id t) :title (str (:wbs_code t) " " (:name t)) :owner_id (:owner_id t) :due_date end :tab "计划与执行"})
      (for [r (open-gov "issue") :when (and (:due_date r) (neg? (compare (:due_date r) date)))]
        {:target_kind "issue" :target_id (:id r) :title (:title r) :owner_id (:owner_id r) :due_date (:due_date r) :tab "需求与治理"})
      (for [r (open-gov "action") :when (and (:due_date r) (neg? (compare (:due_date r) date)))]
        {:target_kind "action" :target_id (:id r) :title (:title r) :owner_id (:owner_id r) :due_date (:due_date r) :tab "需求与治理"})
      (for [r (d/records q project "handover") :when (and (= "open" (:status r)) (:deadline r) (neg? (compare (:deadline r) date)))]
        {:target_kind "handover" :target_id (:id r) :title (str "交底 " (:code r)) :owner_id (:owner_id r) :due_date (:deadline r) :tab "工程交付"})
      (for [r (d/records q project "site-task") :when (and (= "draft" (:status r)) (:planned_start r) (neg? (compare (:planned_start r) date)))]
        {:target_kind "site-task" :target_id (:id r) :title (str "现场任务 " (:title r)) :owner_id (:owner_id r) :due_date (:planned_start r) :tab "工程交付"}))))


(defn reminders!
  "登记新的逾期提醒 (同对象只保留一条未关闭), 刷新已有提醒的逾期天数, 关闭不再逾期的提醒; 返回计数."
  [q project actor date]
  (let [targets (overdue-targets q project date)
        wanted (into {} (map (fn [t] [(str (:target_kind t) ":" (:target_id t)) t]) targets))
        existing (filter #(= "open" (:status %)) (gov/records q project "reminder"))
        existing-codes (set (map :code existing))
        created (count (for [[code t] wanted :when (not (existing-codes code))]
                         (gov/insert! q project actor "reminder"
                                      (assoc t :code code :level "overdue" :days_overdue (days-late date (:due_date t))
                                             :project_no (:project_no project) :raised_on date :source "scheduled_scan")
                                      {:status "open"})))
        refreshed (count (for [r existing :when (contains? wanted (:code r))]
                           (gov/change! q project r "open" {:days_overdue (days-late date (:due_date r)) :last_seen date})))
        closed (count (for [r existing :when (not (contains? wanted (:code r)))]
                        (gov/change! q project r "closed" {:closed_on date :closed_reason "对象已不再逾期"})))]
    {:created created :refreshed refreshed :closed closed :open (+ created refreshed)}))


(defn scan-project!
  "对单个项目执行快照与提醒 (同一事务). 扫描不递增项目聚合版本也不写 pms_event (避免每日扫描让在编辑的用户遭遇版本冲突),
   快照与提醒记录本身 (created_by / raised_on / last_seen) 即是可追溯的扫描痕迹."
  [svc actor project-id date]
  (kernel/transaction! svc
    (fn [q]
      (let [project (q :pms/project {:project_id project-id})]
        (when-not project (rules/fail! 404 "项目不存在"))
        (let [snapshot (snapshot! q project actor date)
              reminders (reminders! q project actor date)]
          {:project_id project-id :project_no (:project_no project) :snapshot_id (:id snapshot)
           :spi (:spi snapshot) :cpi (:cpi snapshot) :overall_percent (:overall_percent snapshot) :reminders reminders})))))


(defn run-all!
  "扫描全部执行中项目; 手动触发须具备平台配置权限, 定时任务以系统身份调用."
  ([svc actor] (run-all! svc actor (str (LocalDate/now))))
  ([svc actor date]
   (rules/permit! actor "pms:config:edit")
   (let [q (:query-fn svc)
         projects (q :pms/projects-in-status {:status "execution"})]
     {:date date :project_count (count projects)
      :results (mapv #(scan-project! svc actor (:project_id %) date) projects)})))
