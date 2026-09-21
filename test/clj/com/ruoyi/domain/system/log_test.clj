(ns com.ruoyi.domain.system.log-test
  "日志领域服务测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.system.log :as log]))


(def mock-oper-logs
  [{:oper_id 1 :title "用户管理" :oper_name "管理员" :status 0}
   {:oper_id 2 :title "角色管理" :oper_name "管理员" :status 0}])


(def mock-login-logs
  [{:info_id 1 :user_name "admin" :ipaddr "127.0.0.1" :status 0 :msg "登录成功"}
   {:info_id 2 :user_name "user1" :ipaddr "192.168.1.1" :status 0 :msg "登录成功"}])


(defn- mock-query-fn
  [query-name params]
  (case query-name
    :list-oper-logs mock-oper-logs
    :count-oper-logs {:total 2}
    :list-login-logs mock-login-logs
    :count-login-logs {:total 2}
    :create-oper-log! nil
    :create-login-log! nil
    :clear-oper-logs! nil
    :clear-login-logs! nil
    :list-online-users [{:session_id "1" :user_name "admin"}]
    :count-online-users {:total 1}
    :create-online-user! nil
    :update-online-user! nil
    :delete-online-user! nil
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


(deftest test-create-oper-log
  (testing "创建操作日志"
    (is (nil? (log/create-oper-log! mock-service {:title "测试" :method "test"})))))


(deftest test-create-login-log
  (testing "创建登录日志"
    (is (nil? (log/create-login-log! mock-service {:user_name "test" :ipaddr "127.0.0.1"})))))


(deftest test-clear-oper-logs
  (testing "清空操作日志"
    (is (nil? (log/clear-oper-logs! mock-service {})))))


(deftest test-clear-login-logs
  (testing "清空登录日志"
    (is (nil? (log/clear-login-logs! mock-service {})))))


(deftest test-list-online-users
  (testing "查询在线用户列表"
    (let [result (log/list-online-users mock-service {})]
      (is (map? result))
      (is (= 1 (:total result))))))


(deftest test-create-online-user
  (testing "创建在线用户记录"
    (is (nil? (log/create-online-user! mock-service {:session_id "1" :user_name "admin"})))))


(deftest test-update-online-user
  (testing "更新在线用户访问时间"
    (is (nil? (log/update-online-user! mock-service {:session_id "1" :access_time "2026-01-01"})))))


(deftest test-delete-online-user
  (testing "踢出在线用户"
    (is (nil? (log/delete-online-user! mock-service "1")))))
