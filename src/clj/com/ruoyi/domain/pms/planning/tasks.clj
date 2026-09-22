(ns com.ruoyi.domain.pms.planning.tasks
  "WBS任务,依赖图与实际进度反馈的项目内操作."
  (:require [clojure.string :as str]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.governance.store :as governance]
            [com.ruoyi.domain.pms.delivery.store :as delivery]
            [com.ruoyi.domain.pms.rules :as rules]
            [com.ruoyi.domain.pms.planning.schedule :as schedule]
            [com.ruoyi.domain.pms.planning.store :as store]))

(def fields
  "客户端允许维护的任务设计字段."
  [:version :parent_id :wbs_code :name :task_type :duration_days :owner_id :start_date :description])

(defn- parent!
  "要求WBS父级是同项目汇总任务,检查循环和层级上限."
  [q project task-id parent-id]
  (loop [id parent-id depth 0]
    (when id
      (when (= id task-id) (rules/fail! 409 "WBS父子关系不能形成循环"))
      (when (> depth 30) (rules/fail! 400 "WBS层级不能超过 30 层"))
      (let [parent (store/task! q project id)]
        (when-not (= "summary" (:task_type parent))
          (rules/fail! 400 "父任务必须是汇总任务"))
        (recur (:parent_id parent) (inc depth))))))

