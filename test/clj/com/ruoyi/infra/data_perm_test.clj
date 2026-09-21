(ns com.ruoyi.infra.data-perm-test
  "数据权限过滤测试."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.data-perm :as dp]))


(deftest test-scope-names
  (testing "数据权限范围名称映射"
    (is (= "ALL" (get dp/scope-names 1)))
    (is (= "CUSTOM" (get dp/scope-names 2)))
    (is (= "DEPT" (get dp/scope-names 3)))
    (is (= "DEPT_CHILD" (get dp/scope-names 4)))
    (is (= "SELF" (get dp/scope-names 5)))
    (is (nil? (get dp/scope-names 99)))))


(deftest test-data-perm-filter-admin
  (testing "管理员角色不过滤"
    (let [identity {:user-id 1 :user-name "admin" :roles [{:role-key "admin" :data-scope 1}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (= "1=1" (:sql result)))
      (is (= {} (:params result))))))


(deftest test-data-perm-filter-all
  (testing "全部数据权限"
    (let [identity {:user-id 2 :user-name "user" :roles [{:role-key "system:user:list" :data-scope 1 :dept-id 3}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (= "1=1" (:sql result)))
      (is (= {} (:params result))))))


(deftest test-data-perm-filter-custom
  (testing "自定义部门范围"
    (let [identity {:user-id 2 :roles [{:role-key "system:user:list" :data-scope 2 :dept-id 3}]}
          result (dp/data-perm-filter identity "system:user:list" :dept-ids [3 4 5])]
      (is (str/includes? (:sql result) "dept_id IN (:v*:data-perm-dept-ids)"))
      (is (= [3 4 5] (get-in result [:params :data-perm-dept-ids]))))))


(deftest test-data-perm-filter-dept
  (testing "本部门权限"
    (let [identity {:user-id 2 :roles [{:role-key "system:user:list" :data-scope 3 :dept-id 7}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (str/includes? (:sql result) "dept_id = :data-perm-dept-id"))
      (is (= 7 (get-in result [:params :data-perm-dept-id]))))))


(deftest test-data-perm-filter-dept-without-dept-id
  (testing "本部门权限缺少部门 ID"
    (let [identity {:user-id 2 :roles [{:role-key "system:user:list" :data-scope 3}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (= "1=0" (:sql result)))
      (is (= {} (:params result))))))


(deftest test-data-perm-filter-dept-child
  (testing "本部门及以下权限"
    (let [identity {:user-id 2 :roles [{:role-key "system:user:list" :data-scope 4 :dept-id 7}]}
          result (dp/data-perm-filter identity "system:user:list" :alias "su")]
      (is (str/includes? (:sql result) "su.dept_id IN (SELECT dept_id FROM sys_dept"))
      (is (= 7 (get-in result [:params :data-perm-dept-id]))))))


(deftest test-data-perm-filter-dept-child-without-dept-id
  (testing "本部门及以下权限缺少部门 ID"
    (let [identity {:user-id 2 :roles [{:role-key "system:user:list" :data-scope 4}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (= "1=0" (:sql result)))
      (is (= {} (:params result))))))


(deftest test-data-perm-filter-self
  (testing "仅本人权限"
    (let [identity {:user-id 9 :roles [{:role-key "system:user:list" :data-scope 5 :dept-id 7}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (str/includes? (:sql result) "user_id = :data-perm-user-id"))
      (is (= 9 (get-in result [:params :data-perm-user-id]))))))


(deftest test-data-perm-filter-self-without-user-id
  (testing "仅本人权限缺少用户 ID"
    (let [identity {:roles [{:role-key "system:user:list" :data-scope 5 :dept-id 7}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (= "1=0" (:sql result)))
      (is (= {} (:params result))))))


(deftest test-data-perm-filter-role-not-found
  (testing "未匹配到角色时不过滤"
    (let [identity {:user-id 2 :roles [{:role-key "other" :data-scope 5 :dept-id 7}]}
          result (dp/data-perm-filter identity "system:user:list")]
      (is (= "1=1" (:sql result)))
      (is (= {} (:params result))))))


(deftest test-data-perm-filter-default-alias
  (testing "默认表别名为 u"
    (let [identity {:user-id 2 :roles [{:role-key "r" :data-scope 3 :dept-id 1}]}
          result (dp/data-perm-filter identity "r")]
      (is (str/starts-with? (:sql result) " u.dept_id")))))


(deftest test-data-perm-filter-custom-alias
  (testing "自定义表别名"
    (let [identity {:user-id 2 :roles [{:role-key "r" :data-scope 3 :dept-id 1}]}
          result (dp/data-perm-filter identity "r" :alias "p")]
      (is (str/starts-with? (:sql result) " p.dept_id")))))
