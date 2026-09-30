(ns com.ruoyi.domain.pms.planning.baseline
  "计划提交,独立审批,不可变基线和基线差异."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [com.ruoyi.domain.pms.approval-chain :as chain]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.governance.approval :as approval]
            [com.ruoyi.domain.pms.rules :as rules]
            [com.ruoyi.domain.pms.planning.store :as store]
            [com.ruoyi.domain.pms.planning.capacity :as capacity])
  (:import [java.security MessageDigest]
           [java.math BigInteger]
           [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(defn- canonical
  "稳定排序快照键并统一数字表示,消除数据库数值类型差异."
  [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (str %1) (str %2)))
                       (map (fn [[k v]] [k (canonical v)]) value))
    (sequential? value) (mapv canonical value)
    (number? value) (.stripTrailingZeros (bigdec value))
    :else value))

(defn- canonical-json
  "生成可重复比较和散列的快照序列."
  [value]
  (json/generate-string (canonical value)))

(defn digest
  "计算计划设计和排程快照的SHA-256摘要."
  [snapshot]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                         (.getBytes (canonical-json snapshot) "UTF-8")))))

(defn record!
  "读取同项目的基线及其冻结快照."
  [q project baseline-id]
  (if-let [record (q :planning/baseline {:project_id (:project_id project) :baseline_id baseline-id})]
    (-> record (assoc :snapshot (json/parse-string (:snapshot_json record) true)) (dissoc :snapshot_json))
    (rules/fail! 404 "计划基线不存在或不属于当前项目")))

