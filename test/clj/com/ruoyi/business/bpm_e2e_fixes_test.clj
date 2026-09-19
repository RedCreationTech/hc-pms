(ns com.ruoyi.business.bpm-e2e-fixes-test
  "BPM E2E 修复回归测试（2026-09）：
   1) 发起后 biz_bpm_instance.current_task = 本实例第一节点任务名（不再取全局第一条待办）；
   2) 审批走完最后节点 → status=\"2\"(通过)，驳回终止 → status=\"3\"(驳回)，current_task 清空；
   3) form_id=0 且模型内嵌 form_json 时 task-detail 回退返回模型表单字段；
   4) 委派(delegateTask) → 被委派人在待办可见 → resolve 路由办结后任务回到 owner；
   5) transfer/delegate 缺 to_user → 500 参数提示。"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.ruoyi.test-utils :refer [system-state system-fixture GET]]
            [peridot.core :as p]
            [clojure.data.json :as json]))

(use-fixtures :once (system-fixture))

(defn- handler [] (:handler/ring (system-state)))

(defn- parse-json [resp]
  (when (:body resp)
    (try (json/read-str (:body resp) :key-fn keyword)
         (catch Exception _ nil))))

(defn- login-token [username]
  (let [ctx (-> (p/session (handler))
                (p/request "/api/auth/login"
                           :request-method :post
                           :content-type "application/json"
                           :body (json/write-str {:username username :password "admin123"})))]
    (get-in (parse-json (:response ctx)) [:data :token])))

(defn- auth-hdr [token] {"authorization" (str "Bearer " token)})

(defn- POST [app path body headers]
  (:response (-> (p/session app)
                 (p/request path
                            :request-method :post
                            :content-type "application/json"
                            :headers headers
                            :body (json/write-str body)))))

(defn- PUT [app path body headers]
  (:response (-> (p/session app)
                 (p/request path
                            :request-method :put
                            :content-type "application/json"
                            :headers headers
                            :body (json/write-str body)))))

;; ── 流程准备 ──────────────────────────────────────────────────────────

(defn- gateway-bpmn
  "start → approve1(admin) → gw0 →(approved=true) end /(approved=false) rejectEnd。
   单节点 + 排他网关：通过走 end，驳回走 rejectEnd 终止。"
  [key]
  (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
       "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<process id=\"" key "\" name=\"E2E修复回归\" isExecutable=\"true\">"
       "<startEvent id=\"start\"/>"
       "<userTask id=\"approve1\" name=\"审批1\" flowable:candidateUsers=\"admin\"/>"
       "<exclusiveGateway id=\"gw0\"/>"
       "<endEvent id=\"end\"/>"
       "<endEvent id=\"rejectEnd\"/>"
       "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve1\"/>"
       "<sequenceFlow id=\"f2\" sourceRef=\"approve1\" targetRef=\"gw0\"/>"
       "<sequenceFlow id=\"f3\" sourceRef=\"gw0\" targetRef=\"end\">"
       "<conditionExpression>${approved == true}</conditionExpression></sequenceFlow>"
       "<sequenceFlow id=\"f4\" sourceRef=\"gw0\" targetRef=\"rejectEnd\">"
       "<conditionExpression>${approved == false}</conditionExpression></sequenceFlow>"
       "</process></definitions>"))

(def ^:private embedded-form-json
  "{\"fields\":[{\"field\":\"reason\",\"title\":\"事由\",\"type\":\"input\",\"required\":true}],\"conf\":{\"form\":{\"layout\":\"vertical\"}}}")

(defn- deploy-model!
  "创建（form_id=0 + 内嵌 form_json）并部署测试模型，返回 {:model-id mid :model-key key}。"
  [app h]
  (let [key (str "e2efix" (System/currentTimeMillis) (rand-int 1000))
        m (parse-json (POST app "/api/business/bpm/model"
                            {:model_key key :model_name "E2E修复回归" :category_id 0
                             :form_type "1" :form_json embedded-form-json} h))
        _ (is (= 200 (:code m)))
        ml (parse-json (GET app "/api/business/bpm/model?page=1&size=200" {} h))
        mid (:model_id (first (filter #(= key (:model_key %)) (get-in ml [:data :rows]))))
        _ (is (some? mid))
        upd (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                             {:model_id mid :model_name "E2E修复回归" :category_id 0
                              :form_type "1" :form_json embedded-form-json
                              :bpmn_xml (gateway-bpmn key) :status "1" :remark ""} h))
        _ (is (= 200 (:code upd)))
        dep (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))
        _ (is (= 200 (:code dep)))]
    {:model-id mid :model-key key}))

(defn- start-instance!
  [app h mid]
  (let [st (parse-json (POST app "/api/business/bpm/instance"
                             {:model_id mid :form_data {:reason "e2e-fixes"}} h))]
    (is (= 200 (:code st)))
    (get-in st [:data :process-instance-id])))

(defn- todo-of
  [app hdr pid]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= pid (:process-instance-id %)) (get-in r [:data :rows] []))))

(defn- instance-row
  "按 model_key 查业务实例行（status/current_task）。"
  [app h model-key]
  (let [r (parse-json (GET app (str "/api/business/bpm/instance?model_key=" model-key
                                    "&starter_id=admin&page=1&size=10") {} h))]
    (first (get-in r [:data :rows]))))

;; ── 测试 ──────────────────────────────────────────────────────────────

