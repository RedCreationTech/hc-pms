(ns com.ruoyi.domain.system.menu-test
  "菜单领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.domain.system.menu :as menu]))

(def mock-menus
  [{:menu_id 1 :menu_name "系统管理" :parent_id 0 :menu_type "M" :order_num 1}
   {:menu_id 2 :menu_name "系统监控" :parent_id 0 :menu_type "M" :order_num 2}
   {:menu_id 3 :menu_name "用户管理" :parent_id 1 :menu_type "C" :order_num 1}
   {:menu_id 4 :menu_name "角色管理" :parent_id 1 :menu_type "C" :order_num 2}
   {:menu_id 100 :menu_name "用户查询" :parent_id 3 :menu_type "F" :order_num 1}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-menus mock-menus
    :find-menu-by-id (first mock-menus)
    :create-menu! [{:menu_id 5}]
    :last-insert-rowid {(keyword "last_insert_rowid()") 5}
    :update-menu! nil
    :delete-menu! nil
    :list-menus-by-role-ids mock-menus
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-menu-tree
  (testing "构建菜单树"
    (let [result (menu/menu-tree mock-service)]
      (is (= 2 (count result)))
      (is (= "系统管理" (:menu_name (first result)))))))

(deftest test-menu-tree-by-roles
  (testing "根据角色构建菜单树"
    (let [result (menu/menu-tree-by-roles mock-service [1])]
      (is (vector? result))
      (is (pos? (count result))))))

(deftest test-menu-tree-by-roles-empty
  (testing "空角色列表返回空树"
    (let [result (menu/menu-tree-by-roles mock-service [])]
      (is (= [] result)))))

(deftest test-find-menu-by-id
  (testing "根据ID查询菜单"
    (let [result (menu/find-menu-by-id mock-service 1)]
      (is (some? result))
      (is (= "系统管理" (:menu_name result))))))

(deftest test-create-menu
  (testing "创建菜单"
    (let [result (menu/create-menu! mock-service {:menu_name "新菜单" :parent_id 1})]
      (is (some? result)))))

(deftest test-update-menu
  (testing "更新菜单"
    (let [result (menu/update-menu! mock-service {:menu_id 1 :menu_name "更新后的菜单"})]
      (is (nil? result)))))

(deftest test-delete-menu
  (testing "删除菜单"
    (let [result (menu/delete-menu! mock-service 1)]
      (is (nil? result)))))

(deftest test-find-menu-by-id-not-found
  (testing "查询不存在的菜单"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (menu/find-menu-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
