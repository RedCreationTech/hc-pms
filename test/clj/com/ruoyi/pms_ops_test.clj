(ns com.ruoyi.pms-ops-test
  "企业接口基础协议的真实网络,隔离数据库和失败恢复验证."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.integration :as integration]
            [com.ruoyi.domain.pms.integration.inbox :as inbox]
            [com.ruoyi.domain.pms.integration.outbox :as outbox]
            [com.ruoyi.domain.pms.integration.dispatch :as dispatch]
            [com.ruoyi.domain.pms.integration.transport :as transport]
            [conman.core :as conman] [migratus.core :as migratus] [next.jdbc :as jdbc])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]))

(def ^:dynamic *svc* nil)

(defn- fixture
  "在隔离SQLite或CI的MySQL中迁移并建立专属审批账户."
  [f]
  (let [provided (System/getenv "PMS_TEST_JDBC_URL")
        file (when-not provided (java.io.File/createTempFile "pms-finance-" ".db"))
        url (or provided (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                        :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9500,'Ops test','ops-test',40,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9500,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
      (doseq [id [9501 9502 9503]]
        (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(?,1,?,?,'0','0')" id (str "fin-" id) (str "财务测试" id)])
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9500)" id]))
      (let [files (->> (.listFiles (io/file "resources/sql"))
                       (filter #(re-matches #"pms.*\.sql" (.getName %)))
                       (map #(str "sql/" (.getName %))) sort)
            queries (:fns (apply conman/bind-connection-map db {} files))
            query (fn ([k p] ((get-in queries [k :fn]) p))
                      ([tx k p] ((get-in queries [k :fn]) tx p)))]
        (binding [*svc* {:db db :query-fn query}] (f)))
      (finally (when file (.delete file))))))

(use-fixtures :once fixture)

(defn- actor "读取实际有效身份和权限." [id] (pms/actor *svc* {:user-id id}))
(defn- version "读取当前项目版本." [id] (:version ((:query-fn *svc*) :pms/project {:project_id id})))
(defn- error-status "提取业务错误状态." [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(defn- command!
  "以指定成员携带真实版本执行业务命令."
  ([f id args body] (command! 9501 f id args body))
  ([uid f id args body]
   (apply f *svc* (actor uid) id (concat args [(assoc body :version (version id))]))))

(defn- project!
  "创建项目并设置审批人和普通成员,不直接改生命周期状态."
  []
  (let [p (pms/create-project! *svc* (actor 9501)
             {:project_no (str "FIN-" (kernel/id)) :name "生命周期验收"
              :manager_id 9501 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id p)]
    (pms/set-member! *svc* (actor 9501) id {:user_id 9502 :role "viewer"})
    (pms/set-member! *svc* (actor 9501) id {:user_id 9503 :role "editor"})
    id))

(defn- document!
  "创建真实附件供确定版本外发."
  [id]
  (:result (command! gov/command! id [:documents :create nil]
                    {:code (kernel/id) :title "外发证据" :filename "proof.txt" :content "  原始证据\n"})))

(defn- enqueue!
  "排队实际附件版本并返回消息."
  [id doc key]
  (:result (command! outbox/enqueue! id [] {:target "crm" :topic "document.registered"
                                           :source_id (:id doc) :idempotency_key key})))

(defn- with-endpoint
  "启动隔离本地HTTP接收方,验证真实网络和受控回执协议."
  [responder f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        seen (atom [])]
    (.createContext server "/events"
      (reify HttpHandler
        (handle [_ exchange]
          (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                key (.getFirst (.getRequestHeaders exchange) "Idempotency-Key")
                _ (swap! seen conj {:body body :key key})
                [status response] (responder body)
                bytes (.getBytes (json/generate-string response) "UTF-8")]
            (.sendResponseHeaders exchange status (alength bytes))
            (with-open [stream (.getResponseBody exchange)] (.write stream bytes))))))
    (.start server)
    (try
      (binding [*svc* (assoc *svc* :integration-connectors
                             {:crm {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/events") :allow_loopback true}})]
        (f seen))
      (finally (.stop server 0)))))

(deftest inbox-is-monotonic-and-collisions-do-not-overwrite
  (let [id (project!) body {:source "erp" :event_id "event-2" :entity_type "order"
                             :external_key "ORDER-1" :source_revision 2 :data {:status "released"}}
        first-message (:result (command! inbox/receive! id [] body))
        repeat-message (:result (command! inbox/receive! id [] body))]
    (is (= (:id first-message) (:id repeat-message)))
    (is (= 409 (error-status #(command! inbox/receive! id [] (assoc body :data {:status "changed"})))))
    (command! inbox/receive! id [] (assoc body :event_id "event-1" :source_revision 1 :data {:status "draft"}))
    (let [workspace (integration/workspace *svc* (actor 9501) id)]
      (is (= 2 (count (:inbox workspace))))
      (is (= 2 (:source_revision (first (:facts workspace)))))
      (is (= 1 (get-in workspace [:reconciliation :ignored])))
      (is (every? #(not (contains? % :payload_json)) (:inbox workspace))))
    (is (= "draft" (:status (pms/project *svc* (actor 9501) id))))
    (is (= 400 (error-status #(command! inbox/receive! id [] (assoc body :source "unknown")))))))

(deftest outbox-freezes-content-and-uses-source-permissions
  (let [id (project!) doc (document! id) message (enqueue! id doc "doc-1")
        duplicate (enqueue! id doc "doc-1")]
    (is (= (:id message) (:id duplicate)))
    (command! gov/command! id [:documents :revisions (:id doc)]
              {:code (:code doc) :title "新版本" :filename "new.txt" :content "新内容"})
    (is (= "  原始证据\n" (get-in (outbox/detail *svc* (actor 9501) id (:id message)) [:payload :data :content])))
    (is (= 503 (error-status #(command! dispatch/deliver! id [(:id message)] {}))))
    (is (= "queued" (:status (first (:outbox (integration/workspace *svc* (actor 9501) id))))))
    (let [other (project!) foreign (document! other)]
      (is (= 404 (error-status #(enqueue! id foreign "wrong-scope")))))
    (is (= 403 (error-status #(outbox/enqueue! *svc* (update (actor 9501) :permissions disj "pms:finance:query") id
                              {:version (version id) :target "erp" :topic "cost.approved" :source_id "fake" :idempotency_key "money"}))))))

(deftest actual-http-requires-matching-business-receipt
  (let [id (project!) message (enqueue! id (document! id) "network")]
    (with-endpoint (fn [body] [200 {:accepted true :message_id (:message_id body) :receipt_id "ACK-1"}])
      (fn [seen]
        (let [result (:result (command! dispatch/deliver! id [(:id message)] {}))
              workspace (integration/workspace *svc* (actor 9501) id)]
          (is (= "delivered" (:status result)))
          (is (= (:id message) (:key (first @seen))))
          (is (= 1 (count @seen)))
          (is (= "ACK-1" (:receipt_id (first (:attempts workspace)))))
          (is (= 0 (get-in workspace [:reconciliation :unconfirmed])))
          (is (= 409 (error-status #(command! dispatch/deliver! id [(:id message)] {})))))))))

(deftest mismatched-receipt-backoff-dead-letter-and-replay
  (let [id (project!) message (enqueue! id (document! id) "faults")]
    (with-endpoint (fn [_] [200 {:accepted true :message_id "wrong" :receipt_id "ACK-WRONG"}])
      (fn [seen]
        (command! dispatch/deliver! id [(:id message)] {})
        (is (= 409 (error-status #(command! dispatch/deliver! id [(:id message)] {}))))
        (dotimes [_ 4]
          (jdbc/execute! (:db *svc*) ["UPDATE pms_outbox SET next_retry_at=0 WHERE message_id=?" (:id message)])
          (command! dispatch/deliver! id [(:id message)] {}))
        (let [workspace (integration/workspace *svc* (actor 9501) id)]
          (is (= "dead_letter" (:status (first (:outbox workspace)))))
          (is (= 5 (count (:attempts workspace))))
          (is (= #{"receipt_mismatch"} (set (map :error_code (:attempts workspace)))))
          (is (= 5 (count @seen)))
          (is (= #{(:id message)} (set (map :key @seen)))))
        (command! dispatch/retry! id [(:id message)] {:reason "修正接收方后重试"})
        (is (= "queued" (:status (first (:outbox (integration/workspace *svc* (actor 9501) id))))))))))

(deftest inbox-audit-failure-rolls-back-source-projection
  (let [id (project!) before (version id) query (:query-fn *svc*)
        failing (assoc *svc* :query-fn (fn ([k p] (query k p))
                                       ([tx k p] (when (= k :pms/insert-event!) (throw (ex-info "audit offline" {})))
                                        (query tx k p))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"audit offline"
          (inbox/receive! failing (actor 9501) id {:version before :source "crm" :event_id "rollback"
             :entity_type "material" :external_key "M-1" :source_revision 1 :data {:qty 4}})))
    (is (= before (version id)))
    (is (empty? (:facts (integration/workspace *svc* (actor 9501) id))))))

(deftest response-body-has-bounded-size-and-completion-time
  (let [id (project!) message (enqueue! id (document! id) "bounded")]
    (with-endpoint (fn [body] [200 {:accepted true :message_id (:message_id body) :receipt_id "ACK"
                                    :padding (apply str (repeat 70000 "x"))}])
      (fn [_]
        (is (= "retry_wait" (get-in (command! dispatch/deliver! id [(:id message)] {}) [:result :status]))))))
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/stall"
      (reify HttpHandler
        (handle [_ exchange]
          (.sendResponseHeaders exchange 200 0)
          (with-open [stream (.getResponseBody exchange)]
            (.write stream (byte-array [123]))
            (.flush stream)
            (Thread/sleep 1500)))))
    (.start server)
    (try
      (let [start (System/nanoTime)
            config {:uri (java.net.URI. (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/stall"))}
            result (binding [transport/*response-timeout-ms* 200]
                     (transport/send! config {:message_id "timeout" :payload_json "{}"}))
            elapsed (/ (- (System/nanoTime) start) 1000000)]
        (is (false? (:accepted? result)))
        (is (< elapsed 1200)))
      (finally (.stop server 0)))))
