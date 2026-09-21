(ns com.ruoyi.business.bpm-phase4-test
  "BPM Phase 4 进阶能力 REST 集成测试:
   模型级 Webhook(本地 HTTP 接收端点),触发器节点(HTTP_REQUEST 回写/UPDATE_FORM/DELETE_FORM),
   子流程 callActivity 变量传递,路由分支节点按条件走线,节点监听器 Create/Assign/Complete 三事件."
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest testing is use-fixtures]]
    [com.ruoyi.test-utils :refer [system-state system-fixture GET]]
    [peridot.core :as p])
  (:import
    (com.sun.net.httpserver
      HttpHandler
      HttpServer)
    (java.net
      InetSocketAddress)
    (java.nio.charset
      StandardCharsets)))


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
                           :body (json/write-str {:username username :password "admin123"})))]
    (get-in (parse-json (:response ctx)) [:data :token])))


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


;; ── 本地 HTTP 接收端点(JDK HttpServer,零依赖)─────────────────────────

(defn- start-receiver!
  "启动本地 /hook 接收端点:记录请求 {:method :path :headers :body},
   统一响应 JSON {\"data\":{\"level\":\"vip\"}}(供触发器响应回写测试)."
  []
  (let [received (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (proxy [HttpHandler] []
                  (handle
                    [exchange]
                    (try
                      (let [body (String. (.readAllBytes (.getRequestBody exchange))
                                          StandardCharsets/UTF_8)
                            headers (into {}
                                          (map (fn [[k v]] [(clojure.string/lower-case (name k)) (first v)]))
                                          (.getRequestHeaders exchange))]
                        (swap! received conj {:method (str (.getRequestMethod exchange))
                                              :path (.getPath (.getRequestURI exchange))
                                              :headers headers
                                              :body body})
                        (.sendResponseHeaders exchange 200
                                              (alength (.getBytes "{\"data\":{\"level\":\"vip\"}}"
                                                                  StandardCharsets/UTF_8)))
                        (with-open [os (.getResponseBody exchange)]
                          (.write os (.getBytes "{\"data\":{\"level\":\"vip\"}}"
                                                StandardCharsets/UTF_8))))
                      (catch Exception _ nil))))]
    (.createContext server "/hook" handler)
    (.setExecutor server nil)
    (.start server)
    {:server server
     :received received
     :port (.getPort (.getAddress server))}))


(defn- stop-receiver!
  [{:keys [server]}]
  (when server (.stop server 0)))


(defn- wait-for
  "轮询等待条件满足(异步 Webhook 投递需要),默认 3s."
  ([pred] (wait-for pred 3000))
  ([pred ms]
   (loop [t 0]
     (cond
       (pred) true
       (>= t ms) false
       :else (do (Thread/sleep 100) (recur (+ t 100)))))))


(defn- received-bodies
  "把收到的请求体解析为 Clojure 数据."
  [receiver]
  (mapv #(try (json/read-str (:body %) :key-fn keyword) (catch Exception _ nil))
        @(:received receiver)))


;; ── 模型准备 ──────────────────────────────────────────────────────────

(defn- one-node-bpmn
  "start → a1(admin) → end(挂 create TaskListener)."
  [key marker]
  (let [listener "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/></extensionElements>"]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"P4" marker "\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"a1\" name=\"审批1" marker "\" flowable:candidateUsers=\"admin\">" listener "</userTask>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"a1\"/>"
         "<sequenceFlow id=\"f2\" sourceRef=\"a1\" targetRef=\"end\"/>"
         "</process></definitions>")))


(defn- two-node-bpmn
  "start → a1(admin) → a2(admin) → end(每节点挂 create TaskListener)."
  [key marker]
  (let [listener "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/></extensionElements>"]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"P4" marker "\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"a1\" name=\"审批1" marker "\" flowable:candidateUsers=\"admin\">" listener "</userTask>"
         "<userTask id=\"a2\" name=\"审批2" marker "\" flowable:candidateUsers=\"admin\">" listener "</userTask>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"a1\"/>"
         "<sequenceFlow id=\"f2\" sourceRef=\"a1\" targetRef=\"a2\"/>"
         "<sequenceFlow id=\"f3\" sourceRef=\"a2\" targetRef=\"end\"/>"
         "</process></definitions>")))


