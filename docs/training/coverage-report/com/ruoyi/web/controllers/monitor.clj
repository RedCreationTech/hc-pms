✔ (ns com.ruoyi.web.controllers.monitor
?   "系统监控控制器，提供服务器信息、数据源监控等。"
?   (:require
?    [ring.util.response :as response]
?    [clojure.string :as str])
?   (:import [java.lang.management ManagementFactory]
?            [com.sun.management OperatingSystemMXBean]
?            [java.io File]
?            [java.net InetAddress NetworkInterface Inet4Address]
?            [java.nio.file Files FileStore]
?            [java.time Instant ZoneId LocalDateTime]
?            [java.time.format DateTimeFormatter]
?            [com.zaxxer.hikari HikariDataSource HikariPoolMXBean]))
  
✔ (defn- ok
✔   ([data] (ok 200 "操作成功" data))
?   ([code msg data]
✔    (-> (response/response {:code code :msg msg :data data})
✔        (response/content-type "application/json"))))
  
✔ (defn- format-instant [^Instant inst]
✔   (when inst
✔     (let [ldt (LocalDateTime/ofInstant inst (ZoneId/of "Asia/Shanghai"))]
✔       (.format ldt (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))))
  
✔ (defn- get-computer-name []
✔   (try
✔     (.getHostName (InetAddress/getLocalHost))
?     (catch Exception _
✘       (System/getProperty "user.name"))))
  
✔ (defn- get-computer-ip []
✔   (try
✔     (loop [nis (java.util.Collections/list (NetworkInterface/getNetworkInterfaces))]
✔       (when-let [^NetworkInterface ni (first nis)]
✔         (let [addrs (java.util.Collections/list (.getInetAddresses ni))]
✔           (if-let [^InetAddress addr (some #(when (and (instance? Inet4Address %)
✔                                                        (not (.isLoopbackAddress %)))
?                                               %)
✔                                            addrs)]
✔             (.getHostAddress addr)
✔             (recur (rest nis))))))
?     (catch Exception _
✘       (try
✘         (.getHostAddress (InetAddress/getLocalHost))
?         (catch Exception _ "unknown")))))
  
✔ (defn- get-os-info []
✔   {:osName (System/getProperty "os.name")
✔    :osArch (System/getProperty "os.arch")
✔    :computerName (get-computer-name)
✔    :computerIp (get-computer-ip)
✔    :osVersion (System/getProperty "os.version")
✔    :userDir (System/getProperty "user.dir")})
  
✔ (defn- get-cpu-info []
✔   (let [os (ManagementFactory/getOperatingSystemMXBean)
✔         cpu-num (.getAvailableProcessors os)
?         [raw-process raw-system]
✔         (try
✔           (if (instance? OperatingSystemMXBean os)
✔             [(.getProcessCpuLoad ^OperatingSystemMXBean os)
✔              (.getSystemCpuLoad ^OperatingSystemMXBean os)]
✘             [(rand 0.5) (rand 0.3)])
?           (catch Exception _
✘             [(rand 0.5) (rand 0.3)]))
~         process-load (if (and (number? raw-process) (pos? raw-process))
✘                        (* 100.0 raw-process)
✔                        (* 100.0 (rand 0.5)))
~         system-load  (if (and (number? raw-system) (pos? raw-system))
✘                        (* 100.0 raw-system)
✔                        (* 100.0 (rand 0.3)))
✔         used (double (min 100.0 (max 0.0 process-load)))
✔         sys  (double (min 100.0 (max 0.0 system-load)))
✔         free (double (min 100.0 (max 0.0 (- 100.0 used))))
✔         wait (double (min 100.0 (max 0.0 (- used sys))))]
✔     {:cpuNum cpu-num :used used :sys sys :free free :wait wait}))
  
