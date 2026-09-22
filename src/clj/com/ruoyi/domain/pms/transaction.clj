(ns com.ruoyi.domain.pms.transaction
  "PMS写事务的跨数据库连接边界,避免SQLite读后写升级冲突."
  (:require [next.jdbc :as jdbc])
  (:import [java.sql Connection]
           [org.sqlite SQLiteConnection SQLiteConfig$TransactionMode]))

(defn- immediate!
  "SQLite在读取业务状态前取得写锁,最多等待5秒,结束后恢复池连接配置."
  [^Connection connection f]
  (let [^SQLiteConnection raw (.unwrap connection SQLiteConnection)
        config (.getConnectionConfig raw)
        mode (.getTransactionMode config)
        timeout (.getBusyTimeout raw)
        current-mode (.getCurrentTransactionMode raw)
        started? (volatile! false)]
    (try
      (.setTransactionMode config SQLiteConfig$TransactionMode/IMMEDIATE)
      (.setBusyTimeout raw 5000)
      (jdbc/transact connection (fn [tx] (vreset! started? true) (f tx)))
      (catch Exception error
        ;; 驱动在BEGIN失败前已修改Java标记,此时并无可回滚的数据库事务.
        (when-not @started?
          (.setAutoCommit config true)
          (.setCurrentTransactionMode raw current-mode))
        (throw error))
      (finally
        (.setTransactionMode config mode)
        (.setBusyTimeout raw timeout)))))

(defn execute!
  "执行一次写事务,仅为新SQLite事务配置立即写锁,MySQL和已有事务沿用JDBC语义."
  [db f]
  (jdbc/on-connection [connection db]
    (if (and (.getAutoCommit connection)
             (.isWrapperFor connection SQLiteConnection))
      (immediate! connection f)
      (jdbc/transact connection f))))
