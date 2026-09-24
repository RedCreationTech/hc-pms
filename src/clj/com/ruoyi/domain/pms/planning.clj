(ns com.ruoyi.domain.pms.planning
  "计划工作台对外应用服务及其他PMS模块的集成入口."
  (:require [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.governance.store :as governance]
            [com.ruoyi.domain.pms.planning.capacity :as capacity]
            [com.ruoyi.domain.pms.governance.quality :as quality]
            [com.ruoyi.domain.pms.planning.earned-value :as ev]
            [com.ruoyi.domain.pms.planning.network :as network]
            [com.ruoyi.domain.pms.planning.progress :as progress]
            [com.ruoyi.domain.pms.rules :as rules]
            [com.ruoyi.domain.pms.scan :as scan]
            [com.ruoyi.domain.pms.planning.store :as store]
            [com.ruoyi.domain.pms.planning.tasks :as tasks]
            [com.ruoyi.domain.pms.planning.resources :as resources]
            [com.ruoyi.domain.pms.planning.baseline :as baseline]))

(defn read-plan
  "读取项目计划,排程,容量冲突,实际反馈及基线列表."
  [svc actor id]
  (kernel/read! svc actor id "pms:project:query"
    (fn [q project]
      (let [plan (store/plan q project) snapshot (store/snapshot q project)
            baselines (store/rows q project :planning/baselines)
            current (first (filter #(= (:revision plan) (:plan_revision %)) baselines))
            raw-tasks (store/rows q project :planning/tasks)
            paused (quality/paused-node-ids q project)
            tasks (mapv #(assoc % :node_paused (boolean (some->> (progress/task-node raw-tasks %) (contains? paused)))) raw-tasks)
            nodes (vec (q :pms/nodes {:project_id (:project_id project)}))
            stages (network/effective-stages q project)
            today (str (java.time.LocalDate/now))
            entries (q :finance/times {:project_id (:project_id project)})]
        (merge snapshot
               {:tasks tasks :nodes nodes :stages (or stages [])
                :stage_weight_source (network/stage-weight-source q project)
                :stage_weights (first (governance/records q project "stage-weights"))
                :node_pauses (governance/records q project "node-pause")
                :paused_node_ids (vec paused)
                :progress_rollup (assoc (progress/rollup tasks nodes stages) :source (network/stage-weight-source q project))
                :plan_conflicts (network/conflicts raw-tasks (:schedule snapshot) nodes)
                :earned_value (ev/earned-value raw-tasks (:schedule snapshot) (:calendar snapshot) entries nodes today)
                :progress_history (->> (governance/records q project "progress-snapshot") (sort-by :snapshot_date) vec)
                :reschedules (governance/records q project "reschedule")
                :reminders (filterv #(= "open" (:status %)) (governance/records q project "reminder"))
                :project_version (:version project) :plan_revision (:revision plan)
                :plan_status (or (:status current) "draft") :baselines baselines
                :feedback (store/rows q project :planning/feedback)
                :approved_changes (->> (governance/records q project "change") governance/latest
                                        (filter #(= "approved" (:status %)))
                                        (mapv #(select-keys % [:id :title])))
                :current_user_id (:user_id actor) :overallocations (capacity/overloads q project snapshot)})))))

(defn read-baseline
  "读取不可变的已提交计划快照."
  [svc actor id baseline-id]
  (kernel/read! svc actor id "pms:project:query" #(baseline/record! %1 %2 baseline-id)))

(defn baseline-diff
  "返回指定基线与当前计划设计的差异."
  [svc actor id baseline-id]
  (kernel/read! svc actor id "pms:project:query" #(baseline/differences %1 %2 baseline-id)))

(defn project-dates-changing!
  "项目日期更改时锁定待审批设计并创建新计划修订,供项目服务事务调用."
  [q project]
  (when (q :planning/plan {:project_id (:project_id project)})
    (store/editable! q project)
    (store/changed! q project)))

(def create-task-record! "调用方事务内建立任务并递增计划修订." tasks/create-record!)
(def execution-ready! "检查当前设计存在内容一致的已批准基线." baseline/execution-ready!)
(def create-task! "创建WBS任务." tasks/create!)
(def update-task! "更新任务设计." tasks/update!)
(def delete-task! "删除未使用任务." tasks/delete!)
(def create-dependency! "添加并验证任务依赖." tasks/dependency!)
(def delete-dependency! "移除任务依赖." tasks/delete-dependency!)
(def task-feedback! "反馈任务实际进度." tasks/feedback!)
(def update-calendar! "修改项目工作日历." resources/calendar!)
(def create-resource! "创建项目资源档案." resources/create!)
(def update-resource! "修改资源日容量." resources/update!)
(def delete-resource! "删除未使用资源." resources/delete!)
(def set-capacity! "维护资源单日容量." resources/capacity!)
(def create-allocation! "添加任务资源分配." resources/allocate!)
(def delete-allocation! "移除任务资源分配." resources/delete-allocation!)
(def submit-plan! "提交并冻结待审计划快照." baseline/submit!)
(def review-plan! "独立审批计划基线." baseline/review!)
(def derive-network! "从模板阶段派生子项目/单机计划 (B03)." network/derive!)
(def reschedule-node! "重排节点下未开始任务, 保留原基线 (H04)." network/reschedule!)
(def set-stage-weights! "项目级阶段权重覆盖 (B02)." network/set-stage-weights!)

(defn snapshot-now!
  "手动生成当日进度快照与逾期提醒 (与定时扫描同一实现), 须具备项目编辑权限与项目读取范围."
  [svc actor id body]
  (rules/object! body [:version :date])
  (kernel/read! svc actor id "pms:project:edit" (fn [_ _] nil))
  (scan/scan-project! svc actor id (or (some-> (:date body) (rules/date! "快照日期")) (str (java.time.LocalDate/now)))))
