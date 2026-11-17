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
   "stakeholder" #{"active"}
   "meeting" #{"recorded"}})


(defn- reference-rules
  "各类型仍引用该记录的检索规则, 逐条给出 [源类型 谓词 文案函数]; 规则顺序即引用清单输出顺序, 未登记的类型给空规则."
  [kind rid]
  (case kind
    "requirement"
    [["trace" (fn [t] (= rid (:requirement_id t)))
      (fn [t] (str "需求追踪 " (:code t)))]]

    "document"
    [["trace" (fn [t] (and (= "document" (:target_kind t)) (= rid (:target_id t))))
      (fn [t] (str "需求追踪 " (:code t)))]
     ["meeting" (fn [m] (some #{rid} (:material_ids m)))
      (fn [m] (str "会议 " (:title m)))]
     ["issue" (fn [i] (or (some #{rid} (:evidence_ids i)) (some #{rid} (:reopen_evidence_ids i))))
      (fn [i] (str "问题 " (:title i)))]
     ["risk" (fn [rk] (or (some #{rid} (:evidence_ids rk)) (some #{rid} (:review_evidence_ids rk))))
      (fn [rk] (str "风险 " (:title rk)))]
     ["action" (fn [a] (some #{rid} (:evidence_ids a)))
      (fn [a] (str "行动 " (:title a)))]]

    "stakeholder"
    [["raci" (fn [rc] (= rid (:stakeholder_id rc)))
      (fn [rc] (str "RACI " (:activity rc)))]
     ["comm-plan" (fn [c] (some #{rid} (:audience c)))
      (fn [c] (str "沟通计划 " (:code c)))]]

    "meeting"
    [["action" (fn [a] (= rid (:meeting_id a)))
      (fn [a] (str "行动 " (:title a)))]
     ["comm-plan" (fn [c] (= rid (:last_meeting_id c)))
      (fn [c] (str "沟通计划 " (:code c)))]]

    []))


(defn- references-of
  "列出仍引用该记录的其它治理对象, 供拒绝作废时给出可读原因."
  [q project kind rid]
  (mapcat (fn [[source pred label]]
            (for [rec (s/records q project source) :when (pred rec)] (label rec)))
          (reference-rules kind rid)))


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


(defn discard-preview
  "只读级联影响预览: 报告若对该记录发起受控作废将命中的状态门控与引用清单, 不写入不改变任何状态."
  [svc actor id kind rid]
  (k/read! svc actor id "pms:project:query"
           (fn [q project]
             (let [record (s/record! q project kind rid)
                   newer? (some #(and (= (:code %) (:code record)) (> (:revision %) (:revision record)))
                                (s/records q project kind))
                   status (:status record)
                   status-ok? (boolean (contains? (get discardable-status kind #{}) status))
                   refs (references-of q project kind rid)]
               {:kind kind :record_id rid :code (:code record) :revision (:revision record)
                :status status :latest? (not newer?) :status_discardable? status-ok?
                :references (vec refs)
                :discardable? (boolean (and (not newer?) status-ok? (empty? refs)))}))))
