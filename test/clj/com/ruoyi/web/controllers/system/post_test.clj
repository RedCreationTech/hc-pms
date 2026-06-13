(ns com.ruoyi.web.controllers.system.post-test
  "岗位控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.post :as post]))

(def mock-post-service
  {:query-fn (fn [q p] (case q
                         :list-posts [{:post_id 1 :post_name "test"}]
                         :find-post-by-id {:post_id 1 :post_name "test"}
                         :create-post! [{:post_id 2}]
                         :update-post! nil
                         :delete-post! nil
                         []))})

(deftest test-list-posts
  (testing "查询岗位列表"
    (let [request {:query-params {}}
          response (post/list-posts {:post-service mock-post-service} request)]
      (is (map? response)))))

(deftest test-get-post
  (testing "获取岗位详情"
    (let [request {:path-params {:id "1"}}
          response (post/get-post {:post-service mock-post-service} request)]
      (is (map? response)))))

(deftest test-create-post
  (testing "创建岗位"
    (let [request {:body-params {:post_code "test" :post_name "test" :post_sort 1}}
          response (post/create-post {:post-service mock-post-service} request)]
      (is (map? response)))))

(deftest test-update-post
  (testing "更新岗位"
    (let [request {:path-params {:id "1"} :body-params {:post_name "updated"}}
          response (post/update-post {:post-service mock-post-service} request)]
      (is (map? response)))))

(deftest test-delete-post
  (testing "删除岗位"
    (let [request {:path-params {:id "1"}}
          response (post/delete-post {:post-service mock-post-service} request)]
      (is (map? response)))))
