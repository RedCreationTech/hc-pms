(ns com.ruoyi.rouyi.web.controllers.system.cache
  "缓存监控控制器。"
  (:require
   [com.ruoyi.rouyi.infra.cache :as cache]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn cache-info
  "获取缓存信息。"
  [_ _]
  (ok {:name "Memory Cache"
       :type "core.cache/LRU"
       :keys-count (count @(:data cache/cache-store))
       :memory-used 0
       :memory-max 256}))

(defn cache-keys
  "获取缓存键列表。"
  [_ _]
  (ok {:keys (keys @(:data cache/cache-store))
       :count (count @(:data cache/cache-store))}))

(defn cache-clear
  "清空缓存。"
  [_ _]
  (reset! (:data cache/cache-store) {})
  (ok "缓存已清空"))

(defn cache-names
  "获取缓存名称列表。"
  [_ _]
  (ok {:cacheNames ["Memory Cache"]}))

(defn cache-keys-by-name
  "获取指定缓存名称的键列表。"
  [_ request]
  (let [cache-name (get-in request [:path-params :cacheName])]
    (ok {:cacheName cache-name :keys (keys @(:data cache/cache-store))})))

(defn cache-value
  "获取缓存值。"
  [_ request]
  (let [cache-name (get-in request [:path-params :cacheName])
        cache-key (get-in request [:path-params :cacheKey])
        val (get @(:data cache/cache-store) (keyword cache-key))]
    (ok {:cacheName cache-name :cacheKey cache-key :value (str val)})))

(defn clear-cache-name
  "清除指定名称的缓存。"
  [_ _]
  (reset! (:data cache/cache-store) {})
  (ok "缓存已清空"))

(defn clear-cache-key
  "清除指定键的缓存。"
  [_ request]
  (let [cache-key (get-in request [:path-params :cacheKey])]
    (swap! (:data cache/cache-store) dissoc (keyword cache-key))
    (ok "缓存键已清除")))

(defn clear-cache-all
  "清除所有缓存。"
  [_ _]
  (reset! (:data cache/cache-store) {})
  (ok "所有缓存已清空"))
