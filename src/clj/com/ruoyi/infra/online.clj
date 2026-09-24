(ns com.ruoyi.infra.online
  "在线会话与令牌撤销.

  令牌 (JWT) 携带会话编号 jti 与签发毫秒 issued-ms. 会话记录在 sys_online (session_id = jti),
  用于在线用户列表与强退; 撤销记录持久化在 sys_token_revoke 并在内存中保留一份, 每次请求校验:
  - 按会话撤销 (退出, 强退): 键 jti:<jti>;
  - 按用户撤销 (停用, 删除, 重置密码, 本人改密): 键 user:<user_id>, 签发时间早于撤销时间的令牌全部失效.
  撤销记录在对应令牌自然过期后清理. 重启后从数据库重新加载, 因此强退不会因重启失效."
  (:require
    [clojure.tools.logging :as log]
    [com.ruoyi.infra.security :as security])
  (:import
    (java.util.concurrent
      ScheduledThreadPoolExecutor
      TimeUnit)))


;; ──────────── 全局状态 ────────────

(defonce ^:private query-fn-atom (atom nil))


(defonce ^:private revocations
  ;; 撤销键 -> 撤销时间 (毫秒)
  (atom {}))


(defonce ^:private last-heartbeat
  ;; jti -> 最近一次写库的毫秒时间, 用于限制心跳写入频率
  (atom {}))


(def idle-minutes
  "在线列表的活跃判定: 超过该分钟数未访问的会话从在线列表清理 (令牌本身仍按有效期与撤销判断)."
  30)


(def ^:private heartbeat-interval-ms 60000)


(defn- lifetime-ms
  []
  (* security/token-lifetime-hours 60 60 1000))


(defn- query-fn
  []
  @query-fn-atom)


(defn- run-query
  "执行查询; 未注入 query-fn (如纯单元测试) 时返回 nil."
  [k params]
  (when-let [q (query-fn)]
    (q k params)))


(defn- load-revocations!
  "从数据库加载未过期的撤销记录."
  []
  (try
    (run-query :delete-expired-token-revokes! {:now (System/currentTimeMillis)})
    (reset! revocations
            (into {} (map (juxt :revoke_key :revoked_at))
                  (or (run-query :list-token-revokes {}) [])))
    (catch Exception e
      (log/warn e "Failed to load token revocations"))))


(defn set-query-fn!
  "由 online-service 在系统启动时注入 query-fn, 并加载持久化的撤销记录."
  [query-fn]
  (reset! query-fn-atom query-fn)
  (reset! revocations {})
  (reset! last-heartbeat {})
  (when query-fn (load-revocations!)))


;; ──────────── 撤销 ────────────

(defn- revoke!
  [revoke-key]
  (let [now (System/currentTimeMillis)]
    (swap! revocations assoc revoke-key now)
    (try
      (run-query :delete-token-revoke! {:revoke_key revoke-key})
      (run-query :insert-token-revoke! {:revoke_key revoke-key :revoked_at now :expires_at (+ now (lifetime-ms))})
      (catch Exception e
        (log/warn e "Failed to persist token revocation" revoke-key)))
    now))


(defn revoked?
  "令牌声明是否已被撤销 (会话被退出/强退, 或签发早于该用户的撤销时间)."
  [claims]
  (let [r @revocations]
    (boolean
      (or (and (:jti claims) (contains? r (str "jti:" (:jti claims))))
          (when-let [at (get r (str "user:" (:user-id claims)))]
            (<= (or (:issued-ms claims) 0) at))))))


(defn valid-claims
  "解析令牌并返回未撤销的声明; 无效, 过期或已撤销返回 nil. 缺少会话编号的旧令牌视为无效."
  [token]
  (when-let [claims (some-> token security/parse-token)]
    (when (and (:jti claims) (not (revoked? claims)))
      claims)))


(defn revoke-user!
  "撤销该用户此前签发的全部令牌并清理其在线记录 (停用, 删除, 重置密码, 本人改密)."
  [user-id user-name]
  (when user-id
    (revoke! (str "user:" user-id))
    (when user-name
      (try
        (run-query :delete-online-users-by-name! {:login_name user-name})
        (catch Exception e
          (log/warn e "Failed to delete online sessions" user-name)))))
  nil)


;; ──────────── 清理调度 ────────────

(declare cleanup-expired-sessions!)


(defonce cleanup-executor
  (delay
    (doto (ScheduledThreadPoolExecutor. 1)
      (.scheduleAtFixedRate
        (reify Runnable
          (run
            [_]
            (try
              (cleanup-expired-sessions!)
              (catch Exception e
                (log/warn e "Online user cleanup failed")))))
        5 5 TimeUnit/MINUTES))))


