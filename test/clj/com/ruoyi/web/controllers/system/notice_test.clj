(ns com.ruoyi.web.controllers.system.notice-test
  "通知公告控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.notice :as notice]))

(def mock-notice-service
  {:query-fn (fn
               ([q]
                (case q
                  :create-notice! [{:notice_id 2}]
                  :update-notice! nil
                  :delete-notice! nil
                  []))
               ([q _p]
                (case q
                  :list-notices [{:notice_id 1 :notice_name "test"}]
                  :count-notices {:total 1}
                  :find-notice-by-id {:notice_id 1 :notice_name "test"}
                  []))
               ([q _p _opts]
                (case q
                  :find-notice-by-id {:notice_id 1 :notice_name "test"}
                  [])))})

(deftest test-list-notices
  (testing "查询通知公告列表"
    (let [request {:query-params {"notice_name" "test" "notice_type" "1" "page" "1" "size" "10"}}
          response (notice/list-notices mock-notice-service request)
          body (:body response)]
      (is (= 200 (:code body)))
      (is (= 1 (:total (:data body))))
      (is (seq (:rows (:data body)))))))

(deftest test-get-notice
  (testing "获取通知公告详情"
    (let [request {:path-params {:id "1"}}
          response (notice/get-notice mock-notice-service request)
          body (:body response)]
      (is (= 200 (:code body)))
      (is (= "test" (:notice_name (:data body)))))))

(deftest test-get-notice-not-found
  (testing "获取不存在通知公告"
    (let [request {:path-params {:id "999"}}
          response (notice/get-notice
                    {:query-fn (fn ([_q _p _opts] nil) ([_q _p] nil) ([_q] nil))}
                    request)
          body (:body response)]
      (is (= 500 (:code body)))
      (is (= "通知公告不存在" (:msg body))))))

(deftest test-create-notice
  (testing "新增通知公告"
    (let [request {:body-params {:notice_name "new notice" :notice_type "2" :status "0"}
                   :identity {:user-name "admin"}}
          response (notice/create-notice mock-notice-service request)
          body (:body response)]
      (is (= 200 (:code body)))
      (is (= "创建成功" (:data body))))))

(deftest test-update-notice
  (testing "更新通知公告"
    (let [request {:path-params {:id "1"}
                   :body-params {:notice_name "updated" :notice_type "1" :status "0" :remark "ok"}
                   :identity {:user-name "admin"}}
          response (notice/update-notice mock-notice-service request)
          body (:body response)]
      (is (= 200 (:code body)))
      (is (= "更新成功" (:data body))))))

(deftest test-delete-notice
  (testing "删除通知公告"
    (let [request {:path-params {:id "1"}}
          response (notice/delete-notice mock-notice-service request)
          body (:body response)]
      (is (= 200 (:code body)))
      (is (= "删除成功" (:msg body)))
      (is (= {} (:data body))))))
