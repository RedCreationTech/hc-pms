(ns com.ruoyi.rouyi.web.controllers.job-test
  "定时任务控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.job :as job]))

(def mock-job-service
  {:query-fn (fn [q p] (case q
                          :list-jobs {:rows [{:job_id 1 :job_name "test"}] :total 1}
                          :find-job-by-id {:job_id 1 :job_name "test"}
                          :create-job! [{:job_id 2}]
                          :update-job! nil
                          :delete-job! nil
                          :execute-job! nil
                          :change-status! nil
                          []))})

(deftest test-list-jobs
  (testing "查询任务列表"
    (let [request {:query-params {}}
          response (job/list-jobs {:job-service mock-job-service} request)]
      (is (map? response)))))

(deftest test-get-job
  (testing "获取任务详情"
    (let [request {:path-params {:id "1"}}
          response (job/get-job {:job-service mock-job-service} request)]
      (is (map? response)))))

(deftest test-create-job
  (testing "创建任务"
    (let [request {:body-params {:job_name "test" :job_group "DEFAULT" :invoke_target "test" :cron_expression "0 0 0 * * ?"}}
          response (job/create-job {:job-service mock-job-service} request)]
      (is (map? response)))))

(deftest test-update-job
  (testing "更新任务"
    (let [request {:path-params {:id "1"} :body-params {:job_name "updated"}}
          response (job/update-job {:job-service mock-job-service} request)]
      (is (map? response)))))

(deftest test-delete-job
  (testing "删除任务"
    (let [request {:path-params {:id "1"}}
          response (job/delete-job {:job-service mock-job-service} request)]
      (is (map? response)))))

(deftest test-execute-job
  (testing "执行任务"
    (let [request {:body-params {:job_id 1 :job_group "DEFAULT"}}
          response (job/execute-job {:job-service mock-job-service} request)]
      (is (map? response)))))

(deftest test-change-status
  (testing "修改任务状态"
    (let [request {:body-params {:job_id 1 :status "0"}}
          response (job/change-status {:job-service mock-job-service} request)]
      (is (map? response)))))
