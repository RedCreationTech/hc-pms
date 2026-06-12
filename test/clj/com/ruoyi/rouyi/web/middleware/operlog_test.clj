(ns com.ruoyi.rouyi.web.middleware.operlog-test
  "操作日志中间件测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.middleware.operlog :as operlog]))

(deftest test-wrap-oper-log
  (testing "操作日志中间件"
    (let [handler (fn [request] {:status 200 :body "ok"})
          wrapped (operlog/wrap-oper-log handler)
          request {:request-method :get :uri "/test" :identity {:user-name "admin"}}
          response (wrapped request)]
      (is (map? response))
      (is (= 200 (:status response))))))
