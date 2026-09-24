(ns com.ruoyi.infra.login-guard
  "登录失败锁定 (对应若依 user.password.maxRetryCount / lockTime): 同一账号连续 5 次用户名或密码错误后锁定 10 分钟.
  状态保存在内存 (单节点); 登录日志页可手动解锁.")


(def max-retry-count 5)
(def lock-minutes 10)


(defonce ^:private state
  ;; user-name -> {:count n :locked-until ms}
  (atom {}))


(defn locked?
  "账号当前是否处于锁定期."
  [user-name]
  (let [{:keys [locked-until]} (get @state user-name)]
    (boolean (and locked-until (< (System/currentTimeMillis) locked-until)))))


(defn record-failure!
  "记录一次失败; 达到上限时锁定. 返回是否因此被锁定."
  [user-name]
  (let [now (System/currentTimeMillis)
        updated (swap! state update user-name
                       (fn [{:keys [count locked-until] :as entry}]
                         (let [expired? (and locked-until (>= now locked-until))
                               n (inc (if expired? 0 (or count 0)))]
                           (cond-> {:count n}
                             (>= n max-retry-count) (assoc :locked-until (+ now (* lock-minutes 60 1000)))))))]
    (boolean (get-in updated [user-name :locked-until]))))


(defn clear!
  "登录成功或管理员解锁后清除失败计数."
  [user-name]
  (swap! state dissoc user-name)
  nil)


(defn lock-message
  []
  (str "密码输入错误" max-retry-count "次, 账户锁定" lock-minutes "分钟"))
