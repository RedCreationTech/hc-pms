(ns com.ruoyi.domain.pms.governance.stakeholders
  "H02 干系人识别, RACI职责矩阵与沟通计划: 明确利益相关者及职责, 知会/参与/批准关系, 沟通节奏可执行并保留受控调整记录."
  (:require
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(def categories
  "干系人业务分类."
  #{"internal" "external" "supplier" "customer" "regulator"})


(def grades
  "关注度与影响力等级."
  #{"high" "medium" "low"})


(def responsibilities
  "RACI职责角色: 执行R, 负责A, 咨询C, 知会I."
  #{"R" "A" "C" "I"})


(def channels
  "沟通渠道."
  #{"meeting" "email" "dashboard" "report" "review"})


(def cadences
  "沟通节奏频率."
  #{"daily" "weekly" "biweekly" "monthly" "quarterly"})


(def engagement-levels
  "干系人参与态度 (PMBOK 投入度评估矩阵: 未知晓/抵制/中立/支持/主导)."
  #{"unaware" "resistant" "neutral" "supportive" "leading"})


(def engagement-order
  "PMBOK 投入度评估由低到高的参与态度序列, 用于计算当前态度与期望态度之间的差距档数."
  ["unaware" "resistant" "neutral" "supportive" "leading"])


(defn- engagement-assessment
  "按 engagement-order 计算当前态度到期望态度的差距: 返回 {:state gap} 二元组, state 取 up (需提升), down (需降低), on (达标) 或 unmarked (当前或期望任一未落在受控枚举)."
  [current desired]
  (let [ci (.indexOf ^java.util.List engagement-order current)
        di (.indexOf ^java.util.List engagement-order desired)]
    (if (or (neg? ci) (neg? di))
      ["unmarked" nil]
      (let [gap (- di ci)]
        [(cond (pos? gap) "up" (neg? gap) "down" :else "on") gap]))))


(defn engagement-assessment-matrix
  "按每个干系人业务编码最新有效版本统计 PMBOK 参与态度评估矩阵 (当前态度 vs 期望态度) 的只读派生: 逐档差距 gap = 期望序 - 当前序, 分类为达标 (gap=0), 需提升 (gap>0), 需降低 (gap<0) 与未标注 (缺当前或期望态度); 给出干系人总数/已标注/达标/需提升/需降低/未标注/需提升合计档数/达标率与需提升与需降低干系人清单 (编号+名称+当前+期望+差距档数, 需提升按差距降序). 最新版本被受控作废 (discarded) 的编号不计入. 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控."
  [stakeholders]
  (let [active (filterv #(not= "discarded" (:status %)) (s/latest stakeholders))
        assessed (mapv (fn [row]
                         (let [[state gap] (engagement-assessment (:engagement row) (:desired_engagement row))]
                           {:state state :gap gap :code (:code row) :name (:name row)
                            :current (:engagement row) :desired (:desired_engagement row)}))
                       active)
        state-count (fn [st] (count (filterv #(= st (:state %)) assessed)))
        total (count assessed)
        on (state-count "on")
        up (state-count "up")
        down (state-count "down")
        unmarked (state-count "unmarked")
        marked (- total unmarked)
        up-steps (reduce + 0 (keep #(when (= "up" (:state %)) (:gap %)) assessed))
        pick (fn [st]
               (let [rows (filterv #(= st (:state %)) assessed)]
                 (->> (if (= st "up") (sort-by :gap > rows) (sort-by :gap rows))
                      (mapv #(select-keys % [:code :name :current :desired :gap]))
                      (vec))))]
    {:available (pos? total)
     :total total
     :marked marked
     :on-target on
     :need-up up
     :need-down down
     :unmarked unmarked
     :up-steps up-steps
     :on-target-pct (if (pos? marked)
                      (int (Math/round ^double (* 100.0 (/ on marked))))
                      0)
     :need-up-stakeholders (pick "up")
     :need-down-stakeholders (pick "down")}))


(defn- owner!
  "可选责任人字段, 提供时必须是当前项目有效成员."
  [q project body]
  (when-let [owner (:owner_id body)] (k/user! q project owner "责任人")))


(defn- stakeholder-fields!
  "校验干系人编号, 名称, 职责, 分类, 关注度, 影响力与可选当前/期望参与态度."
  [q project body]
  (s/input! body [:code :name :role :category :interest :influence :engagement :desired_engagement :owner_id])
  (cond-> {:code (s/text! body :code 100)
           :name (s/text! body :name 200)
           :role (s/text! body :role 200)
           :category (s/enum! (:category body) categories "category")
           :interest (s/enum! (:interest body) grades "interest")
           :influence (s/enum! (:influence body) grades "influence")
           :owner_id (owner! q project body)}
    (:engagement body) (assoc :engagement (s/enum! (:engagement body) engagement-levels "参与态度"))
    (:desired_engagement body) (assoc :desired_engagement
                                      (s/enum! (:desired_engagement body) engagement-levels "期望参与态度"))))


(defn- code-unused!
  "确保同项目同类型业务编号未被占用."
  [q project kind code]
  (when (some #(= code (:code %)) (s/records q project kind))
    (r/fail! 409 (str kind "编号已存在"))))


(defn create-stakeholder!
  "登记项目干系人首版, 编号在同项目内唯一."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "stakeholder.created"
             (fn [q project]
               (let [fields (stakeholder-fields! q project body)]
                 (code-unused! q project "stakeholder" (:code fields))
                 (s/insert! q project actor "stakeholder" fields {:status "active"})))))


(defn revise-stakeholder!
  "保留干系人编号新增不可变修订并回指前一版."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "stakeholder.revised"
             (fn [q project]
               (let [old (s/latest! q project (s/record! q project "stakeholder" rid))
                     fields (stakeholder-fields! q project body)]
                 (when-not (= (:code old) (:code fields)) (r/fail! 400 "修订不得改变干系人编号"))
                 (s/insert! q project actor "stakeholder" (assoc fields :previous_id rid)
                            {:revision (inc (:revision old)) :status "active"})))))


(defn- active-stakeholder!
  "确认引用的干系人存在且处于有效状态."
  [q project sid]
  (let [stakeholder (s/record! q project "stakeholder" sid)]
    (s/status! stakeholder #{"active"})
    stakeholder))


(defn create-raci!
  "为具体活动指派确定职责, 保证每项活动至多一个负责(A)角色且不重复指派."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "raci.assigned"
             (fn [q project]
               (s/input! body [:activity :stakeholder_id :responsibility])
               (let [activity (s/text! body :activity 200)
                     stakeholder (active-stakeholder! q project (:stakeholder_id body))
                     responsibility (s/enum! (:responsibility body) responsibilities "responsibility")
                     same (filterv #(= activity (:activity %)) (s/records q project "raci"))]
                 (when (some #(= (:id stakeholder) (:stakeholder_id %)) same)
                   (r/fail! 409 "同一活动不得重复指派同一干系人"))
                 (when (and (= "A" responsibility) (some #(= "A" (:responsibility %)) same))
                   (r/fail! 409 "该活动已存在负责(A)角色, 批准职责不得重复"))
                 (s/insert! q project actor "raci"
                            {:code (str "RACI:" activity ":" (:id stakeholder))
                             :activity activity :stakeholder_id (:id stakeholder)
                             :stakeholder_name (:name stakeholder) :responsibility responsibility}
                            {:status "assigned"})))))


(defn conflicts
  "按活动汇总RACI完整性缺口: 缺少负责(A)或缺少执行(R)即视为冲突, 供工作台冲突检查."
  [q project]
  (->> (group-by :activity (s/records q project "raci"))
       (mapv (fn [[activity rows]]
               {:activity activity
                :missing-accountable? (not (some #(= "A" (:responsibility %)) rows))
                :missing-responsible? (not (some #(= "R" (:responsibility %)) rows))}))
       (filterv (fn [m] (or (:missing-accountable? m) (:missing-responsible? m))))
       (sort-by :activity)
       (vec)))


(defn- audience!
  "沟通受众必须为同项目有效干系人记录且不重复, 返回其ID向量."
  [q project ids]
  (when-not (and (vector? ids) (<= 1 (count ids) 50) (= (count ids) (count (set ids))))
    (r/fail! 400 "沟通受众必须为1到50个不重复的干系人ID数组"))
  (mapv :id (mapv #(active-stakeholder! q project %) ids)))


(defn- comm-plan-fields!
  "校验沟通计划编号, 目标, 渠道, 频率, 受众与下次日期."
  [q project body]
  (s/input! body [:code :objective :channel :frequency :audience :next_date :owner_id])
  {:code (s/text! body :code 100)
   :objective (s/text! body :objective 500)
   :channel (s/enum! (:channel body) channels "channel")
   :frequency (s/enum! (:frequency body) cadences "frequency")
   :audience (audience! q project (:audience body))
   :next_date (s/date! body :next_date)
   :owner_id (owner! q project body)})


(defn create-comm-plan!
  "登记项目沟通计划首版, 编号在同项目内唯一."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "comm-plan.created"
             (fn [q project]
               (let [fields (comm-plan-fields! q project body)]
                 (code-unused! q project "comm-plan" (:code fields))
                 (s/insert! q project actor "comm-plan" fields {:status "active"})))))


(defn revise-comm-plan!
  "保留沟通计划编号新增受控修订, 形成可审计的节奏调整记录."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "comm-plan.revised"
             (fn [q project]
               (let [old (s/latest! q project (s/record! q project "comm-plan" rid))
                     fields (comm-plan-fields! q project body)]
                 (when-not (= (:code old) (:code fields)) (r/fail! 400 "修订不得改变沟通计划编号"))
                 (s/insert! q project actor "comm-plan" (assoc fields :previous_id rid)
                            {:revision (inc (:revision old)) :status "active"})))))


(defn- attendee-ids
  "收集沟通计划受众干系人已绑定的项目成员用户ID, 去重为参会人."
  [q project audience]
  (->> audience
       (mapcat (fn [sid]
                 (when-let [owner (:owner_id (s/record! q project "stakeholder" sid))]
                   [owner])))
       (distinct)
       (vec)))


(defn materialize-meeting!
  "由沟通计划生成一次受控会议记录并回写来源, 形成沟通计划到会议的闭环."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "comm-plan.meeting-created"
             (fn [q project]
               (s/input! body [:held_on])
               (let [plan (s/latest! q project (s/record! q project "comm-plan" rid))
                     attendee (attendee-ids q project (:audience plan))
                     _ (when (empty? attendee) (r/fail! 409 "沟通计划受众未绑定有效项目成员, 无法生成会议"))
                     held-on (if (:held_on body) (s/date! body :held_on) (:next_date plan))
                     meeting (s/insert! q project actor "meeting"
                                        {:title (str "沟通计划会议: " (:objective plan))
                                         :held_on held-on
                                         :minutes (str "由沟通计划 " rid " 生成, 渠道 " (:channel plan) ", 频率 " (:frequency plan))
                                         :attendee_ids attendee
                                         :source_comm_plan_id rid}
                                        {:status "recorded"})]
                 (s/change! q project plan (:status plan) {:last_meeting_id (:id meeting)})
                 meeting))))


(def cadence-days
  "沟通节奏频率到推进天数的映射, 用于标记已沟通后顺延下次沟通日期."
  {"daily" 1 "weekly" 7 "biweekly" 14 "monthly" 30 "quarterly" 90})


(defn log-communication!
  "记录沟通计划一次实际沟通, 按既定频率顺延下次沟通日期并保留可审计留痕; 可选标注本次实际沟通渠道, 缺省沿用计划渠道."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "comm-plan.logged"
             (fn [q project]
               (s/input! body [:on :note :channel])
               (let [plan (s/latest! q project (s/record! q project "comm-plan" rid))
                     on (if (:on body) (s/date! body :on) (str (LocalDate/now)))
                     note (s/optional-text! body :note 500)
                     channel (if-let [c (:channel body)] (s/enum! c channels "沟通方式") (:channel plan))
                     next-date (str (.plusDays (LocalDate/parse on) (get cadence-days (:frequency plan))))]
                 (s/change! q project plan (:status plan)
                            {:last_communicated_on on :last_communication_note note
                             :last_communication_channel channel :next_date next-date
                             :communication_log (conj (vec (:communication_log plan))
                                                      {:on on :note note :channel channel :next_date next-date})})))))


(defn comm-plan-read-model
  "以服务器日期展示沟通计划下次沟通是否到期及剩余天数, 供到期预警; 无有效日期时不计到期."
  [plan]
  (if-let [next (when-let [s (:next_date plan)]
                  (try (LocalDate/parse s) (catch Exception _ nil)))]
    (assoc plan :comm_overdue (boolean (and (= "active" (:status plan)) (not (.isAfter next (LocalDate/now)))))
                :comm_days_until (int (- (.toEpochDay next) (.toEpochDay (LocalDate/now)))))
    (assoc plan :comm_overdue false :comm_days_until nil)))


(def quadrant-labels
  "权力-利益矩阵四象限对应的干系人管理策略."
  {"manage-close" "重点管理" "keep-satisfied" "保持满意" "keep-informed" "保持知会" "monitor" "持续监控"})


(defn- quadrant-of
  "按干系人影响力(权力)与关注度定位权力-利益象限."
  [stakeholder]
  (let [hi-influence (= "high" (:influence stakeholder))
        hi-interest (= "high" (:interest stakeholder))]
    (cond (and hi-influence hi-interest) "manage-close"
          hi-influence "keep-satisfied"
          hi-interest "keep-informed"
          :else "monitor")))


(defn stakeholder-read-model
  "以影响力与关注度派生权力-利益管理象限, 标记是否尚未绑定项目成员责任人, 并按当前与期望参与态度派生投入度评估差距 (gap 为整数档数, state 取 up/down/on/unmarked); 只读计算不改状态."
  [stakeholder]
  (let [[state gap] (engagement-assessment (:engagement stakeholder) (:desired_engagement stakeholder))]
    (assoc stakeholder :stakeholder_quadrant (quadrant-of stakeholder)
                       :stakeholder_unbound (not (:owner_id stakeholder))
                       :stakeholder_engagement_state state
                       :stakeholder_engagement_gap gap)))


(defn engagement-coverage
  "按每个干系人业务编码最新有效版本统计参与态度声明的只读覆盖度: PMBOK五类态度(未知晓/抵制/中立/支持/主导)各自计数, 已声明/未设定与百分比覆盖率; 最新版本被受控作废(discarded)的编号不计入. 只读派生, 不落库不投递, 不改变不可变版本."
  [stakeholders]
  (let [levels ["unaware" "resistant" "neutral" "supportive" "leading"]
        active (filterv #(not= "discarded" (:status %)) (s/latest stakeholders))
        total (count active)
        declared (count (filterv #(some #{(:engagement %)} levels) active))
        level-count (fn [l] (count (filterv #(= l (:engagement %)) active)))]
    {:total total
     :declared declared
     :undeclared (- total declared)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :by-engagement (mapv (fn [l] {:engagement l :count (level-count l)}) levels)}))


(defn comm-audience-coverage
  "按每个干系人与沟通计划业务编码最新有效版本(store/latest 折叠修订链)统计有多少有效干系人被至少一条活动沟通计划的受众覆盖的只读覆盖度: 干系人总数/沟通计划数/已覆盖/未覆盖与百分比, 并列出未覆盖干系人(编号+名称). 最新版本被受控作废(discarded)的干系人或沟通计划均不计入. 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控."
  [stakeholders comm-plans]
  (let [active-stakeholders (filterv #(not= "discarded" (:status %)) (s/latest stakeholders))
        active-plans (filterv #(not= "discarded" (:status %)) (s/latest comm-plans))
        covered-ids (into #{} (mapcat :audience) active-plans)
        covered? (fn [stakeholder] (contains? covered-ids (:id stakeholder)))
        total (count active-stakeholders)
        covered (count (filterv covered? active-stakeholders))
        uncovered (mapv (fn [stakeholder] {:code (:code stakeholder) :name (:name stakeholder)})
                        (remove covered? active-stakeholders))]
    {:available (pos? total)
     :total total
     :plans (count active-plans)
     :covered covered
     :uncovered (- total covered)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ covered total))))
                     0)
     :uncovered-stakeholders uncovered}))


(defn comm-execution-coverage
  "按每个沟通计划业务编码最新有效版本(store/latest 折叠修订链)统计有多少活动沟通计划已实际执行落地的只读覆盖度: 已标记至少一次沟通(communication_log 非空)或已生成至少一次会议(last_meeting_id 存在)者视为已执行, 两者皆无者为尚未执行, 给出总数/已执行/尚未执行/已标记沟通/已生成会议与执行率, 并列出尚未执行计划(编号+目标). 最新版本被受控作废(discarded)的沟通计划不计入. 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控; 与沟通节奏到期面板互补(到期看距下次沟通的时间分布而本项看是否已实际执行到位)."
  [comm-plans]
  (let [active-plans (filterv #(not= "discarded" (:status %)) (s/latest comm-plans))
        logged? (fn [plan] (pos? (count (:communication_log plan))))
        met? (fn [plan] (some? (:last_meeting_id plan)))
        executed? (fn [plan] (or (logged? plan) (met? plan)))
        total (count active-plans)
        executed (count (filterv executed? active-plans))
        logged (count (filterv logged? active-plans))
        met (count (filterv met? active-plans))
        not-executed (mapv (fn [plan] {:code (:code plan) :objective (:objective plan)})
                           (remove executed? active-plans))]
    {:available (pos? total)
     :total total
     :executed executed
     :not-executed (- total executed)
     :logged logged
     :met met
     :execution-pct (if (pos? total)
                      (int (Math/round ^double (* 100.0 (/ executed total))))
                      0)
     :not-executed-plans not-executed}))


(defn raci-assignment-completeness
  "按活动汇总RACI职责分配完整度的只读覆盖度: 逐活动判断是否至少指派一个负责(A)与一个执行(R), 两者齐备视为完整, 给出活动总数/完整/缺负责A/缺执行R与覆盖率, 并列出未完整活动及其缺项. RACI指派行不按修订链折叠(与逐条冲突检查conflicts一致直接消费原始行). 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控; 与逐条冲突检查互补(conflicts只返回冲突子集而本项给出项目级正向覆盖率与完整分布)."
  [raci-rows]
  (let [by-activity (group-by :activity raci-rows)
        has-a? (fn [rows] (some #(= "A" (:responsibility %)) rows))
        has-r? (fn [rows] (some #(= "R" (:responsibility %)) rows))
        activity-flags (mapv (fn [[activity rows]]
                               {:activity activity
                                :missing-accountable (not (has-a? rows))
                                :missing-responsible (not (has-r? rows))})
                             by-activity)
        incomplete? (fn [flags] (or (:missing-accountable flags) (:missing-responsible flags)))
        total (count activity-flags)
        complete (count (remove incomplete? activity-flags))
        missing-accountable (count (filterv :missing-accountable activity-flags))
        missing-responsible (count (filterv :missing-responsible activity-flags))
        incomplete (->> (filterv incomplete? activity-flags)
                        (sort-by :activity)
                        (vec))]
    {:available (pos? total)
     :total total
     :complete complete
     :missing-accountable missing-accountable
     :missing-responsible missing-responsible
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ complete total))))
                     0)
     :incomplete-activities incomplete}))


(def raci-overload-threshold
  "同一干系人承担执行(R)职责的活动数达到该值即视为负载过重."
  3)


(defn raci-r-loads
  "统计每个干系人被指派为执行(R)的职责数量, 供RACI职责负载与过载预警."
  [raci-rows]
  (frequencies (keep #(when (= "R" (:responsibility %)) (:stakeholder_id %)) raci-rows)))


(defn raci-read-model
  "为RACI指派行补充该干系人的执行R总负载与是否过载; 只读计算不改状态."
  [r-loads row]
  (let [load (get r-loads (:stakeholder_id row) 0)]
    (assoc row :raci_r_load load
               :raci_overloaded (>= load raci-overload-threshold))))


(defn raci-engagement-coverage
  "按活动汇总RACI咨询(C)与知会(I)角色配置覆盖度的只读派生: 逐活动判断是否至少指派一个咨询(C)与一个知会(I), 两者齐备视为充分咨询知会, 给出活动总数/含咨询/含知会/两者齐备与覆盖度, 并列出配置单薄活动及其缺项. RACI指派行不按修订链折叠(与raci-assignment-completeness及逐条冲突检查conflicts一致直接消费原始行). 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控; 与只查负责A与执行R的完整度互补(完整度回答谁做谁负责而本项回答决策有没有被充分咨询、相关方有没有被知会)."
  [raci-rows]
  (let [by-activity (group-by :activity raci-rows)
        has-c? (fn [rows] (some #(= "C" (:responsibility %)) rows))
        has-i? (fn [rows] (some #(= "I" (:responsibility %)) rows))
        activity-flags (mapv (fn [[activity rows]]
                               {:activity activity
                                :with-consult (has-c? rows)
                                :with-inform (has-i? rows)})
                             by-activity)
        fully? (fn [flags] (and (:with-consult flags) (:with-inform flags)))
        total (count activity-flags)
        with-consult (count (filterv :with-consult activity-flags))
        with-inform (count (filterv :with-inform activity-flags))
        fully-engaged (count (filterv fully? activity-flags))
        thin (->> (remove fully? activity-flags)
                  (mapv (fn [flags]
                          {:activity (:activity flags)
                           :missing-consult (not (:with-consult flags))
                           :missing-inform (not (:with-inform flags))}))
                  (sort-by :activity)
                  (vec))]
    {:available (pos? total)
     :total total
     :with-consult with-consult
     :with-inform with-inform
     :fully-engaged fully-engaged
     :engagement-pct (if (pos? total)
                       (int (Math/round ^double (* 100.0 (/ fully-engaged total))))
                       0)
     :thin-activities thin}))
