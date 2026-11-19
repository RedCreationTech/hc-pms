(ns com.ruoyi.domain.pms.report
  "G16 报表指标定义与导出: 把已发布 (published/frozen/locked) 的 report-metric 配置按对象类型与聚合
   在授权项目范围内求值, 输出与 CSV 导出共用的行集与列定义.
   指标定义不可变按版本演进, 已退役版本不参与求值; 缺配置时如实返回不可算, 不猜默认指标.
   只读派生: 不查写路径, 不落库不投递, 不构成任何门控."
  (:require
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.delivery.materials :as materials]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.governance.store :as g]
    [com.ruoyi.domain.pms.planning.progress :as progress]
    [com.ruoyi.domain.pms.portfolio :as portfolio]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)
    (java.time.temporal
      ChronoUnit)))


(def objects
  "报表可覆盖的对象类型及可读称谓."
  [{"value" "project" "label" "项目"}
   {"value" "cost-entry" "label" "费用明细"}
   {"value" "task" "label" "WBS任务"}])


(def aggregates
  "报表聚合及可读称谓."
  [{"value" "count" "label" "计数"}
   {"value" "sum" "label" "求和"}
   {"value" "avg" "label" "平均"}])


(def units
  "指标单位及可读称谓."
  [{"value" "count" "label" "个"} {"value" "money" "label" "金额"} {"value" "percent" "label" "百分比"}])


(def ^:private fields
  "字段白名单及可读称谓, 与 config/report-fields 保持一致."
  {"project" [{"value" "overall_percent" "label" "整体进度%"}
              {"value" "days_to_end" "label" "距完工天数"}
              {"value" "kit_percent" "label" "齐套率%"}
              {"value" "open_issues" "label" "未关闭问题数"}
              {"value" "open_risks" "label" "未关闭风险数"}]
   "cost-entry" [{"value" "amount_minor" "label" "明细金额"}]
   "task" [{"value" "percent_complete" "label" "完成进度%"}]})


(defn- current-versions
  "按编码取最新修订版本 (含草稿与退役), 供求值与未生效标注共用."
  [rows]
  (vals (reduce (fn [acc row]
                  (let [prior (get acc (:code row))]
                    (if (or (nil? prior) (< (:revision prior) (:revision row)))
                      (assoc acc (:code row) row)
                      acc)))
                {} rows)))


