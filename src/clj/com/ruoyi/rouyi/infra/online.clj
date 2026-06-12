(ns com.ruoyi.rouyi.infra.online
  "在线用户管理。

  将会话持久化到 sys_online 表，并提供强退/黑名单能力。
  由于 JWT 令牌在过期前无法单方面失效，强退后会将令牌加入
  内存黑名单，直到令牌自然过期。"
  (:require
   [clojure.tools.logging :as log]
   [com.ruoyi.rouyi.infra.security :as security])
  (:import
   [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

;; ──────────── 全局状态 ────────────

(defonce ^:private query-fn-atom (atom nil))

(defonce ^:private token-blacklist
  ;; token -> 过期时间戳（毫秒）
  (atom {} :validator map?))

(defn set-query-fn!
  "由 online-service 在系统启动时注入 query-fn。"
  [query-fn]
  (reset! query-fn-atom query-fn))

(defn- query-fn []
  (if-let [q @query-fn-atom]
    q
    (throw (IllegalStateException. "online query-fn not initialized"))))

;; ──────────── 黑名单 ────────────

(defn blacklist!
  "将令牌加入黑名单，exp-ms 为令牌过期时间戳。"
  [token exp-ms]
  (swap! token-blacklist assoc token exp-ms))

(defn blacklisted?
  "检查令牌是否已被强退。"
  [token]
  (contains? @token-blacklist token))

(defn cleanup-blacklist!
  "清理已过期的黑名单记录。"
  []
  (let [now (System/currentTimeMillis)]
    (swap! token-blacklist
           (fn [bl]
             (into {} (remove (fn [[_ exp]] (< exp now)) bl))))))

;; ──────────── 清理调度 ────────────

(declare cleanup-expired-sessions!)

(defonce cleanup-executor
  (delay
    (doto (ScheduledThreadPoolExecutor. 1)
      (.scheduleAtFixedRate
       (reify Runnable
         (run [_]
           (try
             (cleanup-expired-sessions!)
             (cleanup-blacklist!)
             (catch Exception e
               (log/warn e "Online user cleanup failed")))))
       5 5 TimeUnit/MINUTES))))

;; ──────────── 核心 API ────────────

(defn register!
  "登录成功后注册在线用户记录。"
  [token user-name login-ip]
  (let [now (System/currentTimeMillis)
        expire-ms (* 30 60 1000)]  ; 30 分钟无访问视为过期
    (try
      ((query-fn) :create-online-user!
       {:session_id token
        :login_name user-name
        :dept_name ""
        :ipaddr (or login-ip "127.0.0.1")
        :login_location ""
        :browser ""
        :os ""
        :status "on_line"
        :start_timestamp now
        :last_access_time now
        :expire_time expire-ms})
      (catch Exception e
        (log/warn e "Failed to register online user")))
    (force cleanup-executor)
    nil))

(defn heartbeat!
  "更新用户最后访问时间。"
  [token]
  (when token
    (try
      ((query-fn) :update-online-user!
       {:session_id token
        :last_access_time (System/currentTimeMillis)
        :status nil
        :expire_time nil})
      (catch Exception e
        (log/warn e "Failed to update online user heartbeat"))))
  nil)

(defn unregister!
  "注销在线用户（用户主动退出）。"
  [token]
  (when token
    (try
      ((query-fn) :delete-online-user! {:session_id token})
      (catch Exception e
        (log/warn e "Failed to unregister online user")))
    (when-let [claims (some-> token security/parse-token)]
      (blacklist! token (:exp claims))))
  nil)

(defn cleanup-expired-sessions!
  "清理超过 expire_time 未心跳的会话。"
  []
  (let [threshold (- (System/currentTimeMillis) (* 30 60 1000))]
    (try
      ((query-fn) :delete-online-user! {:session_id "__cleanup__"})
      (catch Exception _))
    ;; 由于 HugSQL 没有动态 WHERE，这里简单列出后逐条删除
    (let [expired (->> ((query-fn) :list-online-users {:page_size 10000 :offset 0})
                       (filter #(< (:last_access_time %) threshold)))]
      (doseq [s expired]
        (try
          ((query-fn) :delete-online-user! {:session_id (:session_id s)})
          (catch Exception e
            (log/warn e "Failed to delete expired session"))))))
  nil)

(defn list-online
  "获取在线用户列表，支持条件筛选。"
  [& {:keys [login-name ipaddr page-num page-size]
      :or   {page-num 1 page-size 10}}]
  (let [offset (* (dec page-num) page-size)
        rows ((query-fn) :list-online-users
              {:ipaddr ipaddr
               :login_name login-name
               :page_size page-size
               :offset offset})
        total (:total ((query-fn) :count-online-users
                         {:ipaddr ipaddr
                          :login_name login-name}))]
    {:rows (mapv #(-> %
                      (assoc :user-id (:login_name %))
                      (assoc :user-name (:login_name %))
                      (assoc :login-ip (:ipaddr %))
                      (assoc :login-time (:start_timestamp %))
                      (assoc :last-access (:last_access_time %)))
                 rows)
     :total total}))

(defn force-logout!
  "强退指定在线用户。"
  [token]
  (when token
    (try
      ((query-fn) :delete-online-user! {:session_id token})
      (catch Exception e
        (log/warn e "Failed to force logout online user")))
    (when-let [claims (some-> token security/parse-token)]
      (blacklist! token (:exp claims))))
  {:success true})