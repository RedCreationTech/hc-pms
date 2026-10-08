(ns com.ruoyi.domain.pms.governance.collaboration
  "项目风险问题的验证关闭及会议行动转真实计划任务."
  (:require
    [com.ruoyi.domain.pms.governance.reviews :as reviews]
    [com.ruoyi.domain.pms.governance.risk-assessment :as ra]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(def risk-response-strategies
  "风险应对策略枚举: 规避/转移/减轻/接受."
  #{"avoid" "transfer" "mitigate" "accept"})


(def risk-categories
  "风险类别(RBS)枚举: 技术/外部/组织/进度/成本/质量, 登记时未选择则不写入(视为未设定)."
  #{"technical" "external" "organizational" "schedule" "cost" "quality"})


(def action-priorities
  "会议行动优先级枚举: 高/中/低, 未选择时不写入(视为未设定)."
  #{"high" "medium" "low"})


(def issue-resolution-types
  "问题解决方式枚举: 已修复/已规避/设计如此/重复/无法复现/不予修复, 提交解决时未选择则不写入(视为未设定)."
  #{"fixed" "workaround" "by-design" "duplicate" "cannot-reproduce" "wont-fix"})


(defn risk-response-coverage
  "按每个风险最新有效版本统计应对策略声明的只读覆盖度: PMI 四类策略各自计数, 已声明/未设定与覆盖率; 只读派生, 不落库不投递, 不改变风险状态."
  [risks]
  (let [strategies ["avoid" "transfer" "mitigate" "accept"]
        active (s/latest risks)
        total (count active)
        declared (count (filterv #(some #{(:response_strategy %)} strategies) active))
        strategy-count (fn [x] (count (filterv #(= x (:response_strategy %)) active)))]
    {:total total
     :declared declared
     :undeclared (- total declared)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :by-strategy (mapv (fn [x] {:strategy x :count (strategy-count x)}) strategies)}))


(defn risk-category-coverage
  "按每个风险最新有效版本统计风险类别(RBS)声明的只读覆盖度: PMI 六类风险分解结构类别各自计数, 已声明/未设定与覆盖率; 只读派生, 不落库不投递, 不改变风险状态."
  [risks]
  (let [categories ["technical" "external" "organizational" "schedule" "cost" "quality"]
        active (s/latest risks)
        total (count active)
        declared (count (filterv #(some #{(:risk_category %)} categories) active))
        category-count (fn [x] (count (filterv #(= x (:risk_category %)) active)))]
    {:total total
     :declared declared
     :undeclared (- total declared)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :by-category (mapv (fn [x] {:category x :count (category-count x)}) categories)}))


(defn risk-escalation-disposition-summary
  "按每个风险最新有效版本只读聚合超阈值升级处置情况: 升级总数与待确认/已确认/已豁免及 steering/management 分级计数; 只读派生, 不落库不投递, 不改变风险状态."
  [risks]
  (let [active (s/latest risks)
        escalated (filterv :escalated active)
        total (count active)
        esc-total (count escalated)
        state-count (fn [x] (count (filterv #(= x (:escalation_state %)) escalated)))
        level-count (fn [x] (count (filterv #(= x (:escalation_level %)) escalated)))]
    {:total total
     :escalated esc-total
     :not-escalated (- total esc-total)
     :pending (state-count "pending")
     :acknowledged (state-count "acknowledged")
     :waived (state-count "waived")
     :by-level (mapv (fn [x] {:level x :count (level-count x)}) ["steering" "management"])}))


(def risk-score-bands
  "风险评分(概率 x 影响, 1 到 25)热力分布分档: 低 1-5, 中 6-9, 高 10 到升级阈值前, 极高 达升级阈值及以上; 高档上界与极高档下界均取 ra/escalation-threshold, 与超阈值升级门控单一口径对齐, 四档边界连续覆盖 1-25 无缝隙."
  [{:key "low" :label "低" :min 1 :max 5}
   {:key "medium" :label "中" :min 6 :max 9}
   {:key "high" :label "高" :min 10 :max (dec ra/escalation-threshold)}
   {:key "critical" :label "极高" :min ra/escalation-threshold :max nil}])


(defn- band-member?
  "评分落入某档(下界含, 上界含或 nil 表示无上限); 评分缺失(nil)不属于任何档."
  [score {:keys [min max]}]
  (and (some? score) (>= score min) (or (nil? max) (<= score max))))


(defn risk-score-distribution
  "按每个风险最新有效版本只读统计概率 x 影响评分(1 到 25)的热力分布: 低/中/高/极高四档各自计数, 高档及以上(>=10), 达超阈值升级门控的极高(>=阈值)计数与平均评分; 只读派生, 不落库不投递, 不改变风险状态, 不构成门控. 键名不带尾随问号."
  [risks]
  (let [active (s/latest risks)
        total (count active)
        band-count (fn [band] (count (filterv #(band-member? (:score %) band) active)))
        high-or-above (count (filterv #(and (some? (:score %)) (<= 10 (:score %))) active))
        critical (count (filterv #(and (some? (:score %)) (<= ra/escalation-threshold (:score %))) active))
        sum (reduce + 0 (keep :score active))]
    {:available (pos? total)
     :total total
     :high-or-above high-or-above
     :critical critical
     :avg-score (if (pos? total)
                  (int (Math/round ^double (/ (double sum) total)))
                  0)
     :by-band (mapv (fn [{:keys [key label] :as band}]
                      {:band key :label label :count (band-count band)})
                    risk-score-bands)}))


(defn risk-stage-distribution
  "按每个风险最新有效版本只读统计风险在项目阶段(:stage 可选文本, 如 设计/采购/执行/全周期)上的分布: 各阶段条数、平均评分(概率 x 影响, 1 到 25)、高档及以上(>=10)与达超阈值升级门控(>=ra/escalation-threshold)计数, 未标注阶段单列, 用于观察风险在项目生命周期上的集中位置; 高档与极高阈值复用 risk-score-distribution 同款评分带口径单一不漂移(登记风险概率与影响必填, 故每条风险恒有评分), 阶段为自由文本按实际取值动态分组, 只读派生, 不落库不投递, 不改变风险状态, 不构成门控. 键名 kebab-case 不带尾随问号."
  [risks]
  (let [active (s/latest risks)
        total (count active)
        labeled (filterv #(some? (:stage %)) active)
        unclassified (- total (count labeled))
        groups (vals (group-by :stage labeled))
        high-or-above (fn [rs] (count (filterv #(<= 10 (:score %)) rs)))
        critical (fn [rs] (count (filterv #(<= ra/escalation-threshold (:score %)) rs)))
        avg-score (fn [rs] (int (Math/round ^double (/ (double (reduce + 0 (map :score rs))) (count rs)))))
        entry (fn [rs] {:stage (:stage (first rs))
                        :count (count rs)
                        :avg-score (avg-score rs)
                        :high-or-above (high-or-above rs)
                        :critical (critical rs)})]
    {:available (pos? total)
     :total total
     :labeled-stages (count groups)
     :unclassified unclassified
     :stages (sort-by (juxt (comp - :critical) (comp - :high-or-above) (comp - :count) :stage)
                      (mapv entry groups))}))


(def risk-review-frequency-order
  "风险复审频率只读统计顺序 (与 ra/review-frequencies 词汇一致, 无未设定档因未设频率者计入 unassigned)."
  ["weekly" "biweekly" "monthly" "quarterly"])


(def risk-review-frequency-labels
  "风险复审频率中文标签映射 (用于前端展示). 与 risk-review-frequency-order 同词汇."
  {"weekly" "每周" "biweekly" "双周" "monthly" "每月" "quarterly" "每季度"})


(defn risk-review-frequency-distribution
  "按每个风险最新有效版本只读统计复审频率(:review_frequency 可选枚举 weekly/biweekly/monthly/quarterly)在项目级的分布: 四档固定顺序各 count/open/overdue/due-soon 计数, 顶层输出已设定/未设定与已设定百分比. open 排除 status=closed; overdue 与 due-soon 复用 reviews/risk-read-model 已派生的 :review_overdue 与 :review_due_soon (与逐条台账到期倒计时同口径不漂移); 未设定频率(:review_frequency nil)恒计入 unassigned 而不进入 by-frequency 四档(与手工登记风险无应对策略计入未声明同构); 只读派生, 不落库不投递, 不改变风险状态, 不构成门控. 键名 kebab-case 不带尾随问号."
  [risks]
  (let [freqs risk-review-frequency-order
        active (s/latest risks)
        total (count active)
        declared (count (filterv #(some #{(:review_frequency %)} freqs) active))
        unassigned (- total declared)
        subset (fn [x] (filterv #(= x (:review_frequency %)) active))
        tally (fn tally [rs]
                 {:count (count rs)
                  :open (count (remove #(= "closed" (:status %)) rs))
                  :overdue (count (filterv :review_overdue rs))
                  :due-soon (count (filterv :review_due_soon rs))})]
    {:available (pos? total)
     :total total
     :declared declared
     :unassigned unassigned
     :declared-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :by-frequency (mapv (fn [x] (into {:frequency x} (tally (subset x)))) freqs)}))


(defn risk-review-cadence-summary
  "按每个风险最新有效版本只读聚合复审到期节奏: 未关闭风险按到期日三分已逾期(到期日<=今天)/临期(1 到 3 天内)/未来到期(距今超过临期窗口), 并给出未关闭与已关闭计数; 复用 reviews/risk-read-model 已派生的 :review_overdue/:review_due_soon 与 :status, 与逐条台账到期倒计时口径单一不漂移(登记风险必填到期日, 故未关闭风险恒落在三档之一); 只读派生, 不落库不投递, 不改变风险状态, 不构成门控. 键名不带尾随问号."
  [risks]
  (let [active (s/latest risks)
        total (count active)
        closed (count (filterv #(= "closed" (:status %)) active))
        open (count (remove #(= "closed" (:status %)) active))
        open-risks (remove #(= "closed" (:status %)) active)
        overdue (count (filterv :review_overdue open-risks))
        due-soon (count (filterv :review_due_soon open-risks))]
    {:available (pos? total)
     :total total
     :open open
     :closed closed
     :overdue overdue
     :due-soon due-soon
     :upcoming (- open overdue due-soon)}))


(def cadence-due-soon-days
  "沟通节奏临期窗口(天): 下次沟通距今天数在 1 到该值内视为临期."
  7)


(def comm-cadence-frequencies
  "沟通节奏频率只读统计顺序与中文标签 (与 stakeholders/cadences 词汇一致)."
  [["daily" "每日"] ["weekly" "每周"] ["biweekly" "双周"] ["monthly" "每月"] ["quarterly" "每季度"]])


(defn comm-cadence-summary
  "按每个沟通计划最新有效版本只读聚合下次沟通到期节奏: 未逾期计划按到期日三分已逾期(下次沟通<=今天)/临期(1 到临期窗口天数内)/未来到期(距今超过临期窗口), 并按五种沟通频率回显计划条数分布; 复用 stakeholders/comm-plan-read-model 已派生的 :comm_overdue(等价于剩余天数<=0) 与 :comm_days_until 作单一口径, 与逐条台账到期预警对齐不漂移(登记沟通计划必填下次沟通日期, 故每条计划恒落在三档之一, 不设未设定桶); 沟通计划以 code 形成修订链, 经 store/latest 折叠只计最新版; 只读派生, 不落库不投递, 不改变计划状态, 不构成门控. 键名不带尾随问号."
  [plans]
  (let [active (s/latest plans)
        total (count active)
        overdue (count (filterv :comm_overdue active))
        due-soon (count (filterv #(and (some? (:comm_days_until %))
                                       (pos? (:comm_days_until %))
                                       (<= (:comm_days_until %) cadence-due-soon-days))
                                 active))
        freq-count (fn [k] (count (filterv #(= k (:frequency %)) active)))]
    {:available (pos? total)
     :total total
     :overdue overdue
     :due-soon due-soon
     :upcoming (- total overdue due-soon)
     :by-frequency (mapv (fn [[k label]] {:frequency k :label label :count (freq-count k)})
                         comm-cadence-frequencies)}))


(defn- insert-risk!
  "写入风险记录: 统一按概率 x 影响评分, 达阈值自动标记超阈值升级, 可选携带阶段与风险库来源信息; 评分与升级判定共用 risk-assessment 纯函数."
  [q project actor fields]
  (let [probability (ra/score! (:probability fields))
        impact (ra/score! (:impact fields))]
    (s/insert! q project actor "risk"
               (into (ra/assessment probability impact)
                     (cond-> {:title (s/text! fields :title 200)
                              :owner_id (k/user! q project (:owner_id fields) "负责人")
                              :mitigation (s/text! fields :mitigation)
                              :due_date (s/date! fields :due_date)}
                       (:stage fields) (assoc :stage (:stage fields))
                       (:source_key fields) (assoc :source_key (:source_key fields))
                       (:source_category fields) (assoc :source_category (:source_category fields))
                       (:response_strategy fields) (assoc :response_strategy
                                                          (s/enum! (:response_strategy fields) risk-response-strategies "应对策略"))
                       (:risk_category fields) (assoc :risk_category
                                                        (s/enum! (:risk_category fields) risk-categories "风险类别"))
                       (:review_frequency fields) (assoc :review_frequency
                                                           (s/enum! (:review_frequency fields) ra/review-frequencies "复审频率"))))
               {:status "open"})))


(defn create-risk!
  "登记有明确责任人与预防措施的风险; 评分超阈值时自动标记升级待独立确认."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.created"
             (fn [q project]
               (s/input! body [:title :probability :impact :owner_id :mitigation :due_date :response_strategy :risk_category :review_frequency])
               (insert-risk! q project actor body))))


(defn mitigate!
  "记录风险措施实际执行证据并保留其后转问题的能力; 超阈值升级未确认前不得自行缓解."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.mitigated"
             (fn [q project]
               (s/input! body [:mitigation :evidence_ids])
               (let [risk (s/record! q project "risk" rid)]
                 (s/status! risk #{"open" "mitigated"})
                 (when (and (:escalated risk) (= "pending" (:escalation_state risk)))
                   (r/fail! 409 "该风险已超阈值升级, 请先由独立质量审批人确认处置措施再缓解"))
                 (s/change! q project risk "mitigated"
                            {:mitigation (s/text! body :mitigation)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)})))))


(defn acknowledge-escalation!
  "由独立质量审批人确认超阈值风险的升级处置, 批准责成处置或经评估豁免, 记录后方可继续缓解."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "risk.escalation-acknowledged" {:write? false}
             (fn [q project]
               (s/input! body [:decision :note])
               (let [risk (s/record! q project "risk" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
                     note (s/text! body :note 500)]
                 (when-not (:escalated risk) (r/fail! 409 "该风险未触发升级, 无需确认"))
                 (when-not (= "pending" (:escalation_state risk)) (r/fail! 409 "风险升级已确认, 请勿重复处理"))
                 (when (= (:user_id actor) (:created_by risk)) (r/fail! 403 "升级确认不得由风险登记人本人完成"))
                 (s/change! q project risk (:status risk)
                            {:escalation_state (if (= "approved" decision) "acknowledged" "waived")
                             :escalation_decision decision :escalation_note note
                             :escalation_ack_by (:user_id actor) :escalation_ack_on (str (LocalDate/now))
                             :workflow_history (conj (vec (:workflow_history risk))
                                                     {:action "escalation_acknowledged" :actor_id (:user_id actor)
                                                      :on (str (LocalDate/now)) :decision decision})})))))


(def risk-library
  "内置典型风险库: 沉淀常见风险的标准类别, 概率, 影响, 应对措施与适用阶段, 供项目一键实例化并复用超阈值升级门控."
  [{:key "schedule-delay" :category "schedule" :title "关键路径进度延误"
    :probability 4 :impact 4 :mitigation "预留进度缓冲, 按周跟踪关键路径并及早纠偏" :stage "执行"}
   {:key "supply-outage" :category "supply" :title "关键物料断供"
    :probability 5 :impact 5 :mitigation "启用备选供应商并加严来料检验, 提前锁定安全库存" :stage "采购"}
   {:key "tech-uncertainty" :category "technical" :title "关键技术方案不成熟"
    :probability 3 :impact 3 :mitigation "先做技术验证原型, 预留备选技术方案" :stage "设计"}
   {:key "cost-overrun" :category "cost" :title "项目成本超支"
    :probability 4 :impact 5 :mitigation "建立挣值监控, 变更须走成本影响评估审批" :stage "执行"}
   {:key "staff-turnover" :category "other" :title "关键人员流失"
    :probability 2 :impact 3 :mitigation "关键岗位设置 AB 角并做好知识文档化" :stage "全周期"}])


(defn- library-entry
  "按 key 查找内置风险库条目, 未命中返回 404."
  [template-key]
  (or (first (filter #(= (:key %) template-key) risk-library))
      (r/fail! 404 "风险库中不存在该典型风险")))


(defn from-library!
  "从内置典型风险库选用一条, 按当前责任人与期限实例化为真实风险, 继承评分并复用超阈值自动升级."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.created"
             (fn [q project]
               (s/input! body [:template_key :owner_id :due_date])
               (let [entry (library-entry (:template_key body))]
                 (insert-risk! q project actor
                               (assoc entry :owner_id (:owner_id body) :due_date (:due_date body)
                                      :source_key (:key entry)
                                      :source_category (:category entry)))))))


(defn- template-view
  "把 pms_risk_template 数据库行转为平铺只读对象, template_id 归一为 id 并按概率x影响派生评分."
  [row]
  (when row
    (assoc (select-keys row [:project_id :title :category :probability :impact :mitigation :stage
                             :status :created_by :created_at :updated_at])
           :id (:template_id row)
           :score (* (:probability row) (:impact row)))))


(defn risk-templates
  "读取项目内全部有效自定义风险模板, 按评分口径只读派生供工作台展示与实例化选择."
  [q project]
  (mapv template-view (q :rt/list {:project_id (:project_id project)})))


(defn- template-fields!
  "校验自定义风险模板的标题,评分,应对措施与可选类别/阶段; 类别与阶段留空存 nil."
  [body]
  {:title (s/text! body :title 200)
   :probability (ra/score! (:probability body))
   :impact (ra/score! (:impact body))
   :mitigation (s/text! body :mitigation 2000)
   :category (some-> (s/optional-text! body :category 40) not-empty)
   :stage (some-> (s/optional-text! body :stage 40) not-empty)})


(defn- active-template!
  "读取同项目有效自定义风险模板, 缺失或已作废返回404, 供更新,作废与实例化复用."
  [q project template-id]
  (let [row (q :rt/record {:project_id (:project_id project) :template_id template-id})]
    (when-not (and row (= "active" (:status row)))
      (r/fail! 404 "自定义风险模板不存在或已作废"))
    row))


(defn create-risk-template!
  "在项目内新建一份可复用的自定义风险模板: 固化标题,概率x影响评分,应对措施与可选类别/阶段, 供后续一键实例化为真实风险."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk-template.created"
             (fn [q project]
               (s/input! body [:title :category :probability :impact :mitigation :stage])
               (let [template-id (k/id)
                     fields (template-fields! body)]
                 (q :rt/insert! (assoc fields :template_id template-id :project_id (:project_id project)
                                                 :created_by (:user_id actor)))
                 (template-view (q :rt/record {:project_id (:project_id project) :template_id template-id}))))))


(defn update-risk-template!
  "更新有效自定义风险模板的评分与应对措施等内容; 已作废或不存在返回404."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk-template.updated"
             (fn [q project]
               (s/input! body [:title :category :probability :impact :mitigation :stage])
               (active-template! q project rid)
               (let [fields (template-fields! body)]
                 (r/changed! (q :rt/update! (assoc fields :project_id (:project_id project) :template_id rid)))
                 (template-view (q :rt/record {:project_id (:project_id project) :template_id rid}))))))


(defn discard-risk-template!
  "受控作废自定义风险模板: 软置为已作废并从可实例化列表移除, 不影响已登记的历史风险."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk-template.discarded"
             (fn [q project]
               (s/input! body [:reason])
               (active-template! q project rid)
               (r/changed! (q :rt/discard! {:project_id (:project_id project) :template_id rid}))
               (template-view (q :rt/record {:project_id (:project_id project) :template_id rid})))))


(defn from-custom-template!
  "从项目内某份有效自定义风险模板实例化为真实风险, 继承模板评分与应对措施并复用超阈值自动升级门控, 仅需指定责任人与期限."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.created"
             (fn [q project]
               (s/input! body [:template_id :owner_id :due_date])
               (let [row (active-template! q project (:template_id body))]
                 (insert-risk! q project actor
                               {:title (:title row) :probability (:probability row) :impact (:impact row)
                                :mitigation (:mitigation row)
                                :owner_id (:owner_id body) :due_date (:due_date body)
                                :stage (:stage row)
                                :source_key (str "custom:" (:template_id row))
                                :source_category (:category row)})))))


(defn- issue-fields!
  "校验问题内容,严重度,责任人与解决期限."
  [q project body]
  (s/input! body [:title :severity :owner_id :due_date])
  {:title (s/text! body :title 200)
   :severity (s/enum! (:severity body) #{"blocker" "major" "minor"} "severity")
   :owner_id (k/user! q project (:owner_id body) "负责人") :due_date (s/date! body :due_date)})


(defn- issue-escalation
  "按严重度与到期日计算问题自动升级处置: 阻断级(blocker)问题登记即升级到经理层, 若登记时已逾期则升到管理层; 其它严重度不触发升级(返回 nil, 不写任何 escalation 键, 与既有问题用例兼容)."
  [{:keys [severity due_date]}]
  (when (= "blocker" severity)
    (let [overdue? (and due_date (not (.isAfter (LocalDate/parse due_date) (LocalDate/now))))
          level (if overdue? "steering" "management")]
      {:escalated true
       :escalation_state "pending"
       :escalation_level level
       :escalation_reason (str "阻断级问题" (when overdue? "且登记时已逾期")
                               ", 须由独立质量审批人确认" (if (= level "steering") "管理层" "经理层") "处置后方可提交解决.")})))


(defn create-issue!
  "创建需经过独立验证才能关闭的问题; 阻断级问题登记即自动升级, 待独立质量审批人确认处置后方可提交解决."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.created"
             (fn [q project]
               (let [fields (issue-fields! q project body)]
                 (s/insert! q project actor "issue" (merge fields (issue-escalation fields)) {:status "open"})))))


(defn materialize!
  "风险发生时幂等生成问题并保留双向来源关联."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.materialized"
             (fn [q project]
               (s/input! body [:title])
               (let [risk (s/record! q project "risk" rid)]
                 (if-let [target (:issue_id risk)]
                   (s/record! q project "issue" target)
                   (do
                     (s/status! risk #{"open" "mitigated"})
                     (let [issue (s/insert! q project actor "issue"
                                            {:title (or (not-empty (s/optional-text! body :title 200)) (:title risk))
                                             :owner_id (:owner_id risk) :due_date (:due_date risk)
                                             :severity (if (>= (:impact risk) 4) "blocker" "major")
                                             :source_risk_id rid} {:status "open"})]
                       (s/change! q project risk "materialized" {:issue_id (:id issue)})
                       issue)))))))


(defn mitigation-action!
  "将风险的预防措施落实为可追踪的行动项: 复用行动类型与既有完成/验证/转任务生命周期, 记录来源风险; 需项目编辑权限, 仅对进行中或已缓解的风险开放."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "risk.mitigation-action-created"
             (fn [q project]
               (s/input! body [:title :owner_id :due_date])
               (let [risk (s/record! q project "risk" rid)
                     _ (s/status! risk #{"open" "mitigated"})
                     prefix "落实预防措施: "
                     base (:title risk)
                     fallback (str prefix (if (> (count base) (- 200 (count prefix)))
                                            (subs base 0 (- 200 (count prefix))) base))
                     given (s/optional-text! body :title 200)]
                 (s/insert! q project actor "action"
                            {:title (if (seq given) given fallback)
                             :owner_id (k/user! q project (or (:owner_id body) (:owner_id risk)) "负责人")
                             :due_date (r/date! (or (not-empty (s/optional-text! body :due_date 10)) (:due_date risk)) "到期日")
                             :source_risk_id rid}
                            {:status "open"})))))


(defn variance-action!
  "为挣值偏差(进度落后或工时超支)登记有负责人和到期日的可追踪纠正措施, 复用会议行动类型与既有完成/独立验证闭环, 记录来源偏差种类与状态日期; 需项目编辑权限, 只新增行动不改变挣值指标本身."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "action.variance-created"
             (fn [q project]
               (s/input! body [:title :owner_id :due_date :variance_kind :variance_status_date])
               (let [kind (s/enum! (:variance_kind body) #{"schedule" "cost"} "偏差种类")]
                 (s/insert! q project actor "action"
                            {:title (s/text! body :title 200)
                             :owner_id (k/user! q project (:owner_id body) "负责人")
                             :due_date (s/date! body :due_date)
                             :variance_kind kind
                             :variance_status_date (not-empty (s/optional-text! body :variance_status_date 10))}
                            {:status "open"})))))


(defn resolve!
  "提交整改内容和确切证据版本,指定独立验证人."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.resolution-submitted"
             (fn [q project]
               (s/input! body [:resolution :resolution_type :evidence_ids :reviewer_id])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (when (and (:escalated issue) (= "pending" (:escalation_state issue)))
                   (r/fail! 409 "该问题已超阈值升级, 请先由独立质量审批人确认处置措施再提交解决"))
                 (s/change! q project issue "in_review"
                            (cond-> {:review_action "closure" :resolution (s/text! body :resolution)
                                     :evidence_ids (s/evidence! q project (:evidence_ids body) true)
                                     :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                                     :submitted_by (:user_id actor)}
                              (some? (:resolution_type body))
                              (assoc :resolution_type
                                     (s/enum! (:resolution_type body) issue-resolution-types "解决方式"))))))))


(defn verify!
  "指定独立人员验证整改关闭,或批准有理由的受控重开."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "issue.verified" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [issue (s/record! q project "issue" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! issue #{"in_review"})
                 (s/decision-actor! actor issue)
                 (if (= "reopen" (:review_action issue))
                   (reviews/decide-reopening! q project actor issue body)
                   (do
                     (s/evidence! q project (:evidence_ids issue) true)
                     (s/change! q project issue (if (= decision "approved") "closed" "rejected")
                                {:verification_reason (s/text! body :reason) :verified_by (:user_id actor)})))))))


(defn reassign-issue!
  "转派问题责任人, 保留原责任人与转派原因供审计, 新责任人须为当前项目成员."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.reassigned"
             (fn [q project]
               (s/input! body [:owner_id :reason])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (s/change! q project issue (:status issue)
                            {:owner_id (k/user! q project (:owner_id body) "新责任人")
                             :reassigned_from (:owner_id issue)
                             :reassign_reason (s/text! body :reason 500)
                             :reassigned_by (:user_id actor)})))))


(defn acknowledge-issue-escalation!
  "由独立质量审批人确认阻断级问题的升级处置, 批准责成处置或经评估豁免, 记录后方可提交解决."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "issue.escalation-acknowledged" {:write? false}
             (fn [q project]
               (s/input! body [:decision :note])
               (let [issue (s/record! q project "issue" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
                     note (s/text! body :note 500)]
                 (when-not (:escalated issue) (r/fail! 409 "该问题未触发升级, 无需确认"))
                 (when-not (= "pending" (:escalation_state issue)) (r/fail! 409 "问题升级已确认, 请勿重复处理"))
                 (when (= (:user_id actor) (:created_by issue)) (r/fail! 403 "升级确认不得由问题登记人本人完成"))
                 (s/change! q project issue (:status issue)
                            {:escalation_state (if (= "approved" decision) "acknowledged" "waived")
                             :escalation_decision decision :escalation_note note
                             :escalation_ack_by (:user_id actor) :escalation_ack_on (str (LocalDate/now))
                             :workflow_history (conj (vec (:workflow_history issue))
                                                     {:action "escalation_acknowledged" :actor_id (:user_id actor)
                                                      :on (str (LocalDate/now)) :decision decision})})))))


(def meeting-types
  "会议类型: 常规/启动会/评审会/FAT启动会/FAT总结会."
  #{"regular" "kickoff" "review" "fat-kickoff" "fat-summary"})


(defn flag-meeting-baselines
  "只读标注会议引用的基线是否仍是当前最新已批准基线 (引用版本失效校验), 不改状态."
  [meetings baselines]
  (let [approved (last (sort-by :plan_revision (filter #(= "approved" (:status %)) baselines)))
        by-id (into {} (map (juxt :baseline_id identity) baselines))]
    (mapv (fn [meeting]
            (if-let [bid (:baseline_id meeting)]
              (let [current (get by-id bid)]
                (assoc meeting :baseline_current_status (:status current)
                       :baseline_stale (boolean (and approved (not= bid (:baseline_id approved))))))
              meeting))
          meetings)))


(defn create-meeting!
  "持久化项目会议纪要,有效参会人员,可选会前资料(真实文档版本), 会议类型与主计划基线引用."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "meeting.recorded"
             (fn [q project]
               (s/input! body [:title :held_on :minutes :attendee_ids :material_ids :meeting_type :baseline_id])
               (when-not (and (vector? (:attendee_ids body)) (<= 1 (count (:attendee_ids body)) 100))
                 (r/fail! 400 "参会人员必须为1到100人的数组"))
               (let [type (s/enum! (or (:meeting_type body) "regular") meeting-types "meeting_type")
                     materials (s/evidence! q project (or (:material_ids body) []) false)
                     baseline (when (seq (:baseline_id body))
                                (or (q :planning/baseline {:project_id (:project_id project) :baseline_id (:baseline_id body)})
                                    (r/fail! 404 "计划基线不存在或不属于本项目")))]
                 ;; B05: 启动会必须携带会前包 (售前/需求资料版本) 并引用主计划基线, 形成强制关联.
                 (when (= "kickoff" type)
                   (when (empty? materials) (r/fail! 409 "启动会必须绑定售前/需求资料作为会前包"))
                   (when-not baseline (r/fail! 409 "启动会必须引用主计划基线")))
                 (s/insert! q project actor "meeting"
                            (cond-> {:title (s/text! body :title 200) :held_on (s/date! body :held_on)
                                     :minutes (s/text! body :minutes 20000) :meeting_type type
                                     :attendee_ids (vec (distinct (map #(s/user! q %) (:attendee_ids body))))
                                     :material_ids materials}
                              baseline (assoc :baseline_id (:baseline_id baseline) :baseline_revision (:plan_revision baseline)
                                              :baseline_status (:status baseline)))
                            {:status "recorded"})))))


(defn submit-meeting!
  "会议纪要受控发布: 已登记的纪要提交给独立质量审批人审核并冻结为审批中. 免迁移, 元数据随 payload 持久化."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "meeting.submitted"
             (fn [q project]
               (s/input! body [:reviewer_id])
               (let [meeting (s/record! q project "meeting" rid)]
                 (s/status! meeting #{"recorded"})
                 (when-not (seq (:minutes meeting))
                   (r/fail! 409 "纪要内容为空,不能提交发布"))
                 (s/change! q project meeting "in_review"
                            {:reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)})))))


(defn decide-meeting!
  "指定独立审核人核验会议纪要: 批准形成不可变已发布纪要, 驳回退回登记态供补充."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "meeting.decided" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [meeting (s/record! q project "meeting" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! meeting #{"in_review"})
                 (s/decision-actor! actor meeting)
                 (s/change! q project meeting (if (= decision "approved") "approved" "recorded")
                            {:release_decision decision
                             :release_reason (s/text! body :reason 2000)
                             :released_by (when (= decision "approved") (:user_id actor))
                             :decided_by (:user_id actor)})))))


(defn create-action!
  "从确定会议派生有责任人和期限的行动项."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.created"
             (fn [q project]
               (s/input! body [:title :owner_id :due_date :priority])
               (let [meeting (s/record! q project "meeting" rid)]
                 (when (= "discarded" (:status meeting))
                   (r/fail! 409 "会议已作废, 不能派生行动")))
               (s/insert! q project actor "action"
                          (cond-> {:title (s/text! body :title 200) :owner_id (k/user! q project (:owner_id body) "负责人")
                                   :due_date (s/date! body :due_date) :meeting_id rid}
                            (:priority body) (assoc :priority (s/enum! (:priority body) action-priorities "优先级")))
                          {:status "open"}))))


(defn materialize-action!
  "在同一事务中幂等创建真实WBS任务,保留来源及目标ID."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.task-created"
             (fn [q project]
               (s/input! body [:start_date :duration_days :wbs_code])
               (let [action (s/record! q project "action" rid)]
                 (if-let [target (:target_task_id action)]
                   (do
                     (when-not (q :planning/task {:project_id (:project_id project) :task_id target})
                       (r/fail! 409 "已关联任务不存在,需要修复任务引用"))
                     {:target_task_id target :action action})
                   (let [task (planning/create-task-record!
                                q project actor
                                (cond-> {:name (:title action) :owner_id (:owner_id action)
                                         :start_date (or (:start_date body) (:due_date action))
                                         :duration_days (or (:duration_days body) 1)
                                         :description (str "会议行动项 " rid)
                                         :source_type "meeting_action" :source_id rid}
                                  (:wbs_code body) (assoc :wbs_code (:wbs_code body))))
                         target (:task_id task)]
                     (when-not target (r/fail! 500 "任务服务未返回有效任务ID"))
                     {:target_task_id target
                      :action (s/change! q project action "converted" {:target_task_id target})}))))))


(defn complete-action!
  "会议行动完成须提交结果说明与真实证据并指定独立验证人, 保留未转任务行动的追踪闭环."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.completion-submitted"
             (fn [q project]
               (s/input! body [:result :evidence_ids :reviewer_id])
               (let [action (s/record! q project "action" rid)]
                 (s/status! action #{"open" "rejected"})
                 (s/change! q project action "in_review"
                            {:review_action "action_closure"
                             :result (s/text! body :result 2000)
                             :evidence_ids (s/evidence! q project (:evidence_ids body) true)
                             :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)})))))


(defn reopen-action!
  "已关闭的会议行动仅能通过有理由, 证据和独立指定审核人的重开申请重新处理, 沿用问题受控重开闭环."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "action.reopen-submitted"
             (fn [q project]
               (s/input! body [:reason :reviewer_id :evidence_ids])
               (let [action (s/record! q project "action" rid)
                     reason (s/text! body :reason 2000)
                     evidence (s/evidence! q project (:evidence_ids body) true)]
                 (s/status! action #{"closed"})
                 (s/change! q project action "in_review"
                            {:review_action "action_reopen" :reopen_reason reason
                             :reopen_evidence_ids evidence
                             :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                             :submitted_by (:user_id actor)
                             :prior_closure_result (:result action)
                             :prior_verification_reason (:verification_reason action)})))))


(defn verify-action!
  "由指定独立审核人核验会议行动: 常规完成批准关闭或驳回退回负责人, 或对已关闭行动的重开申请批准重开或维持关闭."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "action.verified" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [action (s/record! q project "action" rid)
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! action #{"in_review"})
                 (s/decision-actor! actor action)
                 (if (= "action_reopen" (:review_action action))
                   (do
                     (s/evidence! q project (:reopen_evidence_ids action) true)
                     (s/change! q project action (if (= decision "approved") "open" "closed")
                                {:reopen_decision decision
                                 :reopen_decision_reason (s/text! body :reason 2000)
                                 :reopen_decided_by (:user_id actor)
                                 :review_action nil}))
                   (do
                     (s/evidence! q project (:evidence_ids action) true)
                     (s/change! q project action (if (= decision "approved") "closed" "rejected")
                                {:verification_reason (s/text! body :reason 2000)
                                 :verified_by (:user_id actor)})))))))


(defn action-overdue?
  "行动存在到期日且未关闭未转任务且到期日不晚于服务器当天即视为逾期."
  [action]
  (boolean (and (:due_date action)
                (not (contains? #{"closed" "converted"} (:status action)))
                (not (.isAfter (LocalDate/parse (:due_date action)) (LocalDate/now))))))


(def due-soon-days
  "未决事项到期日距服务器当天不超过该天数(不含当天)即视为临期, 供到期倒计时提前关注."
  3)


(defn- days-until
  "到期日相对服务器当天的剩余天数; 负值表示已逾期天数, 空日期返回 nil. 只读派生不落库."
  [due]
  (when (some? due) (- (.toEpochDay (LocalDate/parse due)) (.toEpochDay (LocalDate/now)))))


(defn action-read-model
  "以服务器日期展示会议行动是否逾期未完成及剩余到期天数; 已关闭或已转真实任务的行动不再计逾期与倒计时."
  [action]
  (let [done? (contains? #{"closed" "converted"} (:status action))
        days (when-not done? (days-until (:due_date action)))]
    (assoc action
           :action_overdue (action-overdue? action)
           :action_due_in_days days
           :action_due_soon (boolean (and (some? days) (<= 1 days due-soon-days))))))


(defn enrich-meetings
  "为会议行汇总其派生行动的闭环情况: 行动总数/未完成(排除已关闭与已转真实任务)/其中逾期, 只读计算不改状态."
  [meetings actions-by-meeting]
  (mapv (fn [meeting]
          (let [acts (get actions-by-meeting (:id meeting) [])
                open (filterv #(not (contains? #{"closed" "converted"} (:status %))) acts)
                overdue (filterv action-overdue? open)]
            (assoc meeting :meeting_action_total (count acts)
                             :meeting_open_actions (count open)
                             :meeting_overdue_actions (count overdue))))
        meetings))


(defn escalate-overdue!
  "C09 逾期追溯升级: 对已逾期且尚未升级的未关闭问题追溯升级到经理层, 进入待独立确认, 提交解决须先确认."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "issue.escalated"
             (fn [q project]
               (s/input! body [:reason])
               (let [issue (s/record! q project "issue" rid)]
                 (s/status! issue #{"open" "rejected"})
                 (when (:escalated issue) (r/fail! 409 "问题已处于升级处置中"))
                 (when-not (and (:due_date issue) (not (.isAfter (LocalDate/parse (:due_date issue)) (LocalDate/now))))
                   (r/fail! 409 "问题尚未逾期, 不能追溯升级"))
                 (s/change! q project issue (:status issue)
                            {:escalated true :escalation_state "pending" :escalation_level "management"
                             :escalation_source "overdue_retroactive"
                             :escalation_reason (str "逾期追溯升级: " (s/optional-text! body :reason 500))
                             :escalated_by (:user_id actor)})))))


(defn issue-read-model
  "以服务器日期展示问题是否逾期未关闭, 标记阻断级严重度, 并给出剩余到期天数与临期提示供台账倒计时; 逾期且未升级时给出追溯升级建议."
  [issue]
  (let [closed? (= "closed" (:status issue))
        days (when-not closed? (days-until (:due_date issue)))
        overdue? (boolean (and (:due_date issue) (not closed?)
                               (not (.isAfter (LocalDate/parse (:due_date issue)) (LocalDate/now)))))]
    (assoc issue
           :issue_escalation_suggested (boolean (and overdue? (not (:escalated issue)) (contains? #{"open" "rejected"} (:status issue))))
           :issue_overdue overdue?
           :issue_critical (= "blocker" (:severity issue))
           :issue_due_in_days days
           :issue_due_soon (boolean (and (some? days) (<= 1 days due-soon-days))))))


(defn issue-escalation-disposition-summary
  "按每个问题最新有效版本只读聚合阻断级/逾期自动升级的独立确认处置情况: 升级总数与待确认/已确认/已豁免及 steering/management 分级计数; 只读派生, 不落库不投递, 不改变问题状态."
  [issues]
  (let [active (s/latest issues)
        escalated (filterv :escalated active)
        total (count active)
        esc-total (count escalated)
        state-count (fn [x] (count (filterv #(= x (:escalation_state %)) escalated)))
        level-count (fn [x] (count (filterv #(= x (:escalation_level %)) escalated)))]
    {:total total
     :escalated esc-total
     :not-escalated (- total esc-total)
     :pending (state-count "pending")
     :acknowledged (state-count "acknowledged")
     :waived (state-count "waived")
     :by-level (mapv (fn [x] {:level x :count (level-count x)}) ["steering" "management"])}))


(def owner-workload-threshold
  "同一责任人跨问题/风险/行动承担的未关闭事项数达到该值即视为负载过重."
  4)


(defn owner-workloads
  "跨问题/风险/行动统计每位责任人当前未关闭的事项数(问题与风险排除 closed, 行动排除 closed/converted), 供负载与过载预警. 只读派生不落库."
  [issues risks actions]
  (frequencies
    (keep :owner_id
          (concat
            (remove #(= "closed" (:status %)) issues)
            (remove #(= "closed" (:status %)) risks)
            (remove #(contains? #{"closed" "converted"} (:status %)) actions)))))


(defn owner-workload-read-model
  "为问题/风险/行动行补充其责任人跨类未关闭负载与是否过载; 只读计算不改状态, 无责任人则负载 0 且不过载."
  [loads row]
  (let [load (get loads (:owner_id row) 0)]
    (assoc row
           :owner_open_load load
           :owner_overloaded (boolean (and (:owner_id row) (>= load owner-workload-threshold))))))


(def due-overview-soon-days
  "跨类到期总览把未闭环事项到期日距今 1 到该天数视为临期(与逾期分档), 供组合级提前关注, 与逐条台账 due-soon-days 独立."
  7)

(def due-overview-horizon-days
  "跨类到期总览把未闭环事项到期日距今不超过该天数视为未来到期, 超过则归入更远期, 供近窗口聚焦."
  30)

(def due-overview-soonest-limit
  "跨类到期总览最近到期清单最多展示的条目数, 按剩余天数升序截断, 逾期(负值)自然排在最前."
  15)


(defn- due-overview-open?
  "跨类到期总览口径下判断某治理事项是否仍未闭环: 行动排除 closed/converted, 风险与问题排除 closed. 与 owner-workloads 逐类排除口径一致."
  [kind row]
  (if (= kind "action")
    (not (contains? #{"closed" "converted"} (:status row)))
    (not= "closed" (:status row))))


(defn- due-overview-date
  "跨类到期总览取某未闭环事项的到期日: 风险优先复审到期日否则登记到期日(与 reviews/risk-read-model 一致), 问题与行动取到期日."
  [kind row]
  (if (= kind "risk")
    (or (:review_due_date row) (:due_date row))
    (:due_date row)))


(defn- due-overview-bucket
  "按剩余天数把未闭环事项归入 overdue/due-soon/upcoming/further/undated 之一; 无到期日落 undated, 到期日不晚于今天落 overdue."
  [days]
  (cond
    (nil? days) :undated
    (<= days 0) :overdue
    (<= days due-overview-soon-days) :due-soon
    (<= days due-overview-horizon-days) :upcoming
    :else :further))


(defn due-workload-overview
  "跨风险/问题/行动只读聚合未闭环事项的到期压力总览: 全局分档已逾期/临期(<=due-overview-soon-days 天)/未来到期(<=due-overview-horizon-days 天)/更远期/无到期日与未闭环总数, 逐来源给出 open/overdue/due-soon/upcoming 计数, 另给按剩余天数升序(逾期在前)截断 due-overview-soonest-limit 的最近到期清单. 复用 days-until 与各来源自身到期/未闭环口径, 只读派生不落库不投递, 不构成门控. 键名不带尾随问号."
  [risks issues actions]
  (let [itemized (for [[kind rows] [["risk" risks] ["issue" issues] ["action" actions]]
                       row rows
                       :when (due-overview-open? kind row)
                       :let [due (due-overview-date kind row)
                             days (when (some? due) (days-until due))]]
                   {:source kind
                    :id (:id row)
                    :title (:title row)
                    :due-date due
                    :due-days days
                    :bucket (due-overview-bucket days)})
        grouped (group-by :source itemized)
        bucket-count (fn [b] (count (filterv #(= b (:bucket %)) itemized)))
        src-stats (fn [kind]
                    (let [items (get grouped kind [])]
                      {:open (count items)
                       :overdue (count (filterv #(= :overdue (:bucket %)) items))
                       :due-soon (count (filterv #(= :due-soon (:bucket %)) items))
                       :upcoming (count (filterv #(= :upcoming (:bucket %)) items))}))]
    {:available (pos? (count itemized))
     :open-total (count itemized)
     :overdue (bucket-count :overdue)
     :due-soon (bucket-count :due-soon)
     :upcoming (bucket-count :upcoming)
     :further (bucket-count :further)
     :undated (bucket-count :undated)
     :by-risk (src-stats "risk")
     :by-issue (src-stats "issue")
     :by-action (src-stats "action")
     :soonest (->> itemized
                   (remove #(nil? (:due-days %)))
                   (sort-by :due-days)
                   (take due-overview-soonest-limit)
                   (mapv #(select-keys % [:source :id :title :due-date :due-days])))}))


(def owner-pressure-overdue-weight
  "责任人到期压力热点把每条已逾期事项按该权重计入责任人压力指数, 逾期最痛故权重最高 (工程默认启发式, 仅用于只读排名不构成门控)."
  4)

(def owner-pressure-due-soon-weight
  "责任人到期压力热点把每条临期(7天内)事项按该权重计入责任人压力指数 (工程默认启发式, 仅用于只读排名)."
  2)

(def owner-pressure-upcoming-weight
  "责任人到期压力热点把每条未来到期(30天内)事项按该权重计入责任人压力指数 (工程默认启发式, 仅用于只读排名)."
  1)

(def owner-hotspot-limit
  "责任人到期压力热点按压力指数降序最多点名的责任人条数, 压力相同依次比较逾期数/临期数/未闭环总数/责任人ID升序保证确定性."
  10)


(defn owner-due-pressure
  "跨风险/问题/行动按责任人只读聚合其未闭环事项的到期压力热点: 复用 due-overview-open?/due-overview-date/days-until/due-overview-bucket 逐条分档, 按 owner_id 聚合 open/overdue/due-soon/upcoming/further/undated 与加权压力指数(逾期x4+临期x2+未来x1), 压力降序截断 owner-hotspot-limit 点名最热责任人, 无 owner_id 者单独计入 unassigned-total 不混入热点; owner-name 为 user_id->显示名映射缺失回退 用户<id>. 只读派生不落库不投递不构成门控. 键名不带尾随问号."
  [risks issues actions owner-name]
  (let [itemized (for [[kind rows] [["risk" risks] ["issue" issues] ["action" actions]]
                       row rows
                       :when (due-overview-open? kind row)
                       :let [due (due-overview-date kind row)
                             days (when (some? due) (days-until due))]]
                   {:owner (:owner_id row)
                    :bucket (due-overview-bucket days)})
        assigned (remove (comp nil? :owner) itemized)
        unassigned (count (filter (comp nil? :owner) itemized))
        tally (fn tally [items]
                (let [c (frequencies (map :bucket items))
                      t {:open (count items)
                         :overdue (get c :overdue 0)
                         :due-soon (get c :due-soon 0)
                         :upcoming (get c :upcoming 0)
                         :further (get c :further 0)
                         :undated (get c :undated 0)}]
                  (assoc t :pressure (+ (* owner-pressure-overdue-weight (:overdue t))
                                        (* owner-pressure-due-soon-weight (:due-soon t))
                                        (* owner-pressure-upcoming-weight (:upcoming t))))))
        by-owner-raw (for [[owner items] (group-by :owner assigned)]
                       (assoc (tally items)
                              :owner-id owner
                              :name (or (get owner-name owner) (str "用户" owner))))
        bucket-total (fn bucket-total [b] (count (filterv #(= b (:bucket %)) assigned)))
        ranked (->> by-owner-raw
                    (sort-by (fn ranked-key [m] [(- (:pressure m)) (- (:overdue m)) (- (:due-soon m)) (- (:open m)) (:owner-id m)]))
                    (take owner-hotspot-limit)
                    vec)]
    {:available (pos? (count assigned))
     :assigned-total (count assigned)
     :unassigned-total unassigned
     :owner-count (count (distinct (map :owner assigned)))
     :overdue (bucket-total :overdue)
     :due-soon (bucket-total :due-soon)
     :upcoming (bucket-total :upcoming)
     :further (bucket-total :further)
     :undated (bucket-total :undated)
     :owners-with-overdue (count (filterv #(pos? (:overdue %)) by-owner-raw))
     :by-owner ranked
     :hottest (first ranked)}))


(def meeting-attendance-type-order
  "会议参会概览按会议类型固定呈现顺序 (与 meeting-types 枚举一致), 仅列出实际出现的类型, 保证分布一行回显顺序确定不随 frequencies 漂移."
  ["regular" "kickoff" "review" "fat-kickoff" "fat-summary"])


(def meeting-attendance-top-limit
  "会议参会概览出勤排行最多点名的参会人数上限, 参会次数相同依次比较显示名再用户ID升序保证确定性 (仅用于只读排名不构成门控)."
  10)


(defn meeting-attendance-summary
  "按每个会议最新有效版本只读聚合参会覆盖与出勤分布: 以未作废会议为分母, 统计平均每场参会人数(avg-attendance), 按 attendee_ids 汇总各人参会次数给出出勤排行(top-attendees, 次数降序截断上限), 按 meeting_type 回显会议类型分布(by-type, 仅实际出现类型); 另以项目成员为口径计算参会覆盖率(至少出席过一次会议的成员占比 attended-members/member-count -> coverage-pct)并点名从未参会的成员(unattended-members). attendee_ids 是经 s/user! 校验的用户ID(可能含非项目成员), 故出勤排行覆盖全部出席者(非成员显示名回退 用户<id>), 而覆盖与未参会仅统计项目成员集合; 只读派生, 不落库不投递, 不改变会议或成员状态, 不构成门控(会议出席本身无门控). 键名一律不带尾随问号."
  [meetings members]
  (let [active (s/latest meetings)
        discarded (count (filterv #(= "discarded" (:status %)) active))
        in-scope (filterv #(not= "discarded" (:status %)) active)
        total (count in-scope)
        member-name (into {} (map (fn [m] [(:user_id m)
                                           (or (not-empty (:nick_name m))
                                               (not-empty (:user_name m))
                                               (str "用户" (:user_id m)))]))
                         members)
        member-ids (into #{} (map :user_id) members)
        name-of (fn [uid] (or (get member-name uid) (str "用户" uid)))
        attendees (mapcat (fn [m] (distinct (:attendee_ids m))) in-scope)
        pct (fn pct [n d] (if (pos? d) (int (Math/round ^double (* 100.0 (/ n d)))) 0))
        freq (frequencies attendees)
        top (->> (for [[uid c] freq]
                   {:user-id uid :name (name-of uid) :attended c :attendance-pct (pct c total)})
                 (sort-by (fn [m] [(- (:attended m)) (:name m) (:user-id m)]))
                 (take meeting-attendance-top-limit)
                 vec)
        attended-member-ids (into #{} (filter member-ids) attendees)
        unattended (->> (remove #(attended-member-ids (:user_id %)) members)
                        (map (fn [m] {:user-id (:user_id m) :name (get member-name (:user_id m))}))
                        (sort-by (fn [m] [(:name m) (:user-id m)]))
                        vec)
        type-freq (frequencies (map :meeting_type in-scope))
        by-type (mapv (fn [t] {:type t :count (get type-freq t 0)})
                      (filter #(contains? type-freq %) meeting-attendance-type-order))]
    {:available (pos? total)
     :total total
     :discarded discarded
     :member-count (count member-ids)
     :attended-members (count attended-member-ids)
     :coverage-pct (pct (count attended-member-ids) (count member-ids))
     :avg-attendance (if (pos? total) (int (Math/round ^double (/ (count attendees) total))) 0)
     :by-type by-type
     :top-attendees top
     :unattended-members unattended}))


(def meeting-cadence-weekday-order
  "会议节奏按星期固定呈现顺序 (周一到周日), 与 java.time.DayOfWeek getValue 1..7 对齐, 保证分布一行回显顺序确定不随 frequencies 漂移 (仅计实际出现的星期)."
  ["monday" "tuesday" "wednesday" "thursday" "friday" "saturday" "sunday"])


(defn meeting-cadence-summary
  "按每个会议最新有效版本只读聚合会议节奏与间隔分布: 以未作废且带举办日期 held_on 的会议为口径, 按 held_on 升序给出首末会议日期(first-held/last-held)与跨天数(span-days, 不足两场计 0), 相邻两次会议间隔的最小/平均/最大天数(shortest/avg/longest-gap-days, 同日举办计 0, 不足两场无间隔故均计 0), 按自然月 YYYY-MM 回显逐月会议数(by-month 月份升序)与最热月份(busiest-month, 会议数相同取更早月)及跨月数(distinct-months), 并按星期(周一到周日固定序)回显分布(by-weekday, 仅列实际出现的星期); 会议以 code 形成修订链经 store/latest 折叠只计最新版, 作废最新版即退出全部时间口径; 只读派生, 不落库不投递, 不改变会议状态, 不构成门控(会议节奏本身无门控). 键名一律不带尾随问号."
  [meetings]
  (let [active (s/latest meetings)
        discarded (count (filterv #(= "discarded" (:status %)) active))
        dated (filterv (fn [m] (and (not= "discarded" (:status m))
                                    (some? (:held_on m)))) active)
        held-strs (sort (map :held_on dated))
        total (count held-strs)
        days (mapv #(.toEpochDay (LocalDate/parse %)) held-strs)
        gaps (map - (drop 1 days) (butlast days))
        month-of (fn [h] (subs h 0 7))
        month-freq (frequencies (map month-of held-strs))
        by-month (mapv (fn [mo] {:month mo :count (get month-freq mo 0)})
                       (sort (keys month-freq)))
        busiest (when (seq month-freq)
                  (ffirst (sort-by (fn [[mo c]] [(- c) mo]) month-freq)))
        weekday-of (fn [h] (nth meeting-cadence-weekday-order
                                (dec (.getValue (.getDayOfWeek (LocalDate/parse h))))))
        weekday-freq (frequencies (map weekday-of held-strs))
        by-weekday (mapv (fn [w] {:weekday w :count (get weekday-freq w 0)})
                         (filter #(contains? weekday-freq %) meeting-cadence-weekday-order))]
    {:available (pos? total)
     :total total
     :discarded discarded
     :first-held (first held-strs)
     :last-held (last held-strs)
     :span-days (if (< 1 total) (- (peek days) (first days)) 0)
     :distinct-months (count month-freq)
     :shortest-gap-days (if (seq gaps) (apply min gaps) 0)
     :longest-gap-days (if (seq gaps) (apply max gaps) 0)
     :avg-gap-days (if (seq gaps) (int (Math/round ^double (/ (double (apply + gaps)) (count gaps)))) 0)
     :busiest-month (when busiest {:month busiest :count (get month-freq busiest 0)})
     :by-month by-month
     :by-weekday by-weekday}))


(def meeting-type-order
  "会议类型分布按固定五档呈现顺序 (与 meeting-types 枚举及 meeting-attendance-type-order 一致), 保证分布一行回显顺序确定不随 frequencies 漂移; 未出现的档仍恒渲染为 0."
  ["regular" "kickoff" "review" "fat-kickoff" "fat-summary"])


(def meeting-type-labels
  "会议类型到中文标签的映射 (与前端台账类型列口径一致), 供只读汇总面板展示."
  {"regular" "例会" "kickoff" "启动会" "review" "评审会" "fat-kickoff" "FAT启动" "fat-summary" "FAT总结"})


(defn meeting-type-distribution
  "按每个会议业务编码最新有效版本(store/latest 折叠修订链)统计其会议类型(:meeting_type)的项目级只读分布: 按固定五档(regular/kickoff/review/fat-kickoff/fat-summary)给出各类型会议数与该类型占会议总数百分比, 并给出会议总数/已覆盖类型数/未覆盖类型数/类型覆盖率与主导类型(会议数最多者). 最新版本被受控作废(discarded)的会议不计入. meeting_type 由 create-meeting!/修订经 s/enum! 校验取自 meeting-types 枚举且缺省 regular, 故每档计数之和恒等于会议总数(与干系人类别/权力-利益象限分布同族, 都是必填受控枚举; 区别于可选的参与态度/渠道使用分布会有未设定档). 已覆盖类型数等于登记中实际出现的不同会议类型档数, 未覆盖档数=5-已覆盖(五档固定故不变量 covered+uncovered=5), 覆盖率=round(100*已覆盖/5)恒取整为0/20/40/60/80/100; 逐档占比之和等于 total. dominant 用 max-key 扫固定 meeting-type-order(并列取扫描靠后者不承诺唯一), 会议总数为 0 时为 nil. 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控; 与会议参会概览(by-type 仅列实际出现类型无覆盖率与主导项)/会议节奏与间隔分布互补(参会看出席人, 节奏看时间间隔, 本项看项目会议在类型维度的构成/覆盖与主导, 回答会议是否覆盖了启动会/评审会/FAT 等关键节点类型)."
  [meetings]
  (let [active (filterv #(not= "discarded" (:status %)) (s/latest meetings))
        total (count active)
        tally (frequencies (map :meeting_type active))
        pct (fn [n] (if (pos? total) (int (Math/round ^double (* 100.0 (/ n total)))) 0))
        by-type (mapv (fn [t] {:type t
                               :label (get meeting-type-labels t t)
                               :count (get tally t 0)
                               :pct (pct (get tally t 0))})
                     meeting-type-order)
        covered (count (filterv pos? (map :count by-type)))
        dominant (when (pos? total)
                   (apply max-key (fn [t] (get tally t 0)) meeting-type-order))
        dominant-count (if dominant (get tally dominant 0) 0)]
    {:available (pos? total)
     :total total
     :covered covered
     :uncovered (- (count meeting-type-order) covered)
     :coverage-pct (int (Math/round ^double (* 100.0 (/ covered (count meeting-type-order)))))
     :dominant-type dominant
     :dominant-count dominant-count
     :by-type by-type}))


(defn enrich-risk-issue-links
  "读取时把已持久化的风险<->问题双向来源关联互相标注对方标题, 供台账可见; 只读派生不落库.
   issue.source_risk_id -> issue_source_risk_id/issue_source_risk_title; risk.issue_id -> risk_issue_id/risk_issue_title; 对端记录缺失时标题为 nil."
  [data]
  (let [risk-by-id (into {} (map (juxt :id identity)) (:risks data))
        issue-by-id (into {} (map (juxt :id identity)) (:issues data))]
    (-> data
        (update :issues #(mapv (fn [issue]
                                 (if-let [rid (:source_risk_id issue)]
                                   (assoc issue :issue_source_risk_id rid
                                                  :issue_source_risk_title (:title (get risk-by-id rid)))
                                   issue))
                               %))
        (update :risks #(mapv (fn [risk]
                                (if-let [iid (:issue_id risk)]
                                  (assoc risk :risk_issue_id iid
                                                 :risk_issue_title (:title (get issue-by-id iid)))
                                  risk))
                              %)))))


(defn enrich-action-source-links
  "读取时把预防行动项持久化的来源风险关联标注对方标题, 供行动台账可见; 只读派生不落库.
   action.source_risk_id -> action_source_risk_id/action_source_risk_title; 来源风险缺失时标题为 nil."
  [data]
  (let [risk-by-id (into {} (map (juxt :id identity)) (:risks data))]
    (update data :actions
            #(mapv (fn [action]
                     (if-let [rid (:source_risk_id action)]
                       (assoc action :action_source_risk_id rid
                                      :action_source_risk_title (:title (get risk-by-id rid)))
                       action))
                   %))))


(defn mitigation-rollup-by-risk
  "按预防行动项持久化的 source_risk_id 反向聚合每个风险派生的预防措施行动总数/未完成数(排除 closed/converted)/逾期未完成数, 供风险台账只读呈现措施落实情况. 只读派生不落库."
  [actions]
  (reduce (fn [acc action]
            (if-let [rid (:source_risk_id action)]
              (let [done? (contains? #{"closed" "converted"} (:status action))
                    overdue? (action-overdue? action)]
                (update acc rid (fn [{:keys [total open overdue]}]
                                  {:total (inc (or total 0))
                                   :open (if done? (or open 0) (inc (or open 0)))
                                   :overdue (if overdue? (inc (or overdue 0)) (or overdue 0))})))
              acc))
          {}
          actions))


(defn mitigation-read-model
  "在风险记录上追加只读派生键: mitigation_action_total/open/overdue 为该风险派生的预防行动总数/未完成数(排除 closed/converted)/其中逾期未完成数, mitigation_action_state 取 unimplemented(尚未落实) / in-progress(落实中) / completed(全部落实). 登记风险必填应对措施, 故不再单列无措施态. 键名不带尾随问号."
  [rollup risk]
  (let [{:keys [total open overdue]} (get rollup (:id risk) {:total 0 :open 0 :overdue 0})
        state (cond
                (zero? total) "unimplemented"
                (pos? open) "in-progress"
                :else "completed")]
    (assoc risk :mitigation_action_total total
                 :mitigation_action_open open
                 :mitigation_action_overdue overdue
                 :mitigation_action_state state)))


(defn remediation-rollup-by-dq
  "按整改行动项持久化的 source_dq_id 反向聚合每个 DQ 派生的整改行动总数/未完成数(排除 closed/converted)/其中逾期未完成数, 供 DQ 台账只读呈现整改落实情况. 只读派生不落库."
  [actions]
  (reduce (fn [acc action]
            (if-let [dqid (:source_dq_id action)]
              (let [done? (contains? #{"closed" "converted"} (:status action))
                    overdue? (action-overdue? action)]
                (update acc dqid (fn [{:keys [total open overdue]}]
                                  {:total (inc (or total 0))
                                   :open (if done? (or open 0) (inc (or open 0)))
                                   :overdue (if overdue? (inc (or overdue 0)) (or overdue 0))})))
              acc))
          {}
          actions))


(defn dq-remediation-read-model
  "在 DQ 记录上追加只读派生键: dq_remediation_total/open/overdue 为该 DQ 未通过必需检查项派生的整改行动总数/未完成数(排除 closed/converted)/其中逾期未完成数, dq_remediation_state 取 unremediated(尚未落实整改) / in-progress(整改中) / completed(全部整改完成). 只读派生不落库, 键名不带尾随问号."
  [rollup dq]
  (let [{:keys [total open overdue]} (get rollup (:id dq) {:total 0 :open 0 :overdue 0})
        state (cond
                (zero? total) "unremediated"
                (pos? open) "in-progress"
                :else "completed")]
    (assoc dq :dq_remediation_total total
                :dq_remediation_open open
                :dq_remediation_overdue overdue
                :dq_remediation_state state)))


(defn remediation-rollup-by-gate
  "按整改行动项持久化的 source_gate_id 反向聚合每个关口实例派生的整改行动总数/未完成数(排除 closed/converted)/其中逾期未完成数, 供关口台账只读呈现整改落实情况. 只读派生不落库."
  [actions]
  (reduce (fn [acc action]
            (if-let [gid (:source_gate_id action)]
              (let [done? (contains? #{"closed" "converted"} (:status action))
                    overdue? (action-overdue? action)]
                (update acc gid (fn [{:keys [total open overdue]}]
                                  {:total (inc (or total 0))
                                   :open (if done? (or open 0) (inc (or open 0)))
                                   :overdue (if overdue? (inc (or overdue 0)) (or overdue 0))})))
              acc))
          {}
          actions))


(defn gate-remediation-read-model
  "在关口实例记录上追加只读派生键: gate_remediation_total/open/overdue 为该关口未通过必需检查项派生的整改行动总数/未完成数(排除 closed/converted)/其中逾期未完成数, gate_remediation_state 取 unremediated(尚未落实整改) / in-progress(整改中) / completed(全部整改完成). 只读派生不落库, 键名不带尾随问号."
  [rollup gate]
  (let [{:keys [total open overdue]} (get rollup (:id gate) {:total 0 :open 0 :overdue 0})
        state (cond
                (zero? total) "unremediated"
                (pos? open) "in-progress"
                :else "completed")]
    (assoc gate :gate_remediation_total total
                  :gate_remediation_open open
                  :gate_remediation_overdue overdue
                  :gate_remediation_state state)))


(defn- issue-remediation-overdue?
  "试验不合格自动生成的整改问题: 存在到期日, 未 closed 且到期日不晚于服务器当天即视为逾期未闭环. 与 fieldwork 试验整改 rollup 的到期口径一致, 免疫 today 漂移."
  [issue]
  (boolean (and (:due_date issue)
                (not= "closed" (:status issue))
                (not (.isAfter (LocalDate/parse (:due_date issue)) (LocalDate/now))))))


(defn project-remediation-overview
  "跨对象项目级未闭环整改总览 (只读派生, 免迁移): 把项目内五类整改来源的未完成闭环情况汇总到一处——
   试验 (试验必检不合格自动生成的阻断问题, issue 带 source_test_id, 未 closed 即未完成),
   关口 (关口未通过必需检查项落实的整改行动, action 带 source_gate_id),
   质量 (DQ 未通过必需检查项落实的整改行动, action 带 source_dq_id),
   风险 (风险预防措施落实的行动, action 带 source_risk_id),
   绩效 (挣值偏差登记的纠正措施, action 带 variance_kind);
   逐来源给出 总数 total / 未完成 open / 已完成 closed / 其中逾期未完成 overdue 与闭环率 closure-pct, 另给全局合计,
   含整改项的来源类别数 sources-with-remediation 与仍有未完成整改的来源类别数 sources-with-open, by-source 仅列有整改项的来源.
   试验来源问题状态会堆叠版本故先按编码取最新有效版本再计 (与 remediation-rollup-by-test 口径一致), 行动为单条活记录直接计 (与各行动台账既有 rollup 一致);
   closed/converted 视为行动完成, closed 视为问题完成; 逾期仅对未完成条目成立故 overdue 永不大于 open.
   与各来源台账的逐项整改视图互补 (前者看单一对象是否落实整改, 本项看项目全局还欠多少整改未闭环), 是整改这一治理对象的跨对象只读投影.
   读取时派生, 不落库不投递, 不构成任何门控 (真正的门控仍由各写路径在提交/签核时执行), 键名不带尾随问号."
  [actions issues]
  (let [test-issues  (filter :source_test_id (s/latest issues))
        groups       [{:key "test" :label "试验不合格整改" :records test-issues :issue? true}
                      {:key "gate" :label "关口检查整改" :records (filter :source_gate_id actions)}
                      {:key "dq" :label "质量检查整改" :records (filter :source_dq_id actions)}
                      {:key "risk" :label "风险预防整改" :records (filter :source_risk_id actions)}
                      {:key "variance" :label "绩效偏差纠正" :records (filter :variance_kind actions)}]
        action-done? #(contains? #{"closed" "converted"} (:status %))
        issue-done?  #(= "closed" (:status %))
        tally        (fn [{:keys [key label records issue?]}]
                       (let [done?   (if issue? issue-done? action-done?)
                             over?   (if issue? issue-remediation-overdue? action-overdue?)
                             total   (count records)
                             closed  (count (filter done? records))
                             open    (- total closed)
                             overdue (count (filter over? records))]
                         {:key key :label label :total total :open open :closed closed :overdue overdue
                          :closure-pct (if (pos? total)
                                         (int (Math/round ^double (* 100.0 (/ closed total))))
                                         0)}))
        tallies      (mapv tally groups)
        present      (filterv #(pos? (:total %)) tallies)
        o-total      (reduce + 0 (map :total tallies))
        o-closed     (reduce + 0 (map :closed tallies))
        o-overdue    (reduce + 0 (map :overdue tallies))
        o-open       (- o-total o-closed)]
    {:available                (pos? o-total)
     :total                    o-total
     :open                     o-open
     :closed                   o-closed
     :overdue                  o-overdue
     :sources-with-remediation (count present)
     :sources-with-open         (count (filterv #(pos? (:open %)) present))
     :closure-pct              (if (pos? o-total)
                                 (int (Math/round ^double (* 100.0 (/ o-closed o-total))))
                                 0)
     :by-source                present}))


(defn action-closure-summary
  "按全部会议与预防行动项只读聚合闭环情况: 总数/已闭环(closed 或 converted)/未完成/其中逾期未完成/转任务数与闭环率; 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [actions]
  (let [total (count actions)
        closed (count (filterv #(contains? #{"closed" "converted"} (:status %)) actions))
        converted (count (filterv #(= "converted" (:status %)) actions))
        overdue (count (filterv action-overdue? actions))]
    {:total total
     :closed closed
     :open (- total closed)
     :converted converted
     :overdue overdue
     :closure-pct (if (pos? total)
                    (int (Math/round ^double (* 100.0 (/ closed total))))
                    0)}))


(defn action-priority-distribution
  "按全部会议行动项只读聚合优先级申报分布: 以 action-priorities(高/中/低)为固定档位, 逐档统计 总数/未完成(排除 closed 与 converted)/其中逾期未完成, 另给已声明优先级数(落在三档内)与申报覆盖率, 未设定优先级数, 以及高优先级未完成数; 各档计数之和等于已声明数, 已声明与未设定之和等于总数, 逾期数不超过未完成数; 只读派生, 不落库不投递, 不构成门控, 键名不带尾随问号."
  [actions]
  (let [total (count actions)
        done? #(contains? #{"closed" "converted"} (:status %))
        of-priority (fn [p] (filterv #(= p (:priority %)) actions))
        declared (count (filterv #(some #{(:priority %)} action-priorities) actions))
        unassigned (- total declared)
        entry (fn [p] (let [rs (of-priority p)]
                        {:priority p
                         :count (count rs)
                         :open (count (remove done? rs))
                         :overdue (count (filterv action-overdue? rs))}))
        open-high (count (remove done? (of-priority "high")))]
    {:available (pos? total)
     :total total
     :declared declared
     :unassigned unassigned
     :declared-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :open-high open-high
     :by-priority (mapv entry ["high" "medium" "low"])}))


(defn meeting-release-coverage
  "按每个会议最新有效版本只读聚合纪要发布进度: 会议总数/已发布/审批中/草稿/已作废及发布率(分母排除已作废); 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [meetings]
  (let [active (s/latest meetings)
        total (count active)
        status-count (fn [x] (count (filterv #(= x (:status %)) active)))
        approved (status-count "approved")
        in-review (status-count "in_review")
        recorded (status-count "recorded")
        discarded (status-count "discarded")
        denom (- total discarded)]
    {:total total
     :approved approved
     :in-review in-review
     :recorded recorded
     :discarded discarded
     :release-pct (if (pos? denom)
                    (int (Math/round ^double (* 100.0 (/ approved denom))))
                    0)}))


(defn meeting-material-readiness
  "按每个会议最新有效版本只读聚合会前预读材料准备就绪度: 以未作废会议数为分母, 统计已挂至少一份会前资料(material_ids 非空)者(with-materials)与尚未准备任何会前资料者(without-materials); 进一步就\"已挂资料是否全部指向已发布(approved)证据文档\"分档——所挂资料全部已发布者为 materials-published, 含未发布(草稿/审批中/已驳回)或引用已失效旧版本者为 materials-pending; 给出预读覆盖率 readiness-pct(=已挂资料/分母)与完全就绪率 full-readiness-pct(=资料齐备且全部已发布/分母), 并列出未准备会议与含待发布资料会议(附未发布份数)清单; 只读派生, 不落库不投递, 不构成门控(启动会会前包强制关联仍由 create-meeting! 门控), 不改变任何不可变版本. 键名不带尾随问号."
  [meetings docs-by-id]
  (let [active (s/latest meetings)
        discarded (count (filterv #(= "discarded" (:status %)) active))
        in-scope (remove #(= "discarded" (:status %)) active)
        denom (count in-scope)
        with-mat (filterv #(seq (:material_ids %)) in-scope)
        without-mat (count (remove #(seq (:material_ids %)) in-scope))
        approved-doc? (fn [id] (= "approved" (:status (get docs-by-id id))))
        pending-ids (fn [m] (filterv (complement approved-doc?) (:material_ids m)))
        published (count (filterv #(empty? (pending-ids %)) with-mat))
        pending (count (filterv #(seq (pending-ids %)) with-mat))
        pct (fn [n] (if (pos? denom)
                      (int (Math/round ^double (* 100.0 (/ n denom))))
                      0))]
    {:available (pos? denom)
     :total denom
     :discarded discarded
     :with-materials (count with-mat)
     :without-materials without-mat
     :readiness-pct (pct (count with-mat))
     :materials-published published
     :materials-pending pending
     :full-readiness-pct (pct published)
     :unprepared-meetings (mapv #(select-keys % [:code :title])
                                (sort-by :code (remove #(seq (:material_ids %)) in-scope)))
     :pending-material-meetings (mapv (fn [m] {:code (:code m) :title (:title m)
                                               :pending-count (count (pending-ids m))})
                                      (sort-by :code (filterv #(seq (pending-ids %)) with-mat)))}))


(defn issue-closure-summary
  "按全部问题最新有效版本只读聚合闭环健康度: 总数/已闭环/未关闭(待处理, 已驳回, 待验证)/逾期未关闭/未关闭阻断级与各严重度分布及闭环率; 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [issues]
  (let [active (s/latest issues)
        total (count active)
        closed (count (filterv #(= "closed" (:status %)) active))
        status-count (fn [x] (count (filterv #(= x (:status %)) active)))
        severity-count (fn [x] (count (filterv #(= x (:severity %)) active)))
        overdue (count (filterv :issue_overdue active))
        blocker-open (count (filterv #(and (= "blocker" (:severity %)) (not= "closed" (:status %))) active))]
    {:available (pos? total)
     :total total
     :closed closed
     :open (- total closed)
     :pending (status-count "open")
     :rejected (status-count "rejected")
     :in-review (status-count "in_review")
     :overdue overdue
     :blocker-open blocker-open
     :by-severity (mapv (fn [x] {:severity x :count (severity-count x)}) ["blocker" "major" "minor"])
     :closure-pct (if (pos? total)
                    (int (Math/round ^double (* 100.0 (/ closed total))))
                    0)}))


(defn issue-resolution-coverage
  "按全部问题最新有效版本只读统计解决方式(Issue 处置类型)声明覆盖度: 六类解决方式各自计数, 已声明/未设定与覆盖率; 只读派生, 不落库不投递, 不构成门控. 键名不带尾随问号."
  [issues]
  (let [types ["fixed" "workaround" "by-design" "duplicate" "cannot-reproduce" "wont-fix"]
        active (s/latest issues)
        total (count active)
        declared (count (filterv #(some #{(:resolution_type %)} types) active))
        type-count (fn [x] (count (filterv #(= x (:resolution_type %)) active)))]
    {:total total
     :declared declared
     :undeclared (- total declared)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :by-resolution (mapv (fn [x] {:resolution x :count (type-count x)}) types)}))


(defn attach-issue-closure-summary
  "把 issue-closure-summary 挂到治理工作区顶层 :issue_closure; 读取时派生, 不改变任何逐条问题记录."
  [data]
  (assoc data :issue_closure (issue-closure-summary (:issues data))))
