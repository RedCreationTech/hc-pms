(ns com.ruoyi.business.bpm-node-config-test
  "BPM Phase 2 节点配置补全 REST 集成测试：
   新候选策略(INITIATOR_SELF/USER_GROUP/FORM_USER/FORM_DEPT_LEADER/EXPRESSION)、
   随机审批(RANDOM)、审批人为空策略(emptyHandler)、操作按钮配置(buttons)、
   手写签名(signEnable)、意见必填(reasonRequire)、驳回默认退回节点、超时 AUTO_PASS(10s 定时器)。
   断言不依赖待办总数（测试环境共享 rouyi.db/flowable，可能有历史遗留流程）。"
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
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


;; ── 用户/部门/分组准备 ────────────────────────────────────────────────────

(defn- ensure-user!
  "创建一个专用测试用户（共享 DB 中没有 ry，避免依赖固定账号），返回 {:username u :user_id id :token t :hdr h}。"
  [app admin-h]
  (let [u (str "ph2u" (rand-int 100000))
        r (parse-json (POST app "/api/system/user"
                            {:user_name u :nick_name "Phase2测试" :password "admin123"}
                            admin-h))
        _ (is (= 200 (:code r)))
        q (parse-json (GET app (str "/api/system/user?user_name=" u "&page=1&size=10") {} admin-h))
        uid (get-in q [:data :rows 0 :user_id])
        token (login-token u)
        _ (is (some? token))]
    {:username u :user-id uid :token token :hdr (auth-hdr token)}))


(defn- two-users!
  "创建两个测试用户。"
  [app admin-h]
  [(ensure-user! app admin-h) (ensure-user! app admin-h)])


;; ── 流程模型部署 ──────────────────────────────────────────────────────────

(defn- escape-attr
  [s]
  (-> (json/write-str s)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))


(defn- node-config-prop
  "nodeConfig JSON → flowable:properties XML 片段。"
  [cfg]
  (str "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
       (escape-attr cfg) "\"/></flowable:properties>"))


(defn- task-el
  "带 create 监听器 + nodeConfig 的 userTask 元素。"
  ([id name cfg] (task-el id name cfg nil))
  ([id name cfg extra-attrs]
   (str "<userTask id=\"" id "\" name=\"" name "\"" (or extra-attrs "") ">"
        "<extensionElements>"
        "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>"
        (node-config-prop cfg)
        "</extensionElements></userTask>")))


(defn- simple-bpmn
  "start → tasks（元素字符串列表）→ end 的线性流程。process id 用占位符，deploy-bpmn! 会替换为模型 key。"
  [task-els]
  (let [ids (map #(second (re-find #"id=\"([^\"]+)\"" %)) task-els)
        chain (concat ["start"] ids ["end"])]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:flowable=\"http://flowable.org/bpmn\" "
         "id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"PH2_PROCESS_KEY\" name=\"节点配置测试\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         (apply str task-els)
         "<endEvent id=\"end\"/>"
         (apply str (map (fn [[a b]]
                           (str "<sequenceFlow id=\"f_" a "_" b "\" sourceRef=\"" a "\" targetRef=\"" b "\"/>"))
                         (partition 2 1 chain)))
         "</process></definitions>")))


(defn- deploy-bpmn!
  "创建分类+模型，写入 BPMN XML（process id 替换为模型 key）并部署。返回 {:model-id mid}。"
  [app h bpmn-xml]
  (let [key (str "ph2" (System/currentTimeMillis) (rand-int 1000))
        bpmn-xml (str/replace bpmn-xml "PH2_PROCESS_KEY" key)
        m (parse-json (POST app "/api/business/bpm/model"
                            {:model_key key :model_name "节点配置测试" :category_id 0
                             :form_type "1" :form_json "{\"fields\":[]}"} h))
        _ (is (= 200 (:code m)))
        ml (parse-json (GET app "/api/business/bpm/model?page=1&size=100" {} h))
        mid (get-in ml [:data :rows 0 :model_id])
        _ (is (some? mid))
        upd (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                             {:model_id mid :model_name "节点配置测试" :category_id 0
                              :form_type "1" :bpmn_xml bpmn-xml :status "1" :remark ""} h))
        _ (is (= 200 (:code upd)))
        dep (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))
        _ (is (= 200 (:code dep)))]
    {:model-id mid}))


