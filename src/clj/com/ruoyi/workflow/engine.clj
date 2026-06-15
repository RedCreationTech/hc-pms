(ns com.ruoyi.workflow.engine
  "Flowable 流程引擎 Integrant 组件。
   启动时创建 ProcessEngine，关闭时销毁。"
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log])
  (:import [org.flowable.engine ProcessEngineConfiguration]
           [org.flowable.engine.impl.cfg StandaloneProcessEngineConfiguration]
           [org.flowable.common.engine.api FlowableObjectNotFoundException]))

(defonce ^:private engine-atom (atom nil))

(defn get-engine
  "获取当前流程引擎实例。"
  []
  @engine-atom)

(defn get-repository-service []
  (when-let [e @engine-atom] (.getRepositoryService e)))

(defn get-runtime-service []
  (when-let [e @engine-atom] (.getRuntimeService e)))

(defn get-task-service []
  (when-let [e @engine-atom] (.getTaskService e)))

(defn get-history-service []
  (when-let [e @engine-atom] (.getHistoryService e)))

(defn get-form-service []
  (when-let [e @engine-atom] (.getFormService e)))

(defmethod ig/init-key :workflow/engine
  [_ {:keys [jdbc-url db-type]}]
  (log/info "[workflow] 初始化 Flowable 流程引擎...")
  ;; Flowable 不支持 SQLite，自动降级为 H2 内存库
  ;; 生产环境请配置 MySQL JDBC_URL
  (let [db-type (or db-type :sqlite)
        ;; SQLite -> H2 内存库；MySQL -> 直接用 MySQL
        flowable-url (if (= db-type :sqlite)
                       "jdbc:h2:mem:flowable;DB_CLOSE_DELAY=-1"
                       jdbc-url)
        flowable-driver (if (= db-type :sqlite)
                          "org.h2.Driver"
                          "com.mysql.cj.jdbc.Driver")
        flowable-user (if (= db-type :sqlite) "sa" "root")
        flowable-pass (if (= db-type :sqlite) "" "password")
        config (StandaloneProcessEngineConfiguration.)
        _ (doto config
            (.setJdbcUrl flowable-url)
            (.setJdbcDriver flowable-driver)
            (.setJdbcUsername flowable-user)
            (.setJdbcPassword flowable-pass)
            (.setDatabaseSchemaUpdate "true")
            (.setAsyncExecutorActivate false))
        engine (.buildProcessEngine config)]
    (reset! engine-atom engine)
    (log/info "[workflow] Flowable 引擎启动成功 (" (if (= db-type :sqlite) "H2 内存库" "MySQL") ")")
    engine))

(defmethod ig/halt-key! :workflow/engine
  [_ engine]
  (log/info "[workflow] 关闭 Flowable 引擎...")
  (when engine
    (.close engine))
  (reset! engine-atom nil)
  (log/info "[workflow] Flowable 引擎已关闭"))
