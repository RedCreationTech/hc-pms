(ns com.ruoyi.rouyi.domain.system.role-test
  "角色领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.role :as role]))

;; ─── 测试用 mock 数据 ──────────────────────────────────────────────────────

(def mock-roles
  [{:role_id 1 :role_name "管理员" :role_key "admin" :role_sort 1 :status "0"}
   {:role_id 2 :role_name "普通用户" :role_key "user" :role_sort 2 :status "0"}])

(def mock-menus
  [{:menu_id 1 :menu_name "系统管理" :parent_id 0}
   {:menu_id 2 :menu_name "用户管理" :parent_id 1}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-roles mock-roles
    :find-role-by-id (first mock-roles)
    :list-menus-by-role-id mock-menus
    :create-role! [{:role_id 3}]
    :update-role! nil
    :delete-role! nil
    :delete-role-mules! nil
    :insert-role-menu! nil
    []))

(def mock-service {:query-fn mock-query-fn})

;; ─── 测试用例 ──────────────────────────────────────────────────────

(deftest test-list-roles
  (testing "查询角色列表"
    (let [result (role/list-roles mock-service {})]
      (is (= 2 (count result)))
      (is (= "管理员" (:role_name (first result)))))))

(deftest test-find-role-by-id
  (testing "根据ID查询角色"
    (let [result (role/find-role-by-id mock-service 1)]
      (is (some? result))
      (is (= "管理员" (:role_name result)))
      (is (contains? result :menu-ids))
      (is (= 2 (count (:menu-ids result)))))))

(deftest test-find-role-by-id-not-found
  (testing "查询不存在的角色"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (role/find-role-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
