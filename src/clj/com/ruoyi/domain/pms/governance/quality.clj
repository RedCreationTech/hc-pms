(ns com.ruoyi.domain.pms.governance.quality
  "DQ 编制与确认关键任务 (B08) 与项目/单机局部暂停恢复 (B16).
   DQ: 检查清单 + 确定版本交付件, 逐项检查后提交独立签认; 交付件出现新版本时只读标注失效.
   局部暂停: 记录节点范围与原因, 暂停期间该节点及后代任务禁止进度反馈, 恢复时记录影响说明."
  (:require [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

;; ── DQ ──────────────────────────────────────────────────────────

(defn- checklist!
  [items]
  (when-not (and (vector? items) (<= 1 (count items) 50)) (r/fail! 400 "DQ检查清单需要1到50项"))
  (let [rows (mapv (fn [item] (r/object! item [:code :title :required])
                     {:code (s/text! item :code 50) :title (s/text! item :title 200)
                      :required (if (false? (:required item)) false true) :passed false :note ""}) items)]
    (when-not (= (count rows) (count (set (map :code rows)))) (r/fail! 400 "DQ检查项编码重复"))
    rows))

(defn create-dq!
  "建立 DQ 关键任务: 标题, 责任人, 检查清单与确定版本交付件."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.created"
    (fn [q project]
      (s/input! body [:code :title :owner_id :checklist :deliverable_ids :task_id])
      (when (seq (:task_id body))
        (when-not (q :planning/task {:project_id (:project_id project) :task_id (:task_id body)})
          (r/fail! 404 "关联任务不存在或不属于本项目")))
      (when (some #(= (:code body) (:code %)) (s/records q project "dq")) (r/fail! 409 "DQ编号已存在"))
      (s/insert! q project actor "dq"
                 (cond-> {:code (s/text! body :code 100) :title (s/text! body :title 200)
                          :owner_id (k/user! q project (:owner_id body) "DQ责任人")
                          :checklist (checklist! (:checklist body))
                          :deliverable_ids (s/evidence! q project (or (:deliverable_ids body) []) false)}
                   (seq (:task_id body)) (assoc :task_id (:task_id body)))
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
                                (let [res (get by-code (:code item))]
                                  (r/object! res [:code :passed :note])
                                  (assoc item :passed (s/boolean! (:passed res) "passed")
                                         :note (r/text! (:note res) "检查说明" 500 false))))
                              (:checklist dq))
              deliverables (if (contains? body :deliverable_ids)
                             (s/evidence! q project (:deliverable_ids body) false)
                             (:deliverable_ids dq))]
          (s/change! q project dq (if (every? :passed (filter :required checklist)) "ready" "draft")
                     {:checklist checklist :deliverable_ids deliverables :checked_by (:user_id actor)}))))))

(defn submit-dq!
  "全部必需项通过且交付件齐备后提交独立签认."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "dq.submitted"
    (fn [q project]
      (s/input! body [:reviewer_id])
      (let [dq (s/record! q project "dq" rid)]
        (s/status! dq #{"ready" "rejected"})
        (when-not (every? :passed (filter :required (:checklist dq))) (r/fail! 409 "仍有必需检查项未通过"))
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

(defn dq-read-model
  "只读标注: 交付件是否出现更新版本 (签认依据失效), 检查项通过数."
  [documents dq]
  (let [latest-by-code (into {} (map (fn [[code rows]] [code (apply max :revision (map :revision rows))]) (group-by :code documents)))
        by-id (into {} (map (juxt :id identity) documents))
        stale (filterv (fn [id] (let [doc (get by-id id)]
                                  (and doc (> (get latest-by-code (:code doc) 0) (:revision doc)))))
                       (:deliverable_ids dq))]
    (assoc dq :dq_stale (boolean (seq stale)) :dq_stale_count (count stale)
           :dq_passed (count (filter :passed (:checklist dq))) :dq_total (count (:checklist dq)))))

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
