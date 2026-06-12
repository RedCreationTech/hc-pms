(ns com.ruoyi.rouyi.domain.system.dict-test
  "字典领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.dict :as dict]))

(def mock-dict-types
  [{:dict_id 1 :dict_name "用户性别" :dict_type "sys_user_sex" :status "0"}
   {:dict_id 2 :dict_name "系统状态" :dict_type "sys_normal_disable" :status "0"}])

(def mock-dict-data
  [{:dict_code 1 :dict_sort 1 :dict_label "男" :dict_value "0" :dict_type "sys_user_sex"}
   {:dict_code 2 :dict_sort 2 :dict_label "女" :dict_value "1" :dict_type "sys_user_sex"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-dict-types mock-dict-types
    :find-dict-type-by-id (first mock-dict-types)
    :list-dict-data mock-dict-data
    :create-dict-type! [{:dict_id 3}]
    :update-dict-type! nil
    :delete-dict-type! nil
    :create-dict-data! [{:dict_code 3}]
    :update-dict-data! nil
    :delete-dict-data! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-dict-types
  (testing "查询字典类型列表"
    (let [result (dict/list-dict-types mock-service {})]
      (is (seq result))
      (is (= 2 (count result))))))

(deftest test-find-dict-type-by-id
  (testing "根据ID查询字典类型"
    (let [result (dict/find-dict-type-by-id mock-service 1)]
      (is (some? result))
      (is (= "用户性别" (:dict_name result))))))

(deftest test-list-dict-data
  (testing "查询字典数据列表"
    (let [result (dict/list-dict-data mock-service {:dict_type "sys_user_sex"})]
      (is (seq result))
      (is (= 2 (count result))))))
