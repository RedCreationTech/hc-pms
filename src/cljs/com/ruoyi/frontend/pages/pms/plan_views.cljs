(ns com.ruoyi.frontend.pages.pms.plan-views
  "服务端 CPM 排程,资源负荷和基线差异的可视化."
  (:require [clojure.string :as str]
            [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]))

(defn- day-index
  "把服务端 ISO 日期转换为图表日轴."
  [date]
  (/ (js/Date.parse (str date "T00:00:00Z")) 86400000))

(defn- gantt-row
  "以服务端最早日期绘制任务条,红色明确关键任务."
  [task model origin span]
  (let [colors (shared/use-colors)
        offset (- (day-index (:start_date task)) origin)
        length (inc (- (day-index (:end_date task)) (day-index (:start_date task))))
        critical? (:critical task)]
    [:div {:style {:display "grid" :gridTemplateColumns "240px 1fr" :minHeight 46 :borderBottom (str "1px solid " (:border colors))}}
     [:div {:style {:padding "12px 8px" :fontSize 12}}
      (w/related-label (:tasks model) :task_id :name (:task_id task))
      (when critical? [:span {:style {:color "#e05b64" :marginLeft 8}} "关键"])]
     [:div {:style {:position "relative" :borderLeft (str "1px solid " (:border colors))
                    :backgroundImage "linear-gradient(to right, transparent 99%, #edf1f5 99%)"
                    :backgroundSize "10% 100%"}}
      [:div {:title (str (:start_date task) " → " (:end_date task) ",总时差 " (:total_float task) " 天")
             :style {:position "absolute" :top 13 :height 20 :borderRadius 4
                     :left (str (* 100 (/ offset span)) "%") :width (str (max 1 (* 100 (/ length span))) "%")
                     :background (if critical? "#e05b64" (:primary colors))}}]]]))

(defn gantt
  "只可视化服务端计算出的工作日历与关键路径结果."
  [model]
  (let [schedule (:schedule model) tasks (:tasks schedule)
        origin (day-index (:start_date schedule))
        span (max 1 (inc (- (day-index (:end_date schedule)) origin)))]
    [shared/panel "CPM 排程与关键路径" "日期由任务依赖和工作日历计算,悬停查看总时差" nil
     (if (seq tasks)
       [:div {:style {:overflowX "auto"}}
        [:div {:style {:minWidth 840}}
         [:div {:style {:display "flex" :justifyContent "space-between" :padding "0 0 16px 248px" :fontSize 12 :color "#718096"}}
          [:span (:start_date schedule)] [:span (str "跨度 " span " 个自然日")] [:span (:end_date schedule)]]
         (for [task tasks] ^{:key (:task_id task)} [gantt-row task model origin span])]]
       [shared/empty-state "添加任务并设置开始日期后生成排程" nil])]))

