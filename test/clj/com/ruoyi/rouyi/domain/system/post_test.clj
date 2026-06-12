(ns com.ruoyi.rouyi.domain.system.post-test
  "岗位领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.post :as post]))

(def mock-posts
  [{:post_id 1 :post_code "ceo" :post_name "董事长" :post_sort 1 :status "0"}
   {:post_id 2 :post_code "cto" :post_name "技术总监" :post_sort 2 :status "0"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-posts mock-posts
    :find-post-by-id (first mock-posts)
    :create-post! [{:post_id 3}]
    :last-insert-rowid {(keyword "last_insert_rowid()") 3}
    :update-post! nil
    :delete-post! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-posts
  (testing "查询岗位列表"
    (let [result (post/list-posts mock-service {})]
      (is (seq result))
      (is (= 2 (count result))))))

(deftest test-find-post-by-id
  (testing "根据ID查询岗位"
    (let [result (post/find-post-by-id mock-service 1)]
      (is (some? result))
      (is (= "董事长" (:post_name result))))))

(deftest test-create-post
  (testing "创建岗位"
    (let [result (post/create-post! mock-service {:post_code "cfo" :post_name "财务总监" :post_sort 3})]
      (is (some? result)))))

(deftest test-update-post
  (testing "更新岗位"
    (let [result (post/update-post! mock-service {:post_id 1 :post_name "更新后的董事长"})]
      (is (nil? result)))))

(deftest test-delete-post
  (testing "删除岗位"
    (let [result (post/delete-post! mock-service 1)]
      (is (nil? result)))))

(deftest test-find-post-by-id-not-found
  (testing "查询不存在的岗位"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (post/find-post-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
