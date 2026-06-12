(ns com.ruoyi.rouyi.infra.cache
  "内存缓存管理，基于 Clojure atom 实现。不依赖外部缓存服务。"
  (:require [clojure.core.cache :as cache]))

(defonce cache-store
  "全局缓存存储。"
  (atom {:data (cache/lru-cache-factory {} :threshold 256)
         :hit 0
         :miss 0}))

(defn cache-get
  "从缓存获取值。"
  [key]
  (let [store @cache-store
        c (:data store)
        hit? (cache/has? c key)]
    (swap! cache-store update (if hit? :hit :miss) inc)
    (when hit?
      (let [new-c (cache/hit c key)]
        (swap! cache-store assoc :data new-c)
        (cache/lookup c key)))))

(defn cache-put!
  "写入缓存。"
  [key value]
  (swap! cache-store update :data #(cache/miss % key value)))

(defn cache-evict!
  "删除缓存项。"
  [key]
  (swap! cache-store update :data #(cache/evict % key)))

(defn cache-stats
  "获取缓存统计信息。"
  []
  {:hit (:hit @cache-store)
   :miss (:miss @cache-store)
   :size (count @(:data @cache-store))})