(defn overloads
  "逐日展示真实超负荷记录, 并在顶部附项目级只读汇总概览."
  [model]
  (let [rows (:overallocations model)
        s (:overload_summary model)]
    [shared/panel "资源负荷检查" "对照资源日历与任务分配识别超负荷日期; 顶部汇总为只读洞察 不构成门控" nil
     (if (seq rows)
       [:div
        [antd/space {:wrap true :style {:marginBottom 12}}
         [antd/tag {:color "volcano"} (str "超配资源 " (:distinct-resources s))]
         [antd/tag {:color "orange"} (str "人日超配 " (:person-rows s))]
         [antd/tag {:color "gold"} (str "设备日超配 " (:equipment-rows s))]
         [antd/tag {:color "red"} (str "最大单日超出 " (:worst-excess-hours s) " 工时")]
         (when (:peak-date s) [antd/tag {:color "magenta"} (str "峰值负荷日 " (:peak-date s))])]
        [w/record-table rows
         [{:title "资源" :dataIndex "resource_id" :render #(w/related-label (:resources model) :resource_id :name %)}
          (w/text-column :date "日期") (w/text-column :planned_hours "计划工时")
          (w/text-column :capacity_hours "容量") (w/text-column :excess_hours "超出工时")] nil]]
       [:div {:style {:padding 16 :color "#568775"}}
        (if (seq (:allocations model)) "当前排程未发现资源超负荷." "尚未分配资源,分配后将执行负荷检查.")])]))

(defn allocation-coverage
  "把可分配任务的实际工时投入覆盖情况以只读汇总面板呈现, 并列出尚未投入任何工时的任务."
  [model]
  (let [cov (:allocation_coverage model)]
    [shared/panel "任务投入覆盖度" "统计可分配任务 (不含汇总与里程碑) 是否已排入资源工时; 只读洞察, 不构成门控" nil
     (if (and cov (:available cov))
       [:div
        [antd/space {:wrap true :style {:marginBottom 12}}
         [antd/tag {:color "blue"} (str "可分配任务 " (:total-tasks cov))]
         [antd/tag {:color "green"} (str "已有投入 " (:with-allocations cov))]
         [antd/tag {:color (if (pos? (:without-allocations cov)) "orange" "green")} (str "未投入 " (:without-allocations cov))]
         [antd/tag {:color (if (>= (:coverage-pct cov) 80) "green" "gold")} (str "投入覆盖率 " (:coverage-pct cov) "%")]]
        (when (seq (:unallocated-tasks cov))
          [:div {:style {:display "flex" :alignItems "center" :gap 8 :flexWrap "wrap"}}
           [:span {:style {:fontSize 12 :color "#718096"}} "未投入工时的任务:"]
           (for [t (:unallocated-tasks cov)]
             ^{:key (:task_id t)} [antd/tag {:color "volcano"} (str (:wbs_code t) " " (:name t))])])]
       [shared/empty-state "尚无普通任务 (汇总与里程碑不计入投入覆盖)." nil])]))

(defn critical-path-staffing
  "把关键路径上尚未排入资源工时的任务以只读汇总面板呈现, 优先暴露最高进度风险的投入缺口."
  [model]
  (let [cp (:critical_path_staffing model)]
    [shared/panel "关键路径投入缺口" "统计关键路径上的可分配任务是否已排入资源工时, 未排入即为最高进度风险; 只读洞察, 不构成门控" nil
     (if (and cp (:available cp))
       [:div
        [antd/space {:wrap true :style {:marginBottom 12}}
         [antd/tag {:color "blue"} (str "关键路径任务 " (:critical-tasks cp))]
         [antd/tag {:color "green"} (str "已投入 " (:staffed cp))]
         [antd/tag {:color (if (pos? (:unstaffed cp)) "red" "green")} (str "投入缺口 " (:unstaffed cp))]
         [antd/tag {:color (if (>= (:staffing-pct cp) 80) "green" "gold")} (str "关键路径投入率 " (:staffing-pct cp) "%")]]
        (when (seq (:unstaffed-tasks cp))
          [:div {:style {:display "flex" :alignItems "center" :gap 8 :flexWrap "wrap"}}
           [:span {:style {:fontSize 12 :color "#718096"}} "关键路径上未排工时的任务:"]
           (for [t (:unstaffed-tasks cp)]
             ^{:key (:task_id t)} [antd/tag {:color "volcano"} (str (:wbs_code t) " " (:name t))])])]
       [shared/empty-state "关键路径上暂无可分配任务 (里程碑与汇总不计入)." nil])]))

(defn schedule-sensitivity
  "把 CPM 总时差只读派生为关键路径敏感度概览, 暴露零浮动关键任务之外轻微滑移即会上关键链的近关键任务; 只读洞察, 不构成门控."
  [model]
  (let [s (:schedule_sensitivity model)]
    [shared/panel "关键路径敏感度" (str "按总时差分档: 关键=0, 近关键 0<" (:band s) " 天(随跨度 " (:span s) " 自适应), 宽松>" (:band s) " 天; 只读洞察, 不构成门控") nil
     (if (and s (:available s))
       [:div
        [antd/space {:wrap true :style {:marginBottom 12}}
         [antd/tag {:color "red"} (str "关键任务 " (:critical-count s))]
         [antd/tag {:color (if (pos? (:near-critical-count s)) "orange" "green")} (str "近关键任务 " (:near-critical-count s))]
         [antd/tag {:color "green"} (str "宽松任务 " (:comfortable-count s))]
         [antd/tag {:color "blue"} (str "近关键带宽 " (:band s) " 天")]
         (when (:min-near-float s) [antd/tag {:color "gold"} (str "最小近关键时差 " (:min-near-float s) " 天")])]
        (when (seq (:near-critical-tasks s))
          [:div {:style {:display "flex" :alignItems "center" :gap 8 :flexWrap "wrap"}}
           [:span {:style {:fontSize 12 :color "#718096"}} "滑移即上关键链的近关键任务 (按时差升序):"]
           (for [t (:near-critical-tasks s)]
             ^{:key (:task_id t)} [antd/tag {:color "volcano"} (str (:wbs_code t) " " (:name t) " · 时差 " (:total_float t) " 天")])])]
       [shared/empty-state "尚未排程 (需任务设置工期与依赖后计算总时差)." nil])]))

(def readable-fields
  {:name "名称" :start_date "开始日期" :end_date "结束日期" :duration_days "工期"
   :wbs_code "WBS编号" :task_type "类型" :daily_capacity "日容量" :hours_per_day "日负荷"
   :dependency_type "依赖关系" :lag_days "间隔" :capacity_hours "容量" :date "日期"})

(defn- describe-record
  "以业务字段解释基线快照差异."
  [record]
  (if (map? record)
    (let [parts (keep (fn [[key label]] (when (contains? record key)
                                        (str label ": " (shared/display-value (get record key))))) readable-fields)]
      (if (seq parts) (str/join "; " parts) "日历或关联关系发生变更"))
    (shared/display-value record)))

(defn baseline-diff
  "对照冻结快照查看当前计划变化."
  [base baseline on-close]
  (let [resource (shared/use-resource (str base "/planning/baselines/" (:baseline_id baseline) "/diff") {} [])]
    [antd/modal {:title (str "基线差异 / 修订 " (:plan_revision baseline)) :open true :footer nil
                 :onCancel on-close :width 960 :destroyOnHidden true}
     [w/resource-view resource
      (fn [data]
        [:div
         [:p (str "基线修订 " (:baseline_revision data) " → 当前修订 " (:current_revision data))]
         (if (seq (:changes data))
           [w/record-table (:changes data)
            [{:title "对象" :dataIndex "entity_type" :render #(get {"task" "WBS任务" "schedule" "排程" "calendar" "工作日历"
                                                                 "dependency" "任务依赖" "resource" "资源" "allocation" "资源分配" "capacity" "资源容量"} % %)}
             {:title "变化" :dataIndex "change_type" :render #(get {"added" "新增" "removed" "删除" "modified" "修改"} % %)}
             {:title "基线内容" :dataIndex "before" :render #(describe-record (js->clj % :keywordize-keys true))}
             {:title "当前内容" :dataIndex "after" :render #(describe-record (js->clj % :keywordize-keys true))}] nil]
           [shared/empty-state "当前计划与该基线一致" nil])])]]))

(defn- percent-bar
  "带百分比文字的进度条."
  [percent color]
  [antd/progress {:percent (or percent 0) :size "small" :strokeColor color :style {:width 200}}])

(defn- metric
  [label value suffix color]
  [:div {:style {:minWidth 110 :padding "8px 12px" :border "1px solid #e4e8ee" :borderRadius 8}}
   [:div {:style {:fontSize 11 :color "#718096" :letterSpacing ".04em"}} label]
   [:div {:style {:fontSize 20 :fontWeight 650 :color (or color "#1b2530") :fontVariantNumeric "tabular-nums"}} (if (nil? value) "—" (str value suffix))]])


(def status-labels
  {"on_track" ["按计划" "green"] "behind" ["进度落后" "red"] "ahead" ["进度提前" "blue"] "no_baseline_yet" ["尚无应完成工作" "default"]
   "over" ["工时超支" "red"] "under" ["工时节约" "blue"] "no_actuals" ["尚无已批准工时" "default"]})


(defn earned-value-panel
  "挣值与完工预测 (H06/B02): 以计划工作日为价值单位, PV/EV/AC/SPI/CPI/EAC 与预测完工日, 只读派生."
  [model]
  (let [evm (:earned_value model)
        [sl sc] (get status-labels (:schedule_status evm) ["-" "default"])
        [cl cc] (get status-labels (:cost_status evm) ["-" "default"])]
    [shared/panel "挣值与完工预测" (str "状态日期 " (:status_date evm) " · 价值单位: 计划工作日 (PV 按排程应完成, EV = 工期 x 完成比例, AC = 已批准工时折算); 财务金额口径待费率 (增量7)")
     [antd/space {:wrap true} [antd/tag {:color sc} sl] [antd/tag {:color cc} cl]
      (when (:forecast_finish evm) [antd/tag {:color (if (and (:planned_finish evm) (pos? (compare (:forecast_finish evm) (:planned_finish evm)))) "red" "green")} (str "预测完工 " (:forecast_finish evm) " / 计划 " (:planned_finish evm))])]
     [:div {:style {:display "grid" :gap 12}}
      [:div {:style {:display "flex" :flexWrap "wrap" :gap 10}}
       [metric "BAC 计划总工作日" (:bac_days evm) "" nil] [metric "PV 应完成" (:pv_days evm) "" nil] [metric "EV 已完成" (:ev_days evm) "" nil]
       [metric "AC 已批准工时" (:ac_days evm) "" nil]
       [metric "SPI" (:spi evm) "" (case (:schedule_status evm) "behind" "#cf1322" "ahead" "#1d39c4" nil)]
       [metric "CPI" (:cpi evm) "" (case (:cost_status evm) "over" "#cf1322" "under" "#1d39c4" nil)]
       [metric "EAC 预测总工作日" (:eac_days evm) "" nil] [metric "ETC 剩余" (:etc_days evm) "" nil]
       [metric "挣值进度" (:percent_complete evm) "%" nil]]
      (when (seq (:stages evm))
        [antd/table {:rowKey "key" :size "small" :pagination false :dataSource (clj->js (:stages evm))
                     :columns (clj->js [{:title "阶段" :dataIndex "label" :width 100} {:title "BAC" :dataIndex "bac_days" :width 80}
                                        {:title "PV" :dataIndex "pv_days" :width 80} {:title "EV" :dataIndex "ev_days" :width 80} {:title "AC" :dataIndex "ac_days" :width 80}
                                        {:title "SPI" :dataIndex "spi" :width 80 :render (fn [v] (if (nil? v) "—" (str v)))}
                                        {:title "CPI" :dataIndex "cpi" :width 80 :render (fn [v] (if (nil? v) "—" (str v)))}
                                        {:title "任务数" :dataIndex "task_count" :width 80}])}])]]))


(def variance-kind-labels
  {"schedule" ["进度落后" "red"] "cost" ["工时超支" "red"]})


(def variance-state-labels
  {"unimplemented" ["尚未落实" "orange"] "in-progress" ["落实中" "gold"] "completed" ["已闭环" "green"]})


(defn variance-panel
  "绩效偏差与纠正措施闭环 (H06): 展示只读派生的进度/工时偏差与其纠正措施落实聚合, 可选登记入口复用会议行动闭环; 只读派生不门控."
  [model on-register]
  (let [rows (:performance_variances model)]
    [shared/panel "绩效偏差与纠正措施" "SPI 或 CPI 低于 0.9 自动识别为需纠正的项目偏差 (与上方挣值面板同源); 每个偏差按种类聚合已登记纠正措施的落实进度, 措施复用会议行动类型走完成与独立核验闭环" nil
     (if (empty? rows)
       [:span {:style {:color "#98a2b3"}} "暂无绩效偏差: 挣值进度与工时均在阈值内, 或尚无已批准基线与工时."]
       [w/record-table rows
        [{:title "偏差种类" :dataIndex "variance_kind" :width 120
          :render (fn [v] (let [[l c] (get variance-kind-labels v [v "default"])] (r/as-element [antd/tag {:color c} l])))}
         {:title "触发指标" :key "metric" :width 210
          :render (fn [_ row] (r/as-element [:span (str (aget row "metric") " = " (aget row "value") " (阈值 < " (aget row "threshold") ")")]))}
         {:title "状态日期" :dataIndex "status_date" :width 110}
         {:title "措施落实" :key "coverage" :width 220
          :render (fn [_ row]
                    (let [state (aget row "variance_action_state")
                          total (aget row "variance_action_total")
                          open (aget row "variance_action_open")
                          [l c] (get variance-state-labels state [state "default"])]
                      (r/as-element [antd/space {:wrap true}
                                     [antd/tag {:color c} l]
                                     (when (pos? total) [:span {:style {:color "#718096" :fontSize 12}}
                                                        (str total " 项 · 未闭环 " open)])])))}]
        on-register])]))


(defn variance-closure-panel
  "绩效偏差纠正措施闭环汇总 (H06): 跨全部挣值偏差只读聚合纠正措施整体闭环健康度, 与上方偏差明细同源; 闭环率 = 已闭环措施 / 措施总数, 复用会议行动闭环口径, 只读派生不构成门控."
  [model]
  (let [summary (:variance_closure model)
        pct (:action_closure_pct summary)]
    [shared/panel "偏差纠正措施闭环汇总" "把上方所有绩效偏差的纠正措施整体闭环情况聚合成一眼可读的健康度 (与偏差明细同源); 每个偏差按种类聚合已登记措施, 措施复用会议行动类型走完成与独立核验闭环" nil
     (if (or (nil? summary) (zero? (:variance_count summary)))
       [:span {:style {:color "#98a2b3"}} "暂无绩效偏差, 无需汇总纠正措施闭环情况."]
       [:div {:style {:display "flex" :flexWrap "wrap" :gap 10}}
        [metric "偏差数" (:variance_count summary) "" nil]
        [metric "已登记措施偏差" (:variance_with_action summary) " 项" nil]
        [metric "全部闭环偏差" (:variance_closed summary) " 项" nil]
        [metric "措施总数" (:action_total summary) " 项" nil]
        [metric "已闭环措施" (:action_closed summary) " 项" "#1d39c4"]
        [metric "未闭环措施" (:action_open summary) " 项" (when (pos? (:action_open summary)) "#cf1322")]
        [metric "措施闭环率" pct "%" (cond (>= pct 100) "#1d39c4" (zero? pct) "#cf1322" :else "#e6a23c")]
       ])]))


(defn conflicts-panel
  "主子约束冲突 (B03): 子项目/单机阶段任务排程完成日晚于主计划同阶段窗口."
  [model]
  (let [rows (:plan_conflicts model)]
    [shared/panel "主子约束冲突" "子项目/单机任务的排程完成日晚于主计划同阶段最晚完成日即为冲突; 主计划该阶段无任务时不判定" nil
     (if (empty? rows)
       [antd/tag {:color "green"} "无主子约束冲突"]
       [antd/table {:rowKey "task_id" :size "small" :pagination false :dataSource (clj->js rows)
                    :columns (clj->js [{:title "节点" :dataIndex "node_code" :width 160} {:title "阶段" :dataIndex "stage" :width 80}
                                       {:title "任务" :dataIndex "name"} {:title "WBS" :dataIndex "wbs_code" :width 140}
                                       {:title "排程完成" :dataIndex "end_date" :width 110} {:title "主计划窗口" :dataIndex "main_end_date" :width 110}
                                       {:title "晚于主计划" :dataIndex "days_late" :width 110 :render (fn [v] (r/as-element [antd/tag {:color "red"} (str v " 天")]))}])}])]))


(defn history-panel
  "进度快照趋势 (H06): 定时扫描或手动快照记录的 PV/EV/AC/SPI/CPI 与预测完工."
  [model]
  (let [rows (:progress_history model)]
    [shared/panel "进度趋势 (快照)" "每日 06:00 定时扫描或手动生成; 同日覆盖更新, 历史保留" nil
     (if (empty? rows)
       [:span {:style {:color "#98a2b3"}} "尚无快照: 项目进入执行后由定时扫描每日生成, 也可手动生成."]
       [antd/table {:rowKey "id" :size "small" :pagination false :dataSource (clj->js rows) :scroll {:x 900}
                    :columns (clj->js [{:title "日期" :dataIndex "snapshot_date" :width 110} {:title "总进度%" :dataIndex "overall_percent" :width 90}
                                       {:title "PV" :dataIndex "pv_days" :width 80} {:title "EV" :dataIndex "ev_days" :width 80} {:title "AC" :dataIndex "ac_days" :width 80}
                                       {:title "SPI" :dataIndex "spi" :width 80 :render (fn [v] (if (nil? v) "—" (str v)))}
                                       {:title "CPI" :dataIndex "cpi" :width 80 :render (fn [v] (if (nil? v) "—" (str v)))}
                                       {:title "EAC" :dataIndex "eac_days" :width 80} {:title "预测完工" :dataIndex "forecast_finish" :width 110}
                                       {:title "未关闭问题" :dataIndex "open_issues" :width 100} {:title "主子冲突" :dataIndex "conflict_count" :width 90}
                                       {:title "进度状态" :dataIndex "schedule_status" :width 120
                                        :render (fn [v] (let [[l c] (get status-labels v [v "default"])] (r/as-element [antd/tag {:color c} l])))}])}])]))


(defn reschedules-panel
  "节点重排记录 (H04): 保留原基线承诺, 记录每次重排的偏移与原因."
  [model]
  (let [rows (:reschedules model)]
    (when (seq rows)
      [shared/panel "节点重排记录" "重排只移动未开始任务, 已批准基线不变" nil
       [antd/table {:rowKey "id" :size "small" :pagination false :dataSource (clj->js rows)
                    :columns (clj->js [{:title "节点" :dataIndex "node_code" :width 160} {:title "原开始" :dataIndex "from_start" :width 110}
                                       {:title "新开始" :dataIndex "to_start" :width 110} {:title "工作日偏移" :dataIndex "delta_working_days" :width 100}
                                       {:title "任务数" :dataIndex "task_count" :width 80} {:title "原因" :dataIndex "reason"} {:title "时间" :dataIndex "created_at" :width 160}])}]])))


(defn progress-rollup
  "阶段权重进度卷积与主/子/单机结构进度 (B02/B03): 权重来自模板实例或项目级覆盖, 未实例化时等权; 读取时派生不落库.
   actions 为面板右上角的操作按钮, node-action 为节点行的操作渲染函数 (可选)."
  [model & [actions node-action]]
  (let [rollup (:progress_rollup model) colors (shared/use-colors)
        node-labels {"main" "主项目" "sub" "子项目" "machine" "单机"}]
    [:div {:style {:display "grid" :gap 20}}
     [shared/panel "阶段进度卷积" (case (:source rollup) "project_override" "权重来自项目级覆盖 (模板快照不变)" "template" "权重来自已实例化项目模板的阶段定义" "未实例化模板, 按已使用阶段等权卷积")
      [antd/space {:wrap true}
       [antd/tag {:color "blue"} (str "总体进度 " (:overall_percent rollup) "%")]
       [antd/tag (str "叶子任务 " (:leaf_count rollup))]
       (when (pos? (or (:unassigned_task_count rollup) 0))
         [antd/tag {:color "orange"} (str "未归属阶段任务 " (:unassigned_task_count rollup))])
       actions]
      (if (empty? (:stages rollup))
        [:span {:style {:color (:muted colors)}} "尚无阶段定义: 应用项目模板或为任务设置所属阶段后可见."]
        [antd/table {:rowKey "code" :size "small" :pagination false :dataSource (clj->js (:stages rollup))
                     :columns (clj->js [{:title "阶段" :dataIndex "code" :width 90}
                                        {:title "名称" :dataIndex "name"}
                                        {:title "权重%" :dataIndex "weight" :width 90}
                                        {:title "任务数" :dataIndex "task_count" :width 90}
                                        {:title "已完成" :dataIndex "done_count" :width 90}
                                        {:title "阶段进度" :dataIndex "percent" :width 240
                                         :render (fn [v] (r/as-element [percent-bar v (:primary colors)]))}])}])]
     [shared/panel "主/子/单机结构进度" "按任务所属结构节点 (含后代节点) 卷积, 子项目/单机分别可见" nil
      (if (empty? (:nodes rollup))
        [:span {:style {:color (:muted colors)}} "暂无结构节点."]
        [antd/table {:rowKey "node_id" :size "small" :pagination false :dataSource (clj->js (:nodes rollup))
                     :columns (clj->js (cond-> [{:title "层级" :dataIndex "node_type" :width 90 :render (fn [v] (get node-labels v v))}
                                                {:title "节点编号" :dataIndex "node_code" :width 160}
                                                {:title "名称" :dataIndex "name"}
                                                {:title "任务数" :dataIndex "task_count" :width 90}
                                                {:title "已完成" :dataIndex "done_count" :width 90}
                                                {:title "节点进度" :dataIndex "percent" :width 240
                                                 :render (fn [v] (r/as-element [percent-bar v (:primary colors)]))}]
                                         node-action (conj {:title "操作" :key "actions" :width 120
                                                            :render (fn [_ row] (r/as-element (node-action (js->clj row :keywordize-keys true))))})))}])]]))
