(ns com.ruoyi.web.controllers.system.dept-test
  "部门控制器测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.system.dept :as dept]))


(def admin-identity
  {:user-id 1 :user-name "admin"})


(def mock-dept-service
  {:query-fn (fn [q p]
               (case q
                 :list-depts [{:dept_id 1 :dept_name "test"}]
                 :find-dept-by-id {:dept_id 1 :dept_name "test"}
                 :create-dept! [{:dept_id 2}]
                 :last-insert-rowid {:last_insert_rowid 2}
                 :update-dept! nil
                 :delete-dept! nil
                 []))})


(deftest test-list-depts
  (testing "查询部门列表"
    (let [request {:query-params {}}
          response (dept/list-depts {:dept-service mock-dept-service} request)]
      (is (map? response)))))


(deftest test-dept-tree
  (testing "获取部门树"
    (let [response (dept/dept-tree {:dept-service mock-dept-service} {})]
      (is (map? response))
      (is (= 200 (:status response))))))


(deftest test-get-dept
  (testing "获取部门详情"
    (let [request {:path-params {:id "1"}}
          response (dept/get-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))


(deftest test-get-dept-not-found
  (testing "获取不存在的部门"
    (let [service {:query-fn (fn [q p] (case q :find-dept-by-id nil []))}
          request {:path-params {:id "999"}}
          response (dept/get-dept {:dept-service service} request)]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "部门不存在" (:msg (:body response)))))))


(deftest test-create-dept
  (testing "创建部门"
    (let [request {:body-params {:dept_name "test" :parent_id 0}
                   :identity admin-identity}
          response (dept/create-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))


(deftest test-update-dept
  (testing "更新部门"
    (let [request {:path-params {:id "1"} :body-params {:dept_name "updated"}
                   :identity admin-identity}
          response (dept/update-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))


(deftest test-delete-dept
  (testing "删除部门"
    (let [request {:path-params {:id "1"}}
          response (dept/delete-dept {:dept-service mock-dept-service} request)]
      (is (map? response)))))


(deftest test-change-status
  (testing "修改部门状态"
    (let [request {:path-params {:id "1"}
                   :body-params {:status "1"}
                   :identity admin-identity}
          response (dept/change-status {:dept-service mock-dept-service} request)]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "状态修改成功" (:data (:body response)))))))