;; ──────────── 会话 ────────────

(defn register!
  "登录成功后登记在线会话 (session_id 为令牌的会话编号)."
  [token user-name login-ip]
  (when-let [claims (some-> token security/parse-token)]
    (let [now (System/currentTimeMillis)]
      (try
        (run-query :create-online-user!
                   {:session_id (:jti claims)
                    :login_name user-name
                    :dept_name ""
                    :ipaddr (or login-ip "127.0.0.1")
                    :login_location ""
                    :browser ""
                    :os ""
                    :status "on_line"
                    :start_timestamp now
                    :last_access_time now
                    :expire_time (* idle-minutes 60 1000)})
        (swap! last-heartbeat assoc (:jti claims) now)
        (catch Exception e
          (log/warn e "Failed to register online user")))))
  (when (query-fn) (force cleanup-executor))
  nil)


(defn heartbeat!
  "更新会话最后访问时间; 同一会话 60 秒内只写一次库. 会话记录已被清理 (长时间空闲) 时重新登记."
  [claims]
  (when-let [jti (:jti claims)]
    (let [now (System/currentTimeMillis)
          last (get @last-heartbeat jti 0)]
      (when (and (query-fn) (> (- now last) heartbeat-interval-ms))
        (swap! last-heartbeat assoc jti now)
        (try
          (let [updated (run-query :update-online-user!
                                   {:session_id jti :last_access_time now :status nil :expire_time nil})]
            (when (and (number? updated) (zero? updated))
              (run-query :create-online-user!
                         {:session_id jti :login_name (str (:user-name claims)) :dept_name "" :ipaddr ""
                          :login_location "" :browser "" :os "" :status "on_line"
                          :start_timestamp now :last_access_time now :expire_time (* idle-minutes 60 1000)})))
          (catch Exception e
            (log/warn e "Failed to update online user heartbeat"))))))
  nil)


(defn unregister!
  "用户主动退出: 撤销当前会话并删除在线记录."
  [token]
  (when-let [claims (some-> token security/parse-token)]
    (when-let [jti (:jti claims)]
      (revoke! (str "jti:" jti))
      (swap! last-heartbeat dissoc jti)
      (try
        (run-query :delete-online-user! {:session_id jti})
        (catch Exception e
          (log/warn e "Failed to unregister online user")))))
  nil)


(defn force-logout!
  "强退指定会话 (参数为会话编号), 该会话令牌立即失效, 重启后仍然有效."
  [token-id]
  (when (seq (str token-id))
    (revoke! (str "jti:" token-id))
    (swap! last-heartbeat dissoc token-id)
    (try
      (run-query :delete-online-user! {:session_id token-id})
      (catch Exception e
        (log/warn e "Failed to force logout online user"))))
  {:success true})


(defn cleanup-expired-sessions!
  "清理长时间未访问的在线记录与已过期的撤销记录."
  []
  (let [now (System/currentTimeMillis)
        threshold (- now (* idle-minutes 60 1000))]
    (doseq [s (->> (or (run-query :list-online-users {:ipaddr nil :login_name nil :page_size 10000 :offset 0}) [])
                   (filter #(< (or (:last_access_time %) 0) threshold)))]
      (try
        (run-query :delete-online-user! {:session_id (:session_id s)})
        (catch Exception e
          (log/warn e "Failed to delete expired session"))))
    (try
      (run-query :delete-expired-token-revokes! {:now now})
      (catch Exception e
        (log/warn e "Failed to delete expired revocations")))
    (let [expire-before (- now (lifetime-ms))]
      (swap! revocations #(into {} (remove (fn [[_ at]] (< at expire-before))) %))
      (swap! last-heartbeat #(into {} (remove (fn [[_ at]] (< at threshold))) %))))
  nil)


(defn list-online
  "获取在线会话列表. 只返回会话编号, 不返回令牌."
  [& {:keys [login-name ipaddr page-num page-size]
      :or   {page-num 1 page-size 10}}]
  (let [offset (* (dec page-num) page-size)
        rows (or (run-query :list-online-users
                            {:ipaddr ipaddr
                             :login_name login-name
                             :page_size page-size
                             :offset offset}) [])
        total (:total (run-query :count-online-users
                                 {:ipaddr ipaddr
                                  :login_name login-name}))]
    {:rows (mapv #(-> %
                      (assoc :token-id (:session_id %))
                      (assoc :user-name (:login_name %))
                      (assoc :login-ip (:ipaddr %))
                      (assoc :login-time (:start_timestamp %))
                      (assoc :last-access (:last_access_time %)))
                 rows)
     :total (or total 0)}))
