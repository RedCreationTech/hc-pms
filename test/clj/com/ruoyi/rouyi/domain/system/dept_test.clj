(ns com.ruoyi.rouyi.domain.system.dept-test
  "部门领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.dept :as dept]))

(def mock-depts
  [{:dept_id 1 :dept_name "总公司" :parent_id 0 :order_num 1 :status "0"}
   {:dept_id 2 :dept_name "技术部" :parent_id 1 :order_num 1 :status "0"}
   {:dept_id 3 :dept_name "市场部" :parent_id 1 :order_num 2 :status "0"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-depts mock-depts
    :find-dept-by-id (first mock-depts)
    :create-dept! [{:dept_id 4}]
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


