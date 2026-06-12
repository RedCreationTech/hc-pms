(ns com.ruoyi.rouyi.web.middleware.auth-test
  "认证中间件测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.middleware.auth :as auth]))

(deftest test-auth-middleware-without-token
  (testing "无 Token 时返回 401"
    (let [handler (fn [request] {:status 200 :body "ok"})
          wrapped (auth/auth-middleware {:required? true} handler)
          request {:headers {}}
          response (wrapped request)]
      (is (map? response))
      (is (= 401 (:status response))))))

(deftest test-auth-middleware-optional
  (testing "可选认证 - 无 Token 时继续"
    (let [handler (fn [request] {:status 200 :body "ok"})
          wrapped (auth/auth-middleware {:required? false} handler)
          request {:headers {}}
          response (wrapped request)]
      (is (map? response))
      (is (= 200 (:status response))))))
