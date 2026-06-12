(ns com.ruoyi.rouyi.web.controllers.system.log-test
  "日志控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.log :as log]))

(def mock-log-service
  {:query-fn (fn [q p] (case q
                          :list-oper-logs {:rows [{:oper_id 1 :title "test"}] :total 1}
                          :list-login-logs {:rows [{:info_id 1 :user_name "test"}] :total 1}
                          :create-oper-log! nil
                          :create-login-log! nil
                          :clear-oper-logs! nil
                          :clear-login-logs! nil
                          []))})

(deftest test-list-oper-logs
  (testing "查询操作日志列表"
    (let [request {:query-params {}}
          response (log/list-oper-logs {:log-service mock-log-service} request)]
      (is (map? response)))))

(deftest test-list-login-logs
  (testing "查询登录日志列表"
    (let [request {:query-params {}}
          response (log/list-login-logs {:log-service mock-log-service} request)]
      (is (map? response)))))

(deftest test-clear-oper-logs
  (testing "清空操作日志"
    (let [request {}
          response (log/clear-oper-logs {:log-service mock-log-service} request)]
      (is (map? response)))))

(deftest test-clear-login-logs
  (testing "清空登录日志"
    (let [request {}
          response (log/clear-login-logs {:log-service mock-log-service} request)]
      (is (map? response)))))
