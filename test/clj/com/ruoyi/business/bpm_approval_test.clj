(ns com.ruoyi.business.bpm-approval-test
  "BPM Phase 1 审批闭环 REST 集成测试:加签/减签/取消/撤回/抄送/可退回节点.
   断言不依赖待办总数(测试环境共享 rouyi.db/flowable,可能有历史遗留流程)."
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest testing is use-fixtures]]
    [com.ruoyi.test-utils :refer [system-state system-fixture GET]]
    [peridot.core :as p]))


(use-fixtures :once (system-fixture))


(defn- handler
  []
  (:handler/ring (system-state)))


(defn- parse-json
  [resp]
  (when (:body resp)
    (try (json/read-str (:body resp) :key-fn keyword)
         (catch Exception _ nil))))


(defn- login-token
  [username]
  (let [ctx (-> (p/session (handler))
                (p/request "/api/auth/login"
                           :request-method :post
                           :content-type "application/json"
                           :body (json/write-str {:username username :password "admin123"})))
        resp (:response ctx)]
    (get-in (parse-json resp) [:data :token])))


(defn- auth-hdr
  [token]
  {"authorization" (str "Bearer " token)})


(defn- POST
  [app path body headers]
  (:response (-> (p/session app)
                 (p/request path
                            :request-method :post
                            :content-type "application/json"
                            :headers headers
                            :body (json/write-str body)))))


(defn- PUT
  [app path body headers]
  (:response (-> (p/session app)
                 (p/request path
                            :request-method :put
                            :content-type "application/json"
                            :headers headers
                            :body (json/write-str body)))))


(defn- DELETE
  [app path body headers]
  (:response (-> (p/session app)
                 (p/request path
                            :request-method :delete
                            :content-type "application/json"
                            :headers headers
                            :body (json/write-str body)))))


;; ── 用户与流程准备 ────────────────────────────────────────────────────

(defn- ensure-user!
  "创建一个专用测试用户(共享 DB 中没有 ry,避免依赖固定账号),返回 {:username u :token t :hdr h}."
  [app admin-h]
  (let [u (str "ph1u" (rand-int 100000))
        r (parse-json (POST app "/api/system/user"
                            {:user_name u :nick_name "Phase1测试" :password "admin123"}
                            admin-h))
        _ (is (= 200 (:code r)))
        token (login-token u)
        _ (is (some? token))]
    {:username u :token token :hdr (auth-hdr token)}))


(defn- two-task-bpmn
  "start → approve1(admin) → [copy1(抄送人, COPY_TASK)] → approve2(admin) → end.
   当 copy-user 非 nil 时在 approve1 之后插入抄送节点(nodeConfig 仅标 nodeType,
   候选人走 candidateUsers 属性 → identityLink 兜底展开)."
  [key copy-user]
  (let [copy-el (when copy-user
                  (str "<userTask id=\"copy1\" name=\"抄送\" flowable:candidateUsers=\"" copy-user "\">"
                       "<extensionElements>"
                       "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>"
                       "<flowable:properties><flowable:property name=\"nodeConfig\" value=\"{&quot;nodeType&quot;:&quot;COPY_TASK&quot;}\"/></flowable:properties>"
                       "</extensionElements></userTask>"))]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"审批闭环测试\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"approve1\" name=\"审批1\" flowable:candidateUsers=\"admin\"/>"
         (or copy-el "")
         "<userTask id=\"approve2\" name=\"审批2\" flowable:candidateUsers=\"admin\"/>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve1\"/>"
         (if copy-user
           (str "<sequenceFlow id=\"f2\" sourceRef=\"approve1\" targetRef=\"copy1\"/>"
                "<sequenceFlow id=\"f2b\" sourceRef=\"copy1\" targetRef=\"approve2\"/>")
           "<sequenceFlow id=\"f2\" sourceRef=\"approve1\" targetRef=\"approve2\"/>")
         "<sequenceFlow id=\"f3\" sourceRef=\"approve2\" targetRef=\"end\"/>"
         "</process></definitions>")))


