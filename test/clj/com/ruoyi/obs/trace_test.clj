(ns com.ruoyi.obs.trace-test
  (:require
    [clojure.data.json    :as json]
    [clojure.string       :as str]
    [clojure.test        :refer [deftest is testing]]
    [com.ruoyi.obs.trace  :as obs :refer [*trace*]]))


(deftest new-trace-defaults
  (testing "新 trace spans 为空；project-intent 暴露 who/intent"
    (let [t (obs/new-trace {:who {:user-name "admin"} :intent "新建用户"})]
      (is (empty? (obs/spans t)))
      (is (= "admin" (get-in (obs/project-intent t) [:who :user-name])))
      (is (= "新建用户" (:intent (obs/project-intent t))))
      (is (string? (:id t))))))


(deftest push-collects-and-noop
  (testing "有 trace 时累加 span；无 trace 时 push! 安全 no-op"
    (let [t (atom (obs/new-trace {}))]
      (binding [obs/*trace* t]
        (obs/push! {:op :a})
        (obs/push! {:op :b})
        (is (= 2 (count (obs/spans @*trace*))))
        (is (= #{:a :b} (set (map :op (obs/spans @*trace*))))))
      (is (nil? (binding [obs/*trace* nil] (obs/push! {:op :x})))))))


(deftest project-timeline
  (testing "timeline 给出 慢点 / SQL 次数 / 异常 / 墙钟"
    (let [t (atom (obs/new-trace {}))]
      (binding [obs/*trace* t]
        (doseq [s [{:op :db-sql :kind :dql :stmt "SELECT 1" :ms 5}
                   {:op :db-sql :kind :dml :stmt "INSERT x"  :ms 90 :rows 3}
                   {:op :error :class "x" :msg "boom"}]]
          (obs/push! s)))
      (let [tl (obs/project-timeline @t)]
        (is (= 2 (:db-calls tl)))
        (is (= 1 (count (:errors tl))))
        (is (= 90 (:ms (:slow-leaf tl))))
        (is (number? (:wall-ms tl)))))))


(deftest dml-predicates
  (testing "dml? 对 keyword 以尾部 '!' 判写，对 SQL 文本以动词判写"
    (is (true? (obs/dml? :create-op!)))
    (is (false? (obs/dml? :list-users)))
    (is (true? (obs/dml? "INSERT INTO t VALUES (1)")))
    (is (false? (obs/dml? "SELECT x FROM t")))))


(deftest wrap-query-fn-records-rows
  (testing "有 trace 时记录 SQL kind + 行数；dql 不记 rows"
    (let [t   (atom (obs/new-trace {}))
          wqf (obs/wrap-query-fn (fn [stmt p] [1 2 3]))]
      (binding [obs/*trace* t]
        (wqf :create-op! {})
        (wqf :list-users {:page 1})
        (let [sps (obs/spans @*trace*)]
          (is (= :dml (-> sps first :kind)))
          (is (= 3   (-> sps first :rows)))
          (is (= :dql (-> sps second :kind)))
          (is (nil? (-> sps second :rows))))))))


(deftest wrap-query-fn-rerethrow
  (testing "异常 re-throw，同时记入 trace 错误链"
    (let [t    (atom (obs/new-trace {}))
          fail (obs/wrap-query-fn (fn [stmt p]
                                    (throw (ex-info "db down" {:sql stmt}))))]
      (binding [obs/*trace* t]
        (is (thrown? clojure.lang.ExceptionInfo (fail :boom! {}))))
      (let [sp (last (obs/spans @t))]
        (is (= :error (:kind sp)))
        (is (= "db down" (:msg (get sp :error))))))))


(deftest wrap-query-fn-transparent
  (testing "无 trace 时完全透明"
    (let [wqf (obs/wrap-query-fn (fn [stmt p] stmt))]
      (is (= :q (wqf :q {})))
      (is (nil? obs/*trace*)))))


(deftest project-result-healthy
  (testing "健康请求：ok / status / 副作用 / 契约通过"
    (let [t (atom (obs/new-trace
                    {:contract {:desc "必须 200"
                                :check (fn [{:keys [response]}] (= 200 (:status response)))}}))]
      (binding [obs/*trace* t]
        (obs/push! {:op :db-sql :kind :dml :rows 1 :stmt "INSERT x"})
        (obs/push! {:op :effect :name :cache})
        (swap! *trace* update :meta assoc :response {:status 200 :body "x"}))
      (let [r (obs/project-result @t)]
        (is (true? (:ok r)))
        (is (= 200 (:status r)))
        (is (= 2 (count (:effects r))))
        (is (true? (get-in r [:contract :ok])))
        (is (= [] (:error-chain r)))))))


(deftest project-result-contract-violation
  (testing "契约不通过时 :contract 的 ok 为 false"
    (let [t (atom (obs/new-trace
                    {:contract {:desc "必须 200"
                                :check (fn [{:keys [response]}] (= 200 (:status response)))}}))]
      (binding [obs/*trace* t]
        (swap! *trace* update :meta assoc :response {:status 500}))
      (is (false? (get-in (obs/project-result @t) [:contract :ok]))))))


(deftest observe-exception-handler-records-error
  (testing "observe-exception-handler 把异常记入 trace 并委托 handler"
    (let [handler (fn [msg status ex req] {:status status :msg msg})
          wrapped (obs/observe-exception-handler handler)
          ex       (ex-info "boom" {:sql "DELETE x" :params {:id 1}})
          t         (atom (obs/new-trace {}))]
      (binding [obs/*trace* t]
        (let [ret (wrapped "internal" 500 ex {:uri "/x"})]
          (is (= 500 (:status ret)))
          (let [chain (-> (obs/project-result @*trace*) :error-chain first)]
            (is (= "clojure.lang.ExceptionInfo" (:class chain)))
            (is (= "DELETE x" (get-in chain [:data :sql])))))))))


(deftest canonical-stability
  (testing "集合比较与顺序无关；函数/异常被规约"
    (is (= (obs/canonical {:s #{1 2 3}})
           (obs/canonical {:s #{3 2 1}})))
    (is (= {:f :<fn>} (obs/canonical {:f (fn [])})))
    (is (= {:exception "clojure.lang.ExceptionInfo" :msg "boom"}
           (obs/canonical (ex-info "boom" {}))))))


(deftest replay
  (testing "把结果与重跑结果 canonical 比较"
    (let [mk (fn [r]
               (binding [obs/*trace* (atom (obs/new-trace {}))]
                 (swap! *trace* update :meta assoc :response r)
                 @*trace*))]
      (is (= :stable     (:status (obs/replay (mk {:n 2}) (constantly {:n 2})))))
      (is (= :changed    (:status (obs/replay (mk {:n 2}) (constantly {:n 3})))))
      (is (= :no-capture (:status (obs/replay (obs/new-trace {}) (constantly {:x 1}))))))))


(deftest end-to-end-wrap-trace
  (testing "wrap-trace 接一次请求：过程/结果/x-trace-id/上下文 JSON"
    (let [c       (atom nil)
          db      (obs/wrap-query-fn (fn [stmt p] {:ok stmt}))
          handler (fn [_req]
                    (obs/record-effect! :cache :invalidate)
                    (db :list-users {:page 1})
                    {:status 200
                     :headers {:content-type "application/json"}
                     :body    "{\"ok\":true}"})
          mid     (obs/wrap-trace
                    handler
                    {:on-trace (fn [t] (reset! c t))
                     :meta-fn (fn [req]
                                {:who {:user-name "admin"}
                                 :intent "查询用户"
                                 :contract {:desc "必须 200"
                                            :check (fn [{:keys [response]}]
                                                     (= 200 (:status response)))}})})
          resp    (mid {:uri "/api/system/user" :request-method :get})
          tr      @c
          ac      (obs/agent-context tr)
          js      (obs/render-context tr)]
      (is (= 200 (:status resp)))
      (is (string? (get-in resp [:headers "x-trace-id"])))
      (is (= 1 (:db-calls ac)))
      (is (= :cache (-> ac :effects first :name)))
      (is (true? (:ok ac)))
      (is (true? (get-in ac [:contract :ok])))
      (is (map? (json/read-str js)))
      (is (false? (str/includes? js "<fn>"))))))


(deftest render-context-json-safety
  (testing "render-context 产出可解析 JSON"
    (let [t  (obs/new-trace {:contract {:check (fn []) :desc "d"}})
          t' (binding [obs/*trace* (atom t)]
               (obs/record-error! (ex-info "boom" {:foo 1}))
               @*trace*)]
      (let [s (obs/render-context t')]
        (is (string? s))
        (is (map? (json/read-str s)))))))
