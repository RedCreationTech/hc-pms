(ns com.ruoyi.rouyi.domain.system.menu-test
  "菜单领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.menu :as menu]))

;; ─── 测试用 mock 数据 ──────────────────────────────────────────────────────

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
    :update-menu! nil
    :delete-menu! nil
    :list-menus-by-role-ids mock-menus
    []))

(def mock-service {:query-fn mock-query-fn})

;; ─── 测试用例 ──────────────────────────────────────────────────────

(deftest test-menu-tree
  (testing "构建菜单树"
    (let [result (menu/menu-tree mock-service)]
      (is (= 2 (count result)))
      ;; 第一个顶级菜单是系统管理
      (is (= "系统管理" (:menu_name (first result))))
      ;; 系统管理有子菜单
      (is (seq (:children (first result)))))))

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
