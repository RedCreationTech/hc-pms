(ns com.ruoyi.infra.db
  "数据库抽象层 — 支持 SQLite 和 MySQL。"
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; ─── 数据库类型检测 ──────────────────────────────────────────────────────

(defn detect-db-type
  "检测数据库类型。"
  [db]
  (let [meta (try
               (.getMetaData (:connectable db))
               (catch Exception _ nil))]
    (if meta
      (let [product-name (.getDatabaseProductName meta)]
        (cond
          (str/includes? (str/lower-case product-name) "sqlite") :sqlite
          (str/includes? (str/lower-case product-name) "mysql") :mysql
          :else :unknown))
      :unknown)))

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
    (jdbc/execute! db
                   (into [paginated-sql] (vals params))
                   {:builder-fn rs/as-unqualified-kebab-maps})))

;; ─── 表结构查询 ──────────────────────────────────────────────────────

(defn get-table-columns
  "获取表的列信息。"
  [db table-name]
  (let [db-type (detect-db-type db)]
    (case db-type
      :sqlite
      (jdbc/execute! db
                     [(str "PRAGMA table_info(" table-name ")")]
                     {:builder-fn rs/as-unqualified-kebab-maps})
      :mysql
      (jdbc/execute! db
                     [(str "DESCRIBE " table-name)]
                     {:builder-fn rs/as-unqualified-kebab-maps})
      [])))

(defn get-tables
  "获取数据库中的所有表。"
  [db]
  (let [db-type (detect-db-type db)]
    (case db-type
      :sqlite
      (jdbc/execute! db
                     ["SELECT name as table_name, COALESCE(name, '') as table_comment FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"]
                     {:builder-fn rs/as-unqualified-kebab-maps})
      :mysql
      (jdbc/execute! db
                     ["SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name"]
                     {:builder-fn rs/as-unqualified-kebab-maps})
      [])))
