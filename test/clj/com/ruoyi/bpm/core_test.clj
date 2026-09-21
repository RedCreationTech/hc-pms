(ns com.ruoyi.bpm.core-test
  "BPM 核心 API 单元测试：在独立的内存 H2 引擎上验证，无需 Integrant 系统。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.bpm.core :as bpm])
  (:import
    (org.flowable.engine
      ProcessEngineConfiguration)))


(defn- leave-bpmn
  "带排他网关 + 两个审批节点的请假流程。"
  []
  (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
       "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:flowable=\"http://flowable.org/bpmn\" "
       "id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<process id=\"leaveT\" name=\"请假审批\" isExecutable=\"true\">"
       "<startEvent id=\"start\"/>"
       "<userTask id=\"approve\" name=\"部门经理审批\" flowable:candidateUsers=\"admin\"/>"
       "<exclusiveGateway id=\"gw\"/>"
       "<userTask id=\"hr\" name=\"HR确认\" flowable:candidateUsers=\"hr\"/>"
       "<endEvent id=\"end\"/><endEvent id=\"rejectEnd\"/>"
       "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve\"/>"
       "<sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"gw\"/>"
       "<sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"hr\">"
       "<conditionExpression xsi:type=\"tFormalExpression\">${approved == true}</conditionExpression></sequenceFlow>"
       "<sequenceFlow id=\"f4\" sourceRef=\"gw\" targetRef=\"rejectEnd\">"
       "<conditionExpression xsi:type=\"tFormalExpression\">${approved == false}</conditionExpression></sequenceFlow>"
       "<sequenceFlow id=\"f5\" sourceRef=\"hr\" targetRef=\"end\"/>"
       "</process></definitions>"))


(defn- new-engine!
  "创建一个全新的内存 H2 引擎（每测试独立，避免状态污染）。"
  []
  (let [cfg (ProcessEngineConfiguration/createStandaloneProcessEngineConfiguration)
        _ (.setJdbcUrl cfg (str "jdbc:h2:mem:bpmtest-" (System/nanoTime) ";DB_CLOSE_DELAY=-1"))
        _ (.setDatabaseSchemaUpdate cfg "true")
        _ (.setAsyncExecutorActivate cfg false)]
    (.buildProcessEngine cfg)))


(defn- with-leave-engine
  "在临时内存引擎上部署请假流程,调用 (f engine),最后关闭引擎."
  [f]
  (let [engine (new-engine!)]
    (try
      (bpm/deploy! engine (leave-bpmn) "leaveT" "请假审批")
      (f engine)
      (finally (.close engine)))))


(deftest full-approval-cycle
  (testing "完整审批周期：发起->经理审批->HR审批->结束, 已办/历史可追踪"
    (with-leave-engine
      (fn [engine]
        (let [started (bpm/start! engine "leaveT" "biz-1" {:days 3})
              pid (:process-instance-id started)]
          (is (some? pid))
          (is (= "biz-1" (:business-key started)))
          (is (= ["部门经理审批"] (mapv :name (bpm/todo-list engine "admin"))))
          (is (= 1 (bpm/todo-count engine "admin")))
          (is (= 1 (bpm/instance-count engine)))
          ;; 经理审批通过 -> HR 待办
          (let [t1 (first (bpm/todo-list engine "admin"))]
            (is (true? (bpm/approve! engine (:task-id t1) "admin" "同意")))
            (is (empty? (bpm/todo-list engine "admin")))
            (is (= ["HR确认"] (mapv :name (bpm/todo-list engine "hr")))))
          ;; HR 审批 -> 流程结束
          (let [t2 (first (bpm/todo-list engine "hr"))]
            (is (true? (bpm/approve! engine (:task-id t2) "hr" "OK")))
            (is (= 0 (bpm/instance-count engine))))
          ;; 已办可追踪操作人
          (is (some #(and (= "部门经理审批" (:name %)) (= "admin" (:assignee %)))
                    (bpm/done-list engine "admin")))
          ;; 历史包含两个审批节点
          (let [acts (bpm/history-of engine pid)]
            (is (some #(= "部门经理审批" (:activity-name %)) acts))
            (is (some #(= "HR确认" (:activity-name %)) acts))))))))


(deftest reject-path
  (testing "驳回：approved=false 走排他网关到终止节点"
    (with-leave-engine
      (fn [engine]
        (let [started (bpm/start! engine "leaveT" "biz-2" {:days 1})
              pid (:process-instance-id started)
              t1 (first (bpm/todo-list engine "admin"))]
          (is (true? (bpm/reject! engine (:task-id t1) "admin" "不同意")))
          (is (empty? (bpm/todo-list engine "admin")))
          (is (= 0 (bpm/instance-count engine)))
          (is (some #(= "rejectEnd" (:activity-id %)) (bpm/history-of engine pid))))))))


(deftest transfer-and-claim
  (testing "认领与转办"
    (with-leave-engine
      (fn [engine]
        (let [started (bpm/start! engine "leaveT" "biz-3")
              t1 (first (bpm/todo-list engine "admin"))]
          (is (true? (bpm/claim! engine (:task-id t1) "admin")))
          (is (= "admin" (:assignee (first (bpm/todo-list engine "admin")))))
          (is (true? (bpm/transfer! engine (:task-id t1) "admin" "manager")))
          (is (= ["部门经理审批"] (mapv :name (bpm/todo-list engine "manager"))))
          (is (empty? (bpm/todo-list engine "admin"))))))))
