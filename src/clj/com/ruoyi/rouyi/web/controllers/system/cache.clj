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