(defn- deploy-test-model!
  "创建并部署一个测试流程模型,返回 {:model-id mid}."
  [app h copy-user]
  (let [key (str "ph1" (System/currentTimeMillis) (rand-int 1000))
        cat (parse-json (POST app "/api/business/bpm/category" {:name "Phase1测试" :code "ph1" :sort 9} h))
        _ (is (= 200 (:code cat)))
        cat-id (get-in (parse-json (GET app "/api/business/bpm/category?name=Phase1&page=1&size=10" {} h))
                       [:data :rows 0 :category_id])
        m (parse-json (POST app "/api/business/bpm/model"
                            {:model_key key :model_name "审批闭环测试" :category_id cat-id
                             :form_type "1" :form_json "{\"fields\":[]}"} h))
        _ (is (= 200 (:code m)))
        ml (parse-json (GET app "/api/business/bpm/model?page=1&size=100" {} h))
        mid (get-in ml [:data :rows 0 :model_id])
        _ (is (some? mid))
        upd (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                             {:model_id mid :model_name "审批闭环测试" :category_id cat-id
                              :form_type "1" :bpmn_xml (two-task-bpmn key copy-user) :status "1" :remark ""} h))
        _ (is (= 200 (:code upd)))
        dep (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))
        _ (is (= 200 (:code dep)))]
    {:model-id mid}))


(defn- start-instance!
  "发起流程实例,返回 process-instance-id."
  [app h mid]
  (let [st (parse-json (POST app "/api/business/bpm/instance"
                             {:model_id mid :form_data {:days 1 :reason "phase1"}} h))]
    (is (= 200 (:code st)))
    (get-in st [:data :process-instance-id])))


(defn- todo-of
  "某 token 对应用户在本实例上的待办任务列表(keywordized rows)."
  [app hdr pid]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= pid (:process-instance-id %)) (get-in r [:data :rows] []))))


;; ── 主流程:加签 → 减签 → 审批 → 撤回 → 退回 → 撤回到起点 ──────────────

