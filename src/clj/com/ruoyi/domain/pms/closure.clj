(ns com.ruoyi.domain.pms.closure
  "项目收尾清单, 遗留移交, 经验与绑定确定证据快照的独立关闭审批."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]
            [com.ruoyi.domain.pms.finance :as finance]
            [com.ruoyi.domain.pms.delivery :as delivery]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.governance :as governance]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules]))

(defn task-blockers
  "仅叶级任务全部完成后才能进入收尾, 汇总节点由排程计算."
  [q project]
  (let [tasks (q :planning/tasks {:project_id (:project_id project)})
        pending (remove #(or (= "summary" (:task_type %)) (= "done" (:status %))) tasks)]
    (cond-> []
      (empty? tasks) (conj "项目没有可验收的计划任务")
      (seq pending) (conj (str "仍有" (count pending) "个未完成的叶级任务")))))

(defn blockers
  "汇总任务, 质量, 财务和必需收尾项的真实缺口."
  [q project]
  (let [items (q :closure/items {:project_id (:project_id project)})
        required (filter #(or (= "handoff" (:kind %)) (= 1 (:required %))) items)]
    (into (vec (concat (task-blockers q project)
                       (:closure (governance/blockers q project))
                       (finance/closure-blockers q project)
                       (delivery/closure-blockers q project)))
          (concat (when-not (some #(= "check" (:kind %)) required) ["缺少必需收尾清单"])
                  (map #(str "尚未完成: " (:title %)) (remove #(= "completed" (:status %)) required))))))

(defn- snapshot
  "收集会影响关闭决定的对象版本, 不包含可变化的通用审计计数."
  [q project]
  (let [params {:project_id (:project_id project)}]
    {:project (select-keys project [:project_id :project_no :name :manager_id :contract_no :end_date])
     :delivery (into {} (for [kind ["configuration" "material" "bom" "assembly" "test" "shipment" "service"]]
                           [kind (vec (q :delivery/list (assoc params :kind kind)))]))
     :tasks (vec (q :planning/tasks params)) :plan (q :planning/plan params)
     :quality (into {} (for [kind ["charter" "gate-template" "gate" "issue" "document"]]
                        [kind (vec (q :gov/list (assoc params :kind kind)))]))
     :costs (vec (q :finance/versions params)) :times (vec (q :finance/times params))
     :items (vec (q :closure/items params)) :lessons (vec (q :closure/lessons params))}))

(defn- encoded-snapshot
  "以稳定键序编码关闭输入快照用于摘要比较."
  [q project]
  (json/generate-string
    (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %)
                   (snapshot q project))))

(defn ready!
  "关闭时再次核对实际条件和审批时的内容摘要, 防止批准后改写依据."
  [q project]
  (when-let [message (first (blockers q project))] (rules/fail! 409 message))
  (let [approval (q :closure/approval {:project_id (:project_id project)})]
    (when (< (or (:project_version approval) 0)
             (or (:reopened_version (q :lifecycle/state {:project_id (:project_id project)})) 0))
      (rules/fail! 409 "重开项目必须重新提交独立关闭审批"))
    (when-not (= "approved" (:status approval)) (rules/fail! 409 "尚未获得独立关闭批准"))
    (when-not (= (:snapshot_hash approval) (money/digest (encoded-snapshot q project)))
      (rules/fail! 409 "关闭依据在审批后发生变化,请重新提交关闭审批")))
  true)

(defn overview
  "返回收尾工作台和明确缺口, 不向普通成员暴露财务金额."
  [svc actor project-id]
  (kernel/read! svc actor project-id "pms:project:query"
    (fn [q project]
      (let [items (mapv #(assoc % :id (:item_id %) :required (= 1 (:required %)))
                        (q :closure/items {:project_id project-id}))
            missing (blockers q project)
            approval (q :closure/approval {:project_id project-id})]
        {:project_version (:version project) :checks (filterv #(= "check" (:kind %)) items)
         :handoffs (filterv #(= "handoff" (:kind %)) items)
         :lessons (mapv #(assoc % :id (:lesson_id %)) (q :closure/lessons {:project_id project-id}))
         :reopen_request (q :reopen/latest {:project_id project-id})
         :approval (some-> approval (dissoc :snapshot_json))
         :ready (empty? missing) :blockers missing}))))

(defn create-item!
  "建立受控收尾检查或遗留移交事项."
  [svc actor project-id kind body]
  (rules/object! body [:version :title :required :owner_id :due_date])
  (when (and (contains? body :required) (not (boolean? (:required body))))
    (rules/fail! 400 "必需标记必须是布尔值"))
  (when-not (contains? #{"check" "handoff"} kind) (rules/fail! 400 "无效收尾对象类型"))
  (kernel/mutate! svc actor project-id "pms:project:edit" body "closure.item.created"
    (fn [q project]
      (let [owner (when (= kind "handoff") (kernel/user! q project (:owner_id body) "移交责任人"))
            date (when (= kind "handoff") (rules/date! (:due_date body) "移交期限"))
            item {:item_id (kernel/id) :project_id project-id :kind kind
                  :title (rules/text! (:title body) "收尾事项" 200 true)
                  :required (if (false? (:required body)) 0 1) :owner_id owner :due_date date}]
        (when (and (= kind "handoff") (nil? date)) (rules/fail! 400 "移交期限不能为空"))
        (q :closure/insert-item! item)
        (assoc item :id (:item_id item) :status "open")))))

(defn complete-item!
  "以同项目不可变文档版本完成收尾或移交, 记录实际责任人."
  [svc actor project-id kind item-id body]
  (rules/object! body [:version :evidence_ref :comment])
  (kernel/mutate! svc actor project-id "pms:project:edit" body "closure.item.completed"
    (fn [q project]
      (let [item (q :closure/item {:project_id project-id :item_id item-id})
            evidence (governance/evidence-version! q project (:evidence_ref body))]
        (when-not (= kind (:kind item)) (rules/fail! 404 "收尾事项不存在"))
        (when-not (= "open" (:status item)) (rules/fail! 409 "收尾事项已完成"))
        (when (and (= kind "handoff") (not= (:owner_id item) (:user_id actor)))
          (rules/fail! 403 "仅指定接收人可以确认遗留事项移交"))
        (rules/changed! (q :closure/complete-item!
                           (assoc item :evidence_ref (:id evidence) :completed_by (:user_id actor)
                                  :comment (rules/text! (:comment body) "完成说明" 1000 true))))
        (assoc (q :closure/item item) :id item-id)))))

(defn create-lesson!
  "登记可复用的项目经验, 保留来源项目和作者."
  [svc actor project-id body]
  (rules/object! body [:version :title :category :content])
  (kernel/mutate! svc actor project-id "pms:project:edit" body "closure.lesson.created"
    (fn [q _]
      (let [lesson {:lesson_id (kernel/id) :project_id project-id :created_by (:user_id actor)
                    :title (rules/text! (:title body) "经验标题" 200 true)
                    :category (rules/text! (:category body) "经验分类" 40 true)
                    :content (rules/text! (:content body) "经验内容" 20000 true)}]
        (q :closure/insert-lesson! lesson)
        (assoc lesson :id (:lesson_id lesson))))))

(defn submit!
  "在收尾条件满足后提交确定版本的关闭申请."
  [svc actor project-id body]
  (rules/object! body [:version :reviewer_id])
  (kernel/mutate! svc actor project-id "pms:project:edit" body "closure.submitted"
    (fn [q project]
      (when-not (= "closing" (:status project)) (rules/fail! 409 "项目必须先进入收尾阶段"))
      (when-let [message (first (blockers q project))] (rules/fail! 409 message))
      (let [existing (q :closure/approval {:project_id project-id})
            reviewer (kernel/user! q project (:reviewer_id body) "关闭审批人")
            snapshot (encoded-snapshot q project)]
        (when (= "submitted" (:status existing)) (rules/fail! 409 "已有待处理的关闭申请"))
        (when (= reviewer (:user_id actor)) (rules/fail! 400 "不能审批自己提交的关闭申请"))
        (q :closure/insert-approval! {:approval_id (kernel/id) :project_id project-id
            :project_version (inc (:version project)) :submitted_by (:user_id actor) :reviewer_id reviewer
            :snapshot_hash (money/digest snapshot) :snapshot_json snapshot})
        (dissoc (q :closure/approval {:project_id project-id}) :snapshot_json)))))

(defn review!
  "指定独立审批人审核仍然有效的关闭依据, 不自动越过最终状态命令."
  [svc actor project-id body]
  (rules/object! body [:version :decision :reason])
  (when-not (contains? #{"approved" "rejected"} (:decision body)) (rules/fail! 400 "无效审批决定"))
  (kernel/mutate! svc actor project-id "pms:project:close" body "closure.reviewed" {:write? false}
    (fn [q project]
      (let [approval (q :closure/approval {:project_id project-id})
            reason (rules/text! (:reason body) "审批意见" 1000 (= "rejected" (:decision body)))]
        (when-not (= "submitted" (:status approval)) (rules/fail! 409 "没有待处理的关闭申请"))
        (kernel/independent-review! actor (:submitted_by approval) (:reviewer_id approval))
        (when (= "approved" (:decision body))
          (when-let [message (first (blockers q project))] (rules/fail! 409 message))
          (when-not (= (:snapshot_hash approval) (money/digest (encoded-snapshot q project)))
            (rules/fail! 409 "关闭依据已变化,请驳回并重新提交")))
        (rules/changed! (q :closure/review! (assoc approval :status (:decision body) :review_note reason)))
        (dissoc (q :closure/approval {:project_id project-id}) :snapshot_json)))))
