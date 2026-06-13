✔ (ns com.ruoyi.web.controllers.system.cache
?   "缓存监控控制器 — 模拟多缓存空间，提供命令统计、键值浏览与清除。"
?   (:require
?    [ring.util.response :as response]
?    [clojure.string :as str]
?    [clojure.data.json :as json]))
  
? ;; ─── 内存缓存存储 ──────────────────────────────────────────────────
  
? ;; 命令统计：get / hit / miss / clear
~ (defonce cache-stats
✘   (atom {:get 0 :hit 0 :miss 0 :clear 0}))
  
✔ (defn- seed-cache []
✔   {"user"
✔    {"admin" {:user_name "admin" :nick_name "若依管理员" :status "0"}}
?    "dict"
✔    {"sys_user_status:0" {:label "正常" :value "0"}
✔     "sys_user_status:1" {:label "停用" :value "1"}}
?    "config"
✔    {"sys.account.captchaEnabled" {:config_key "sys.account.captchaEnabled" :config_value "true"}}
?    "notice"
✔    {"1" {:notice_id 1 :notice_title "系统公告" :notice_type "1"}}})
  
~ (defonce cache-data
✘   (atom (seed-cache)))
  
✔ (defn- ok
✔   ([data] (ok 200 "操作成功" data))
?   ([code msg data]
✔    (-> (response/response {:code code :msg msg :data data})
✔        (response/content-type "application/json"))))
  
✔ (defn- total-memory-bytes []
~   (try
✔     (let [rt (Runtime/getRuntime)]
✔       (- (.totalMemory rt) (.freeMemory rt)))
?     (catch Exception _ 0)))
  
✔ (defn- memory-used-mb []
✔   (quot (total-memory-bytes) 1048576))
  
✔ (defn- memory-max-mb []
✔   (quot (.maxMemory (Runtime/getRuntime)) 1048576))
  
✔ (defn- total-keys []
✔   (reduce + (map count (vals @cache-data))))
  
✔ (defn- command-stats []
✔   (let [s @cache-stats]
✔     [{:name "get" :value (:get s)}
✔      {:name "hit" :value (:hit s)}
✔      {:name "miss" :value (:miss s)}
✔      {:name "clear" :value (:clear s)}]))
  
? ;; ─── 缓存信息 ──────────────────────────────────────────────────────
  
✔ (defn cache-info
?   "获取缓存整体信息。"
?   [_ _]
✔   (ok {:name "Memory Cache"
?        :type "Clojure Atom"
✔        :keysCount (total-keys)
✔        :memoryUsed (memory-used-mb)
✔        :memoryMax (memory-max-mb)
✔        :commandStats (command-stats)
✔        :cacheNames [{:name "Memory Cache"
?                      :type "Clojure Atom"
✔                      :keysCount (total-keys)
✔                      :memoryUsed (memory-used-mb)
✔                      :memoryMax (memory-max-mb)}]}))
  
✔ (defn cache-names
?   "获取缓存名称列表。"
?   [_ _]
✔   (ok {:cacheNames (vec (keys @cache-data))}))
  
✔ (defn cache-keys
?   "获取所有缓存键（扁平化）。"
?   [_ _]
~   (let [ks (for [[cache-name entries] @cache-data
✔                  k (keys entries)]
✔              (str cache-name "::" (name k)))]
✔     (ok {:keys (vec ks)
✔          :count (count ks)})))
  
✔ (defn cache-keys-by-name
?   "获取指定缓存名称下的键列表。"
?   [_ request]
✔   (let [cache-name (get-in request [:path-params :cacheName])
✔         entries (get @cache-data cache-name {})]
✔     (ok {:cacheName cache-name
✔          :keys (mapv name (keys entries))
✔          :count (count entries)})))
  
✔ (defn cache-value
?   "获取缓存值。"
?   [_ request]
✔   (let [cache-name (get-in request [:path-params :cacheName])
✔         cache-key (get-in request [:path-params :cacheKey])
✔         val (get-in @cache-data [cache-name cache-key])]
✔     (swap! cache-stats update :get inc)
✔     (if (some? val)
✔       (swap! cache-stats update :hit inc)
✔       (swap! cache-stats update :miss inc))
✔     (ok {:cacheName cache-name
✔          :cacheKey cache-key
✔          :value (if (some? val) (json/write-str val) "")})))
  
? ;; ─── 清除操作 ──────────────────────────────────────────────────────
  
✔ (defn clear-cache
?   "清空所有缓存。"
?   [_ _]
✔   (reset! cache-data (seed-cache))
✔   (swap! cache-stats update :clear inc)
✔   (ok "缓存已清空"))
  
✔ (defn clear-cache-name
?   "清除指定名称的缓存。"
?   [_ request]
✔   (let [cache-name (get-in request [:path-params :cacheName])]
✔     (swap! cache-data assoc cache-name {})
✔     (swap! cache-stats update :clear inc)
✔     (ok "缓存已清空")))
  
✔ (defn clear-cache-key
?   "清除指定键。"
?   [_ request]
✔   (let [cache-name (get-in request [:path-params :cacheName])
✔         cache-key (get-in request [:path-params :cacheKey])]
✔     (swap! cache-data update cache-name dissoc cache-key)
✔     (swap! cache-stats update :clear inc)
✔     (ok "缓存键已清除")))
  
✔ (defn clear-cache-all
?   "清除所有缓存并重置统计。"
?   [_ _]
✔   (reset! cache-data (seed-cache))
✔   (reset! cache-stats {:get 0 :hit 0 :miss 0 :clear 0})
✔   (ok "所有缓存已清空"))
