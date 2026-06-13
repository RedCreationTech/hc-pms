(ns com.ruoyi.web.controllers.system.dict-test
  "字典控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.dict :as dict]))

(def mock-dict-service
  {:query-fn (fn [q p] (case q
                         :list-dict-types [{:dict_id 1 :dict_name "test"}]
                         :find-dict-type-by-id {:dict_id 1 :dict_name "test"}
                         :list-dict-data [{:dict_code 1 :dict_label "test"}]
                         :create-dict-type! [{:dict_id 2}]
                         :update-dict-type! nil
                         :delete-dict-type! nil
                         :create-dict-data! [{:dict_code 2}]
                         :update-dict-data! nil
                         :delete-dict-data! nil
                         []))})

(deftest test-list-dict-types
  (testing "查询字典类型列表"
    (let [request {:query-params {}}
          response (dict/list-dict-types {:dict-service mock-dict-service} request)]
      (is (map? response)))))

(deftest test-get-dict-type
  (testing "获取字典类型详情"
    (let [request {:path-params {:id "1"}}
          response (dict/get-dict-type {:dict-service mock-dict-service} request)]
      (is (map? response)))))

(deftest test-create-dict-type
  (testing "创建字典类型"
    (let [request {:body-params {:dict_name "test" :dict_type "test_type"}}
          response (dict/create-dict-type {:dict-service mock-dict-service} request)]
      (is (map? response)))))

(deftest test-update-dict-type
  (testing "更新字典类型"
    (let [request {:path-params {:id "1"} :body-params {:dict_name "updated"}}
          response (dict/update-dict-type {:dict-service mock-dict-service} request)]
      (is (map? response)))))

(deftest test-delete-dict-type
  (testing "删除字典类型"
    (let [request {:path-params {:id "1"}}
          response (dict/delete-dict-type {:dict-service mock-dict-service} request)]
      (is (map? response)))))

(deftest test-list-dict-data
  (testing "查询字典数据列表"
    (let [request {:query-params {:dict_type "test_type"}}
          response (dict/list-dict-data {:dict-service mock-dict-service} request)]
      (is (map? response)))))