(defn- task-input!
  "校验任务设计,必填开始日期,成员负责人和工期."
  [q project task-id body]
  (let [type (or (:task_type body) "task")
        duration (if (= type "task") (or (:duration_days body) 1) (or (:duration_days body) 0))
        code (rules/text! (:wbs_code body) "WBS编号" 64 true)
        existing (q :planning/task-code {:project_id (:project_id project) :wbs_code code})
        start (rules/date! (or (:start_date body) (:start_date project)) "任务开始日期")]
    (when-not (contains? #{"summary" "task" "milestone"} type) (rules/fail! 400 "无效任务类型"))
    (store/integer! duration "计划工期" (if (= type "task") 1 0) (if (= type "task") 3650 0))
    (when-not start (rules/fail! 400 "请填写任务或项目开始日期"))
    (when (and existing (not= task-id (:task_id existing))) (rules/fail! 409 "WBS编号已存在"))
    (parent! q project task-id (:parent_id body))
    {:task_id task-id :project_id (:project_id project) :parent_id (:parent_id body)
     :wbs_code code :name (rules/text! (:name body) "任务名称" 200 true)
     :task_type type :duration_days duration :start_date start
     :owner_id (when (:owner_id body) (kernel/user! q project (:owner_id body) "任务负责人"))
     :description (rules/text! (:description body) "任务说明" 2000 false)
     :source_type (:source_type body) :source_id (:source_id body)}))

(defn create-record!
  "在调用方事务内创建真实任务并递增设计修订,供会议行动项复用."
  [q project actor fields]
  (store/editable! q project)
  (when (>= (count (store/rows q project :planning/tasks)) 1000)
    (rules/fail! 400 "当前项目任务数量已达到 1000 个上限"))
  (let [id (kernel/id)
        body (merge {:wbs_code (str "ACT-" (subs id 0 8)) :task_type "task" :duration_days 1} fields)
        task (task-input! q project id body)]
    (q :planning/create-task! task)
    (store/changed! q project)
    (store/task! q project id)))

(defn create!
  "创建项目WBS任务及事务审计."
  [svc actor id body]
  (rules/object! body fields)
  (kernel/mutate! svc actor id "pms:project:edit" body "plan.task.created"
    #(create-record! %1 %2 actor (dissoc body :version))))

(defn- type-change!
  "已有关联的任务不能被改为不兼容的汇总类型."
  [q project old task]
  (when (not= (:task_type old) (:task_type task))
    (let [id (:task_id task) tasks (store/rows q project :planning/tasks)
          dependencies (store/rows q project :planning/dependencies)
          allocations (store/rows q project :planning/allocations)]
      (when (or (some #(= id (:parent_id %)) tasks)
                (some #(or (= id (:predecessor_id %)) (= id (:successor_id %))) dependencies)
                (some #(= id (:task_id %)) allocations)
                (not= "todo" (:status old)))
        (rules/fail! 409 "任务已存在子任务,依赖,分配或实际进度,不能更改类型")))))

(defn update!
  "更新任务设计而不覆盖已上报的实际进度."
  [svc actor id task-id body]
  (rules/object! body fields)
  (store/mutation! svc actor id body "plan.task.updated"
    (fn [q project]
      (let [old (store/task! q project task-id)
            task (task-input! q project task-id (merge old (dissoc body :version)))]
        (type-change! q project old task)
        (q :planning/update-task! task)
        (store/task! q project task-id)))))

(defn delete!
  "只允许删除没有子任务,依赖,资源分配和反馈的未开始任务."
  [svc actor id task-id body]
  (rules/object! body [:version])
  (store/mutation! svc actor id body "plan.task.deleted"
    (fn [q project]
      (let [task (store/task! q project task-id)
            dependencies (store/rows q project :planning/dependencies)]
        (when (or (= "meeting_action" (:source_type task))
                  (governance/task-referenced? q project task-id)
                  (delivery/task-referenced? q project task-id)
                  (pos? (:total (q :planning/task-finance-references {:project_id id :task_id task-id})))
                  (some #(= task-id (:parent_id %)) (store/rows q project :planning/tasks))
                  (some #(or (= task-id (:predecessor_id %)) (= task-id (:successor_id %))) dependencies)
                  (some #(= task-id (:task_id %)) (store/rows q project :planning/allocations))
                  (some #(= task-id (:task_id %)) (store/rows q project :planning/feedback))
                  (not= "todo" (:status task)))
          (rules/fail! 409 "请先处理任务子级和关联,已形成业务追踪或实际反馈的任务不得删除"))
        (q :planning/delete-task! {:project_id id :task_id task-id})
        {:task_id task-id :deleted true}))))

(defn dependency!
  "新增四类前置依赖,拒绝自依赖,跨项目,重复和依赖环."
  [svc actor id body]
  (rules/object! body [:version :predecessor_id :successor_id :dependency_type :lag_days])
  (store/mutation! svc actor id body "plan.dependency.created"
    (fn [q project]
      (let [pre (store/task! q project (:predecessor_id body))
            suc (store/task! q project (:successor_id body))
            dependency (assoc (dissoc body :version) :dependency_id (kernel/id) :project_id id
                               :lag_days (store/integer! (or (:lag_days body) 0) "提前或滞后工作日" -3650 3650))
            current (store/rows q project :planning/dependencies)]
        (when (or (= (:task_id pre) (:task_id suc))
                  (some #(= "summary" (:task_type %)) [pre suc]))
          (rules/fail! 400 "依赖必须连接两个不同的非汇总任务"))
        (when-not (contains? #{"FS" "SS" "FF" "SF"} (:dependency_type body))
          (rules/fail! 400 "依赖类型必须为 FS,SS,FF 或 SF"))
        (when (some #(= (select-keys % [:predecessor_id :successor_id])
                        (select-keys body [:predecessor_id :successor_id])) current)
          (rules/fail! 409 "这两个任务之间已存在依赖"))
        (schedule/topological-order (filterv #(not= "summary" (:task_type %))
                                             (store/rows q project :planning/tasks))
                                    (conj current dependency))
        (q :planning/create-dependency! dependency)
        dependency))))

(defn delete-dependency!
  "移除当前项目的一条依赖边."
  [svc actor id dependency-id body]
  (rules/object! body [:version])
  (store/mutation! svc actor id body "plan.dependency.deleted"
    (fn [q project]
      (when-not (some #(= dependency-id (:dependency_id %)) (store/rows q project :planning/dependencies))
        (rules/fail! 404 "任务依赖不存在或不属于当前项目"))
      (q :planning/delete-dependency! {:project_id id :dependency_id dependency-id})
      {:dependency_id dependency-id :deleted true})))

(defn feedback!
  "执行期间记录实际进度,完成任务不可回退,不修改计划设计修订."
  [svc actor id task-id body]
  (rules/object! body [:version :status :percent_complete :remaining_days :comment])
  (kernel/mutate! svc actor id "pms:project:edit" body "plan.task.feedback"
    (fn [q project]
      (when-not (= "execution" (:status project)) (rules/fail! 409 "仅执行阶段可以反馈任务进度"))
      (let [task (store/task! q project task-id) status (:status body)
            percent (store/integer! (:percent_complete body) "完成百分比" 0 100)
            remaining (store/integer! (:remaining_days body) "剩余工期" 0 3650)
            record {:feedback_id (kernel/id) :project_id id :task_id task-id :user_id (:user_id actor)
                    :status status :percent_complete percent :remaining_days remaining
                    :project_version (inc (:version project))
                    :comment (rules/text! (:comment body) "进度说明" 2000 false)}]
        (when (= "summary" (:task_type task)) (rules/fail! 400 "汇总任务不能直接反馈进度"))
        (when-not (contains? #{"in_progress" "blocked" "done"} status) (rules/fail! 400 "无效任务状态"))
        (when (or (< percent (:percent_complete task))
                  (and (= "done" (:status task)) (not= status "done")))
          (rules/fail! 409 "已确认的实际进度不可回退"))
        (when (or (and (= status "done") (not (and (= 100 percent) (zero? remaining))))
                  (and (not= status "done") (= 100 percent)))
          (rules/fail! 400 "完成状态必须为 100% 且剩余工期为 0"))
        (q :planning/create-feedback! record)
        (q :planning/task-progress! record)
        record))))
