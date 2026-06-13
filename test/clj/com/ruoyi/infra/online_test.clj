(ns com.ruoyi.infra.online-test
  "在线用户管理测试。"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.infra.online :as online]
            [com.ruoyi.infra.security :as security]))

(use-fixtures :each
  (fn [f]
    (reset! @#'online/token-blacklist {})
    (reset! @#'online/query-fn-atom nil)
    (f)
    (reset! @#'online/token-blacklist {})
    (reset! @#'online/query-fn-atom nil)))

(defn- make-mock-query-fn
  "返回 [query-fn calls-atom]。query-fn 根据 query key 返回预设值，
   并将每次调用记录到 calls-atom。"
  [& {:keys [list-return count-return]
      :or {list-return [] count-return {:total 0}}}]
  (let [calls (atom [])]
    [(fn [q params]
       (swap! calls conj [q params])
       (case q
         :create-online-user! nil
         :update-online-user! nil
         :delete-online-user! nil
         :list-online-users list-return
         :count-online-users count-return
         nil))
     calls]))

(deftest test-set-query-fn!
  (testing "注入 query-fn 后查询生效"
    (let [[mock-fn calls] (make-mock-query-fn :count-return {:total 0})]
      (online/set-query-fn! mock-fn)
      (online/list-online)
      (is (= 1 (count (filter #(= :list-online-users (first %)) @calls))))
      (is (= 1 (count (filter #(= :count-online-users (first %)) @calls)))))))

(deftest test-blacklist-and-check
  (testing "令牌加入黑名单并可查询"
    (is (not (online/blacklisted? "token-1")))
    (online/blacklist! "token-1" (+ (System/currentTimeMillis) 10000))
    (is (online/blacklisted? "token-1"))
    (is (not (online/blacklisted? "token-2")))))

(deftest test-cleanup-blacklist!
  (testing "清理过期黑名单，保留未过期项"
    (let [now (System/currentTimeMillis)]
      (online/blacklist! "expired" (- now 1000))
      (online/blacklist! "valid" (+ now 10000))
      (online/cleanup-blacklist!)
      (is (not (online/blacklisted? "expired")))
      (is (online/blacklisted? "valid")))))

(deftest test-register!
  (testing "注册在线用户写入数据库"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      ;; 避免测试启动真实调度线程
      (with-redefs [online/cleanup-executor (delay nil)]
        (is (nil? (online/register! "session-1" "admin" "192.168.1.1")))
        (is (= 1 (count (filter #(= :create-online-user! (first %)) @calls))))
        (let [[_ params] (first (filter #(= :create-online-user! (first %)) @calls))]
          (is (= "session-1" (:session_id params)))
          (is (= "admin" (:login_name params)))
          (is (= "192.168.1.1" (:ipaddr params)))
          (is (= "on_line" (:status params)))
          (is (number? (:start_timestamp params)))
          (is (number? (:last_access_time params)))
          (is (number? (:expire_time params))))))))

(deftest test-heartbeat!
  (testing "更新心跳时间"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      (is (nil? (online/heartbeat! "session-1")))
      (is (= 1 (count (filter #(= :update-online-user! (first %)) @calls))))
      (let [[_ params] (first (filter #(= :update-online-user! (first %)) @calls))]
        (is (= "session-1" (:session_id params)))
        (is (number? (:last_access_time params)))
        (is (nil? (:status params)))
        (is (nil? (:expire_time params)))))))

(deftest test-heartbeat-nil-token
  (testing "nil token 不触发数据库操作"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      (is (nil? (online/heartbeat! nil)))
      (is (empty? @calls)))))

(deftest test-unregister!
  (testing "注销在线用户并将有效令牌加入黑名单"
    (let [[mock-fn calls] (make-mock-query-fn)
          token (security/generate-token 1 "admin" ["admin"] :exp-hours 24)]
      (online/set-query-fn! mock-fn)
      (is (nil? (online/unregister! token)))
      (is (= 1 (count (filter #(= :delete-online-user! (first %)) @calls))))
      (is (online/blacklisted? token)))))

(deftest test-unregister-invalid-token
  (testing "无效 token 只删除记录，不加黑名单"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      (is (nil? (online/unregister! "invalid-token")))
      (is (= 1 (count (filter #(= :delete-online-user! (first %)) @calls))))
      (is (not (online/blacklisted? "invalid-token"))))))

(deftest test-unregister-nil-token
  (testing "nil token 不触发任何操作"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      (is (nil? (online/unregister! nil)))
      (is (empty? @calls))
      (is (not (online/blacklisted? nil))))))

(deftest test-cleanup-expired-sessions!
  (testing "清理超过过期阈值的会话"
    (let [now (System/currentTimeMillis)
          threshold (- now (* 30 60 1000))
          expired {:session_id "expired" :last_access_time (- threshold 1000)}
          active {:session_id "active" :last_access_time (+ threshold 1000)}
          [mock-fn calls] (make-mock-query-fn :list-return [expired active])]
      (online/set-query-fn! mock-fn)
      (is (nil? (online/cleanup-expired-sessions!)))
      ;; 一次 __cleanup__ 占位删除 + 一条过期记录删除
      (is (= 2 (count (filter #(= :delete-online-user! (first %)) @calls))))
      (let [deleted-ids (->> @calls
                             (filter #(= :delete-online-user! (first %)))
                             (map #(get-in % [1 :session_id]))
                             set)]
        (is (contains? deleted-ids "expired"))
        (is (not (contains? deleted-ids "active")))))))

(deftest test-list-online
  (testing "查询在线用户列表并映射字段"
    (let [row {:session_id "s1"
               :login_name "admin"
               :ipaddr "127.0.0.1"
               :start_timestamp 1000
               :last_access_time 2000}
          [mock-fn calls] (make-mock-query-fn
                            :list-return [row]
                            :count-return {:total 1})]
      (online/set-query-fn! mock-fn)
      (let [result (online/list-online
                     :login-name "admin"
                     :ipaddr "127"
                     :page-num 1
                     :page-size 10)]
        (is (= 1 (:total result)))
        (is (= 1 (count (:rows result))))
        (let [mapped (first (:rows result))]
          (is (= "s1" (:token-id mapped)))
          (is (= "s1" (:token mapped)))
          (is (= "admin" (:user-id mapped)))
          (is (= "admin" (:user-name mapped)))
          (is (= "127.0.0.1" (:login-ip mapped)))
          (is (= 1000 (:login-time mapped)))
          (is (= 2000 (:last-access mapped))))
        (let [[_ list-params] (first (filter #(= :list-online-users (first %)) @calls))
              [_ count-params] (first (filter #(= :count-online-users (first %)) @calls))]
          (is (= "admin" (:login_name list-params)))
          (is (= "127" (:ipaddr list-params)))
          (is (= 10 (:page_size list-params)))
          (is (= 0 (:offset list-params)))
          (is (= "admin" (:login_name count-params)))
          (is (= "127" (:ipaddr count-params))))))))

(deftest test-force-logout!
  (testing "强退用户并黑名单令牌"
    (let [[mock-fn calls] (make-mock-query-fn)
          token (security/generate-token 1 "admin" ["admin"] :exp-hours 24)]
      (online/set-query-fn! mock-fn)
      (is (= {:success true} (online/force-logout! token)))
      (is (= 1 (count (filter #(= :delete-online-user! (first %)) @calls))))
      (is (online/blacklisted? token)))))

(deftest test-force-logout-invalid-token
  (testing "强退无效 token 只删除记录"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      (is (= {:success true} (online/force-logout! "invalid-token")))
      (is (= 1 (count (filter #(= :delete-online-user! (first %)) @calls))))
      (is (not (online/blacklisted? "invalid-token"))))))

(deftest test-force-logout-nil-token
  (testing "nil token 强退无操作但返回成功"
    (let [[mock-fn calls] (make-mock-query-fn)]
      (online/set-query-fn! mock-fn)
      (is (= {:success true} (online/force-logout! nil)))
      (is (empty? @calls)))))
