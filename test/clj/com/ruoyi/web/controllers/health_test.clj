(ns com.ruoyi.web.controllers.health-test
  "健康检查控制器测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.health :as health]))


(deftest test-health-check
  (testing "健康检查返回成功"
    (let [response (health/healthcheck! {})]
      (is (map? response))
      (is (= 200 (:status response))))))
