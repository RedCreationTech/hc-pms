(ns com.ruoyi.rouyi.web.controllers.system.import-export-test
  "导入导出控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.import-export :as import-export]))

(def mock-user-service
  {:query-fn (fn [q p] (case q
                          :list-users {:rows [{:user_id 1 :user_name "test"}] :total 1}
                          :create-user! [{:user_id 2}]
                          []))})

(deftest test-export-users
  (testing "导出用户数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-users {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-import-template
  (testing "下载导入模板"
    (let [request {}
          response (import-export/import-template {} request)]
      (is (map? response)))))
