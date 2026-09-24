(ns com.ruoyi.domain.pms.planning.schedule
  "工作日历,四类前置依赖与关键路径的纯函数排程."
  (:require [com.ruoyi.domain.pms.rules :as rules])
  (:import [java.time LocalDate]))

(def default-calendar
  "默认采用周一至周五,每天八小时."
  {:working_days [1 2 3 4 5] :holidays [] :extra_workdays [] :hours_per_day 8})

(defn working-day?
  "判断日期是否属于项目工作日,额外工作日优先于假日."
  [calendar date]
  (let [date (str date)]
    (or (contains? (set (:extra_workdays calendar)) date)
        (and (not (contains? (set (:holidays calendar)) date))
             (contains? (set (:working_days calendar))
                        (.getValue (.getDayOfWeek (LocalDate/parse date))))))))

(defn shift-working-days
  "把日期按项目日历移动 n 个工作日 (n 可为负), 结果落在工作日上; n 为 0 时返回当日或之后的首个工作日."
  [calendar date n]
  (let [step (if (neg? n) -1 1)]
    (loop [d (LocalDate/parse (str date)) remaining (Math/abs (long n)) guard 0]
      (when (> guard 40000) (rules/fail! 400 "日期移动超过支持范围"))
      (cond (and (zero? remaining) (working-day? calendar d)) (str d)
            (zero? remaining) (recur (.plusDays d 1) 0 (inc guard))
            :else (let [next (.plusDays d step)]
                    (recur next (if (working-day? calendar next) (dec remaining) remaining) (inc guard)))))))


(defn working-days-between
  "从 from 到 to 之间的工作日数 (to 在 from 之后为正, 之前为负, 相同为 0), 不含 from 含 to."
  [calendar from to]
  (let [a (LocalDate/parse (str from)) b (LocalDate/parse (str to))
        sign (if (.isBefore b a) -1 1)]
    (loop [d a n 0 guard 0]
      (when (> guard 40000) (rules/fail! 400 "日期跨度超过支持范围"))
      (if (= d b) (* sign n)
          (let [next (.plusDays d sign)]
            (recur next (if (working-day? calendar next) (inc n) n) (inc guard)))))))


(defn topological-order
  "按有向无环图顺序返回任务,遇到缺失任务或依赖环拒绝排程."
  [tasks dependencies]
  (let [ids (set (map :task_id tasks))]
    (doseq [{:keys [predecessor_id successor_id]} dependencies]
      (when-not (and (ids predecessor_id) (ids successor_id))
        (rules/fail! 400 "依赖任务不属于当前项目或不是可排程任务")))
    (loop [remaining ids result []]
      (if (empty? remaining) result
          (let [ready (sort (filter (fn [id]
                                     (not-any? #(and (= id (:successor_id %))
                                                    (remaining (:predecessor_id %))) dependencies))
                                   remaining))]
            (when (empty? ready) (rules/fail! 409 "任务依赖构成循环,无法排程"))
            (recur (apply disj remaining ready) (into result ready)))))))

