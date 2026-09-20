(ns com.ruoyi.web.middleware.exception-test
  "异常处理中间件测试。"
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.middleware.exception :as exception]))


(defn- parse-json-body
  [response]
  (json/parse-string (:body response) true))


(deftest test-handler-builds-json-response
  (testing "handler 根据传入状态码和消息构建 JSON 响应"
    (let [ex (ex-info "boom" {:detail "x"})
          request {:uri "/api/test" :request-method :get}
          response (exception/handler "test message" 418 ex request)]
      (is (= 418 (:status response)))
      (is (= "application/json;charset=utf-8" (get-in response [:headers "content-type"])))
      (let [body (parse-json-body response)]
        (is (= "test message" (:message body)))
        (is (= "clojure.lang.ExceptionInfo" (:exception body)))
        (is (= {:detail "x"} (:data body)))
        (is (= "/api/test" (:uri body)))))))


(deftest test-handler-logs-server-errors
  (testing "handler 在状态码 >= 500 时不会丢失异常信息"
    (let [ex (RuntimeException. "server error")
          response (exception/handler "internal" 500 ex {:uri "/error"})]
      (is (= 500 (:status response)))
      (let [body (parse-json-body response)]
        (is (= "internal" (:message body)))
        (is (= "java.lang.RuntimeException" (:exception body)))))))


(defn- make-throwing-handler
  [e]
  (fn [_request]
    (throw e)))


(defn- wrap-handler
  [handler]
  ((:wrap exception/wrap-exception) handler))


(deftest test-wrap-exception-passes-through-normal-response
  (testing "无异常时中间件透传原始响应"
    (let [handler (wrap-handler (fn [_] {:status 200 :body "ok"}))
          response (handler {:uri "/ok"})]
      (is (= 200 (:status response)))
      (is (= "ok" (:body response))))))


(deftest test-wrap-exception-business-exception
  (testing "业务异常映射为 400"
    (let [ex (ex-info "参数错误" {:type :system.exception/business})
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/users"})]
      (is (= 400 (:status response)))
      (let [body (parse-json-body response)]
        (is (= "bad request" (:message body)))
        (is (= "/api/users" (:uri body)))))))


(deftest test-wrap-exception-not-found-exception
  (testing "资源不存在异常映射为 404"
    (let [ex (ex-info "找不到" {:type :system.exception/not-found})
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/missing"})]
      (is (= 404 (:status response)))
      (is (= "not found" (:message (parse-json-body response)))))))


(deftest test-wrap-exception-unauthorized-exception
  (testing "未认证异常映射为 401"
    (let [ex (ex-info "未登录" {:type :system.exception/unauthorized})
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/secret"})]
      (is (= 401 (:status response)))
      (is (= "unauthorized" (:message (parse-json-body response)))))))


(deftest test-wrap-exception-forbidden-exception
  (testing "无权限异常映射为 403"
    (let [ex (ex-info "无权限" {:type :system.exception/forbidden})
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/admin"})]
      (is (= 403 (:status response)))
      (is (= "forbidden" (:message (parse-json-body response)))))))


(deftest test-wrap-exception-internal-exception
  (testing "内部异常映射为 500"
    (let [ex (ex-info "内部错误" {:type :system.exception/internal})
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/fail"})]
      (is (= 500 (:status response)))
      (is (= "internal exception" (:message (parse-json-body response)))))))


(deftest test-wrap-exception-default-exception
  (testing "未注册类型异常使用默认 500 处理器"
    (let [ex (ex-info "未知错误" {:unknown true})
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/unknown"})]
      (is (= 500 (:status response)))
      (let [body (parse-json-body response)]
        (is (= "default" (:message body)))
        (is (= {:unknown true} (:data body)))
        (is (= "/api/unknown" (:uri body)))))))


(deftest test-wrap-exception-runtime-exception
  (testing "普通 RuntimeException 使用默认 500 处理器"
    (let [ex (RuntimeException. "boom")
          handler (wrap-handler (make-throwing-handler ex))
          response (handler {:uri "/api/crash"})]
      (is (= 500 (:status response)))
      (let [body (parse-json-body response)]
        (is (= "default" (:message body)))
        (is (= "java.lang.RuntimeException" (:exception body)))
        (is (= "/api/crash" (:uri body)))))))
