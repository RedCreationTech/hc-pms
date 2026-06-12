(ns com.ruoyi.rouyi.web.controllers.system.notice-test
  "通知公告控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.notice :as notice]))

(def mock-notice-service
  {:query-fn (fn [q p] (case q
                          :list-notices [{:notice_id 1 :notice_title "test"}]
                          :find-notice-by-id {:notice_id 1 :notice_title "test"}
                          :create-notice! [{:notice_id 2}]
                          :update-notice! nil
                          :delete-notice! nil
                          []))})

(deftest test-list-notices
  (testing "查询通知列表"
    (let [request {:query-params {}}
          response (notice/list-notices {:notice-service mock-notice-service} request)]
      (is (map? response)))))

(deftest test-get-notice
  (testing "获取通知详情"
    (let [request {:path-params {:id "1"}}
          response (notice/get-notice {:notice-service mock-notice-service} request)]
      (is (map? response)))))

(deftest test-create-notice
  (testing "创建通知"
    (let [request {:body-params {:notice_title "test" :notice_type "1" :notice_content "content"}}
          response (notice/create-notice {:notice-service mock-notice-service} request)]
      (is (map? response)))))

(deftest test-update-notice
  (testing "更新通知"
    (let [request {:path-params {:id "1"} :body-params {:notice_title "updated"}}
          response (notice/update-notice {:notice-service mock-notice-service} request)]
      (is (map? response)))))

(deftest test-delete-notice
  (testing "删除通知"
    (let [request {:path-params {:id "1"}}
          response (notice/delete-notice {:notice-service mock-notice-service} request)]
      (is (map? response)))))
