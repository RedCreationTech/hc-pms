(ns com.ruoyi.rouyi.web.controllers.monitor-test
  "监控控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.monitor :as monitor]))

(deftest test-server-info
  (testing "获取服务器信息"
    (let [response (monitor/server-info {})]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-datasource-info
  (testing "获取数据源信息"
    (let [response (monitor/datasource-info {})]
      (is (map? response))
      (is (= 200 (:status response))))))
