(ns com.ruoyi.business.bpm-phase3-test
  "BPM Phase 3 治理能力 REST 集成测试:
   定义版本页/恢复,模型启停/清理/复制,编号规则,自动去重,标题渲染,摘要,打印."
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


;; ── 模型准备 ──────────────────────────────────────────────────────────

(defn- two-node-bpmn
  "start → a1(admin) → a2(second-user|admin) → end.marker 写入节点名用于区分版本.
   两个人工节点都挂 create TaskListener(候选解析/自动去重在此触发)."
  [key marker second-user]
  (let [listener "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/></extensionElements>"]
    (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
         "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" key "\" name=\"Phase3" marker "\" isExecutable=\"true\">"
         "<startEvent id=\"start\"/>"
         "<userTask id=\"a1\" name=\"审批1" marker "\" flowable:candidateUsers=\"admin\">" listener "</userTask>"
         "<userTask id=\"a2\" name=\"审批2" marker "\" flowable:candidateUsers=\"" (or second-user "admin") "\">" listener "</userTask>"
         "<endEvent id=\"end\"/>"
         "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"a1\"/>"
         "<sequenceFlow id=\"f2\" sourceRef=\"a1\" targetRef=\"a2\"/>"
         "<sequenceFlow id=\"f3\" sourceRef=\"a2\" targetRef=\"end\"/>"
         "</process></definitions>")))


(defn- create-model!
  "创建模型(bpmn-fn 接收 model-key 生成 BPMN;extra 为扩展字段),返回 {:model-id :model-key}."
  [app h bpmn-fn extra]
  (let [key (str "ph3" (System/currentTimeMillis) (rand-int 100000))
        bpmn (bpmn-fn key)
        m (parse-json (POST app "/api/business/bpm/model"
                            (merge {:model_key key :model_name "Phase3测试" :form_type "0"} extra) h))
        _ (is (= 200 (:code m)))
        ml (parse-json (GET app "/api/business/bpm/model?page=1&size=100" {} h))
        mid (get-in (first (filter #(= key (:model_key %)) (get-in ml [:data :rows]))) [:model_id])
        _ (is (some? mid))
        upd (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                             (merge {:model_id mid :model_name "Phase3测试" :category_id 0
                                     :form_type "0" :bpmn_xml bpmn :status "1" :remark ""}
                                    extra) h))
        _ (is (= 200 (:code upd)))]
    {:model-id mid :model-key key}))


(defn- deploy!
  [app h mid]
  (let [r (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))]
    (is (= 200 (:code r)))))


(defn- start-instance!
  "发起流程实例,返回响应 data(:process-instance-id/:bill-code/:name)."
  [app h mid fd]
  (:data (parse-json (POST app "/api/business/bpm/instance"
                           {:model_id mid :form_data (or fd {})} h))))


(defn- todo-of
  [app hdr pid]
  (let [r (parse-json (GET app "/api/business/bpm/todo" {} hdr))]
    (filter #(= pid (:process-instance-id %)) (get-in r [:data :rows] []))))


(defn- instances-of
  [app h model-key]
  (get-in (parse-json (GET app (str "/api/business/bpm/instance?model_key=" model-key "&page=1&size=50") {} h))
          [:data :rows]))


;; ── 3.3 编号规则 + 3.5 标题渲染 + 摘要 ────────────────────────────────

(deftest bpm-phase3-bill-code-name-summary-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        rule {:enable true :prefix "CG-" :infix "DAY" :suffix "-" :length 5}
        ;; 绑定动态表单以校验摘要 label
        form (parse-json (POST app "/api/business/bpm/form"
                               {:form_name "Phase3表单" :form_key (str "ph3f" (rand-int 100000))
                                :form_json (json/write-str {:fields [{:field "days" :title "请假天数"}
                                                                     {:field "reason" :title "事由"}]})} h))
        _ (is (= 200 (:code form)))
        forms (parse-json (GET app "/api/business/bpm/form?page=1&size=100" {} h))
        form-id (:form_id (first (filter #(= "Phase3表单" (:form_name %)) (get-in forms [:data :rows]))))
        {:keys [model-id model-key]} (create-model! app h #(two-node-bpmn % "" "admin")
                                                    {:form_type "1" :form_id form-id
                                                     :name_rule "{发起人}的{days}天{流程名称}"
                                                     :summary_fields "[\"days\"]"
                                                     :process_id_rule (json/write-str rule)})
        _ (deploy! app h model-id)
        d1 (start-instance! app h model-id {:days 3 :reason "测试"})
        d2 (start-instance! app h model-id {:days 5 :reason "再测"})]
    (is (some? (:process-instance-id d1)) (str "start1: " (:msg d1)))
    (is (some? (:process-instance-id d2)) (str "start2: " (:msg d2)))
    (testing "单号：前缀+日期中缀+当日递增流水号(长度5)"
      (let [c1 (:bill-code d1) c2 (:bill-code d2)
            today (.format (java.time.LocalDate/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd"))]
        (is (re-matches (re-pattern (str "CG-" today "-\\d{5}")) c1) (str "单号格式: " c1))
        (is (= (str "CG-" today "-00002") c2) (str "第二个单号应递增: " c1 " -> " c2))))
    (testing "标题渲染：{发起人}/{字段}/{流程名称}"
      (is (= (str "admin的3天Phase3测试") (:name d1)))
      (is (= (str "admin的5天Phase3测试") (:name d2))))
    (testing "实例列表返回单号/名称/摘要(含表单 label)"
      (let [rows (instances-of app h model-key)
            r1 (first (filter #(= (:process-instance-id d1) (:process_instance_id %)) rows))
            summary (:summary r1)]
        (is (= (:bill-code d1) (:bill_code r1)))
        (is (= (:name d1) (:name r1)))
        (is (= [{:key "days" :value "3" :label "请假天数"}] summary))))
    (testing "待办返回摘要与实例名/单号"
      (let [todos (todo-of app h (:process-instance-id d1))
            t1 (first todos)]
        (is (some? t1))
        (is (= (:name d1) (:instance-name t1)))
        (is (= (:bill-code d1) (:bill-code t1)))
        (is (= "3" (:value (first (:summary t1)))))))
    (testing "打印数据完整"
      (let [rows (instances-of app h model-key)
            iid (:instance_id (first (filter #(= (:process-instance-id d1) (:process_instance_id %)) rows)))
            pd (parse-json (GET app (str "/api/business/bpm/instance/print-data?id=" iid) {} h))
            data (:data pd)]
        (is (= 200 (:code pd)))
        (is (= (:name d1) (get-in data [:instance :name])))
        (is (= (:bill-code d1) (get-in data [:instance :bill_code])))
        (is (= [{:field "days" :title "请假天数"} {:field "reason" :title "事由"}]
               (get-in data [:form :schema :fields])))
        (is (pos? (count (:task-history data))))
        (is (contains? (get-in data [:model]) :print_template_html))))))


;; ── 3.4 自动去重 ─────────────────────────────────────────────────────

(deftest bpm-phase3-auto-approval-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        ended? (fn [pid]
                 (some #(and (= "end" (:activity-id %)) (:end-time %))
                       (get-in (parse-json (GET app (str "/api/business/bpm/instance/history/" pid) {} h))
                               [:data :activities])))]
    (testing "APPROVE_ONCE：同一审批人只审一次，第二节点自动通过"
      (let [{:keys [model-id]} (create-model! app h #(two-node-bpmn % "AO" "admin")
                                              {:auto_approval_type "APPROVE_ONCE"})
            _ (deploy! app h model-id)
            pid (:process-instance-id (start-instance! app h model-id {:days 1}))
            t1 (:task-id (first (todo-of app h pid)))]
        (is (some? t1))
        (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                            {:comment "同意"} h)))))
        (is (empty? (todo-of app h pid)) "第二节点应被自动通过")
        (is (ended? pid) "流程应已结束")))
    (testing "CONSECUTIVE：连续重复节点自动通过"
      (let [{:keys [model-id]} (create-model! app h #(two-node-bpmn % "CC" "admin")
                                              {:auto_approval_type "CONSECUTIVE"})
            _ (deploy! app h model-id)
            pid (:process-instance-id (start-instance! app h model-id {:days 1}))
            t1 (:task-id (first (todo-of app h pid)))]
        (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                            {:comment "同意"} h)))))
        (is (empty? (todo-of app h pid)))
        (is (ended? pid))))
    (testing "NONE（默认）：第二节点仍需人工审批"
      (let [{:keys [model-id]} (create-model! app h #(two-node-bpmn % "NO" "admin") {})
            _ (deploy! app h model-id)
            pid (:process-instance-id (start-instance! app h model-id {:days 1}))
            t1 (:task-id (first (todo-of app h pid)))]
        (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                            {:comment "同意"} h)))))
        (is (some #(= "审批2NO" (:name %)) (todo-of app h pid)) "第二节点应保留待办")))))


;; ── 3.1 定义版本页 + 恢复 / 3.2 启停 / 清理 / 复制 ────────────────────

(deftest bpm-phase3-definition-version-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [model-id model-key]} (create-model! app h #(two-node-bpmn % "V1" "admin") {})
        _ (deploy! app h model-id)
        ;; 修改 BPMN(节点名带 V2 标记)再部署 → v2
        _ (is (= 200 (:code (parse-json (PUT app (str "/api/business/bpm/model/" model-id)
                                             {:model_id model-id :model_name "Phase3测试"
                                              :category_id 0 :form_type "0"
                                              :bpmn_xml (two-node-bpmn model-key "V2" "admin")
                                              :status "1" :remark ""} h)))))
        _ (deploy! app h model-id)]
    (testing "定义版本页：按 modelKey 过滤，两个版本倒序"
      (let [r (parse-json (GET app (str "/api/business/bpm/definition/page?modelKey=" model-key "&page=1&size=10") {} h))
            rows (get-in r [:data :rows])]
        (is (= 200 (:code r)))
        (is (= 2 (:total (:data r))))
        (is (= [2 1] (map :version rows)))
        (is (every? #(contains? % :deploy-time) rows))))
    (testing "查看定义 XML"
      (let [def-id (:id (first (get-in (parse-json (GET app (str "/api/business/bpm/definition/page?modelKey=" model-key) {} h)) [:data :rows])))
            r (parse-json (GET app (str "/api/business/bpm/definition/xml?definitionId=" def-id) {} h))]
        (is (= 200 (:code r)))
        (is (re-find #"审批2V2" (get-in r [:data :xml])))))
    (testing "恢复 v1 定义回模型后可再部署"
      (let [defs (get-in (parse-json (GET app (str "/api/business/bpm/definition/page?modelKey=" model-key) {} h)) [:data :rows])
            v1 (first (filter #(= 1 (:version %)) defs))
            r (parse-json (PUT app "/api/business/bpm/definition/restore" {:definitionId (:id v1)} h))]
        (is (= 200 (:code r)))
        (is (= model-id (:model_id (:data r))))
        (let [m (parse-json (GET app (str "/api/business/bpm/model/" model-id) {} h))]
          (is (re-find #"审批2V1" (:bpmn_xml (:data m))) "模型 BPMN 应为 v1 内容")
          (is (nil? (:deployment_id (:data m)))))
        (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/model/deploy/" model-id) {} h))))
            "恢复后应能重新部署")
        (let [rows (get-in (parse-json (GET app (str "/api/business/bpm/definition/page?modelKey=" model-key) {} h)) [:data :rows])]
          (is (= 3 (count rows))))))))


(deftest bpm-phase3-state-clean-copy-test
  (let [app (handler) token (login-token "admin") h (auth-hdr token)
        {:keys [model-id model-key]} (create-model! app h #(two-node-bpmn % "SC" "admin") {})
        _ (deploy! app h model-id)]
    (testing "挂起后不可发起，激活后恢复"
      (let [r (parse-json (PUT app "/api/business/bpm/model/state" {:id model-id :state 2} h))]
        (is (= 200 (:code r)))
        (is (true? (:suspended? (:data r)))))
      (let [defs (get-in (parse-json (GET app (str "/api/business/bpm/definition/page?modelKey=" model-key) {} h)) [:data :rows])]
        (is (every? :suspended? defs)))
      (let [r (parse-json (POST app "/api/business/bpm/instance"
                                {:model_id model-id :form_data {:days 1}} h))]
        (is (= 500 (:code r)))
        (is (re-find #"挂起" (:msg r))))
      (let [r (parse-json (PUT app "/api/business/bpm/model/state" {:id model-id :state 1} h))]
        (is (= 200 (:code r)))
        (is (false? (:suspended? (:data r)))))
      (let [r (start-instance! app h model-id {:days 1})]
        (is (some? (:process-instance-id r)))))
    (testing "复制模型：key+_copy，名称+副本"
      (let [r (parse-json (POST app (str "/api/business/bpm/model/copy?id=" model-id) {} h))]
        (is (= 200 (:code r)))
        (is (= (str model-key "_copy") (get-in r [:data :model_key])))
        (is (= "Phase3测试副本" (get-in r [:data :model_name])))
        (let [copied (parse-json (GET app (str "/api/business/bpm/model/" (get-in r [:data :model_id] -1)) {} h))]
          (is (some? copied))
          (is (nil? (:deployment_id (:data copied))) "副本不应带部署")
          (is (re-find #"审批2SC" (:bpmn_xml (:data copied)))))))
    (testing "清理：删除全部历史实例与部署"
      (let [before (count (instances-of app h model-key))
            _ (is (pos? before))
            r (parse-json (DELETE app (str "/api/business/bpm/model/clean?id=" model-id) {} h))]
        (is (= 200 (:code r)))
        (is (pos? (:deleted-instances (:data r))))
        (is (= 0 (count (instances-of app h model-key))))
        (is (= 0 (get-in (parse-json (GET app (str "/api/business/bpm/definition/page?modelKey=" model-key) {} h)) [:data :total])))
        (is (nil? (:deployment_id (:data (parse-json (GET app (str "/api/business/bpm/model/" model-id) {} h))))))))))
