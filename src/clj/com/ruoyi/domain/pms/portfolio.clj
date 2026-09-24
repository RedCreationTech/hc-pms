(ns com.ruoyi.domain.pms.portfolio
  "跨项目只读派生: 我的待办 (C07/C09 本地提醒, 不投递外部消息), 全局检索 (G16, 按项目权限与密级过滤),
   项目组合看板 (G02, 主/子/单机进度/齐套/试验/问题/成本下钻) 与经营目标达成 (F09).
   全部读取时计算, 不落库, 仅覆盖当前用户可访问的项目."
  (:require [clojure.string :as str]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.delivery.materials :as materials]
            [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.governance.gates :as gates]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.planning.progress :as progress]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(defn authorized-projects
  "当前用户可见的全部项目 (管理员/经理/成员范围), 不分页."
  [q actor]
  (vec (q :pms/authorized-projects (r/access-params actor))))

(defn- days-until
  [today date]
  (when (seq date) (.between ChronoUnit/DAYS today (LocalDate/parse date))))

(defn- project-ref
  [project]
  (select-keys project [:project_id :project_no :name :status]))

;; ── 我的待办 ────────────────────────────────────────────────────

(def review-kinds
  "各类待独立审批对象及其所在工作台页签."
  {"charter" ["章程" "需求与治理"] "change" ["变更" "需求与治理"] "document" ["文档发布" "需求与治理"]
   "gate" ["Gate评审" "需求与治理"] "risk" ["风险复评" "需求与治理"] "issue" ["问题验证" "需求与治理"]
   "action" ["行动核验" "需求与治理"] "dq" ["DQ签认" "需求与治理"]
   "material" ["备料审批" "工程交付"] "bom" ["BOM冻结" "工程交付"] "assembly" ["装配交检" "工程交付"]
   "test" ["试验评审" "工程交付"] "shipment" ["发运放行/签收" "工程交付"] "service" ["售后验证" "工程交付"]
   "survey" ["工勘确认" "工程交付"]})

(defn- item
  [project today kind label tab record due]
  (let [days (days-until today due)]
    {:project_id (:project_id project) :project_no (:project_no project) :project_name (:name project)
     :kind kind :label label :tab tab :id (:id record) :code (:code record)
     :title (or (:title record) (:text record) (:code record)) :status (:status record)
     :due_date due :days days :overdue (boolean (and (some? days) (neg? days)))
     :due_soon (boolean (and (some? days) (<= 0 days 3)))}))

