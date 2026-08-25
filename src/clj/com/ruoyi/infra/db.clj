(ns com.ruoyi.infra.db
  "数据库抽象层 — 支持 SQLite 和 MySQL。"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [com.ruoyi.infra.datasource :as ds]
            [migratus.core]))

;; ─── 数据库类型检测 ──────────────────────────────────────────────────────

(defn detect-db-type
  "检测数据库类型。支持 DataSource、Connection 以及 next.jdbc 包装对象。"
  [db]
  (let [connable (or (:connectable db) db)]
    (try
      (let [product-name (try
                           (.getDatabaseProductName (.getMetaData connable))
                           (catch Exception _
                             (with-open [conn (jdbc/get-connection connable)]
                               (.getDatabaseProductName (.getMetaData conn)))))]
        (cond
          (str/includes? (str/lower-case product-name) "sqlite") :sqlite
          (str/includes? (str/lower-case product-name) "mysql") :mysql
          :else :unknown))
      (catch Exception _ :unknown))))

(defn- connectable
  "提取可用于 JDBC 执行的数据源或连接。"
  [db]
  (or (:connectable db) db))

(defn last-insert-id
  "获取最近一次插入的自增 ID，自动适配 SQLite/MySQL。
   默认使用 :last-insert-rowid（SQLite）或 :last-insert-rowid-mysql（MySQL）查询。
   可通过 result-key 指定返回字段名，例如 :job_id。
   注意：MySQL 下请传入与插入同一事务的连接，否则可能获取不到 ID。"
  ([query-fn db]
   (last-insert-id query-fn db :last-insert-rowid :last_insert_rowid))
  ([query-fn db query-name result-key]
   (let [db-type (detect-db-type db)
         q (if (= :mysql db-type)
             (keyword (str (name query-name) "-mysql"))
             query-name)]
     (get (query-fn db q {}) result-key))))

(defn insert-and-get-id!
  "在同一事务中执行插入并返回自增 ID，自动适配 SQLite/MySQL。"
  ([query-fn db insert-query params]
   (insert-and-get-id! query-fn db insert-query params :last-insert-rowid :last_insert_rowid))
  ([query-fn db insert-query params id-query id-key]
   (let [db-type (detect-db-type db)
         id-q (if (= :mysql db-type)
                (keyword (str (name id-query) "-mysql"))
                id-query)]
     (if (some? db)
       (jdbc/with-transaction [tx db]
         (query-fn tx insert-query params)
         (get (query-fn tx id-q {}) id-key))
       (do
         (query-fn insert-query params)
         (get (query-fn id-q {}) id-key))))))

;; ─── SQL 方言转换 ──────────────────────────────────────────────────────