(defn- ready-to-submit!
  "计划审批前必须具备有效任务负责人,可排程设计和可用资源容量."
  [q project snapshot]
  (when-not (contains? #{"planning" "execution"} (:status project))
    (rules/fail! 409 "项目须进入计划或执行阶段后才能提交计划审批"))
  (let [tasks (:tasks snapshot) leaves (remove #(= "summary" (:task_type %)) tasks)]
    (when (empty? leaves) (rules/fail! 409 "请先建立可执行任务或里程碑"))
    (doseq [task leaves]
      (when-not (:owner_id task) (rules/fail! 409 (str "任务缺少负责人: " (:name task))))
      (kernel/user! q project (:owner_id task) "任务负责人"))
    (doseq [task (filter #(and (= "summary" (:task_type %)) (not= "template" (:source_type %))) tasks)]
      (when-not (some #(= (:task_id task) (:parent_id %)) tasks)
        (rules/fail! 409 "空汇总任务不能提交审批"))))
  (when (seq (capacity/overloads q project snapshot))
    (rules/fail! 409 "计划存在资源超配,请先调整分配或日容量")))

(defn submit!
  "冻结提交时快照,等待非提交者独立审批,审批期间禁止结构变更."
  [svc actor id body]
  (rules/object! body [:version :comment :change_id])
  (kernel/mutate! svc actor id "pms:project:edit" body "plan.submitted"
    (fn [q project]
      (store/editable! q project)
      (let [plan (store/plan q project) snapshot (store/snapshot q project)
            revisions (store/rows q project :planning/baselines)
            record {:baseline_id (kernel/id) :project_id id :plan_revision (:revision plan)
                    :snapshot_json (canonical-json snapshot) :snapshot_hash (digest snapshot)
                    :submitted_by (:user_id actor) :change_id (:change_id body)
                    :submit_comment (rules/text! (:comment body) "提交说明" 2000 false)}]
        (ready-to-submit! q project snapshot)
        (when (or (= "execution" (:status project)) (:change_id body))
          (when-not (:change_id body) (rules/fail! 409 "执行期重建计划基线必须关联已批准变更"))
          (approval/approved-change! q project (:change_id body)))
        (when (some #(= (:revision plan) (:plan_revision %)) revisions)
          (rules/fail! 409 "此计划修订已提交,请修改设计后再发起新的审批"))
        (q :planning/create-baseline! record)
        ;; 已发布 "计划基线" 审批策略时按策略逐级审批, 否则由有审批权限的非提交成员审批
        (chain/start! q actor project "plan-baseline" (:baseline_id record)
                      {:title (str "计划基线 " (:project_no project) " 修订 " (:revision plan))})
        (dissoc (record! q project (:baseline_id record)) :snapshot)))))

(defn- approval-ready!
  "批准时重验变更,冻结内容和共享资源;拒绝不被失效依据阻塞."
  [q project record]
  (when (:change_id record) (approval/approved-change! q project (:change_id record)))
  (let [snapshot (store/snapshot q project)]
    (when-not (and (= (:plan_revision record) (:revision (store/plan q project)))
                   (= (:snapshot_hash record) (digest snapshot)))
      (rules/fail! 409 "当前设计已偏离提交快照,不能批准此申请"))
    (when (seq (capacity/overloads q project snapshot))
      (rules/fail! 409 "当前共享资源存在超配,不能批准计划"))))

(defn review!
  "有审批权限的项目可读成员独立批准或拒绝,快照内容始终不变."
  [svc actor id baseline-id body]
  (rules/object! body [:version :decision :comment])
  (kernel/mutate! svc actor id "pms:plan:approve" body "plan.reviewed" {:write? false}
    (fn [q project]
      (let [record (record! q project baseline-id)
            decision (:decision body) comment (rules/text! (:comment body) "审批意见" 2000 false)]
        (when-not (= "submitted" (:status record)) (rules/fail! 409 "该计划申请已完成审批"))
        (chain/guard-legacy-decision! q "plan-baseline" baseline-id)
        (when (= (:user_id actor) (:submitted_by record)) (rules/fail! 403 "提交者不能审批自己的计划"))
        (when-not (contains? #{"approved" "rejected"} decision) (rules/fail! 400 "审批决定必须为 approved 或 rejected"))
        (when (and (= decision "rejected") (str/blank? comment)) (rules/fail! 400 "拒绝计划必须填写原因"))
        (when (= decision "approved") (approval-ready! q project record))
        (rules/changed! (q :planning/review-baseline!
                           {:project_id id :baseline_id baseline-id :status decision
                            :reviewed_by (:user_id actor) :review_comment comment}))
        (dissoc (record! q project baseline-id) :snapshot)))))

(defn- finalize!
  "审批链落定计划基线: 通过前重验变更, 冻结内容与共享资源; 审批人记为末级决定人."
  [q actor project baseline-id decision comment]
  (let [record (record! q project baseline-id)]
    (when-not (= "submitted" (:status record)) (rules/fail! 409 "该计划申请已完成审批"))
    (when (= decision "approved") (approval-ready! q project record))
    (rules/changed! (q :planning/review-baseline!
                       {:project_id (:project_id project) :baseline_id baseline-id :status decision
                        :reviewed_by (:user_id actor) :review_comment (or comment "")}))))


(chain/register-adapter! "plan-baseline" {:finalize finalize!})


(defn execution-ready!
  "要求最新设计修订存在已批准且内容一致的冻结基线."
  [q project]
  (let [revision (:revision (store/plan q project))
        baseline (first (filter #(and (= revision (:plan_revision %)) (= "approved" (:status %)))
                                (store/rows q project :planning/baselines)))]
    (when-not baseline (rules/fail! 409 "最新计划尚无批准基线,请完成计划独立审批"))
    (when-not (= (:snapshot_hash baseline) (digest (store/snapshot q project)))
      (rules/fail! 409 "当前计划与已批准基线不一致,请重新提交审批"))
    (when (seq (capacity/overloads q project (store/snapshot q project)))
      (rules/fail! 409 "共享资源存在超配,请调整计划后再进入执行"))
    (select-keys baseline [:baseline_id :plan_revision :status])))

(defn- collection-diff
  "按稳定业务标识比较新增,删除和修改的计划实体."
  [kind key before after]
  (let [left (into {} (map (juxt key identity) before))
        right (into {} (map (juxt key identity) after))]
    (->> (set/union (set (keys left)) (set (keys right))) sort
         (keep (fn [id]
                 (when-not (= (canonical-json (left id)) (canonical-json (right id)))
                   {:entity_type kind :entity_id id
                    :change_type (cond (nil? (left id)) "added" (nil? (right id)) "removed" :else "modified")
                    :before (left id) :after (right id)}))) vec)))

(defn differences
  "比较冻结基线和当前草稿,保留可检查的前后值."
  [q project baseline-id]
  (let [record (record! q project baseline-id) before (:snapshot record) after (store/snapshot q project)
        kinds [["task" :tasks :task_id] ["dependency" :dependencies :dependency_id]
               ["resource" :resources :resource_id] ["allocation" :allocations :allocation_id]
               ["capacity" :capacities #(str (:resource_id %) ":" (:date %))]]
        changes (vec (concat
                      (mapcat (fn [[kind field key]] (collection-diff kind key (get before field) (get after field))) kinds)
                      (keep (fn [field]
                              (when-not (= (canonical-json (get before field)) (canonical-json (get after field)))
                                {:entity_type (name field) :entity_id (name field) :change_type "modified"
                                 :before (get before field) :after (get after field)})) [:calendar :schedule])))]
    {:baseline_id baseline-id :baseline_revision (:plan_revision record)
     :current_revision (:revision (store/plan q project)) :changed (boolean (seq changes)) :changes changes}))

(defn- latest-approved
  "从基线列表 (按 plan_revision 降序) 取最新一条已批准基线, 无则返回 nil."
  [baselines]
  (first (filter #(= "approved" (:status %)) baselines)))

(defn- date-slip
  "当前日期相对基线日期的日历天差 (当前在基线之后为正, 表示延后)."
  [base-date current-date]
  (int (.between ChronoUnit/DAYS (LocalDate/parse (str base-date)) (LocalDate/parse (str current-date)))))

(defn- schedule-by-task
  "把一份排程结果按 task_id 索引, 仅保留基线偏差比较所需的日期."
  [schedule]
  (into {} (map (juxt :task_id #(select-keys % [:start_date :end_date]))) (:tasks schedule)))

(defn- task-variance
  "单个当前排程任务相对基线排程的只读偏差: added 表示基线中不存在该任务, 否则按完成日期
   日历天差给出 behind (延后)/ahead (提前)/on_baseline (持平); 只读派生, 键名不带尾随问号."
  [base-by-task row]
  (let [id (:task_id row) base (get base-by-task id)]
    (if-not base
      {:task_id id :baseline_state "added" :baseline_start_variance nil :baseline_finish_variance nil}
      (let [sv (date-slip (:start_date base) (:start_date row))
            fv (date-slip (:end_date base) (:end_date row))
            state (cond (pos? fv) "behind" (neg? fv) "ahead" :else "on_baseline")]
        {:task_id id :baseline_state state :baseline_start_variance sv :baseline_finish_variance fv}))))

(defn variance
  "只读派生任务级基线进度偏差: 取最新一条已批准基线冻结排程与当前设计排程逐任务比较, 输出
   按 task_id 索引的 :by-task 偏差 map 与汇总 :summary (available/baseline_id/baseline_revision/
   total/on-baseline/behind/ahead/added/worst-finish-slip). 无已批准基线时返回 {:available false}.
   只读派生, 不落库不门控不投递, 键名不带尾随问号."
  [q project baselines current-snapshot]
  (if-let [approved (latest-approved baselines)]
    (let [base-sched (:schedule (:snapshot (record! q project (:baseline_id approved))))
          base-by-task (schedule-by-task base-sched)
          rows (mapv #(task-variance base-by-task %) (:tasks (:schedule current-snapshot)))
          by-task (into {} (map (juxt :task_id #(dissoc % :task_id)) rows))
          states (frequencies (map :baseline_state rows))
          slips (remove nil? (map :baseline_finish_variance rows))]
      {:available true
       :by-task by-task
       :summary {:available true
                 :baseline_id (:baseline_id approved)
                 :baseline_revision (:plan_revision approved)
                 :total (count rows)
                 :on-baseline (get states "on_baseline" 0)
                 :behind (get states "behind" 0)
                 :ahead (get states "ahead" 0)
                 :added (get states "added" 0)
                 :worst-finish-slip (apply max 0 slips)}})
    {:available false :by-task {} :summary {:available false}}))
