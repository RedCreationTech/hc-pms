(ns com.ruoyi.bpm.core
  "Flowable 工作流引擎的高层封装。

  这一层是 BPM 能力的核心：把所有面向用户的 BPM 操作收敛为简洁的 Clojure 函数，
  Controller 只调用这里的函数，不直接触碰 Flowable 的 Java API。

  设计约定：
    · 所有函数接收 ProcessEngine 作为首参（解耦、可测试）
    · 返回 Clojure 数据结构（map/seq），方便序列化给前端
    · 审批约定：approve!/reject! 通过写入 :approved 布尔变量驱动，
      流程模型需用排他网关(exclusiveGateway)按该变量分流
    · 引擎状态全在 Flowable(H2)，业务记录在 app 库，通过 business-key 关联
  "
  (:import
   (org.flowable.engine ProcessEngine)
   (org.flowable.engine.history HistoricActivityInstance)
   (org.flowable.engine.repository ProcessDefinition)
   (org.flowable.engine.runtime ProcessInstance)
   (org.flowable.task.api Task)
   (java.util HashMap Date)))

;; ── 工具 ───────────────────────────────────────────────────────────────

(defn- vars-map
  "把 Clojure map 转成 java.util.HashMap（key 一律转字符串，供 Flowable 变量）。"
  [m]
  (let [hm (java.util.HashMap.)]
    (doseq [[k v] m]
      (.put hm (name k) v))
    hm))

(defn- timestamp->str
  "Date 转 ISO 字符串。"
  ^String [^Date d]
  (when d (str (.toInstant d))))

;; ── 流程定义 (Deployment / ProcessDefinition) ─────────────────────────

(defn deploy!
  "部署一个 BPMN 流程定义。返回 deployment-id。
   参数: engine, bpmn-xml, key, name"
  [^ProcessEngine engine bpmn-xml key name]
  (let [repo (.getRepositoryService engine)
        dep (-> (.createDeployment repo)
                (.name (str name))
                (.addString (str key ".bpmn20.xml") bpmn-xml))]
    (.getId (.deploy dep))))

(defn delete-deployment!
  "删除流程部署（级联清理实例）。"
  [^ProcessEngine engine deployment-id]
  (.deleteDeployment (.getRepositoryService engine) deployment-id true))

(defn definitions
  "列出所有已部署的流程定义。"
  [^ProcessEngine engine]
  (let [repo (.getRepositoryService engine)]
    (mapv (fn [^ProcessDefinition pd]
            {:id (.getId pd)
             :key (.getKey pd)
             :name (.getName pd)
             :version (.getVersion pd)
             :deployment-id (.getDeploymentId pd)
             :suspended? (.isSuspended pd)})
          (.list (.createProcessDefinitionQuery repo)))))

(defn definition-by-key
  "按 key 查流程定义。"
  [^ProcessEngine engine key]
  (let [repo (.getRepositoryService engine)]
    (when-let [pd (.singleResult
                    (.processDefinitionKey (.createProcessDefinitionQuery repo) key))]
      {:id (.getId pd)
       :key (.getKey pd)
       :name (.getName pd)
       :version (.getVersion pd)})))

;; ── 流程实例 (ProcessInstance) ────────────────────────────────────────

(defn start!
  "发起流程实例。返回 process-instance-id。
   参数: engine, definition-key, business-key, [variables]"
  ([^ProcessEngine engine definition-key business-key]
   (start! engine definition-key business-key nil))
  ([^ProcessEngine engine definition-key business-key variables]
   (let [rt (.getRuntimeService engine)
         inst (if variables
                (.startProcessInstanceByKey rt definition-key business-key (vars-map variables))
                (.startProcessInstanceByKey rt definition-key business-key))]
     (when (nil? inst)
       (throw (ex-info "流程实例启动失败: 未找到流程定义" {:key definition-key})))
     {:process-instance-id (.getId ^ProcessInstance inst)
      :process-definition-id (.getProcessDefinitionId ^ProcessInstance inst)
      :business-key (.getBusinessKey ^ProcessInstance inst)})))

(defn running-instances
  "运行中的流程实例。"
  [^ProcessEngine engine]
  (let [rt (.getRuntimeService engine)]
    (mapv (fn [^ProcessInstance pi]
            {:process-instance-id (.getId pi)
             :process-definition-id (.getProcessDefinitionId pi)
             :business-key (.getBusinessKey pi)
             :start-activity-id (.getStartActivityId pi)})
          (.list (.createProcessInstanceQuery rt)))))

(defn instance-count
  "运行中实例数。"
  [^ProcessEngine engine]
  (.count (.createProcessInstanceQuery (.getRuntimeService engine))))

(defn suspend!
  "挂起流程实例。"
  [^ProcessEngine engine process-instance-id]
  (.suspendProcessInstanceById (.getRuntimeService engine process-instance-id))
  true)

(defn activate!
  "激活流程实例。"
  [^ProcessEngine engine process-instance-id]
  (.activateProcessInstanceById (.getRuntimeService engine process-instance-id))
  true)

;; ── 任务 (Task) ────────────────────────────────────────────────────────

(defn- task->map
  [^Task t]
  {:task-id (.getId t)
   :name (.getName t)
   :description (.getDescription t)
   :assignee (.getAssignee t)
   :owner (.getOwner t)
   :process-instance-id (.getProcessInstanceId t)
   :process-definition-id (.getProcessDefinitionId t)
   :create-time (timestamp->str (.getCreateTime t))
   :due-date (timestamp->str (.getDueDate t))})

