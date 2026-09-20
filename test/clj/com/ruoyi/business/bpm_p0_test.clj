(ns com.ruoyi.business.bpm-p0-test
  "BPM P0 UI 差距修复 REST 集成测试：
   P0-1 新建模型入口(key 校验/重名/默认值) / P0-4 allow_cancel·allow_withdraw 开关校验与接口返回 /
   P0-5 抄送节点策略制解析 + 旧 copy-user-ids 兼容 / P0-6 办理人节点默认「办理」按钮。"
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


(defn- DELETE
  [app path body headers]
  (:response (-> (p/session app)
                 (p/request path
                            :request-method :delete
                            :content-type "application/json"
                            :headers headers
                            :body (json/write-str body)))))


;; ── 用户与流程准备 ────────────────────────────────────────────────────

(defn- user-id-of
  "按登录名查用户 ID。"
  [app h username]
  (let [q (parse-json (GET app (str "/api/system/user?user_name=" username "&page=1&size=10") {} h))]
    (get-in q [:data :rows 0 :user_id])))


(defn- ensure-user!
  "创建专用测试用户，返回 {:username u :user-id id :token t :hdr h}。"
  [app admin-h]
  (let [u (str "p0u" (rand-int 100000))
        r (parse-json (POST app "/api/system/user"
                            {:user_name u :nick_name "P0测试" :password "admin123"}
                            admin-h))
        _ (is (= 200 (:code r)))
        token (login-token u)
        _ (is (some? token))
        h (auth-hdr token)
        uid (user-id-of app admin-h u)]
    {:username u :user-id uid :token token :hdr h}))


(defn- create-model!
  "新建模型（P0-1 入口，POST /bpm/model），返回 {:model-id mid :model-key key}。"
  [app h]
  (let [key (str "p0_" (System/currentTimeMillis) "_" (rand-int 1000))
        r (parse-json (POST app "/api/business/bpm/model"
                            {:model_key key :model_name "P0测试流程" :remark "p0"}
                            h))]
    (is (= 200 (:code r)) (str "创建模型失败: " (:msg r)))
    (let [lst (parse-json (GET app (str "/api/business/bpm/model?model_key=" key "&page=1&size=5") {} h))
          mid (get-in lst [:data :rows 0 :model_id])]
      (is (some? mid))
      {:model-id mid :model-key key})))


(defn- update-model!
  "更新模型（部署前写入 BPMN 与权限开关）。flags 如 {:allow_cancel \"0\"}。"
  [app h mid bpmn-xml flags]
  (let [r (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                           (merge {:model_id mid :model_name "P0测试流程" :category_id 0
                                   :form_type "0" :bpmn_xml bpmn-xml :status "1" :remark ""}
                                  flags)
                           h))]
    (is (= 200 (:code r)) (str "更新模型失败: " (:msg r)))))


(defn- deploy-model!
  [app h mid]
  (let [r (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))]
    (is (= 200 (:code r)) (str "部署失败: " (:msg r)))))


(defn- start-instance!
  "以某用户发起流程实例，返回 process-instance-id。"
  [app hdr mid form-data]
  (let [st (parse-json (POST app "/api/business/bpm/instance"
                             {:model_id mid :form_data (or form-data {:days 1 :reason "p0"})}
                             hdr))]
    (is (= 200 (:code st)) (str "发起失败: " (:msg st)))
    (get-in st [:data :process-instance-id])))


(defn- todo-of
  "某 token 对应用户在本实例上的待办任务列表（keywordized rows）。"
  [app hdr pid]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= pid (:process-instance-id %)) (get-in r [:data :rows] []))))


;; ── P0-1 新建模型入口 ─────────────────────────────────────────────────

