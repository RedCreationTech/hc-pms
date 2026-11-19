(ns com.ruoyi.domain.pms.platform
  "G18 平台能力与兼容基线: 由服务端真实运行环境与 pms_config_record 中的 report-metric 口径派生,
   供界面展示我在什么环境下看到哪些报表口径, 不猜任何未经确认的兼容矩阵.
   只读派生: 不落库, 不构成门控, 不代替业务批准的兼容矩阵."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.portfolio :as portfolio]
    [com.ruoyi.domain.pms.report :as report]
    [com.ruoyi.domain.pms.rules :as r]
    [com.ruoyi.infra.db :as db]))


(def required-runtime
  "服务端运行所需的 JDK 主版本下限; 低于该值启动即不受支持."
  21)


(def supported-databases
  "已实现并随包提供迁移的数据库类型与迁移目录."
  [{:kind "sqlite" :label "SQLite" :migration_dir "migrations-sqlite"}
   {:kind "mysql" :label "MySQL" :migration_dir "migrations"}])


(def supported-browsers
  "浏览器兼容基线: 由本项目前端实际依赖的 Ant Design 6 与 shadow-cljs 输出决定,
   不按旧示意版本推定. 未列入的浏览器版本不受支持, 也不承诺等价行为."
  {"chrome" {:label "Chrome / Edge (Chromium)" :minimum 100}
   "firefox" {:label "Firefox" :minimum 100}
   "safari" {:label "Safari" :minimum 15}})


(defn- java-version
  "解析 java.version 的主版本; 1.8.x 这类旧格式按次版本取值."
  []
  (let [raw (or (System/getProperty "java.version") "") value (str/replace raw #"[^0-9.].*$" "")]
    (when (seq value)
      (let [parts (str/split value #"\.") major (parse-long (first parts))]
        (if (and major (= 1 major) (> (count parts) 1)) (parse-long (second parts)) major)))))


(defn- database-kind
  "按真实 JDBC 元数据判定库类型; 只认本包提供迁移的两种, 其余如实标为 unknown 而不是猜."
  [connection]
  (let [kind (name (db/detect-db-type connection))]
    (assoc (or (some #(when (= kind (:kind %)) %) supported-databases)
               {:kind "unknown" :label "未识别" :migration_dir nil})
           :supported (not= "unknown" kind))))


(defn- client-version
  "从 User-Agent 解析主流浏览器及其主版本; 无法识别时返回 nil, 不臆造."
  [user-agent]
  (let [ua (or user-agent "")]
    (cond
      (re-find #"(?i)headlesschrome|chrome/" ua)
      (when-let [[_ v] (re-find #"(?i)chrome/(\d+)" ua)] {:family "chrome" :version (parse-long v)})
      (re-find #"(?i)firefox/" ua)
      (when-let [[_ v] (re-find #"(?i)firefox/(\d+)" ua)] {:family "firefox" :version (parse-long v)})
      (re-find #"(?i)version/" ua)
      (when-let [[_ v] (re-find #"(?i)version/(\d+).*safari" ua)] {:family "safari" :version (parse-long v)})
      :else nil)))


(defn- client-check
  "当前请求浏览器是否在兼容基线内; 无 User-Agent 时如实标为 unknown."
  [user-agent]
  (if-let [{:keys [family version]} (client-version user-agent)]
    (let [{:keys [label minimum]} (get supported-browsers family)]
      {:family family :label label :version version :minimum minimum
       :supported (and minimum (>= version minimum)) :reported true})
    {:family nil :label nil :version nil :minimum nil :supported nil :reported false}))


(defn- items
  "把 {表 数量} 汇总为带类型与名称的条目列表."
  [definitions]
  (let [counts (frequencies (map #(or (get % "object_type") (:object_type %)) definitions))]
    (mapv (fn [object]
            (let [kind (get object "value")]
              {:object_type kind :label (get object "label")
               :metric_count (get counts kind 0)}))
          report/objects)))


(defn- metric-environment-check
  "生效指标所依赖的对象在当前环境实际可计算: 项目行、任务行与费用明细行是否真的读得到.
   只读核对, 不改任何数据, 也不把空集合当成通过."
  [q actor]
  (let [projects (portfolio/authorized-projects q actor)
        pids (mapv :project_id projects)]
    {:project_rows (count projects)
     :task_rows (count (q :pms/tasks-in-projects {:project_ids pids}))
     :cost_entry_rows (count (q :pms/cost-entries-in-projects {:project_ids pids}))}))


(defn capabilities
  "G18 平台能力: 服务端运行前沿, 数据库支持、浏览器兼容基线与当前客户端判定, 以及报表指标口径分布.
   低于最低 JDK 时 java.supported 为 false 并如实给出 required; 未知客户端不当作通过."
  [svc actor headers]
  (r/permit! actor "pms:project:list")
  (let [q (:query-fn svc)
        major (java-version)
        {:keys [metrics]} (report/definitions q)
        check (metric-environment-check q actor)
        client (client-check (get headers "user-agent"))
        database (database-kind (:db svc))]
    {:java {:version (System/getProperty "java.version") :major major :required required-runtime
            :supported (boolean (and major (>= major required-runtime)))
            :vendor (System/getProperty "java.vendor") :home (System/getProperty "java.home")}
     :databases (mapv #(assoc % :current (= (:kind %) (:kind database))) supported-databases)
     :database database
     :browsers (mapv (fn [[family {:keys [label minimum]}]]
                       {:family family :label label :minimum minimum
                        :current (= family (:family client)) :supported client})
                     supported-browsers)
     :client client
     :frontend {:framework "Reagent 2 + Ant Design 6" :build "shadow-cljs"}
     :metrics {:published (count metrics) :definitions (items metrics)
               :object_types (mapv #(get % "value") report/objects)}
     :environment check
     :generated_at (str (java.time.Instant/now))}))
