(ns com.ruoyi.rouyi.domain.system.log-test
  "日志领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.log :as log]))

(def mock-oper-logs
  [{:oper_id 1 :title "用户管理" :oper_name "管理员" :status 0}
   {:oper_id 2 :title "角色管理" :oper_name "管理员" :status 0}])

(def mock-login-logs
  [{:info_id 1 :user_name "admin" :ipaddr "127.0.0.1" :status 0 :msg "登录成功"}
   {:info_id 2 :user_name "user1" :ipaddr "192.168.1.1" :status 0 :msg "登录成功"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-oper-logs mock-oper-logs
    :count-oper-logs {:total 2}
    :list-login-logs mock-login-logs
    :count-login-logs {:total 2}
    :create-oper-log! nil
    :create-login-log! nil
    :clear-oper-logs! nil
    :clear-login-logs! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-oper-logs
  (testing "查询操作日志列表"
    (let [result (log/list-oper-logs mock-service {})]
      (is (map? result))
      (is (= 2 (:total result))))))

(deftest test-list-login-logs
  (testing "查询登录日志列表"
    (let [result (log/list-login-logs mock-service {})]
      (is (map? result))
      (is (= 2 (:total result))))))

(deftest test-create-oper-log!
  (testing "创建操作日志"
    (is (nil? (log/create-oper-log! mock-service {:title "测试"})))))

(deftest test-create-login-log!
  (testing "创建登录日志"
    (is (nil? (log/create-login-log! mock-service {:user_name "test"})))))
