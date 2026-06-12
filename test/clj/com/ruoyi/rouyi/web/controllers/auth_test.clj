(ns com.ruoyi.rouyi.web.controllers.auth-test
  "认证控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.auth :as auth]))

(def mock-user-service
  {:query-fn (fn [q p] (case q
                          :find-user-by-id {:user_id 1 :user_name "admin" :password "hashed"}
                          :find-user-by-username {:user_id 1 :user_name "admin" :password "hashed"}
                          nil))})

(def mock-menu-service
  {:query-fn (fn [q p] [])})

(deftest test-get-info-without-identity
  (testing "未登录获取用户信息"
    (let [request {:identity nil}
          response (auth/get-info {:user-service mock-user-service :menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-logout
  (testing "登出"
    (let [request {:headers {"authorization" "Bearer test-token"}}
          response (auth/logout request)]
      (is (map? response)))))