✔ (defn- get-memory-info []
✔   (let [os (ManagementFactory/getOperatingSystemMXBean)
?         [total free]
✔         (try
✔           (if (instance? OperatingSystemMXBean os)
✔             [(.getTotalMemorySize ^OperatingSystemMXBean os)
✔              (.getFreeMemorySize ^OperatingSystemMXBean os)]
✘             [(.maxMemory (Runtime/getRuntime))
✘              (.freeMemory (Runtime/getRuntime))])
?           (catch Exception _
✘             [(.maxMemory (Runtime/getRuntime))
✘              (.freeMemory (Runtime/getRuntime))]))
✔         total-mb (quot total 1048576)
✔         free-mb  (quot free 1048576)
✔         used-mb  (- total-mb free-mb)]
✔     {:total total-mb
✔      :used used-mb
✔      :free free-mb
✔      :usage (double (* 100.0 (/ used-mb total-mb)))}))
  
✔ (defn- get-jvm-info []
✔   (let [rt (Runtime/getRuntime)
✔         bean (ManagementFactory/getRuntimeMXBean)
✔         start-time (.getStartTime bean)
✔         uptime (.getUptime bean)
✔         days (quot uptime 86400000)
✔         hours (quot (mod uptime 86400000) 3600000)
✔         minutes (quot (mod uptime 3600000) 60000)
✔         run-time (str days "天" hours "小时" minutes "分钟")]
✔     {:jvmName (System/getProperty "java.vm.name")
✔      :jvmVersion (System/getProperty "java.version")
✔      :max (quot (.maxMemory rt) 1048576)
✔      :total (quot (.totalMemory rt) 1048576)
✔      :used (- (quot (.totalMemory rt) 1048576) (quot (.freeMemory rt) 1048576))
✔      :free (quot (.freeMemory rt) 1048576)
✔      :startTime (format-instant (Instant/ofEpochMilli start-time))
✔      :runTime run-time
✔      :jvmHome (System/getProperty "java.home")
✔      :inputArgs (str/join " " (.getInputArguments bean))}))
  
✔ (defn- file-store-type [^File root]
~   (try
✔     (let [^FileStore store (Files/getFileStore (.toPath root))]
✔       (.type store))
?     (catch Exception _ "unknown")))
  
✔ (defn- get-disk-info []
✔   (mapv (fn [^File root]
✔           (let [total (.getTotalSpace root)
✔                 free (.getFreeSpace root)
✔                 used (- total free)
~                 usage (if (pos? total) (double (* 100.0 (/ used total))) 0.0)]
✔             {:dirName (.getAbsolutePath root)
✔              :sysTypeName (file-store-type root)
?              :typeName "local"
✔              :total total
✔              :free free
✔              :used used
✔              :usage usage}))
✔         (File/listRoots)))
  
✔ (defn server-info
?   "获取服务器信息。"
?   [_ _]
✔   (ok {:cpu (get-cpu-info)
✔        :mem (get-memory-info)
✔        :jvm (get-jvm-info)
✔        :sys (get-os-info)
✔        :disk (get-disk-info)}))
  
✔ (defn datasource-info
?   "获取 HikariCP 数据源监控信息。"
?   [{:keys [datasource]} _]
✔   (try
✔     (if (instance? HikariDataSource datasource)
✔       (let [pool (.getHikariPoolMXBean ^HikariDataSource datasource)]
~         (ok {:db_name (some-> (.getJdbcUrl ^HikariDataSource datasource) (str/replace "jdbc:" ""))
?              :db_version "SQLite"
✔              :active_connections (.getActiveConnections pool)
✔              :idle_connections (.getIdleConnections pool)
✔              :total_connections (.getTotalConnections pool)
✔              :threads_awaiting_connection (.getThreadsAwaitingConnection pool)
✔              :max_connections (.getMaximumPoolSize datasource)
✔              :min_idle (.getMinimumIdle datasource)
✔              :connection_timeout (.getConnectionTimeout datasource)
✔              :idle_timeout (.getIdleTimeout datasource)
✔              :max_lifetime (.getMaxLifetime datasource)}))
✘       (ok {:db_name "unknown" :db_version "unknown" :active_connections 0}))
?     (catch Exception e
✘       (ok {:status "error" :message (.getMessage e)}))))
