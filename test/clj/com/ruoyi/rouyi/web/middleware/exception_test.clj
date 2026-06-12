(ns com.ruoyi.rouyi.web.middleware.exception-test
  "异常中间件测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.middleware.exception :as exception]))

(deftest test-wrap-exception
  (testing "异常处理中间件 - 正常请求"
    (let [handler (fn [request] {:status 200 :body "ok"})
          wrapped (exception/wrap-exception handler)
          request {:request-method :get :uri "/test"}
          response (wrapped request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-wrap-exception-with-error
  (testing "异常处理中间件 - 异常请求"
    (let [handler (fn [request] (throw (Exception. "test error")))
          wrapped (exception/wrap-exception handler)
          request {:request-method :get :uri "/test"}
          response (wrapped request)]
      (is (map? response))
      (is (= 500 (:status response))))))
