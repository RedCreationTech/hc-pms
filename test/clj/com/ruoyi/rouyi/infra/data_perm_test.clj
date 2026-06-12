(ns com.ruoyi.rouyi.infra.data-perm-test
  "数据权限测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.infra.data-perm :as data-perm]))

(deftest test-data-perm-filter
  (testing "数据权限过滤"
    (let [identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]}
          result (data-perm/data-perm-filter identity "default" :alias "u")]
      (is (map? result)))))

(deftest test-data-perm-filter-all
  (testing "全部数据权限"
    (let [identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]}
          result (data-perm/data-perm-filter identity "default" :alias "u")]
      (is (contains? result :where)))))

(deftest test-data-perm-filter-custom
  (testing "自定义数据权限"
    (let [identity {:user-id 1 :roles [{:role_id 1 :data_scope "2"}]}
          result (data-perm/data-perm-filter identity "default" :alias "u")]
      (is (map? result)))))
