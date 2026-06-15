(ns com.ruoyi.workflow.service
  "流程引擎业务层 — 流程定义/实例/任务 CRUD。"
  (:require [com.ruoyi.workflow.engine :as engine]
            [clojure.tools.logging :as log])
  (:import [org.flowable.engine.repository Deployment]
           [java.io ByteArrayInputStream]))

;; ─── 流程定义 (Process Definition) ──────────────────────────────

(defn deploy!
  "部署 BPMN XML。返回 Deployment 对象。
   :name    部署名称
   :xml     BPMN 2.0 XML 字符串"
  [{:keys [name xml]}]
  (let [repo (engine/get-repository-service)
        bytes (.getBytes (str xml) "UTF-8")]
    (-> (.createDeployment repo)
        (.addBytes (str name ".bpmn20.xml") bytes)
        (.name (or name "unnamed"))
        (.deploy))))

(defn list-definitions
  "查询所有流程定义。返回 [{:id :key :name :version :deployment-id :suspended?}]"
  []
  (let [repo (engine/get-repository-service)
        defs (-> (.createProcessDefinitionQuery repo)
                 (.orderByProcessDefinitionName)
                 (.asc)
                 (.list))]
    (mapv (fn [d]
            {:id (.getId d)
             :key (.getKey d)
             :name (.getName d)
             :version (.getVersion d)
             :deployment-id (.getDeploymentId d)
             :suspended? (.isSuspended d)
             :description (.getDescription d)})
          defs)))

(defn get-definition-bpmn
  "获取流程定义的 BPMN XML。"
  [deployment-id]
  (let [repo (engine/get-repository-service)
        stream (.getResourceAsStream repo deployment-id "")]
    (when stream
      (slurp stream))))

(defn delete-deployment!
  "删除部署（级联删除流程定义和实例）。"
  [deployment-id]
  (let [repo (engine/get-repository-service)]
    (.deleteDeployment repo deployment-id true)))

(defn suspend-definition!
  "挂起流程定义。"
  [definition-id]
  (let [repo (engine/get-repository-service)]
    (.suspendProcessDefinitionById repo definition-id true nil)))

(defn activate-definition!
  "激活流程定义。"
  [definition-id]
  (let [repo (engine/get-repository-service)]
    (.activateProcessDefinitionById repo definition-id true nil)))

;; ─── 流程实例 (Process Instance) ──────────────────────────────

(defn start-instance!
  "启动流程实例。
   :process-key 流程定义 key
   :business-key 业务关联 key
   :variables 流程变量 {:applicant \"zhangsan\"}"
  [{:keys [process-key business-key variables]}]
  (let [runtime (engine/get-runtime-service)
        vars (when variables (java.util.HashMap. ^java.util.Map variables))]
    (-> (.startProcessInstanceByKey runtime
                                    ^String process-key
                                    ^String business-key
                                    ^java.util.Map vars))))

(defn list-instances
  "查询运行中的流程实例。"
  ([]
   (list-instances {}))
  ([{:keys [process-key page size]
     :or {page 1 size 20}}]
   (let [runtime (engine/get-runtime-service)
         query (.createProcessInstanceQuery runtime)]
     (when process-key (.processDefinitionKey query process-key))
     (let [total (.count query)
           items (-> query
                     (.orderByStartTime)
                     (.desc)
                     (.listPage (* (dec page) size) (* page size)))]
       {:total total
        :items (mapv (fn [i]
                       {:id (.getId i)
                        :process-definition-id (.getProcessDefinitionId i)
                        :process-definition-key (.getProcessDefinitionKey i)
                        :business-key (.getBusinessKey i)
                        :start-time (.getStartTime i)
                        :suspended? (.isSuspended i)})
                     items)}))))

(defn delete-instance!
  "删除流程实例。"
  [instance-id & [reason]]
  (let [runtime (engine/get-runtime-service)]
    (.deleteProcessInstance runtime instance-id (or reason ""))))

;; ─── 任务 (Task) ──────────────────────────────────────────────

(defn list-tasks
  "查询待办任务。
   :assignee 指定处理人
   :candidate-user 候选用户
   :page :size 分页"
  [{:keys [assignee candidate-user page size]
    :or {page 1 size 20}}]
  (let [task-svc (engine/get-task-service)
        query (.createTaskQuery task-svc)]
    (when assignee (.taskAssignee query assignee))
    (when candidate-user (.taskCandidateUser query candidate-user))
    (let [total (.count query)
          items (-> query
                    (.orderByTaskCreateTime)
                    (.desc)
                    (.listPage (* (dec page) size) (* page size)))]
      {:total total
       :items (mapv (fn [t]
                      {:id (.getId t)
                       :name (.getName t)
                       :assignee (.getAssignee t)
                       :create-time (.getCreateTime t)
                       :process-instance-id (.getProcessInstanceId t)
                       :process-definition-id (.getProcessDefinitionId t)
                       :task-definition-key (.getTaskDefinitionKey t)
                       :description (.getDescription t)
                       :form-key (.getFormKey t)})
                    items)})))

(defn complete-task!
  "完成任务。
   :task-id 任务 ID
   :variables 流程变量"
  [{:keys [task-id variables]}]
  (let [task-svc (engine/get-task-service)
        vars (when variables (java.util.HashMap. ^java.util.Map variables))]
    (.complete task-svc task-id vars)))

(defn claim-task!
  "签收任务（候选人领取）。"
  [task-id user-id]
  (let [task-svc (engine/get-task-service)]
    (.claim task-svc task-id user-id)))

(defn delegate-task!
  "委托任务。"
  [task-id user-id]
  (let [task-svc (engine/get-task-service)]
    (.delegateTask task-svc task-id user-id)))
