(ns com.ruoyi.domain.pms.delivery.store
  "交付执行对象的受控记录与共享校验,不暴露任意JSON写入."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.time LocalDate]))


(def kinds "交付对象白名单." #{"configuration" "material" "bom" "assembly" "test" "shipment" "service" "survey" "handover" "site-task"})


(defn records
  "读取同项目同类型交付记录."
  [q project kind]
  (g/enum! kind kinds "kind")
  (mapv g/decode (q :delivery/list {:project_id (:project_id project) :kind kind})))


(defn record!
  "验证同项目强类型引用."
  [q project kind rid]
  (when-not (string? rid) (r/fail! 400 "引用ID必须为字符串"))
  (let [record (g/decode (q :delivery/record {:project_id (:project_id project) :record_id rid}))]
    (when-not (and record (= kind (:kind record))) (r/fail! 404 "交付对象不存在或不属于本项目"))
    record))


(defn insert!
  "插入经过业务模式校验的新对象."
  [q project actor kind fields status]
  (g/enum! kind kinds "kind")
  (let [rid (k/id)]
    (q :delivery/insert!
       {:record_id rid :project_id (:project_id project) :kind kind
        :code (or (:code fields) rid) :revision 1 :status status :created_by (:user_id actor)
        :owner_id (:owner_id fields)
        :payload (json/generate-string (assoc (dissoc fields :code :owner_id) :created_project_version (inc (:version project))))})
    (record! q project kind rid)))


(defn change!
  "按已校验业务命令更新记录,保留身份与创建时间."
  [q project record status patch]
  (let [payload (apply dissoc (merge record patch)
                       [:id :record_id :project_id :kind :code :revision :status
                        :created_by :owner_id :created_at :updated_at])]
    (r/changed! (q :delivery/update!
                   {:record_id (:id record) :project_id (:project_id project) :status status
                    :owner_id (or (:owner_id patch) (:owner_id record)) :payload (json/generate-string payload)}))
    (record! q project (:kind record) (:id record))))


(defn mutate!
  "复用聚合事务,乐观锁,项目范围与同事务审计."
  [svc actor id body event review? f]
  (k/mutate! svc actor id (if review? "pms:quality:approve" "pms:project:edit")
             body event {:write? (not review?)} f))


(defn references!
  "要求实际任务以及一至五十个确切URS版本,防止跨项目追踪."
  [q project body]
  (let [task-id (g/text! body :task_id 36) ids (:requirement_ids body)]
    (when-not (q :planning/task {:project_id (:project_id project) :task_id task-id})
      (r/fail! 404 "关联任务不存在或不属于本项目"))
    (when-not (and (vector? ids) (<= 1 (count ids) 50) (= (count ids) (count (set ids))))
      (r/fail! 400 "requirement_ids必须为1到50个不重复的需求版本ID"))
    (doseq [rid ids] (g/record! q project "requirement" rid))
    {:task_id task-id :requirement_ids ids}))


(defn owner!
  "校验执行责任人仍是有效项目成员."
  [q project uid]
  (k/user! q project uid "执行负责人"))


(defn actual-date!
  "验证已发生事实日期不得晚于服务器今天,计划日期不受此限制."
  [body key]
  (let [date (g/date! body key)]
    (when (.isAfter (LocalDate/parse date) (LocalDate/now))
      (r/fail! 400 "实际发生日期不能晚于今天"))
    date))

(defn execution!
  "真实执行反馈仅允许在执行或收尾阶段登记."
  [project]
  (when-not (contains? #{"execution" "closing"} (:status project))
    (r/fail! 409 "请先完成章程,计划基线和Gate审批并进入执行阶段")))


(defn submit!
  "冻结待审记录,绑定独立审核人和具体证据版本."
  [q project actor record body]
  (g/status! record #{"draft" "ready" "rejected" "in_progress"})
  (change! q project record "in_review"
           {:reviewer_id (g/reviewer! q project actor (:reviewer_id body))
            :submitted_by (:user_id actor)
            :evidence_ids (g/evidence! q project (:evidence_ids body) true)}))


(defn decide!
  "由指定独立审核人根据冻结记录作出批准或拒绝."
  [q project actor record body approved-status]
  (g/status! record #{"in_review"})
  (g/decision-actor! actor record)
  (let [decision (g/enum! (:decision body) #{"approved" "rejected"} "decision")]
    (change! q project record (if (= decision "approved") approved-status "rejected")
             {:decision_reason (g/text! body :reason) :decided_by (:user_id actor)})))


(defn configuration-of
  "把显式配置记录 (可为 nil) 合成执行链配置: 缺省为本地工程默认, 不声称是企业已确认规则."
  [explicit]
  (merge {:required_survey_visits 0 :pre_ship_conditions [] :handover_deadline_days 2 :site_lag_days 2 :handover_required false}
         (or explicit
             {:required_stages ["materials" "assembly" "quality" "shipment"]
              :required_test_types ["SIT" "FAT" "SAT"] :source "engineering_default"})))


(defn configuration
  "返回显式配置或本地工程默认的执行链,不声称是企业已确认规则."
  [q project]
  (configuration-of (first (records q project "configuration"))))


(defn- stages!
  "适用环节须包含物料到交付的必要前置,不允许孤立配置绕过执行."
  [stages]
  (let [selected (set stages)]
    (doseq [[stage predecessors] [["assembly" #{"materials"}]
                                  ["quality" #{"materials" "assembly"}]
                                  ["shipment" #{"materials" "assembly" "quality"}]]]
      (when (and (selected stage) (not-every? selected predecessors))
        (r/fail! 400 "适用环节必须包含物料,装配,质量的必要前置")))))

(defn configure!
  "在正式执行前配置适用环节和试验,执行后锁定流程配置."
  [svc actor id body]
  (mutate! svc actor id body "delivery.configured" false
    (fn [q project]
      (g/input! body [:required_stages :required_test_types :reason :required_survey_visits :pre_ship_conditions
                      :handover_deadline_days :site_lag_days :handover_required])
      (when-not (contains? #{"draft" "initiated" "planning"} (:status project))
        (r/fail! 409 "执行开始后不可修改适用交付流程"))
      (doseq [[key limit] [[:required_survey_visits 10] [:handover_deadline_days 30] [:site_lag_days 30]]]
        (when (contains? body key)
          (when-not (and (integer? (get body key)) (<= 0 (get body key) limit))
            (r/fail! 400 (str (name key) " 必须是0到" limit "的整数")))))
      (when (contains? body :pre_ship_conditions)
        (let [values (:pre_ship_conditions body)]
          (when-not (and (vector? values) (= (count values) (count (set values))))
            (r/fail! 400 "发货前条件必须为不重复数组"))
          (doseq [value values] (g/enum! value #{"warehouse_in" "payment"} "pre_ship_conditions"))))
      (doseq [[key allowed] [[:required_stages #{"materials" "assembly" "quality" "shipment"}]
                             [:required_test_types #{"SIT" "FAT" "SAT"}]]]
        (let [values (get body key)]
          (when-not (and (vector? values) (seq values) (= (count values) (count (set values))))
            (r/fail! 400 "适用流程与试验类型必须为非空不重复数组"))
          (doseq [value values] (g/enum! value allowed (name key)))))
      (stages! (:required_stages body))
      (when (contains? body :handover_required) (g/boolean! (:handover_required body) "handover_required"))
      (let [fields (assoc (select-keys body [:required_stages :required_test_types :required_survey_visits
                                             :pre_ship_conditions :handover_deadline_days :site_lag_days :handover_required])
                          :code "configuration" :source "project_configuration" :reason (g/text! body :reason))]
        (if-let [old (first (records q project "configuration"))]
          (change! q project old "registered" fields)
          (insert! q project actor "configuration" fields "registered"))))))


(defn task-referenced?
  "保护交付记录使用的任务不被删除."
  [q project task-id]
  (boolean (some #(= task-id (:task_id %))
                 (mapcat #(records q project %) ["material" "bom" "assembly" "test" "shipment" "service"]))))
