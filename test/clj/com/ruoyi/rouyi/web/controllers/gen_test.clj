(ns com.ruoyi.rouyi.web.controllers.gen-test
  "代码生成器控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.gen :as gen]))

(def mock-gen-service
  {:query-fn (fn [q p] (case q
                          :gen-tables [{:table_name "sys_user" :table_comment "用户表"}]
                          :gen-columns [{:column_name "user_id" :data_type "integer"}]
                          []))})

(deftest test-list-tables
  (testing "查询表列表"
    (let [response (gen/list-tables {:gen-service mock-gen-service} {})]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-table-columns
  (testing "查询表列信息"
    (let [request {:query-params {:tableName "sys_user"}}
          response (gen/table-columns {:gen-service mock-gen-service} request)]
      (is (map? response)))))

(deftest test-preview-code
  (testing "预览代码"
    (let [request {:query-params {:tableName "sys_user"}}
          response (gen/preview-code {:gen-service mock-gen-service} request)]
      (is (map? response)))))

(deftest test-batch-generate
  (testing "批量生成代码"
    (let [request {:body-params {:tables ["sys_user"]}}
          response (gen/batch-generate {:gen-service mock-gen-service} request)]
      (is (map? response)))))

(deftest test-deploy-code
  (testing "部署代码"
    (let [request {:body-params {:tableName "sys_user"}}
          response (gen/deploy-code {:gen-service mock-gen-service} request)]
      (is (map? response)))))
