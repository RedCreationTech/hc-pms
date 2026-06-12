(ns com.ruoyi.rouyi.web.controllers.system.dept-test
  "部门控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.dept :as dept]))

(def mock-dept-service
  {:query-fn (fn [q p] (case q
                          :list-depts [{:dept_id 1 :dept_name "test"}]
                          :find-dept-by-id {:dept_id 1 :dept_name "test"}
                          :create-dept! [{:dept_id 2}]
                          :update-dept! nil
                          :delete-dept! nil
                          []))})

(deftest test-list-depts
  (testing "查询部门列表"
    (let [request {:query-params {}}
          response (dept/list-depts {:dept-service mock-dept-service} request)]
      (is (map? response)))))

(deftest test-get-dept
  (testing "获取部门详情"
    (let [request {:path-params {:id "1"}}
          response (dept/get-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))

(deftest test-create-dept
  (testing "创建部门"
    (let [request {:body-params {:dept_name "test" :parent_id 0}}
          response (dept/create-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))

(deftest test-update-dept
  (testing "更新部门"
    (let [request {:path-params {:id "1"} :body-params {:dept_name "updated"}}
          response (dept/update-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))

(deftest test-delete-dept
  (testing "删除部门"
    (let [request {:path-params {:id "1"}}
          response (dept/delete-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))
