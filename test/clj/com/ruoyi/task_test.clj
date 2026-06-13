(ns com.ruoyi.task-test
  "定时任务示例函数测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.task :as task]))

(deftest test-ry-no-params
  (testing "无参示例任务"
    (is (nil? (task/ry-no-params)))))

(deftest test-ry-params
  (testing "带字符串参数示例任务"
    (is (nil? (task/ry-params "hello")))))
