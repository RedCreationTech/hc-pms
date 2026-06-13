(ns com.ruoyi.integrant.trace
  "Integrant 函数组件的运行时调用追踪。"
  (:require
   [integrant.core :as ig]
   [com.ruoyi.integrant.state :as state]
   [com.ruoyi.web.handler :as handler]
   [kit.edge.db.sql.conman]
   [clojure.string :as str]))

(defonce ^:private registry (atom {}))
;; registry: {key-str {:original fn :active? boolean :logs [...]}}

(defonce ^:private dynamic-atoms (atom {}))
;; dynamic-atoms: {keyword <atom-of-actual-fn>}

(defn register-dynamic!
  "注册一个动态代理组件。返回的函数会实时去取 atom 里的实际实现。"
  [k f]
  (let [a (atom f)]
    (swap! dynamic-atoms assoc k a)
    (fn [& args]
      (apply @a args))))

(defn set-dynamic!
  "替换动态代理组件的实际实现。"
  [k f]
  (when-let [a (get @dynamic-atoms k)]
    (reset! a f)))

(defn current-dynamic
  "获取动态代理组件当前实际实现。"
  [k]
  (when-let [a (get @dynamic-atoms k)]
    @a))

;; 覆盖 kit-sql-conman 的 query-fn，使其成为一个可动态替换的代理。
;; 这样启动后所有持有 :db.sql/query-fn 的服务仍然指向同一个函数对象，
;; 但函数对象内部会读取 atom，从而支持运行时切换追踪包装。
(def ^:private original-query-fn-init
  (get-method ig/init-key :db.sql/query-fn))

(defmethod ig/init-key :db.sql/query-fn
  [_ opts]
  (let [actual (original-query-fn-init _ opts)]
    (register-dynamic! :db.sql/query-fn actual)))

(def ^:private max-log-entries 200)

(defn- sensitive-key? [k]
  (let [s (str/lower-case (name k))]
    (boolean (some #(str/includes? s %)
                   ["authorization" "cookie" "token" "password" "passwd" "secret"]))))

(defn- safe-snapshot [v]
  "把任意值转成可 JSON 序列化的简短摘要。"
  (cond
    (nil? v) nil
    (map? v) (into {} (map (fn [[k v]] [(str k) (if (sensitive-key? k) "<redacted>" (safe-snapshot v))])) v)
    (sequential? v) (mapv safe-snapshot v)
    (set? v) (mapv safe-snapshot v)
    (fn? v) "<function>"
    (instance? Throwable v) (str (class v) ": " (ex-message v))
    :else
    (let [s (pr-str v)]
      (if (> (count s) 400)
        (str (subs s 0 400) "...")
        s))))

(defn- now []
  (System/currentTimeMillis))

(defn- make-wrapper [key-str original]
  (fn [& args]
    (let [rec (get @registry key-str)
          active? (:active? rec)
          t0 (now)]
      (try
        (let [ret (apply original args)]
          (when active?
            (swap! registry update-in [key-str :logs]
                   (fn [logs]
                     (->> (conj logs
                                {:time (now)
                                 :duration (- (now) t0)
                                 :args (safe-snapshot args)
                                 :result (safe-snapshot ret)})
                          (take-last max-log-entries)
                          vec))))
          ret)
        (catch Throwable e
          (when active?
            (swap! registry update-in [key-str :logs]
                   (fn [logs]
                     (->> (conj logs
                                {:time (now)
                                 :duration (- (now) t0)
                                 :args (safe-snapshot args)
                                 :error (safe-snapshot e)})
                          (take-last max-log-entries)
                          vec))))
          (throw e))))))

(defn- kw [key-str]
  (if (keyword? key-str) key-str (keyword key-str)))

(def ^:private dynamic-keys
  "支持运行时动态替换的函数组件。对这些 key 的追踪不会修改系统 map，
   而是替换它们内部的代理 atom，从而让已持有引用的调用方也能看到新实现。"
  #{:handler/ring :db.sql/query-fn})

(defn- dynamic-key? [k]
  (contains? dynamic-keys k))

(defn- current-actual [k]
  (case k
    :handler/ring (handler/current-ring-handler)
    :db.sql/query-fn (current-dynamic :db.sql/query-fn)
    (get @state/system k)))

(defn- set-actual! [k f]
  (case k
    :handler/ring (handler/set-ring-handler! f)
    :db.sql/query-fn (set-dynamic! :db.sql/query-fn f)
    (swap! state/system assoc k f)))

(defn start! [key-str]
  "开始对某个 Integrant key 对应的函数进行调用追踪。"
  (let [k (kw key-str)
        f (current-actual k)]
    (when (fn? f)
      (swap! registry
             (fn [reg]
               (if (get-in reg [key-str :wrapper])
                 (assoc-in reg [key-str :active?] true)
                 (let [wrapper (make-wrapper key-str f)]
                   (assoc reg key-str {:original f :wrapper wrapper :active? true :logs []})))))
      (let [wrapper (get-in @registry [key-str :wrapper])]
        (set-actual! k wrapper))
      true)))

(defn stop! [key-str]
  "停止追踪并清空日志，恢复原始函数。"
  (let [k (kw key-str)
        rec (get @registry key-str)]
    (when rec
      (let [original (:original rec)]
        (set-actual! k original)
        (swap! registry dissoc key-str)))
    true))

(defn set-active! [key-str active?]
  (if active?
    (start! key-str)
    (stop! key-str)))

(defn logs [key-str]
  (get-in @registry [key-str :logs] []))

(defn active? [key-str]
  (boolean (get-in @registry [key-str :active?])))
