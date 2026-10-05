(ns com.ruoyi.domain.pms.governance.approval
  "项目章程与正式变更的类型化内容和独立评审."
  (:require [com.ruoyi.domain.pms.approval-chain :as chain]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))

(def charter-fields
  "章程必备内容字段."
  [:title :objective :scope :success_criteria :sponsor_id])

(def charter-budget-fields
  "章程可选初始预算字段,随内容版本不可变持久化."
  [:initial_budget :budget_currency])

(def charter-pm-fields
  "章程可选授权项目经理字段,随内容版本不可变持久化."
  [:authorized_pm_id])

(def change-fields
  "变更必须明确的影响维度."
  [:title :reason :scope_impact :schedule_impact :cost_impact :quality_impact :resource_impact])

(def change-impact-fields
  "变更可选量化影响字段,随内容版本不可变持久化."
  [:schedule_impact_days :cost_impact_amount])

(def change-type-fields
  "变更可选请求类型字段,随内容版本不可变持久化."
  [:change_type])

(def change-types
  "变更请求类型枚举(PMBOK四类变更请求): 纠错性/预防性/缺陷修复/更新; 登记时未选择则不写入(视为未设定)."
  #{"corrective" "preventive" "defect-repair" "updates"})

(def high-impact-schedule-days
  "工期影响达到该天数即判定为高影响变更."
  10)

(def high-impact-cost-minor
  "成本影响达到该最小单位(即100000.00)即判定为高影响变更."
  10000000)

(defn- charter-budget!
  "校验章程可选初始预算:金额非负并规范化为两位小数,币种缺省CNY;未填预算时不写入任何预算键."
  [body]
  (when-let [raw (:initial_budget body)]
    (let [amount (money/amount! raw "初始预算")]
      (when (neg? amount) (r/fail! 400 "初始预算不得为负数"))
      (let [currency (if-let [c (:budget_currency body)]
                       (do (when-not (money/currencies c) (r/fail! 400 "暂仅支持CNY/USD/EUR/GBP/HKD")) c)
                       "CNY")]
        {:initial_budget (money/money amount) :budget_currency currency}))))

(defn- charter-pm!
  "校验章程可选授权项目经理:须为有效本地用户;未指定时不写入任何授权PM键,仍由项目 manager_id 隐含承载."
  [q body]
  (when-let [uid (:authorized_pm_id body)]
    {:authorized_pm_id (s/user! q uid)}))

(defn- change-impact!
  "校验变更可选量化影响: 工期影响为0..3650整数天, 成本影响为最多两位小数非负金额并规范化; 未填时不写入对应键."
  [body]
  (cond-> {}
    (some? (:schedule_impact_days body))
    (assoc :schedule_impact_days
           (let [s (str (:schedule_impact_days body))]
             (when-not (re-matches #"[0-9]{1,4}" s) (r/fail! 400 "工期影响须为0至3650的整数天"))
             (let [d (Long/parseLong s)]
               (when (> d 3650) (r/fail! 400 "工期影响不得超过3650天"))
               d)))
    (some? (:cost_impact_amount body))
    (assoc :cost_impact_amount
           (let [amount (money/amount! (:cost_impact_amount body) "成本影响")]
             (when (neg? amount) (r/fail! 400 "成本影响金额不得为负数"))
             (money/money amount)))))

(defn- change-type!
  "校验变更可选请求类型(PMBOK四类): 缺键或空串视为未设定不写入该键; 非法取值400. 免迁移随 payload 持久化."
  [body]
  (when (seq (:change_type body))
    {:change_type (s/enum! (:change_type body) change-types "变更类型")}))

(defn- high-impact?
  "判定变更内容是否达到高影响阈值: 工期影响达到阈值天数或成本影响金额达到阈值最小单位."
  [record]
  (let [days (:schedule_impact_days record)
        cost-str (:cost_impact_amount record)
        cost-minor (when (and (some? cost-str) (not= "" cost-str)) (money/amount! cost-str "成本影响"))]
    (boolean (or (when (number? days) (>= days high-impact-schedule-days))
                 (when (some? cost-minor) (>= cost-minor high-impact-cost-minor))))))

(defn- change-escalation
  "为达到高影响阈值的变更生成升级处置字段 (待变更控制独立确认); 未达阈值不写任何 escalation 键, 与低影响变更用例兼容."
  [record]
  (when (high-impact? record)
    {:escalated true
     :escalation_state "pending"
     :escalation_level "ccb"
     :escalation_reason (str "变更量化影响达到高影响阈值 (工期 >= " high-impact-schedule-days " 天或成本 >= 100000.00), 须由变更控制独立审批人在批准前确认升级处置.")}))

(defn content!
  "分别校验章程和变更内容,拒绝任意JSON字段."
  [q kind body]
  (let [fields (if (= kind "charter") charter-fields change-fields)
        allowed (if (= kind "charter")
                  (into (into charter-fields charter-budget-fields) charter-pm-fields)
                  (into (into change-fields change-impact-fields) change-type-fields))]
    (s/input! body allowed)
    (cond-> (into {} (for [field (remove #{:sponsor_id} fields)]
                       [field (s/text! body field (if (= field :title) 200 4000))]))
      (= kind "charter") (assoc :sponsor_id (s/user! q (:sponsor_id body)))
      (= kind "charter") (merge (charter-budget! body))
      (= kind "charter") (merge (charter-pm! q body))
      (= kind "change") (merge (change-impact! body))
      (= kind "change") (merge (change-type! body)))))

(defn create!
  "创建章程的新版本或独立变更申请."
  [svc actor id kind body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".created")
    (fn [q project]
      (let [fields (content! q kind body)
            prior (when (= kind "charter") (s/records q project kind))
            revision (inc (reduce max 0 (map :revision prior)))]
        (s/insert! q project actor kind
                   (cond-> fields (= kind "charter") (assoc :code "charter"))
                   {:revision revision})))))

(defn revise!
  "为已存在的变更或章程创建新内容版本,旧审批依据不漂移."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".revised")
    (fn [q project]
      (let [old (s/latest! q project (s/record! q project kind rid))
            content (content! q kind body)]
        (s/insert! q project actor kind (assoc content :code (:code old) :previous_id rid)
                   {:revision (inc (:revision old))})))))

(defn submit!
  "冻结当前内容并选择具有权限的独立审核人."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".submitted")
    (fn [q project]
      (s/input! body [:reviewer_id])
      (let [record (s/latest! q project (s/record! q project kind rid))
            ;; 章程在已发布 "项目章程" 审批策略时按策略逐级审批, 指定审核人可选 (用于 "提交人选择" 级别)
            chain? (and (= "charter" kind) (some? (chain/policy q "charter")))
            reviewer (when (or (not chain?) (some? (:reviewer_id body)))
                       (s/reviewer! q project actor (:reviewer_id body)))]
        (s/status! record #{"draft" "rejected"})
        (let [started (when chain?
                        (chain/start! q actor project "charter" (:id record)
                                      {:reviewer-id reviewer :amount (:initial_budget record)
                                       :title (str "项目章程 " (:code record) " 第" (:revision record) "版 " (:title record))}))]
          (s/change! q project record "in_review"
                     (cond-> {:reviewer_id (or reviewer (:first-approver started)) :submitted_by (:user_id actor)}
                       (= "change" kind) (merge (change-escalation record)))))))))

(defn- ccb-tally
  "只读汇总变更控制委员会表决进度; 未设置委员会时 state 为 none, 仅读取时计算, 不落存储."
  [record]
  (let [members (vec (:ccb_members record))
        required (:ccb_required record)
        ballots (vec (:ccb_ballots record))
        approve (count (filter #(= "approve" (:vote %)) ballots))
        reject (count (filter #(= "reject" (:vote %)) ballots))
        n (count members)]
    {:members n
     :required required
     :approve approve
     :reject reject
     :quorum_met (boolean (and (some? required) (>= approve required)))
     :state (cond
              (nil? required) "none"
              (>= approve required) "passed"
              (> reject (- n required)) "failed"
              :else "voting")}))


(defn decide!
  "指定独立审核人批准或退回当前提交版本."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:quality:approve" body (str kind ".decided") {:write? false}
    (fn [q project]
      (s/input! body [:decision :reason])
      (let [record (s/latest! q project (s/record! q project kind rid))
            decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
        (s/status! record #{"in_review"})
        (when (and (= "change" kind) (= "approved" decision)
                   (:escalated record) (= "pending" (:escalation_state record)))
          (r/fail! 409 "该高影响变更尚未完成变更控制升级独立确认, 请先由独立审批人确认升级处置后再批准"))
        (when (and (= "change" kind) (= "approved" decision)
                   (some? (:ccb_required record)))
          (let [{:keys [approve required members]} (ccb-tally record)]
            (when (< approve required)
              (r/fail! 409 (str "变更控制委员会表决未达通过票数 (需 " required "/" members " 赞成, 现 " approve " 票), 请补足赞成票后再批准")))))
        (when (= "charter" kind) (chain/guard-legacy-decision! q "charter" (:id record)))
        (s/decision-actor! actor record)
        (s/change! q project record decision
                   {:decision_reason (s/text! body :reason)
                    :decided_by (:user_id actor)})))))

(defn acknowledge-change-escalation!
  "由变更控制独立审批人确认高影响变更的升级处置, 批准责成处置或经评估豁免; 确认前高影响变更不得被批准, 驳回不受此门控."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "change.escalation-acknowledged" {:write? false}
    (fn [q project]
      (s/input! body [:decision :note])
      (let [record (s/latest! q project (s/record! q project "change" rid))
            decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")
            note (s/text! body :note 500)]
        (s/status! record #{"in_review"})
        (when-not (:escalated record) (r/fail! 409 "该变更未触发升级, 无需确认"))
        (when-not (= "pending" (:escalation_state record)) (r/fail! 409 "变更升级已确认, 请勿重复处理"))
        (when (= (:user_id actor) (:created_by record)) (r/fail! 403 "升级确认不得由变更登记人本人完成"))
        (when (= (:user_id actor) (:submitted_by record)) (r/fail! 403 "升级确认不得由提交人本人完成"))
        (s/change! q project record (:status record)
                   {:escalation_state (if (= "approved" decision) "acknowledged" "waived")
                    :escalation_decision decision :escalation_note note
                    :escalation_ack_by (:user_id actor) :escalation_ack_on (str (LocalDate/now))
                    :workflow_history (conj (vec (:workflow_history record))
                                            {:action "escalation_acknowledged" :actor_id (:user_id actor)
                                             :on (str (LocalDate/now)) :decision decision})})))))

(defn set-ccb!
  "为变更申请登记或重置变更控制委员会名单与通过门槛; 重新登记将清空既有表决记录, 须重新表决; 只读质量审批人亦可登记."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "change.ccb-set" {:write? false}
    (fn [q project]
      (s/input! body [:members :required])
      (let [record (s/latest! q project (s/record! q project "change" rid))
            raw (:members body)]
        (when-not (and (vector? raw) (pos? (count raw)) (<= (count raw) 15)
                       (= (count raw) (count (set raw))))
          (r/fail! 400 "委员会成员须为1至15个不重复的用户标识数组"))
        (s/status! record #{"draft" "in_review"})
        (let [members (mapv #(s/user! q %) raw)
              required (r/positive-id! (:required body) "通过门槛")]
          (when (> required (count members))
            (r/fail! 400 "通过门槛不得超过委员会成员人数"))
          (s/change! q project record (:status record)
            {:ccb_members members
             :ccb_required required
             :ccb_ballots []
             :workflow_history
             (conj (vec (:workflow_history record))
               {:action "ccb_set" :actor_id (:user_id actor)
                :on (str (LocalDate/now))
                :member_count (count members)
                :required required})}))))))

(defn cast-ccb-ballot!
  "变更控制委员会成员投下赞成或反对票, 重复投票覆盖本人上一票; 仅委员会成员且非登记人/提交人可投票."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "change.ccb-ballot" {:write? false}
    (fn [q project]
      (s/input! body [:vote :note])
      (let [record (s/latest! q project (s/record! q project "change" rid))
            vote (s/enum! (:vote body) #{"approve" "reject"} "表决")
            note (s/optional-text! body :note 500)
            uid (:user_id actor)
            members (vec (:ccb_members record))]
        (s/status! record #{"in_review"})
        (when (empty? members) (r/fail! 409 "该变更尚未设立变更控制委员会, 无法表决"))
        (when (= uid (:created_by record)) (r/fail! 403 "变更登记人不得参与自身变更表决"))
        (when (= uid (:submitted_by record)) (r/fail! 403 "变更提交人不得参与自身变更表决"))
        (when-not (some #(= % uid) members) (r/fail! 403 "仅变更控制委员会成员可投票"))
        (let [ballots (vec (:ccb_ballots record))
              others (filterv #(not= (:member_id %) uid) ballots)
              cast (conj others {:member_id uid :vote vote :note note
                                 :on (str (LocalDate/now))})]
          (s/change! q project record (:status record)
            {:ccb_ballots cast
             :workflow_history
             (conj (vec (:workflow_history record))
               {:action "ccb_ballot" :actor_id uid
                :on (str (LocalDate/now)) :vote vote})}))))))


(defn approved-change!
  "验证后续基线命令所引用的变更确已通过独立批准."
  [q project rid]
  (let [record (s/latest! q project (s/record! q project "change" rid))]
    (s/status! record #{"approved"})
    record))

(defn change-read-model
  "为变更读模型补充只读派生高影响判定: 工期影响达到阈值或成本影响金额达到阈值时 change_high_impact 为 true; 仅读取时计算, 不落存储."
  [record]
  (-> record
      (assoc :change_high_impact (high-impact? record))
      (assoc :ccb_summary (ccb-tally record))))


(defn change-closure-summary
  "项目级变更控制闭环只读汇总: 按变更最新有效版本(s/latest, 修订链只计最新版)统计总数与各工作流状态(草稿/审批中/已批准/已驳回)计数与已批准率, 高影响升级面(复用 high-impact? 阈值单一口径)与独立确认处置推进(pending/acknowledged/waived), 以及变更控制委员会表决状态分布(复用 ccb-tally: none/voting/passed/failed 与已达门槛数); 只读派生, 不落库不投递, 不改变任何变更状态或门控. 键名不带尾随问号."
  [changes]
  (let [active (s/latest changes)
        total (count active)
        status-count (fn [st] (count (filterv #(= st (:status %)) active)))
        approved (status-count "approved")
        esc-count (fn [st] (count (filterv #(and (:escalated %) (= st (:escalation_state %))) active)))
        ccb-count (fn [st] (count (filterv #(= st (:state (ccb-tally %))) active)))
        quorum (count (filterv #(true? (:quorum_met (ccb-tally %))) active))]
    {:available (pos? total)
     :total total
     :draft (status-count "draft")
     :in-review (status-count "in_review")
     :approved approved
     :rejected (status-count "rejected")
     :approval-pct (if (pos? total)
                    (int (Math/round ^double (/ (* 100.0 approved) total)))
                    0)
     :high-impact (count (filterv high-impact? active))
     :escalated (count (filterv :escalated active))
     :pending (esc-count "pending")
     :acknowledged (esc-count "acknowledged")
     :waived (esc-count "waived")
     :ccb-none (ccb-count "none")
     :ccb-voting (ccb-count "voting")
     :ccb-passed (ccb-count "passed")
     :ccb-failed (ccb-count "failed")
     :ccb-quorum quorum}))


(defn- finalize-charter!
  "审批链落定章程: 末级通过即批准, 任一级驳回即退回."
  [q actor project rid decision reason]
  (let [record (s/latest! q project (s/record! q project "charter" rid))]
    (s/status! record #{"in_review"})
    (s/change! q project record decision {:decision_reason (or reason "") :decided_by (:user_id actor)})))


(chain/register-adapter! "charter" {:finalize finalize-charter!})
