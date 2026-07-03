(ns com.ruoyi.web.middleware.operlog-test
  "操作日志中间件测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.middleware.operlog :as operlog]))

(defn- make-capturing-query-fn []
  (let [calls (atom [])]
    (fn [q p] (swap! calls conj [q p]) nil)
    calls))

(deftest test-wrap-oper-log-records-post
  (testing "POST API 请求记录操作日志"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params {:user_name "admin"}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)
          log-entry (second (first @calls))]
      (is (= 200 (:status response)))
      (is (= :create-oper-log! (ffirst @calls)))
      (is (= "用户管理" (:title log-entry)))
      (is (= "admin" (:oper_name log-entry)))
      (is (= 0 (:status log-entry)))
      (is (= "127.0.0.1" (:oper_ip log-entry))))))

(deftest test-wrap-oper-log-skips-get
  (testing "GET 请求不记录日志"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/system/user"
                   :request-method :get
                   :params {}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (empty? @calls)))))

(deftest test-wrap-oper-log-skips-login
  (testing "登录路径不记录日志"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/auth/login"
                   :request-method :post
                   :params {:username "admin" :password "123"}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (empty? @calls)))))

(deftest test-wrap-oper-log-error-status
  (testing "错误响应记录状态为 1"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 500}))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params {}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)
          log-entry (second (first @calls))]
      (is (= 500 (:status response)))
      (is (= 1 (:status log-entry))))))

(deftest test-wrap-oper-log-x-forwarded-for
  (testing "优先使用 X-Forwarded-For IP"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params {}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :headers {"x-forwarded-for" "10.0.0.1"}
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)
          log-entry (second (first @calls))]
      (is (= 200 (:status response)))
      (is (= "10.0.0.1" (:oper_ip log-entry))))))

(deftest test-wrap-oper-log-no-query-fn
  (testing "无 query-fn 时不抛出异常"
    (let [handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params {}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"}]
      (is (= 200 (:status (handler request)))))))

(deftest test-wrap-oper-log-string-params
  (testing "字符串参数直接记录"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params "raw-body"
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)
          log-entry (second (first @calls))]
      (is (= 200 (:status response)))
      (is (= "raw-body" (:oper_param log-entry))))))

(deftest test-wrap-oper-log-truncates-long-params
  (testing "超长参数被截断并追加省略号"
    (let [calls (make-capturing-query-fn)
          handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          long-value (apply str (repeat 300 "a"))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params {:data long-value}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}}
          response (handler request)
          log-entry (second (first @calls))
          oper-param (:oper_param log-entry)]
      (is (= 200 (:status response)))
      (is (= 203 (count oper-param)))
      (is (clojure.string/ends-with? oper-param "...")))))

(deftest test-wrap-oper-log-query-fn-exception
  (testing "query-fn 抛异常时不影响响应"
    (let [handler (operlog/wrap-oper-log (fn [_] {:status 200}))
          request {:uri "/api/system/user"
                   :request-method :post
                   :params {}
                   :identity {:user-name "admin"}
                   :remote-addr "127.0.0.1"
                   :components {:query-fn (fn [_ _] (throw (Exception. "db down")))}}]
      (is (= 200 (:status (handler request)))))))
