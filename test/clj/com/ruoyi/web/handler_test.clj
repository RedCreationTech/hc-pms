(ns com.ruoyi.web.handler-test
  "Ring 处理器测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.handler :as handler]
    [integrant.core :as ig]))


(deftest test-spa-not-found-handler-api
  (testing "API 路径返回 404 纯文本"
    (let [response (@#'handler/spa-not-found-handler {:uri "/api/unknown"})]
      (is (= 404 (:status response)))
      (is (= "Not found" (:body response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"]))))))


(deftest test-spa-not-found-handler-spa
  (testing "非 API 路径返回 index.html"
    (let [response (@#'handler/spa-not-found-handler {:uri "/system/user"})]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (some? (:body response))))))


(deftest test-router-routes-init
  (testing "router/routes 支持函数路由和静态路由"
    (let [routes [["/api/health" {:get {:handler (fn [_] {:status 200})}}]
                  (fn [] ["/api/dynamic" {:get {:handler (fn [_] {:status 200})}}])]
          result (ig/init-key :router/routes {:routes routes})]
      (is (= 2 (count result)))
      (is (vector? (first result)))
      (is (vector? (second result))))))


(deftest test-router-core-dev
  (testing "dev 环境下 router/core 返回函数"
    (let [router (ig/init-key :router/core {:routes [] :env :dev})]
      (is (fn? router))
      (is (some? (router))))))


(deftest test-router-core-prod
  (testing "非 dev 环境下 router/core 返回常量函数，每次调用返回同一 router"
    (let [router (ig/init-key :router/core {:routes [] :env :prod})]
      (is (fn? router))
      (is (some? (router)))
      (is (identical? (router) (router))))))
