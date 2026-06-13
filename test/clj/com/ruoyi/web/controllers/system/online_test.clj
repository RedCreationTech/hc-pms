(ns com.ruoyi.web.controllers.system.online-test
  "在线用户控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.online :as online]))

(def mock-online-service
  {:list-online (fn [_params] {:rows [{:tokenId "1" :userName "admin"}]
                               :total 1})
   :force-logout (fn [token-id] (is (= "1" token-id)))})

(deftest test-list-online
  (testing "查询在线用户列表"
    (let [request {:query-params {}}
          response (online/list-online {:online-service mock-online-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= 1 (:total (:data body))))
      (is (seq (:rows (:data body)))))))

(deftest test-list-online-with-params
  (testing "带分页参数查询在线用户"
    (let [request {:query-params {"pageNum" "2" "pageSize" "20" "user_name" "admin" "ipaddr" "127.0.0.1"}}
          response (online/list-online {:online-service mock-online-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body))))))

(deftest test-force-logout
  (testing "强退在线用户"
    (let [request {:path-params {:token-id "1"}}
          response (online/force-logout {:online-service mock-online-service} request)
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= 200 (:code body)))
      (is (= "操作成功" (:msg body))))))
