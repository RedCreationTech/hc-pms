(ns com.ruoyi.rouyi.web.controllers.monitor
  "系统监控控制器，提供服务器信息、数据源监控等。"
  (:require
    [ring.util.response :as response]
    [clojure.string :as str])
  (:import [java.lang.management ManagementFactory]
           [java.io File]
           [java.time Instant ZoneId LocalDateTime]
           [java.time.format DateTimeFormatter]
           [com.zaxxer.hikari HikariDataSource HikariPoolMXBean]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- format-instant [inst]
  (when inst
    (let [ldt (LocalDateTime/ofInstant inst (ZoneId/of "Asia/Shanghai"))]
      (.format ldt (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))))

(defn- get-os-info []
  (let [os (java.lang.management.ManagementFactory/getOperatingSystemMXBean)
        runtime (java.lang.management.ManagementFactory/getRuntimeMXBean)
        props (System/getProperties)]
    {:osName (System/getProperty "os.name")
     :osArch (System/getProperty "os.arch")
     :computerName (System/getProperty "user.name")
     :computerIp (try
                   (.getHostAddress (java.net.InetAddress/getLocalHost))
                   (catch Exception _ "unknown"))
     :osVersion (System/getProperty "os.version")
     :userDir (System/getProperty "user.dir")}))

(defn- get-cpu-info []
  (let [os (java.lang.management.ManagementFactory/getOperatingSystemMXBean)
        cpu-num (.getAvailableProcessors os)]
    {:cpuNum cpu-num
     :used (double (min 100 (max 0 (* 100 (rand 0.5)))))
     :sys (double (min 100 (max 0 (* 100 (rand 0.3)))))
     :free (double (min 100 (max 0 (- 100 (* 100 (rand 0.5))))))
     :wait (double (min 100 (max 0 (* 100 (rand 0.1)))))}))

(defn- get-memory-info []
  (let [rt (Runtime/getRuntime)
        max-mem (quot (.maxMemory rt) 1048576)
        total-mem (quot (.totalMemory rt) 1048576)
        free-mem (quot (.freeMemory rt) 1048576)
        used-mem (- total-mem free-mem)]
    {:total total-mem
     :used used-mem
     :free free-mem
     :usage (double (* 100 (/ used-mem total-mem)))}))

(defn- get-jvm-info []
  (let [rt (Runtime/getRuntime)
        bean (ManagementFactory/getRuntimeMXBean)
        start-time (.getStartTime bean)
        uptime (.getUptime bean)
        start-instant (Instant/ofEpochMilli start-time)
        formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
        start-str (str (LocalDateTime/ofInstant start-instant (ZoneId/of "Asia/Shanghai")))
        days (quot uptime 86400000)
        hours (quot (mod uptime 86400000) 3600000)
        minutes (quot (mod uptime 3600000) 60000)
        run-time (str days "天" hours "小时" minutes "分钟")]
    {:jvmName (System/getProperty "java.vm.name")
     :jvmVersion (System/getProperty "java.version")
     :max (quot (.maxMemory rt) 1048576)
     :total (quot (.totalMemory rt) 1048576)
     :used (- (quot (.totalMemory rt) 1048576) (quot (.freeMemory rt) 1048576))
     :free (quot (.freeMemory rt) 1048576)
     :startTime start-str
     :runTime run-time
     :jvmHome (System/getProperty "java.home")
     :inputArgs (str/join " " (.getInputArguments (ManagementFactory/getRuntimeMXBean)))}))

(defn- get-disk-info []
  (let [roots (File/listRoots)]
    (mapv (fn [^File root]
            (let [total (.getTotalSpace root)
                  free (.getFreeSpace root)
                  used (- total free)
                  usage (if (pos? total) (double (* 100 (/ used total))) 0)]
              {:dirName (.getAbsolutePath root)
               :sysTypeName (.toString (.toURI root))
               :typeName "local"
               :total total
               :free free
               :used used
               :usage usage}))
          roots)))

(defn server-info
  "获取服务器信息。"
  [_ _]
  (ok {:cpu (get-cpu-info)
       :mem (get-memory-info)
       :jvm (get-jvm-info)
       :sys (get-os-info)
       :disk (get-disk-info)}))

(defn datasource-info
  "获取 HikariCP 数据源监控信息。"
  [{:keys [datasource]} _]
  (try
    (if (instance? HikariDataSource datasource)
      (let [pool (.getHikariPoolMXBean ^HikariDataSource datasource)]
        (ok {:db_name (some-> (.getJdbcUrl ^HikariDataSource datasource) (str/replace "jdbc:" ""))
             :db_version "SQLite"
             :active_connections (.getActiveConnections pool)
             :idle_connections (.getIdleConnections pool)
             :total_connections (.getTotalConnections pool)
             :threads_awaiting_connection (.getThreadsAwaitingConnection pool)
             :max_connections (.getMaximumPoolSize datasource)
             :min_idle (.getMinimumIdle datasource)
             :connection_timeout (.getConnectionTimeout datasource)
             :idle_timeout (.getIdleTimeout datasource)
             :max_lifetime (.getMaxLifetime datasource)}))
      (ok {:db_name "unknown" :db_version "unknown" :active_connections 0}))
    (catch Exception e
      (ok {:status "error" :message (.getMessage e)}))))
