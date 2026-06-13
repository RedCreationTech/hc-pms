(ns com.ruoyi.domain.system.dept-test
  "部门领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.domain.system.dept :as dept]))

(def mock-depts
  [{:dept_id 1 :dept_name "总公司" :parent_id 0 :order_num 1 :status "0"}
   {:dept_id 2 :dept_name "技术部" :parent_id 1 :order_num 1 :status "0"}
   {:dept_id 3 :dept_name "市场部" :parent_id 1 :order_num 2 :status "0"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-depts mock-depts
    :find-dept-by-id (first mock-depts)
    :create-dept! [{:dept_id 4}]
    :last-insert-rowid {(keyword "last_insert_rowid()") 4}
    :update-dept! nil
    :delete-dept! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-depts
  (testing "查询部门列表"
    (let [result (dept/list-depts mock-service {})]
      (is (seq result))
      (is (= 3 (count result))))))

(deftest test-find-dept-by-id
  (testing "根据ID查询部门"
    (let [result (dept/find-dept-by-id mock-service 1)]
      (is (some? result))
      (is (= "总公司" (:dept_name result))))))

(deftest test-create-dept
  (testing "创建部门"
    (let [result (dept/create-dept! mock-service {:dept_name "新部门" :parent_id 1})]
      (is (some? result)))))

(deftest test-update-dept
  (testing "更新部门"
    (let [result (dept/update-dept! mock-service {:dept_id 1 :dept_name "更新后的总公司"})]
      (is (nil? result)))))

(deftest test-delete-dept
  (testing "删除部门"
    (let [result (dept/delete-dept! mock-service 1)]
      (is (nil? result)))))

(deftest test-find-dept-by-id-not-found
  (testing "查询不存在的部门"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (dept/find-dept-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
