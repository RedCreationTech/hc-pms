(ns com.ruoyi.web.controllers.system.log-test
  "日志审计控制器测试。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.system.log :as log]))


(def mock-log-service
  {:query-fn (fn [q _p]
               (case q
                 :list-oper-logs [{:oper_id 1 :title "测试"}]
                 :count-oper-logs {:total 1}
                 :list-login-logs [{:info_id 1 :user_name "admin"}]
                 :count-login-logs {:total 1}
                 :list-online-users [{:session_id "1" :user_name "admin"}]
                 :count-online-users {:total 1}
                 :clear-oper-logs! nil
                 :clear-login-logs! nil
                 :delete-online-user! nil
                 []))})


(deftest test-list-oper-logs
  (testing "查询操作日志列表"
    (let [request {:query-params {}}
          response (log/list-oper-logs {:log-service mock-log-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= 1 (:total (:data body))))
      (is (seq (:rows (:data body)))))))


(deftest test-clear-oper-logs
  (testing "清空操作日志"
    (let [request {:query-params {}}
          response (log/clear-oper-logs {:log-service mock-log-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= "清空成功" (:data body))))))


(deftest test-list-login-logs
  (testing "查询登录日志列表"
    (let [request {:query-params {}}
          response (log/list-login-logs {:log-service mock-log-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 1 (:total (:data body)))))))


(deftest test-clear-login-logs
  (testing "清空登录日志"
    (let [request {:query-params {}}
          response (log/clear-login-logs {:log-service mock-log-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= "清空成功" (:data body))))))


(deftest test-list-online-users
  (testing "查询在线用户列表"
    (let [request {:query-params {}}
          response (log/list-online-users {:log-service mock-log-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 1 (:total (:data body)))))))


(deftest test-kick-online-user
  (testing "强退在线用户"
    (let [request {:path-params {:id "1"}}
          response (log/kick-online-user {:log-service mock-log-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= "强退成功" (:data body))))))