(defn- escape-xml-attr
  [s]
  (-> (or s "")
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "\"" "&quot;")
      (clojure.string/replace "<" "&lt;")
      (clojure.string/replace ">" "&gt;")))


(defn- listener-bpmn
  "两节点流程,a1 节点带 nodeConfig.listeners(create/assign/complete 三事件 HTTP 回调)."
  [key marker url]
  (let [nc (json/write-str
             {"listeners"
              {"create" {"enable" true "url" url
                         "params" [{"key" "taskId" "value" "${taskId}"}
                                   {"key" "event" "value" "${event}"}]}
               "assign" {"enable" true "url" url
                         "params" [{"key" "taskId" "value" "${taskId}"}
                                   {"key" "event" "value" "${event}"}]}
               "complete" {"enable" true "url" url
                           "params" [{"key" "taskId" "value" "${taskId}"}
                                     {"key" "event" "value" "${event}"}]}}})
        ext (str "<extensionElements>"
                 "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>"
                 "<flowable:taskListener event=\"assignment\" delegateExpression=\"${bpmTaskListener}\"/>"
                 "<flowable:taskListener event=\"complete\" delegateExpression=\"${bpmTaskListener}\"/>"
                 "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
                 (escape-xml-attr nc) "\"/></flowable:properties></extensionElements>")]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"L4" marker "\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"a1\" name=\"监听1" marker "\" flowable:candidateUsers=\"admin\">" ext "</userTask>"
         "<userTask id=\"a2\" name=\"监听2" marker "\" flowable:candidateUsers=\"admin\">"
         "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/></extensionElements>"
         "</userTask>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"a1\"/>"
         "<sequenceFlow id=\"f2\" sourceRef=\"a1\" targetRef=\"a2\"/>"
         "<sequenceFlow id=\"f3\" sourceRef=\"a2\" targetRef=\"end\"/>"
         "</process></definitions>")))


(defn- create-model!
  "创建模型(extra 为扩展字段),返回 {:model-id :model-key}."
  [app h extra]
  (let [key (str "ph4" (System/currentTimeMillis) (rand-int 100000))]
    (is (= 200 (:code (parse-json (POST app "/api/business/bpm/model"
                                        (merge {:model_key key :model_name "Phase4测试"
                                                :form_type "0" :bpmn_xml ""}
                                               extra)
                                        h)))))
    (let [ml (parse-json (GET app "/api/business/bpm/model?page=1&size=100" {} h))
          mid (get-in (first (filter #(= key (:model_key %)) (get-in ml [:data :rows]))) [:model_id])]
      (is (some? mid))
      {:model-id mid :model-key key})))


(defn- update-bpmn!
  [app h mid bpmn]
  (is (= 200 (:code (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                                     {:model_id mid :model_name "Phase4测试" :category_id 0
                                      :form_type "0" :bpmn_xml bpmn :status "1" :remark ""}
                                     h))))))


(defn- save-tree!
  [app h mid tree]
  (let [r (parse-json (POST app (str "/api/business/bpm/model/" mid "/tree") tree h))]
    (is (= 200 (:code r)) (str "save-tree: " (:msg r)))))


(defn- deploy!
  [app h mid]
  (let [r (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))]
    (is (= 200 (:code r)) (str "deploy: " (:msg r)))))


(defn- start-instance!
  [app h mid fd]
  (let [r (parse-json (POST app "/api/business/bpm/instance"
                            {:model_id mid :form_data (or fd {})} h))]
    (is (= 200 (:code r)) (str "start-instance: " (:msg r)))
    (:data r)))


(defn- todo-of
  [app hdr pid]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= pid (:process-instance-id %)) (get-in r [:data :rows] []))))


(defn- todo-by-name
  [app hdr name]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= name (:name %)) (get-in r [:data :rows] []))))


(defn- approve!
  [app h task-id]
  (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" task-id "/approve")
                                      {:comment "同意"} h))))))


