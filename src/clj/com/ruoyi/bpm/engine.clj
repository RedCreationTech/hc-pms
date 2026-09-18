(ns com.ruoyi.bpm.engine
  "Flowable BPM 引擎的 Integrant 组件封装。

  路线 2（混搭）：应用逻辑用 Clojure，工作流引擎内嵌 Flowable。
  Flowable 使用独立的 H2 1.4.200 数据库文件（默认 flowable.db），
  与 app 业务库（SQLite/MySQL）完全解耦。

  关键点：
    · H2 必须用 1.4.200（Flowable 的 IDENTITY 方言不兼容 H2 2.x）
    · 数据库双轨：业务数据存 app 库，引擎状态（ACT_*）存 H2
    · 异步执行器默认开启（超时边界定时事件等 timer 任务依赖它），
      可通过 FLOWABLE_ASYNC=false 关闭
    · 生产可把 Flowable 库切到外部 MySQL/PostgreSQL（仅改 JDBC URL）
  "
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.ruoyi.bpm.core :as bpm-core]
   [integrant.core :as ig])
  (:import
   (org.flowable.engine ProcessEngine ProcessEngineConfiguration)))

(defn- as-bool
  "将环境值/配置值转为布尔（容忍字符串 'true'/'1'/'false'/'0'）。"
  [v]
  (if (string? v)
    (contains? #{"true" "1" "yes" "on"} (str/lower-case v))
    (boolean v)))

(defn build-process-engine
  "构建并启动一个 Flowable ProcessEngine（standalone，独立 H2 文件）。
   返回 ProcessEngine 实例。"
  [{:keys [jdbc-url database-schema-update async?]}]
  (let [async? (if (nil? async?) true (as-bool async?))
        cfg (ProcessEngineConfiguration/createStandaloneProcessEngineConfiguration)
        _   (.setJdbcUrl cfg (or jdbc-url "jdbc:h2:file:./flowable;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE"))
        _   (.setDatabaseSchemaUpdate cfg (or database-schema-update "true"))
        _   (.setAsyncExecutorActivate cfg async?)
        _   (.setJdbcMaxActiveConnections cfg 3)
        _   (.setJdbcMaxIdleConnections cfg 2)
        _   (when (some? (requiring-resolve 'com.ruoyi.bpm.core/make-task-listener))
              (.setBeans cfg {"bpmTaskListener" (bpm-core/make-task-listener)
                              "bpmTimeoutHandler" (bpm-core/make-timeout-handler)
                              "bpmTriggerDelegate" (bpm-core/make-trigger-delegate)}))
        engine (.buildProcessEngine cfg)
        _   (when engine (bpm-core/register-engine! engine))]
    (log/info "[bpm/engine] Flowable ProcessEngine 启动完成:" (.getName engine)
              "| async:" async?)
    engine))

(defn- halt!
  "关闭引擎。"
  [engine]
  (when engine
    (try
      (.close ^ProcessEngine engine)
      (log/info "[bpm/engine] Flowable ProcessEngine 已关闭")
      (catch Exception e
        (log/warn "[bpm/engine] 关闭引擎出错:" (.getMessage e))))))

;; ── Integrant 组件 ────────────────────────────────────────────────────
(defmethod ig/init-key :app.bpm/engine
  [_ opts]
  (log/info "[bpm/engine] 初始化 :app.bpm/engine")
  (build-process-engine opts))

(defmethod ig/halt-key! :app.bpm/engine
  [_ engine]
  (halt! engine))

;; ── 便捷访问器 ────────────────────────────────────────────────────────
(defn process-engine
  "从 Integrant system 或已初始化的组件取值中取出 ProcessEngine。"
  [component-or-system]
  (or (:app.bpm/engine component-or-system)
      (get-in component-or-system [:app.bpm/engine])
      component-or-system))