(defn sqlite->mysql
  "将 SQLite SQL 转换为 MySQL 兼容 SQL。"
  [sql]
  (-> sql
      ;; SQLite 的 AUTOINCREMENT -> MySQL 的 AUTO_INCREMENT
      (str/replace #"AUTOINCREMENT" "AUTO_INCREMENT")
      ;; SQLite 的 INTEGER PRIMARY KEY -> MySQL 的 BIGINT PRIMARY KEY AUTO_INCREMENT
      (str/replace #"INTEGER PRIMARY KEY" "BIGINT PRIMARY KEY AUTO_INCREMENT")
      ;; SQLite 的 datetime('now') -> MySQL 的 NOW()
      (str/replace #"datetime\('now'\)" "NOW()")
      ;; SQLite 的 date('now') -> MySQL 的 CURDATE()
      (str/replace #"date\('now'\)" "CURDATE()")
      ;; SQLite 的 julianday -> MySQL 的 DATEDIFF
      (str/replace #"julianday\(([^)]+)\)\s*-\s*julianday\(([^)]+)\)" "DATEDIFF($1, $2)")
      ;; SQLite 的 GROUP_CONCAT -> MySQL 的 GROUP_CONCAT
      (str/replace #"group_concat" "GROUP_CONCAT")
      ;; SQLite 的 PRAGMA -> MySQL 的 SHOW
      (str/replace #"PRAGMA table_info\(([^)]+)\)" "DESCRIBE $1")
      ;; SQLite 的 sqlite_master -> MySQL 的 information_schema
      (str/replace #"sqlite_master" "information_schema.tables")
      ;; SQLite 的 type='table' -> MySQL 的 table_type='BASE TABLE'
      (str/replace #"type='table'" "table_type='BASE TABLE'")))

(defn mysql->sqlite
  "将 MySQL SQL 转换为 SQLite 兼容 SQL。"
  [sql]
  (-> sql
      ;; MySQL 的 AUTO_INCREMENT -> SQLite 的 AUTOINCREMENT
      (str/replace #"AUTO_INCREMENT" "AUTOINCREMENT")
      ;; MySQL 的 BIGINT PRIMARY KEY AUTO_INCREMENT -> SQLite 的 INTEGER PRIMARY KEY
      (str/replace #"BIGINT PRIMARY KEY AUTO_INCREMENT" "INTEGER PRIMARY KEY")
      ;; MySQL 的 NOW() -> SQLite 的 datetime('now')
      (str/replace #"NOW\(\)" "datetime('now')")
      ;; MySQL 的 CURDATE() -> SQLite 的 date('now')
      (str/replace #"CURDATE\(\)" "date('now')")
      ;; MySQL 的 DATEDIFF -> SQLite 的 julianday
      (str/replace #"DATEDIFF\(([^,]+),\s*([^)]+)\)" "julianday($1) - julianday($2)")
      ;; MySQL 的 DESCRIBE -> SQLite 的 PRAGMA table_info
      (str/replace #"DESCRIBE\s+(\w+)" "PRAGMA table_info($1)")))

;; ─── 数据库兼容层 ──────────────────────────────────────────────────────

(defn adapt-sql
  "根据数据库类型适配 SQL。"
  [db sql]
  (let [db-type (detect-db-type db)]
    (case db-type
      :mysql (sqlite->mysql sql)
      :sqlite sql
      sql)))

;; ─── 分页查询 ──────────────────────────────────────────────────────

(defn paginate-query
  "分页查询适配。"
  [db sql params page-num page-size]
  (let [db-type (detect-db-type db)
        offset (* (dec page-num) page-size)
        paginated-sql (case db-type
                        :mysql (str sql " LIMIT " page-size " OFFSET " offset)
                        :sqlite (str sql " LIMIT " page-size " OFFSET " offset)
                        (str sql " LIMIT " page-size " OFFSET " offset))]
    (jdbc/execute! (connectable db)
                   (into [paginated-sql] (vals params))
                   {:builder-fn rs/as-unqualified-kebab-maps})))

;; ─── 表结构查询 ──────────────────────────────────────────────────────

(defn get-table-columns
  "获取表的列信息，返回统一字段：
   :column_name :data_type :is_nullable :column_default :column_comment
   :character_maximum_length :numeric_precision :numeric_scale :is_pk"
  [db table-name]
  (let [db-type (detect-db-type db)]
    (case db-type
      :sqlite
      (mapv (fn [{:keys [name type notnull dflt_value pk]}]
              {:column_name name
               :data_type type
               :is_nullable (if (= 1 notnull) "NO" "YES")
               :column_default dflt_value
               :dflt_value dflt_value
               :column_comment ""
               :character_maximum_length nil
               :numeric_precision nil
               :numeric_scale nil
               :is_pk (if (= 1 pk) "YES" "NO")
               :pk pk})
            (jdbc/execute! (connectable db)
                           [(str "PRAGMA table_info(" table-name ")")]
                           {:builder-fn rs/as-unqualified-lower-maps}))
      :mysql
      (jdbc/execute! (connectable db)
                     [(str "SELECT c.column_name, c.data_type, c.is_nullable, "
                           "c.column_default, c.column_comment, "
                           "c.character_maximum_length, c.numeric_precision, c.numeric_scale, "
                           "CASE WHEN tc.constraint_type = 'PRIMARY KEY' THEN 'YES' ELSE 'NO' END AS is_pk "
                           "FROM information_schema.columns c "
                           "LEFT JOIN information_schema.key_column_usage kcu "
                           "  ON c.table_schema = kcu.table_schema "
                           "  AND c.table_name = kcu.table_name "
                           "  AND c.column_name = kcu.column_name "
                           "LEFT JOIN information_schema.table_constraints tc "
                           "  ON kcu.constraint_schema = tc.constraint_schema "
                           "  AND kcu.constraint_name = tc.constraint_name "
                           "  AND tc.constraint_type = 'PRIMARY KEY' "
                           "WHERE c.table_schema = DATABASE() AND c.table_name = ? "
                           "ORDER BY c.ordinal_position")
                      table-name]
                     {:builder-fn rs/as-unqualified-lower-maps})
      [])))

(defn get-tables
  "获取数据库中的所有表。"
  [db]
  (let [db-type (detect-db-type db)]
    (case db-type
      :sqlite
      (jdbc/execute! (connectable db)
                     ["SELECT name as table_name, COALESCE(name, '') as table_comment FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"]
                     {:builder-fn rs/as-unqualified-lower-maps})
      :mysql
      (jdbc/execute! (connectable db)
                     ["SELECT table_name, IFNULL(table_comment, '') AS table_comment FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name"]
                     {:builder-fn rs/as-unqualified-lower-maps})
      [])))

;; ─── 运行时数据库热切换 ──────────────────────────────────────────────

(defn make-hikari-datasource
  "根据 JDBC URL 创建 HikariCP 连接池。"
  [jdbc-url & [{:keys [pool-size]}]]
  (let [pool-size (or pool-size 5)
        hc (doto (com.zaxxer.hikari.HikariConfig.)
             (.setJdbcUrl jdbc-url)
             (.setMaximumPoolSize
               (int (if (.contains jdbc-url "sqlite") 1 pool-size)))
             (.setMinimumIdle
               (int (if (.contains jdbc-url "sqlite") 1 1)))
             (.setConnectionTestQuery "SELECT 1")
             (.setValidationTimeout 3000))]
    (com.zaxxer.hikari.HikariDataSource. hc)))

(defn run-migrations!
  "对指定 DataSource 执行数据库迁移。"
  [datasource migration-dir]
  (let [config {:store :database
                :db {:datasource datasource}
                :migrate-on-init? false
                :migration-dir migration-dir}]
    (migratus.core/migrate config)))

(defn swap-db!
  "热切换数据库连接池。无需重启 JVM。用法: (swap-db! system jdbc-url opts)"
  [system jdbc-url & [{:keys [migration-dir pool-size]}]]
  (let [conn (:db.sql/connection system)]
    (when-not (com.ruoyi.infra.datasource/swappable? conn)
      (throw (ex-info "db.sql/connection 不是可热切换的 DataSource，请重启 Integrant 系统。"
                      {:type (type conn)})))
    (log/info "[swap-db!] 创建新连接池:" jdbc-url)
    (let [new-ds (make-hikari-datasource jdbc-url {:pool-size pool-size})
          migration-dir (or migration-dir
                           (if (.contains jdbc-url "mysql") "migrations" "migrations-sqlite"))]
      ;; 运行迁移
      (log/info "[swap-db!] 运行迁移 (" migration-dir ")...")
      (run-migrations! new-ds migration-dir)
      ;; 替换底层 DataSource
      (let [old-ds (ds/swap-delegate! conn new-ds)]
        (log/info "[swap-db!] 连接池已替换，关闭旧连接池...")
        (try (.close old-ds)
             (catch Exception e
               (log/warn "关闭旧连接池时出错:" (.getMessage e)))))
      ;; 重新绑定 query-fn
      (log/info "[swap-db!] 重新加载 query-fn...")
      (let [set-dynamic! (resolve 'com.ruoyi.integrant.trace/set-dynamic!)
            load-queries (fn []
                           (require 'conman.core)
                           (let [bind-fn (resolve 'conman.core/bind-connection-map)]
                             (bind-fn conn {}
                                      "queries.sql" "sql/system.sql" "sql/log.sql"
                                      "sql/job.sql" "sql/gen.sql" "sql/generated.sql" "sql/business.sql")))
            new-qf (fn
                     ([query params]
                      (let [f (get (:fns (load-queries)) query)]
                        (when-not f
                          (throw (ex-info (str "Query not found: " query) {:query query})))
                        ((:fn f) params)))
                     ([conn query params & opts]
                      (let [f (get (:fns (load-queries)) query)]
                        (when-not f
                          (throw (ex-info (str "Query not found: " query) {:query query})))
                        (apply (:fn f) conn params opts))))]
        (set-dynamic! :db.sql/query-fn new-qf))
      (let [db-type (detect-db-type conn)]
        (log/info "[swap-db!] 完成! 当前数据库类型:" db-type)
        {:db-type db-type :jdbc-url jdbc-url :migration-dir migration-dir}))))
