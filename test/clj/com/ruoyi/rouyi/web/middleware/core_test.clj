(ns com.ruoyi.rouyi.web.middleware.core-test
  "核心中间件测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.middleware.core :as core]))

(deftest test-wrap-base
  (testing "基础中间件"
    (let [handler (fn [request] {:status 200 :body "ok"})
          wrapped (core/wrap-base handler)
          request {:request-method :get :uri "/test"}
          response (wrapped request)]
      (is (map? response))
      (is (= 200 (:status response))))))
