(ns com.ruoyi.domain.pms.readiness
  "H19 运行就绪核对: 把「服务其实跑在什么状态下」如实摊开, 不代替任何上线签收.
   只读派生: 一次真实连接探测 + 迁移版本对账 + 关键配置项在位判定, 不落库, 不门控."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.platform :as platform]
    [com.ruoyi.domain.pms.rules :as r]
    [com.ruoyi.infra.db]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc])
  (:import
    (java.time
      Instant)))


(def max-latency-ms
  "连接探测耗时超过该值即如实标为偏慢, 提示排查而不阻断启动."
  1000)


(def required-config
  "上线前必须在位的关键配置项: [环境变量名 说明]. 缺失如实标为 missing, 不猜默认值."
  [["JDBC_URL" "业务库连接串"]
   ["JAVA_HOME" "JDK 安装目录"]])


(defn- now-ms
  []
  (System/currentTimeMillis))


(defn- probe
  "对当前数据源做一次真实往返探测: 连得上才继续量耗时, 连不上如实记录异常类型而不抛."
  [db]
  (let [start (now-ms)]
    (try
      (with-open [conn (jdbc/get-connection db)]
        (jdbc/execute-one! conn ["SELECT 1"])
        (let [latency (- (now-ms) start)]
          {:ok true :latency-ms latency :slow (> latency max-latency-ms)}))
      (catch Exception e
        {:ok false :latency-ms (- (now-ms) start)
         :error (str (.getSimpleName (class e)) ": " (.getMessage e))}))))


(defn migration-config
  "迁移配置: 与启动时同源的 :database store 与迁移目录."
  [db migration-dir]
  {:store :database :db {:datasource db} :migration-dir migration-dir})


(def migration-check-fns
  "迁移对账所需的随包迁移清单读取函数, 可在测试中注入以模拟读不到清单的环境.
   已应用集合直接读 schema_migrations (migratus 的 completed-list 返回的是名称而非版本 id, 不能直接求差)."
  {:all migratus/all-migrations})


(defn- pending-migrations
  "随包迁移清单与已应用集合求差; 清单读不到时返回 nil, 不下结论."
  [db migration-dir check]
  (try
    (let [cfg (migration-config db migration-dir)
          definitions ((:all check) cfg)
          ids (set (map :id definitions))
          applied-rows (jdbc/execute! db ["SELECT id FROM schema_migrations"])
          applied (set (filter ids (map (fn [row] (or (get row :schema_migrations/id) (get row :id) (val (first row))))
                                        applied-rows)))]
      {:known (count ids) :applied (count applied)
       :pending (vec (sort (remove applied ids)))})
    (catch Exception _
      nil)))


(defn database-kind
  "按真实 JDBC 元数据取已支持数据库条目, 用来决定迁移目录; 未识别时返回 nil."
  [db]
  (let [kind (name (com.ruoyi.infra.db/detect-db-type db))]
    (first (filter #(= kind (:kind %)) platform/supported-databases))))


(defn- config-presence
  "逐项核对关键配置是否在位; 只报告在不在, 绝不回显取值本身."
  [env]
  (mapv (fn [[k purpose]]
          {:key k :purpose purpose
           :present (boolean (seq (str/trim (str (get env k "")))))
           :value_exposed false})
        required-config))


(defn readiness
  "H19 运行就绪核对: 数据源探测 (连通/耗时/是否偏慢), 迁移版本对账 (已应用/待应用), 关键配置在位情况,
   以及无副作用的复核标记. 配置项只报在位与否, 不回显取值. 任一硬失败 (连不上或存在待应用迁移)
   时 ready 为 false, 但本函数本身永不抛错, 供运维在故障态下也能读到实况."
  [svc actor env]
  (r/permit! actor "pms:project:list")
  (let [db (:db svc)
        probe-result (probe db)
        databases platform/supported-databases
        kind (database-kind db)
        migrations (when kind (pending-migrations db (:migration_dir kind) migration-check-fns))
        env (or env {})
        config (config-presence env)
        missing-config (mapv :key (filter #(not (:present %)) config))
        pending-count (if migrations (count (:pending migrations)) 0)
        ready (boolean (and (:ok probe-result) (zero? pending-count)))]
    {:ready ready
     :database {:connected (:ok probe-result) :latency-ms (:latency-ms probe-result)
                :slow (boolean (:slow probe-result)) :threshold-ms max-latency-ms
                :error (:error probe-result)}
     :migrations (or migrations {:known 0 :applied 0 :pending [] :unreadable true})
     :pending-count pending-count
     :config config
     :missing-config missing-config
     :config-complete (empty? missing-config)
     :supports (mapv :kind databases)
     :read-only true
     :side-effects []
     :checked-at (str (Instant/now))}))
