(ns com.ruoyi.rouyi.web.controllers.system.user-test
  "用户控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.user :as user]))

(def mock-user-service
  {:query-fn (fn [q p] (case q
                          :list-users {:rows [{:user_id 1 :user_name "admin" :nick_name "管理员" :email "admin@test.com" :phonenumber "13800138000" :sex "0" :status "0" :dept_id 1}] :total 1}
                          :find-user-by-id {:user_id 1 :user_name "admin" :nick_name "管理员" :email "admin@test.com" :phonenumber "13800138000" :sex "0" :status "0" :dept_id 1 :role_ids [1] :post_ids [1]}
                          :create-user! [{:user_id 2}]
                          :update-user! nil
                          :delete-user! nil
                          :reset-pwd! nil
                          :change-status! nil
                          :get-user-roles [{:role_id 1 :role_name "管理员"}]
                          :get-user-posts [{:post_id 1 :post_name "董事长"}]
                          []))})

(def mock-dept-service
  {:query-fn (fn [q p] (case q
                          :list-depts [{:dept_id 1 :dept_name "总公司"}]
                          []))})

(def mock-role-service
  {:query-fn (fn [q p] (case q
                          :list-roles [{:role_id 1 :role_name "管理员"}]
                          []))})

(def mock-post-service
  {:query-fn (fn [q p] (case q
                          :list-posts [{:post_id 1 :post_name "董事长"}]
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
    (let [request {:body-params {:user_name "test" :nick_name "test" :password "123456" :role_ids [1] :post_ids [1]}}
          response (user/create-user {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-update-user
  (testing "更新用户"
    (let [request {:path-params {:id "1"} :body-params {:nick_name "updated" :role_ids [1] :post_ids [1]}}
          response (user/update-user {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-delete-user
  (testing "删除用户"
    (let [request {:path-params {:id "1"}}
          response (user/delete-user {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-reset-pwd
  (testing "重置密码"
    (let [request {:path-params {:id "1"} :body-params {:password "newpassword"}}
          response (user/reset-password {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-change-status
  (testing "修改状态"
    (let [request {:path-params {:id "1"} :body-params {:status "1"}}
          response (user/change-status {:user-service mock-user-service} request)]
      (is (map? response)))))






