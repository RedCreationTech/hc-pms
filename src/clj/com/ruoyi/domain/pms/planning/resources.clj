(ns com.ruoyi.domain.pms.planning.resources
  "项目资源,每日容量覆盖与任务资源分配."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules]
            [com.ruoyi.domain.pms.planning.store :as store]))

(def fields
  "资源表单允许维护的字段."
  [:version :name :resource_type :user_id :daily_capacity])

(defn calendar!
  "保存工作日历并形成新的计划修订."
  [svc actor id body]
  (let [calendar (store/calendar! body)]
    (store/mutation! svc actor id body "plan.calendar.updated"
      (fn [q project]
        (store/ensure-plan! q project)
        (q :planning/calendar! {:project_id id :calendar_json (json/generate-string calendar)})
        calendar))))

(defn- input!
  "校验资源类别,项目成员及日容量,同一成员只建一个资源档案."
  [q project resource-id body]
  (let [type (or (:resource_type body) "person")
        uid (when (:user_id body) (kernel/user! q project (:user_id body) "资源成员"))
        daily (or (:daily_capacity body) (get-in (store/plan q project) [:calendar :hours_per_day]))]
    (when-not (contains? #{"person" "equipment"} type) (rules/fail! 400 "资源类别必须为 person 或 equipment"))
    (when (and (= type "person") (nil? uid)) (rules/fail! 400 "人员资源必须关联当前项目成员"))
    (when (and (= type "equipment") uid) (rules/fail! 400 "设备资源不能关联人员账号"))
    (when (and uid (some #(and (= uid (:user_id %)) (not= resource-id (:resource_id %)))
                         (store/rows q project :planning/resources)))
      (rules/fail! 409 "该成员已经建立资源档案"))
    {:resource_id resource-id :project_id (:project_id project)
     :name (rules/text! (:name body) "资源名称" 200 true) :resource_type type
     :user_id uid :daily_capacity (store/hours! daily "每日容量" true)}))

(defn create!
  "创建项目人员或设备资源."
  [svc actor id body]
  (rules/object! body fields)
  (store/mutation! svc actor id body "plan.resource.created"
    (fn [q project]
      (let [resource (input! q project (kernel/id) body)]
        (q :planning/create-resource! resource)
        resource))))

(defn update!
  "维护资源档案与默认每日容量."
  [svc actor id resource-id body]
  (rules/object! body fields)
  (store/mutation! svc actor id body "plan.resource.updated"
    (fn [q project]
      (let [old (store/resource! q project resource-id)
            resource (input! q project resource-id (merge old (dissoc body :version)))]
        (q :planning/update-resource! resource)
        resource))))

(defn delete!
  "删除尚未分配到任务的资源及其日容量覆盖."
  [svc actor id resource-id body]
  (rules/object! body [:version])
  (store/mutation! svc actor id body "plan.resource.deleted"
    (fn [q project]
      (store/resource! q project resource-id)
      (when (some #(= resource-id (:resource_id %)) (store/rows q project :planning/allocations))
        (rules/fail! 409 "资源仍有任务分配,请先移除分配"))
      (q :planning/delete-capacities! {:project_id id :resource_id resource-id})
      (q :planning/delete-resource! {:project_id id :resource_id resource-id})
      {:resource_id resource-id :deleted true})))

(defn capacity!
  "设置单日容量覆盖,可用零小时表示不可用."
  [svc actor id resource-id body]
  (rules/object! body [:version :date :capacity_hours])
  (store/mutation! svc actor id body "plan.capacity.updated"
    (fn [q project]
      (store/resource! q project resource-id)
      (let [date (rules/date! (:date body) "容量日期")
            record {:project_id id :resource_id resource-id :date date
                    :capacity_hours (store/hours! (:capacity_hours body) "日容量" true)}]
        (when-not date (rules/fail! 400 "容量日期不能为空"))
        (q :planning/delete-capacity! record)
        (q :planning/create-capacity! record)
        record))))

(defn allocate!
  "为普通任务分配每天固定工时,里程碑和汇总任务不可分配."
  [svc actor id body]
  (rules/object! body [:version :task_id :resource_id :hours_per_day])
  (store/mutation! svc actor id body "plan.allocation.created"
    (fn [q project]
      (let [task (store/task! q project (:task_id body))
            resource (store/resource! q project (:resource_id body))
            record {:allocation_id (kernel/id) :project_id id :task_id (:task_id task)
                    :resource_id (:resource_id resource)
                    :hours_per_day (store/hours! (:hours_per_day body) "分配工时" false)}]
        (when-not (= "task" (:task_type task)) (rules/fail! 400 "只能向普通任务分配资源"))
        (when (some #(= (select-keys % [:task_id :resource_id])
                        (select-keys record [:task_id :resource_id]))
                    (store/rows q project :planning/allocations))
          (rules/fail! 409 "资源已分配给该任务"))
        (q :planning/create-allocation! record)
        record))))

(defn delete-allocation!
  "移除本项目的一条资源分配."
  [svc actor id allocation-id body]
  (rules/object! body [:version])
  (store/mutation! svc actor id body "plan.allocation.deleted"
    (fn [q project]
      (when-not (some #(= allocation-id (:allocation_id %)) (store/rows q project :planning/allocations))
        (rules/fail! 404 "资源分配不存在或不属于当前项目"))
      (q :planning/delete-allocation! {:project_id id :allocation_id allocation-id})
      {:allocation_id allocation-id :deleted true})))
