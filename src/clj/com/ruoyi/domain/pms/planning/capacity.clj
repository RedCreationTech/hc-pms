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

(defn overload-summary
  "把逐日超负荷明细聚合为项目级只读概览(超配资源数/人日/设备日/最大单日超出/峰值负荷日),
  仅供台账汇总面板呈现, 不改变明细, 不构成任何门控."
  [rows]
  (let [n (count rows)
        resources (distinct (map :resource_id rows))
        person (count (filter #(= "shared_person" (:scope %)) rows))
        excess (map :excess_hours rows)
        worst (if (seq excess) (apply max excess) 0M)
        by-date (reduce (fn [m {:keys [date excess_hours]}]
                          (update m date (fnil + 0M) excess_hours))
                        {} rows)
        peak-date (when (seq by-date) (first (apply max-key val by-date)))]
    {:available (pos? n)
     :total-rows n
     :distinct-resources (count resources)
     :person-rows person
     :equipment-rows (- n person)
     :worst-excess-hours worst
     :peak-date peak-date}))

(defn allocation-coverage
  "把可分配叶任务 (task_type=task, 汇总与里程碑不接受工时分配) 与工时分配明细只读聚合为投入覆盖概览:
  逐任务判断是否至少有一条工时分配, 输出可分配总数/已投入/未投入/覆盖率与未投入任务清单;
  仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控, 键名不带尾随问号."
  [tasks allocations]
  (let [allocatable (filterv #(= "task" (:task_type %)) tasks)
        allocated (set (map :task_id allocations))
        total (count allocatable)
        with (count (filterv #(allocated (:task_id %)) allocatable))
        without (mapv #(select-keys % [:task_id :wbs_code :name])
                      (remove #(allocated (:task_id %)) allocatable))]
    {:available (pos? total)
     :total-tasks total
     :with-allocations with
     :without-allocations (- total with)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ with total))))
                     0)
     :unallocated-tasks without}))

(defn critical-path-staffing
  "把关键路径任务与工时分配明细只读聚合为关键路径投入缺口概览:
  仅统计关键路径上的可分配叶任务 (task_type=task, 汇总与里程碑不接受工时分配),
  逐任务判断是否至少有一条工时分配, 输出关键路径可分配总数/已排入/缺口/投入率与缺口任务清单;
  与 allocation-coverage 互补 (后者看全部任务是否排人, 本项优先聚焦关键路径上还没排人的最高进度风险任务);
  仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控, 键名不带尾随问号."
  [tasks allocations critical-path]
  (let [cp (set critical-path)
        allocatable (filterv #(and (cp (:task_id %)) (= "task" (:task_type %))) tasks)
        allocated (set (map :task_id allocations))
        total (count allocatable)
        staffed (count (filterv #(allocated (:task_id %)) allocatable))
        gaps (mapv #(select-keys % [:task_id :wbs_code :name])
                   (remove #(allocated (:task_id %)) allocatable))]
    {:available (pos? total)
     :critical-tasks total
     :staffed staffed
     :unstaffed (- total staffed)
     :staffing-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ staffed total))))
                     0)
     :unstaffed-tasks gaps}))

(def ^:private level-ratio
  "峰值-平均负荷比 (peak-to-avg) 低于该值即视为负荷均衡 (资源平滑良好, 无需再调配)."
  1.3)

(def ^:private moderate-ratio
  "峰值-平均负荷比达到该值即视为负荷尖峰 (存在明显忙闲不均, 需要资源平滑)."
  1.6)

(defn- rounded-dec
  "把一个数值保留一位小数 (走 double 避免 BigDecimal 非终止除法), 供负荷均值与峰值-平均比呈现."
  [x]
  (/ (Math/round ^double (* 10.0 (double x))) 10.0))

(defn resource-load-leveling
  "把本项目各任务工作日上的工时分配只读派生为资源投入均衡度 (负荷平滑) 概览: 与 overload-summary (谁超容量) /
   allocation-coverage (谁还没排) / critical-path-staffing (关键路径缺不缺人) 三项正交 —— 那三项看\"够不够 / 缺不缺\",
   本项看\"忙闲均不均\": 即便没有任何一天超容量, 若负荷在时间轴上忽高忽低 (有的工作日堆满、有的排定工作日却空转),
   仍是需要资源平滑的信号 (经典 resource leveling). 按日期升序把每条 allocation 的 hours_per_day 摊到其任务的每个
   working_date 上, 得到逐工作日总负荷曲线; 派生 scheduled-days (排定工作日全集) / active-days (有负荷的工作日) /
   idle-days (排定却零负荷的空转日) / total-hours / peak-hours / peak-date (峰值负荷最早出现日) / avg-hours (一位小数) /
   peak-to-avg (峰值/均值比, 一位小数, 负荷越平越接近 1) / cv-pct (变异系数 std/mean 百分比, 越大越颠簸) /
   leveling-level 定性档: 无任何排定工作日 -> nil (available=false); 总负荷为 0 -> unassigned (排了日历却没人投入);
   否则 peak-to-avg < level-ratio -> level, < moderate-ratio -> moderate, 否则 -> spiky.
   仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控, 键名不带尾随问号.
   sched-tasks 为含 :task_id 与 :working_dates 的排程任务行 (来自 (:schedule snapshot)), allocations 为含
   :task_id/:resource_id/:hours_per_day 的工时分配行."
  [sched-tasks allocations]
  (let [wd (into {} (map (juxt :task_id :working_dates)) sched-tasks)
        all-dates (into #{} (mapcat :working_dates) sched-tasks)
        sorted-dates (sort all-dates)
        n (count sorted-dates)
        load-by-date (reduce (fn [m {:keys [task_id hours_per_day]}]
                               (if (number? hours_per_day)
                                 (reduce #(update %1 %2 (fnil + 0M) (bigdec hours_per_day))
                                         m (get wd task_id []))
                                 m))
                             {} allocations)
        loads (mapv #(get load-by-date % 0M) sorted-dates)
        total-hours (reduce + 0M loads)
        active (count (filterv pos? loads))
        idle (- n active)
        peak (if (pos? n) (apply max loads) 0M)
        peak-date (when (pos? n) (first (filter #(= peak (get load-by-date % 0M)) sorted-dates)))
        mean-d (if (pos? n) (double (/ (double total-hours) (double n))) 0.0)
        peak-to-avg (if (and (pos? n) (pos? mean-d)) (rounded-dec (/ (double peak) mean-d)) 0)
        cv-pct (if (and (pos? n) (pos? mean-d))
                 (let [var (/ (reduce + 0.0 (map #(Math/pow (- (double %) mean-d) 2) loads)) (double n))]
                   (int (Math/round ^double (* 100.0 (/ (Math/sqrt var) mean-d)))))
                 0)]
    {:available (pos? n)
     :scheduled-days n
     :active-days active
     :idle-days idle
     :total-hours total-hours
     :peak-hours peak
     :peak-date peak-date
     :avg-hours (rounded-dec mean-d)
     :peak-to-avg peak-to-avg
     :cv-pct cv-pct
     :leveling-level (cond
                       (zero? n) nil
                       (zero? (compare total-hours 0M)) "unassigned"
                       (< peak-to-avg level-ratio) "level"
                       (< peak-to-avg moderate-ratio) "moderate"
                       :else "spiky")}))

(def ^:private low-utilization-pct
  "整体容量利用率低于该百分比即视为资源投入不足 (日历容量明显富余, 排入的资源大多只用了不到一半)."
  50)

(def ^:private high-utilization-pct
  "整体容量利用率达到该百分比即视为资源接近满负荷 (日历容量与投入基本贴平, 再插任务即超配)."
  90)

(defn- utilization-pct
  "committed/capacity 的百分比 (四舍五入整数), 容量为 0 或缺失时给 0, 走 double 避免 BigDecimal 非终止除法."
  [committed capacity]
  (if (pos? (compare capacity 0M))
    (int (Math/round ^double (* 100.0 (/ (double committed) (double capacity)))))
    0))

(defn capacity-utilization
  "把本项目每个资源在其被排入的工作日上的投入工时与其日历可用容量只读派生为容量利用率 (投入与容量匹配度) 概览:
   与 overload-summary (谁超容量) / allocation-coverage (谁还没排) / critical-path-staffing (关键路径缺不缺人) /
   resource-load-leveling (忙闲均不均) 四项正交 —— 那四项要么看单日上限要么看有没有排要么看时间分布,
   本项把视角翻到\"每个被排入的资源平均用了它日历容量多少\": 只统计至少有一条工时分配的资源,
   逐资源把每条 allocation 的 hours_per_day 摊到其任务的每个 working_date 上得到投入工时 (committed),
   再对该资源被排入的去重工作日集合逐日累加其有效日容量 (capacities 覆盖优先, 否则 daily_capacity) 得容量工时 (capacity),
   利用率 = committed/capacity. 派生 available (是否至少一个被排入的资源) / resource-count (被排入资源数) /
   total-committed-hours / total-capacity-hours (BigDecimal) / overall-pct (加权整体利用率, 四舍五入整数) /
   avg-pct (各资源利用率的算术均值, 供对比整体加权) / min-pct / max-pct (被排入资源利用率的最小/最大值) /
   underused (利用率 < low-utilization-pct 的轻载资源清单按利用率升序, 每项含 resource_id/name/active-days/
   committed-hours/capacity-hours/utilization-pct) / near-saturated (利用率 >= high-utilization-pct 的接近满负荷清单按利用率降序) /
   utilization-level 定性档 (按整体加权利用率给).
   与 resource-load-leveling 互补: 均衡度看同一批工时在时间轴上分得均不均, 利用率看相对日历容量整体填了多少,
   一个忽高忽低但总量偏低的计划在均衡度是 spiky 而在利用率是 underused (既颠簸又没用满).
   仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控 (利用率高低都不阻断任何保存/提交/冻结), 键名不带尾随问号.
   sched-tasks 为含 :task_id 与 :working_dates 的排程任务行 (来自 (:schedule snapshot)), resources 为含 :resource_id/:name/
   :daily_capacity 的资源行, capacities 为含 :resource_id/:date/:capacity_hours 的日容量覆盖行, allocations 为含
   :task_id/:resource_id/:hours_per_day 的工时分配行."
  [sched-tasks resources capacities allocations]
  (let [wd (into {} (map (juxt :task_id :working_dates)) sched-tasks)
        res (into {} (map (juxt :resource_id identity)) resources)
        overrides (into {} (map #(vector [(:resource_id %) (:date %)] (:capacity_hours %))) capacities)
        engaged (distinct (keep :resource_id allocations))
        rows (remove nil?
                     (map (fn [rid]
                            (when-let [r (res rid)]
                              (let [allocs (filterv #(= rid (:resource_id %)) allocations)
                                    days (into #{} (mapcat #(get wd (:task_id %) [])) allocs)
                                    committed (reduce (fn [s {:keys [task_id hours_per_day]}]
                                                        (if (number? hours_per_day)
                                                          (+ s (* (bigdec hours_per_day) (count (get wd task_id []))))
                                                          s))
                                                      0M allocs)
                                    capacity (reduce (fn [s d]
                                                       (let [cap (get overrides [rid d] (:daily_capacity r))]
                                                         (+ s (if (number? cap) (bigdec cap) 0M))))
                                                     0M (sort days))
                                    pct (utilization-pct committed capacity)]
                                {:resource_id rid
                                 :name (:name r)
                                 :active-days (count days)
                                 :committed-hours committed
                                 :capacity-hours capacity
                                 :utilization-pct pct})))
                          engaged))
        n (count rows)
        total-committed (reduce + 0M (map :committed-hours rows))
        total-capacity (reduce + 0M (map :capacity-hours rows))
        overall-pct (utilization-pct total-committed total-capacity)
        avg-pct (if (pos? n)
                  (int (Math/round ^double (/ (double (reduce + 0.0 (map :utilization-pct rows))) (double n))))
                  0)
        min-pct (when (pos? n) (apply min (map :utilization-pct rows)))
        max-pct (when (pos? n) (apply max (map :utilization-pct rows)))
        underused (->> (filterv #(< (:utilization-pct %) low-utilization-pct) rows)
                       (sort-by :utilization-pct)
                       vec)
        near-saturated (->> (filterv #(>= (:utilization-pct %) high-utilization-pct) rows)
                            (sort-by :utilization-pct >)
                            vec)]
    {:available (pos? n)
     :resource-count n
     :total-committed-hours total-committed
     :total-capacity-hours total-capacity
     :overall-pct overall-pct
     :avg-pct avg-pct
     :min-pct min-pct
     :max-pct max-pct
     :underused underused
     :near-saturated near-saturated
     :utilization-level (cond
                          (zero? n) nil
                          (< overall-pct low-utilization-pct) "underused"
                          (< overall-pct high-utilization-pct) "balanced"
                          :else "saturated")}))

(def ^:private concentrated-share-pct
  "单一资源类型投入占比达到该百分比即视为投入结构高度集中 (严重偏科, 项目工时几乎只压在某一类资源上)."
  80)

(def ^:private skewed-share-pct
  "单一资源类型投入占比达到该百分比即视为投入结构明显偏斜 (一类资源主导, 其余类型贡献偏低)."
  60)

(defn- share-pct
  "part/total 的百分比 (四舍五入整数), total 为 0 或缺失时给 0, 走 double 避免 BigDecimal 非终止除法."
  [part total]
  (if (pos? (compare total 0M))
    (int (Math/round ^double (* 100.0 (/ (double part) (double total)))))
    0))

(defn resource-type-mix
  "把本项目各资源按其 resource_type (person 人力 / equipment 设备) 分组的工时投入只读派生为投入结构
   (资源类别构成) 概览: 与 overload-summary (谁超容量) / allocation-coverage (谁还没排) / critical-path-staffing
   (关键路径缺不缺人) / resource-load-leveling (忙闲均不均) / capacity-utilization (相对日历容量填了多少) 五项正交 ——
   那五项要么逐日要么逐任务要么逐\"被排入\"资源, 本项把视角拉到资源类别层面: 项目的工时在人力与设备两类资源之间是怎么分布的,
   是否存在\"严重偏科\" (几乎只依赖一类) 与\"整类闲置\" (某类资源建了却一条工时都没排). 逐资源把每条 allocation 的
   hours_per_day 摊到其任务的每个 working_date 得该资源投入工时 (与 capacity-utilization 同口径), 再按 resource_type 汇总:
   resource-total (该类资源数) / engaged (该类有投入的资源数) / idle (=resource-total-engaged, 该类闲置资源数) /
   committed-hours (该类投入合计, BigDecimal) / share-pct (该类占全部投入的百分比). 派生 available (是否存在至少一个资源) /
   type-count (出现的资源类型数) / types (按投入工时降序的类型清单) / total-committed-hours / dominant-type (投入占比最高的
   类型名, 无资源时 nil) / dominant-share-pct (其占比) / structure-level 定性档 (无任何资源 -> nil; 排了资源却零投入 ->
   unassigned; 主导类型占比 >= concentrated-share-pct -> concentrated; >= skewed-share-pct -> skewed; 否则 -> balanced).
   仅供台账汇总面板呈现, 只读派生, 不落库不投递, 不构成门控 (投入结构如何都不阻断任何保存/提交/冻结), 键名不带尾随问号.
   sched-tasks 为含 :task_id 与 :working_dates 的排程任务行 (来自 (:schedule snapshot)), resources 为含 :resource_id/:name/
   :resource_type 的资源行, allocations 为含 :task_id/:resource_id/:hours_per_day 的工时分配行."
  [sched-tasks resources allocations]
  (let [wd (into {} (map (juxt :task_id :working_dates)) sched-tasks)
        committed-by-resource (reduce (fn [m {:keys [resource_id task_id hours_per_day]}]
                                        (if (number? hours_per_day)
                                          (update m resource_id (fnil + 0M)
                                                  (* (bigdec hours_per_day) (count (get wd task_id []))))
                                          m))
                                      {} allocations)
        total-committed (reduce + 0M (vals committed-by-resource))
        grouped (reduce (fn [m r]
                          (let [rt (or (:resource_type r) "unknown")
                                e (get m rt {:resource-total 0 :engaged 0 :committed-hours 0M})
                                committed (get committed-by-resource (:resource_id r) 0M)]
                            (assoc m rt
                                   (-> e
                                       (update :resource-total inc)
                                       (update :engaged (fn [x] (if (pos? (compare committed 0M)) (inc x) x)))
                                       (update :committed-hours + committed)))))
                        {} resources)
        types (->> (map (fn [[rt e]]
                          {:resource_type rt
                           :resource-total (:resource-total e)
                           :engaged (:engaged e)
                           :idle (- (:resource-total e) (:engaged e))
                           :committed-hours (:committed-hours e)
                           :share-pct (share-pct (:committed-hours e) total-committed)})
                        grouped)
                   (sort-by :committed-hours >)
                   vec)
        dominant (first types)
        structure-level (cond
                          (empty? resources) nil
                          (zero? (compare total-committed 0M)) "unassigned"
                          (>= (:share-pct dominant) concentrated-share-pct) "concentrated"
                          (>= (:share-pct dominant) skewed-share-pct) "skewed"
                          :else "balanced")]
    {:available (boolean (seq resources))
     :type-count (count types)
     :total-committed-hours total-committed
     :types types
     :dominant-type (:resource_type dominant)
     :dominant-share-pct (:share-pct dominant)
     :structure-level structure-level}))