(deftest bpm-phase1-sign-withdraw-return-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [username hdr] :as u} (ensure-user! app h)
        {:keys [model-id]} (deploy-test-model! app h nil)
        pid (start-instance! app h model-id)
        task1 (:task-id (first (todo-of app h pid)))]
    (is (some? pid))
    (is (some? task1))
    (testing "加签：创建子任务并出现在加签列表"
      (let [r (parse-json (POST app "/api/business/bpm/task/create-sign"
                                {:taskId task1 :userIds [username] :type "before" :reason "请协助"} h))
            _ (is (= 200 (:code r)))
            signs (parse-json (GET app (str "/api/business/bpm/task/sign-list?taskId=" task1) {} h))]
        (is (= 1 (count (get-in signs [:data :rows]))))
        (is (= username (:assignee (first (get-in signs [:data :rows])))))
        (is (= "RUNNING" (:status (first (get-in signs [:data :rows])))))))
    (testing "子任务完成前父任务不能通过"
      (let [r (parse-json (POST app (str "/api/business/bpm/task/" task1 "/approve") {:comment "同意"} h))]
        (is (= 500 (:code r)))
        (is (re-find #"加签任务未完成" (:msg r)))))
    (testing "加签子任务在抄送人待办中，可完成"
      (let [child (first (todo-of app hdr pid))]
        (is (some? child))
        (let [r (parse-json (POST app (str "/api/business/bpm/task/" (:task-id child) "/approve")
                                  {:comment "已阅"} hdr))]
          (is (= 200 (:code r))))))
    (testing "再次加签后减签，减签后父任务可通过"
      (let [_ (is (= 200 (:code (parse-json (POST app "/api/business/bpm/task/create-sign"
                                                  {:taskId task1 :userIds [username] :type "after" :reason "复核"} h)))))
            signs (parse-json (GET app (str "/api/business/bpm/task/sign-list?taskId=" task1) {} h))
            _ (is (= 2 (count (get-in signs [:data :rows]))))
            del (parse-json (DELETE app "/api/business/bpm/task/delete-sign"
                                    {:taskId task1 :userIds [username] :reason "不需要了"} h))
            _ (is (= 200 (:code del)))
            signs2 (parse-json (GET app (str "/api/business/bpm/task/sign-list?taskId=" task1) {} h))
            running (filter #(= "RUNNING" (:status %)) (get-in signs2 [:data :rows]))
            _ (is (empty? running))
            ap (parse-json (POST app (str "/api/business/bpm/task/" task1 "/approve") {:comment "同意"} h))]
        (is (= 200 (:code ap)))))
    (testing "审批推进到第二节点后可撤回（撤回已办）"
      (let [task2 (:task-id (first (todo-of app h pid)))]
        (is (some? task2))
        (let [r (parse-json (PUT app "/api/business/bpm/task/withdraw" {:taskId task1} h))]
          (is (= 200 (:code r))))
        ;; token 移回 approve1,admin 重新出现审批1待办
        (is (some #(= "审批1" (:name %)) (todo-of app h pid)))))
    (testing "return-list：第二节点之前已完成节点非空（按流程定义顺序）"
      (let [cur (:task-id (first (todo-of app h pid)))
            _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" cur "/approve")
                                                  {:comment "再次同意"} h)))))
            task2 (:task-id (first (todo-of app h pid)))
            rl (parse-json (GET app (str "/api/business/bpm/task/return-list?taskId=" task2) {} h))]
        (is (some? task2))
        (is (some #(= "approve1" (:activity-id %)) (get-in rl [:data :rows])))))
    (testing "驳回：选择 return-list 节点退回"
      (let [task2 (:task-id (first (todo-of app h pid)))
            r (parse-json (POST app (str "/api/business/bpm/task/" task2 "/reject")
                                {:comment "资料不全" :return_node_id "approve1"} h))]
        (is (= 200 (:code r)))
        (is (some #(= "审批1" (:name %)) (todo-of app h pid)))))
    (testing "发起人撤回到起始节点（withdraw-to-start）"
      (let [r (parse-json (PUT app "/api/business/bpm/task/withdraw-to-start" {:processInstanceId pid} h))]
        (is (= 200 (:code r)))
        (is (some #(= "审批1" (:name %)) (todo-of app h pid)))))
    (testing "流程可继续走到结束"
      (let [t1 (:task-id (first (todo-of app h pid)))
            _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve") {:comment "ok"} h)))))
            t2 (:task-id (first (todo-of app h pid)))
            _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t2 "/approve") {:comment "ok"} h)))))]
        (is (empty? (todo-of app h pid)))))))


;; ── 取消(发起人或管理员)─────────────────────────────────────────────

(deftest bpm-phase1-cancel-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [hdr]} (ensure-user! app h)
        {:keys [model-id]} (deploy-test-model! app h nil)
        pid (start-instance! app h model-id)]
    (testing "非发起人/非管理员不能取消"
      (let [r (parse-json (DELETE app "/api/business/bpm/instance/cancel"
                                  {:id pid :reason "恶作剧"} hdr))]
        (is (= 500 (:code r)))
        (is (re-find #"发起人或管理员" (:msg r)))))
    (testing "发起人可以取消，状态变为 CANCELED"
      (let [r (parse-json (DELETE app "/api/business/bpm/instance/cancel"
                                  {:id pid :reason "不想申请了"} h))
            _ (is (= 200 (:code r)))
            lst (parse-json (GET app "/api/business/bpm/instance?starter_id=admin&page=1&size=50" {} h))
            row (first (filter #(= pid (:process_instance_id %)) (get-in lst [:data :rows])))]
        (is (some? row))
        (is (= "CANCELED" (:status row)))))))


;; ── 抄送:手动抄送 + COPY_TASK 节点自动抄送 ───────────────────────────

(deftest bpm-phase1-copy-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [username hdr]} (ensure-user! app h)
        {:keys [model-id]} (deploy-test-model! app h nil)
        pid (start-instance! app h model-id)]
    (testing "手动抄送写入并可分页查询"
      (let [r (parse-json (POST app "/api/business/bpm/task/copy"
                                {:processInstanceId pid :userIds [username] :reason "请知悉"} h))
            _ (is (= 200 (:code r)))
            page (parse-json (GET app "/api/business/bpm/task/copy/page?page=1&size=10" {} hdr))
            rows (get-in page [:data :rows])]
        (is (pos? (get-in page [:data :total])))
        (is (some #(= pid (:process_instance_id %)) rows))))
    (testing "COPY_TASK 节点：任务创建时自动抄送并自动完成"
      (let [{m2 :model-id} (deploy-test-model! app h username)
            pid2 (start-instance! app h m2)
            ;; 抄送节点在审批1之后:先通过审批1,触发 copy1 节点
            t1 (:task-id (first (todo-of app h pid2)))
            _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                                  {:comment "ok"} h)))))
            page (parse-json (GET app "/api/business/bpm/task/copy/page?page=1&size=10" {} hdr))
            rows (filter #(= pid2 (:process_instance_id %)) (get-in page [:data :rows]))]
        (is (some? pid2))
        (is (pos? (count rows)) "自动抄送记录应存在")
        (is (= "copy1" (:activity_id (first rows))))
        (is (= username (:user_id (first rows))))
        ;; 抄送任务自动完成,流程推进到审批2(admin 待办)
        (is (some #(= "审批2" (:name %)) (todo-of app h pid2)))
        ;; 抄送人没有抄送任务待办(任务已被自动完成)
        (is (empty? (todo-of app hdr pid2)))))))