(deftest bpm-p0-create-model-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        key (str "p0new" (System/currentTimeMillis))]
    (testing "非法 key（数字开头）被拒绝"
      (let [r (parse-json (POST app "/api/business/bpm/model"
                                {:model_key (str "9" key) :model_name "x"} h))]
        (is (= 500 (:code r)))
        (is (re-find #"格式不正确" (:msg r)))))
    (testing "重名 key 被拒绝"
      (let [_ (create-model! app h)
            existing (get-in (parse-json (GET app "/api/business/bpm/model?page=1&size=1" {} h))
                             [:data :rows 0 :model_key])
            r (parse-json (POST app "/api/business/bpm/model"
                                {:model_key existing :model_name "x"} h))]
        (is (= 500 (:code r)))
        (is (re-find #"已存在" (:msg r)))))
    (testing "合法 key 创建成功并补默认 BPMN 骨架/权限开关默认值"
      (let [{:keys [model-id model-key]} (create-model! app h)
            m (parse-json (GET app (str "/api/business/bpm/model/" model-id) {} h))]
        (is (= 200 (:code m)))
        (is (str/includes? (get-in m [:data :bpmn_xml] "") "<startEvent"))
        (is (= "1" (get-in m [:data :allow_cancel])))
        (is (= "1" (get-in m [:data :allow_withdraw])))
        (is (= model-key (get-in m [:data :model_key])))))))


;; ── P0-4 提交人/审批人权限开关 ─────────────────────────────────────────

(defn- two-task-bpmn
  [key]
  "start → approve1(admin) → approve2(admin) → end"
  (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
       "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<process id=\"" key "\" name=\"P0开关测试\" isExecutable=\"true\">"
       "<startEvent id=\"start\"/>"
       "<userTask id=\"approve1\" name=\"审批1\" flowable:candidateUsers=\"admin\"/>"
       "<userTask id=\"approve2\" name=\"审批2\" flowable:candidateUsers=\"admin\"/>"
       "<endEvent id=\"end\"/>"
       "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve1\"/>"
       "<sequenceFlow id=\"f2\" sourceRef=\"approve1\" targetRef=\"approve2\"/>"
       "<sequenceFlow id=\"f3\" sourceRef=\"approve2\" targetRef=\"end\"/>"
       "</process></definitions>"))


(defn- deploy-switch-model!
  [app h flags]
  (let [{:keys [model-id model-key]} (create-model! app h)]
    (update-model! app h model-id (two-task-bpmn model-key) flags)
    (deploy-model! app h model-id)
    {:model-id model-id :model-key model-key}))


(deftest bpm-p0-allow-cancel-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [username hdr]} (ensure-user! app h)
        ;; allow_cancel=0：发起人（非管理员）不可取消；管理员不受限
        {:keys [model-id]} (deploy-switch-model! app h {:allow_cancel "0"})
        pid (start-instance! app hdr model-id nil)]
    (is (some? pid))
    (testing "allow_cancel=0：发起人取消被拒绝"
      (let [r (parse-json (DELETE app "/api/business/bpm/instance/cancel"
                                  {:id pid :reason "不想申请了"} hdr))]
        (is (= 500 (:code r)))
        (is (re-find #"已禁止发起人撤销" (:msg r)))))
    (testing "allow_cancel=0：管理员仍可取消"
      (let [r (parse-json (DELETE app "/api/business/bpm/instance/cancel"
                                  {:id pid :reason "管理员取消"} h))]
        (is (= 200 (:code r)))))
    (testing "allow_cancel=1：发起人可以取消"
      (let [{:keys [model-id]} (deploy-switch-model! app h {:allow_cancel "1"})
            pid2 (start-instance! app hdr model-id nil)
            r (parse-json (DELETE app "/api/business/bpm/instance/cancel"
                                  {:id pid2 :reason "撤销"} hdr))]
        (is (= 200 (:code r)))
        (let [lst (parse-json (GET app (str "/api/business/bpm/instance?starter_id=" username "&page=1&size=50") {} h))
              row (first (filter #(= pid2 (:process_instance_id %)) (get-in lst [:data :rows])))]
          (is (= "CANCELED" (:status row))))))
    (testing "实例列表/历史接口返回模型权限开关"
      (let [{:keys [model-id]} (deploy-switch-model! app h {:allow_cancel "0" :allow_withdraw "0"})
            pid3 (start-instance! app hdr model-id nil)
            lst (parse-json (GET app (str "/api/business/bpm/instance?starter_id=" username "&page=1&size=50") {} h))
            row (first (filter #(= pid3 (:process_instance_id %)) (get-in lst [:data :rows])))
            hist (parse-json (GET app (str "/api/business/bpm/instance/history/" pid3) {} h))]
        (is (= "0" (:allow_cancel row)))
        (is (= "0" (:allow_withdraw row)))
        (is (= "0" (get-in hist [:data :model :allow_cancel])))
        (is (= "0" (get-in hist [:data :model :allow_withdraw]))))))

  (deftest bpm-p0-allow-withdraw-test
    (let [app (handler) token (login-token "admin") h (auth-hdr token)
          {:keys [hdr]} (ensure-user! app h)
          {:keys [model-id]} (deploy-switch-model! app h {:allow_withdraw "0"})
          pid (start-instance! app hdr model-id nil)
          task1 (:task-id (first (todo-of app h pid)))]
      (is (some? task1))
      (testing "审批推进到第二节点"
        (let [r (parse-json (POST app (str "/api/business/bpm/task/" task1 "/approve") {:comment "同意"} h))]
          (is (= 200 (:code r))))
        (is (some #(= "审批2" (:name %)) (todo-of app h pid))))
      (testing "allow_withdraw=0：审批人撤回被拒绝"
        (let [r (parse-json (PUT app "/api/business/bpm/task/withdraw" {:taskId task1} h))]
          (is (= 500 (:code r)))
          (is (re-find #"已禁止审批人撤回" (:msg r)))))
      (testing "allow_withdraw=0：发起人撤回到起始节点也被拒绝"
        (let [r (parse-json (PUT app "/api/business/bpm/task/withdraw-to-start" {:processInstanceId pid} hdr))]
          (is (= 500 (:code r)))
          (is (re-find #"已禁止审批人撤回" (:msg r)))))
      (testing "allow_withdraw=1：审批人撤回成功"
        (let [{:keys [model-id]} (deploy-switch-model! app h {:allow_withdraw "1"})
              pid2 (start-instance! app hdr model-id nil)
              t1 (:task-id (first (todo-of app h pid2)))
              _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve") {:comment "ok"} h)))))
              r (parse-json (PUT app "/api/business/bpm/task/withdraw" {:taskId t1} h))]
          (is (= 200 (:code r)))
          (is (some #(= "审批1" (:name %)) (todo-of app h pid2)))))
      (testing "非发起人/非管理员仍不能撤回（权限优先于开关）"
        (let [other (ensure-user! app h)
              r (parse-json (PUT app "/api/business/bpm/task/withdraw-to-start" {:processInstanceId pid} (:hdr other)))]
          (is (= 500 (:code r)))
          (is (re-find #"发起人或管理员" (:msg r)))))))

  ;; ── P0-5 抄送节点策略扩展 ─────────────────────────────────────────────

  (defn- escape-attr
    [s]
    (-> (json/write-str s)
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")))

  (defn- copy-bpmn
    "start → approve1(admin) → copy1(COPY_TASK) → end。
   copy-cfg 为 copy1 的 nodeConfig（nodeType 由函数补 COPY_TASK）。"
    [key copy-cfg]
    (let [cfg (assoc copy-cfg "nodeType" "COPY_TASK")
          copy-el (str "<userTask id=\"copy1\" name=\"抄送节点\">"
                       "<extensionElements>"
                       "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>"
                       "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
                       (escape-attr cfg) "\"/></flowable:properties>"
                       "</extensionElements></userTask>")]
      (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
           "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
           "<process id=\"" key "\" name=\"P0抄送测试\" isExecutable=\"true\">"
           "<startEvent id=\"start\"/>"
           "<userTask id=\"approve1\" name=\"审批1\" flowable:candidateUsers=\"admin\"/>"
           copy-el
           "<endEvent id=\"end\"/>"
           "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve1\"/>"
           "<sequenceFlow id=\"f2\" sourceRef=\"approve1\" targetRef=\"copy1\"/>"
           "<sequenceFlow id=\"f3\" sourceRef=\"copy1\" targetRef=\"end\"/>"
           "</process></definitions>")))

  (defn- deploy-copy-model!
    [app h copy-cfg]
    (let [{:keys [model-id model-key]} (create-model! app h)]
      (update-model! app h model-id (copy-bpmn model-key copy-cfg) {})
      (deploy-model! app h model-id)
      {:model-id model-id :model-key model-key}))

  (defn- copy-page-of
    [app hdr pid]
    (let [r (parse-json (GET app "/api/business/bpm/task/copy/page?page=1&size=100" {} hdr))]
      (filter #(= pid (:process_instance_id %)) (get-in r [:data :rows] []))))

  (deftest bpm-p0-copy-strategy-test
    (let [app (handler) token (login-token "admin") h (auth-hdr token)]
      (testing "策略制抄送：INITIATOR_SELF（纯运行时解析）给发起人插入抄送记录"
        (let [{:keys [model-id]} (deploy-copy-model! app h {"candidate-strategy" "INITIATOR_SELF"})
              pid (start-instance! app h model-id nil)
              t1 (:task-id (first (todo-of app h pid)))
              _ (is (some? t1))
              _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve") {:comment "ok"} h)))))
              rows (copy-page-of app h pid)]
          (is (some #(= "admin" (:user_id %)) rows))
          (is (some #(= "copy1" (:activity_id %)) rows))))
      (testing "策略制抄送：FORM_USER 按 candidate-param 指定的表单用户字段解析收件人"
        (let [recipient (ensure-user! app h)
              {:keys [model-id]} (deploy-copy-model! app h {"candidate-strategy" "FORM_USER"
                                                            "candidate-param" {"form-user-field" "cc"}})
              pid (start-instance! app h model-id {:cc (:user-id recipient)})
              t1 (:task-id (first (todo-of app h pid)))
              _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve") {:comment "ok"} h)))))
              rows (copy-page-of app (:hdr recipient) pid)]
          (is (= 1 (count rows)))
          (is (= "copy1" (:activity_id (first rows))))))
      (testing "旧键兼容：copy-user-ids 直接配置继续工作"
        (let [copy-user (ensure-user! app h)
              {:keys [model-id]} (deploy-copy-model! app h {"copy-user-ids" [(:user-id copy-user)]})
              pid (start-instance! app h model-id nil)
              t1 (:task-id (first (todo-of app h pid)))
              _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve") {:comment "ok"} h)))))
              rows (copy-page-of app (:hdr copy-user) pid)]
          (is (= 1 (count rows)))
          (is (= "copy1" (:activity_id (first rows))))))))

  ;; ── P0-6 办理人节点 ────────────────────────────────────────────────────

  (defn- transactor-bpmn
    [key admin-uid]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"P0办理人测试\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"transact1\" name=\"办理材料\" flowable:candidateUsers=\"admin\">"
         "<extensionElements>"
         "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>"
         "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
         (escape-attr {"nodeType" "TRANSACTOR"
                       "approve-type" "USER"
                       "candidate-strategy" "USER"
                       "candidate-param" {"user-ids" [admin-uid]}})
         "\"/></flowable:properties>"
         "</extensionElements></userTask>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"transact1\"/>"
         "<sequenceFlow id=\"f2\" sourceRef=\"transact1\" targetRef=\"end\"/>"
         "</process></definitions>"))

  (deftest bpm-p0-transactor-buttons-test
    (let [app (handler) token (login-token "admin") h (auth-hdr token)
          admin-uid (user-id-of app h "admin")
          _ (is (some? admin-uid))
          {:keys [model-id model-key]} (create-model! app h)]
      (update-model! app h model-id (transactor-bpmn model-key admin-uid) {})
      (deploy-model! app h model-id)
      (let [pid (start-instance! app h model-id nil)
            task (:task-id (first (todo-of app h pid)))]
        (is (some? task))
        (testing "办理人节点 task-detail 默认按钮：approve=办理(启用)，其余隐藏"
          (let [d (parse-json (GET app (str "/api/business/bpm/task/" task "/detail") {} h))
                buttons (get-in d [:data :buttons])]
            (is (= true (get-in buttons [:approve :enable])))
            (is (= "办理" (get-in buttons [:approve :displayName])))
            (is (= false (get-in buttons [:reject :enable])))
            (is (= false (get-in buttons [:transfer :enable])))))
        (testing "待办行按钮配置同 task-detail"
          (let [row (first (todo-of app h pid))
                buttons (:buttons row)]
            (is (= "办理" (get-in buttons [:approve :displayName])))
            (is (= false (get-in buttons [:reject :enable])))))
        (testing "办理（approve）可正常完成流程"
          (let [r (parse-json (POST app (str "/api/business/bpm/task/" task "/approve") {:comment "已办理"} h))]
            (is (= 200 (:code r))))
          (is (empty? (todo-of app h pid))))))))