(defn definitions
  "读取报表指标定义: 仅生效版本参与求值, 已退役与草稿如实排除."
  [q]
  (let [current (current-versions (config/records q "report-metric"))
        active (filterv #(contains? #{"published" "frozen" "locked"} (:status %)) current)]
    {:metrics active
     :excluded (mapv (fn [row] {:code (:code row) :revision (:revision row) :status (:status row)})
                     (remove (set active) current))}))


(def ^:private project-gov-kinds
  "项目指标字段所依赖的治理记录种类, 与组合看板卡片同源."
  ["template-instance" "stage-weights" "issue" "risk"])


(def ^:private project-delivery-kinds
  "项目指标字段所依赖的交付记录种类, 与组合看板卡片同源."
  ["bom"])


(defn- by-project
  "按授权项目集合一次性批量读取并按 project_id 分组, 空集合不发查询; 记录需解码后再参与派生."
  [q query projects kinds]
  (if (seq projects)
    (group-by :project_id (map g/decode (q query {:project_ids (mapv :project_id projects) :kinds kinds})))
    {}))


(defn- delivery-by-project
  [q projects kinds]
  (if (seq projects)
    (group-by :project_id (map g/decode (q :pms/delivery-in-projects {:project_ids (mapv :project_id projects) :kinds kinds})))
    {}))


(defn- days-until
  [date]
  (when (seq date) (.between ChronoUnit/DAYS (LocalDate/now) (LocalDate/parse (subs date 0 10)))))


(defn- of-kind
  [rows kind]
  (filterv #(= kind (:kind %)) rows))


(defn- project-rows
  "项目指标行: 进度/齐套/问题/风险等字段在 pms_project 表上并不存在, 必须与组合看板同源派生,
    否则求和会静默得到 0. 未关联数据的项目如实为 nil, 由求值侧按缺失剔除而不是冒充 0."
  [q projects]
  (if (empty? projects)
    []
    (let [pids (mapv :project_id projects)
          by-project-id (fn [rows] (group-by :project_id rows))
          tasks-by (by-project-id (q :pms/tasks-in-projects {:project_ids pids}))
          nodes-by (by-project-id (q :pms/nodes-in-projects {:project_ids pids}))
          gov-by (by-project q :pms/gov-in-projects projects project-gov-kinds)
          del-by (delivery-by-project q projects project-delivery-kinds)]
      (mapv (fn [project]
              (let [pid (:project_id project)
                    tasks (vec (get tasks-by pid [])) nodes (vec (get nodes-by pid []))
                    gov (get gov-by pid []) del (get del-by pid [])
                    template-stages (:stages (first (of-kind gov "template-instance")))
                    override (into {} (map (juxt :code :weight) (:stages (first (of-kind gov "stage-weights")))))
                    stages (when template-stages
                             (mapv #(if (contains? override (:code %)) (assoc % :weight (override (:code %))) %) template-stages))
                    iss (of-kind gov "issue") risks (of-kind gov "risk")]
                {:project_id pid :status (:status project) :project_type (:project_type project)
                 :days_to_end (days-until (:end_date project))
                 :overall_percent (:overall_percent (progress/rollup tasks nodes stages))
                 :kit_percent (:kit_percent (materials/kitting-rollup (of-kind del "bom") tasks nodes))
                 :open_issues (count (remove #(= "closed" (:status %)) iss))
                 :open_risks (count (remove #(= "closed" (:status %)) risks))}))
            projects))))


(defn- rows-of
  "按对象类型取授权范围内的原始对象行; 项目行与其字段同组合看板同源派生, 任务与费用明细按项目 id 在该集合内过滤,
    不额外放行任何未授权项目."
  [q object projects]
  (case object
    "project" (project-rows q projects)
    "task" (q :pms/tasks-in-projects {:project_ids (mapv :project_id projects)})
    "cost-entry" (q :pms/cost-entries-in-projects {:project_ids (mapv :project_id projects)})
    []))


(defn- value-of
  "单条配置在行集上的取值: count 计行数; sum/avg 只累计字段存在的行 (nil 不冒充 0).
    平均值取整到两位小数, 与算术平均口径一致; 无可用行时返 nil, 不输出 0 冒充有值."
  [metric rows]
  (let [field (:field metric) aggregate (:aggregate metric)]
    (if (= "count" aggregate)
      (count rows)
      (let [values (keep #(get % (keyword field)) rows)]
        (case aggregate
          "sum" (reduce + 0 values)
          "avg" (when (seq values)
                  (/ (Math/round ^double (* 100.0 (/ (reduce + 0.0 values) (count values)))) 100.0)))))))


(defn- display
  "按单位把内部值转换为对外展示: 金额分转元, 其余保留两位小数的数值; 无可用行时 nil (界面与本 CSV 都显示为空)."
  [metric value]
  (cond (nil? value) nil
        (= "money" (:unit metric)) (money/money value)
        (instance? Double value) (Double/parseDouble (format "%.2f" (double value)))
        :else value))


(defn- scope-rows
  "按指标声明的对象状态过滤行; 未声明状态表示不限 (如实是全部对象), 不臆造默认过滤."
  [metric rows]
  (let [status (:filter_status metric)]
    (if status (filterv #(= status (:status %)) rows) rows)))


(defn- metric-row
  "单个指标的定义与实际值; 无数据时 available 为 false 且如实计数为 0."
  [metric total]
  (let [raw (value-of metric total)]
    {:code (:code metric) :label (:label metric) :object_type (:object_type metric) :aggregate (:aggregate metric)
     :field (:field metric) :filter_status (:filter_status metric) :unit (:unit metric) :revision (:revision metric)
     :available (some? raw)
     :row_count (if (= "count" (:aggregate metric)) (count total) (count (keep #(get % (keyword (:field metric))) total)))
     :raw raw :value (display metric raw)}))


(defn reports
  "G16 报表: 返回生效指标定义的求值与导出列定义, 供页面与 CSV 导出共用同一口径.
    导出列固定为 指标编码/指标名称/单位/对象/聚合/字段/状态过滤/行数/指标值 九列, 与导出范围一致.
    无财务权限时费用明细类指标如实列入 withheld, 不返回 0 冒充."
  [svc actor]
  (r/permit! actor ["pms:dashboard:query" "pms:project:list"])
  (let [q (:query-fn svc)
        {:keys [metrics excluded]} (definitions q)
        projects (portfolio/authorized-projects q actor)
        finance? (or (:admin? actor) (contains? (:permissions actor) "pms:finance:query"))
        usable (if finance? metrics (filterv #(not= "cost-entry" (:object_type %)) metrics))
        withheld (if finance? [] (filterv #(= "cost-entry" (:object_type %)) metrics))
        by-object (group-by :object_type usable)
        source (if (empty? projects) {} (into {} (map (fn [[object _items]] [object (rows-of q object projects)]) by-object)))
        rows (vec (sort-by (juxt :object_type :code)
                           (mapcat (fn [[object items]] (mapv #(metric-row % (scope-rows % (get source object []))) items)) by-object)))]
    {:rows rows
     :columns [{:key "code" :title "指标编码"} {:key "label" :title "指标名称"}
               {:key "unit" :title "单位"} {:key "object_type" :title "对象"}
               {:key "aggregate" :title "聚合"} {:key "field" :title "字段"} {:key "filter_status" :title "状态过滤"}
               {:key "row_count" :title "行数"} {:key "value" :title "指标值"}]
     :available (pos? (count rows))
     :metric_count (count rows)
     :by_object (mapv (fn [[object items]] {:object_type object :count (count items)}) by-object)
     :excluded excluded
     :finance_visible finance?
     :withheld (mapv #(select-keys % [:code :label :object_type]) withheld)
     :withheld_count (count withheld)
     :objects objects :aggregates aggregates :units units :fields fields
     :generated_at (str (java.time.Instant/now))}))


(defn csv-cells
  "把指标行投影为 CSV 单元 (顺序与 reports 的 columns 一致, 金额/数值已按单位格式化)."
  [row]
  [(:code row) (:label row) (:unit row) (:object_type row) (:aggregate row) (or (:field row) "") (or (:filter_status row) "全部")
   (:row_count row) (:value row)])
