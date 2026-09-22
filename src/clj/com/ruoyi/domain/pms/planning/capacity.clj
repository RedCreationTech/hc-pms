(ns com.ruoyi.domain.pms.planning.capacity
  "跨项目人员容量汇总,对外只返回本项目资源和匿名占用总量."
  (:require [com.ruoyi.domain.pms.planning.store :as store]))

(defn- person-resources
  "从快照中取得需要共享容量的人员资源."
  [snapshot user-ids]
  (filter #(and (= "person" (:resource_type %)) (contains? user-ids (:user_id %)))
          (:resources snapshot)))

(defn- person-loads
  "把任务工作日上的资源分配折合为人员每日总量."
  [snapshot user-ids]
  (let [people (into {} (map (juxt :resource_id :user_id) (person-resources snapshot user-ids)))
        tasks (into {} (map (juxt :task_id identity) (get-in snapshot [:schedule :tasks])))]
    (reduce (fn [loads {:keys [resource_id task_id hours_per_day]}]
              (if-let [uid (people resource_id)]
                (reduce #(update %1 [uid %2] (fnil + 0M) (bigdec hours_per_day))
                        loads (:working_dates (tasks task_id)))
                loads)) {} (:allocations snapshot))))

(defn- capacity-on
  "同一人员在多个未结束项目中的容量设置取保守最小值."
  [snapshots uid date]
  (apply min
         (mapcat (fn [snapshot]
                   (let [overrides (into {} (map #(vector [(:resource_id %) (:date %)] (:capacity_hours %))
                                                  (:capacities snapshot)))]
                     (map #(get overrides [(:resource_id %) date] (:daily_capacity %))
                          (person-resources snapshot #{uid})))) snapshots)))

(defn- shared-rows
  "按当前项目资源返回共享占用,不返回外部项目或任务标识."
  [snapshot snapshots]
  (let [resources (filter #(= "person" (:resource_type %)) (:resources snapshot))
        users (set (map :user_id resources))
        local (person-loads snapshot users)
        external (reduce #(merge-with + %1 (person-loads %2 users)) {} (rest snapshots))
        total (merge-with + local external)
        by-user (into {} (map (juxt :user_id identity) resources))]
    (keep (fn [[[uid date] hours]]
            (let [capacity (capacity-on snapshots uid date) resource (by-user uid)]
              (when (> hours capacity)
                {:resource_id (:resource_id resource) :resource_name (:name resource)
                 :date date :scope "shared_person" :planned_hours hours
                 :project_hours (get local [uid date] 0M)
                 :other_project_hours (get external [uid date] 0M)
                 :capacity_hours capacity :excess_hours (- hours capacity)}))) total)))

(defn overloads
  "综合本项目设备和所有未结束项目人员负荷,保护外部项目内容."
  [q project snapshot]
  (let [people (filter #(= "person" (:resource_type %)) (:resources snapshot))
        equipment-ids (set (map :resource_id (filter #(= "equipment" (:resource_type %)) (:resources snapshot))))
        equipment (filter #(contains? equipment-ids (:resource_id %)) (store/overloads snapshot))
        external (when (seq people)
                   (mapv #(store/snapshot q %) (q :planning/shared-person-projects {:project_id (:project_id project)})))
        rows (concat equipment (when (seq people) (shared-rows snapshot (into [snapshot] external))))]
    (vec (sort-by (juxt :date :resource_id) rows))))
