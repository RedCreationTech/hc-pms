(ns com.ruoyi.domain.system.user-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.system.user :as user]))


(def mock-users
  [{:user_id 1 :user_name "admin" :nick_name "管理员" :status "0" :dept_id 1}
   {:user_id 2 :user_name "user1" :nick_name "用户1" :status "0" :dept_id 2}])


(defn- mock-query-fn
  [q p & rest]
  (case q
    :list-users {:rows mock-users :total 2}
    :find-user-by-id (first (filter #(= (:user_id %) (:user_id p)) mock-users))
    :find-user-by-name (first (filter #(= (:user_name %) (:user_name p)) mock-users))
    :find-user-by-phone (first (filter #(= (:phonenumber %) (:phonenumber p)) mock-users))
    :find-user-by-email (first (filter #(= (:email %) (:email p)) mock-users))
    :list-depts [{:dept_id 1 :parent_id 0 :ancestors "0"}
                 {:dept_id 2 :parent_id 1 :ancestors "0,1"}]
    :create-user! [{:user_id 3}]
    :update-user! nil
    :delete-user! nil
    :list-roles-by-user-id [{:role_id 1 :role_name "管理员"}]
    :update-user-roles! nil
    :delete-user-roles! nil
    :delete-user-posts! nil
    :insert-user-role! nil
    :insert-user-post! nil
    :last-insert-rowid {:last_insert_rowid 3}
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
    (let [result (user/create-user! mock-service {:user_name "newuser" :nick_name "新用户" :password "123456" :roles [] :posts []})]
      (is (some? result)))))


(deftest test-delete-user
  (testing "删除用户"
    (let [result (user/delete-user! mock-service 2)]
      (is (nil? result)))))


(deftest test-delete-admin-user-denied
  (testing "admin 用户不能删除"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"admin 用户不能删除"
          (user/delete-user! mock-service 1)))))


(deftest test-create-duplicate-user-denied
  (testing "登录账号不能重复"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"登录账号不能重复"
          (user/create-user! mock-service {:user_name "admin" :nick_name "管理员" :password "123456" :roles [] :posts []})))))


(deftest test-get-user-roles
  (testing "获取用户角色"
    (let [result (user/get-user-roles mock-service 1)]
      (is (seq result)))))


(deftest test-update-user-roles
  (testing "更新用户角色"
    (let [result (user/update-user-roles! mock-service {:user-id 1 :role-ids [1 2]})]
      (is (nil? result)))))


(deftest test-update-user-with-password-and-relations
  (testing "更新用户：修改密码、角色、岗位"
    (let [result (user/update-user! mock-service
                                    {:user-id 1
                                     :user_name "admin"
                                     :password "newpwd"
                                     :roles [1 2]
                                     :posts [1]})]
      (is (= 1 result)))))


(deftest test-update-user-without-password
  (testing "更新用户：不修改密码"
    (let [result (user/update-user! mock-service
                                    {:user-id 1
                                     :user_name "admin"
                                     :roles []
                                     :posts []})]
      (is (= 1 result)))))
