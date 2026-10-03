(ns com.ruoyi.domain.pms.governance.quality
  "DQ 编制与确认关键任务 (B08) 与项目/单机局部暂停恢复 (B16).
   DQ: 检查清单 + 确定版本交付件, 逐项检查后提交独立签认; 交付件出现新版本时只读标注失效.
   局部暂停: 记录节点范围与原因, 暂停期间该节点及后代任务禁止进度反馈, 恢复时记录影响说明."
  (:require [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

;; ── DQ ──────────────────────────────────────────────────────────

(def check-methods
  "DQ 编制时可预先声明的检验方法 (H10 质量准则/方法/角色口径, 复用四类常见质量检验手段). 值以英文枚举持久化, 前端映射中文标签展示."
  #{"inspection" "measurement" "test" "documentation-review"})

(defn- checklist!
  [items]
  (when-not (and (vector? items) (<= 1 (count items) 50)) (r/fail! 400 "DQ检查清单需要1到50项"))
  (let [rows (mapv (fn [item] (r/object! item [:code :title :required])
                     {:code (s/text! item :code 50) :title (s/text! item :title 200)
                      :required (if (false? (:required item)) false true)
                      :passed false :note "" :waived false :waiver_reason ""}) items)]
    (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "DQ检查项编码重复"))
    rows))

(defn create-dq!
  "建立 DQ 关键任务: 标题, 责任人, 检查清单与确定版本交付件; 可选预先声明检验方法与执行角色 (H10 质量准则/方法/角色)."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.created"
    (fn [q project]
      (s/input! body [:code :title :owner_id :checklist :deliverable_ids :task_id :check_method :responsible_role])
      (when (seq (:task_id body))
        (when-not (q :planning/task {:project_id (:project_id project) :task_id (:task_id body)})
          (r/fail! 404 "关联任务不存在或不属于本项目")))
      (when (some #(= (:code body) (:code %)) (s/records q project "dq")) (r/fail! 409 "DQ编号已存在"))
      (s/insert! q project actor "dq"
                 (cond-> {:code (s/text! body :code 100) :title (s/text! body :title 200)
                          :owner_id (k/user! q project (:owner_id body) "DQ责任人")
                          :checklist (checklist! (:checklist body))
                          :deliverable_ids (s/evidence! q project (or (:deliverable_ids body) []) false)}
                   (seq (:task_id body)) (assoc :task_id (:task_id body))
                   (seq (:check_method body)) (assoc :check_method (s/enum! (:check_method body) check-methods "check_method"))
                   (seq (s/optional-text! body :responsible_role 100)) (assoc :responsible_role (s/optional-text! body :responsible_role 100)))
                 {:status "draft"}))))

(defn check-dq!
  "逐项登记检查结果, 全部必需项通过后进入待提交."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.checked"
    (fn [q project]
      (s/input! body [:results :deliverable_ids])
      (let [dq (s/record! q project "dq" rid)
            results (:results body)]
        (s/status! dq #{"draft" "ready" "rejected"})
        (when-not (and (vector? results) (= (set (map :code results)) (set (map :code (:checklist dq)))))
          (r/fail! 400 "必须提交清单全部检查项"))
        (let [by-code (into {} (map (juxt :code identity) results))
              checklist (mapv (fn [item]
                                (let [res (get by-code (:code item))
                                      waived (boolean (:waived res))]
                                  (r/object! res [:code :passed :note :waived :waiver_reason])
                                  (when (and (contains? res :waived) (not (boolean? (:waived res)))) (r/fail! 400 "waived必须为布尔值"))
                                  (when (and waived (s/boolean! (:passed res) "passed")) (r/fail! 400 (str "检查项 " (:code item) " 已通过, 无需例外")))
                                  (cond-> (assoc item :passed (s/boolean! (:passed res) "passed")
                                                 :note (r/text! (:note res) "检查说明" 500 false)
                                                 :waived waived)
                                    waived (assoc :waiver_reason (r/text! (:waiver_reason res) "例外说明" 500 true))
                                    (not waived) (assoc :waiver_reason ""))))
                              (:checklist dq))
              deliverables (if (contains? body :deliverable_ids)
                             (s/evidence! q project (:deliverable_ids body) false)
                             (:deliverable_ids dq))]
          (s/change! q project dq (if (every? #(or (:passed %) (:waived %)) (filter :required checklist)) "ready" "draft")
                     {:checklist checklist :deliverable_ids deliverables :checked_by (:user_id actor)}))))))

(defn submit-dq!
  "全部必需项通过或经例外放行且交付件齐备后提交独立签认."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.submitted"
    (fn [q project]
      (s/input! body [:reviewer_id])
      (let [dq (s/record! q project "dq" rid)]
        (s/status! dq #{"ready" "rejected"})
        (when-not (every? #(or (:passed %) (:waived %)) (filter :required (:checklist dq)))
          (r/fail! 409 "仍有必需检查项未通过"))
        (when (some #(and (:required %) (:waived %) (empty? (:waiver_reason %))) (:checklist dq))
          (r/fail! 409 "例外放行的必需检查项缺少说明"))
        (when (empty? (:deliverable_ids dq)) (r/fail! 409 "DQ必须绑定确定版本交付件"))
        (s/change! q project dq "in_review"
                   {:reviewer_id (s/reviewer! q project actor (:reviewer_id body)) :submitted_by (:user_id actor)
                    :submitted_deliverable_ids (:deliverable_ids dq)})))))

(defn decide-dq!
  "指定独立签认人确认或退回 DQ."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "dq.decided" {:write? false}
    (fn [q project]
      (s/input! body [:decision :reason])
      (let [dq (s/record! q project "dq" rid)
            decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
        (s/status! dq #{"in_review"})
        (s/decision-actor! actor dq)
        (s/change! q project dq decision {:decision_reason (s/text! body :reason) :decided_by (:user_id actor)})))))

(defn remediation-action!
  "把 DQ 未通过的必需检查项落实为可追踪的整改行动项: 复用行动类型与既有完成/独立验证/转任务生命周期, 记录来源 DQ 与所选未通过必需检查项编码, 缺省沿用 DQ 责任人; 需项目编辑权限, 仅对草稿或已退回且确有未通过必需检查项的 DQ 开放."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.remediation-action-created"
    (fn [q project]
      (s/input! body [:title :owner_id :due_date :check_code])
      (let [dq (s/record! q project "dq" rid)
            _ (s/status! dq #{"draft" "rejected"})
            failing (filterv #(and (:required %) (not (:passed %)) (not (:waived %))) (:checklist dq))]
        (when (empty? failing) (r/fail! 409 "该 DQ 没有未通过的必需检查项, 无需整改"))
        (let [cc (not-empty (s/optional-text! body :check_code 50))
              target (if cc
                       (or (first (filter #(= cc (:code %)) failing))
                           (r/fail! 400 "指定的检查项不存在或并非未通过的必需项"))
                       (first failing))
              prefix "整改: "
              base (str (:title dq) " / " (:title target))
              fallback (str prefix (if (> (count base) (- 200 (count prefix)))
                                     (subs base 0 (- 200 (count prefix))) base))
              given (s/optional-text! body :title 200)]
          (s/insert! q project actor "action"
                     {:title (if (seq given) given fallback)
                      :owner_id (k/user! q project (or (:owner_id body) (:owner_id dq)) "负责人")
                      :due_date (s/date! body :due_date)
                      :source_dq_id rid
                      :source_check_code (:code target)}
                     {:status "open"}))))))

(defn remediation-actions!
  "把 DQ 全部未通过的必需检查项一次性落实为多条可追踪整改行动项: 单条 remediation-action! 的批量版, 复用同一行动类型与既有完成/独立验证/转任务生命周期, 每个未通过必需项各生成一条独立 open 行动并记录来源 DQ 与该项检查编码, 缺省沿用 DQ 责任人与统一到期日; 需项目编辑权限, 仅对草稿或已退回且确有未通过必需检查项的 DQ 开放, 不改动 DQ 状态."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.remediation-actions-created"
    (fn [q project]
      (s/input! body [:owner_id :due_date])
      (let [dq (s/record! q project "dq" rid)
            _ (s/status! dq #{"draft" "rejected"})
            failing (filterv #(and (:required %) (not (:passed %)) (not (:waived %))) (:checklist dq))]
        (when (empty? failing) (r/fail! 409 "该 DQ 没有未通过的必需检查项, 无需整改"))
        (let [owner (k/user! q project (or (:owner_id body) (:owner_id dq)) "负责人")
              due (s/date! body :due_date)]
          (mapv #(let [prefix "整改: "
                       base (str (:title dq) " / " (:title %))
                       title (str prefix (if (> (count base) (- 200 (count prefix)))
                                           (subs base 0 (- 200 (count prefix))) base))]
                   (s/insert! q project actor "action"
                              {:title title
                               :owner_id owner
                               :due_date due
                               :source_dq_id rid
                               :source_check_code (:code %)}
                              {:status "open"}))
                failing))))))

(defn dq-read-model
  "只读标注: 交付件是否出现更新版本 (签认依据失效), 检查项通过数, 必需检查项就绪度 (通过或例外放行均视为满足), 以及例外放行计数."
  [documents dq]
  (let [latest-by-code (into {} (map (fn [[code rows]] [code (apply max (map :revision rows))]) (group-by :code documents)))
        by-id (into {} (map (juxt :id identity)) documents)
        stale (filterv (fn [id] (let [doc (get by-id id)]
                                  (and doc (> (get latest-by-code (:code doc) 0) (:revision doc)))))
                       (:deliverable_ids dq))
        checklist (:checklist dq)
        required (filter :required checklist)
        satisfied? (fn [c] (or (:passed c) (:waived c)))
        required-passed (count (filter :passed required))
        required-waived (count (filter :waived required))
        required-satisfied (count (filter satisfied? required))]
    (assoc dq :dq_stale (boolean (seq stale)) :dq_stale_count (count stale)
           :dq_passed (count (filter :passed checklist)) :dq_total (count checklist)
           :dq_waived (count (filter :waived checklist))
           :dq_required_total (count required) :dq_required_passed required-passed
           :dq_required_waived required-waived :dq_required_satisfied required-satisfied
           :dq_required_missing (- (count required) required-satisfied)
           :dq_required_met (= (count required) required-satisfied))))

(defn dq-deliverable-voided-model
  "只读派生 DQ 签认快照所绑定交付件是否现已整体作废: 逐个 deliverable_ids (登记时绑定的不可变文档版本 id) 经 docs-by-id 解析其业务编码, 若该编码最新版本已被受控作废(discarded) 则计入 dq_voided_deliverables, 任一命中则 dq_deliverable_voided 为 true. 交付件自身快照口径不漂移(引用的仍是那一个历史版本), 本标注仅额外提示该交付件业务编码现已整体作废. 免迁移读取时计算, 不写存储, 不改变不可变版本, 不门控, 键名不带尾随问号."
  [voided-codes docs-by-id dq]
  (let [voided-count (count (filterv #(when-let [doc (get docs-by-id %)]
                                        (voided-codes (:code doc)))
                                     (or (:deliverable_ids dq) [])))]
    (assoc dq :dq_voided_deliverables voided-count
           :dq_deliverable_voided (pos? voided-count))))

(defn dq-summary
  "把全部 DQ 关键任务的最新状态只读聚合为项目级质量检查闭环概览: total/approved/in-review/ready/draft/rejected 各状态计数, required-met (必需检查项全满足数, 含例外放行), exception-met (必需项已满足但其中至少一项靠例外放行才满足的 DQ 数), stale (签认依据交付件出现更新版本数), voided (绑定交付件业务编码现已整体作废数), methods-declared (已预先声明检验方法的 DQ 数), roles-declared (已指定执行角色的 DQ 数), remediated (已落实至少一条整改行动的 DQ 数, 依 dq_remediation_total 派生), remediation-open (仍有未完成整改行动的 DQ 数, 依 dq_remediation_open 派生), closure-pct (已签认 approved 占全部分母的整数百分比, 无 DQ 时为 0). 依赖 dq-read-model, dq-deliverable-voided-model 与 dq-remediation-read-model 已写入的派生键, 故须在三者之后调用. 免迁移读取时计算, 不写存储, 不构成门控, 键名不带尾随问号."
  [dqs]
  (let [total (count dqs)
        by-status (frequencies (map :status dqs))
        approved (get by-status "approved" 0)]
    {:available (pos? total)
     :total total
     :approved approved
     :in-review (get by-status "in_review" 0)
     :ready (get by-status "ready" 0)
     :draft (get by-status "draft" 0)
     :rejected (get by-status "rejected" 0)
     :required-met (count (filter :dq_required_met dqs))
     :exception-met (count (filter #(and (:dq_required_met %) (pos? (:dq_required_waived % 0))) dqs))
     :stale (count (filter :dq_stale dqs))
     :voided (count (filter :dq_deliverable_voided dqs))
     :methods-declared (count (filter (comp seq :check_method) dqs))
     :roles-declared (count (filter (comp seq :responsible_role) dqs))
     :remediated (count (filter #(pos? (:dq_remediation_total % 0)) dqs))
     :remediation-open (count (filter #(pos? (:dq_remediation_open % 0)) dqs))
     :closure-pct (if (pos? total)
                    (int (Math/round ^double (* 100.0 (/ approved total))))
                    0)}))

(defn attach-dq-summary
  "在治理读模型上追加 :dq_summary 只读汇总 (基于已富化的 :dqs), 不改变任何逐条 DQ 记录."
  [data]
  (assoc data :dq_summary (dq-summary (:dqs data))))

;; ── 局部暂停 ────────────────────────────────────────────────────

(defn pause-node!
  "暂停某结构节点 (子项目/单机) 及其后代的执行反馈, 记录原状态与原因; 同一节点不可重复暂停."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:transition" body "node.paused"
    (fn [q project]
      (s/input! body [:node_id :reason])
      (let [node (or (q :pms/node {:project_id (:project_id project) :node_id (:node_id body)})
                     (r/fail! 404 "结构节点不存在或不属于本项目"))]
        (when (= "main" (:node_type node)) (r/fail! 400 "主项目请使用项目级暂停"))
        (when (some #(and (= (:node_id node) (:node_id %)) (= "active" (:status %))) (s/records q project "node-pause"))
          (r/fail! 409 "该节点已处于暂停中"))
        (s/insert! q project actor "node-pause"
                   {:code (str "PAUSE-" (:node_code node) "-" (subs (k/id) 0 8))
                    :node_id (:node_id node) :node_code (:node_code node) :node_name (:name node) :node_type (:node_type node)
                    :reason (s/text! body :reason 1000) :project_status_at_pause (:status project)
                    :paused_by (:user_id actor) :paused_at (str (java.time.Instant/now))}
                   {:status "active"})))))

(defn resume-node!
  "恢复被暂停节点, 记录恢复条件校验与重排影响说明, 保留暂停历史."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:transition" body "node.resumed"
    (fn [q project]
      (s/input! body [:impact_note])
      (let [pause (s/record! q project "node-pause" rid)]
        (s/status! pause #{"active"})
        (s/change! q project pause "closed"
                   {:impact_note (s/text! body :impact_note 1000) :resumed_by (:user_id actor)
                    :resumed_at (str (java.time.Instant/now))})))))

(defn paused-node-ids
  "返回当前处于暂停中的节点及其全部后代节点 id 集合."
  [q project]
  (let [nodes (q :pms/nodes {:project_id (:project_id project)})
        active (map :node_id (filter #(= "active" (:status %)) (s/records q project "node-pause")))]
    (loop [result (set active) frontier (vec active)]
      (let [children (map :node_id (filter #(contains? (set frontier) (:parent_id %)) nodes))]
        (if (empty? children) result (recur (into result children) (vec children)))))))