(defn- start-instance!
  "发起流程实例，返回 process-instance-id。"
  [app h mid form-data]
  (let [st (parse-json (POST app "/api/business/bpm/instance"
                             {:model_id mid :form_data (or form-data {:reason "phase2"})} h))]
    (is (= 200 (:code st)))
    (get-in st [:data :process-instance-id])))


(defn- todo-of
  "某 token 对应用户在本实例上的待办任务列表（keywordized rows）。"
  [app hdr pid]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= pid (:process-instance-id %)) (get-in r [:data :rows] []))))


(defn- history-tasks
  "实例任务级审批历史。"
  [app h pid]
  (get-in (parse-json (GET app (str "/api/business/bpm/instance/history/" pid) {} h))
          [:data :task-history] []))


;; ── 2.1 新候选策略 ────────────────────────────────────────────────────────

(deftest bpm-phase2-initiator-self-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        cfg {:approve-type "USER" :candidate-strategy "INITIATOR_SELF"
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "发起人自审" cfg)]))
        pid (start-instance! app h model-id nil)
        my-todo (todo-of app h pid)]
    (is (some? pid))
    (testing "INITIATOR_SELF：任务落在发起人本人待办"
      (is (some #(= "发起人自审" (:name %)) my-todo)))
    (testing "审批后流程结束"
      (let [t1 (:task-id (first my-todo))]
        (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                            {:comment "自审通过"} h)))))
        (is (empty? (todo-of app h pid)))))))


(deftest bpm-phase2-form-user-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [username hdr]} (ensure-user! app h)
        cfg {:approve-type "USER" :candidate-strategy "FORM_USER"
             :candidate-param {:form-user-field "approver"}
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "表单用户审批" cfg)]))
        pid (start-instance! app h model-id {:approver username})
        user-todo (todo-of app hdr pid)]
    (is (some? pid))
    (testing "FORM_USER：表单字段指定的用户收到待办"
      (is (some #(= "表单用户审批" (:name %)) user-todo))
      (is (empty? (todo-of app h pid)) "admin 不应收到待办"))))


