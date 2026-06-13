(ns com.ruoyi.infra.security-test
  "安全基础设施测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.infra.security :as security]))

(deftest test-password-hashing
  (testing "密码哈希"
    (let [raw-password "test123"
          hashed (security/hash-password raw-password)]
      (is (string? hashed))
      (is (not= raw-password hashed))
      (is (security/verify-password raw-password hashed)))))

(deftest test-password-verification-failure
  (testing "密码验证失败"
    (let [hashed (security/hash-password "test123")]
      (is (not (security/verify-password "wrong" hashed))))))

(deftest test-extract-token
  (testing "从请求中提取 Token"
    (let [request {:headers {"authorization" "Bearer test-token-123"}}
          token (security/extract-token request)]
      (is (= "test-token-123" token)))))
