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

(def ^:private near-critical-ratio
  "近关键带宽占项目工作日跨度的比例: 总时差落在 0 与该带宽之间即视为近关键, 轻微滑移就会变成关键任务."
  0.1)

(def ^:private near-critical-floor
  "近关键带宽的最小工作日下限, 防止短项目带宽退化为 0."
  2)

(defn- float-band
  "按项目工作日跨度推导近关键带宽 (至少 near-critical-floor 个工作日), 跨度缺失时取下限."
  [span]
  (if (and (number? span) (pos? span))
    (int (max (long near-critical-floor) (Math/round ^double (* near-critical-ratio (double span)))))
    near-critical-floor))

(defn float-sensitivity
  "把 CPM 排程结果的总时差只读派生为关键路径敏感度概览 (H04):
   按非汇总叶子任务把总时差分档 —— 零浮动关键任务数, 近关键 (0 < 总时差 <= band) 任务数与按时差升序的清单 (携带 WBS 编号),
   以及宽松 (> band) 任务数; band 随项目工作日跨度按比例推导 (至少 2 个自然工作日).
   仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控, 键名不带尾随问号.
   schedule 为 schedule/schedule 的输出, raw-tasks 为含 :wbs_code 的原始任务行."
  [schedule raw-tasks]
  (let [wbs (into {} (for [t raw-tasks] [(:task_id t) (:wbs_code t)]))
        leaves (filterv #(not= "summary" (:task_type %)) (:tasks schedule))
        band (float-band (:working_days schedule))
        detail (fn [{:keys [task_id name total_float duration_days]}]
                 {:task_id task_id :wbs_code (wbs task_id) :name name
                  :total_float total_float :duration_days duration_days})
        critical (filterv #(zero? (:total_float %)) leaves)
        near (sort-by :total_float (filterv #(and (pos? (:total_float %)) (<= (:total_float %) band)) leaves))
        comfortable (filterv #(< band (:total_float %)) leaves)]
    {:available (boolean (seq leaves))
     :band band
     :span (:working_days schedule)
     :leaf-count (count leaves)
     :critical-count (count critical)
     :near-critical-count (count near)
     :comfortable-count (count comfortable)
     :near-critical-tasks (mapv detail near)
     :min-near-float (:total_float (first near))}))

(def ^:private tightness-thresholds
  "关键任务占比 (%) 的紧凑度分档上界: 达到即判为对应档位, 用于把排程整体松紧给一个定性健康度."
  [["very-tight" 50] ["tight" 25] ["moderate" 10]])

(defn- tightness-level
  "按关键任务占比给出定性紧凑度档位 (无叶任务时 nil)."
  [critical-pct]
  (some (fn [[level upper]] (when (>= critical-pct upper) level)) tightness-thresholds))

(defn float-tightness
  "把 CPM 排程结果的总时差只读派生为排程整体紧凑度概览 (H04), 与 float-sensitivity 正交互补:
   sensitivity 看关键链之外谁快变关键 (逐任务分档), 本项看整个计划有多紧 —— 关键任务占比, 平均/最小/最大总时差,
   以及按关键任务占比给的定性紧凑度档位 (very-tight>=50% / tight>=25% / moderate>=10% / 否则 loose).
   只统计非汇总叶子任务 (与 float-sensitivity / critical_path_staffing 分母口径一致), 总时差为整数工作日.
   仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控, 键名不带尾随问号.
   schedule 为 schedule/schedule 的输出."
  [schedule]
  (let [floats (mapv :total_float (filterv #(not= "summary" (:task_type %)) (:tasks schedule)))
        leaf-count (count floats)
        critical-count (count (filter zero? floats))
        critical-pct (if (pos? leaf-count)
                       (int (Math/round ^double (* 100.0 (/ (double critical-count) (double leaf-count)))))
                       0)
        avg-float (if (pos? leaf-count)
                    (/ (Math/round ^double (* 10.0 (double (/ (apply + floats) leaf-count)))) 10.0)
                    0)]
    {:available (pos? leaf-count)
     :leaf-count leaf-count
     :critical-count critical-count
     :critical-pct critical-pct
     :avg-float avg-float
     :min-float (when (pos? leaf-count) (apply min floats))
     :max-float (when (pos? leaf-count) (apply max floats))
     :span (:working_days schedule)
     :tightness-level (when (pos? leaf-count) (tightness-level critical-pct))}))
