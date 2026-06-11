(ns com.ruoyi.rouyi.infra.online
  "在线用户管理。
  
  使用原子缓存跟踪活跃 Token，定期清理过期会话。
  提供在线用户列表和强退功能。"
  (:require
    [clojure.tools.logging :as log])
  (:import
    [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

;; ──────────── 在线用户状态 ────────────

(defonce online-users
  "原子 map: token -> {:user-id :user-name :login-ip :login-time :last-access}"
  (atom {} {:validator map?}))

(defonce cleanup-executor
  (delay
    (doto (ScheduledThreadPoolExecutor. 1)
      (.scheduleAtFixedRate
        (reify Runnable
          (run [_]
            (try
              (cleanup-expired!)
              (catch Exception e
                (log/warn e "Online user cleanup failed")))))
        5 5 TimeUnit/MINUTES))))

;; ──────────── 核心 API ────────────

(defn register!
  "注册或刷新在线用户记录。"
  [token user-id user-name login-ip]
  (let [now (System/currentTimeMillis)]
    (swap! online-users assoc token
      {:user-id     user-id
       :user-name   user-name
       :login-ip    login-ip
       :login-time  now
       :last-access now}))
  (force cleanup-executor)
  nil)

(defn heartbeat!
  "更新用户最后访问时间。"
  [token]
  (when-let [entry (get @online-users token)]
    (swap! online-users assoc-in [token :last-access]
      (System/currentTimeMillis)))
  nil)

(defn unregister!
  "注销在线用户（用户主动退出或强退）。"
  [token]
  (swap! online-users dissoc token)
  nil)

(defn cleanup-expired!
  "清理超过 30 分钟无心跳的在线用户。"
  []
  (let [threshold (- (System/currentTimeMillis) (* 30 60 1000))]
    (swap! online-users
      (fn [users]
        (into {} (remove (fn [[_ v]] (< (:last-access v) threshold)) users)))))
  nil)

(defn list-online
  "获取在线用户列表，支持条件筛选。"
  [& {:keys [user-name login-ip page-num page-size]
      :or   {page-num 1 page-size 10}}]
  (let [all (->> @online-users
                 vals
                 (sort-by :last-access >)
                 (filter (fn [u]
                           (and (or (nil? user-name)
                                    (str/includes? (:user-name u) user-name))
                                (or (nil? login-ip)
                                    (str/includes? (:login-ip u) login-ip))))))
        total (count all)
        offset (* (dec page-num) page-size)
        rows  (->> all (drop offset) (take page-size) vec)]
    {:rows rows :total total}))

(defn force-logout!
  "强退指定在线用户。"
  [token]
  (let [user (:token-id token)]
    (swap! online-users dissoc token)
    {:success true}))
