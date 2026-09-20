(ns com.ruoyi.business.bpm-p1-test
  "BPM P1 模型页体验对齐 REST/单元集成测试：
   发起权限拦截/放行(start_user_ids/start_dept_ids) / 模型·分类排序 API /
   子流程多实例 BPMN 生成(multiInstanceLoopCharacteristics) / 延迟器固定日期 timeDate 生成 /
   Webhook 响应回写(JSON 路径 → 流程变量)。"
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest testing is use-fixtures]]
    [com.ruoyi.domain.business.bpm-flow :as bpm-flow]
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


(defn- bpm-service
  []
  (:app.business/bpm-service (system-state)))


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


;; ── 准备工具 ──────────────────────────────────────────────────────────

(defn- user-row
  "按登录名查用户行（含 user_id / dept_id）。"
  [app h username]
  (let [q (parse-json (GET app (str "/api/system/user?user_name=" username "&page=1&size=10") {} h))]
    (get-in q [:data :rows 0])))


(defn- ensure-user!
  "创建专用测试用户，返回 {:username u :user-id id :hdr h}。"
  [app admin-h]
  (let [u (str "p1u" (rand-int 100000))
        r (parse-json (POST app "/api/system/user"
                            {:user_name u :nick_name "P1测试" :password "admin123"}
                            admin-h))]
    (is (= 200 (:code r)))
    {:username u
     :user-id (:user_id (user-row app admin-h u))
     :hdr (auth-hdr (login-token u))}))


(defn- one-node-bpmn
  "start → a1(admin) → end。"
  [key]
  (let [listener "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/></extensionElements>"]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"P1\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"a1\" name=\"审批\" flowable:candidateUsers=\"admin\">" listener "</userTask>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"a1\"/>"
         "<sequenceFlow id=\"f2\" sourceRef=\"a1\" targetRef=\"end\"/>"
         "</process></definitions>")))


(defn- create-model!
  [app h]
  (let [key (str "p1_" (System/currentTimeMillis) "_" (rand-int 1000))
        r (parse-json (POST app "/api/business/bpm/model"
                            {:model_key key :model_name "P1测试流程" :remark "p1"}
                            h))]
    (is (= 200 (:code r)) (str "创建模型失败: " (:msg r)))
    (let [lst (parse-json (GET app (str "/api/business/bpm/model?model_key=" key "&page=1&size=5") {} h))]
      {:model-id (get-in lst [:data :rows 0 :model_id]) :model-key key})))


(defn- update-model!
  [app h mid key flags]
  (let [r (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                           (merge {:model_id mid :model_name "P1测试流程" :category_id 0
                                   :form_type "0" :bpmn_xml (one-node-bpmn key)
                                   :status "1" :remark ""}
                                  flags)
                           h))]
    (is (= 200 (:code r)) (str "更新模型失败: " (:msg r)))))


(defn- deploy-model!
  [app h mid]
  (let [r (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))]
    (is (= 200 (:code r)) (str "部署失败: " (:msg r)))))


(defn- start-instance
  "发起（不断言），返回响应。form-data 可选。"
  ([app hdr mid] (start-instance app hdr mid {}))
  ([app hdr mid form-data]
   (parse-json (POST app "/api/business/bpm/instance"
                     {:model_id mid :form_data form-data}
                     hdr))))


;; ── P1-1 发起权限：指定人员 拦截/放行 ──────────────────────────────────

(deftest bpm-p1-start-permission-user-test
  (let [app (handler)
        admin-h (auth-hdr (login-token "admin"))
        admin-id (:user_id (user-row app admin-h "admin"))
        u (ensure-user! app admin-h)
        {:keys [model-id model-key]} (create-model! app admin-h)]
    (update-model! app admin-h model-id model-key
                   {:start_user_ids (json/write-str [admin-id])
                    :start_dept_ids "[]"})
    (deploy-model! app admin-h model-id)
    (testing "不在指定人员名单内 → 500 您没有权限发起该流程"
      (let [r (start-instance app (:hdr u) model-id)]
        (is (= 500 (:code r)))
        (is (= "您没有权限发起该流程" (:msg r)))))
    (testing "指定人员内 → 放行"
      (let [r (start-instance app admin-h model-id)]
        (is (= 200 (:code r)) (str "发起应成功: " (:msg r)))
        (is (some? (get-in r [:data :process-instance-id])))))))


;; ── P1-2 发起权限：指定部门 放行/拦截 ──────────────────────────────────

