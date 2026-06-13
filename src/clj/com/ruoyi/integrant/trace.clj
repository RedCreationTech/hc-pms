(ns com.ruoyi.integrant.trace
  "Integrant 函数组件的运行时调用追踪。"
  (:require
   [com.ruoyi.integrant.state :as state]
   [com.ruoyi.web.handler :as handler]
   [clojure.string :as str]))

(defonce ^:private registry (atom {}))
;; registry: {key-str {:original fn :active? boolean :logs [...]}}

(def ^:private max-log-entries 200)

(defn- safe-snapshot [v]
  "把任意值转成可 JSON 序列化的简短摘要。"
  (cond
    (nil? v) nil
    (map? v) (into {} (map (fn [[k v]] [(str k) (safe-snapshot v)])) v)
    (sequential? v) (mapv safe-snapshot v)
    (set? v) (into #{} (map safe-snapshot v))
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

(defn- ring-key? [k]
  (= k :handler/ring))

(defn start! [key-str]
  "开始对某个 Integrant key 对应的函数进行调用追踪。"
  (let [k (kw key-str)
        f (if (ring-key? k)
            (handler/current-ring-handler)
            (get @state/system k))]
    (when (fn? f)
      (swap! registry
             (fn [reg]
               (if (get-in reg [key-str :wrapper])
                 (assoc-in reg [key-str :active?] true)
                 (let [wrapper (make-wrapper key-str f)]
                   (assoc reg key-str {:original f :wrapper wrapper :active? true :logs []})))))
      (let [wrapper (get-in @registry [key-str :wrapper])]
        (if (ring-key? k)
          (handler/set-ring-handler! wrapper)
          (swap! state/system assoc k wrapper)))
      true)))

(defn stop! [key-str]
  "停止追踪并清空日志，恢复原始函数。"
  (let [k (kw key-str)
        rec (get @registry key-str)]
    (when rec
      (let [original (:original rec)]
        (if (ring-key? k)
          (handler/set-ring-handler! original)
          (swap! state/system assoc k original))
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
