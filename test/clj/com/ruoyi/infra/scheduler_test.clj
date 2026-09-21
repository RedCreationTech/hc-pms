(ns com.ruoyi.infra.scheduler-test
  "调度器公共函数测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.scheduler :as scheduler]))


(deftest test-invoke-target-allowed?
  (testing "允许合法命名空间内的目标"
    (is (scheduler/invoke-target-allowed? "com.ruoyi.task/ry-no-params"))
    (is (scheduler/invoke-target-allowed? "com.ruoyi.task/ry-params('hello')")))
  (testing "拒绝危险协议"
    (is (not (scheduler/invoke-target-allowed? "http://example.com")))
    (is (not (scheduler/invoke-target-allowed? "rmi://example.com")))
    (is (not (scheduler/invoke-target-allowed? "ldap://example.com")))
    (is (not (scheduler/invoke-target-allowed? "ldaps://example.com"))))
  (testing "拒绝非允许命名空间"
    (is (not (scheduler/invoke-target-allowed? "java.lang.System/exit")))
    (is (not (scheduler/invoke-target-allowed? "clojure.core/eval")))))
