(ns com.ruoyi.business.bpm-integration-test
  "BPM 业务 REST 集成测试：启动完整系统，走 HTTP 全链路。
   断言不依赖待办总数（测试环境共享 rouyi.db/flowable，可能有历史遗留流程）。"
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

(defn- login-token []
  (let [ctx (-> (p/session (handler))
                (p/request "/api/auth/login"
                           :request-method :post
                           :content-type "application/json"
                           :body (json/write-str {:username "admin" :password "admin123"})))
        resp (:response ctx)]
    (get-in (parse-json resp) [:data :token])))

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

(defn- leave-bpmn [key]
  (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
       "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<process id=\"" key "\" name=\"请假审批\" isExecutable=\"true\">"
       "<startEvent id=\"start\"/>"
       "<userTask id=\"approve\" name=\"部门经理审批\" flowable:candidateUsers=\"admin\"/>"
       "<endEvent id=\"end\"/>"
       "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve\"/>"
       "<sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"end\"/>"
       "</process></definitions>"))

(deftest bpm-full-flow-via-http
  (testing "分类→模型→部署→发起→待办→审批→已办 全链路"
    (let [token (login-token) h (auth-hdr token) app (handler)
          cat-resp (POST app "/api/business/bpm/category" {:name "测试分类" :code "t" :sort 1} h)]
      (is (= 200 (:code (parse-json cat-resp))))
      (let [cat-id (get-in (parse-json (GET app "/api/business/bpm/category?page=1&size=10" {} h))
                           [:data :rows 0 :category_id])
            model-key (str "itLeave" (System/currentTimeMillis))
            model-resp (POST app "/api/business/bpm/model"
                             {:model_key model-key
                              :model_name "集成测试请假" :category_id cat-id
                              :form_type "1" :form_json "{\"fields\":[]}"} h)]
        (is (= 200 (:code (parse-json model-resp))))
        (let [mlist (parse-json (GET app "/api/business/bpm/model?page=1&size=100" {} h))
              mid (get-in mlist [:data :rows 0 :model_id])]
          (is (some? mid))
          (let [upd (PUT app (str "/api/business/bpm/model/" mid)
                         {:model_id mid :model_name "集成测试请假" :category_id cat-id
                          :form_type "1" :bpmn_xml (leave-bpmn model-key) :status "1" :remark ""} h)
                dep (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))]
            (is (= 200 (:code (parse-json upd))))
            (is (= 200 (:code dep)))
            (is (some? (get-in dep [:data :deployment-id])))
            (let [st (parse-json (POST app "/api/business/bpm/instance"
                                       {:model_id mid :form_data {:days 2 :reason "测试"}} h))
                  pid (get-in st [:data :process-instance-id])]
              (is (= 200 (:code st)))
              (is (some? pid))
              ;; 找本流程实例的待办任务（不依赖待办总数，兼容历史遗留）
              (let [todo (parse-json (GET app "/api/business/bpm/todo" {} h))
                    task (first (filter #(= pid (:process-instance-id %))
                                        (get-in todo [:data :rows])))]
                (is (some? task))
                (is (= "部门经理审批" (:name task)))
                (let [tid (:task-id task)
                      ap (parse-json (POST app (str "/api/business/bpm/task/" tid "/approve")
                                           {:comment "同意"} h))]
                  (is (= 200 (:code ap)))
                  (let [todo2 (parse-json (GET app "/api/business/bpm/todo" {} h))
                        done (parse-json (GET app "/api/business/bpm/done" {} h))]
                    (is (not-any? #(= pid (:process-instance-id %))
                                  (get-in todo2 [:data :rows])))
                    (is (some #(= tid (:task-id %)) (get-in done [:data :rows])))))))))))))

(deftest bpm-category-crud-via-http
  (testing "分类 CRUD"
    (let [token (login-token) h (auth-hdr token) app (handler)
          resp (POST app "/api/business/bpm/category" {:name "CRUD分类" :code "crud" :sort 2} h)]
      (is (= 200 (:code (parse-json resp))))
      ;; 用名称过滤查询，避免被历史分类挤出第1页
      (let [lst (parse-json (GET app "/api/business/bpm/category?name=CRUD&page=1&size=10" {} h))]
        (is (pos? (get-in lst [:data :total])))
        (is (some #(= "CRUD分类" (:name %)) (get-in lst [:data :rows])))))))