(defn todo
  "汇总待我审批, 待我确认升级, 我负责的到期/逾期事项与交底/现场时限, 只读派生供本地提醒."
  [svc actor]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (let [q (:query-fn svc) uid (:user_id actor) today (LocalDate/now)
        approver? (or (:admin? actor) (contains? (:permissions actor) "pms:quality:approve"))
        items (for [project (authorized-projects q actor)
                    :when (not (contains? #{"closed" "cancelled"} (:status project)))
                    :let [gov (mapcat #(g/records q project %) (keys (select-keys review-kinds ["charter" "change" "document" "gate" "risk" "issue" "action" "dq"])))
                          del (mapcat #(d/records q project %) ["material" "bom" "assembly" "test" "shipment" "service" "survey" "handover" "site-task"])
                          times (q :finance/times {:project_id (:project_id project)})]]
                (concat
                  (for [rec (concat gov del) :when (and (= "in_review" (:status rec)) (= uid (:reviewer_id rec)) (not= uid (:submitted_by rec)))
                        :let [[label tab] (get review-kinds (:kind rec) ["审批" "需求与治理"])]]
                    (assoc (item project today "review" label tab rec (:due_date rec)) :group "reviews"))
                  (for [rec del :when (and (contains? #{"shipped" "conditional" "returned"} (:status rec)) (= "shipment" (:kind rec)) (= uid (:reviewer_id rec)))]
                    (assoc (item project today "review" "签收验证" "工程交付" rec nil) :group "reviews"))
                  (for [t times :when (and (= "submitted" (:status t)) (= uid (:reviewer_id t)))]
                    (assoc (item project today "time" "工时审核" "实际工时" {:id (:entry_id t) :code (:work_date t) :title (str (:user_name t) " " (money/hours (:minutes t)) "h") :status (:status t)} nil) :group "reviews"))
                  (for [rec gov :when (and approver? (:escalated rec) (= "pending" (:escalation_state rec)) (not= uid (:created_by rec)) (not= "closed" (:status rec)))]
                    (assoc (item project today "escalation" (if (= "risk" (:kind rec)) "风险升级确认" "问题升级确认") "需求与治理" rec (:due_date rec)) :group "escalations"))
                  (for [rec gov :when (and (= uid (:owner_id rec)) (contains? #{"issue" "action" "risk"} (:kind rec))
                                           (not (contains? #{"closed" "converted" "discarded"} (:status rec))))]
                    (assoc (item project today (:kind rec) (get {"issue" "我负责的问题" "action" "我负责的行动" "risk" "我负责的风险"} (:kind rec)) "需求与治理" rec
                                 (or (:review_due_date rec) (:due_date rec))) :group "owned"))
                  (for [rec del :when (and (= uid (:owner_id rec)) (= "handover" (:kind rec)) (= "open" (:status rec)))]
                    (assoc (item project today "handover" "交底截止" "工程交付" rec (:deadline rec)) :group "owned"))
                  (for [rec del :when (and (= uid (:owner_id rec)) (= "site-task" (:kind rec)) (= "draft" (:status rec)))]
                    (assoc (item project today "site-task" "现场任务计划开始" "工程交付" rec (:planned_start rec)) :group "owned"))))
        all (vec (apply concat items))]
    {:reviews (filterv #(= "reviews" (:group %)) all)
     :escalations (filterv #(= "escalations" (:group %)) all)
     :owned (sort-by (fn [i] [(if (:overdue i) 0 1) (or (:days i) 9999)]) (filterv #(= "owned" (:group %)) all))
     :summary {:reviews (count (filter #(= "reviews" (:group %)) all))
               :escalations (count (filter #(= "escalations" (:group %)) all))
               :owned (count (filter #(= "owned" (:group %)) all))
               :overdue (count (filter :overdue all))
               :due_soon (count (filter :due_soon all))}
     :delivery_status "local_only" :generated_at (str today)}))

;; ── 全局检索 ────────────────────────────────────────────────────

(def search-kinds
  {"requirement" ["URS需求" "需求与治理"] "document" ["证据文档" "需求与治理"] "risk" ["风险" "需求与治理"] "issue" ["问题" "需求与治理"]
   "meeting" ["会议" "需求与治理"] "action" ["行动" "需求与治理"] "change" ["变更" "需求与治理"] "charter" ["章程" "需求与治理"]
   "gate" ["Gate" "需求与治理"] "gate-template" ["Gate模板" "需求与治理"] "stakeholder" ["干系人" "需求与治理"] "dq" ["DQ" "需求与治理"]
   "material" ["备料申请" "工程交付"] "bom" ["BOM" "工程交付"] "assembly" ["装配" "工程交付"] "test" ["试验" "工程交付"]
   "shipment" ["发运" "工程交付"] "service" ["售后" "工程交付"] "survey" ["工勘" "工程交付"] "handover" ["交底" "工程交付"] "site-task" ["现场任务" "工程交付"]})

(defn- matches?
  [needle record]
  (let [hay (str/lower-case (str/join " " (remove nil? (map #(get record %) [:code :title :text :name :wbs_code :reason :minutes :description :deliverable]))))]
    (str/includes? hay needle)))

(defn search
  "按项目权限检索项目/任务/治理/交付对象; 机密文档仅对具备密级权限者可见; 结果回链到项目页签, 不返回正文."
  [svc actor params]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (let [q (:query-fn svc)
        needle (str/lower-case (r/text! (:q params) "检索内容" 200 true))
        classification (not-empty (r/text! (:classification params) "密级" 20 false))
        confidential? (or (:admin? actor) (contains? (:permissions actor) "*:*:*") (contains? (:permissions actor) "pms:document:confidential"))
        projects (authorized-projects q actor)
        results (for [project projects
                      :let [tasks (map #(assoc % :kind "task") (q :planning/tasks {:project_id (:project_id project)}))
                            gov (mapcat #(g/records q project %) (keys (select-keys search-kinds ["requirement" "document" "risk" "issue" "meeting" "action" "change" "charter" "gate" "gate-template" "stakeholder" "dq"])))
                            del (mapcat #(d/records q project %) ["material" "bom" "assembly" "test" "shipment" "service" "survey" "handover" "site-task"])]
                      rec (concat (when (matches? needle project) [(assoc project :kind "project" :code (:project_no project) :title (:name project))]) tasks gov del)
                      :when (and (matches? needle rec)
                                 (or (not= "document" (:kind rec)) (and (or confidential? (not= "confidential" (:classification rec)))
                                                                        (or (nil? classification) (= classification (:classification rec))))))
                      :let [[label tab] (get search-kinds (:kind rec) (if (= "task" (:kind rec)) ["WBS任务" "计划与执行"] ["项目" "项目概况"]))]]
                  {:project_id (:project_id project) :project_no (:project_no project) :project_name (:name project)
                   :kind (:kind rec) :label label :tab tab :id (or (:id rec) (:task_id rec) (:project_id rec))
                   :code (or (:code rec) (:wbs_code rec)) :title (or (:title rec) (:name rec) (:text rec))
                   :revision (:revision rec) :status (:status rec) :classification (:classification rec)})]
    {:q needle :count (count (take 200 results)) :truncated (> (count results) 200)
     :results (vec (take 200 results)) :confidential_visible confidential?}))

;; ── 项目组合看板 ────────────────────────────────────────────────

(defn- finance-summary
  [q actor project]
  (when (or (:admin? actor) (contains? (:permissions actor) "pms:finance:query"))
    (let [versions (mapv #(cost/dto q %) (q :finance/versions {:project_id (:project_id project)}))
          latest (fn [kind] (->> versions (filter #(and (= kind (:kind %)) (= "approved" (:status %)))) (sort-by :version_no >) first))
          budget (latest "budget") actual (latest "actual") settlement (latest "settlement") estimate (latest "estimate")]
      {:currency (:currency (or settlement actual budget estimate))
       :estimate (:total estimate) :budget (:total budget) :actual (:total actual) :settlement (:total settlement)
       :revenue (:revenue (or settlement actual budget estimate)) :margin (:margin (or settlement actual budget))
       :budget_variance (when (and budget actual) (money/money (- (:total_minor actual) (:total_minor budget))))})))

(defn- project-card
  [q actor project]
  (let [tasks (vec (q :planning/tasks {:project_id (:project_id project)}))
        nodes (vec (q :pms/nodes {:project_id (:project_id project)}))
        stages (:stages (first (g/records q project "template-instance")))
        rollup (progress/rollup tasks nodes stages)
        issues (g/records q project "issue") risks (g/records q project "risk")
        templates (g/records q project "gate-template") gates (g/records q project "gate")
        progress-rows (gates/gate-progress templates gates)
        boms (d/records q project "bom") tests (d/records q project "test") shipments (d/records q project "shipment")
        config (d/configuration q project)
        kitting (materials/kitting-rollup boms tasks nodes)
        today (LocalDate/now)]
    (merge (project-ref project)
           {:project_type (:project_type project) :manager_name (:manager_name project) :end_date (:end_date project)
            :days_to_end (days-until today (:end_date project))
            :overall_percent (:overall_percent rollup) :leaf_count (:leaf_count rollup)
            :stages (mapv #(select-keys % [:code :name :weight :percent]) (:stages rollup))
            :nodes (:nodes rollup)
            :node_count (count nodes)
            :kit_percent (:kit_percent kitting) :bom_count (:bom_count kitting) :shortage_count (count (:shortages kitting))
            :tests_required (:required_test_types config)
            :tests_approved (vec (distinct (map :test_type (filter #(= "approved" (:status %)) tests))))
            :tests_failed (count (filter #(= "rejected" (:status %)) tests))
            :shipments_received (count (filter #(= "received" (:status %)) shipments))
            :open_issues (count (remove #(= "closed" (:status %)) issues))
            :blocker_issues (count (filter #(and (= "blocker" (:severity %)) (not= "closed" (:status %))) issues))
            :open_risks (count (remove #(= "closed" (:status %)) risks))
            :escalations (count (filter #(= "pending" (:escalation_state %)) (concat issues risks)))
            :gates_passed (count (filter :passed progress-rows)) :gates_total (count progress-rows)
            :finance (finance-summary q actor project)
            :data_time (str (java.time.Instant/now))})))

(defn portfolio
  "多项目组合看板: 每个可见项目的进度/齐套/试验/问题/关口/成本卷积, 结构节点可下钻."
  [svc actor]
  (r/permit! actor ["pms:dashboard:query" "pms:project:list"])
  (let [q (:query-fn svc) projects (authorized-projects q actor)
        cards (mapv (partial project-card q actor) projects)]
    {:projects cards
     :summary {:total (count cards)
               :active (count (filter #(contains? #{"initiated" "planning" "execution" "paused" "closing"} (:status %)) cards))
               :overdue (count (filter #(and (some? (:days_to_end %)) (neg? (:days_to_end %)) (not (contains? #{"closed" "cancelled"} (:status %)))) cards))
               :blocker_issues (reduce + 0 (map :blocker_issues cards))
               :escalations (reduce + 0 (map :escalations cards))
               :average_percent (if (seq cards) (int (Math/round (double (/ (reduce + 0 (map :overall_percent cards)) (count cards))))) 0)}
     :generated_at (str (java.time.Instant/now))}))

;; ── 经营目标达成 ────────────────────────────────────────────────

(defn- quarter-of
  [date]
  (let [d (LocalDate/parse (subs date 0 10))] [(.getYear d) (inc (quot (dec (.getMonthValue d)) 3))]))

(defn targets
  "季度经营目标与实际达成 (F09): 收入/毛利取该季度关闭项目已批准决算, 结项数取该季度关闭项目数; 目标修订不改写历史."
  [svc actor]
  (r/permit! actor ["pms:finance:query" "pms:dashboard:query"])
  (let [q (:query-fn svc)
        finance? (or (:admin? actor) (contains? (:permissions actor) "pms:finance:query"))
        projects (authorized-projects q actor)
        closed (for [p projects :when (= "closed" (:status p))
                     :let [closed-at (str (or (:archived_at (q :lifecycle/state {:project_id (:project_id p)})) (:updated_at p)))
                           settlement (when finance?
                                        (->> (q :finance/versions {:project_id (:project_id p)})
                                             (filter #(and (= "settlement" (:kind %)) (= "approved" (:status %))))
                                             (sort-by :version_no >) first (cost/dto q)))
                           on-time? (and (:end_date p) (not (.isAfter (LocalDate/parse (subs closed-at 0 10)) (LocalDate/parse (:end_date p)))))]
                     :when (>= (count closed-at) 10)]
                 {:project_id (:project_id p) :project_no (:project_no p) :name (:name p) :quarter (quarter-of closed-at) :closed_at (subs closed-at 0 10)
                  :revenue_minor (or (some-> settlement :revenue_minor) (when settlement (money/amount! (:revenue settlement) "收入")) 0)
                  :margin_minor (if settlement (- (money/amount! (:revenue settlement) "收入") (:total_minor settlement)) 0)
                  :currency (:currency settlement) :on_time on-time?})
        rows (for [target (config/records q "quarterly-target")
                   :let [key [(:year target) (:quarter target)]
                         in-quarter (filter #(= key (:quarter %)) closed)
                         actual (case (:metric target)
                                  "revenue" (money/money (reduce + 0 (map :revenue_minor in-quarter)))
                                  "gross_margin" (money/money (reduce + 0 (map :margin_minor in-quarter)))
                                  "closed_projects" (count in-quarter)
                                  "on_time_rate" (if (seq in-quarter) (double (/ (* 100 (count (filter :on_time in-quarter))) (count in-quarter))) 0.0))
                         target-num (case (:metric target)
                                      ("revenue" "gross_margin") (/ (money/amount! (:target_value target) "目标") 100.0)
                                      (:target_value target))
                         actual-num (case (:metric target) ("revenue" "gross_margin") (/ (money/amount! actual "实际") 100.0) actual)]]
               (assoc target :actual actual :achievement_pct (if (and (number? target-num) (pos? target-num)) (int (Math/round (* 100.0 (/ actual-num target-num)))) 0)
                      :closed_projects (mapv #(select-keys % [:project_id :project_no :name :on_time :currency]) in-quarter)
                      :source (if finance? "approved_settlements" "counts_only")))]
    {:rows (vec (sort-by (juxt :year :quarter :metric :revision) rows)) :finance_visible finance?
     :metrics [{:value "revenue" :label "收入"} {:value "gross_margin" :label "毛利"} {:value "closed_projects" :label "结项数"} {:value "on_time_rate" :label "准时结项率%"}]}))
