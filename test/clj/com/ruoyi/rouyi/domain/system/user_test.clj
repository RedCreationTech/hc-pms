(ns com.ruoyi.rouyi.domain.system.user-test
  "用户领域服务测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.domain.system.user :as user]))

(def mock-users
  [{:user_id 1 :user_name "admin" :nick_name "管理员" :status "0" :dept_id 1}
   {:user_id 2 :user_name "user1" :nick_name "用户1" :status "0" :dept_id 2}])

(def mock-roles
  [{:role_id 1 :role_name "管理员"}])

(defn- mock-query-fn [query-name params]
  (case query-name
    :list-users {:rows mock-users :total 2}
    :find-user-by-id (first mock-users)
    :find-user-by-name (first mock-users)
    :list-roles-for-user mock-roles
    :create-user! [{:user_id 3}]
    :update-user! nil
    :delete-user! nil
    :get-user-roles mock-roles
    :update-user-roles! nil
    []))

(def mock-service {:query-fn mock-query-fn})

(deftest test-list-users
  (testing "查询用户列表"
    (let [result (user/list-users mock-service {})]
      (is (some? result)))))

(deftest test-find-user-by-id
  (testing "根据ID查询用户"
    (let [result (user/find-user-by-id mock-service 1)]
      (is (some? result))
      (is (= "admin" (:user_name result))))))

(deftest test-find-user-by-name
  (testing "根据用户名查询用户"
    (let [result (user/find-user-by-name mock-service "admin")]
      (is (some? result))
      (is (= 1 (:user_id result))))))

(deftest test-create-user
  (testing "创建用户"
    (let [result (user/create-user! mock-service {:user_name "newuser" :nick_name "新用户" :password "123456"})]
      (is (some? result)))))

(deftest test-update-user
  (testing "更新用户"
    (let [result (user/update-user! mock-service {:user_id 1 :nick_name "更新后的管理员"})]
      (is (nil? result)))))

(deftest test-delete-user
  (testing "删除用户"
    (let [result (user/delete-user! mock-service 1)]
      (is (nil? result)))))

(deftest test-get-user-roles
  (testing "获取用户角色"
    (let [result (user/get-user-roles mock-service 1)]
      (is (seq result)))))

(deftest test-update-user-roles
  (testing "更新用户角色"
    (let [result (user/update-user-roles! mock-service 1 [1 2])]
      (is (nil? result)))))

(deftest test-find-user-by-id-not-found
  (testing "查询不存在的用户"
    (with-redefs [mock-query-fn (fn [_ _] nil)]
      (let [result (user/find-user-by-id {:query-fn mock-query-fn} 999)]
        (is (nil? result))))))
