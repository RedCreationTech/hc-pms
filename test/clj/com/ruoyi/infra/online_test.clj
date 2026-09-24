(ns com.ruoyi.infra.online-test
  "在线会话与令牌撤销测试 (模拟 query-fn)."
  (:require
    [buddy.sign.jwt]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]))


(defn- make-mock-query-fn
  "返回 [query-fn calls-atom store-atom]; store 模拟 sys_token_revoke 表."
  [& {:keys [list-return count-return update-return]
      :or {list-return [] count-return {:total 0} update-return 1}}]
  (let [calls (atom [])
        store (atom {})]
    [(fn [q params]
       (swap! calls conj [q params])
       (case q
         :create-online-user! 1
         :update-online-user! update-return
         :delete-online-user! 1
         :delete-online-users-by-name! 1
         :list-online-users list-return
         :count-online-users count-return
         :insert-token-revoke! (do (swap! store assoc (:revoke_key params) params) 1)
         :delete-token-revoke! (do (swap! store dissoc (:revoke_key params)) 1)
         :delete-expired-token-revokes! (do (swap! store #(into {} (remove (fn [[_ v]] (< (:expires_at v) (:now params)))) %)) 1)
         :list-token-revokes (vec (vals @store))
         nil))
     calls
     store]))


(use-fixtures :each
  (fn [f]
    (with-redefs [online/cleanup-executor (delay nil)]
      (online/set-query-fn! nil)
      (f)
      (online/set-query-fn! nil))))


(defn- calls-of
  [calls k]
  (filter #(= k (first %)) @calls))


(deftest valid-claims-and-legacy-tokens
  (testing "未注入 query-fn 时不做撤销检查, 有效令牌返回声明"
    (let [token (security/generate-token 7 "u7" [2])
          claims (online/valid-claims token)]
      (is (= 7 (:user-id claims)))
      (is (string? (:jti claims)))))
  (testing "无效或缺少会话编号的令牌无效"
    (is (nil? (online/valid-claims nil)))
    (is (nil? (online/valid-claims "not-a-token")))
    (is (nil? (online/valid-claims (buddy.sign.jwt/sign {:user-id 1 :exp (+ (quot (System/currentTimeMillis) 1000) 60)}
                                                        security/secret-key {:alg :hs256}))))))


(deftest register-uses-session-id-not-token
  (let [[q calls] (make-mock-query-fn)
        token (security/generate-token 1 "admin" [1])]
    (online/set-query-fn! q)
    (online/register! token "admin" "10.0.0.1")
    (let [[_ params] (first (calls-of calls :create-online-user!))]
      (is (= (:jti (security/parse-token token)) (:session_id params)))
      (is (not= token (:session_id params)))
      (is (= "admin" (:login_name params)))
      (is (= "10.0.0.1" (:ipaddr params))))))


(deftest logout-and-force-logout-revoke-persistently
  (let [[q calls store] (make-mock-query-fn)
        token (security/generate-token 1 "admin" [1])
        other (security/generate-token 1 "admin" [1])
        jti (:jti (security/parse-token other))]
    (online/set-query-fn! q)
    (testing "退出撤销当前会话"
      (online/unregister! token)
      (is (nil? (online/valid-claims token)))
      (is (some? (online/valid-claims other)))
      (is (seq (calls-of calls :delete-online-user!))))
    (testing "强退按会话编号撤销, 并写入持久化存储"
      (online/force-logout! jti)
      (is (nil? (online/valid-claims other)))
      (is (contains? @store (str "jti:" jti))))
    (testing "重新加载 (模拟重启) 后撤销仍然有效"
      (online/set-query-fn! q)
      (is (nil? (online/valid-claims token)))
      (is (nil? (online/valid-claims other))))))


(deftest revoke-user-invalidates-earlier-tokens-only
  (let [[q calls] (make-mock-query-fn)
        before (security/generate-token 5 "u5" [2])]
    (online/set-query-fn! q)
    (Thread/sleep 5)
    (online/revoke-user! 5 "u5")
    (Thread/sleep 5)
    (let [after (security/generate-token 5 "u5" [2])]
      (is (nil? (online/valid-claims before)))
      (is (some? (online/valid-claims after)))
      (is (some? (online/valid-claims (security/generate-token 6 "u6" [2]))) "其他用户不受影响")
      (is (= [{:login_name "u5"}] (map second (calls-of calls :delete-online-users-by-name!)))))))


(deftest heartbeat-is-throttled-and-recreates-cleaned-session
  (let [[q calls] (make-mock-query-fn :update-return 0)
        claims (security/parse-token (security/generate-token 3 "u3" [2]))]
    (online/set-query-fn! q)
    (online/heartbeat! claims)
    (online/heartbeat! claims)
    (is (= 1 (count (calls-of calls :update-online-user!))) "60 秒内只写一次")
    (is (= 1 (count (calls-of calls :create-online-user!))) "记录已被清理时重新登记")))


(deftest list-online-exposes-session-id-only
  (let [[q] (make-mock-query-fn :list-return [{:session_id "abc" :login_name "admin" :ipaddr "1.1.1.1"
                                               :start_timestamp 1 :last_access_time 2}]
                                :count-return {:total 1})]
    (online/set-query-fn! q)
    (let [{:keys [rows total]} (online/list-online :page-num 1 :page-size 10)]
      (is (= 1 total))
      (is (= "abc" (:token-id (first rows))))
      (is (not (contains? (first rows) :token))))))


(deftest cleanup-removes-idle-sessions
  (let [now (System/currentTimeMillis)
        [q calls] (make-mock-query-fn :list-return [{:session_id "old" :last_access_time (- now (* 31 60 1000))}
                                                    {:session_id "new" :last_access_time now}])]
    (online/set-query-fn! q)
    (online/cleanup-expired-sessions!)
    (is (= [{:session_id "old"}] (map second (calls-of calls :delete-online-user!))))))
