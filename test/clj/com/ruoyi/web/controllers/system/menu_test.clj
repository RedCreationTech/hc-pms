(ns com.ruoyi.web.controllers.system.menu-test
  "菜单控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.menu :as menu]))

(def mock-menu-service
  {:query-fn (fn [q p] (case q
                         :list-menus [{:menu_id 1 :menu_name "test"}]
                         :find-menu-by-id {:menu_id 1 :menu_name "test"}
                         :create-menu! [{:menu_id 2}]
                         :update-menu! nil
                         :delete-menu! nil
                         []))})

(deftest test-list-menus
  (testing "查询菜单列表"
    (let [request {:query-params {}}
          response (menu/list-menus {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-get-menu
  (testing "获取菜单详情"
    (let [request {:path-params {:id "1"}}
          response (menu/get-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-create-menu
  (testing "创建菜单"
    (let [request {:body-params {:menu_name "test" :parent_id 0 :menu_type "M" :order_num 1}}
          response (menu/create-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-update-menu
  (testing "更新菜单"
    (let [request {:path-params {:id "1"} :body-params {:menu_name "updated"}}
          response (menu/update-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-delete-menu
  (testing "删除菜单"
    (let [request {:path-params {:id "1"}}
          response (menu/delete-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))