(deftest bpm-phase2-form-dept-leader-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [user-id hdr]} (ensure-user! app h)
        dept-name (str "PH2部门" (rand-int 100000))
        dept (parse-json (POST app "/api/system/dept"
                               {:parent_id 0 :dept_name dept-name :order_num 99
                                :leader (str user-id) :status "0"} h))
        _ (is (= 200 (:code dept)))
        dl (parse-json (GET app (str "/api/system/dept?dept_name=" dept-name) {} h))
        dept-id (:dept_id (first (filter #(= dept-name (:dept_name %)) (:data dl))))
        _ (is (some? dept-id))
        cfg {:approve-type "USER" :candidate-strategy "FORM_DEPT_LEADER"
             :candidate-param {:form-dept-field "dept"}
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "部门负责人审批" cfg)]))
        pid (start-instance! app h model-id {:dept (str dept-id)})
        leader-todo (todo-of app hdr pid)]
    (testing "FORM_DEPT_LEADER：表单部门字段对应部门的负责人收到待办"
      (is (some? pid))
      (is (some #(= "部门负责人审批" (:name %)) leader-todo)))))


(deftest bpm-phase2-user-group-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        [u1 u2] (two-users! app h)
        grp (parse-json (POST app "/api/business/bpm/user-group"
                              {:name (str "PH2组" (rand-int 100000))
                               :user_ids (str (:user-id u1) "," (:user-id u2))
                               :status "0" :description "phase2"} h))
        _ (is (= 200 (:code grp)))
        gl (parse-json (GET app "/api/business/bpm/user-group?page=1&size=100" {} h))
        gid (get-in gl [:data :rows 0 :group_id])
        _ (is (some? gid))
        cfg {:approve-type "USER" :candidate-strategy "USER_GROUP"
             :candidate-param {:user-group-ids [gid]}
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "用户组审批" cfg)]))
        pid (start-instance! app h model-id nil)]
    (testing "USER_GROUP：组内两个用户都收到待办"
      (is (some? pid))
      (is (some #(= "用户组审批" (:name %)) (todo-of app (:hdr u1) pid)))
      (is (some #(= "用户组审批" (:name %)) (todo-of app (:hdr u2) pid))))))


(deftest bpm-phase2-expression-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        cfg {:approve-type "USER" :candidate-strategy "EXPRESSION"
             :expression "${startUserId}"
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "表达式审批" cfg)]))
        pid (start-instance! app h model-id nil)
        my-todo (todo-of app h pid)]
    (testing "EXPRESSION：${startUserId} 求值为发起人，任务落在发起人待办"
      (is (some? pid))
      (is (some #(= "表达式审批" (:name %)) my-todo)))))


;; ── 2.1 RANDOM 随机审批 ──────────────────────────────────────────────────

(deftest bpm-phase2-random-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        [u1 u2] (two-users! app h)
        ;; USER 静态策略烘焙双候选 + approve-method RANDOM → create 监听器随机指定一人
        cfg {:approve-type "USER" :candidate-strategy "USER"
             :candidate-param {:user-ids [(:user-id u1) (:user-id u2)]}
             :approve-method "RANDOM" :assign-empty-handler {:type "TO_ADMIN"}}
        cand-attr (str " flowable:candidateUsers=\"" (:username u1) "," (:username u2) "\"")
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "随机审批" cfg cand-attr)]))
        pid (start-instance! app h model-id nil)
        todo1 (todo-of app (:hdr u1) pid)
        todo2 (todo-of app (:hdr u2) pid)]
    (testing "RANDOM：恰好一人收到任务且为 assignee（非候选）"
      (is (= 1 (+ (count todo1) (count todo2))))
      (let [t (or (first todo1) (first todo2))]
        (is (some? t))
        (is (some? (:assignee t)))
        (is (contains? #{(:username u1) (:username u2)} (str (:assignee t))))))))


;; ── 2.2 审批人为空策略 ────────────────────────────────────────────────────

(deftest bpm-phase2-empty-handler-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [hdr username]} (ensure-user! app h)
        admin-cfg {:approve-type "USER" :candidate-strategy "USER"
                   :candidate-param {:user-ids [1]}
                   :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        auto-pass-cfg {:approve-type "USER" :candidate-strategy "FORM_USER"
                       :candidate-param {:form-user-field "missing_field"}
                       :approve-method "SEQUENTIAL"
                       :assign-empty-handler {:type "AUTO_PASS"}}
        to-admin-cfg {:approve-type "USER" :candidate-strategy "FORM_USER"
                      :candidate-param {:form-user-field "missing_field"}
                      :approve-method "SEQUENTIAL"
                      :assign-empty-handler {:type "TO_ADMIN"}}
        auto-reject-cfg {:approve-type "USER" :candidate-strategy "FORM_USER"
                         :candidate-param {:form-user-field "missing_field"}
                         :approve-method "SEQUENTIAL"
                         :assign-empty-handler {:type "AUTO_REJECT"}}
        ;; 实例1：AUTO_PASS 节点 → 应自动通过到 admin 节点
        m1 (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "空自动通过" auto-pass-cfg)
                                             (task-el "t2" "后续审批" admin-cfg)]))
        pid1 (start-instance! app h (:model-id m1) {:reason "x"})
        ;; 实例2：TO_ADMIN 节点 → 应转交管理员
        m2 (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "空转管理员" to-admin-cfg)]))
        pid2 (start-instance! app h (:model-id m2) {:reason "x"})
        ;; 实例3：AUTO_REJECT 节点（线性无网关，complete approved=false 后流程结束）
        m3 (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "空自动驳回" auto-reject-cfg)]))
        pid3 (start-instance! app h (:model-id m3) {:reason "x"})]
    (testing "AUTO_PASS：空审批人节点自动通过，流程推进到下一节点"
      (is (some #(= "后续审批" (:name %)) (todo-of app h pid1))))
    (testing "TO_ADMIN：空审批人节点转交管理员"
      (let [t (first (todo-of app h pid2))]
        (is (some? t))
        (is (= "admin" (:assignee t)))))
    (testing "AUTO_REJECT：空审批人节点自动驳回（流程结束，无待办）"
      (is (empty? (todo-of app h pid3))))))


;; ── 2.4 操作按钮配置 ──────────────────────────────────────────────────────

(deftest bpm-phase2-buttons-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        cfg {:approve-type "USER" :candidate-strategy "USER"
             :candidate-param {:user-ids [1]}
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}
             :buttons {"approve" {"enable" false "displayName" "同意啦"}
                       "reject" {"enable" true "displayName" "打回"}
                       "transfer" {"enable" false "displayName" "转办"}
                       "delegate" {"enable" true "displayName" "委派"}
                       "add-sign" {"enable" false "displayName" "加签"}
                       "return" {"enable" true "displayName" "退回"}}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "按钮配置" cfg)]))
        pid (start-instance! app h model-id nil)
        row (first (todo-of app h pid))
        detail (parse-json (GET app (str "/api/business/bpm/task/" (:task-id row) "/detail") {} h))
        buttons (get-in detail [:data :buttons])]
    (testing "todo 行附带节点按钮配置"
      (is (some? row))
      (is (= false (get-in row [:buttons :approve :enable])))
      (is (= "同意啦" (get-in row [:buttons :approve :displayName]))))
    (testing "task-detail 返回按钮配置（未配置项默认启用）"
      (is (= false (get-in buttons [:approve :enable])))
      (is (= "打回" (get-in buttons [:reject :displayName])))
      (is (= false (get-in buttons [:transfer :enable])))
      (is (= true (get-in buttons [:delegate :enable])))
      (is (= false (get-in buttons [:add-sign :enable])))
      (is (= true (get-in buttons [:return :enable]))))))


