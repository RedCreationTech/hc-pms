(ns com.ruoyi.rouyi.web.controllers.system.online-test
  "在线用户控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.online :as online]))

(deftest test-list-online
  (testing "查询在线用户列表"
    (let [request {:query-params {}}
          response (online/list-online {} request)]
      (is (map? response)))))

(deftest test-force-logout
  (testing "强退在线用户"
    (let [request {:path-params {:tokenId "test-token"}}
          response (online/force-logout {} request)]
      (is (map? response)))))
