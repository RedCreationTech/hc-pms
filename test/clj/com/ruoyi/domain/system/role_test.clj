(ns com.ruoyi.domain.system.role-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.domain.system.role :as role]))

(def mock-roles
  [{:role_id 1 :role_name "管理员" :role_key "admin" :role_sort 1 :status "0"}
   {:role_id 2 :role_name "普通用户" :role_key "user" :role_sort 2 :status "0"}])

(def mock-menus
  [{:menu_id 1 :menu_name "系统管理" :parent_id 0}
   {:menu_id 2 :menu_name "用户管理" :parent_id 1}])

(defn- mock-query-fn [q p & rest]
  (case q
    :list-roles mock-roles
    :find-role-by-id (first mock-roles)
    :list-menus-by-role-id mock-menus
    :create-role! [{:role_id 3}]
    :update-role! nil
    :delete-role! nil
    :delete-role-menus! nil
    :insert-role-menu! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-roles
  (testing "查询角色列表"
    (let [result (role/list-roles mock-service {})]
      (is (seq result))
      (is (= 2 (count result))))))

(deftest test-find-role-by-id
  (testing "根据ID查询角色"
    (let [result (role/find-role-by-id mock-service 1)]
      (is (some? result))
      (is (= "管理员" (:role_name result)))
      (is (contains? result :menu-ids)))))

(deftest test-create-role
  (testing "创建角色"
    (let [result (role/create-role! mock-service {:role_name "新角色" :role_key "new" :menu-ids [1 2]})]
      (is (some? result)))))

(deftest test-update-role
  (testing "更新角色"
    (let [result (role/update-role! mock-service {:role-id 1 :role_name "更新后的角色" :menu-ids [1]})]
      (is (= 1 result)))))

(deftest test-update-role-with-menu-only
  (testing "只更新角色菜单"
    (let [result (role/update-role! mock-service {:role-id 1 :menu-ids [1 2 3]})]
      (is (= 1 result)))))

(deftest test-delete-role
  (testing "删除角色"
    (let [result (role/delete-role! mock-service 1)]
      (is (nil? result)))))
