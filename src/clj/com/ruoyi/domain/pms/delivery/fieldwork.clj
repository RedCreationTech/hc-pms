(ns com.ruoyi.domain.pms.delivery.fieldwork
  "工勘交付型任务 (B07), 装配上岛/交检/连线/下岛/交接明细 (E01), 发货前本地条件 (E04),
   发货后交底时限 (E07) 与现场定位/安装/调试/SAT任务 (E08/E09) 的本地闭环.
   外部 CRM/ERP/MES 回传保持 not_configured, 不冒充已同步."
  (:require [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.time LocalDate]))

;; ── B07 工勘 ────────────────────────────────────────────────────

(defn- visit!
  [value]
  (when-not (and (integer? value) (<= 1 value 20)) (r/fail! 400 "工勘次序必须是1到20的整数"))
  value)

(defn create-survey!
  "按项目适用性登记一次工勘: 次序, 责任人, 计划日期与交付物."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.survey.created" false
    (fn [q project]
      (g/input! body [:code :title :visit_no :owner_id :planned_date :deliverable :task_id])
      (when (seq (:task_id body))
        (when-not (q :planning/task {:project_id (:project_id project) :task_id (:task_id body)})
          (r/fail! 404 "关联任务不存在或不属于本项目")))
      (let [visit (visit! (:visit_no body))]
        (when (some #(and (= visit (:visit_no %)) (not= "rejected" (:status %))) (d/records q project "survey"))
          (r/fail! 409 "该次序的工勘已登记"))
        (d/insert! q project actor "survey"
                   (cond-> {:code (g/text! body :code 100) :title (g/text! body :title 200) :visit_no visit
                            :owner_id (d/owner! q project (:owner_id body)) :planned_date (g/date! body :planned_date)
                            :deliverable (g/text! body :deliverable 500)}
                     (seq (:task_id body)) (assoc :task_id (:task_id body)))
                   "draft")))))

(defn submit-survey!
  "工勘完成后以实际日期和交付证据提交独立确认."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.survey.submitted" false
    (fn [q project]
      (g/input! body [:reviewer_id :evidence_ids :actual_date :findings])
      (let [survey (d/record! q project "survey" rid)
            actual (d/actual-date! body :actual_date)]
        (d/submit! q project actor (d/change! q project survey (:status survey)
                                              {:actual_date actual :findings (g/optional-text! body :findings 2000)})
                   body)))))

(defn decide-survey!
  "指定审核人确认工勘交付物."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.survey.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (d/decide! q project actor (d/record! q project "survey" rid) body "approved"))))

