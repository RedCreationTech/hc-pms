(ns com.ruoyi.web.middleware.auth-test
  "认证与授权中间件测试。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.middleware.auth :as auth]))


(deftest test-wrap-jwt-auth-with-valid-token
  (testing "合法 Token 附加 identity 并更新心跳"
    (let [calls (atom [])]
      (with-redefs [security/extract-token (fn [_] "valid-token")
                    security/parse-token (fn [_] {:user-id 1 :user-name "admin"})
                    online/blacklisted? (fn [_] false)
                    online/heartbeat! (fn [token] (swap! calls conj [:heartbeat token]))]
        (let [handler (auth/wrap-jwt-auth (fn [req] {:identity (:identity req)}))
              response (handler {:headers {"authorization" "Bearer valid-token"}})]
          (is (= {:user-id 1 :user-name "admin"} (:identity response)))
          (is (= [[:heartbeat "valid-token"]] @calls)))))))


(deftest test-wrap-jwt-auth-with-blacklisted-token
  (testing "黑名单 Token 不附加 identity 且不更新心跳"
    (let [calls (atom [])]
      (with-redefs [security/extract-token (fn [_] "blacklisted-token")
                    security/parse-token (fn [_] {:user-id 1})
                    online/blacklisted? (fn [_] true)
                    online/heartbeat! (fn [token] (swap! calls conj [:heartbeat token]))]
        (let [handler (auth/wrap-jwt-auth (fn [req] {:identity (:identity req)}))
              response (handler {})]
          (is (nil? (:identity response)))
          (is (empty? @calls)))))))


(deftest test-wrap-jwt-auth-with-invalid-token
  (testing "无效 Token 不附加 identity"
    (with-redefs [security/extract-token (fn [_] "invalid-token")
                  security/parse-token (fn [_] nil)
                  online/blacklisted? (fn [_] false)
                  online/heartbeat! (fn [_] (throw (Exception. "不应调用")))]
      (let [handler (auth/wrap-jwt-auth (fn [req] {:identity (:identity req)}))
            response (handler {})]
        (is (nil? (:identity response)))))))


(deftest test-wrap-jwt-auth-without-token
  (testing "无 Token 时不附加 identity"
    (with-redefs [security/extract-token (fn [_] nil)
                  security/parse-token (fn [_] (throw (Exception. "不应调用")))
                  online/blacklisted? (fn [_] (throw (Exception. "不应调用")))
                  online/heartbeat! (fn [_] (throw (Exception. "不应调用")))]
      (let [handler (auth/wrap-jwt-auth (fn [req] {:identity (:identity req)}))
            response (handler {})]
        (is (nil? (:identity response)))))))


(deftest test-require-auth-authorized
  (testing "已认证请求放行"
    (let [handler (auth/require-auth (fn [req] {:ok true}))
          response (handler {:identity {:user-id 1}})]
      (is (= {:ok true} response)))))


(deftest test-require-auth-unauthorized
  (testing "未认证请求返回 401"
    (let [handler (auth/require-auth (fn [req] {:ok true}))
          response (handler {})]
      (is (= 401 (:status response)))
      (is (= {:code 401 :msg "未登录或令牌已过期"} (:body response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"]))))))


(deftest test-require-perms-allowed
  (testing "拥有任一所需权限时放行"
    (let [handler ((auth/require-perms ["system:user:list" "system:user:add"])
                   (fn [req] {:ok true}))
          response (handler {:identity {:perms #{"system:user:add"}}})]
      (is (= {:ok true} response)))))


(deftest test-require-perms-denied
  (testing "无所需权限时返回 403"
    (let [handler ((auth/require-perms ["system:user:list"])
                   (fn [req] {:ok true}))
          response (handler {:identity {:perms #{"system:user:add"}}})]
      (is (= 403 (:status response)))
      (is (= {:code 403 :msg "没有操作权限"} (:body response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"]))))))


(deftest test-require-perms-no-identity
  (testing "无身份时返回 403"
    (let [handler ((auth/require-perms ["system:user:list"])
                   (fn [req] {:ok true}))
          response (handler {})]
      (is (= 403 (:status response)))
      (is (= {:code 403 :msg "没有操作权限"} (:body response))))))


(deftest test-require-perms-single-string
  (testing "单个字符串权限也正常工作"
    (let [handler ((auth/require-perms "system:user:list")
                   (fn [req] {:ok true}))
          response (handler {:identity {:perms #{"system:user:list"}}})]
      (is (= {:ok true} response)))))


(deftest test-auth-middleware-combined
  (testing "组合中间件：JWT + 认证 + 权限"
    (let [calls (atom [])]
      (with-redefs [security/extract-token (fn [_] "token")
                    security/parse-token (fn [_] {:user-id 1 :perms ["system:user:list"]})
                    online/blacklisted? (fn [_] false)
                    online/heartbeat! (fn [token] (swap! calls conj [:heartbeat token]))]
        (let [middleware (auth/auth-middleware {:required? true
                                                :perms ["system:user:list"]})
              handler (middleware (fn [req] {:identity (:identity req)}))
              response (handler {})]
          (is (= {:user-id 1 :perms ["system:user:list"]} (:identity response)))
          (is (= [[:heartbeat "token"]] @calls)))))))


(deftest test-auth-middleware-optional-auth
  (testing "组合中间件：不强制认证时未登录也能访问"
    (with-redefs [security/extract-token (fn [_] nil)
                  security/parse-token (fn [_] nil)
                  online/blacklisted? (fn [_] false)
                  online/heartbeat! (fn [_] nil)]
      (let [middleware (auth/auth-middleware {})
            handler (middleware (fn [req] {:ok true}))
            response (handler {})]
        (is (= {:ok true} response))))))