(defn- claim!
  [app h task-id]
  (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" task-id "/claim") {} h))))))


(defn- ended?
  [app h pid]
  (some #(and (= "end" (:activity-id %)) (:end-time %))
        (get-in (parse-json (GET app (str "/api/business/bpm/instance/history/" pid) {} h))
                [:data :activities])))


;; ── 4.1 Webhook ───────────────────────────────────────────────────────

(deftest bpm-phase4-webhook-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        receiver (start-receiver!)
        url (str "http://127.0.0.1:" (:port receiver) "/hook")
        hook (fn [path]
               {:url (str url path)
                :headers [{"key" "X-Test" "value" "ph4"}]
                :bodyParams [{"key" "starter" "value" "${startUserId}"}
                             {"key" "event" "value" "${event}"}]})
        webhooks {"process_start" (hook "")
                  "task_start" (hook "")
                  "task_end" (hook "")
                  "process_end" (hook "")}]
    (try
      (let [{:keys [model-id model-key]} (create-model! app h {:webhooks (json/write-str webhooks)})
            _ (update-bpmn! app h model-id (one-node-bpmn model-key "W"))
            _ (deploy! app h model-id)
            data (start-instance! app h model-id {:days 3})
            pid (:process-instance-id data)
            _ (is (some? pid) (str "start: " (:msg data)))
            t1 (:task-id (first (todo-of app h pid)))]
        (is (some? t1))
        (testing "process_start / task_start 钩子已收到回调（占位符替换 + headers）"
          (let [bodies (received-bodies receiver)
                ps (first (filter #(= "process_start" (:event %)) bodies))]
            (is (some? ps) (str "process_start 未收到: " (pr-str bodies)))
            (is (= "admin" (:starter ps)))
            ;; task_start 为异步投递(任务创建时业务行尚未落库),轮询等待
            (is (wait-for #(some (fn [b] (= "task_start" (:event b))) (received-bodies receiver)))
                "task_start 未收到")
            (let [ts (first (filter #(= "task_start" (:event %)) (received-bodies receiver)))]
              (is (= "admin" (:starter ts))))
            (is (= "ph4" (get-in (first (filter #(= "/hook" (:path %)) @(:received receiver)))
                                 [:headers "x-test"])))))
        (testing "审批通过 → task_end / process_end 钩子"
          (approve! app h t1)
          (let [bodies (received-bodies receiver)
                te (first (filter #(= "task_end" (:event %)) bodies))
                pe (first (filter #(= "process_end" (:event %)) bodies))]
            (is (some? te) (str "task_end 未收到: " (pr-str bodies)))
            (is (some? pe) (str "process_end 未收到: " (pr-str bodies)))
            (is (= "admin" (:starter pe))))))
      (finally (stop-receiver! receiver)))))


;; ── 4.2 触发器节点 ─────────────────────────────────────────────────────

(defn- trigger-tree
  "start → 触发器 → 路由分支(level==期望值 跳 end,否则走人工节点)→ end."
  [trigger-cfg expect & [left-side]]
  (let [left (or left-side "level")]
    {:id "start" :type "START_USER_NODE" :name "发起"
     :child-node (assoc {:id "t1" :type "TRIGGER_NODE" :name "触发器"
                         :config trigger-cfg
                         :child-node {:id "r1" :type "ROUTER_BRANCH_NODE" :name "路由"
                                      :config {:groups [{:target-node-id "end"
                                                         :rules [{:left-side left
                                                                  :op-code "=="
                                                                  :right-side (str "\"" expect "\"")}]}]}
                                      :child-node {:id "b1" :type "USER_TASK_NODE" :name "人工审批"
                                                   :config {:approve-type "USER"
                                                            :candidate-strategy "USER"
                                                            :candidate-param {:user-ids [1]}}
                                                   :child-node {:id "end" :type "END_EVENT_NODE" :name "结束"}}}}
                        :name (:name trigger-cfg))}))


(deftest bpm-phase4-trigger-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        receiver (start-receiver!)
        url (str "http://127.0.0.1:" (:port receiver) "/hook")]
    (try
      (testing "HTTP_REQUEST：占位符请求 + 响应 JSON 按映射回写流程变量并驱动路由"
        (let [{:keys [model-id]} (create-model! app h {})
              _ (save-tree! app h model-id
                            (trigger-tree {:trigger-type "HTTP_REQUEST"
                                           :name "HTTP触发"
                                           :url url
                                           :method "POST"
                                           :headers [{:key "X-Form" :value "${days}"}]
                                           :body-params [{:key "days" :value "${days}"}]
                                           :response-mappings [{:source "data.level" :target "level"}]}
                                          "vip"))
              _ (deploy! app h model-id)
              pid (:process-instance-id (start-instance! app h model-id {:days 5}))]
          (is (some? pid))
          (let [req (first (filter #(= "/hook" (:path %)) @(:received receiver)))]
            (is (some? req) "触发器应调用本地 HTTP 端点")
            (is (= "5" (get-in (json/read-str (:body req) :key-fn keyword) [:days])))
            (is (= "5" (get-in req [:headers "x-form"]))))
          (is (empty? (todo-of app h pid)) "level=vip 应路由直达 end，无人工任务")
          (is (ended? app h pid))))
      (testing "UPDATE_FORM：满足条件时更新字段并驱动路由；不满足时走默认分支"
        (let [{:keys [model-id]} (create-model! app h {})
              upd {:trigger-type "UPDATE_FORM" :name "更新表单"
                   :conditions [{:left-side "days" :op-code ">=" :right-side "2"}]
                   :fields [{:field "level" :value "high"}]}
              _ (save-tree! app h model-id (trigger-tree upd "high"))
              _ (deploy! app h model-id)
              pid1 (:process-instance-id (start-instance! app h model-id {:days 5}))
              pid2 (:process-instance-id (start-instance! app h model-id {:days 1 :level ""}))]
          (is (some? pid1))
          (is (empty? (todo-of app h pid1)) "days>=2 时 level=high → 直达 end")
          (is (ended? app h pid1))
          (is (some? (first (todo-of app h pid2))) "days=1 条件不满足 → 默认走人工审批")))
      (testing "DELETE_FORM：清除字段后条件不命中 → 走默认分支"
        (let [{:keys [model-id]} (create-model! app h {})
              del {:trigger-type "DELETE_FORM" :name "删除字段"
                   :fields ["temp"]}
              _ (save-tree! app h model-id (trigger-tree del "gone" "marker"))
              _ (deploy! app h model-id)
              pid (:process-instance-id (start-instance! app h model-id {:days 1 :temp "x" :marker "keep"}))]
          (is (some? pid))
          (is (some? (first (todo-of app h pid)))
              "temp 被清除后 temp==x 不成立 → 默认走人工审批")))
      (finally (stop-receiver! receiver)))))


;; ── 4.3 子流程(callActivity 变量传递)───────────────────────────────────

(deftest bpm-phase4-child-process-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        ;; 子流程:cdays>=3 直达结束;否则人工审批 → UPDATE_FORM 回写 cout=child-done → 结束
        child-tree {:id "start" :type "START_USER_NODE" :name "发起"
                    :child-node {:id "cr1" :type "ROUTER_BRANCH_NODE" :name "子路由"
                                 :config {:groups [{:target-node-id "end"
                                                    :rules [{:left-side "cdays" :op-code ">=" :right-side "3"}]}]}
                                 :child-node {:id "ct1" :type "USER_TASK_NODE" :name "子审批"
                                              :config {:approve-type "USER"
                                                       :candidate-strategy "USER"
                                                       :candidate-param {:user-ids [1]}}
                                              :child-node {:id "ct2" :type "TRIGGER_NODE" :name "回写结果"
                                                           :config {:trigger-type "UPDATE_FORM"
                                                                    :conditions []
                                                                    :fields [{:field "cout" :value "child-done"}]}
                                                           :child-node {:id "end" :type "END_EVENT_NODE" :name "结束"}}}}}
        parent-tree (fn [child-key]
                      {:id "start" :type "START_USER_NODE" :name "发起"
                       :child-node {:id "c1" :type "CHILD_PROCESS_NODE" :name "子流程"
                                    :config {:child-process-key child-key
                                             :in-mappings [{:source "days" :target "cdays"}]
                                             :out-mappings [{:source "cout" :target "pout"}]
                                             :initiator-strategy "START_USER"}
                                    :child-node {:id "pr1" :type "ROUTER_BRANCH_NODE" :name "主路由"
                                                 :config {:groups [{:target-node-id "end"
                                                                    :rules [{:left-side "pout"
                                                                             :op-code "=="
                                                                             :right-side "\"child-done\""}]}]}
                                                 :child-node {:id "pb1" :type "USER_TASK_NODE" :name "主审批"
                                                              :config {:approve-type "USER"
                                                                       :candidate-strategy "USER"
                                                                       :candidate-param {:user-ids [1]}}
                                                              :child-node {:id "end" :type "END_EVENT_NODE" :name "结束"}}}}})]
    (let [{child-id :model-id child-key :model-key} (create-model! app h {})]
      (save-tree! app h child-id child-tree)
      (deploy! app h child-id)
      (let [{parent-id :model-id} (create-model! app h {})]
        (save-tree! app h parent-id (parent-tree child-key))
        (deploy! app h parent-id)
        (testing "主→子变量传递：cdays>=3 时子流程自动结束，父流程走默认分支"
          (let [pid (:process-instance-id (start-instance! app h parent-id {:days 5}))]
            (is (some? pid))
            (is (some? (first (todo-of app h pid))) "父流程应停在主审批（子流程无 cout 回写）")))
        (testing "子→主变量传递：子任务审批 → cout 回写 pout → 父流程路由直达 end"
          (let [pid (:process-instance-id (start-instance! app h parent-id {:days 1}))
                _ (is (some? pid))
                child-task (first (todo-by-name app h "子审批"))]
            (is (some? child-task) "cdays=1 时应产生子审批任务（子流程真实启动）")
            (approve! app h (:task-id child-task))
            (is (empty? (todo-of app h pid)) "pout=child-done → 父流程直达 end")
            (is (ended? app h pid))))))))


;; ── 4.4 路由分支节点(纯语法糖展开为排他网关)────────────────────────────

(deftest bpm-phase4-router-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        tree (fn []
               {:id "start" :type "START_USER_NODE" :name "发起"
                :child-node {:id "a1" :type "USER_TASK_NODE" :name "一级审批"
                             :config {:approve-type "USER"
                                      :candidate-strategy "USER"
                                      :candidate-param {:user-ids [1]}}
                             :child-node {:id "r1" :type "ROUTER_BRANCH_NODE" :name "路由分支"
                                          :config {:groups [{:target-node-id "end"
                                                             :rules [{:left-side "days"
                                                                      :op-code ">"
                                                                      :right-side "3"}]}]}
                                          :child-node {:id "b1" :type "USER_TASK_NODE" :name "二级审批"
                                                       :config {:approve-type "USER"
                                                                :candidate-strategy "USER"
                                                                :candidate-param {:user-ids [1]}}
                                                       :child-node {:id "end" :type "END_EVENT_NODE" :name "结束"}}}}})
        {:keys [model-id]} (create-model! app h {})]
    (save-tree! app h model-id (tree))
    (deploy! app h model-id)
    (testing "条件命中：一级审批后直达 end（跳过二级审批）"
      (let [pid (:process-instance-id (start-instance! app h model-id {:days 5}))
            _ (is (some? pid))
            t1 (:task-id (first (todo-of app h pid)))]
        (approve! app h t1)
        (is (empty? (todo-of app h pid)))
        (is (ended? app h pid))))
    (testing "条件未命中：走默认 child-node 进入二级审批"
      (let [pid (:process-instance-id (start-instance! app h model-id {:days 1}))
            _ (is (some? pid))
            t1 (:task-id (first (todo-of app h pid)))]
        (approve! app h t1)
        (is (some? (first (filter #(= "二级审批" (:name %)) (todo-of app h pid)))))))))


;; ── 4.5 节点监听器(Create / Assign / Complete 三事件)────────────────────

(deftest bpm-phase4-node-listener-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        receiver (start-receiver!)
        url (str "http://127.0.0.1:" (:port receiver) "/hook")]
    (try
      (let [{:keys [model-id model-key]} (create-model! app h {})
            _ (update-bpmn! app h model-id (listener-bpmn model-key "N" url))
            _ (deploy! app h model-id)
            pid (:process-instance-id (start-instance! app h model-id {:days 1}))
            _ (is (some? pid))
            t1 (:task-id (first (todo-of app h pid)))
            _ (is (some? t1))
            events-of (fn [ev] (filter #(= ev (:event %)) (received-bodies receiver)))]
        (testing "Create：任务创建即触发"
          (is (some? (first (events-of "create")))))
        (testing "Assign：认领任务时触发（带 taskId）"
          (claim! app h t1)
          (let [as (first (events-of "assign"))]
            (is (some? as) "assign 事件未触发")
            (is (= t1 (:taskId as)))))
        (testing "Complete：审批完成时触发"
          (approve! app h t1)
          (let [cp (first (events-of "complete"))]
            (is (some? cp) "complete 事件未触发")
            (is (= t1 (:taskId cp))))))
      (finally (stop-receiver! receiver)))))
