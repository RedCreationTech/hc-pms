(ns com.ruoyi.rouyi.web.routes.utils-test
  "路由工具测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.routes.utils :as utils]))

(deftest test-route-data
  (testing "路由数据"
    (let [result (utils/route-data {})]
      (is (some? result)))))

(deftest test-route-data-key
  (testing "路由数据键"
    (let [result (utils/route-data-key {})]
      (is (some? result)))))
