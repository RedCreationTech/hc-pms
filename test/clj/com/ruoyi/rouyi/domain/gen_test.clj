(ns com.ruoyi.rouyi.domain.gen-test
  "代码生成器领域层测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.gen :as gen]))

;; ─── 测试用 mock 数据 ──────────────────────────────────────────────────────

(def mock-tables
  [{:table_name "sys_user" :table_comment "用户表"}
   {:table_name "sys_role" :table_comment "角色表"}
   {:table_name "sys_menu" :table_comment "菜单表"}])

(def mock-columns
  [{:column_name "user_id" :data_type "integer" :is_pk "YES" :is_nullable "NO"}
   {:column_name "user_name" :data_type "varchar" :is_pk "NO" :is_nullable "NO"}
   {:column_name "nick_name" :data_type "varchar" :is_pk "NO" :is_nullable "YES"}
   {:column_name "email" :data_type "varchar" :is_pk "NO" :is_nullable "YES"}
   {:column_name "status" :data_type "char" :is_pk "NO" :is_nullable "YES"}])

(defn- mock-query-fn
  "模拟查询函数。"
  [query-name params]
  (case query-name
    :gen-tables mock-tables
    :gen-columns mock-columns
    []))

(def mock-service
  {:query-fn mock-query-fn})

;; ─── 测试用例 ──────────────────────────────────────────────────────

(deftest test-list-tables
  (testing "查询表列表"
    (let [result (gen/list-tables mock-service)]
      (is (= 3 (count result)))
      (is (= "sys_user" (:table_name (first result)))))))

(deftest test-table-columns
  (testing "查询表列信息"
    (let [result (gen/table-columns mock-service "sys_user")]
      (is (= 5 (count result)))
      (is (= "user_id" (:column_name (first result)))))))

(deftest test-generate-code
  (testing "生成代码"
    (let [result (gen/generate-code mock-service "sys_user")]
      (is (= "sys_user" (:table-name result)))
      (is (= "User" (:entity-name result)))
      (is (= "user" (:kebab-name result)))
      (is (= 5 (:columns result)))
      (is (string? (:message result))))))

(deftest test-generate-code-entity-name
  (testing "实体名称生成"
    (is (= "User" (:entity-name (gen/generate-code mock-service "sys_user"))))
    (is (= "Role" (:entity-name (gen/generate-code mock-service "sys_role"))))
    (is (= "Menu" (:entity-name (gen/generate-code mock-service "sys_menu"))))))

(deftest test-generate-code-kebab-name
  (testing "kebab-case 名称生成"
    (is (= "user" (:kebab-name (gen/generate-code mock-service "sys_user"))))
    (is (= "role" (:kebab-name (gen/generate-code mock-service "sys_role"))))))
