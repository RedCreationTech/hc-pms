(ns com.ruoyi.rouyi.web.controllers.gen-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.gen :as gen]))

(def mock-gen-service
  {:query-fn (fn [q p & rest] (case q
                                :gen-tables [{:table_name "sys_user"}]
                                :gen-columns [{:column_name "user_id"}]
                                []))})

(deftest test-list-tables
  (testing "查询表列表"
    (let [response (gen/list-tables {:gen-service mock-gen-service} {})]
      (is (map? response)))))

(deftest test-table-columns
  (testing "查询表列信息"
    (let [request {:query-params {:tableName "sys_user"}}
          response (gen/table-columns {:gen-service mock-gen-service} request)]
      (is (map? response)))))
