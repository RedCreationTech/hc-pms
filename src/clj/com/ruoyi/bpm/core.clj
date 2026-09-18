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
  (:require [clojure.tools.logging :as log])
  (:import
   (org.flowable.task.api.history HistoricTaskInstance)
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

;; ── Phase 3 治理能力：流程定义版本 / 启停 / 清理 ───────────────────────

(defn latest-definition
  "某 key 最新版本的流程定义（含挂起状态）。"
  [^ProcessEngine engine key]
  (let [repo (.getRepositoryService engine)
        q (-> (.createProcessDefinitionQuery repo)
              (.processDefinitionKey key)
              (.latestVersion))]
    (when-let [pd (.singleResult q)]
      {:id (.getId pd)
       :key (.getKey pd)
       :name (.getName pd)
       :version (.getVersion pd)
       :suspended? (.isSuspended pd)})))

(defn definition-page
  "流程定义分页（全部版本，按版本号倒序）。返回 {:rows [...] :total n}。
   每行含 version / suspended? / deployment-id / deploy-time。"
  [^ProcessEngine engine key offset size]
  (let [repo (.getRepositoryService engine)
        q0 (.createProcessDefinitionQuery repo)
        q1 (if (seq key) (.processDefinitionKey q0 key) q0)
        q (.desc (.orderByProcessDefinitionVersion q1))
        total (.count q)
        deploy-time (fn [^String dep-id]
                      (some-> (.createDeploymentQuery repo)
                              (.deploymentId dep-id)
                              .singleResult
                              (.getDeploymentTime)
                              (timestamp->str)))]
    {:total total
     :rows (mapv (fn [^ProcessDefinition pd]
                   {:id (.getId pd)
                    :key (.getKey pd)
                    :name (.getName pd)
                    :version (.getVersion pd)
                    :deployment-id (.getDeploymentId pd)
                    :suspended? (.isSuspended pd)
                    :deploy-time (deploy-time (.getDeploymentId pd))})
                 (.listPage q (int offset) (int size)))}))

(defn definition-key-of
  "按定义 id 查流程定义 key。"
  [^ProcessEngine engine definition-id]
  (some-> (.getRepositoryService engine)
          (.createProcessDefinitionQuery)
          (.processDefinitionId definition-id)
          .singleResult
          (.getKey)))

(defn definition-xml
  "读取流程定义的 BPMN XML 文本。"
  [^ProcessEngine engine definition-id]
  (with-open [is (.getProcessModel (.getRepositoryService engine) definition-id)]
    (when is (slurp is))))

(defn suspend-definition-by-key!
  "挂起某 key 的全部流程定义（挂起后不可发起新实例）。"
  [^ProcessEngine engine key]
  (.suspendProcessDefinitionByKey (.getRepositoryService engine) key true nil)
  true)

(defn activate-definition-by-key!
  "激活某 key 的全部流程定义。"
  [^ProcessEngine engine key]
  (.activateProcessDefinitionByKey (.getRepositoryService engine) key true nil)
  true)

(defn delete-deployments-by-key!
  "删除某 key 的全部部署（级联删除运行中/历史实例与定义）。返回删除的部署数。"
  [^ProcessEngine engine key]
  (let [repo (.getRepositoryService engine)
        deps (.list (-> (.createDeploymentQuery repo)
                        (.processDefinitionKey key)))]
    (doseq [^org.flowable.engine.repository.Deployment d deps]
      (.deleteDeployment repo (.getId d) true))
    (count deps)))

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

(defn sync-identity!
  "把系统用户/角色/部门/岗位同步到 Flowable identity 表，
   使 candidateGroups(role:id / dept:id / post:id / dept-leader:id) 运行时能被正确匹配。
   data-map 结构: {:users [...] :roles [...] :depts [...] :posts [...]
                   :user-roles [{:user_id :role_id}] :user-posts [{:user_id :post_id}]}"
  [^ProcessEngine engine data]
  (let [id-svc (.getIdentityService engine)
        users (:users data)
        roles (:roles data)
        depts (:depts data)
        posts (:posts data)
        user-roles (:user-roles data)
        user-posts (:user-posts data)
        str-id (fn [x] (str (or x "")))
        uname (fn [u] (str (or (:user_name u) (:nick_name u) (:user_id u))))
        existing-group (fn [id] (some-> (.createGroupQuery id-svc) (.groupId id) .singleResult))
        existing-user (fn [id] (some-> (.createUserQuery id-svc) (.userId id) .singleResult))]
    ;; 1) groups
    (doseq [r roles]
      (let [gid (str "role:" (str-id (:role_id r)))]
        (when-not (existing-group gid)
          (let [g (.newGroup id-svc gid)]
            (.setName g (str (:role_name r)))
            (.saveGroup id-svc g)))))
    (doseq [d depts]
      (let [gid (str "dept:" (str-id (:dept_id d)))]
        (when-not (existing-group gid)
          (let [g (.newGroup id-svc gid)]
            (.setName g (str (:dept_name d)))
            (.saveGroup id-svc g))))
      (let [gid (str "dept-leader:" (str-id (:dept_id d)))]
        (when-not (existing-group gid)
          (let [g (.newGroup id-svc gid)]
            (.setName g (str (:dept_name d) "-负责人"))
            (.saveGroup id-svc g)))))
    (doseq [p posts]
      (let [gid (str "post:" (str-id (:post_id p)))]
        (when-not (existing-group gid)
          (let [g (.newGroup id-svc gid)]
            (.setName g (str (:post_name p)))
            (.saveGroup id-svc g)))))
    ;; 2) users
    (doseq [u users]
      (let [uid (uname u)]
        (when-not (existing-user uid)
          (let [user (.newUser id-svc uid)]
            (.setFirstName user (str (or (:nick_name u) (:user_name u) (:user_id u))))
            (.saveUser id-svc user)))))
    ;; 3) memberships（忽略重复，保证幂等）
    (doseq [ur user-roles]
      (when (and (:user_id ur) (:role_id ur))
        (try (.createMembership id-svc (uname (first (filter #(= (:user_id ur) (:user_id %)) users)))
                                (str "role:" (str-id (:role_id ur))))
             (catch Exception _ nil))))
    (doseq [u users]
      (when (:dept_id u)
        (try (.createMembership id-svc (uname u) (str "dept:" (str-id (:dept_id u))))
             (catch Exception _ nil))
        (when-let [d (first (filter #(= (:dept_id u) (:dept_id %)) depts))]
          (when (= (str-id (:user_id u)) (str-id (:leader d)))
            (try (.createMembership id-svc (uname u) (str "dept-leader:" (str-id (:dept_id u))))
                 (catch Exception _ nil))))))
    (doseq [up user-posts]
      (when (and (:user_id up) (:post_id up))
        (try (.createMembership id-svc (uname (first (filter #(= (:user_id up) (:user_id %)) users)))
                                (str "post:" (str-id (:post_id up))))
             (catch Exception _ nil))))
    true))
(declare node-config-of)

(defonce ^:private engine-ref (atom nil))
(defn register-engine! [engine] (reset! engine-ref engine))

(defonce ^:private node-create-handler (atom nil))

(defn set-node-create-handler!
  "注册节点 create 事件处理器。f 签名: (fn [task node-config] -> action)
   action 取值:
     [:candidates users]  添加候选人
     [:assign users]      指定办理人（清掉候选，第一人为 assignee）
     [:complete approved] 自动完成任务（approved 为 boolean 或 nil 表示不带变量）
   由 domain 层实现具体策略（候选解析 / 审批人为空策略 / 随机审批）。"
  [f]
  (reset! node-create-handler f))

;; ── 抄送（COPY_TASK 节点自动抄送处理器注册）────────────────────────────

(defonce ^:private copy-handler (atom nil))

(defn set-copy-handler!
  "注册抄送节点处理器。f 签名: (fn [^DelegateTask task node-config])，
   由 domain 层实现：插入 biz_bpm_copy 记录并自动完成任务。"
  [f]
  (reset! copy-handler f))

(defn make-task-listener
  "构建一个 Flowable TaskListener（create 事件）：
   1) 抄送节点(nodeType=COPY_TASK)：调用注册的抄送处理器（插 biz_bpm_copy + 自动完成任务）
   2) 其他人工节点：调用节点 create 处理器，按其返回 action 设置候选人/办理人或自动完成
      （覆盖新候选策略解析、审批人为空策略 AUTO_PASS/AUTO_REJECT/ASSIGN_USER/TO_ADMIN、
        随机审批 RANDOM 等）。"
  []
  (let [clear-candidates!
        (fn [^org.flowable.task.service.delegate.DelegateTask task]
          (doseq [^org.flowable.identitylink.api.IdentityLink l
                  (seq (.getCandidates task))]
            (if-let [u (.getUserId l)]
              (.deleteCandidateUser task u)
              (when-let [g (.getGroupId l)]
                (.deleteCandidateGroup task g)))))
        apply-action
        (fn [^org.flowable.task.service.delegate.DelegateTask task action]
          (when (and action (vector? action))
            (case (first action)
              :candidates (.addCandidateUsers task (java.util.ArrayList. ^java.util.List (vec (second action))))
              :assign (let [users (mapv str (second action))]
                        (clear-candidates! task)
                        (when (seq users)
                          (.setAssignee task (first users))
                          (when (seq (rest users))
                            (.addCandidateUsers task (java.util.ArrayList. ^java.util.List (vec (rest users)))))))
              :complete (when-let [^ProcessEngine engine @engine-ref]
                          (let [vars (java.util.HashMap.)]
                            (when (boolean? (second action))
                              (.put vars "approved" ^boolean (second action)))
                            (try
                              (.complete (.getTaskService engine) (.getId task) vars)
                              (catch Exception e
                                (log/error "[bpm-tasklistener] 自动完成节点失败:" (.getMessage e))))))
              (log/warn "[bpm-tasklistener] 未知 action:" (pr-str action)))))
        resolve (fn [^org.flowable.task.service.delegate.DelegateTask task]
                  (try
                    (let [node-config (when-let [^ProcessEngine engine @engine-ref]
                                        (node-config-of engine task))]
                      (when (and (= "COPY_TASK" (get-in node-config [:nodeType]))
                                 @copy-handler)
                        (@copy-handler task node-config))
                      (when (and (not= "COPY_TASK" (get-in node-config [:nodeType]))
                                 @node-create-handler)
                        ;; 无 nodeConfig 的节点传 {}（静态候选由引擎烘焙，
                        ;; 监听器只做 RANDOM/为空兜底/模型级自动去重）
                        (when-let [action (@node-create-handler task (or node-config {}))]
                          (apply-action task action))))
                    (catch Exception e
                      (log/error "[bpm-tasklistener] 解析任务监听器失败:" (.getMessage e)))))]
    (proxy [org.flowable.engine.delegate.TaskListener] []
      (notify [task] (resolve task)))))

(defn- task->map
  "把 Flowable Task 对象转成 Clojure map。"
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

(defn task->map*
  "把 Flowable Task 对象转成 Clojure map（公开，供外部构造任务 map）。"
  [^Task t]
  (task->map t))

(defn task-of
  "按任务 id 查单个任务（含实例 id/定义 id）。"
  [^ProcessEngine engine task-id]
  (some-> (.singleResult (.taskId (.createTaskQuery (.getTaskService engine)) task-id))
          task->map))

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

(defn- task-entity
  "按任务 id 查运行中任务实体，不存在则抛错。"
  [^ProcessEngine engine task-id]
  (let [t (some-> (.taskId (.createTaskQuery (.getTaskService engine)) task-id)
                  .singleResult)]
    (when-not t
      (throw (ex-info "任务不存在或已完成" {:task-id task-id})))
    t))

(defn- child-sign-tasks
  "某任务的未完成（运行中）加签子任务。Flowable8 运行期 TaskQuery 无 taskParentTaskId，
   按 processInstanceId 查询后用 getParentTaskId 过滤。"
  [^ProcessEngine engine task-id]
  (let [t (task-entity engine task-id)
        ts (.getTaskService engine)]
    (filter #(= task-id (.getParentTaskId ^Task %))
            (.list (.processInstanceId (.createTaskQuery ts) (.getProcessInstanceId t))))))

(defn- open-sign-count
  "某任务的未完成加签子任务数。"
  [^ProcessEngine engine task-id]
  (count (child-sign-tasks engine task-id)))

(defn- complete*
  "完成任务并写入变量。若任务未认领且给定 user，则先认领给该用户（保证已办/历史可追踪）。
   同时维护流程变量 bpmApprovedUsers/bpmLastApprover（模型级自动去重依据，同命令内可见）。"
  [^ProcessEngine engine task-id user variables]
  (let [ts (.getTaskService engine)
        q (.taskId (.createTaskQuery ts) task-id)
        t (.singleResult q)]
    (when t
      (let [cur (.getAssignee t)]
        (when (and cur (not= cur user))
          (throw (ex-info "无权办理该任务(非本人)" {:task-id task-id :assignee cur :user user})))
        (when (and user (nil? cur))
          (.claim ts task-id user))
        ;; 记录已审人（自动去重：APPROVE_ONCE / CONSECUTIVE）
        (when (and user (.getProcessInstanceId t))
          (let [rt (.getRuntimeService engine)
                pid (.getProcessInstanceId t)
                cur-users (vec (or (.getVariable rt pid "bpmApprovedUsers") []))]
            (.setVariable rt pid "bpmApprovedUsers" (conj cur-users (str user)))
            (.setVariable rt pid "bpmLastApprover" (str user))))))
    (when-let [c (:comment variables)]
      (.setVariableLocal ts task-id "comment" c))
    (when-let [a (contains? variables :approved)]
      (.setVariableLocal ts task-id "approved" (boolean (:approved variables))))
    (when-let [s (or (:sign-pic-url variables) (:signPicUrl variables))]
      (.setVariableLocal ts task-id "signPicUrl" (str s)))
    (.complete ts task-id (vars-map (dissoc variables :comment :sign-pic-url :signPicUrl))))
  true)

(defn approve!
  "审批通过：完成任务，写入 approved=true。父任务通过前校验无未完成加签子任务。
   可选 sign-pic-url 作为任务局部变量存储手写签名图 URL。"
  ([^ProcessEngine engine task-id user comment]
   (approve! engine task-id user comment nil))
  ([^ProcessEngine engine task-id user comment sign-pic-url]
   (when (pos? (open-sign-count engine task-id))
     (throw (ex-info "加签任务未完成" {:task-id task-id})))
   (complete* engine task-id user (cond-> {:approved true}
                                    comment (assoc :comment comment)
                                    sign-pic-url (assoc :sign-pic-url sign-pic-url)))
   true))

(defn complete!
  "通用完成任务（自定义变量）。"
  [^ProcessEngine engine task-id user variables]
  (complete* engine task-id user variables)
  true)

(defn node-config-of
  "从任务对应 BPMN 节点的 extensionElements 读取 nodeConfig JSON（config round-trip 数据）。"
  [^ProcessEngine engine task]
  (try
    (let [repo (.getRepositoryService engine)
          bpmn (.getBpmnModel repo (.getProcessDefinitionId task))
          el (.getFlowElement bpmn (.getTaskDefinitionKey task))
          ext (when el (.getExtensionElements el))
          props-list (when ext (or (.get ext "flowable:properties") (.get ext "properties")))
          props (when (and props-list (seq props-list)) (first props-list))
          children (when props (.getChildElements props))
          props-children (when children (or (.get children "flowable:property") (.get children "property")))]
      (some (fn [^org.flowable.bpmn.model.ExtensionElement p]
              (when (= "nodeConfig" (.getAttributeValue p nil "name"))
                (when-let [v (.getAttributeValue p nil "value")]
                  (try (cheshire.core/parse-string v true) (catch Exception _ nil)))))
            (or props-children [])))
    (catch Exception _ nil)))

;; ── 操作按钮配置（nodeConfig.buttons）────────────────────────────────────

(def default-buttons
  "审批操作按钮默认配置：全部启用 + 默认名称。"
  {"approve"    {"enable" true "displayName" "通过"}
   "reject"     {"enable" true "displayName" "驳回"}
   "transfer"   {"enable" true "displayName" "转办"}
   "delegate"   {"enable" true "displayName" "委派"}
   "add-sign"   {"enable" true "displayName" "加签"}
   "return"     {"enable" true "displayName" "退回"}})

(defn buttons-of
  "合并节点 buttons 配置与默认配置，返回 {btn-key {\"enable\" bool \"displayName\" str}}。
   nodeConfig JSON 解析后按钮 key/字段可能是 keyword 或字符串，两者都兼容；未配置时全部启用。"
  [node-config]
  (let [configured (or (:buttons node-config) {})
        get-btn (fn [k] (let [b (or (get configured k) (get configured (keyword k)))]
                          (if (map? b) b {})))]
    (into {}
          (map (fn [[k default-v]]
                 (let [b (get-btn k)]
                   [k {"enable" (let [v (or (find b :enable) (find b "enable"))]
                                 (if v (boolean (val v)) true))
                      "displayName" (or (:displayName b) (:display-name b)
                                        (get b "displayName") (get default-v "displayName"))}])))
          default-buttons)))

(defn- element-node-config
  "读取任意 FlowElement 的 nodeConfig 属性 JSON。"
  [^org.flowable.bpmn.model.FlowElement el]
  (try
    (let [ext (.getExtensionElements el)
          props-list (when ext (or (.get ext "flowable:properties") (.get ext "properties")))
          props (when (and props-list (seq props-list)) (first props-list))
          children (when props (.getChildElements props))
          props-children (when children (or (.get children "flowable:property") (.get children "property")))]
      (some (fn [^org.flowable.bpmn.model.ExtensionElement p]
              (when (= "nodeConfig" (.getAttributeValue p nil "name"))
                (when-let [v (.getAttributeValue p nil "value")]
                  (try (cheshire.core/parse-string v true) (catch Exception _ nil)))))
            (or props-children [])))
    (catch Exception _ nil)))

;; ── 超时处理（boundary timer → TimeoutHandler）───────────────────────────

(defn make-timeout-handler
  "构建超时执行监听器：挂在 userTask 的非中断边界定时事件上，触发时按 nodeConfig 里的
   timeout 配置执行：REMINDER 记录提醒日志 / AUTO_PASS 自动通过 / AUTO_REJECT 自动驳回
   （完成当前节点任务并写 approved 变量，由网关按正常出线流转）。"
  []
  (proxy [org.flowable.engine.delegate.ExecutionListener] []
    (notify [^org.flowable.engine.delegate.DelegateExecution execution]
      (try
        (when-let [^ProcessEngine engine @engine-ref]
          (let [repo (.getRepositoryService engine)
                bpmn (.getBpmnModel repo (.getProcessDefinitionId execution))
                el (some-> bpmn (.getFlowElement (.getCurrentActivityId execution)))
                ^org.flowable.bpmn.model.BoundaryEvent be
                (when (instance? org.flowable.bpmn.model.BoundaryEvent el) el)
                cfg (when be (element-node-config be))
                timeout (or (:timeout cfg) (:timeout-handler cfg) {})
                action (str (or (:action timeout) (:type timeout) "REMINDER"))
                pid (.getProcessInstanceId execution)
                attached-id (some-> be .getAttachedToRef .getId)
                ts (.getTaskService engine)
                tasks (if attached-id
                        (.list (-> (.createTaskQuery ts)
                                   (.processInstanceId pid)
                                   (.taskDefinitionKey attached-id)))
                        '())]
            (case action
              "REMINDER"
              (log/info "[bpm-timeout] 流程" pid "节点" attached-id "超时未处理，提醒相关办理人")
              ("AUTO_PASS" "AUTO_REJECT")
              (let [approved? (= action "AUTO_PASS")
                    vars (doto (java.util.HashMap.) (.put "approved" approved?))]
                (doseq [^Task t tasks]
                  (try
                    ;; 任务局部变量（时间轴/流转记录展示），与 complete* 行为一致
                    (.setVariableLocal ts (.getId t) "approved" approved?)
                    (.setVariableLocal ts (.getId t) "comment"
                                       (str "超时自动" (if approved? "通过" "驳回")))
                    ;; 流程变量驱动排他网关 approved 分流
                    (.complete ts (.getId t) vars)
                    (log/info "[bpm-timeout] 流程" pid "节点" attached-id
                              (if approved? "超时自动通过" "超时自动驳回"))
                    (catch Exception e
                      (log/error "[bpm-timeout] 自动处理失败:" (.getMessage e))))))
              (log/warn "[bpm-timeout] 未知超时动作:" action))))
        (catch Exception e
          (log/error "[bpm-timeout] 超时处理失败:" (.getMessage e)))))))

(defn- move-to-activity!
  "把流程实例从当前活动迁移到目标活动（驳回到指定节点）。"
  [^ProcessEngine engine process-instance-id from-activity-id to-activity-id]
  (-> (.createChangeActivityStateBuilder (.getRuntimeService engine))
      (.processInstanceId process-instance-id)
      (.moveActivityIdTo from-activity-id to-activity-id)
      .changeState)
  true)

(defn reject!
  "审批驳回：完成任务，写入 approved=false。
   若显式传入 return-node-id（前端从 return-list 选择）或任务节点配置了
   “驳回到指定节点”(reject-handler.type=RETURN_USER_TASK),
   则把流程实例迁移回目标节点重新审批；否则走网关条件分流(approved=false)。"
  ([^ProcessEngine engine task-id user comment]
   (reject! engine task-id user comment nil))
  ([^ProcessEngine engine task-id user comment return-node-id]
   (reject! engine task-id user comment return-node-id nil))
  ([^ProcessEngine engine task-id user comment return-node-id sign-pic-url]
   (let [ts (.getTaskService engine)
         t (some-> (.taskId (.createTaskQuery ts) task-id) .singleResult)
         node-config (when t (node-config-of engine t))
         reject-handler (or (:reject-handler node-config) (:rejectHandler node-config))
         return-node (or return-node-id
                         (:return-node-id reject-handler)
                         (:return-node reject-handler)
                         (:reject-return-node node-config)
                         (:rejectReturnNode node-config))]
     (if (and t return-node)
       ;; 驳回到指定节点：不 complete，直接迁移流程实例（changeState 自动处理当前任务；
       ;; 驳回到自身时 moveActivityIdTo 同节点 = 重新激活当前审批）
       (do
         (when comment
           (let [rt (.getRuntimeService engine)]
             (.setVariable rt (.getProcessInstanceId t) "comment" comment)))
         (move-to-activity! engine (.getProcessInstanceId t)
                            (.getTaskDefinitionKey t) return-node))
       ;; 终止流程(FINISH_PROCESS)或无条件：complete + approved=false 走网关
       (complete* engine task-id user (cond-> {:approved false}
                                        comment (assoc :comment comment)
                                        sign-pic-url (assoc :sign-pic-url sign-pic-url))))
     true)))

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

(defn task-history-of
  "某流程实例的任务级审批历史（含任务局部变量 comment/approved，按结束时间倒序）。"
  [^ProcessEngine engine process-instance-id]
  (let [hs (.getHistoryService engine)
        q (-> (.createHistoricTaskInstanceQuery hs)
              (.processInstanceId process-instance-id)
              (.includeTaskLocalVariables)
              (.orderByHistoricTaskInstanceEndTime)
              (.desc))]
    (mapv (fn [^HistoricTaskInstance t]
            (let [locals (try (.getTaskLocalVariables t) (catch Exception _ nil))]
              {:task-id (.getId t)
               :name (.getName t)
               :assignee (.getAssignee t)
               :start-time (timestamp->str (.getStartTime t))
               :end-time (timestamp->str (.getEndTime t))
               :comment (get locals "comment")
               :approved (get locals "approved")
               :sign-pic-url (get locals "signPicUrl")}))
          (.list q))))

;; ── 流程图示 (diagram) ─────────────────────────────────────────────────

(defn active-activity-ids
  "流程实例当前正在执行的活动节点 id。流程已结束时返回空。"
  [^ProcessEngine engine process-instance-id]
  (try
    (vec (.getActiveActivityIds (.getRuntimeService engine) process-instance-id))
    (catch Exception _ [])))

(defn completed-activity-ids
  "流程实例已结束的活动节点 id（按历史去重）。"
  [^ProcessEngine engine process-instance-id]
  (->> (history-of engine process-instance-id)
       (filter :end-time)
       (map :activity-id)
       (remove nil?)
       distinct
       vec))

(defn all-tasks
  "全部运行中任务（管理员视图）。"
  [^ProcessEngine engine]
  (mapv task->map (.list (.createTaskQuery (.getTaskService engine)))))

(defn terminate!
  "终止流程实例（运维操作）。"
  [^ProcessEngine engine process-instance-id reason]
  (.deleteProcessInstance (.getRuntimeService engine) process-instance-id (or reason "运维终止"))
  true)

;; ── 加签 / 减签 ────────────────────────────────────────────────────────





(defn create-sign!
  "加签：为当前任务创建子任务（parentTaskId=当前任务），每个加签人一条。
   type 为 before/after（仅前端展示语义，子任务都须先完成）；reason 存子任务局部变量。"
  [^ProcessEngine engine task-id user-names sign-type reason]
  (let [t (task-entity engine task-id)
        ts (.getTaskService engine)]
    (when (empty? (seq user-names))
      (throw (ex-info "加签人不能为空" {:task-id task-id})))
    (doseq [u user-names]
      (let [child (.newTask ts)]
        (.setName child (str (.getName t)))
        (.setParentTaskId child task-id)
        (.setAssignee child (str u))
        (.setProcessInstanceId child (.getProcessInstanceId t))
        (.setTaskDefinitionKey child (.getTaskDefinitionKey t))
        (.saveTask ts child)
        (when sign-type
          (.setVariableLocal ts (.getId child) "signType" (str sign-type)))
        (when reason
          (.setVariableLocal ts (.getId child) "signReason" (str reason)))))
    true))

(defn delete-sign!
  "减签：删除指定加签人的未完成子任务，reason 记录到父任务评论。"
  [^ProcessEngine engine task-id user-names reason]
  (let [t (task-entity engine task-id)
        ts (.getTaskService engine)
        user-set (set (map str user-names))
        children (filter #(contains? user-set (str (.getAssignee ^Task %)))
                         (child-sign-tasks engine task-id))]
    (when (empty? children)
      (throw (ex-info "没有可减签的加签任务" {:task-id task-id :users user-names})))
    (doseq [^Task c children]
      (.deleteTask ts (.getId c) true))
    (when reason
      (.addComment ts task-id (.getProcessInstanceId t) (str "减签: " reason)))
    true))

(defn sign-list
  "某任务的加签子任务列表（含 assignee/status/reason，按创建时间升序）。"
  [^ProcessEngine engine task-id]
  (let [hs (.getHistoryService engine)
        q (-> (.createHistoricTaskInstanceQuery hs)
              (.taskParentTaskId task-id)
              (.includeTaskLocalVariables)
              (.orderByHistoricTaskInstanceStartTime)
              (.asc))]
    (mapv (fn [^HistoricTaskInstance h]
            (let [locals (try (.getTaskLocalVariables h) (catch Exception _ nil))]
              {:task-id (.getId h)
               :name (.getName h)
               :assignee (.getAssignee h)
               :status (if (.getEndTime h) "FINISHED" "RUNNING")
               :sign-type (get locals "signType")
               :reason (get locals "signReason")
               :create-time (timestamp->str (.getStartTime h))
               :end-time (timestamp->str (.getEndTime h))}))
          (.list q))))

;; ── 取消 / 撤回 ────────────────────────────────────────────────────────

(defn cancel-instance!
  "取消流程实例（发起人/管理员）。"
  [^ProcessEngine engine process-instance-id reason]
  (.deleteProcessInstance (.getRuntimeService engine)
                          process-instance-id (or reason "取消申请"))
  true)

(defn- active-activity-ids-safe
  [^ProcessEngine engine process-instance-id]
  (try
    (vec (.getActiveActivityIds (.getRuntimeService engine) process-instance-id))
    (catch Exception _ [])))

(defn withdraw!
  "撤回：审批人把自己刚审完的任务撤回（要求下一节点任务未完成）。
   把流程实例从下一活动迁移回本任务节点，并把新任务指派人还原为原审批人。"
  [^ProcessEngine engine task-id user]
  (let [hs (.getHistoryService engine)
        ht (some-> (.createHistoricTaskInstanceQuery hs)
                   (.taskId task-id) .singleResult)]
    (when-not ht
      (throw (ex-info "任务不存在" {:task-id task-id})))
    (when-not (= (str user) (str (.getAssignee ht)))
      (throw (ex-info "只能撤回本人审批的任务" {:task-id task-id :user user})))
    (let [pid (.getProcessInstanceId ht)
          act (.getTaskDefinitionKey ht)
          active (active-activity-ids-safe engine pid)]
      (when (empty? active)
        (throw (ex-info "流程已结束，无法撤回" {:process-instance-id pid})))
      (let [next-acts (remove #(= act %) active)]
        (when (empty? next-acts)
          (throw (ex-info "没有可撤回的后续节点" {:process-instance-id pid})))
        (move-to-activity! engine pid (first next-acts) act)
        ;; 迁移后重新生成的本节点任务指派人还原为原审批人
        (let [ts (.getTaskService engine)
              new-tasks (filter #(and (= pid (.getProcessInstanceId ^Task %))
                                      (= act (.getTaskDefinitionKey ^Task %)))
                                (.list (.createTaskQuery ts)))]
          (doseq [^Task nt new-tasks]
            (.setAssignee ts (.getId nt) (str user))))
        true))))

(defn withdraw-to-start!
  "发起人撤回到起始节点：把所有活动迁移回 startEvent，流程重新走线。"
  [^ProcessEngine engine process-instance-id]
  (let [hs (.getHistoryService engine)
        start-act (some-> (.createHistoricActivityInstanceQuery hs)
                          (.processInstanceId process-instance-id)
                          (.activityType "startEvent")
                          (.orderByHistoricActivityInstanceStartTime)
                          (.asc)
                          .list first (.getActivityId))
        active (active-activity-ids-safe engine process-instance-id)]
    (when (empty? active)
      (throw (ex-info "流程已结束，无法撤回" {:process-instance-id process-instance-id})))
    (when-not start-act
      (throw (ex-info "找不到流程起始节点" {:process-instance-id process-instance-id})))
    (let [rt (.getRuntimeService engine)
          builder (.createChangeActivityStateBuilder rt)]
      (.processInstanceId builder process-instance-id)
      (doseq [act active]
        (.moveActivityIdTo builder act start-act))
      (.changeState builder))
    true))

;; ── 可退回节点列表 ─────────────────────────────────────────────────────

(defn return-list
  "当前任务之前（按 BPMN 文档顺序）已至少完成过一次的同名用户任务节点列表，
   排除当前及之后节点、排除网关/开始。基于流程定义顺序而非时间戳，
   避免撤回/驳回造成的历史活动实例干扰。"
  [^ProcessEngine engine task-id]
  (let [t (task-entity engine task-id)
        pid (.getProcessInstanceId t)
        cur-key (.getTaskDefinitionKey t)
        repo (.getRepositoryService engine)
        bpmn (.getBpmnModel repo (.getProcessDefinitionId t))
        proc (.getMainProcess bpmn)
        order (mapv (fn [^org.flowable.bpmn.model.UserTask ut]
                      [(.getId ut) (or (.getName ut) (.getId ut))])
                    (filter #(instance? org.flowable.bpmn.model.UserTask %)
                            (.getFlowElements ^org.flowable.bpmn.model.Process proc)))
        idx (or (first (keep-indexed (fn [i [id _]] (when (= id cur-key) i)) order))
                0)
        hs (.getHistoryService engine)
        completed (set (map (fn [^HistoricActivityInstance h] (.getActivityId h))
                            (filter #(.getEndTime ^HistoricActivityInstance %)
                                    (.list (.processInstanceId
                                            (.createHistoricActivityInstanceQuery hs)
                                            pid)))))]
    (->> (take idx order)
         (filter (fn [[id _]] (contains? completed id)))
         (mapv (fn [[id name]] {:activity-id id :activity-name name})))))
