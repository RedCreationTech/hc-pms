(ns com.ruoyi.infra.datasource
  "代理 DataSource — 支持运行时热切换底层连接池（SQLite ↔ MySQL）。"
  (:import
    (java.sql
      Connection)
    (javax.sql
      DataSource)))


;; 全局注册表: {object-id -> atom-of-datasource}
;; 用于在 classloader reload 后仍能通过对象身份找到对应的 delegate
(defonce ^:private registry (java.util.concurrent.ConcurrentHashMap.))


(defn delegating-datasource
  "创建一个支持热切换的代理 DataSource，初始指向 real-ds。"
  ^DataSource [^DataSource real-ds]
  (let [delegate (atom real-ds)
        ds (reify
             DataSource
             (getConnection
               [_]
               (.getConnection ^DataSource @delegate))

             (getConnection
               [_ username password]
               (.getConnection ^DataSource @delegate ^String username ^String password))


             Object

             (toString
               [_]
               (str "DelegatingDataSource -> " @delegate)))]
    ;; 注册到全局注册表
    (.put registry (System/identityHashCode ds) delegate)
    ds))


(defn swappable?
  "检查 ds 是否为可热切换的代理 DataSource。通过类名检测。"
  [ds]
  (boolean
    (when ds
      (.containsKey registry (System/identityHashCode ds)))))


(defn- get-delegate-atom
  "获取代理 DataSource 内部的 delegate atom。"
  [ds]
  (let [aid (System/identityHashCode ds)]
    (when-let [a (.get registry aid)]
      a)))


(defn swap-delegate!
  "替换代理 DataSource 的底层连接池。返回旧的 DataSource。"
  [dds new-ds]
  (when-let [a (get-delegate-atom dds)]
    (let [old @a]
      (reset! a new-ds)
      old)))


(defn get-delegate
  "获取代理 DataSource 当前的底层 DataSource。"
  [dds]
  (when-let [a (get-delegate-atom dds)]
    @a))
