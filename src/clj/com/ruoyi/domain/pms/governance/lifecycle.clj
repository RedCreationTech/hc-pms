(ns com.ruoyi.domain.pms.governance.lifecycle
  "治理记录的受控作废: 不把取消当物理删除, 校验状态与跨对象引用后软置为 discarded 并保留审计痕迹."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(def discardable-status
  "各类型允许被作废的当前状态集合, 已进入审批或终态的记录不得直接作废."
  {"requirement" #{"registered"}
   "document" #{"registered" "rejected"}
   "stakeholder" #{"active"}})


(defn- references-of
  "列出仍引用该记录的其它治理对象, 供拒绝作废时给出可读原因."
  [q project kind rid]
  (case kind
    "requirement"
    (for [t (s/records q project "trace") :when (= rid (:requirement_id t))]
      (str "需求追踪 " (:code t)))

    "document"
    (concat
      (for [t (s/records q project "trace")
            :when (and (= "document" (:target_kind t)) (= rid (:target_id t)))]
        (str "需求追踪 " (:code t)))
      (for [m (s/records q project "meeting") :when (some #{rid} (:material_ids m))]
        (str "会议 " (:title m)))
      (for [i (s/records q project "issue")
            :when (or (some #{rid} (:evidence_ids i)) (some #{rid} (:reopen_evidence_ids i)))]
        (str "问题 " (:title i)))
      (for [rk (s/records q project "risk")
            :when (or (some #{rid} (:evidence_ids rk)) (some #{rid} (:review_evidence_ids rk)))]
        (str "风险 " (:title rk)))
      (for [a (s/records q project "action") :when (some #{rid} (:evidence_ids a))]
        (str "行动 " (:title a))))

    "stakeholder"
    (concat
      (for [rc (s/records q project "raci") :when (= rid (:stakeholder_id rc))]
        (str "RACI " (:activity rc)))
      (for [c (s/records q project "comm-plan") :when (some #{rid} (:audience c))]
        (str "沟通计划 " (:code c))))

    []))


(defn discard!
  "受控作废最新版本记录: 校验可作废状态, 拒绝仍被引用的对象, 软置为 discarded 并留审计, 绝不物理删除."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".discarded")
    (fn [q project]
      (s/input! body [:reason])
      (let [record (s/record! q project kind rid)
            _ (s/latest! q project record)
            _ (s/status! record (get discardable-status kind #{}))
            refs (references-of q project kind rid)
            reason (s/optional-text! body :reason 500)]
        (when (seq refs)
          (r/fail! 409 (str "记录仍被其它对象引用, 不能作废: " (str/join "、" (take 5 refs)))))
        (s/change! q project record "discarded"
                   {:discard_reason reason :discarded_by (:user_id actor) :discarded_on (str (LocalDate/now))
                    :workflow_history (conj (vec (:workflow_history record))
                                            {:action "discarded" :actor_id (:user_id actor)
                                             :on (str (LocalDate/now)) :prior_status (:status record)
                                             :reason reason})})))))


(defn- prior-status-before-discard
  "从 workflow_history 里最近一条 discarded 审计项取回作废前状态; 无则返回 nil."
  [record]
  (some->> (:workflow_history record)
           (filterv #(= "discarded" (:action %)))
           (last)
           (:prior_status)))


(defn restore!
  "受控撤销作废: 只把处于 discarded 的最新版本恢复到作废前状态, 保留审计, 不重放任何副作用."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".restored")
    (fn [q project]
      (s/input! body [:reason])
      (let [record (s/record! q project kind rid)
            _ (s/latest! q project record)
            _ (s/status! record #{"discarded"})
            prior (prior-status-before-discard record)
            reason (s/optional-text! body :reason 500)]
        (when (nil? prior)
          (r/fail! 409 "无法确定作废前状态, 不能恢复"))
        (s/change! q project record prior
                   {:restore_reason reason :restored_by (:user_id actor) :restored_on (str (LocalDate/now))
                    :workflow_history (conj (vec (:workflow_history record))
                                            {:action "restored" :actor_id (:user_id actor)
                                             :on (str (LocalDate/now)) :prior_status "discarded"
                                             :restored_to prior :reason reason})})))))