(defn- dates
  "生成一百年以内的工作日索引,避免异常输入导致无界计算."
  [calendar start]
  (let [start (LocalDate/parse start)
        calendar (-> calendar (update :working_days set) (update :holidays set) (update :extra_workdays set))]
    (->> (range 36600) (map #(.plusDays start %))
         (filter #(working-day? calendar %)) (mapv str))))

(defn- date-index
  "把最早开始日期映射到当日或之后的首个工作日."
  [working date]
  (or (first (keep-indexed #(when (not (neg? (compare %2 date))) %1) working))
      (rules/fail! 400 "排程日期超过一百年支持范围")))

(defn- weight
  "把FS,SS,FF,SF转换为两个开始点之间的工作日距离."
  [by-id {:keys [predecessor_id successor_id dependency_type lag_days]}]
  (let [pd (:duration_days (by-id predecessor_id))
        sd (:duration_days (by-id successor_id))]
    (+ lag_days (case dependency_type "FS" pd "SS" 0 "FF" (- pd sd) "SF" (- sd)))))

(defn- earliest
  "向前计算每个任务的最早开始偏移."
  [order by-id dependencies working]
  (reduce (fn [result id]
            (assoc result id
                   (reduce max (date-index working (:start_date (by-id id)))
                           (map #(+ (result (:predecessor_id %)) (weight by-id %))
                                (filter #(= id (:successor_id %)) dependencies)))))
          {} order))

(defn- latest
  "向后计算不延误项目完工的最晚开始偏移."
  [order by-id dependencies finish]
  (reduce (fn [result id]
            (assoc result id
                   (reduce min (- finish (:duration_days (by-id id)))
                           (map #(- (result (:successor_id %)) (weight by-id %))
                                (filter #(= id (:predecessor_id %)) dependencies)))))
          {} (reverse order)))

(defn- scheduled-task
  "生成任务的工作日区间与总时差."
  [working task early late]
  (let [start (early (:task_id task)) duration (:duration_days task)
        end (+ start (max 0 (dec duration)))
        slack (- (late (:task_id task)) start)]
    (when (>= end (count working)) (rules/fail! 400 "任务排程超过支持范围"))
    {:task_id (:task_id task) :name (:name task) :task_type (:task_type task)
     :start_date (working start) :end_date (working end) :duration_days duration
     :total_float slack :critical (zero? slack)
     :working_dates (subvec working start (+ start duration))}))

(defn- summary-row
  "递归汇总WBS父任务的子任务日期范围."
  [tasks rows id working]
  (let [task (first (filter #(= id (:task_id %)) tasks))
        children (filter #(= id (:parent_id %)) tasks)
        values (map #(or (get rows (:task_id %))
                         (summary-row tasks rows (:task_id %) working)) children)]
    (if (empty? values)
      {:task_id id :name (:name task) :task_type "summary" :duration_days 0
       :start_date (:start_date task) :end_date (:start_date task)
       :total_float 0 :critical false :working_dates []}
      (let [start (first (sort (map :start_date values)))
            end (last (sort (map :end_date values)))
            slack (apply min (map :total_float values))]
        {:task_id id :name (:name task) :task_type "summary"
         :start_date start :end_date end
         :duration_days (inc (- (date-index working end) (date-index working start)))
         :total_float slack :critical (boolean (some :critical values)) :working_dates []}))))

(defn schedule
  "计算叶子任务CPM与父任务汇总,依赖使用工作日及半开工期区间."
  [project tasks dependencies calendar]
  (let [leaves (filterv #(not= "summary" (:task_type %)) tasks)]
    (if (empty? leaves)
      {:tasks [] :start_date nil :end_date nil :critical_path [] :working_days 0}
      (let [start (first (sort (remove nil? (cons (:start_date project) (map :start_date tasks)))))
            working (dates calendar start) by-id (into {} (map (juxt :task_id identity) leaves))
            order (topological-order leaves dependencies)
            early (earliest order by-id dependencies working)
            finish (apply max (map #(+ (early (:task_id %)) (:duration_days %)) leaves))
            late (latest order by-id dependencies finish)
            rows (mapv #(scheduled-task working (by-id %) early late) order)
            lookup (into {} (map (juxt :task_id identity) rows))
            summaries (map #(summary-row tasks lookup (:task_id %) working)
                           (filter #(= "summary" (:task_type %)) tasks))]
        {:tasks (vec (concat rows summaries)) :start_date (first (sort (map :start_date rows)))
         :end_date (last (sort (map :end_date rows))) :working_days finish
         :critical_path (mapv :task_id (filter :critical rows))}))))

(defn overallocations
  "累加资源每天的计划负荷,报告超过独立日容量的日期."
  [schedule resources allocations capacities]
  (let [tasks (into {} (map (juxt :task_id identity) (:tasks schedule)))
        overrides (into {} (map #(vector [(:resource_id %) (:date %)] (:capacity_hours %)) capacities))
        by-resource (into {} (map (juxt :resource_id identity) resources))
        loads (reduce (fn [acc {:keys [task_id resource_id hours_per_day]}]
                        (reduce #(update %1 [resource_id %2] (fnil + 0M) (bigdec hours_per_day))
                                acc (:working_dates (tasks task_id)))) {} allocations)]
    (->> loads
         (keep (fn [[[resource-id date] hours]]
                 (let [resource (by-resource resource-id)
                       capacity (get overrides [resource-id date] (:daily_capacity resource))]
                   (when (> hours capacity)
                     {:resource_id resource-id :resource_name (:name resource) :date date
                      :planned_hours hours :capacity_hours capacity :excess_hours (- hours capacity)}))))
         (sort-by (juxt :date :resource_id)) vec)))
