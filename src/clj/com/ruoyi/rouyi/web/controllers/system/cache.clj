(ns com.ruoyi.rouyi.web.controllers.system.cache
  "缓存监控控制器。"
  (:require
    [ring.util.response :as response]))

;; 简单的内存缓存存储
(defonce cache-data (atom {}))
(defonce cache-stats (atom {:hit 0 :miss 0}))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn cache-info
  "获取缓存信息。"
  [_ _]
  (ok {:name "Memory Cache"
       :type "clojure.core/atom"
       :keysCount (count @cache-data)
       :memoryUsed (quot (+ (count (pr-str @cache-data)) 1024) 1024)
       :memoryMax 256
       :hitCount (:hit @cache-stats)
       :missCount (:miss @cache-stats)}))

(defn cache-keys
  "获取缓存键列表。"
  [_ _]
  (ok {:keys (mapv name (keys @cache-data))
       :count (count @cache-data)}))

(defn cache-names
  "获取缓存名称列表。"
  [_ _]
  (ok {:cacheNames ["Memory Cache"]}))

(defn cache-keys-by-name
  "获取指定缓存名称的键列表。"
  [_ request]
  (let [cache-name (get-in request [:path-params :cacheName])]
    (ok {:cacheName cache-name
         :keys (mapv name (keys @cache-data))
         :count (count @cache-data)})))

(defn cache-value
  "获取缓存值。"
  [_ request]
  (let [cache-key (get-in request [:path-params :cacheKey])
        val (get @cache-data (keyword cache-key))]
    (swap! cache-stats update :hit inc)
    (ok {:cacheName "Memory Cache"
         :cacheKey cache-key
         :value (str val)})))

(defn clear-cache
  "清空缓存。"
  [_ _]
  (reset! cache-data {})
  (ok "缓存已清空"))

(defn clear-cache-name
  "清除指定名称的缓存。"
  [_ _]
  (reset! cache-data {})
  (ok "缓存已清空"))

(defn clear-cache-key
  "清除指定键的缓存。"
  [_ request]
  (let [cache-key (get-in request [:path-params :cacheKey])]
    (swap! cache-data dissoc (keyword cache-key))
    (ok "缓存键已清除")))

(defn clear-cache-all
  "清除所有缓存。"
  [_ _]
  (reset! cache-data {})
  (reset! cache-stats {:hit 0 :miss 0})
  (ok "所有缓存已清空"))
