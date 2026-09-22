(ns com.ruoyi.pms-concurrency-test
  "真实连接池并发写入,版本互斥及SQLite事务配置恢复验收."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.governance.gates :as gates]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.infra.datasource :as datasource]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [org.sqlite SQLiteConnection SQLiteConfig$TransactionMode]))

(def ^:dynamic *svc* nil)
(def ^:dynamic *sqlite?* false)

(defn- queries
  "加载业务和真实在线会话查询,支持显式连接."
  [db]
  (let [files (->> (.listFiles (io/file "resources/sql"))
                   (filter #(re-matches #"pms.*\.sql" (.getName %)))
                   (map #(str "sql/" (.getName %))) sort)
        functions (:fns (apply conman/bind-connection-map db {} "sql/log.sql" files))]
    (fn
      ([name params] ((get-in functions [name :fn]) params))
      ([tx name params] ((get-in functions [name :fn]) tx params)))))

(defn- fixture
  "使用四连接Hikari及应用同款代理,默认独立SQLite并支持CI的MySQL."
  [f]
  (let [url (System/getenv "PMS_TEST_JDBC_URL")
        file (when-not url (java.io.File/createTempFile "pms-concurrency-" ".db"))
        url (or url (str "jdbc:sqlite:" file))
        sqlite? (.startsWith url "jdbc:sqlite:")
        config (doto (HikariConfig.) (.setJdbcUrl url) (.setMaximumPoolSize 4)
                     (.setMinimumIdle 1) (.setConnectionTimeout 5000))]
    (try
      (with-open [pool (HikariDataSource. config)]
        (let [db (datasource/delegating-datasource pool)]
          (migratus/migrate {:store :database :db {:datasource db}
                             :migration-dir (if sqlite? "migrations-sqlite" "migrations")})
          (binding [*svc* {:db db :query-fn (queries db)} *sqlite?* sqlite?] (f))))
      (finally (when file (io/delete-file file true))))))

(use-fixtures :once fixture)

(defn- actor
  "读取种子管理员的真实身份,避免并发测试额外创建角色."
  []
  (pms/actor *svc* {:user-id 1}))

(defn- project-body
  "构造每次唯一的真实项目输入."
  []
  {:project_no (str "LOCK-" (kernel/id)) :name "并发验收"
   :manager_id 1 :dept_id 1 :start_date "2026-09-22"})

(defn- project!
  "由管理员实际创建项目及审计."
  []
  (pms/create-project! *svc* (actor) (project-body)))

(defn- result
  "捕获线程中的业务异常,保留状态和消息用于断言."
  [f]
  (try {:value (f)}
       (catch Exception e {:status (:status (ex-data e)) :message (.getMessage e)})))

(defn- await!
  "有界等待线程协作,超时作为测试失败."
  [x]
  (let [value (deref x 8000 ::timeout)]
    (when (= ::timeout value) (throw (ex-info "并发测试等待超时" {})))
    value))

(defn- traced
  "统计事务查询,记录事务回调首次开始的位置."
  [svc entered calls]
  (let [q (:query-fn svc)]
    (assoc svc :query-fn (fn
                          ([name params] (q name params))
                          ([tx name params]
                           (deliver entered true)
                           (swap! calls update name (fnil inc 0))
                           (q tx name params))))))

(defn- heartbeat-row!
  "建立真实在线会话行用于模拟鉴权心跳写入."
  []
  (let [id (kernel/id)]
    ((:query-fn *svc*) :create-online-user!
     {:session_id id :login_name "concurrency-test" :dept_name "" :ipaddr "127.0.0.1"
      :login_location "" :browser "" :os "" :status "on_line"
      :start_timestamp 1 :last_access_time 1 :expire_time 1800000})
    id))

(defn- with-held-heartbeat
  "另一个连接持有心跳写锁时启动业务,业务应在首次读取前等待."
  [f]
  (let [q (:query-fn *svc*) id (heartbeat-row!) locked (promise) release (promise)
        entered (promise) started (promise) calls (atom {})
        writer (future
                 (jdbc/with-transaction [tx (:db *svc*)]
                   (q tx :update-online-user! {:session_id id :last_access_time 2
                                               :status nil :expire_time nil})
                   (deliver locked true) (await! release)))]
    (try
      (await! locked)
      (let [worker (future (deliver started true)
                           (result #(f (traced *svc* entered calls))))]
        (await! started)
        (is (= ::blocked (deref entered 200 ::blocked))
            "已有写锁时不得先读取旧快照再升级写锁")
        (is (= ::waiting (deref worker 200 ::waiting))
            "无版本竞争的写命令应等待短暂心跳锁,而不是提前返回409")
        (deliver release true)
        (await! writer)
        {:outcome (await! worker) :calls @calls})
      (finally (deliver release true) (await! writer)
               (q :delete-online-user! {:session_id id})))))

(deftest project-creation-waits-for-heartbeat
  (when *sqlite?*
    (let [admin (actor) body (project-body)
          {:keys [outcome calls]} (with-held-heartbeat #(pms/create-project! % admin body))
          project (:value outcome)]
      (is (nil? (:status outcome)) (pr-str outcome))
      (is (string? (:project_id project)))
      (is (= 1 (:pms/insert-project! calls)))
      (is (= 1 (:pms/insert-node! calls)))
      (is (= 1 (:pms/insert-event! calls)))
      (is (= 1 (:version project))))))

(deftest gate-template-waits-for-heartbeat
  (when *sqlite?*
    (let [project (project!) admin (actor)
          body {:version 1 :code "GATE" :title "交付" :stage "execution" :required false :checks [{:code "READY" :title "就绪" :required false}]}
          {:keys [outcome calls]} (with-held-heartbeat
                                   #(gates/create-template! % admin (:project_id project) body))]
      (is (nil? (:status outcome)) (pr-str outcome))
      (is (= 2 (get-in outcome [:value :project_version])))
      (is (= 1 (:gov/insert! calls)))
      (is (= 1 (:pms/insert-event! calls)))
      (is (= [2 1] (mapv :aggregate_version
                        ((:query-fn *svc*) :pms/events {:project_id (:project_id project)})))))))

(deftest same-version-allows-exactly-one-commit
  (let [project (project!) admin (actor) id (:project_id project) start (promise)
        commands (mapv (fn [code]
                         (future (await! start)
                           (result #(gates/create-template! *svc* admin id
                                      {:version 1 :code code :title code :stage "execution"
                                       :required false :checks [{:code "READY" :title "就绪" :required false}]})))) ["A" "B"])]
    (deliver start true)
    (let [results (mapv await! commands)]
      (is (= 1 (count (filter :value results))))
      (is (= [409] (mapv :status (filter :status results))))
      (is (= 2 (:version ((:query-fn *svc*) :pms/project {:project_id id}))))
      (is (= 1 (count ((:query-fn *svc*) :gov/list {:project_id id :kind "gate-template"}))))
      (is (= [2 1] (mapv :aggregate_version ((:query-fn *svc*) :pms/events {:project_id id}))))
      (is (= 409 (:status (result #(pms/update-project! *svc* admin id {:version 1 :name "旧版本"}))))))))

(defn- connection-state
  "读取池包装底层连接的模式,等待预算及自动提交状态."
  [connection]
  (let [raw (.unwrap connection SQLiteConnection)]
    {:mode (.getTransactionMode (.getConnectionConfig raw))
     :timeout (.getBusyTimeout raw) :auto-commit (.getAutoCommit connection)}))

(deftest pooled-configuration-is-restored-and-callback-runs-once
  (when *sqlite?*
    (with-open [connection (jdbc/get-connection (:db *svc*))]
      (let [raw (.unwrap connection SQLiteConnection) before (connection-state connection)
            calls (atom 0) svc (assoc *svc* :db connection)]
        (is (= true (:auto-commit before)))
        (is (= :done (kernel/transaction! svc
                      (fn [_] (swap! calls inc)
                        (is (= SQLiteConfig$TransactionMode/IMMEDIATE (:mode (connection-state connection))))
                        (is (= false (:auto-commit (connection-state connection)))) :done))))
        (is (= 1 @calls))
        (is (= before (connection-state connection)))
        (is (= 409 (:status (result #(kernel/transaction! svc
                                      (fn [_] (swap! calls inc)
                                        (throw (ex-info "拒绝" {:status 409 :pms-error true}))))))))
        (is (= 2 @calls))
        (is (= before (connection-state connection)))
        (is (not (.isClosed connection)))))))

(deftest audit-failure-rolls-back-without-replaying-command
  (let [project (project!) admin (actor) id (:project_id project)
        q (:query-fn *svc*) inserted (atom 0)
        svc (assoc *svc* :query-fn
              (fn ([name params] (q name params))
                  ([tx name params]
                   (when (= name :gov/insert!) (swap! inserted inc))
                   (when (= name :pms/insert-event!) (throw (ex-info "审计不可用" {})))
                   (q tx name params))))]
    (is (= "审计不可用" (:message (result #(gates/create-template! svc admin id
                                               {:version 1 :code "ROLLBACK" :title "回滚"
                                                :stage "execution" :required false :checks [{:code "READY" :title "就绪" :required false}]})))))
    (is (= 1 @inserted))
    (is (= 1 (:version (q :pms/project {:project_id id}))))
    (is (empty? (q :gov/list {:project_id id :kind "gate-template"})))
    (is (= [1] (mapv :aggregate_version (q :pms/events {:project_id id}))))))

(deftest exhausted-write-lock-budget-keeps-pooled-connection-usable
  (when *sqlite?*
    (let [q (:query-fn *svc*) id (heartbeat-row!) locked (promise) release (promise)
          calls (atom 0)
          writer (future (jdbc/with-transaction [tx (:db *svc*)]
                           (q tx :update-online-user! {:session_id id :last_access_time 3
                                                       :status nil :expire_time nil})
                           (deliver locked true) (await! release)))]
      (try
        (await! locked)
        (with-open [connection (jdbc/get-connection (:db *svc*))]
          (let [before (connection-state connection) svc (assoc *svc* :db connection)
                failed (future (result #(kernel/transaction! svc
                                          (fn [_] (swap! calls inc) :unexpected))))]
            (is (= 409 (:status (await! failed))))
            (is (= 0 @calls) "未获得事务写锁时不得执行任何业务回调")
            (is (= before (connection-state connection)))
            (deliver release true) (await! writer)
            (is (= :recovered (kernel/transaction! svc (fn [_] (swap! calls inc) :recovered))))
            (is (= 1 @calls))
            (is (= before (connection-state connection)))))
        (finally (deliver release true) (await! writer)
                 (q :delete-online-user! {:session_id id}))))))
