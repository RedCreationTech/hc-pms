(ns com.ruoyi.domain.pms.config
  "平台级版本化业务配置: 项目模板, 编码/版本规则, 季度经营目标, 跨项目研发费用池与工时封期.
   记录不可变按版本演进 (draft -> published/frozen/locked -> retired), 修改模板不追溯覆盖已实例化项目."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.ruoyi.domain.pms.config.catalog :as catalog]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(def kinds
  "允许持久化的平台配置类型."
  #{"project-template" "coding-rule" "quarterly-target" "rd-pool" "period-lock" "approval-policy" "rate-table" "exchange-rate"
    "tax-rate" "report-metric"})


(def project-types
  "项目类别: 订单型三类与研发/部门事务型 (A11)."
  #{"equipment" "line" "service" "new_product" "new_technology" "special_rd" "dept_affairs"})


(def checkpoints
  "Gate 模板可阻断的交付检查点."
  #{"assembly.start" "test.SIT" "test.FAT" "test.SAT" "shipment.dispatch"})


(def gate-stages
  "Gate 模板适用阶段: execution/closure 参与项目状态迁移, 其余为专项过程阶段."
  #{"execution" "closure" "design" "manufacturing" "delivery" "site"})


(def write-permissions
  "各类型的维护权限."
  {"project-template" "pms:config:edit" "coding-rule" "pms:config:edit"
   "quarterly-target" "pms:target:edit" "rd-pool" "pms:finance:approve" "period-lock" "pms:finance:approve"
   "approval-policy" "pms:config:edit" "rate-table" "pms:finance:approve"
   "exchange-rate" "pms:finance:approve" "tax-rate" "pms:finance:approve"
   "report-metric" "pms:config:edit"})


(def read-permissions
  "读取平台配置的任一权限."
  ["pms:config:list" "pms:project:query" "pms:finance:query" "pms:dashboard:query"])


(defn decode
  "把配置记录展开为平铺只读对象."
  [row]
  (when row
    (merge (json/parse-string (:payload row) true) (dissoc row :payload) {:id (:config_id row)})))


(defn records
  "读取某类型全部版本."
  [q kind]
  (s/enum! kind kinds "kind")
  (mapv decode (q :config/list {:kind kind})))


(defn record!
  "读取指定类型的配置记录."
  [q kind id]
  (when-not (string? id) (r/fail! 400 "配置ID必须为字符串"))
  (let [row (decode (q :config/record {:config_id id}))]
    (when-not (and row (= kind (:kind row))) (r/fail! 404 "配置不存在或类型不匹配"))
    row))


(defn published
  "取每个编码当前生效 (published/frozen/locked) 的版本."
  [q kind]
  (filterv #(contains? #{"published" "frozen" "locked"} (:status %)) (records q kind)))


(defn published-by-code
  "按编码读取当前生效版本."
  [q kind code]
  (some #(when (= code (:code %)) %) (published q kind)))


;; ── 校验 ────────────────────────────────────────────────────────

(defn- strings!
  "校验有限长度的文本数组."
  [values label limit]
  (when-not (and (vector? values) (<= (count values) limit))
    (r/fail! 400 (str label "必须是最多" limit "项的数组")))
  (mapv #(r/text! % label 100 true) values))


(defn- stage-gates!
  "单阶段三门: 权重 0..100 整数, 派生层级非空 main/sub/machine 数组, 默认工期 1..3650 整数."
  [weight levels days]
  (when-not (and (integer? weight) (<= 0 weight 100)) (r/fail! 400 "阶段权重必须是0到100的整数"))
  (when (and (some? levels) (not (and (vector? levels) (seq levels) (every? #{"main" "sub" "machine"} levels))))
    (r/fail! 400 "阶段派生层级只能是 main/sub/machine"))
  (when (and (some? days) (not (and (integer? days) (<= 1 days 3650))))
    (r/fail! 400 "阶段默认工期必须是1到3650的整数")))


(defn- stage-row!
  "单阶段校验与组装: 字段白名单, 三门, 可选键按 cond-> 挂载 (缺省不带)."
  [stage]
  (r/object! stage [:code :name :weight :gate_type :levels :default_days])
  (let [weight (:weight stage) levels (:levels stage) days (:default_days stage)]
    (stage-gates! weight levels days)
    (cond-> {:code (s/text! stage :code 20) :name (s/text! stage :name 100) :weight weight}
      (seq (:gate_type stage)) (assoc :gate_type (:gate_type stage))
      (some? levels) (assoc :levels (vec (distinct levels)))
      (some? days) (assoc :default_days days))))


(defn- stages!
  "阶段编码唯一, 权重为 0..100 整数且合计 100, 供进度卷积使用."
  [stages]
  (when-not (and (vector? stages) (<= 1 (count stages) 20)) (r/fail! 400 "模板需要1到20个阶段"))
  (let [rows (mapv stage-row! stages)]
    (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "阶段编码重复"))
    (when-not (= 100 (reduce + (map :weight rows))) (r/fail! 400 "阶段权重合计必须等于100"))
    rows))


(defn- structure!
  "结构模板: 子项目/单元与其下单机, 编号后缀唯一."
  [structure]
  (when-not (and (vector? structure) (<= (count structure) 50)) (r/fail! 400 "结构模板最多50个子项目"))
  (let [rows (mapv (fn [node]
                     (r/object! node [:node_type :suffix :name :machines])
                     (let [machines (or (:machines node) [])]
                       (when-not (and (vector? machines) (<= (count machines) 50)) (r/fail! 400 "单机模板最多50台"))
                       {:node_type (s/enum! (or (:node_type node) "sub") #{"sub"} "node_type")
                        :suffix (s/text! node :suffix 20) :name (s/text! node :name 200)
                        :machines (mapv (fn [m]
                                          (r/object! m [:suffix :name])
                                          {:suffix (s/text! m :suffix 20) :name (s/text! m :name 200)}) machines)}))
                   structure)
        suffixes (concat (map :suffix rows) (mapcat #(map :suffix (:machines %)) rows))]
    (when-not (= (count suffixes) (count (set suffixes))) (r/fail! 400 "结构编号后缀重复"))
    rows))


(defn- gate-checks!
  "检查项列表门: 1 到 30 项, 每项白名单与必填文本, 编码唯一; 返回归一后的检查项行."
  [gate]
  (let [checks (:checks gate)]
    (when-not (and (vector? checks) (<= 1 (count checks) 30)) (r/fail! 400 "关口模板需要1到30个检查项"))
    (let [rows (mapv (fn [c]
                       (r/object! c [:code :title :required :require_released])
                       (cond-> {:code (s/text! c :code 50) :title (s/text! c :title 200)
                                :required (s/boolean! (:required c) "required")}
                         (contains? c :require_released) (assoc :require_released (s/boolean! (:require_released c) "require_released"))))
                     checks)]
      (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "关口检查项编码重复"))
      rows)))


(defn gate-template!
  "校验一条 Gate 模板定义 (供平台模板与项目内从目录建立共用)."
  [gate]
  (r/object! gate [:code :title :gate_type :stage :required :blocks :checks])
  (let [rows (gate-checks! gate)
        blocks (or (:blocks gate) [])]
    (when-not (and (vector? blocks) (every? checkpoints blocks) (= (count blocks) (count (set blocks))))
      (r/fail! 400 "阻断检查点取值不合法"))
    (when (and (:required gate) (not-any? :required rows)) (r/fail! 400 "必需关口模板至少应有一个必需检查项"))
    {:code (s/text! gate :code 100) :title (s/text! gate :title 200)
     :gate_type (s/enum! (or (:gate_type gate) "generic") (set (map :gate_type catalog/gate-types)) "gate_type")
     :stage (s/enum! (:stage gate) gate-stages "stage")
     :required (s/boolean! (:required gate) "required") :blocks (vec blocks) :checks rows}))


(defn- gate-templates!
  [gates]
  (when-not (and (vector? gates) (<= (count gates) 30)) (r/fail! 400 "模板最多30个Gate"))
  (let [rows (mapv gate-template! gates)]
    (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "Gate模板编码重复"))
    rows))


(defn- delivery!
  "适用交付环节与试验类型, 与交付配置同一约束."
  [delivery]
  (r/object! delivery [:required_stages :required_test_types])
  (let [stages (:required_stages delivery) tests (:required_test_types delivery) selected (set stages)]
    (doseq [[key allowed values] [[:required_stages #{"materials" "assembly" "quality" "shipment"} stages]
                                  [:required_test_types #{"SIT" "FAT" "SAT"} tests]]]
      (when-not (and (vector? values) (seq values) (= (count values) (count (set values))))
        (r/fail! 400 "适用流程与试验类型必须为非空不重复数组"))
      (doseq [value values] (s/enum! value allowed (name key))))
    (doseq [[stage predecessors] [["assembly" #{"materials"}] ["quality" #{"materials" "assembly"}]
                                  ["shipment" #{"materials" "assembly" "quality"}]]]
      (when (and (selected stage) (not-every? selected predecessors))
        (r/fail! 400 "适用环节必须包含物料,装配,质量的必要前置")))
    {:required_stages stages :required_test_types tests}))


(defn- closure-items!
  [items]
  (when-not (and (vector? items) (<= (count items) 30)) (r/fail! 400 "收尾清单最多30项"))
  (mapv (fn [item]
          (r/object! item [:kind :title :required])
          {:kind (s/enum! (or (:kind item) "check") #{"check" "handoff"} "kind")
           :title (s/text! item :title 200) :required (if (false? (:required item)) false true)}) items))


(defn- template!
  [body]
  (r/object! body [:code :title :description :project_types :stages :structure :gate_templates :team_roles
                   :document_categories :delivery :closure_items :reason])
  (let [types (:project_types body)]
    (when-not (and (vector? types) (seq types) (every? project-types types)) (r/fail! 400 "适用项目类别不合法"))
    {:code (s/text! body :code 100) :title (s/text! body :title 200)
     :description (s/optional-text! body :description 2000) :project_types (vec (distinct types))
     :stages (stages! (:stages body)) :structure (structure! (or (:structure body) []))
     :gate_templates (gate-templates! (or (:gate_templates body) []))
     :team_roles (strings! (or (:team_roles body) []) "团队角色" 20)
     :document_categories (strings! (or (:document_categories body) []) "文档类别" 20)
     :delivery (delivery! (or (:delivery body) {:required_stages ["materials" "assembly" "quality" "shipment"] :required_test_types ["SIT" "FAT" "SAT"]}))
     :closure_items (closure-items! (or (:closure_items body) []))}))


(def placeholder
  "编码规则占位符."
  #"\{(YYYY|YY|MM|TYPE|PROJECT|SEQ:[1-9])\}")


(defn- coding-rule!
  [body]
  (r/object! body [:code :object_type :pattern :enforced :description :version_rule :collection_rule :reason])
  (let [pattern (s/text! body :pattern 100)]
    (when-not (re-find #"SEQ:[1-9]" pattern) (r/fail! 400 "编码规则必须包含 {SEQ:n} 流水占位符"))
    (when (re-find #"[{}]" (str/replace pattern placeholder "")) (r/fail! 400 "编码规则含有未知占位符"))
    {:code (s/text! body :code 100)
     :object_type (s/enum! (:object_type body) #{"project" "sub" "machine" "document" "requirement" "task"} "object_type")
     :pattern pattern :enforced (boolean (:enforced body))
     :description (s/optional-text! body :description 500) :version_rule (s/optional-text! body :version_rule 500)
     :collection_rule (s/optional-text! body :collection_rule 500)}))


(def target-metrics
  "季度经营目标指标: 收入/毛利为金额, 结项数为整数, 准时率为百分比."
  #{"revenue" "gross_margin" "closed_projects" "on_time_rate"})


(defn- target-value!
  "按指标校验并归一目标值: 结项数为 0..100000 整数, 准时率为 0..100 数值, 金额类转分且不得为负."
  [metric body]
  (case metric
    "closed_projects" (let [v (:target_value body)]
                        (when-not (and (integer? v) (<= 0 v 100000)) (r/fail! 400 "结项数目标必须是非负整数"))
                        v)
    "on_time_rate" (let [v (:target_value body)]
                     (when-not (and (number? v) (<= 0 v 100)) (r/fail! 400 "准时率目标必须是0到100"))
                     (double v))
    (let [amount (money/amount! (:target_value body) "目标金额")]
      (when (neg? amount) (r/fail! 400 "目标金额不得为负数"))
      (money/money amount))))


(defn- target-currency!
  "金额类指标带币种 (缺省 CNY 且受控枚举), 其余指标不带币种."
  [metric body]
  (when (contains? #{"revenue" "gross_margin"} metric)
    (let [c (or (:currency body) "CNY")]
      (when-not (money/currencies c) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD"))
      c)))


(defn- quarterly-target!
  [body]
  (r/object! body [:year :quarter :metric :target_value :currency :basis :reason])
  (let [year (:year body) quarter (:quarter body)
        metric (s/enum! (:metric body) target-metrics "metric")]
    (when-not (and (integer? year) (<= 2000 year 2100)) (r/fail! 400 "年份必须是2000到2100的整数"))
    (when-not (and (integer? quarter) (<= 1 quarter 4)) (r/fail! 400 "季度必须是1到4"))
    (let [value (target-value! metric body)
          currency (target-currency! metric body)]
      (cond-> {:code (str year "Q" quarter "-" metric) :year year :quarter quarter :metric metric
               :target_value value :basis (s/optional-text! body :basis 1000)}
        currency (assoc :currency currency)))))


(defn- period!
  [value]
  (when-not (and (string? value) (re-matches #"[0-9]{4}-(0[1-9]|1[0-2])" value)) (r/fail! 400 "期间格式必须为 YYYY-MM"))
  value)


(defn- rd-pool!
  [body]
  (r/object! body [:period :amount :currency :description :reason])
  (let [amount (money/amount! (:amount body) "费用池金额")
        currency (or (:currency body) "CNY")]
    (when-not (pos? amount) (r/fail! 400 "费用池金额必须大于0"))
    (when-not (money/currencies currency) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD"))
    {:code (str "POOL-" (period! (:period body))) :period (:period body) :amount (money/money amount)
     :amount_minor amount :currency currency :description (s/optional-text! body :description 1000)}))


(defn- period-lock!
  [body]
  (r/object! body [:period :reason])
  {:code (period! (:period body)) :period (:period body) :reason (s/text! body :reason 500)})


(def ^:private member-roles
  "费率可按项目成员角色取值 (与 pms_member.role 一致)."
  #{"manager" "editor" "viewer"})


(defn- rate-entry!
  "校验一条有效期费率: 角色或人员范围, 每小时金额为正, 起止日期有序."
  [row]
  (r/object! row [:code :scope :role :user_id :amount_per_hour :effective_from :effective_to])
  (let [scope (s/enum! (:scope row) #{"role" "person"} "scope")
        amount (money/amount! (:amount_per_hour row) "每小时费率")
        from (r/date! (:effective_from row) "生效起日")
        to (r/date! (:effective_to row) "生效止日")]
    (when-not (pos? amount) (r/fail! 400 "每小时费率必须大于0"))
    (when-not from (r/fail! 400 "生效起日必填"))
    (when (and to (pos? (compare from to))) (r/fail! 400 "生效止日不能早于生效起日"))
    (when (= "role" scope) (s/enum! (:role row) member-roles "role"))
    (when (= "person" scope) (r/positive-id! (:user_id row) "人员"))
    {:code (s/text! row :code 50) :scope scope
     :role (when (= "role" scope) (:role row))
     :user_id (when (= "person" scope) (r/positive-id! (:user_id row) "人员"))
     :amount_per_hour (money/money amount) :amount_per_hour_minor amount
     :effective_from from :effective_to to}))


(defn- exchange-rate-entry!
  "校验一条有效期汇率: 币种对必须不同且都在支持范围, 汇率是正的小数精度字符串 (不经浮点),
   起止日期有序. 精确小数由 finance_forecast 以 BigDecimal 解释, 这里只校验形状与小数位."
  [row]
  (r/object! row [:from_currency :to_currency :rate :effective_from :effective_to])
  (let [from (s/text! row :from_currency 3)
        to (s/text! row :to_currency 3)
        rate (s/text! row :rate 30)
        effective-from (r/date! (:effective_from row) "生效起日")
        effective-to (r/date! (:effective_to row) "生效止日")]
    (when-not (money/currencies from) (r/fail! 400 "来源币种不在支持范围"))
    (when-not (money/currencies to) (r/fail! 400 "目标币种不在支持范围"))
    (when (= from to) (r/fail! 400 "来源与目标币种不能相同"))
    (when-not (re-matches #"[0-9]{1,9}(\.[0-9]{1,8})?" rate) (r/fail! 400 "汇率必须是最多8位小数的正数"))
    (when-not (pos? (compare (BigDecimal. rate) BigDecimal/ZERO)) (r/fail! 400 "汇率必须大于0"))
    (when-not effective-from (r/fail! 400 "生效起日必填"))
    (when (and effective-to (pos? (compare effective-from effective-to)))
      (r/fail! 400 "生效止日不能早于生效起日"))
    ;; 编码由币种对派生, 调用方无需填写 (与费率表 RATE-<币种> 同为后端生成).
    {:code (str from "-" to) :from_currency from :to_currency to :rate rate
     :effective_from effective-from :effective_to effective-to}))


(defn- exchange-rate!
  "有效期汇率表: 同一币种对的生效区间互不重叠 (重叠会导致同一日期有两个汇率, 折算结果不确定)."
  [body]
  (r/object! body [:base_currency :rates :reason])
  (let [currency (or (:base_currency body) "CNY")]
    (when-not (money/currencies currency) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD"))
    (when-not (and (vector? (:rates body)) (<= 1 (count (:rates body)) 200))
      (r/fail! 400 "汇率行需要1到200条"))
    (let [rates (mapv exchange-rate-entry! (:rates body))]
      (doseq [[_ rows] (group-by (juxt :from_currency :to_currency) rates)]
        (doseq [[a b] (partition 2 1 (sort-by :effective_from rows))]
          (when-not (pos? (compare (:effective_from b) (or (:effective_to a) "9999-12-31")))
            (r/fail! 400 "同一币种对的汇率有效期重叠"))))
      {:code (str "FX-" currency) :base_currency currency :rates rates})))


(defn- rate-table!
  "有效期费率表: 同一对象 (角色或人员) 的有效期互不重叠, 允许留白区间 (该区间未覆盖)."
  [body]
  (r/object! body [:base_currency :rates :reason])
  (let [currency (or (:base_currency body) "CNY")]
    (when-not (money/currencies currency) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD"))
    (when-not (and (vector? (:rates body)) (<= 1 (count (:rates body)) 200))
      (r/fail! 400 "费率行需要1到200条"))
    (let [rates (mapv rate-entry! (:rates body))]
      (doseq [[_ rows] (group-by (juxt :scope :role :user_id) rates)]
        (doseq [[a b] (partition 2 1 (sort-by :effective_from rows))]
          (when-not (pos? (compare (:effective_from b) (or (:effective_to a) "9999-12-31")))
            (r/fail! 400 "同一对象的费率有效期重叠"))))
      {:code (str "RATE-" currency) :base_currency currency :rates rates})))


(defn- tax-rate!
  "F08 税率口径: 按本位币种维护增值税税率与净利率, 均为 0-1 之间的比例字符串 (最多 6 位小数),
   由 finance 以 BigDecimal 解释, 不经浮点. 净利率 <= 1 - 税率, 保证税后净利非负.
   缺这张表时看板只输出税前毛利并如实标注净利不可算, 不猜默认税率."
  [body]
  (r/object! body [:base_currency :vat_rate :net_rate :reason])
  (let [currency (or (:base_currency body) "CNY")
        shape #"[0-9]{1,2}(\.[0-9]{1,6})?"
        rate! (fn [k label]
                (let [v (s/text! body k 20)
                      minor (money/amount! v label)]
                  (when-not (re-matches shape v) (r/fail! 400 (str label "必须是最多6位小数的比例 (0到1)")))
                  (when-not (<= 0 minor 100) (r/fail! 400 (str label "必须在0到1之间")))
                  (money/money minor)))]
    (when-not (money/currencies currency) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD"))
    (let [vat (rate! :vat_rate "增值税税率")
          net (rate! :net_rate "净利率")]
      (when (pos? (compare (BigDecimal. vat) (BigDecimal. "1"))) (r/fail! 400 "增值税税率必须在0到1之间"))
      (when (pos? (compare (BigDecimal. net) (.subtract (BigDecimal. "1") (BigDecimal. vat))))
        (r/fail! 400 "净利率不能超过 1 - 增值税税率"))
      ;; 编码由本位币种派生, 与费率表/汇率表同为后端生成.
      {:code (str "TAX-" currency) :base_currency currency :vat_rate vat :net_rate net})))


(def report-objects
  "G16 报表指标可覆盖的对象类型: 每类对象只能使用与该类型语意相符的聚合."
  #{"project" "cost-entry" "task"})


(def report-aggregates
  "G16 报表指标聚合: 计数/求和 (按字段取值)/算术平均 (按字段取分或整数)."
  #{"count" "sum" "avg"})


(def report-statuses
  "G16 指标可附加的对象状态过滤: 费用明细按所属版本状态, 项目按项目状态; 未指定表示不限状态 (count 口径同此)."
  #{"approved" "submitted" "draft" "rejected" "cancelled" "closed"})


(def report-fields
  "G16 指标取值字段白名单; 字段不存在于对象行时该行不计入 (reconciled 如实标注), 不用 0 冒充分母."
  {"project" #{:overall_percent :days_to_end :kit_percent :open_issues :open_risks}
   "cost-entry" #{:amount_minor}
   "task" #{:percent_complete}})


(defn- report-field!
  "校验指标字段: count 不允许字段, 其余必须落在对象的字段白名单内."
  [object aggregate field]
  (if (= "count" aggregate)
    (when (some? field) (r/fail! 400 "计数指标不能指定字段"))
    (let [f (s/enum! field (set (map name (report-fields object))) "指标字段")]
      (keyword f))))


(defn- report-metric!
  "G16 报表指标定义: 对象 + 聚合 + 字段的组合决定口径, 编码由三者派生 (PROJ-COUNT / ENTRY-SUM-AMOUNT_MINOR),
    调用方无需填写; 单位只允许受控取值, 保证导出的表头与数值同口径."
  [body]
  (r/object! body [:object_type :aggregate :field :status :unit :label :description :reason])
  (let [object (s/enum! (:object_type body) report-objects "对象类型")
        aggregate (s/enum! (:aggregate body) report-aggregates "聚合")
        field (report-field! object aggregate (not-empty (str (or (:field body) ""))))
        status (not-empty (str (or (:status body) "")))
        status (when status (s/enum! status report-statuses "对象状态"))
        unit (s/enum! (or (:unit body) "count") #{"count" "money" "percent"} "指标单位")]
    ;; 状态是口径的一部分, 编入编码, 保证同一编码不多口径累加.
    {:code (str (str/upper-case (str (if (= "project" object) "PROJ" (str/upper-case object))
                                     "-" aggregate
                                     (when field (str "-" (name field)))))
                (when status (str "@" (str/upper-case status))))
     :object_type object :aggregate aggregate :field field :filter_status status :unit unit
     :label (r/text! (or (:label body) (str object " " aggregate)) "指标名称" 100 true)
     :description (s/optional-text! body :description 500)}))


(def approval-types
  "可按策略配置多级审批的业务类型 (编码即类型); 带金额的类型允许按金额设置级别条件."
  {"plan-baseline" {:label "计划基线" :amount? false}
   "charter" {:label "项目章程" :amount? true}
   "cost-version" {:label "费用版本" :amount? true}
   "closure" {:label "项目结项" :amount? false}})


(def approval-rules
  "审批人规则: 指定角色, 项目所属部门负责人 (可上溯), 项目经理, 指定用户, 提交人选择."
  #{"role" "dept_leader" "project_manager" "user" "submitter_choice"})


(defn- level-rule-fields
  "按审批人规则补齐规则专属字段并返回级别行: role 的角色与成员限定, dept_leader 的上溯级数, user 的指定用户."
  [rule level base]
  (case rule
    "role" (assoc base :role_key (r/text! (:role_key level) "角色" 100 true)
                  :members_only (true? (:members_only level)))
    "dept_leader" (let [up (or (:up level) 0)]
                    (when-not (and (integer? up) (<= 0 up 5)) (r/fail! 400 "上溯级数必须为0到5"))
                    (assoc base :up up))
    "user" (let [ids (:user_ids level)]
             (when-not (and (vector? ids) (<= 1 (count ids) 20)) (r/fail! 400 "指定用户需要1到20人"))
             (assoc base :user_ids (vec (distinct (map #(r/positive-id! % "审批用户") ids)))))
    base))


(defn- approval-level!
  [amount? level]
  (when-not (map? level) (r/fail! 400 "审批级别必须是对象"))
  (let [rule (s/enum! (:rule level) approval-rules "审批人规则")
        mode (s/enum! (or (:mode level) "any") #{"any" "all"} "会签/或签")
        on-empty (s/enum! (or (:on_empty level) "reject") #{"reject" "skip"} "无审批人处理")
        min-amount (when-let [v (not-empty (str (or (:min_amount level) "")))]
                     (when-not amount? (r/fail! 400 "该审批类型没有金额, 不能设置金额条件"))
                     (let [minor (money/amount! v "金额条件")]
                       (when (neg? minor) (r/fail! 400 "金额条件不能为负数"))
                       (money/money minor)))
        base {:name (r/text! (:name level) "级别名称" 50 true) :rule rule :mode mode :on_empty on-empty
              :min_amount min-amount}]
    (level-rule-fields rule level base)))


(defn- approval-policy!
  "审批策略: 编码为业务类型, 1 到 6 级, 每级一个审批人规则."
  [body]
  (let [code (s/enum! (:code body) (set (keys approval-types)) "审批类型")
        levels (:levels body)]
    (when-not (and (vector? levels) (<= 1 (count levels) 6)) (r/fail! 400 "审批策略需要1到6级"))
    {:code code
     :name (r/text! (or (:name body) (str (get-in approval-types [code :label]) "审批")) "策略名称" 100 true)
     :levels (mapv #(approval-level! (get-in approval-types [code :amount?]) %) levels)}))


(defn content!
  "按类型校验配置内容."
  [kind body]
  (case kind
    "project-template" (template! body)
    "coding-rule" (coding-rule! body)
    "quarterly-target" (quarterly-target! body)
    "rd-pool" (rd-pool! body)
    "period-lock" (period-lock! body)
    "rate-table" (rate-table! body)
    "exchange-rate" (exchange-rate! body)
    "tax-rate" (tax-rate! body)
    "report-metric" (report-metric! body)
    "approval-policy" (approval-policy! body)))


;; ── 命令 ────────────────────────────────────────────────────────

(defn- history
  [record action actor reason]
  (conj (vec (:history record)) {:action action :actor_id (:user_id actor) :actor_name (:user_name actor)
                                 :at (str (java.time.Instant/now)) :reason reason}))


(defn- insert!
  [q actor kind fields revision status]
  (let [id (k/id)]
    (q :config/insert! {:config_id id :kind kind :code (:code fields) :revision revision :status status
                        :created_by (:user_id actor)
                        :payload (json/generate-string (assoc (dissoc fields :code) :history (history nil (if (= status "locked") "locked" "created") actor nil)))})
    (record! q kind id)))


(defn change!
  "受控改变配置状态并追加历史, 可附带命令产生的补充字段 (如费用池分摊结果)."
  ([q kind record status actor action reason] (change! q kind record status actor action reason {}))
  ([q kind record status actor action reason patch]
   (let [payload (apply dissoc (merge (assoc record :history (history record action actor reason)) patch)
                        [:id :config_id :kind :code :revision :status :created_by :created_at :updated_at :created_by_name])]
     (r/changed! (q :config/update! {:config_id (:id record) :status status :payload (json/generate-string payload)}))
     (record! q kind (:id record)))))


(defn create!
  "创建新编码的首个版本: 常规类型为草稿, 封期直接锁定."
  [svc actor kind body]
  (s/enum! kind kinds "kind")
  (r/permit! actor (write-permissions kind))
  (k/transaction! svc
                  (fn [q]
                    (let [fields (content! kind body)
                          existing (filter #(= (:code fields) (:code %)) (records q kind))]
                      (when (some #(not= "retired" (:status %)) existing)
                        (r/fail! 409 "编码已存在, 请对最新版本创建修订"))
                      ;; 同编码全部已退役 (如解锁后的封期, 已退役的费用池) 时以新修订号重新建立, 保留历史.
                      (insert! q actor kind fields (inc (reduce max 0 (map :revision existing))) (if (= kind "period-lock") "locked" "draft"))))))


(defn revise!
  "从最新版本创建新修订草稿, 旧版本内容不变."
  [svc actor kind id body]
  (s/enum! kind kinds "kind")
  (r/permit! actor (write-permissions kind))
  (when (= kind "period-lock") (r/fail! 400 "封期不支持修订, 请解锁后重新锁定"))
  (k/transaction! svc
                  (fn [q]
                    (let [old (record! q kind id)
                          all (records q kind)
                          latest (apply max-key :revision (filter #(= (:code old) (:code %)) all))]
                      (when-not (= (:id latest) (:id old)) (r/fail! 409 "对象已有更新版本,请使用最新版本"))
                      (when (= "draft" (:status old)) (r/fail! 409 "当前版本仍是草稿, 可直接发布或作废"))
                      (let [fields (content! kind (if (contains? #{"project-template" "coding-rule" "approval-policy"} kind)
                                                    (assoc body :code (:code old)) (dissoc body :code)))]
                        (when-not (= (:code fields) (:code old)) (r/fail! 400 "修订不能改变配置编码"))
                        (insert! q actor kind (assoc fields :previous_id (:id old)) (inc (:revision old)) "draft"))))))


(defn publish!
  "发布草稿使其生效, 同编码此前生效版本自动退役; 研发费用池发布即冻结."
  [svc actor kind id body]
  (s/enum! kind kinds "kind")
  (r/permit! actor (write-permissions kind))
  (r/object! body [:reason])
  (k/transaction! svc
                  (fn [q]
                    (let [record (record! q kind id)
                          target (if (= kind "rd-pool") "frozen" "published")]
                      (s/status! record #{"draft"})
                      (doseq [prior (filter #(and (= (:code record) (:code %)) (not= (:id record) (:id %))
                                                  (contains? #{"published" "frozen"} (:status %))) (records q kind))]
                        (change! q kind prior "retired" actor "superseded" (str "被版本 " (:revision record) " 取代")))
                      (change! q kind record target actor target (s/optional-text! body :reason 500))))))


(defn retire!
  "退役生效版本或作废草稿, 解锁封期; 保留完整历史."
  [svc actor kind id body]
  (s/enum! kind kinds "kind")
  (r/permit! actor (write-permissions kind))
  (r/object! body [:reason])
  (k/transaction! svc
                  (fn [q]
                    (let [record (record! q kind id)]
                      (s/status! record #{"draft" "published" "frozen" "locked"})
                      (change! q kind record "retired" actor "retired" (s/text! body :reason 500))))))


(defn import-catalog!
  "从内置目录导入一项为草稿配置."
  [svc actor kind body]
  (r/object! body [:code])
  (let [item (case kind
               "project-template" (some #(when (= (:code body) (:code %)) %) catalog/project-templates)
               "coding-rule" (some #(when (= (:code body) (:code %)) %) catalog/coding-rules)
               "approval-policy" (some #(when (= (:code body) (:code %)) %) catalog/approval-policies)
               nil)]
    (when-not item (r/fail! 404 "目录项不存在"))
    (create! svc actor kind item)))


;; ── 读取 ────────────────────────────────────────────────────────

(defn listing
  "返回某类型的全部版本及内置目录."
  [svc actor kind]
  (r/permit! actor read-permissions)
  (s/enum! kind kinds "kind")
  (let [q (:query-fn svc)]
    {:rows (records q kind)
     :catalog (case kind
                "project-template" catalog/project-templates
                "coding-rule" catalog/coding-rules
                "approval-policy" catalog/approval-policies
                [])
     :gate_types catalog/gate-types
     :current_user_id (:user_id actor)}))


;; ── 编码规则应用 ────────────────────────────────────────────────

(defn- placeholder-regex
  [token]
  (cond (= token "YYYY") "[0-9]{4}" (= token "YY") "[0-9]{2}" (= token "MM") "[0-9]{2}"
        (= token "TYPE") "[A-Z_]+" (= token "PROJECT") ".+"
        (str/starts-with? token "SEQ:") (str "[0-9]{" (subs token 4) ",}")))


(defn pattern-regex
  "把编码模式转换为校验正则: 占位符换成对应字符类, 其余字面量原样转义."
  [pattern]
  (re-pattern (apply str (map (fn [piece]
                                (if-let [[_ token] (re-matches placeholder piece)]
                                  (placeholder-regex token)
                                  (java.util.regex.Pattern/quote piece)))
                              (remove str/blank? (str/split pattern #"(?<=\})|(?=\{)"))))))


(defn- sequence-count
  [q object-type project]
  (case object-type
    "project" (:total (q :config/project-count {}))
    ("sub" "machine") (:total (q :config/node-count {:node_type object-type}))
    ("document" "requirement") (:total (q :config/gov-count {:project_id (:project_id project) :kind object-type}))
    "task" (:total (q :config/task-count {:project_id (:project_id project)}))))


(defn render
  "按规则生成下一个编号."
  [rule context]
  (let [today (LocalDate/now)]
    (str/replace (:pattern rule) placeholder
                 (fn [[_ token]]
                   (cond (= token "YYYY") (str (.getYear today))
                         (= token "YY") (subs (str (.getYear today)) 2)
                         (= token "MM") (format "%02d" (.getMonthValue today))
                         (= token "TYPE") (str/upper-case (or (:type context) "EQUIPMENT"))
                         (= token "PROJECT") (or (:project_no context) "PRJ")
                         :else (format (str "%0" (subs token 4) "d") (inc (or (:count context) 0))))))))


(defn next-code
  "读取对象类型当前生效规则并生成建议编号 (流水按现有数量+1, 保存时仍以唯一约束为准)."
  [svc actor object-type project-id type]
  (r/permit! actor read-permissions)
  (let [q (:query-fn svc)
        rule (some #(when (= object-type (:object_type %)) %) (published q "coding-rule"))
        project (when project-id (r/access! q actor (q :pms/project {:project_id project-id}) false))]
    (if-not rule
      {:object_type object-type :rule nil :code nil}
      {:object_type object-type :rule (select-keys rule [:id :code :pattern :enforced :revision])
       :code (render rule {:type (or type (:project_type project)) :project_no (:project_no project)
                           :count (sequence-count q object-type project)})})))


(defn enforce!
  "当生效规则要求强制时校验编号格式, 否则放行."
  [q object-type value]
  (when-let [rule (some #(when (and (= object-type (:object_type %)) (:enforced %)) %) (published q "coding-rule"))]
    (when-not (re-matches (pattern-regex (:pattern rule)) (str value))
      (r/fail! 400 (str "编号不符合生效的编码规则 " (:pattern rule)))))
  value)
