(ns com.ruoyi.domain.pms.governance.gates
  "关口模板,指定版本证据,独立签核及生命周期前置检查."
  (:require [clojure.string :as str]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.config.catalog :as catalog]
            [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn create-template!
  "登记不可变的项目关口模板: 类型, 适用阶段, 阻断检查点与检查项 (含发布版本要求)."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate-template.created"
    (fn [q project]
      (s/input! body [:code :title :stage :required :checks :gate_type :blocks])
      (let [template (config/gate-template! (dissoc body :version))]
        (when (some #(= (:code template) (:code %)) (s/records q project "gate-template"))
          (r/fail! 409 "关口模板编号已存在"))
        (s/insert! q project actor "gate-template" template {:status "registered"})))))

(defn from-catalog!
  "按原蓝图关口目录一键建立项目内 Gate 模板 (B06-B15 模板候选, 适用范围待业务签收)."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate-template.created"
    (fn [q project]
      (s/input! body [:gate_type :required])
      (let [item (or (catalog/gate-type (:gate_type body)) (r/fail! 404 "关口目录项不存在"))
            template (config/gate-template! {:code (str "GT-" (str/upper-case (:gate_type item)))
                                             :title (:title item) :gate_type (:gate_type item) :stage (:stage item)
                                             :required (if (false? (:required body)) false true)
                                             :blocks (or (:blocks item) []) :checks (:checks item)})]
        (when (some #(= (:code template) (:code %)) (s/records q project "gate-template"))
          (r/fail! 409 "该类型关口模板已建立"))
        (s/insert! q project actor "gate-template" (assoc template :source "catalog") {:status "registered"})))))

(defn create!
  "从确定模板创建关口实例并绑定独立审核人."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.created"
    (fn [q project]
      (s/input! body [:template_id :title :reviewer_id])
      (let [template (s/record! q project "gate-template" (:template_id body))]
        (s/insert! q project actor "gate"
                   {:title (s/text! body :title 200) :template_id (:id template)
                    :stage (:stage template) :required (:required template)
                    :gate_type (:gate_type template "generic")
                    :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                    :checks (mapv #(assoc % :passed false :evidence_ids []) (:checks template))}
                   {})))))

(defn- completed-checks!
  "将检查结果绑定模板原有项目和不可变文档证据."
  [q project gate checks]
  (when-not (and (vector? checks) (= (count checks) (count (:checks gate))))
    (r/fail! 400 "必须提交模板的全部检查项"))
  (when-not (= (set (map :code checks)) (set (map :code (:checks gate))))
    (r/fail! 400 "检查项编码与模板不一致"))
  (let [results (into {} (map (juxt :code identity) checks))]
    (mapv (fn [item]
            (let [result (get results (:code item))
                  waived (boolean (:waived result))]
              (r/object! result [:code :passed :evidence_ids :waived :waiver_reason])
              (when (and (contains? result :waived) (not (boolean? (:waived result)))) (r/fail! 400 "waived必须为布尔值"))
              (when (and waived (s/boolean! (:passed result) "passed")) (r/fail! 400 (str "检查项 " (:code item) " 已通过, 无需例外")))
              (cond-> (assoc item :passed (s/boolean! (:passed result) "passed")
                             :evidence_ids (s/evidence! q project (or (:evidence_ids result) []) false)
                             :waived waived)
                waived (assoc :waiver_reason (r/text! (:waiver_reason result) "例外说明" 500 true))
                (not waived) (dissoc :waiver_reason))))
          (:checks gate))))

(defn checks!
  "编辑未签核关口的检查结果,保留模板必需性不被客户端覆盖."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.checked"
    (fn [q project]
      (s/input! body [:checks])
      (let [gate (s/record! q project "gate" rid)]
        (s/status! gate #{"draft" "ready" "rejected"})
        (s/change! q project gate "ready"
                   {:checks (completed-checks! q project gate (:checks body))})))))

(defn- evidence-ready!
  "所有必需项须通过并提供确定文档版本; 声明 require_released 的检查项其证据须已经独立发布 (C06 发布链联动)."
  [q project gate]
  (doseq [check (:checks gate) :when (:required check)]
    (when-not (or (:passed check) (:waived check)) (r/fail! 409 (str "必需检查未通过: " (:code check))))
    (when (:waived check) (when-not (seq (:waiver_reason check)) (r/fail! 409 (str "例外检查项缺少说明: " (:code check)))))
    (when (:passed check) (s/evidence! q project (:evidence_ids check) true))
    (when (:require_released check)
      (doseq [id (:evidence_ids check)]
        (when-not (= "approved" (:status (s/record! q project "document" id)))
          (r/fail! 409 (str "检查项 " (:code check) " 引用的证据尚未发布"))))))
  true)

(defn checkpoint-ready!
  "阻断型关口: 声明了该交付检查点的模板必须已有通过或豁免的实例, 否则拒绝后续交付命令."
  [q project checkpoint]
  (doseq [template (filter #(some #{checkpoint} (:blocks %)) (s/records q project "gate-template"))]
    (let [instances (filter #(= (:id template) (:template_id %)) (s/records q project "gate"))]
      (when-not (some #(contains? #{"approved" "waived"} (:status %)) instances)
        (r/fail! 409 (str "阻断关口尚未通过: " (:title template))))))
  true)

(defn gate-progress
  "只读汇总各关口模板的实例进展与检查项通过数, 供汇总 Gate 下钻 (B09/B10)."
  [templates gates]
  (mapv (fn [template]
          (let [instances (filter #(= (:id template) (:template_id %)) gates)
                latest (last (sort-by :created_at instances))
                checks (:checks latest)]
            {:template_id (:id template) :code (:code template) :title (:title template)
             :gate_type (:gate_type template "generic") :stage (:stage template) :blocks (:blocks template [])
             :required (:required template) :instance_count (count instances)
             :status (if latest (:status latest) "not_started")
             :passed_checks (count (filter #(or (:passed %) (:waived %)) checks)) :waived_checks (count (filter :waived checks))
             :total_checks (count (:checks template))
             :passed (boolean (some #(contains? #{"approved" "waived"} (:status %)) instances))}))
        templates))

(defn gate-read-model
  "只读派生单个关口实例的签核就绪度: 检查项总数/已满足数/豁免数, 必需未满足项编码及其数量(gate_required_missing)及是否可签核, 不写存储."
  [gate]
  (let [checks (or (:checks gate) [])
        satisfied? (fn [c] (or (:passed c) (:waived c)))
        blocking (mapv :code (remove satisfied? (filter :required checks)))]
    (assoc gate
           :gate_total (count checks)
           :gate_passed (count (filter satisfied? checks))
           :gate_waived (count (filter :waived checks))
           :blocking_checks blocking
           :gate_required_missing (count blocking)
           :ready_to_sign (empty? blocking))))

(defn gate-evidence-voided-model
  "只读派生关口验收快照所引用证据是否现已作废: 逐个检查项的 evidence_ids (登记时绑定的不可变文档版本 id) 经 docs-by-id 解析其业务编码, 若该编码最新版本已被受控作废(discarded) 则计入 gate_voided_checks, 任一命中则 gate_evidence_voided 为 true. 检查项自身快照口径不漂移(引用的仍是那一个历史版本), 本标注仅额外提示证据现已整体作废. 免迁移读取时计算, 不写存储, 不改变不可变版本, 不门控, 键名不带尾随问号."
  [voided-codes docs-by-id gate]
  (let [checks (or (:checks gate) [])
        voided-check? (fn [c]
                        (some #(when-let [doc (get docs-by-id %)]
                                 (voided-codes (:code doc)))
                              (:evidence_ids c)))
        voided-count (count (filter voided-check? checks))]
    (assoc gate
           :gate_voided_checks voided-count
           :gate_evidence_voided (pos? voided-count))))

(defn gate-evidence-release-model
  "只读派生关口验收快照所引用的已通过证据是否已正式发布: 逐个已勾选通过 (:passed) 且绑定了 evidence_ids (登记时锁定的不可变文档版本 id) 的检查项, 经 docs-by-id 解析所绑文档的当前状态, 只要存在任一绑定文档尚未签发 (status 不为 approved, 含 still registered/in_review/rejected/缺档) 即计入 gate_evidence_pending. 派生 gate_evidence_checks (通过且绑证据的检查项数), gate_evidence_pending (其中证据未全发布的检查项数) 与布尔 gate_evidence_unreleased (gate_evidence_pending > 0). 本标注只提示证据尚未正式发布, 与 gate_evidence_voided (作废) 正交; 免迁移读取时计算, 不写存储, 不改变不可变版本, 不门控, 键名不带尾随问号."
  [docs-by-id gate]
  (let [checks (or (:checks gate) [])
        passed-with-evidence? (fn [c] (and (:passed c) (seq (:evidence_ids c))))
        unreleased-check? (fn [c]
                            (some #(not= "approved" (:status (get docs-by-id %)))
                                  (:evidence_ids c)))
        evidence-checks (filter passed-with-evidence? checks)
        pending-count (count (filter unreleased-check? evidence-checks))]
    (assoc gate
           :gate_evidence_checks (count evidence-checks)
           :gate_evidence_pending pending-count
           :gate_evidence_unreleased (pos? pending-count))))

(defn submit!
  "提交关口审核,允许完整检查或有理由的豁免申请."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.submitted"
    (fn [q project]
      (s/input! body [:waiver_reason])
      (let [gate (s/record! q project "gate" rid)
            waiver (s/optional-text! body :waiver_reason 2000)]
        (s/status! gate #{"draft" "ready" "rejected"})
        (s/reviewer! q project actor (:reviewer_id gate))
        (when (empty? waiver) (evidence-ready! q project gate))
        (s/change! q project gate "in_review"
                   {:submitted_by (:user_id actor) :waiver_reason waiver})))))

(defn decide!
  "指定审核人批准,拒绝或带充分理由豁免关口."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "gate.decided" {:write? false}
    (fn [q project]
      (s/input! body [:decision :reason])
      (let [gate (s/record! q project "gate" rid)
            decision (s/enum! (:decision body) #{"approved" "rejected" "waived"} "decision")
            reason (s/text! body :reason)]
        (s/status! gate #{"in_review"})
        (s/decision-actor! actor gate)
        (when (= decision "approved") (evidence-ready! q project gate))
        (when (and (= decision "waived") (empty? (:waiver_reason gate)))
          (r/fail! 409 "未提出豁免申请,不能直接豁免"))
        (s/change! q project gate decision {:decision_reason reason :decided_by (:user_id actor)})))))

(defn remediation-action!
  "把关口未通过的必需检查项落实为可追踪的整改行动项: 复用行动类型与既有完成/独立验证/转任务生命周期, 记录来源关口与所选未通过必需检查项编码, 缺省沿用关口审核人为负责人; 需项目编辑权限, 仅对尚未签核(draft/ready/rejected)且确有未通过必需检查项的关口实例开放."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.remediation-action-created"
    (fn [q project]
      (s/input! body [:title :owner_id :due_date :check_code])
      (let [gate (s/record! q project "gate" rid)
            _ (s/status! gate #{"draft" "ready" "rejected"})
            failing (filterv #(and (:required %) (not (:passed %)) (not (:waived %))) (:checks gate))]
        (when (empty? failing) (r/fail! 409 "该关口没有未通过的必需检查项, 无需整改"))
        (let [cc (not-empty (s/optional-text! body :check_code 50))
              target (if cc
                       (or (first (filter #(= cc (:code %)) failing))
                           (r/fail! 400 "指定的检查项不存在或并非未通过的必需项"))
                       (first failing))
              prefix "整改: "
              base (str (:title gate) " / " (:title target))
              fallback (str prefix (if (> (count base) (- 200 (count prefix)))
                                     (subs base 0 (- 200 (count prefix))) base))
              given (s/optional-text! body :title 200)]
          (s/insert! q project actor "action"
                     {:title (if (seq given) given fallback)
                      :owner_id (k/user! q project (or (:owner_id body) (:reviewer_id gate)) "负责人")
                      :due_date (s/date! body :due_date)
                      :source_gate_id rid
                      :source_check_code (:code target)}
                     {:status "open"}))))))

(defn- stage-ready!
  "要求必需关口模板存在且全部实例已通过或被正式豁免."
  [q project stage]
  (let [templates (filter #(and (= stage (:stage %)) (:required %)) (s/records q project "gate-template"))
        gates (filter #(= stage (:stage %)) (s/records q project "gate"))]
    (when (empty? templates) (r/fail! 409 "尚未配置该阶段必需关口模板"))
    (doseq [template templates]
      (let [instances (filter #(= (:id template) (:template_id %)) gates)]
        (when (or (empty? instances) (some #(not (contains? #{"approved" "waived"} (:status %))) instances))
          (r/fail! 409 (str "必需关口尚未通过: " (:title template))))))
    true))

(defn execution-ready!
  "执行开始需要当前正式章程及所有必需执行关口通过."
  [q project]
  (let [charter (first (s/latest (s/records q project "charter")))]
    (when-not (= "approved" (:status charter)) (r/fail! 409 "当前项目章程尚未独立批准"))
    (stage-ready! q project "execution")))

(defn closure-ready!
  "收尾需要必需验收关口通过且无未关闭的阻塞问题."
  [q project]
  (when (some #(and (= "blocker" (:severity %)) (not= "closed" (:status %)))
              (s/records q project "issue"))
    (r/fail! 409 "仍有未关闭的阻塞问题"))
  (stage-ready! q project "closure"))

(defn gate-closure-summary
  "只读聚合全部关口实例的签核闭环健康度: 按状态计数 (批准/豁免/审批中/待提交/草稿/驳回), 已签核 (批准或豁免) 占全部实例的整数百分比, 被未满足必需检查阻断的实例数, 证据已作废与证据待发布的实例数, 以及已落实整改 (至少派生一条整改行动, 依 gate_remediation_total) 与仍有未完成整改 (依 gate_remediation_open) 的关口数; 是关口实例层的签核闭环汇总, 与 gate-progress 的模板层下钻互补. 依赖 gate-remediation-read-model 已写入的派生键, 故须在其之后调用. 只读派生, 不改变任何关口状态, 不构成门控, 键名不带尾随问号."
  [gates]
  (let [total (count gates)
        by-status (frequencies (map :status gates))
        signed (+ (get by-status "approved" 0) (get by-status "waived" 0))]
    {:available (pos? total)
     :total total
     :approved (get by-status "approved" 0)
     :waived (get by-status "waived" 0)
     :in-review (get by-status "in_review" 0)
     :ready (get by-status "ready" 0)
     :draft (get by-status "draft" 0)
     :rejected (get by-status "rejected" 0)
     :signed signed
     :blocked (count (remove :ready_to_sign gates))
     :evidence-voided (count (filter :gate_evidence_voided gates))
     :evidence-pending (count (filter :gate_evidence_unreleased gates))
     :remediated (count (filter #(pos? (:gate_remediation_total % 0)) gates))
     :remediation-open (count (filter #(pos? (:gate_remediation_open % 0)) gates))
     :closure-pct (if (pos? total)
                    (int (Math/round ^double (* 100.0 (/ signed total))))
                    0)}))

(defn attach-gate-closure-summary
  "在治理读模型上追加 :gate_closure 只读汇总 (基于已富化的 :gates), 不改变任何逐条关口记录."
  [data]
  (assoc data :gate_closure (gate-closure-summary (:gates data))))

(defn gate-exception-summary
  "只读聚合全部关口实例的检查项级例外放行治理健康度: 必需检查项总数 required-checks, 其中经真实通过数 passed-checks 与经检查项例外放行 (:waived + :waiver_reason) 清除数 exception-checks, 含至少一项必需检查项被例外放行的关口数 gates-with-exception, 以及“靠例外放行才就绪”的关口数 gates-exception-dependent (该关口全部必需检查项当前均满足, 但其中至少一项仅因例外放行而满足—即去掉例外放行即无法满足必需项), 例外放行占必需检查项的整数百分比 waiver-pct (required-checks 为 0 时给 0), 与例外放行却缺失说明的必需检查项数 reason-missing (写路径 evidence-ready! 已强制例外检查须带说明, 故通常恒为 0, 本项作控制断言以显式暴露违规例外). 与 gate-closure-summary 互补且口径正交—后者按关口整体实例状态 (approved/waived 裁决/in_review/…) 计数签核闭环, 本项聚焦检查项粒度的例外放行依赖度, 回答“关口的通过有多少是靠逐检查项豁免撑起来的”这一控制问题. 免迁移读取时派生, 不写存储, 不改变任何关口状态或检查项, 不构成门控, 键名不带尾随问号."
  [gates]
  (let [total (count gates)
        required-of (fn [g] (filter :required (:checks g)))
        waived-required-of (fn [g] (filter #(and (:required %) (:waived %)) (:checks g)))
        passed-required-of (fn [g] (filter #(and (:required %) (:passed %)) (:checks g)))
        ready-now? (fn [g] (every? #(or (:passed %) (:waived %)) (required-of g)))
        required-checks (reduce + (map #(count (required-of %)) gates))
        exception-checks (reduce + (map #(count (waived-required-of %)) gates))
        passed-checks (reduce + (map #(count (passed-required-of %)) gates))
        gates-with-exception (count (filter #(pos? (count (waived-required-of %))) gates))
        gates-exception-dependent (count (filter #(and (ready-now? %) (pos? (count (waived-required-of %)))) gates))
        reason-missing (reduce + (map #(count (remove (comp seq :waiver_reason) (waived-required-of %))) gates))]
    {:available (pos? total)
     :total total
     :required-checks required-checks
     :passed-checks passed-checks
     :exception-checks exception-checks
     :gates-with-exception gates-with-exception
     :gates-exception-dependent gates-exception-dependent
     :reason-missing reason-missing
     :waiver-pct (if (pos? required-checks)
                   (int (Math/round ^double (* 100.0 (/ exception-checks required-checks))))
                   0)}))

(defn attach-gate-exception-summary
  "在治理读模型上追加 :gate_exception_summary 只读汇总 (基于已富化的 :gates), 不改变任何逐条关口记录."
  [data]
  (assoc data :gate_exception_summary (gate-exception-summary (:gates data))))