(defn survey-blockers
  "配置了必需工勘次数时, 已确认工勘不足则阻塞收尾."
  [q project config]
  (let [required (or (:required_survey_visits config) 0)
        approved (count (filter #(= "approved" (:status %)) (d/records q project "survey")))]
    (when (< approved required) [(str "已确认工勘 " approved " 次, 少于配置要求 " required " 次")])))

;; ── E01 装配步骤 ───────────────────────────────────────────────

(def assembly-steps
  "上岛 -> 装配 -> 单机交检 -> 连线交检 -> 下岛 -> 交接, 顺序不可倒退."
  ["on_island" "assembling" "unit_inspection" "wiring_inspection" "off_island" "handover"])

(defn record-step!
  "登记装配执行明细步骤的实际日期, 顺序单调且不得晚于今天."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.assembly.step-recorded" false
    (fn [q project]
      (g/input! body [:step :actual_date :note :evidence_ids])
      (d/execution! project)
      (let [assembly (d/record! q project "assembly" rid)
            step (g/enum! (:step body) (set assembly-steps) "step")
            steps (vec (:steps assembly))
            index (.indexOf ^java.util.List assembly-steps step)
            done (set (map :step steps))
            actual (d/actual-date! body :actual_date)]
        (g/status! assembly #{"in_progress" "in_review" "approved"})
        (when (done step) (r/fail! 409 "该步骤已登记"))
        (when (some #(> (.indexOf ^java.util.List assembly-steps (:step %)) index) steps)
          (r/fail! 409 "后续步骤已登记, 不能倒退补录"))
        (when (and (pos? index) (not (done (nth assembly-steps (dec index)))))
          (r/fail! 409 (str "请先登记前一步骤: " (nth assembly-steps (dec index)))))
        (when-let [last-date (:actual_date (last steps))]
          (when (.isBefore (LocalDate/parse actual) (LocalDate/parse last-date))
            (r/fail! 400 "步骤日期不能早于上一步骤")))
        (d/change! q project assembly (:status assembly)
                   {:steps (conj steps {:step step :actual_date actual :note (g/optional-text! body :note 500)
                                        :recorded_by (:user_id actor) :recorded_at (str (java.time.Instant/now))
                                        :evidence_ids (g/evidence! q project (or (:evidence_ids body) []) false)})
                    :mes_sync_status "not_configured"})))))

(defn assembly-read-model
  "只读派生装配步骤进度."
  [assembly]
  (let [steps (:steps assembly)]
    (assoc assembly :step_count (count steps) :step_total (count assembly-steps)
           :current_step (or (:step (last steps)) "not_started")
           :next_step (get assembly-steps (count steps)))))

;; ── E04 发货前本地条件 ─────────────────────────────────────────

(defn confirm-conditions!
  "登记入库/提货款等本地事实 (含证据与来源说明), 不冒充OA/ERP回执."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.shipment.conditions" false
    (fn [q project]
      (g/input! body [:warehouse_in_confirmed :warehouse_note :payment_confirmed :payment_note :evidence_ids])
      (let [shipment (d/record! q project "shipment" rid)]
        (g/status! shipment #{"draft" "in_review" "rejected" "released"})
        (d/change! q project shipment (:status shipment)
                   {:preconditions {:warehouse_in_confirmed (boolean (:warehouse_in_confirmed body))
                                    :warehouse_note (g/optional-text! body :warehouse_note 500)
                                    :payment_confirmed (boolean (:payment_confirmed body))
                                    :payment_note (g/optional-text! body :payment_note 500)
                                    :evidence_ids (g/evidence! q project (or (:evidence_ids body) []) false)
                                    :confirmed_by (:user_id actor) :confirmed_at (str (java.time.Instant/now))
                                    :source "local_fact"}})))))

(defn conditions-ready!
  "按配置要求的发货前条件核对本地事实."
  [config shipment]
  (let [required (set (:pre_ship_conditions config)) pre (:preconditions shipment)]
    (when (and (required "warehouse_in") (not (:warehouse_in_confirmed pre)))
      (r/fail! 409 "发货前条件未满足: 入库/装箱未确认"))
    (when (and (required "payment") (not (:payment_confirmed pre)))
      (r/fail! 409 "发货前条件未满足: 提货款条件未确认")))
  true)

(defn shipment-read-model
  "只读派生发货前条件清单."
  [config fat-ok? blocker-free? shipment]
  (let [required (set (:pre_ship_conditions config)) pre (:preconditions shipment)]
    (assoc shipment :preship_checklist
           (cond-> [{:code "fat" :label "适用FAT/SIT试验已批准" :required true :satisfied (boolean fat-ok?) :source "derived"}
                    {:code "remediation" :label "阻塞整改已关闭" :required true :satisfied (boolean blocker-free?) :source "derived"}]
             (required "warehouse_in") (conj {:code "warehouse_in" :label "入库/装箱已确认" :required true
                                              :satisfied (boolean (:warehouse_in_confirmed pre)) :source "local_fact"})
             (required "payment") (conj {:code "payment" :label "提货款条件已确认" :required true
                                         :satisfied (boolean (:payment_confirmed pre)) :source "local_fact"})))))

;; ── E07 交底 ───────────────────────────────────────────────────

(defn create-handover!
  "发运登记后在同一事务内生成交底任务: 截止期 = 发运日 + 配置天数 (自然日, 工程默认2天)."
  [q project actor shipment config]
  (let [days (or (:handover_deadline_days config) 2)
        deadline (str (.plusDays (LocalDate/parse (:shipped_on shipment)) days))]
    (d/insert! q project actor "handover"
               {:code (str "HO-" (:code shipment)) :title (str "项目交底: " (:title shipment))
                :shipment_id (:id shipment) :shipped_on (:shipped_on shipment) :deadline deadline
                :deadline_days days :deadline_basis "calendar_days" :owner_id (:owner_id shipment)
                :crm_sync_status "not_configured"}
               "open")))

(defn complete-handover!
  "在期限内完成资料签交: 检查清单版本与文件清单 (项目内文档版本), 逾期完成如实标记."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.handover.completed" false
    (fn [q project]
      (g/input! body [:document_ids :checklist_note :completed_on])
      (let [handover (d/record! q project "handover" rid)
            completed (d/actual-date! body :completed_on)
            documents (g/evidence! q project (:document_ids body) true)
            config (d/configuration q project)
            late? (.isAfter (LocalDate/parse completed) (LocalDate/parse (:deadline handover)))]
        (g/status! handover #{"open"})
        (when (.isBefore (LocalDate/parse completed) (LocalDate/parse (:shipped_on handover)))
          (r/fail! 400 "交底完成日期不能早于发运日期"))
        (let [closed (d/change! q project handover "closed"
                                {:document_ids documents :checklist_note (g/optional-text! body :checklist_note 1000)
                                 :completed_on completed :completed_late late? :completed_by (:user_id actor)
                                 :crm_sync_status "not_configured"})
              lag (or (:site_lag_days config) 2)
              start (str (.plusDays (LocalDate/parse completed) lag))]
          ;; E08: 交底完成后按配置滞后期生成现场任务序列, 不下发 ERP.
          (doseq [[seq-no key name] [[1 "positioning" "现场定位"] [2 "installation" "现场安装"]
                                     [3 "commissioning" "现场调试"] [4 "sat" "现场SAT"]]]
            (d/insert! q project actor "site-task"
                       {:code (str "SITE-" (:code handover) "-" seq-no) :title (str name ": " (:title handover))
                        :handover_id (:id handover) :shipment_id (:shipment_id handover) :sequence seq-no :task_key key
                        :planned_start start :lag_days lag :owner_id (:owner_id handover)
                        :erp_dispatch_status "not_configured" :crm_feedback_status "not_configured"}
                       "draft"))
          closed)))))

(defn handover-read-model
  "只读派生交底剩余天数/逾期标记."
  [today handover]
  (let [deadline (LocalDate/parse (:deadline handover))
        days (.between java.time.temporal.ChronoUnit/DAYS today deadline)]
    (assoc handover :handover_days_left (when (= "open" (:status handover)) days)
           :handover_overdue (boolean (and (= "open" (:status handover)) (neg? days))))))

;; ── E08/E09 现场任务 ───────────────────────────────────────────

(defn start-site-task!
  "按序启动现场任务, 前序未完成不得开始, 实际日期不得晚于今天."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.site-task.started" false
    (fn [q project]
      (g/input! body [:actual_start :note])
      (let [task (d/record! q project "site-task" rid)
            siblings (filter #(= (:handover_id task) (:handover_id %)) (d/records q project "site-task"))
            prior (filter #(< (:sequence %) (:sequence task)) siblings)]
        (g/status! task #{"draft"})
        (when (some #(not= "closed" (:status %)) prior) (r/fail! 409 "前序现场任务尚未完成"))
        (d/change! q project task "in_progress"
                   {:actual_start (d/actual-date! body :actual_start) :started_by (:user_id actor)
                    :note (g/optional-text! body :note 500)})))))

(defn complete-site-task!
  "以实际完成日期与现场证据关闭任务; 完成日期不早于开始日期."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.site-task.completed" false
    (fn [q project]
      (g/input! body [:actual_end :evidence_ids :result])
      (let [task (d/record! q project "site-task" rid)
            end (d/actual-date! body :actual_end)]
        (g/status! task #{"in_progress"})
        (when (.isBefore (LocalDate/parse end) (LocalDate/parse (:actual_start task)))
          (r/fail! 400 "完成日期不能早于开始日期"))
        (d/change! q project task "closed"
                   {:actual_end end :result (g/optional-text! body :result 1000) :completed_by (:user_id actor)
                    :evidence_ids (g/evidence! q project (:evidence_ids body) true)})))))

(defn site-task-read-model
  "只读派生现场任务是否延误."
  [today task]
  (let [planned (LocalDate/parse (:planned_start task))]
    (assoc task :site_delayed (boolean (and (= "draft" (:status task)) (.isAfter today planned)))
           :site_days_to_start (when (= "draft" (:status task)) (.between java.time.temporal.ChronoUnit/DAYS today planned)))))
