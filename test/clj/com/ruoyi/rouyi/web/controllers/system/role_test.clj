(ns com.ruoyi.rouyi.web.controllers.system.role-test
  "角色控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.role :as role]))

(def mock-role-service
  {:query-fn (fn [q p] (case q
                          :list-roles [{:role_id 1 :role_name "管理员" :role_key "admin" :role_sort 1 :status "0"}]
                          :find-role-by-id {:role_id 1 :role_name "管理员" :role_key "admin" :role_sort 1 :status "0" :menu_ids [1 2]}
                          :create-role! [{:role_id 2}]
                          :update-role! nil
                          :delete-role! nil
                          :change-status! nil
                          :list-menus-by-role-id [{:menu_id 1 :menu_name "系统管理"}]
                          []))})

(def mock-menu-service
  {:query-fn (fn [q p] (case q
                          :list-menus [{:menu_id 1 :menu_name "系统管理"}]
                          []))})

(deftest test-list-roles
  (testing "查询角色列表"
    (let [request {:query-params {}}
          response (role/list-roles {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-get-role
  (testing "获取角色详情"
    (let [request {:path-params {:id "1"}}
          response (role/get-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-create-role
  (testing "创建角色"
    (let [request {:body-params {:role_name "test" :role_key "test" :role_sort 1 :menu_ids [1]}}
          response (role/create-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-update-role
  (testing "更新角色"
    (let [request {:path-params {:id "1"} :body-params {:role_name "updated" :menu_ids [1]}}
          response (role/update-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-delete-role
  (testing "删除角色"
    (let [request {:path-params {:id "1"}}
          response (role/delete-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-change-status
  (testing "修改角色状态"
    (let [request {:path-params {:id "1"} :body-params {:status "1"}}
          response (role/change-status {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-data-scope
  (testing "设置数据权限"
    (let [request {:path-params {:id "1"} :body-params {:data_scope "2"}}
          response (role/data-scope {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-option-select
  (testing "获取角色选项"
    (let [request {}
          response (role/option-select {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-dept-tree
  (testing "获取部门树"
    (let [request {}
          response (role/dept-tree-by-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-allocated-list
  (testing "查询已分配用户"
    (let [request {:query-params {:role-id "1"}}
          response (role/allocated-list {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-unallocated-list
  (testing "查询未分配用户"
    (let [request {:query-params {:role-id "1"}}
          response (role/unallocated-list {:role-service mock-role-service} request)]
      (is (map? response)))))
