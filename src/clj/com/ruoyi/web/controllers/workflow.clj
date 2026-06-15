(ns com.ruoyi.web.controllers.workflow
  "流程引擎 API 控制器。"
  (:require [com.ruoyi.workflow.service :as wf]
            [ring.util.response :as response]
            [clojure.tools.logging :as log]))

(defn ok [data]
  (response/response {:code 200 :msg "操作成功" :data data}))

(defn fail [msg & [code]]
  (response/response {:code (or code 500) :msg msg}))

;; ─── 流程定义 ──────────────────────────────────────────────

(defn list-definitions [_req]
  (try
    (ok (wf/list-definitions))
    (catch Exception e
      (log/error e "查询流程定义失败")
      (fail (.getMessage e)))))

(defn deploy [req]
  (try
    (let [body (slurp (:body req))
          name (get-in req [:params :name] "unnamed")
          dep (wf/deploy! {:name name :xml body})]
      (ok {:deployment-id (.getId dep)
           :name (.getName dep)}))
    (catch Exception e
      (log/error e "部署流程失败")
      (fail (.getMessage e)))))

(defn delete-deployment [req]
  (try
    (let [id (get-in req [:params :id])]
      (wf/delete-deployment! id)
      (ok nil))
    (catch Exception e
      (log/error e "删除部署失败")
      (fail (.getMessage e)))))

(defn get-definition-bpmn [req]
  (try
    (let [id (get-in req [:params :id])
          bpmn (wf/get-definition-bpmn id)]
      (if bpmn
        (response/response bpmn)
        (fail "未找到 BPMN" 404)))
    (catch Exception e
      (log/error e "获取 BPMN 失败")
      (fail (.getMessage e)))))

;; ─── 流程实例 ──────────────────────────────────────────────

(defn list-instances [req]
  (try
    (let [params (:params req)
          result (wf/list-instances {:process-key (:processKey params)
                                     :page (or (:page params) 1)
                                     :size (or (:size params) 20)})]
      (ok result))
    (catch Exception e
      (log/error e "查询流程实例失败")
      (fail (.getMessage e)))))

(defn start-instance [req]
  (try
    (let [body (:body-params req)
          instance (wf/start-instance! {:process-key (:processKey body)
                                        :business-key (:businessKey body)
                                        :variables (:variables body)})]
      (ok {:instance-id (.getId instance)
           :process-definition-key (.getProcessDefinitionId instance)}))
    (catch Exception e
      (log/error e "启动流程实例失败")
      (fail (.getMessage e)))))

(defn delete-instance [req]
  (try
    (let [id (get-in req [:params :id])]
      (wf/delete-instance! id)
      (ok nil))
    (catch Exception e
      (log/error e "删除流程实例失败")
      (fail (.getMessage e)))))

;; ─── 任务 ──────────────────────────────────────────────

(defn list-tasks [req]
  (try
    (let [params (:params req)
          result (wf/list-tasks {:assignee (:assignee params)
                                 :candidate-user (:candidateUser params)
                                 :page (or (:page params) 1)
                                 :size (or (:size params) 20)})]
      (ok result))
    (catch Exception e
      (log/error e "查询任务失败")
      (fail (.getMessage e)))))

(defn complete-task [req]
  (try
    (let [body (:body-params req)]
      (wf/complete-task! {:task-id (:taskId body)
                          :variables (:variables body)})
      (ok nil))
    (catch Exception e
      (log/error e "完成任务失败")
      (fail (.getMessage e)))))

(defn claim-task [req]
  (try
    (let [body (:body-params req)]
      (wf/claim-task! (:taskId body) (:userId body))
      (ok nil))
    (catch Exception e
      (log/error e "签收任务失败")
      (fail (.getMessage e)))))

(defn delegate-task [req]
  (try
    (let [body (:body-params req)]
      (wf/delegate-task! (:taskId body) (:userId body))
      (ok nil))
    (catch Exception e
      (log/error e "委托任务失败")
      (fail (.getMessage e)))))
