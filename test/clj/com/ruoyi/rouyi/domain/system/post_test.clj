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
