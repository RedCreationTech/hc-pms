(ns com.ruoyi.infra.cache-test
  "缓存基础设施测试。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.cache :as cache]))


(deftest test-cache-store-exists
  (testing "缓存存储存在"
    (is (some? cache/cache-store))
    (is (instance? clojure.lang.Atom cache/cache-store))))
