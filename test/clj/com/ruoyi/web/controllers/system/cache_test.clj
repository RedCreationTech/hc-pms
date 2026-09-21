(ns com.ruoyi.web.controllers.system.cache-test
  "缓存监控控制器测试."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.web.controllers.system.cache :as cache]))


(use-fixtures :each
  (fn [test-fn]
    (cache/clear-cache-all {} {})
    (test-fn)
    (cache/clear-cache-all {} {})))


(deftest test-cache-info
  (testing "获取缓存整体信息"
    (let [response (cache/cache-info {} {})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "Memory Cache" (get-in body [:data :name])))
      (is (pos? (get-in body [:data :keysCount])))
      (is (seq (get-in body [:data :commandStats])))
      (is (seq (get-in body [:data :cacheNames]))))))


(deftest test-cache-names
  (testing "获取缓存名称列表"
    (let [response (cache/cache-names {} {})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (seq (get-in body [:data :cacheNames])))
      (is (some #{"user" "dict" "config" "notice"} (get-in body [:data :cacheNames]))))))


(deftest test-cache-keys
  (testing "获取所有缓存键"
    (let [response (cache/cache-keys {} {})
          body (:body response)
          keys (get-in body [:data :keys])]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (seq keys))
      (is (every? string? keys))
      (is (= (count keys) (get-in body [:data :count]))))))


(deftest test-cache-keys-by-name
  (testing "获取指定缓存名称下的键列表"
    (let [response (cache/cache-keys-by-name {} {:path-params {:cacheName "dict"}})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "dict" (get-in body [:data :cacheName])))
      (is (seq (get-in body [:data :keys])))
      (is (= (count (get-in body [:data :keys])) (get-in body [:data :count])))))
  (testing "获取不存在的缓存名称下的键列表"
    (let [response (cache/cache-keys-by-name {} {:path-params {:cacheName "unknown"}})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "unknown" (get-in body [:data :cacheName])))
      (is (empty? (get-in body [:data :keys])))
      (is (zero? (get-in body [:data :count]))))))


(deftest test-cache-value
  (testing "获取存在的缓存值"
    (let [before @cache/cache-stats
          response (cache/cache-value {} {:path-params {:cacheName "user" :cacheKey "admin"}})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "user" (get-in body [:data :cacheName])))
      (is (= "admin" (get-in body [:data :cacheKey])))
      (is (seq (get-in body [:data :value])))
      (is (= (inc (:get before)) (:get @cache/cache-stats)))
      (is (= (inc (:hit before)) (:hit @cache/cache-stats)))))
  (testing "获取不存在的缓存值"
    (let [before @cache/cache-stats
          response (cache/cache-value {} {:path-params {:cacheName "user" :cacheKey "not-exist"}})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "" (get-in body [:data :value])))
      (is (= (inc (:get before)) (:get @cache/cache-stats)))
      (is (= (inc (:miss before)) (:miss @cache/cache-stats))))))


(deftest test-clear-cache
  (testing "清空所有缓存"
    (let [response (cache/clear-cache {} {})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "操作成功" (:msg body)))
      (is (= "缓存已清空" (get-in body [:data])))
      (is (= 1 (:clear @cache/cache-stats)))
      (is (seq (get @cache/cache-data "user")))
      (is (seq (get @cache/cache-data "dict")))
      (is (seq (get @cache/cache-data "config")))
      (is (seq (get @cache/cache-data "notice"))))))


(deftest test-clear-cache-name
  (testing "清除指定名称的缓存"
    (let [response (cache/clear-cache-name {} {:path-params {:cacheName "dict"}})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "缓存已清空" (get-in body [:data])))
      (is (= 1 (:clear @cache/cache-stats)))
      (is (empty? (get @cache/cache-data "dict")))
      (is (seq (get @cache/cache-data "user"))))))


(deftest test-clear-cache-key
  (testing "清除指定键"
    (let [response (cache/clear-cache-key {} {:path-params {:cacheName "user" :cacheKey "admin"}})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "缓存键已清除" (get-in body [:data])))
      (is (= 1 (:clear @cache/cache-stats)))
      (is (nil? (get-in @cache/cache-data ["user" "admin"])))
      (is (seq (get @cache/cache-data "dict"))))))


(deftest test-clear-cache-all
  (testing "清除所有缓存并重置统计"
    (swap! cache/cache-stats assoc :get 10 :hit 5 :miss 3)
    (let [response (cache/clear-cache-all {} {})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "所有缓存已清空" (get-in body [:data])))
      (is (seq (get @cache/cache-data "user")))
      (is (seq (get @cache/cache-data "dict")))
      (is (seq (get @cache/cache-data "config")))
      (is (seq (get @cache/cache-data "notice")))
      (is (= {:get 0 :hit 0 :miss 0 :clear 0} @cache/cache-stats)))))