;; ── 2.5 手写签名 + 2.6 意见必填 ─────────────────────────────────────────

(deftest bpm-phase2-sign-and-reason-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        cfg {:approve-type "USER" :candidate-strategy "USER"
             :candidate-param {:user-ids [1]}
             :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}
             :sign-enable true :reason-require true}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "签名审批" cfg)]))
        pid (start-instance! app h model-id nil)
        row (first (todo-of app h pid))
        task-id (:task-id row)
        detail (parse-json (GET app (str "/api/business/bpm/task/" task-id "/detail") {} h))]
    (testing "task-detail 返回签名/意见必填配置"
      (is (= true (get-in detail [:data :sign-enable])))
      (is (= true (get-in detail [:data :reason-require]))))
    (testing "意见必填：无意见审批被拒绝"
      (let [r (parse-json (POST app (str "/api/business/bpm/task/" task-id "/approve") {} h))]
        (is (= 500 (:code r)))
        (is (re-find #"审批意见" (:msg r)))))
    (testing "带签名图 URL + 意见可审批，历史记录含签名图"
      (let [r (parse-json (POST app (str "/api/business/bpm/task/" task-id "/approve")
                                {:comment "同意" :sign_pic_url "/uploads/test-sign.png"} h))]
        (is (= 200 (:code r))))
      (let [hist (history-tasks app h pid)
            t1 (first (filter #(= "签名审批" (:name %)) hist))]
        (is (= "/uploads/test-sign.png" (:sign-pic-url t1)))))))


;; ── 2.3 驳回默认退回节点 ──────────────────────────────────────────────────

(deftest bpm-phase2-reject-return-node-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        admin-cfg {:approve-type "USER" :candidate-strategy "USER"
                   :candidate-param {:user-ids [1]}
                   :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        reject-cfg {:approve-type "USER" :candidate-strategy "USER"
                    :candidate-param {:user-ids [1]}
                    :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}
                    :reject-handler {:type "RETURN_USER_TASK" :return-node-id "t1"}}
        {:keys [model-id]} (deploy-bpmn! app h (simple-bpmn [(task-el "t1" "一级审批" admin-cfg)
                                                             (task-el "t2" "二级审批" reject-cfg)]))
        pid (start-instance! app h model-id nil)
        t1 (:task-id (first (todo-of app h pid)))
        _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                              {:comment "ok"} h)))))
        t2 (:task-id (first (todo-of app h pid)))
        detail (parse-json (GET app (str "/api/business/bpm/task/" t2 "/detail") {} h))]
    (testing "task-detail 返回默认驳回节点"
      (is (= "t1" (get-in detail [:data :reject-return-node]))))
    (testing "不传 return_node_id 时按配置退回到 t1"
      (let [r (parse-json (POST app (str "/api/business/bpm/task/" t2 "/reject")
                                {:comment "退回重做"} h))]
        (is (= 200 (:code r))))
      (is (some #(= "一级审批" (:name %)) (todo-of app h pid))))))


;; ── 2.7 超时处理（10 秒定时器 AUTO_PASS 实测，轮询等待）──────────────────

(deftest bpm-phase2-timeout-auto-pass-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        admin-cfg {:approve-type "USER" :candidate-strategy "USER"
                   :candidate-param {:user-ids [1]}
                   :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}}
        timeout-cfg {:approve-type "USER" :candidate-strategy "USER"
                     :candidate-param {:user-ids [1]}
                     :approve-method "SEQUENTIAL" :assign-empty-handler {:type "TO_ADMIN"}
                     :timeout-handler {:enable true :type "AUTO_PASS"
                                       :time-duration 10 :time-unit "SECOND"}}
        boundary-el (str "<boundaryEvent id=\"timeout_t1\" attachedToRef=\"t1\" cancelActivity=\"false\">"
                         "<extensionElements>"
                         "<flowable:executionListener event=\"start\" delegateExpression=\"${bpmTimeoutHandler}\"/>"
                         (node-config-prop {:timeout {:action "AUTO_PASS"}})
                         "</extensionElements>"
                         "<timerEventDefinition><timeDuration xsi:type=\"tFormalExpression\">PT10S</timeDuration></timerEventDefinition>"
                         "</boundaryEvent>")
        bpmn (str/replace (simple-bpmn [(task-el "t1" "超时自动通过" timeout-cfg)
                                        (task-el "t2" "后续审批" admin-cfg)])
                          "<endEvent id=\"end\"/>"
                          (str boundary-el "<endEvent id=\"end\"/>"))
        {:keys [model-id]} (deploy-bpmn! app h bpmn)
        pid (start-instance! app h model-id nil)]
    (testing "超时 AUTO_PASS：10 秒定时器触发后自动通过 t1，流程推进到 t2"
      (is (some #(= "超时自动通过" (:name %)) (todo-of app h pid)))
      (loop [attempt 0]
        (let [todos (todo-of app h pid)]
          (if (some #(= "后续审批" (:name %)) todos)
            (do (is (some #(= "后续审批" (:name %)) todos))
                (let [hist (history-tasks app h pid)
                      t1 (first (filter #(= "超时自动通过" (:name %)) hist))]
                  (is (= true (:approved t1)))
                  (is (re-find #"超时自动通过" (str (:comment t1))))))
            (if (> attempt 36)
              (is (some #(= "后续审批" (:name %)) todos) "等待 90 秒后超时 AUTO_PASS 仍未生效")
              (do (Thread/sleep 2500)
                  (recur (inc attempt))))))))))
