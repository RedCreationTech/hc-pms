(ns com.ruoyi.rouyi.infra.online-test
  "在线用户管理测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.infra.online :as online]))

(deftest test-register-and-list
  (testing "注册和获取在线用户"
    (let [token "test-token-123"
          user-info {:user_id 1 :user_name "admin" :ip "127.0.0.1"}]
      (online/register! token user-info)
      (let [users (online/list-online)]
        (is (seq users)))
      (online/unregister! token))))

(deftest test-unregister
  (testing "注销在线用户"
    (let [token "test-token-456"
          user-info {:user_id 2 :user_name "user1" :ip "192.168.1.1"}]
      (online/register! token user-info)
      (online/unregister! token)
      (let [users (online/list-online)]
        (is (empty? users))))))

(deftest test-force-logout
  (testing "强制登出"
    (let [token "test-token-789"
          user-info {:user_id 3 :user_name "user2" :ip "10.0.0.1"}]
      (online/register! token user-info)
      (let [result (online/force-logout! token)]
        (is (true? result))))))

(deftest test-list-online-empty
  (testing "获取空在线用户列表"
    (let [users (online/list-online)]
      (is (vector? users)))))

(deftest test-heartbeat
  (testing "心跳"
    (let [token "test-token-heartbeat"
          user-info {:user_id 4 :user_name "user3" :ip "192.168.1.2"}]
      (online/register! token user-info)
      (online/heartbeat! token)
      (online/unregister! token))))