(defn todo-list
  "某人待办：候选人或已认领的任务。"
  [^ProcessEngine engine user]
  (let [ts (.getTaskService engine)
        q (.taskCandidateOrAssigned (.createTaskQuery ts) user)]
    (mapv task->map (.list q))))

(defn todo-count
  "待办数。"
  [^ProcessEngine engine user]
  (.count (.taskCandidateOrAssigned (.createTaskQuery (.getTaskService engine)) user)))

(defn done-list
  "某人已办（历史已完成任务）。"
  [^ProcessEngine engine user]
  (let [hs (.getHistoryService engine)
        q (-> (.createHistoricTaskInstanceQuery hs)
              (.taskAssignee user)
              (.finished))]
    (mapv (fn [h]
            {:task-id (.getId h)
             :name (.getName h)
             :assignee (.getAssignee h)
             :process-instance-id (.getProcessInstanceId h)
             :start-time (timestamp->str (.getStartTime h))
             :end-time (timestamp->str (.getEndTime h))})
          (.list q))))

(defn claim!
  "认领任务。"
  [^ProcessEngine engine task-id user]
  (.claim (.getTaskService engine) task-id user)
  true)

(defn unclaim!
  "释放认领。"
  [^ProcessEngine engine task-id]
  (.unclaim (.getTaskService engine) task-id)
  true)

(defn transfer!
  "转办：直接改指派人。若指定 from-user，则校验任务当前归属。"
  [^ProcessEngine engine task-id from-user to-user]
  (when (and from-user to-user (not= from-user to-user))
    (when-let [task (.singleResult (.taskId (.createTaskQuery (.getTaskService engine)) task-id))]
      (let [cur (.getAssignee task)]
        (when (and cur (not= cur from-user))
          (throw (ex-info "转办失败: 任务不属于该用户" {:task-id task-id :from from-user :cur cur}))))))
  (.setAssignee (.getTaskService engine) task-id to-user)
  true)

(defn delegate!
  "委派（保留 owner，受派人完成后回到 owner）。"
  [^ProcessEngine engine task-id to-user]
  (.delegate (.getTaskService engine) task-id to-user)
  true)

(defn resolve!
  "被委派人完成后回到 owner 待办。"
  [^ProcessEngine engine task-id]
  (.resolveTask (.getTaskService engine) task-id)
  true)

(defn- complete*
  "完成任务并写入变量。若任务未认领且给定 user，则先认领给该用户（保证已办/历史可追踪）。"
  [^ProcessEngine engine task-id user variables]
  (let [ts (.getTaskService engine)
        q (.taskId (.createTaskQuery ts) task-id)
        t (.singleResult q)]
    (when t
      (let [cur (.getAssignee t)]
        (when (and cur (not= cur user))
          (throw (ex-info "无权办理该任务(非本人)" {:task-id task-id :assignee cur :user user})))
        (when (and user (nil? cur))
          (.claim ts task-id user))))
    (.complete ts task-id (vars-map (or variables {}))))
  true)

(defn approve!
  "审批通过：完成任务，写入 approved=true。"
  [^ProcessEngine engine task-id user comment]
  (complete* engine task-id user (cond-> {:approved true}
                                   comment (assoc :comment comment)))
  true)

(defn reject!
  "审批驳回：完成任务，写入 approved=false（模型需按该变量走排他网关）。"
  [^ProcessEngine engine task-id user comment]
  (complete* engine task-id user (cond-> {:approved false}
                                   comment (assoc :comment comment)))
  true)

(defn complete!
  "通用完成任务（自定义变量）。"
  [^ProcessEngine engine task-id user variables]
  (complete* engine task-id user variables)
  true)

;; ── 历史 (History) ─────────────────────────────────────────────────────

(defn history-of
  "某流程实例的活动轨迹（按时间排序）。"
  [^ProcessEngine engine process-instance-id]
  (let [hs (.getHistoryService engine)
        q (-> (.createHistoricActivityInstanceQuery hs)
              (.processInstanceId process-instance-id)
              (.orderByHistoricActivityInstanceStartTime)
              (.asc))]
    (mapv (fn [^HistoricActivityInstance h]
            {:activity-id (.getActivityId h)
             :activity-type (.getActivityType h)
             :activity-name (.getActivityName h)
             :assignee (.getAssignee h)
             :start-time (timestamp->str (.getStartTime h))
             :end-time (timestamp->str (.getEndTime h))
             :duration (when (and (.getStartTime h) (.getEndTime h))
                         (str (.getDurationInMillis h)))})
          (.list q))))

;; ── 流程图示 (diagram) ─────────────────────────────────────────────────

(defn active-activity-ids
  "流程实例当前正在执行的活动节点 id。"
  [^ProcessEngine engine process-instance-id]
  (vec (.getActiveActivityIds (.getRuntimeService engine) process-instance-id)))

(defn completed-activity-ids
  "流程实例已结束的活动节点 id（按历史去重）。"
  [^ProcessEngine engine process-instance-id]
  (->> (history-of engine process-instance-id)
       (filter :end-time)
       (map :activity-id)
       (remove nil?)
       distinct
       vec))
