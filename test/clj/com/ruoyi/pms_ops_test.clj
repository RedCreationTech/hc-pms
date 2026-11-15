(ns com.ruoyi.pms-ops-test
  "企业接口基础协议的真实网络,隔离数据库和失败恢复验证."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.planning :as plan]
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
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9600,'Ops test','ops-test',40,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9600,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
      (doseq [id [9601 9602 9603]]
        (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(?,1,?,?,'0','0')" id (str "fin-" id) (str "财务测试" id)])
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9600)" id]))
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
(defn- error-info "提取业务错误状态与文案, 成功时返回 nil." [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))

(defn- command!
  "以指定成员携带真实版本执行业务命令."
  ([f id args body] (command! 9601 f id args body))
  ([uid f id args body]
   (apply f *svc* (actor uid) id (concat args [(assoc body :version (version id))]))))

(defn- project!
  "创建项目并设置审批人和普通成员,不直接改生命周期状态."
  []
  (let [p (pms/create-project! *svc* (actor 9601)
             {:project_no (str "FIN-" (kernel/id)) :name "生命周期验收"
              :manager_id 9601 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id p)]
    (pms/set-member! *svc* (actor 9601) id {:user_id 9602 :role "viewer"})
    (pms/set-member! *svc* (actor 9601) id {:user_id 9603 :role "editor"})
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
    (let [workspace (integration/workspace *svc* (actor 9601) id)]
      (is (= 2 (count (:inbox workspace))))
      (is (= 2 (:source_revision (first (:facts workspace)))))
      (is (= 1 (get-in workspace [:reconciliation :ignored])))
      (is (every? #(not (contains? % :payload_json)) (:inbox workspace))))
    (is (= "draft" (:status (pms/project *svc* (actor 9601) id))))
    (is (= 400 (error-status #(command! inbox/receive! id [] (assoc body :source "unknown")))))))

(deftest outbox-freezes-content-and-uses-source-permissions
  (let [id (project!) doc (document! id) message (enqueue! id doc "doc-1")
        duplicate (enqueue! id doc "doc-1")]
    (is (= (:id message) (:id duplicate)))
    (command! gov/command! id [:documents :revisions (:id doc)]
              {:code (:code doc) :title "新版本" :filename "new.txt" :content "新内容"})
    (is (= "  原始证据\n" (get-in (outbox/detail *svc* (actor 9601) id (:id message)) [:payload :data :content])))
    (is (= 503 (error-status #(command! dispatch/deliver! id [(:id message)] {}))))
    (is (= "queued" (:status (first (:outbox (integration/workspace *svc* (actor 9601) id))))))
    (let [other (project!) foreign (document! other)]
      (is (= 404 (error-status #(enqueue! id foreign "wrong-scope")))))
    (is (= 403 (error-status #(outbox/enqueue! *svc* (update (actor 9601) :permissions disj "pms:finance:query") id
                              {:version (version id) :target "erp" :topic "cost.approved" :source_id "fake" :idempotency_key "money"}))))))

;; batch59 (mutator 方向: cover the uncovered): outbox 计划/财务主题载荷与幂等键/读取守卫 —
;; plan.published 批准基线正文 (L22-25) 与未批准409, cost.approved 批准版本正文 (L27-29)
;; 与未批准409, 未知主题400 (L31), 幂等键换对象409 (L44), 消息不存在404 (L61).
(deftest outbox-plan-cost-topics-and-guards
  (let [id (project!)]
    (command! pms/transition-project! id [] {:status "initiated" :reason "立项"})
    (command! pms/transition-project! id [] {:status "planning" :reason "计划"})
    (testing "计划主题: 未批准基线409, 批准基线外发正文来自快照"
      (command! plan/create-task! id [] {:wbs_code "1" :name "设计" :duration_days 1 :owner_id 9601})
      (let [baseline (:result (command! plan/submit-plan! id [] {:comment "发布基线"}))
            base-id (:baseline_id baseline)]
        (is (= [409 "只允许外发同项目已批准基线"]
               (error-info #(command! outbox/enqueue! id [] {:target "erp" :topic "plan.published"
                                                          :source_id base-id :idempotency_key "plan-1"}))))
        (command! 9602 plan/review-plan! id [base-id] {:decision "approved" :comment "已核验"})
        (let [msg (:result (command! outbox/enqueue! id [] {:target "erp" :topic "plan.published"
                                                        :source_id base-id :idempotency_key "plan-1"}))
              payload (:payload (outbox/detail *svc* (actor 9601) id (:id msg)))]
          (is (= "plan.published" (:topic msg)))
          (is (= base-id (get-in payload [:data :baseline_id])))
          (is (map? (get-in payload [:data :snapshot]))))))
    (testing "财务主题: 未批准版本409, 批准版本外发正文含总额"
      (let [v (:result (command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "CNY"
                                                    :name "九月核算" :revenue "0" :reviewer_id 9602}))]
        (command! cost/add-entry! id [(:id v)] {:category "material" :label "原料" :amount "10"})
        (is (= [409 "只允许外发已批准财务版本"]
               (error-info #(command! outbox/enqueue! id [] {:target "erp" :topic "cost.approved"
                                                          :source_id (:id v) :idempotency_key "cost-1"}))))
        (command! cost/submit! id [(:id v)] {})
        (command! 9602 cost/review! id [(:id v)] {:decision "approved" :reason "核对完成"})
        (let [msg (:result (command! outbox/enqueue! id [] {:target "erp" :topic "cost.approved"
                                                        :source_id (:id v) :idempotency_key "cost-1"}))]
          (is (= "cost.approved" (:topic msg)))
          (is (= "10.00" (get-in (outbox/detail *svc* (actor 9601) id (:id msg)) [:payload :data :total]))))))
    (testing "未知主题400, 幂等键换对象409, 消息不存在404"
      (is (= [400 "外发主题必须为已批准计划,财务版本或真实文档"]
             (error-info #(command! outbox/enqueue! id [] {:target "erp" :topic "hr.created"
                                                        :source_id "x" :idempotency_key "weird"}))))
      (let [d1 (document! id) d2 (document! id)]
        (command! outbox/enqueue! id [] {:target "crm" :topic "document.registered"
                                       :source_id (:id d1) :idempotency_key "shared-key"})
        (is (= [409 "外发幂等键已用于不同对象"]
               (error-info #(command! outbox/enqueue! id [] {:target "crm" :topic "document.registered"
                                                          :source_id (:id d2) :idempotency_key "shared-key"})))))
      (is (= [404 "消息不存在"] (error-info #(outbox/detail *svc* (actor 9601) id "no-such-message")))))))

(deftest actual-http-requires-matching-business-receipt
  (let [id (project!) message (enqueue! id (document! id) "network")]
    (with-endpoint (fn [body] [200 {:accepted true :message_id (:message_id body) :receipt_id "ACK-1"}])
      (fn [seen]
        (let [result (:result (command! dispatch/deliver! id [(:id message)] {}))
              workspace (integration/workspace *svc* (actor 9601) id)]
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
        (let [workspace (integration/workspace *svc* (actor 9601) id)]
          (is (= "dead_letter" (:status (first (:outbox workspace)))))
          (is (= 5 (count (:attempts workspace))))
          (is (= #{"receipt_mismatch"} (set (map :error_code (:attempts workspace)))))
          (is (= 5 (count @seen)))
          (is (= #{(:id message)} (set (map :key @seen)))))
        (command! dispatch/retry! id [(:id message)] {:reason "修正接收方后重试"})
        (is (= "queued" (:status (first (:outbox (integration/workspace *svc* (actor 9601) id))))))))))

(deftest inbox-audit-failure-rolls-back-source-projection
  (let [id (project!) before (version id) query (:query-fn *svc*)
        failing (assoc *svc* :query-fn (fn ([k p] (query k p))
                                       ([tx k p] (when (= k :pms/insert-event!) (throw (ex-info "audit offline" {})))
                                        (query tx k p))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"audit offline"
          (inbox/receive! failing (actor 9601) id {:version before :source "crm" :event_id "rollback"
             :entity_type "material" :external_key "M-1" :source_revision 1 :data {:qty 4}})))
    (is (= before (version id)))
    (is (empty? (:facts (integration/workspace *svc* (actor 9601) id))))))

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

;; batch64 (mutator 方向: cover the uncovered): 收件箱跨事件同版本异内容409 (L26)
;; 与发送租约/重试资格/回执版本冲突重试环守卫 (L84-85/L87/L58-61).
(deftest inbox-cross-event-same-revision-conflict
  (let [id (project!)
        base {:source "erp" :entity_type "order" :external_key "ORD-9"
              :source_revision 2 :data {:status "released"}}]
    (command! inbox/receive! id [] (assoc base :event_id "evt-A"))
    (testing "同一来源版本换事件携带不同内容409 (事实投影不倒退)"
      (is (= [409 "同一来源版本携带不同内容,须在源系统修正版本"]
             (error-info #(command! inbox/receive! id []
                                    (assoc base :event_id "evt-B" :data {:status "changed"}))))))
    (testing "不支持的来源事实类型400"
      (is (= [400 "不支持的来源事实类型"]
             (error-info #(command! inbox/receive! id []
                                    (assoc base :event_id "evt-D" :entity_type "bogus"))))))
    (testing "更高来源版本更新事实投影"
      (is (= "applied" (:status (:result (command! inbox/receive! id []
                                                    (assoc base :event_id "evt-C"
                                                           :source_revision 3
                                                           :data {:status "shipped"})))))))))


(deftest dispatch-retry-lease-and-version-conflict-guards
  (testing "不存在的外发消息404"
    (let [id (project!)]
      (is (= [404 "外发消息不存在"]
             (error-info #(command! dispatch/deliver! id ["no-such-message"] {}))))))
  (testing "排队中的消息不能人工重试409"
    (let [id (project!) message (enqueue! id (document! id) "retry-queued")]
      (is (= [409 "仅失败,死信或过期发送可以重试"]
             (error-info #(command! dispatch/retry! id [(:id message)] {:reason "尚未失败"}))))))
  (testing "发送租约仍有效409, 过期后可恢复为排队"
    (let [id (project!) message (enqueue! id (document! id) "retry-lease")]
      (jdbc/execute! (:db *svc*)
                     ["UPDATE pms_outbox SET status='sending',lease_id='L-1',lease_started_at=? WHERE message_id=?"
                      (System/currentTimeMillis) (:id message)])
      (is (= [409 "发送租约仍有效,请等待回执或租约到期"]
             (error-info #(command! dispatch/retry! id [(:id message)] {:reason "租约未到期"}))))
      (jdbc/execute! (:db *svc*)
                     ["UPDATE pms_outbox SET lease_started_at=? WHERE message_id=?"
                      (- (System/currentTimeMillis) 31000) (:id message)])
      (is (= "queued" (:status (:result (command! dispatch/retry! id [(:id message)]
                                                   {:reason "租约已过期"})))))))
  (testing "网络期间租约被抢占 -> finish! 版本冲突重试3次耗尽后抛出409"
    (let [id (project!) message (enqueue! id (document! id) "retry-conflict") db (:db *svc*)]
      (with-endpoint (fn [body]
                       (jdbc/execute! db ["UPDATE pms_outbox SET lease_id='stolen' WHERE message_id=?"
                                          (:id message)])
                       [200 {:accepted true :message_id (:message_id body) :receipt_id "ACK-C"}])
        (fn [_]
          (is (= 409 (error-status #(command! dispatch/deliver! id [(:id message)] {})))))))))

;; batch67 (mutator 方向: cover the uncovered): 已提交评审的文档版本不能外发 (governance L46).
(deftest outbox-rejects-unregistered-document-version
  (let [id (project!) doc (document! id)]
    (command! gov/command! id [:documents :submit (:id doc)] {:reviewer_id 9602})
    (is (= [409 "证据版本尚未登记"]
           (error-info #(command! outbox/enqueue! id [] {:target "crm" :topic "document.registered"
                                                      :source_id (:id doc) :idempotency_key "ev-unreg"}))))))
