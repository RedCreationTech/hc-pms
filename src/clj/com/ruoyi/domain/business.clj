(ns com.ruoyi.domain.business
  "业务系统 Integrant 组件注册。"
  (:require [integrant.core :as ig]
            [clojure.string :as str]))

(defmethod ig/init-key :app.business/service
  [_ {:keys [query-fn]}]
  {:query-fn query-fn})

(defn- parse-int-default
  "安全解析整数，失败返回默认值。"
  [v default]
  (try (cond (int? v) v (string? v) (Integer/parseInt v) :else default)
       (catch Exception _ default)))

(defn init-chapter-statuses!
  "为方案初始化章节状态记录。接收 query-fn、方案ID 和章节列表。"
  [query-fn solution-id chapters]
  (doseq [[idx chapter] (map-indexed vector chapters)]
    (query-fn :create-solution-status!
              {:solution_id solution-id
               :chapter_name (:name chapter)
               :chapter_key (:key chapter)
               :sort_order idx
               :status -1})))

(defn have-generating?
  "检查当前用户是否有其他正在生成的方案。旧系统以 draft 状态表示已触发但尚未完成的生成任务。"
  ([query-fn user-name]
   (have-generating? query-fn user-name nil))
  ([query-fn user-name current-solution-id]
   (let [result (query-fn :count-generating-solutions {:create_by user-name
                                                       :current_id current-solution-id})]
     (pos? (:total result)))))

(defn- db-time->millis
  "将 SQLite/MySQL 返回的时间值转为毫秒，解析失败返回 0。"
  [value]
  (try
    (cond
      (instance? java.util.Date value) (.getTime ^java.util.Date value)
      (string? value) (.getTime (java.sql.Timestamp/valueOf value))
      :else 0)
    (catch Exception _ 0)))

(defn timeout-fail-expired!
  "将超时的生成中方案标记为失败，保留章节并把未完成章节置为失败。"
  [query-fn timeout-ms]
  (let [solutions (query-fn :get-generating-solutions {})
        now (System/currentTimeMillis)]
    (doseq [sol solutions]
      (let [last-status (query-fn :latest-active-solution-status {:solution_id (:id sol)})
            base-time (or (:start_time last-status) (:update_time last-status) (:create_time sol))
            elapsed (- now (db-time->millis base-time))]
        (when (> elapsed timeout-ms)
          (query-fn :mark-solution-failed! {:id (:id sol)})
          (query-fn :mark-unfinished-solution-statuses-failed!
                    {:solution_id (:id sol)
                     :error_msg "生成超时，已自动标记失败"}))))))

