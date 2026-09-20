(ns com.ruoyi.web.controllers.job-test
  "定时任务控制器测试。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.scheduler :as scheduler-core]
    [com.ruoyi.web.controllers.job :as job]))


(def ^:private test-job
  {:job_id 1
   :job_name "test"
   :job_group "DEFAULT"
   :invoke_target "com.ruoyi.task/test-job"
   :cron_expression "0 0 0 * * ?"
   :misfire_policy "1"
   :concurrent "0"
   :status "0"
   :remark "test"})


(defn mock-query-fn
  "根据查询关键字返回固定响应的 mock query-fn。"
  [q p]
  (case q
    :list-jobs [test-job]
    :find-job-by-id test-job
    :create-job! nil
    :last-insert-job-id {:job_id 2}
    :update-job! nil
    :delete-job! nil
    :list-job-logs [{:job_log_id 1 :job_name "test"}]
    :count-job-logs [{:total 1}]
    :execute-job! nil
    :clean-job-logs! nil
    nil))


(def mock-service
  {:query-fn mock-query-fn})


(deftest test-list-jobs
  (testing "查询定时任务列表"
    (let [request {:query-params {:job_name "test" :status "0"}}
          response (job/list-jobs mock-service request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= [test-job] (get-in response [:body :data]))))))


(deftest test-get-job-found
  (testing "获取定时任务详情（存在）"
    (let [request {:path-params {:id "1"}}
          response (job/get-job mock-service request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= test-job (get-in response [:body :data]))))))


(deftest test-get-job-not-found
  (testing "获取定时任务详情（不存在）"
    (let [service {:query-fn (fn [q _] (when (= q :find-job-by-id) nil))}
          request {:path-params {:id "999"}}
          response (job/get-job service request)]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (= "任务不存在" (get-in response [:body :msg]))))))


(deftest test-create-job
  (testing "创建定时任务"
    (with-redefs [scheduler-core/schedule-job! (fn [_] nil)]
      (let [request {:identity {:user-name "admin"}
                     :body-params {:job_name "new"
                                   :job_group "DEFAULT"
                                   :invoke_target "com.ruoyi.task/test-job"
                                   :cron_expression "0 0 0 * * ?"
                                   :misfire_policy "1"
                                   :concurrent "0"
                                   :status "0"
                                   :remark "new"}}
            response (job/create-job mock-service request)]
        (is (= 200 (:status response)))
        (is (= 200 (get-in response [:body :code])))
        (is (= {:job_id 2} (get-in response [:body :data])))))))


(deftest test-create-job-invalid-cron
  (testing "创建定时任务（cron 非法）"
    (let [request {:identity {:user-name "admin"}
                   :body-params {:job_name "new"
                                 :job_group "DEFAULT"
                                 :invoke_target "com.ruoyi.task/test-job"
                                 :cron_expression "invalid"}}
          response (job/create-job mock-service request)]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (= "cron 表达式不合法" (get-in response [:body :msg]))))))


(deftest test-update-job
  (testing "更新定时任务"
    (with-redefs [scheduler-core/reschedule-job! (fn [_] nil)]
      (let [request {:identity {:user-name "admin"}
                     :path-params {:id "1"}
                     :body-params {:job_name "updated"
                                   :cron_expression "0 0 0 * * ?"
                                   :invoke_target "com.ruoyi.task/test-job"}}
            response (job/update-job mock-service request)]
        (is (= 200 (:status response)))
        (is (= 200 (get-in response [:body :code])))
        (is (= "更新成功" (get-in response [:body :data])))))))


(deftest test-delete-job
  (testing "删除定时任务"
    (with-redefs [scheduler-core/unschedule-job! (fn [_ _] nil)]
      (let [request {:path-params {:id "1"}}
            response (job/delete-job mock-service request)]
        (is (= 200 (:status response)))
        (is (= 200 (get-in response [:body :code])))
        (is (= "删除成功" (get-in response [:body :data])))))))


(deftest test-list-job-logs
  (testing "查询任务日志列表"
    (let [request {:query-params {:job-name "test" :status "0" :page-num "1" :page-size "10"}}
          response (job/list-job-logs mock-service request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= 1 (get-in response [:body :data :total])))
      (is (= [{:job_log_id 1 :job_name "test"}] (get-in response [:body :data :rows]))))))


(deftest test-execute-job
  (testing "执行一次定时任务"
    (let [request {:path-params {:id "1"}}
          response (job/execute-job mock-service request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= "执行成功" (get-in response [:body :data]))))))


(deftest test-change-status-resume
  (testing "恢复定时任务"
    (with-redefs [scheduler-core/resume-job! (fn [_ _] nil)]
      (let [request {:identity {:user-name "admin"}
                     :path-params {:id "1"}
                     :body-params {:status "0"}}
            response (job/change-status mock-service request)]
        (is (= 200 (:status response)))
        (is (= 200 (get-in response [:body :code])))
        (is (= "状态修改成功" (get-in response [:body :data])))))))


(deftest test-change-status-pause
  (testing "暂停定时任务"
    (with-redefs [scheduler-core/pause-job! (fn [_ _] nil)]
      (let [request {:identity {:user-name "admin"}
                     :path-params {:id "1"}
                     :body-params {:status "1"}}
            response (job/change-status mock-service request)]
        (is (= 200 (:status response)))
        (is (= 200 (get-in response [:body :code])))
        (is (= "状态修改成功" (get-in response [:body :data])))))))


(deftest test-run-once
  (testing "立即触发任务"
    (with-redefs [scheduler-core/trigger-job! (fn [_ _] nil)]
      (let [request {:path-params {:id "1"}}
            response (job/run-once {} request)]
        (is (= 200 (:status response)))
        (is (= 200 (get-in response [:body :code])))
        (is (= "任务 1 已触发执行" (get-in response [:body :data])))))))


(deftest test-clean-logs
  (testing "清空任务日志"
    (let [response (job/clean-logs mock-service {})]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= "日志已清空" (get-in response [:body :data]))))))
