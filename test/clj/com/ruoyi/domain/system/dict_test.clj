(ns com.ruoyi.domain.system.dict-test
  "字典领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.domain.system.dict :as dict]))

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
    :find-dict-data-by-id (first mock-dict-data)
    :create-dict-type! [{:dict_id 3}]
    :last-insert-rowid {(keyword "last_insert_rowid()") 3}
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

(deftest test-find-dict-data-by-id
  (testing "根据ID查询字典数据"
    (let [result (dict/find-dict-data-by-id mock-service 1)]
      (is (some? result))
      (is (= "男" (:dict_label result))))))

(deftest test-create-dict-type
  (testing "创建字典类型"
    (let [result (dict/create-dict-type! mock-service {:dict_name "新字典" :dict_type "new_type"})]
      (is (some? result)))))

(deftest test-update-dict-type
  (testing "更新字典类型"
    (let [result (dict/update-dict-type! mock-service {:dict_id 1 :dict_name "更新后的字典"})]
      (is (nil? result)))))

(deftest test-delete-dict-type
  (testing "删除字典类型"
    (let [result (dict/delete-dict-type! mock-service 1)]
      (is (nil? result)))))

(deftest test-create-dict-data
  (testing "创建字典数据"
    (let [result (dict/create-dict-data! mock-service {:dict_type "sys_user_sex" :dict_label "未知" :dict_value "2"})]
      (is (some? result)))))

(deftest test-update-dict-data
  (testing "更新字典数据"
    (let [result (dict/update-dict-data! mock-service {:dict_code 1 :dict_label "更新后的男"})]
      (is (nil? result)))))

(deftest test-delete-dict-data
  (testing "删除字典数据"
    (let [result (dict/delete-dict-data! mock-service 1)]
      (is (nil? result)))))

(deftest test-find-dict-type-by-id-not-found
  (testing "查询不存在的字典类型"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (dict/find-dict-type-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