(def chapter-templates
  "方案类型章节模板定义。"
  {"墙面工程" [{:key "cover" :name "封面"}
               {:key "project-overview" :name "工程概况"}
               {:key "compilation-basis" :name "编制依据"}
               {:key "construction-arrangement" :name "施工安排"}
               {:key "construction-schedule" :name "施工进度计划"}
               {:key "construction-preparation" :name "施工准备与资源配置计划"}
               {:key "construction-method" :name "施工方法及工艺要求"}
               {:key "acceptance" :name "验收要求"}
               {:key "management-plan" :name "各项管理计划"}
               {:key "emergency" :name "应急预案"}]
   "地面工程" [{:key "cover" :name "封面"}
               {:key "project-overview" :name "工程概况"}
               {:key "compilation-basis" :name "编制依据"}
               {:key "construction-arrangement" :name "施工安排"}
               {:key "construction-schedule" :name "施工进度计划"}
               {:key "construction-preparation" :name "施工准备与资源配置计划"}
               {:key "construction-method" :name "施工方法及工艺要求"}
               {:key "acceptance" :name "验收要求"}
               {:key "management-plan" :name "各项管理计划"}
               {:key "emergency" :name "应急预案"}]
   "顶面工程" [{:key "cover" :name "封面"}
               {:key "project-overview" :name "工程概况"}
               {:key "compilation-basis" :name "编制依据"}
               {:key "construction-arrangement" :name "施工安排"}
               {:key "construction-schedule" :name "施工进度计划"}
               {:key "construction-preparation" :name "施工准备与资源配置计划"}
               {:key "construction-method" :name "施工方法及工艺要求"}
               {:key "acceptance" :name "验收要求"}
               {:key "management-plan" :name "各项管理计划"}
               {:key "emergency" :name "应急预案"}]
   "落地式脚手架" [{:key "cover" :name "封面"}
                    {:key "compilation-basis" :name "编制依据"}
                    {:key "project-overview" :name "工程概况"}
                    {:key "scaffolding-design" :name "脚手架设计"}
                    {:key "construction-schedule" :name "施工计划"}
                    {:key "construction-technique" :name "施工工艺技术"}
                    {:key "construction-measures" :name "施工保证措施"}
                    {:key "personnel" :name "施工管理及作业人员配备和分工"}
                    {:key "acceptance" :name "验收要求"}
                    {:key "emergency" :name "应急预案"}]
   "钢筋工程" [{:key "cover" :name "封面"}
                {:key "compilation-basis" :name "编制依据"}
                {:key "project-overview" :name "工程概况"}
                {:key "construction-arrangement" :name "施工安排"}
                {:key "construction-schedule" :name "施工进度计划"}
                {:key "construction-preparation" :name "施工准备与资源配置计划"}
                {:key "construction-method" :name "施工方法及工艺要求"}
                {:key "acceptance" :name "验收要求"}
                {:key "management-plan" :name "各项管理计划及保证措施"}
                {:key "emergency" :name "应急预案"}]
   "混凝土工程" [{:key "cover" :name "封面"}
                  {:key "compilation-basis" :name "编制依据"}
                  {:key "project-overview" :name "工程概况"}
                  {:key "construction-arrangement" :name "施工安排"}
                  {:key "construction-schedule" :name "施工进度计划"}
                  {:key "construction-preparation" :name "施工准备与资源配置计划"}
                  {:key "construction-method" :name "施工方法及工艺要求"}
                  {:key "acceptance" :name "验收要求"}
                  {:key "management-plan" :name "各项管理计划及保证措施"}
                  {:key "emergency" :name "应急预案"}]
   "雨季施工" [{:key "cover" :name "封面"}
                {:key "compilation-basis" :name "编制依据"}
                {:key "project-overview" :name "工程概况"}
                {:key "construction-arrangement" :name "施工安排"}
                {:key "construction-schedule" :name "施工进度计划"}
                {:key "construction-preparation" :name "施工准备与资源配置计划"}
                {:key "rainy-measures" :name "汛期施工措施"}
                {:key "management-plan" :name "各项管理计划及保证措施"}
                {:key "emergency" :name "应急预案"}]
   "预防高坠" [{:key "cover" :name "封面"}
                {:key "compilation-basis" :name "编制依据"}
                {:key "project-overview" :name "工程概况"}
                {:key "construction-arrangement" :name "施工安排"}
                {:key "construction-schedule" :name "施工进度计划"}
                {:key "construction-preparation" :name "施工准备与资源配置计划"}
                {:key "fall-prevention" :name "预防高处坠落安全措施及施工工艺要点"}
                {:key "acceptance" :name "验收要求"}
                {:key "management-measures" :name "预防高处坠落各项管理措施"}
                {:key "emergency" :name "应急预案"}]
   "吊篮工程" [{:key "cover" :name "封面"}
                {:key "project-overview" :name "工程概况"}
                {:key "compilation-basis" :name "编制依据"}
                {:key "construction-arrangement" :name "施工安排"}
                {:key "construction-schedule" :name "施工进度计划"}
                {:key "construction-preparation" :name "施工准备及资源配置计划"}
                {:key "construction-technique" :name "施工工艺技术"}
                {:key "acceptance" :name "验收要求"}
                {:key "safety-plan" :name "安全文明管理计划"}
                {:key "quality-plan" :name "质量管理计划"}
                {:key "schedule-plan" :name "进度管理计划"}
                {:key "protection-plan" :name "成品保护计划"}
                {:key "emergency" :name "应急预案"}]})

