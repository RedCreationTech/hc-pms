(ns com.ruoyi.rouyi.web.controllers.system.user-test
  "用户控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.user :as user]))

(def mock-user-service
  {:query-fn (fn [q p] (case q
                          :list-users {:rows [{:user_id 1 :user_name "test"}] :total 1}
                          :find-user-by-id {:user_id 1 :user_name "test"}
                          :create-user! [{:user_id 2}]
                          :update-user! nil
                          :delete-user! nil
                          []))})

(deftest test-list-users
  (testing "查询用户列表"
    (let [request {:query-params {}}
          response (user/list-users {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-get-user
  (testing "获取用户详情"
    (let [request {:path-params {:id "1"}}
          response (user/get-user {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-create-user
  (testing "创建用户"
    (let [request {:body-params {:user_name "test" :nick_name "test" :password "123456"}}
          response (user/create-user {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-update-user
  (testing "更新用户"
    (let [request {:path-params {:id "1"} :body-params {:nick_name "updated"}}
          response (user/update-user {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-delete-user
  (testing "删除用户"
    (let [request {:path-params {:id "1"}}
          response (user/delete-user {:user-service mock-user-service} request)]
      (is (map? response)))))
