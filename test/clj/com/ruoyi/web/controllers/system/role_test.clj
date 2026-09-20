(ns com.ruoyi.web.controllers.system.role-test
  "角色控制器测试。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.system.role :as role]))


(def mock-role-service
  {:query-fn (fn [q p]
               (case q
                 :list-roles [{:role_id 1 :role_name "admin" :role_key "admin"}]
                 :find-role-by-id {:role_id 1 :role_name "admin" :role_key "admin" :dept_ids "1,2"}
                 :list-menus-by-role-id [{:menu_id 1}]
                 :create-role! [{:role_id 2}]
                 :update-role! nil
                 :delete-role! nil
                 :list-users-by-role [{:user_id 1 :user_name "user"}]
                 :list-users-not-in-role [{:user_id 2 :user_name "other"}]
                 :delete-user-role! nil
                 :insert-user-role! nil
                 []))})


(def mock-dept-service
  {:query-fn (fn [q p]
               (case q
                 :list-depts [{:dept_id 1 :dept_name "总部"}]
                 []))})


(deftest test-list-roles
  (testing "查询角色列表"
    (let [request {:query-params {}}
          response (role/list-roles {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-get-role
  (testing "获取角色详情"
    (let [request {:path-params {:id "1"}}
          response (role/get-role {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-create-role
  (testing "创建角色"
    (let [request {:body-params {:role_name "test" :role_key "test" :role_sort 1 :status "0"}}
          response (role/create-role {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-update-role
  (testing "更新角色"
    (let [request {:path-params {:id "1"} :body-params {:role_name "updated"}}
          response (role/update-role {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-delete-role
  (testing "删除角色"
    (let [request {:path-params {:id "1"}}
          response (role/delete-role {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-change-status
  (testing "修改角色状态"
    (let [request {:path-params {:id "1"} :body-params {:status "1"}}
          response (role/change-status {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-data-scope
  (testing "设置角色数据权限范围"
    (let [request {:parameters {:body {:role_id 1 :data_scope "1"}}}
          response (role/data-scope {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-option-select
  (testing "获取角色选项列表"
    (let [response (role/option-select {:role-service mock-role-service} {})]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-allocated-list
  (testing "查询角色已分配用户列表"
    (let [request {:parameters {:query {:role_id 1 :user_name "u" :phonenumber "138"}}}
          response (role/allocated-list {:role-service mock-role-service :user-service {}} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-unallocated-list
  (testing "查询角色未分配用户列表"
    (let [request {:parameters {:query {:role_id 1 :user_name "u" :phonenumber "138"}}}
          response (role/unallocated-list {:role-service mock-role-service :user-service {}} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-cancel-auth-user
  (testing "取消用户角色授权"
    (let [request {:parameters {:body {:role_id 1 :user_id 2}}}
          response (role/cancel-auth-user {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-cancel-auth-user-all
  (testing "批量取消用户角色授权"
    (let [request {:parameters {:query {:role_id 1 :user_ids "2,3"}}}
          response (role/cancel-auth-user-all {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-select-auth-user-all
  (testing "批量授权用户角色"
    (let [request {:parameters {:query {:role_id 1 :user_ids "2,3"}}}
          response (role/select-auth-user-all {:role-service mock-role-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-dept-tree-by-role
  (testing "获取角色部门树"
    (let [request {:path-params {:id "1"}}
          response (role/dept-tree-by-role {:role-service mock-role-service :dept-service mock-dept-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))
