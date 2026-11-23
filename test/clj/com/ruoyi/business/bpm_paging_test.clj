(ns com.ruoyi.business.bpm-paging-test
  "BPM 待办/已办列表分页回归测试:
   1) /bpm/todo 接受 page/size, 返回 {rows total page size}, total 为全量而非当页条数;
   2) /bpm/done 同理, total 为已完成任务总数;
   3) 不传分页参数时保持旧语义(返回全部), page/size 为 nil;
   4) 非法分页参数(page=abc&size=-1)退化为 page=1,size=10 而不是 500."
  (:require
    [clojure.data.json :as json]
    [clojure.set :as set]
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


(defn- two-node-bpmn
  "start -> a1(admin) -> a2(admin) -> end."
  [key]
  (str "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
       "xmlns:flowable=\"http://flowable.org/bpmn\" id=\"d\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<process id=\"" key "\" name=\"分页回归\" isExecutable=\"true\">"
       "<startEvent id=\"start\"/>"
       "<userTask id=\"a1\" name=\"分页审批1\" flowable:candidateUsers=\"admin\"/>"
       "<userTask id=\"a2\" name=\"分页审批2\" flowable:candidateUsers=\"admin\"/>"
       "<endEvent id=\"end\"/>"
       "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"a1\"/>"
       "<sequenceFlow id=\"f2\" sourceRef=\"a1\" targetRef=\"a2\"/>"
       "<sequenceFlow id=\"f3\" sourceRef=\"a2\" targetRef=\"end\"/>"
       "</process></definitions>"))


(defn- deploy-model!
  "创建并部署两节点模型, 返回 {:model-id mid :model-key key}."
  [app h]
  (let [key (str "paging" (System/currentTimeMillis) (rand-int 1000))
        _ (is (= 200 (:code (parse-json (POST app "/api/business/bpm/model"
                                              {:model_key key :model_name "分页回归" :category_id 0
                                               :form_type "0" :bpmn_xml ""} h)))))
        ml (parse-json (GET app "/api/business/bpm/model?page=1&size=200" {} h))
        mid (:model_id (first (filter #(= key (:model_key %)) (get-in ml [:data :rows]))))
        _ (is (some? mid))
        _ (is (= 200 (:code (parse-json (PUT app (str "/api/business/bpm/model/" mid)
                                             {:model_id mid :model_name "分页回归" :category_id 0
                                              :form_type "0" :bpmn_xml (two-node-bpmn key)
                                              :status "1" :remark ""} h)))))
        _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/model/deploy/" mid) {} h)))))]
    {:model-id mid :model-key key}))


(defn- start-instance!
  [app h mid]
  (let [st (parse-json (POST app "/api/business/bpm/instance" {:model_id mid :form_data {}} h))]
    (is (= 200 (:code st)) (str "start-instance: " (:msg st)))
    (get-in st [:data :process-instance-id])))


(defn- todo
  [app h query]
  (parse-json (GET app (str "/api/business/bpm/todo" query) {} h)))


(defn- done
  [app h query]
  (parse-json (GET app (str "/api/business/bpm/done" query) {} h)))


(deftest todo-pagination-test
  (let [app (handler) h (auth-hdr (login-token "admin"))
        {:keys [model-id]} (deploy-model! app h)
        pids (vec (repeatedly 3 #(start-instance! app h model-id)))
        _ (is (every? some? pids))
        all (todo app h "")
        total (get-in all [:data :total])]
    (is (>= total 3) "至少有本次创建的 3 条待办")
    (is (= total (count (get-in all [:data :rows]))) "不传分页参数时返回全部")
    (is (nil? (get-in all [:data :page])) "旧语义下不返回 page")
    (testing "page=1&size=2 只返回 2 行, total 仍为全量"
      (let [r (todo app h "?page=1&size=2")]
        (is (= 200 (:code r)))
        (is (= 2 (count (get-in r [:data :rows]))))
        (is (= total (get-in r [:data :total])) "total 必须是全量条数, 不能是当页条数")
        (is (= 1 (get-in r [:data :page])))
        (is (= 2 (get-in r [:data :size])))))
    (testing "第二页与第一页的下标任务不相交"
      (let [p1 (set (map :task-id (get-in (todo app h "?page=1&size=2") [:data :rows])))
            p2 (set (map :task-id (get-in (todo app h "?page=2&size=2") [:data :rows])))]
        (is (seq p1))
        (is (seq p2))
        (is (empty? (set/intersection p1 p2)))))
    (testing "只给 size 时按第一页处理"
      (let [r (todo app h "?size=1")]
        (is (= 1 (count (get-in r [:data :rows]))))
        (is (= 1 (get-in r [:data :page])))))
    (testing "非法分页参数退化为 page=1,size=10"
      (let [r (todo app h "?page=abc&size=-1")]
        (is (= 200 (:code r)))
        (is (= 1 (get-in r [:data :page])))
        (is (= 10 (get-in r [:data :size])))))))


(deftest done-pagination-test
  (let [app (handler) h (auth-hdr (login-token "admin"))
        {:keys [model-id]} (deploy-model! app h)
        pid (start-instance! app h model-id)
        tasks (filter #(= pid (:process-instance-id %))
                      (get-in (todo app h "?page=1&size=200") [:data :rows]))
        t1 (:task-id (first tasks))
        _ (is (some? t1))
        _ (is (= 200 (:code (parse-json (POST app (str "/api/business/bpm/task/" t1 "/approve")
                                              {:comment "同意"} h)))))
        all (done app h "")
        total (get-in all [:data :total])]
    (is (>= total 1) "审批完成后已办至少 1 条")
    (testing "已办按结束时间倒序, 刚办完的排在第一"
      (is (= (str t1) (:task-id (first (get-in all [:data :rows]))))))
    (testing "page=1&size=1 只返回 1 行, total 为全量"
      (let [r (done app h "?page=1&size=1")]
        (is (= 200 (:code r)))
        (is (= 1 (count (get-in r [:data :rows]))))
        (is (= total (get-in r [:data :total])))
        (is (= (str t1) (:task-id (first (get-in r [:data :rows])))))))
    (testing "行内保留模型权限开关"
      (let [row (first (get-in (done app h "?page=1&size=1") [:data :rows]))]
        (is (= "1" (:allow_cancel row)))
        (is (= "1" (:allow_withdraw row)))))
    (testing "非法分页参数不报 500"
      (let [r (done app h "?page=abc&size=0")]
        (is (= 200 (:code r)))
        (is (= 1 (get-in r [:data :page])))
        (is (= 10 (get-in r [:data :size])))))))