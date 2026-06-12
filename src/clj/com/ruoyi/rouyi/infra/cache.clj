(ns com.ruoyi.rouyi.infra.cache
  "内存缓存管理，基于 Clojure atom 实现。不依赖外部缓存服务。")

;; 全局缓存存储
(defonce cache-store
  (atom {:data (atom {})
         :hit 0
         :miss 0}))
