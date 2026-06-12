(ns com.ruoyi.rouyi.web.controllers.system.role-test
  "角色控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.role :as role]))

(def mock-role-service
  {:query-fn (fn [q p] (case q
                          :list-roles [{:role_id 1 :role_name "test"}]
                          :find-role-by-id {:role_id 1 :role_name "test"}
                          :create-role! [{:role_id 2}]
                          :update-role! nil
                          :delete-role! nil
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
    (let [request {:body-params {:role_name "test" :role_key "test" :role_sort 1}}
          response (role/create-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-update-role
  (testing "更新角色"
    (let [request {:path-params {:id "1"} :body-params {:role_name "updated"}}
          response (role/update-role {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-delete-role
  (testing "删除角色"
    (let [request {:path-params {:id "1"}}
          response (role/delete-role {:role-service mock-role-service} request)]
      (is (map? response)))))
