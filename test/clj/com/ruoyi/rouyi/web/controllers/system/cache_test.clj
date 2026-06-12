(ns com.ruoyi.rouyi.web.controllers.system.cache-test
  "缓存控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.cache :as cache]))

(deftest test-cache-info
  (testing "获取缓存信息"
    (let [response (cache/cache-info {})]
      (is (map? response)))))

(deftest test-cache-names
  (testing "获取缓存名称列表"
    (let [response (cache/cache-names {})]
      (is (map? response)))))
