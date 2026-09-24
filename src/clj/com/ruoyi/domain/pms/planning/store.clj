(ns com.ruoyi.domain.pms.planning.store
  "计划聚合的持久化边界,快照与输入校验工具."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules]
            [com.ruoyi.domain.pms.planning.schedule :as schedule]))

(def task-fields
  "计划版本包含的任务设计字段,不混入实际进度."
  [:task_id :project_id :parent_id :wbs_code :name :task_type :duration_days
   :owner_id :start_date :description :source_type :source_id :node_id :stage_code])

(defn integer!
  "校验有边界的整数值."
  [value label minimum maximum]
  (when-not (and (integer? value) (<= minimum value maximum))
    (rules/fail! 400 (str label "必须是 " minimum " 至 " maximum " 的整数")))
  value)

(defn hours!
  "校验有限的日工时或容量,最多两位小数."
  [value label allow-zero?]
  (when-not (and (number? value) (Double/isFinite (double value))
                 (<= (if allow-zero? 0 0.01) value 24))
    (rules/fail! 400 (str label "必须在 " (if allow-zero? "0" "0.01") " 至 24 之间")))
  (let [value (bigdec value)]
    (when (> (.scale (.stripTrailingZeros value)) 2)
      (rules/fail! 400 (str label "最多保留两位小数")))
    value))

(defn rows
  "读取属于项目的计划集合."
  [q project key]
  (vec (q key {:project_id (:project_id project)})))

(defn plan
  "读取计划版本与日历,尚未编辑时使用默认日历."
  [q project]
  (if-let [row (q :planning/plan {:project_id (:project_id project)})]
    (-> row (assoc :calendar (json/parse-string (:calendar_json row) true)) (dissoc :calendar_json))
    {:project_id (:project_id project) :revision 0 :calendar schedule/default-calendar}))

(defn ensure-plan!
  "在首个修改事务中创建计划容器."
  [q project]
  (when-not (q :planning/plan {:project_id (:project_id project)})
    (q :planning/insert-plan! {:project_id (:project_id project)
                               :calendar_json (json/generate-string schedule/default-calendar)})))

(defn editable!
  "待审核快照提交后锁定设计,拒绝或审批结束后可以另开修订."
  [q project]
  (when (some #(= "submitted" (:status %)) (rows q project :planning/baselines))
    (rules/fail! 409 "计划正在审批,请完成审批后再修改"))
  (when (contains? #{"closing"} (:status project))
    (rules/fail! 409 "项目收尾中,不能修改计划")))

(defn changed!
  "递增计划设计修订,实际反馈不调用此函数."
  [q project]
  (ensure-plan! q project)
  (q :planning/bump-plan! {:project_id (:project_id project)}))

(defn mutation!
  "通过共享项目事务执行计划设计变更并递增设计修订."
  [svc actor id body event-type f]
  (kernel/mutate! svc actor id "pms:project:edit" body event-type
    (fn [q project]
      (editable! q project)
      (let [result (f q project)]
        (changed! q project)
        result))))

(defn task!
  "读取当前项目中的任务,禁止通过外部标识绑定跨项目数据."
  [q project id]
  (when-not (and (string? id) (parse-uuid id)) (rules/fail! 400 "任务标识必须是有效UUID"))
  (or (q :planning/task {:project_id (:project_id project) :task_id id})
      (rules/fail! 404 "任务不存在或不属于当前项目")))

(defn resource!
  "读取当前项目中的资源."
  [q project id]
  (when-not (and (string? id) (parse-uuid id)) (rules/fail! 400 "资源标识必须是有效UUID"))
  (or (q :planning/resource {:project_id (:project_id project) :resource_id id})
      (rules/fail! 404 "资源不存在或不属于当前项目")))

(defn calendar!
  "校验周工作日,假日和额外工作日,拒绝无工作日的日历."
  [body]
  (rules/object! body [:version :working_days :holidays :extra_workdays :hours_per_day])
  (let [calendar (merge schedule/default-calendar (dissoc body :version))]
    (when-not (and (vector? (:working_days calendar)) (seq (:working_days calendar))
                   (every? #(and (integer? %) (<= 1 % 7)) (:working_days calendar)))
      (rules/fail! 400 "每周至少指定一个工作日,使用 1 至 7 表示周一至周日"))
    (doseq [key [:holidays :extra_workdays]]
      (when-not (and (vector? (get calendar key)) (<= (count (get calendar key)) 3660))
        (rules/fail! 400 "假日与额外工作日必须是有限的日期列表"))
      (doseq [date (get calendar key)]
        (when-not (rules/date! date "日历日期") (rules/fail! 400 "日历日期不能为空"))))
    (assoc calendar :working_days (vec (sort (distinct (:working_days calendar))))
           :holidays (vec (sort (distinct (:holidays calendar))))
           :extra_workdays (vec (sort (distinct (:extra_workdays calendar))))
           :hours_per_day (hours! (:hours_per_day calendar) "每天工作小时" false))))

(defn snapshot
  "读取不可变基线所需的纯设计数据及可复算排程."
  [q project]
  (let [calendar (:calendar (plan q project))
        tasks (mapv #(select-keys % task-fields) (rows q project :planning/tasks))
        dependencies (rows q project :planning/dependencies)
        resources (rows q project :planning/resources)
        allocations (rows q project :planning/allocations)
        capacities (rows q project :planning/capacities)]
    {:calendar calendar :tasks tasks :dependencies dependencies :resources resources
     :allocations allocations :capacities capacities
     :schedule (schedule/schedule project tasks dependencies calendar)}))

(defn overloads
  "计算当前设计快照的日容量冲突."
  [snapshot]
  (schedule/overallocations (:schedule snapshot) (:resources snapshot)
                           (:allocations snapshot) (:capacities snapshot)))
