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


(defn- owner!
  "可选责任人字段, 提供时必须是当前项目有效成员."
  [q project body]
  (when-let [owner (:owner_id body)] (k/user! q project owner "责任人")))


(defn- stakeholder-fields!
  "校验干系人编号, 名称, 职责, 分类, 关注度与影响力."
  [q project body]
  (s/input! body [:code :name :role :category :interest :influence :owner_id])
  {:code (s/text! body :code 100)
   :name (s/text! body :name 200)
   :role (s/text! body :role 200)
   :category (s/enum! (:category body) categories "category")
   :interest (s/enum! (:interest body) grades "interest")
   :influence (s/enum! (:influence body) grades "influence")
   :owner_id (owner! q project body)})


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
  "记录沟通计划一次实际沟通, 按既定频率顺延下次沟通日期并保留可审计留痕."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "comm-plan.logged"
             (fn [q project]
               (s/input! body [:on :note])
               (let [plan (s/latest! q project (s/record! q project "comm-plan" rid))
                     on (if (:on body) (s/date! body :on) (str (LocalDate/now)))
                     note (s/optional-text! body :note 500)
                     next-date (str (.plusDays (LocalDate/parse on) (get cadence-days (:frequency plan))))]
                 (s/change! q project plan (:status plan)
                            {:last_communicated_on on :last_communication_note note :next_date next-date
                             :communication_log (conj (vec (:communication_log plan))
                                                      {:on on :note note :next_date next-date})})))))


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
  "以影响力与关注度派生权力-利益管理象限, 并标记是否尚未绑定项目成员责任人; 只读计算不改状态."
  [stakeholder]
  (assoc stakeholder :stakeholder_quadrant (quadrant-of stakeholder)
                     :stakeholder_unbound (not (:owner_id stakeholder))))


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
