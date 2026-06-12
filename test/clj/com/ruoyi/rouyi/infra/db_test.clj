(ns com.ruoyi.rouyi.infra.db-test
  "数据库抽象层测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.infra.db :as db]))

(deftest test-sqlite->mysql
  (testing "SQLite SQL 转换为 MySQL"
    (is (= "AUTO_INCREMENT" (db/sqlite->mysql "AUTOINCREMENT")))
    (is (= "NOW()" (db/sqlite->mysql "datetime('now')")))
    (is (= "CURDATE()" (db/sqlite->mysql "date('now')")))))

(deftest test-mysql->sqlite
  (testing "MySQL SQL 转换为 SQLite"
    (is (= "AUTOINCREMENT" (db/mysql->sqlite "AUTO_INCREMENT")))
    (is (= "datetime('now')" (db/mysql->sqlite "NOW()")))
    (is (= "date('now')" (db/mysql->sqlite "CURDATE()")))))

(deftest test-sql-conversion-roundtrip
  (testing "SQL 转换往返测试"
    (let [sqlite-sql "datetime('now')"
          mysql-sql (db/sqlite->mysql sqlite-sql)
          back-to-sqlite (db/mysql->sqlite mysql-sql)]
      (is (= sqlite-sql back-to-sqlite)))))
