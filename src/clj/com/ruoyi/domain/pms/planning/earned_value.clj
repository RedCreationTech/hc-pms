(ns com.ruoyi.domain.pms.planning.earned-value
  "挣值与完工预测 (H06/B02): 以计划工作日为价值单位的本地挣值口径, 读取时派生不落库.
   PV = 状态日期前按排程应完成的工作日, EV = 计划工期 x 完成百分比, AC = 已批准工时折算的工作日 (分钟 / 60 / 每日工时);
   SPI = EV/PV, CPI = EV/AC, EAC = BAC/CPI, ETC = EAC - AC, 预测完工 = 状态日期 + 剩余价值/SPI 个工作日.
   这不是财务挣值 (金额口径在增量7 费率之后), 也不替代业务批准的进度口径."
  (:require
    [com.ruoyi.domain.pms.planning.progress :as progress]
    [com.ruoyi.domain.pms.planning.schedule :as schedule]))


(defn- round2
  [x]
  (when (some? x) (/ (Math/round (* 100.0 (double x))) 100.0)))


(defn- ratio
  [a b]
  (when (and (some? b) (pos? (double b))) (round2 (/ (double a) (double b)))))


(defn- planned-value
  "任务在状态日期前应完成的工作日数 (排程工作日中不晚于状态日期的个数)."
  [row status-date]
  (count (filter #(<= (compare % status-date) 0) (:working_dates row))))


(defn- actual-days
  "已批准工时按每日工时折算为工作日."
  [entries hours-per-day status-date]
  (let [minutes (reduce + 0 (map #(or (:minutes %) 0)
                                 (filter #(and (= "approved" (:status %)) (<= (compare (str (:work_date %)) status-date) 0)) entries)))]
    (/ (/ minutes 60.0) (double (or hours-per-day 8)))))


(defn- group-metrics
  [rows key label-fn]
  (->> rows
       (group-by key)
       (keep (fn [[k items]]
               (when (some? k)
                 (let [pv (reduce + 0.0 (map :pv items)) ev (reduce + 0.0 (map :ev items)) ac (reduce + 0.0 (map :ac items))
                       bac (reduce + 0 (map :bac items))]
                   {:key k :label (label-fn k) :bac_days bac :pv_days (round2 pv) :ev_days (round2 ev) :ac_days (round2 ac)
                    :spi (ratio ev pv) :cpi (ratio ev ac) :task_count (count items)}))))
       (sort-by :key) vec))


(defn earned-value
  "计算项目级与按阶段/节点分组的挣值指标. tasks 为全部任务, schedule 为 CPM 结果, entries 为工时单, status-date 为状态日期 (yyyy-MM-dd)."
  [tasks schedule calendar entries nodes status-date]
  (let [leaves (progress/leaf-rows tasks)
        rows (into {} (map (juxt :task_id identity) (:tasks schedule)))
        hours (or (:hours_per_day calendar) 8)
        by-task (group-by :task_id entries)
        node-code (into {} (map (juxt :node_id :node_code) nodes))
        items (mapv (fn [task]
                      (let [row (rows (:task_id task)) bac (max 0 (or (:duration_days task) 0))
                            pv (if row (min bac (planned-value row status-date)) 0)
                            ev (* bac (/ (or (:percent_complete task) 0) 100.0))
                            ac (actual-days (get by-task (:task_id task)) hours status-date)]
                        {:task_id (:task_id task) :wbs_code (:wbs_code task) :stage (:stage task) :node (:node task)
                         :bac bac :pv pv :ev ev :ac ac :end_date (:end_date row)}))
                    leaves)
        bac (reduce + 0 (map :bac items))
        pv (reduce + 0.0 (map :pv items)) ev (reduce + 0.0 (map :ev items)) ac (reduce + 0.0 (map :ac items))
        spi (ratio ev pv) cpi (ratio ev ac)
        eac (when (and cpi (pos? cpi)) (round2 (/ bac cpi)))
        etc (when eac (round2 (- eac ac)))
        remaining (- bac ev)
        forecast (when (and (pos? bac) spi (pos? spi))
                   (schedule/shift-working-days calendar status-date (int (Math/ceil (/ remaining spi)))))
        planned-finish (:end_date schedule)]
    {:status_date status-date :unit "working_days"
     :bac_days bac :pv_days (round2 pv) :ev_days (round2 ev) :ac_days (round2 ac)
     :sv_days (round2 (- ev pv)) :cv_days (round2 (- ev ac))
     :spi spi :cpi cpi :eac_days eac :etc_days etc
     :percent_complete (if (pos? bac) (int (Math/round (* 100.0 (/ ev bac)))) 0)
     :planned_finish planned-finish
     :forecast_finish (if (and (pos? bac) (>= ev bac)) planned-finish forecast)
     :schedule_status (cond (nil? spi) "no_baseline_yet" (< spi 0.9) "behind" (> spi 1.1) "ahead" :else "on_track")
     :cost_status (cond (nil? cpi) "no_actuals" (< cpi 0.9) "over" (> cpi 1.1) "under" :else "on_track")
     :stages (group-metrics items :stage identity)
     :nodes (group-metrics items :node #(get node-code % %))}))


(defn snapshot-payload
  "把挣值与卷积结果压缩成可持久化的日快照 (趋势用)."
  [date ev rollup counts]
  (merge {:code date :snapshot_date date :overall_percent (:overall_percent rollup)}
         (select-keys ev [:bac_days :pv_days :ev_days :ac_days :sv_days :cv_days :spi :cpi :eac_days :etc_days :forecast_finish :planned_finish :schedule_status :cost_status])
         counts))
