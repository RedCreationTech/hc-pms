(ns com.ruoyi.rouyi.domain.system.user-test
  "用户领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.user :as user]))

;; ─── 测试用 mock 数据 ──────────────────────────────────────────────────────

(def mock-users
  [{:user_id 1 :user_name "admin" :nick_name "管理员" :status "0"}
   {:user_id 2 :user_name "user1" :nick_name "用户1" :status "0"}])

(def mock-roles
  [{:role_id 1 :role_name "管理员"}])

(def mock-depts
  [{:dept_id 1 :dept_name "总公司"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-users {:rows mock-users :total 2}
    :find-user-by-id (first mock-users)
    :list-roles-for-user mock-roles
    :list-depts-for-user mock-depts
    :create-user! [{:user_id 3}]
    :update-user! nil
    :delete-user! nil
    []))

(def mock-service {:query-fn mock-query-fn})

;; ─── 测试用例 ──────────────────────────────────────────────────────

(deftest test-list-users
  (testing "查询用户列表"
    (let [result (user/list-users mock-service {})]
      (is (some? result)))))

(deftest test-find-user-by-id
  (testing "根据ID查询用户"
    (let [result (user/find-user-by-id mock-service 1)]
      (is (some? result))
      (is (= "admin" (:user_name result))))))

(deftest test-find-user-by-id-not-found
  (testing "查询不存在的用户"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (user/find-user-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