(def scheme-type-code->label
  "旧系统方案类型编码到中文名称的映射。"
  {"SCAFFOLDING" "落地式脚手架"
   "STEEL" "钢筋工程"
   "CONCRETE" "混凝土工程"
   "RAINY" "雨季施工"
   "HIGHFALL" "预防高坠"
   "WALL" "墙面工程"
   "CEILING" "顶面工程"
   "FLOOR" "地面工程"
   "CRADLE" "吊篮工程"})

(defn normalize-scheme-type
  "归一化方案类型，兼容旧系统编码和中文名称。"
  [scheme-type]
  (get scheme-type-code->label scheme-type scheme-type))

(defn get-chapters
  "根据方案类型返回章节列表。"
  [scheme-type]
  (let [label (normalize-scheme-type scheme-type)]
    (get chapter-templates label
         (get chapter-templates "墙面工程"))))

(def scheme-type-label->code
  "旧系统方案类型中文名称到编码的映射。"
  (into {} (map (fn [[code label]] [label code]) scheme-type-code->label)))

(def legacy-resource-table->type
  "旧系统资源表名到当前资源类型的映射。"
  {"generate_standard_specification" "standard"
   "generate_exemplary_case" "case"
   "generate_general_atlas" "atlas"
   "exp_chapter_vector" "vector-kb"
   "exp_emergency_rescue_procedure" "structured-kb"
   "exp_construction_key_difficulty" "structured-kb"
   "exp_emergency_disposal_measure" "structured-kb"
   "exp_quality_assurance_measure" "structured-kb"
   "exp_finished_product_protection" "structured-kb"
   "exp_risk_identification_classification" "structured-kb"
   "exp_safety_management_measure" "structured-kb"
   "exp_safety_management_policy" "structured-kb"
   "exp_emergency_response_procedure" "structured-kb"})

(def route-type->legacy
  "当前 route_type 到旧系统 route_type 的映射。"
  {"scheme_type" "1"
   "province" "2"
   "region" "2"})

(defn normalize-resource-type
  "归一化资源类型，兼容旧系统表名和当前资源类型。"
  [resource-type]
  (get legacy-resource-table->type resource-type resource-type))

(defn- equivalent-route-type
  "计算资源关联类型的旧系统等价值。"
  [route-type]
  (get route-type->legacy route-type))

(defn- equivalent-route-value
  "计算资源关联匹配的等价值，兼容方案类型编码和中文名称。"
  [route-type route-value]
  (when (contains? #{"scheme_type" "1"} route-type)
    (if-let [label (get scheme-type-code->label route-value)]
      label
      (get scheme-type-label->code route-value))))

(defn find-resources-by-route
  "根据方案类型或省份检索资源。"
  [query-fn resource-type route-type route-value]
  (query-fn :find-resources-by-route {:resource_type (normalize-resource-type resource-type)
                                      :route_type route-type
                                      :route_type_alt (equivalent-route-type route-type)
                                      :route_value route-value
                                      :route_value_alt (equivalent-route-value route-type route-value)}))

(defn generation-resources
  "按旧系统 Python 生成参数检索资源，优先方案类型，省份作为补充。"
  [query-fn {:keys [resource_table resourceTable scheme_type schemeType region]}]
  (let [resource-type (normalize-resource-type (or resource_table resourceTable "standard"))
        scheme-type (or scheme_type schemeType)
        by-scheme (if (str/blank? scheme-type)
                    []
                    (find-resources-by-route query-fn resource-type "scheme_type" scheme-type))
        by-region (if (str/blank? region)
                    []
                    (find-resources-by-route query-fn resource-type "region" region))]
    (vec (vals (into {}
                     (map (fn [row] [(:id row) row])
                          (concat by-scheme by-region)))))))

(defn search-knowledge
  "全文搜索知识库内容。"
  [query-fn keyword]
  (query-fn :list-resources {:resource_type "vector-kb"
                             :name keyword
                             :code nil :category nil :status nil
                             :limit 20 :offset 0}))