(deftest bpm-instance-start-current-task-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [model-id model-key]} (deploy-model! app h)
        pid (start-instance! app h model-id)
        _ (is (some? pid))
        first-task (first (todo-of app h pid))
        row (instance-row app h model-key)]
    (testing "发起后 current_task = 本实例第一节点任务名"
      (is (some? first-task))
      (is (= "审批1" (:name first-task)))
      (is (= "审批1" (:current_task row)) "current_task 必须来自新实例的活动任务")
      (is (= "1" (:status row))))))

(deftest bpm-instance-end-status-writeback-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [model-id model-key]} (deploy-model! app h)]
    (testing "审批走完最后节点 → status=2(通过)，current_task 清空"
      (let [pid (start-instance! app h model-id)
            t1 (:task-id (first (todo-of app h pid)))
            _ (is (some? t1))
            ap (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve") {:comment "同意"} h))
            _ (is (= 200 (:code ap)))
            row (instance-row app h model-key)]
        (is (= pid (:process_instance_id row)))
        (is (= "2" (:status row)))
        (is (empty? (str (:current_task row))))))
    (testing "驳回终止 → status=3(驳回)，current_task 清空"
      (let [pid2 (start-instance! app h model-id)
            t2 (:task-id (first (todo-of app h pid2)))
            _ (is (some? t2))
            rj (parse-json (POST app (str "/api/business/bpm/task/" t2 "/reject") {:comment "驳回"} h))
            _ (is (= 200 (:code rj)))
            row2 (first (filter #(= pid2 (:process_instance_id %))
                                (get-in (parse-json (GET app (str "/api/business/bpm/instance?model_key=" model-key
                                                                  "&starter_id=admin&page=1&size=10") {} h))
                                        [:data :rows])))]
        (is (some? row2))
        (is (= "3" (:status row2)))
        (is (empty? (str (:current_task row2))))))))

(deftest bpm-embedded-form-json-task-detail-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [model-id model-key]} (deploy-model! app h)
        pid (start-instance! app h model-id)
        t1 (:task-id (first (todo-of app h pid)))
        _ (is (some? t1))
        detail (parse-json (GET app (str "/api/business/bpm/task/" t1 "/detail") {} h))]
    (testing "模型列表携带内嵌 form_json（发起页直接解析，不发 get-form 0）"
      (let [ml (parse-json (GET app "/api/business/bpm/model?page=1&size=200" {} h))
            row (first (filter #(= model-key (:model_key %)) (get-in ml [:data :rows])))]
        (is (seq (get-in row [:form_json :fields])))))
    (testing "form_id=0 且模型内嵌 form_json 时 task-detail 返回模型表单字段"
      (is (= 200 (:code detail)))
      (let [fields (get-in detail [:data :form :schema :fields])]
        (is (seq fields))
        (is (= "reason" (:field (first fields))))
        (is (= "事由" (:title (first fields))))))
    (testing "instance-history 同样回退内嵌 form_json"
      (let [hist (parse-json (GET app (str "/api/business/bpm/instance/history/" pid) {} h))]
        (is (= 200 (:code hist)))
        (is (seq (get-in hist [:data :form :schema :fields])))))))

(deftest bpm-delegate-resolve-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        u (str "dlgu" (rand-int 100000))
        _ (is (= 200 (:code (parse-json (POST app "/api/system/user"
                                              {:user_name u :nick_name "委派测试" :password "admin123"} h)))))
        u-token (login-token u)
        u-hdr (auth-hdr u-token)
        {:keys [model-id]} (deploy-model! app h)
        pid (start-instance! app h model-id)
        t1 (:task-id (first (todo-of app h pid)))
        _ (is (some? t1))]
    (testing "委派：先认领 → assignee 变为受派人，owner 保留为 admin"
      (let [claim (parse-json (POST app (str "/api/business/bpm/task/" t1 "/claim") {} h))
            _ (is (= 200 (:code claim)))
            r (parse-json (POST app (str "/api/business/bpm/task/" t1 "/delegate") {:to_user u} h))]
        (is (= 200 (:code r))))
      (let [task (get-in (parse-json (GET app (str "/api/business/bpm/task/" t1 "/detail") {} h))
                         [:data :task])]
        (is (= u (:assignee task)))
        (is (= "admin" (:owner task)))))
    (testing "受派人待办可见该任务"
      (is (some #(= t1 (:task-id %)) (todo-of app u-hdr pid))))
    (testing "resolve 路由：办结后任务回到 owner 待办"
      (let [r (parse-json (POST app "/api/business/bpm/task/resolve" {:taskId t1} u-hdr))]
        (is (= 200 (:code r))))
      (let [task (get-in (parse-json (GET app (str "/api/business/bpm/task/" t1 "/detail") {} h))
                         [:data :task])]
        (is (= "admin" (:assignee task)))
        (is (= "admin" (:owner task)))))))

(deftest bpm-transfer-delegate-param-validation-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [model-id]} (deploy-model! app h)
        pid (start-instance! app h model-id)
        t1 (:task-id (first (todo-of app h pid)))
        _ (is (some? t1))]
    (testing "transfer 缺 to_user → 500 提示"
      (let [r (parse-json (POST app (str "/api/business/bpm/task/" t1 "/transfer") {} h))]
        (is (= 500 (:code r)))
        (is (re-find #"to_user" (:msg r)))))
    (testing "delegate 缺 to_user → 500 提示"
      (let [r (parse-json (POST app (str "/api/business/bpm/task/" t1 "/delegate") {} h))]
        (is (= 500 (:code r)))
        (is (re-find #"to_user" (:msg r)))))))
