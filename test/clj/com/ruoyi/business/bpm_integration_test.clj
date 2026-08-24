(ns com.ruoyi.business.bpm-integration-test
  "BPM 业务 REST 集成测试：启动完整系统，走 HTTP 全链路。"
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
    (let [token (login-token)
          h (auth-hdr token)
          app (handler)
          cat-resp (POST app "/api/business/bpm/category" {:name "测试分类" :code "t" :sort 1} h)]
      (is (= 200 (:code (parse-json cat-resp))))
      (let [cat-id (get-in (parse-json (GET app "/api/business/bpm/category?page=1&size=10" {} h))
                           [:data :rows 0 :category_id])
            model-resp (POST app "/api/business/bpm/model"
                             {:model_key "itLeave" :model_name "集成测试请假" :category_id cat-id
                              :form_type "1" :form_json "{\"fields\":[]}"} h)]
        (is (= 200 (:code (parse-json model-resp))))
        (let [mid (get-in (parse-json (GET app "/api/business/bpm/model?page=1&size=10" {} h))
                          [:data :rows 0 :model_id])
              upd (PUT app (str "/api/business/bpm/model/" mid)
                       {:model_id mid :model_name "集成测试请假" :category_id cat-id
                        :form_type "1" :bpmn_xml (leave-bpmn "itLeave") :status "1" :remark ""} h)
              dep (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h))]
          (is (= 200 (:code (parse-json upd))))
          (is (= 200 (:code dep)))
          (is (some? (get-in dep [:data :deployment-id])))
          (let [st (parse-json (POST app "/api/business/bpm/instance"
                                     {:model_id mid :form_data {:days 2 :reason "测试"}} h))
                pid (get-in st [:data :process-instance-id])]
            (is (= 200 (:code st)))
            (is (some? pid))
            (let [todo (parse-json (GET app "/api/business/bpm/todo" {} h))]
              (is (= 1 (get-in todo [:data :total])))
              (is (= "部门经理审批" (get-in todo [:data :rows 0 :name])))
              (let [tid (get-in todo [:data :rows 0 :task-id])
                    ap (parse-json (POST app (str "/api/business/bpm/task/" tid "/approve")
                                         {:comment "同意"} h))]
                (is (= 200 (:code ap)))
                (let [todo2 (parse-json (GET app "/api/business/bpm/todo" {} h))
                      done (parse-json (GET app "/api/business/bpm/done" {} h))]
                  (is (= 0 (get-in todo2 [:data :total])))
                  (is (some #(= tid (:task-id %)) (get-in done [:data :rows]))))))))))))

(deftest bpm-category-crud-via-http
  (testing "分类 CRUD"
    (let [token (login-token) h (auth-hdr token) app (handler)
          resp (POST app "/api/business/bpm/category" {:name "CRUD分类" :code "crud" :sort 2} h)]
      (is (= 200 (:code (parse-json resp))))
      (let [lst (parse-json (GET app "/api/business/bpm/category?page=1&size=10" {} h))]
        (is (pos? (get-in lst [:data :total])))
        (is (some #(= "CRUD分类" (:name %)) (get-in lst [:data :rows])))))))
