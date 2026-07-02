(ns com.ruoyi.web.controllers.system.menu-test
  "菜单控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.menu :as menu]))

(def admin-identity
  {:user-id 1 :user-name "admin"})

(def mock-menu-service
  {:query-fn (fn [q p] (case q
                         :list-menus [{:menu_id 1 :menu_name "test"}]
                         :find-menu-by-id {:menu_id 1 :menu_name "test"}
                         :create-menu! [{:menu_id 2}]
                         :last-insert-rowid {:last_insert_rowid 2}
                         :update-menu! nil
                         :delete-menu! nil
                         []))
   :menu-tree (fn [] [{:menu_id 1 :menu_name "test"}])})

(deftest test-list-menus
  (testing "查询菜单列表"
    (let [request {:query-params {}}
          response (menu/list-menus {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-menu-tree
  (testing "获取菜单树"
    (let [response (menu/menu-tree {:menu-service mock-menu-service} {})]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-get-menu
  (testing "获取菜单详情"
    (let [request {:path-params {:id "1"}}
          response (menu/get-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-get-menu-not-found
  (testing "获取不存在的菜单"
    (let [service {:query-fn (fn [q p] (case q :find-menu-by-id nil []))
                   :menu-tree (fn [] [])}
          request {:path-params {:id "999"}}
          response (menu/get-menu {:menu-service service} request)]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "菜单不存在" (:msg (:body response)))))))

(deftest test-create-menu
  (testing "创建菜单"
    (let [request {:body-params {:menu_name "test" :parent_id 0 :menu_type "M" :order_num 1}
                   :identity admin-identity}
          response (menu/create-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-update-menu
  (testing "更新菜单"
    (let [request {:path-params {:id "1"} :body-params {:menu_name "updated"}
                   :identity admin-identity}
          response (menu/update-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-delete-menu
  (testing "删除菜单"
    (let [request {:path-params {:id "1"}}
          response (menu/delete-menu {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-change-status
  (testing "修改菜单状态"
    (let [request {:path-params {:id "1"}
                   :body-params {:status "1"}
                   :identity admin-identity}
          response (menu/change-status {:menu-service mock-menu-service} request)]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "状态修改成功" (:data (:body response)))))))

(deftest test-save-sort-uses-ruoyi-params
  (testing "保存排序支持 RuoYi-Vue 的 menuIds/orderNums 参数"
    (let [calls (atom [])
          service {:query-fn (fn [q p]
                               (when (= q :update-menu-order!)
                                 (swap! calls conj p)))}
          request {:body-params {:menuIds "10,20" :orderNums "3,4"}}
          response (menu/save-sort {:menu-service service} request)]
      (is (= 200 (:status response)))
      (is (= "排序保存成功" (:data (:body response))))
      (is (= [{:menu_id 10 :order_num 3}
              {:menu_id 20 :order_num 4}]
             @calls)))))