(deftest bpm-p1-start-permission-dept-test
  (let [app (handler)
        admin-h (auth-hdr (login-token "admin"))
        admin-dept (:dept_id (user-row app admin-h "admin"))
        u (ensure-user! app admin-h)
        {:keys [model-id model-key]} (create-model! app admin-h)]
    (is (some? admin-dept) "admin 应有部门")
    (update-model! app admin-h model-id model-key
                   {:start_user_ids "[]"
                    :start_dept_ids (json/write-str [admin-dept])})
    (deploy-model! app admin-h model-id)
    (testing "admin 在指定部门 → 放行"
      (let [r (start-instance app admin-h model-id)]
        (is (= 200 (:code r)) (str "发起应成功: " (:msg r)))))
    (testing "无部门测试用户不在指定部门 → 拦截"
      (let [r (start-instance app (:hdr u) model-id)]
        (is (= 500 (:code r)))
        (is (= "您没有权限发起该流程" (:msg r)))))))


;; ── P1-3 模型排序 API ─────────────────────────────────────────────────

(deftest bpm-p1-model-sort-test
  (let [app (handler)
        h (auth-hdr (login-token "admin"))
        m1 (create-model! app h)
        m2 (create-model! app h)
        m3 (create-model! app h)]
    (testing "默认按 order_num(0) + model_id 倒序"
      (let [r (parse-json (GET app "/api/business/bpm/model?page=1&size=100" {} h))
            ids (mapv :model_id (:rows (:data r)))]
        (is (= [(:model-id m3) (:model-id m2) (:model-id m1)]
               (take 3 (filter #(#{(:model-id m1) (:model-id m2) (:model-id m3)} %) ids))))))
    (testing "PUT /bpm/model/sort 倒序保存后列表顺序随之改变"
      (let [r (parse-json (PUT app "/api/business/bpm/model/sort"
                               {:ids [(:model-id m1) (:model-id m2) (:model-id m3)]} h))]
        (is (= 200 (:code r)))
        (is (= 3 (get-in r [:data :sorted]))))
      (let [r (parse-json (GET app "/api/business/bpm/model?page=1&size=10000" {} h))
            ids (mapv :model_id (:rows (:data r)))
            pos (fn [id] (first (keep-indexed (fn [i x] (when (= id x) i)) ids)))]
        (is (every? some? (map (comp pos :model-id) [m1 m2 m3])))
        (is (= [(:model-id m1) (:model-id m2) (:model-id m3)]
               (mapv #(nth ids (pos (:model-id %))) [m1 m2 m3])))))))


;; ── P1-4 分类排序 API ─────────────────────────────────────────────────

(deftest bpm-p1-category-sort-test
  (let [app (handler)
        h (auth-hdr (login-token "admin"))
        n1 (str "P1分类A" (rand-int 10000))
        n2 (str "P1分类B" (rand-int 10000))]
    (let [r1 (parse-json (POST app "/api/business/bpm/category" {:name n1 :code (str "p1a" (rand-int 10000))} h))
          r2 (parse-json (POST app "/api/business/bpm/category" {:name n2 :code (str "p1b" (rand-int 10000))} h))]
      (is (= 200 (:code r1)) (str "创建分类1失败: " (:msg r1)))
      (is (= 200 (:code r2)) (str "创建分类2失败: " (:msg r2))))
    (let [cats (get-in (parse-json (GET app "/api/business/bpm/category?page=1&size=100" {} h)) [:data :rows])
          id1 (:category_id (first (filter #(= n1 (:name %)) cats)))
          id2 (:category_id (first (filter #(= n2 (:name %)) cats)))]
      (is (and id1 id2))
      (testing "倒序保存排序"
        (let [r (parse-json (PUT app "/api/business/bpm/category/sort" {:ids [id2 id1]} h))]
          (is (= 200 (:code r)))))
      (let [cats2 (get-in (parse-json (GET app "/api/business/bpm/category?page=1&size=100" {} h)) [:data :rows])
            pos (fn [id] (first (keep-indexed (fn [i c] (when (= id (:category_id c)) i)) cats2)))]
        (is (< (pos id2) (pos id1)) "倒序后 id2 应排在 id1 前")))))


;; ── P1-5 子流程多实例 BPMN 生成 ────────────────────────────────────────

(def ^:private p1-tree-child-mi
  {:id "start" :type "START_USER_NODE" :name "发起人"
   :child-node {:id "c1" :type "CHILD_PROCESS_NODE" :name "子流程"
                :config {:child-process-key "sub_key"
                         :mi-enable true :mi-sequential false :mi-ratio 60
                         :mi-source "FIXED" :mi-count 3}
                :child-node {:id "end" :type "END_EVENT_NODE" :name "结束"}}})


(deftest bpm-p1-child-multi-instance-bpmn-test
  (let [xml (bpm-flow/tree->bpmn p1-tree-child-mi "p1_mi")]
    (testing "callActivity 外包 multiInstanceLoopCharacteristics"
      (is (clojure.string/includes? xml "multiInstanceLoopCharacteristics"))
      (is (clojure.string/includes? xml "flowable:collection=\"${miList_c1}\""))
      (is (clojure.string/includes? xml "isSequential=\"false\""))
      (is (re-find #"nrOfCompletedInstances / nrOfInstances >= 0\.60" xml)))
    (testing "回读还原 mi-* 配置"
      (let [back (bpm-flow/bpmn->tree xml)
            cfg (get-in back [:child-node :config])]
        (is (= true (:mi-enable cfg)))
        (is (= false (:mi-sequential cfg)))
        (is (= 60 (:mi-ratio cfg)))))
    (testing "串行 + 全部完成（无比例表达式）"
      (let [xml2 (bpm-flow/tree->bpmn
                   (assoc-in p1-tree-child-mi [:child-node :config]
                             {:child-process-key "sub_key" :mi-enable true
                              :mi-sequential true :mi-ratio 100})
                   "p1_mi2")]
        (is (clojure.string/includes? xml2 "isSequential=\"true\""))
        (is (clojure.string/includes? xml2 "${nrOfCompletedInstances >= nrOfInstances}"))))))


;; ── P1-6 延迟器固定日期 timeDate 生成 ─────────────────────────────────

(deftest bpm-p1-delay-timedate-test
  (let [tree {:id "start" :type "START_USER_NODE" :name "发起人"
              :child-node {:id "d1" :type "DELAY_TIMER_NODE" :name "延迟"
                           :config {:timer-type "DATE" :time-date "2026-09-20T10:00:00"}
                           :child-node {:id "end" :type "END_EVENT_NODE" :name "结束"}}}
        xml (bpm-flow/tree->bpmn tree "p1_delay")]
    (testing "生成 timeDate 而非 timeDuration"
      (is (clojure.string/includes? xml "<timeDate xsi:type=\"tFormalExpression\">2026-09-20T10:00:00</timeDate>"))
      (is (not (clojure.string/includes? xml "<timeDuration"))))
    (testing "回读还原 timer-type/time-date"
      (let [back (bpm-flow/bpmn->tree xml)]
        (is (= "DATE" (get-in back [:child-node :config :timer-type])))
        (is (= "2026-09-20T10:00:00" (get-in back [:child-node :config :time-date])))))
    (testing "时长模式仍生成 timeDuration"
      (let [xml2 (bpm-flow/tree->bpmn
                   (assoc-in tree [:child-node :config] {:time-duration 6 :time-unit "HOUR"})
                   "p1_delay2")]
        (is (clojure.string/includes? xml2 "<timeDuration xsi:type=\"tFormalExpression\">PT6H</timeDuration>"))))))


;; ── P1-7 Webhook 响应回写 ─────────────────────────────────────────────

(defn- start-receiver!
  "本地 /hook 端点：响应 JSON {\"data\":{\"level\":\"vip\"}}，供响应回写测试。"
  []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (proxy [HttpHandler] []
                  (handle
                    [exchange]
                    (try
                      (.sendResponseHeaders exchange 200
                                            (alength (.getBytes "{\"data\":{\"level\":\"vip\"}}"
                                                                StandardCharsets/UTF_8)))
                      (with-open [os (.getResponseBody exchange)]
                        (.write os (.getBytes "{\"data\":{\"level\":\"vip\"}}"
                                              StandardCharsets/UTF_8)))
                      (catch Exception _ nil))))]
    (.createContext server "/hook" handler)
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))


(defn- stop-receiver!
  [{:keys [server]}]
  (when server (.stop server 0)))


(defn- wait-for
  ([pred] (wait-for pred 3000))
  ([pred ms]
   (loop [t 0]
     (cond (pred) true
           (>= t ms) false
           :else (do (Thread/sleep 100) (recur (+ t 100)))))))


(defn- instance-var
  "读取流程实例变量（运行中 RuntimeService / 结束后 HistoryService）。"
  [pid name]
  (let [engine (:engine (bpm-service))
        rt (.getRuntimeService ^org.flowable.engine.ProcessEngine engine)]
    (try (.getVariable rt pid name)
         (catch Exception _ nil))))


(deftest bpm-p1-webhook-response-writeback-test
  (let [app (handler)
        h (auth-hdr (login-token "admin"))
        receiver (start-receiver!)
        {:keys [model-id model-key]} (create-model! app h)]
    (try
      (update-model! app h model-id model-key {})
      (update-model! app h model-id model-key
                     {:webhooks (json/write-str
                                  {"process_start"
                                   {"enable" true
                                    "url" (str "http://127.0.0.1:" (:port receiver) "/hook")
                                    "headers" []
                                    "bodyParams" [{"key" "k" "value" "v"}]
                                    "response-mappings" [{"key" "data.level" "value" "level"}]}})})
      (deploy-model! app h model-id)
      (let [r (start-instance app h model-id)]
        (is (= 200 (:code r)) (str "发起失败: " (:msg r)))
        (let [pid (get-in r [:data :process-instance-id])]
          (testing "POST 成功后按 JSON 路径回写流程变量"
            (is (wait-for #(= "vip" (instance-var pid "level"))))
            (is (= "vip" (instance-var pid "level"))))))
      (finally (stop-receiver! receiver)))))
