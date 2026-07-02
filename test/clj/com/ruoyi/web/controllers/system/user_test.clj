(ns com.ruoyi.web.controllers.system.user-test
  "用户管理控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.web.controllers.system.user :as user]))

(def admin-identity
  {:user-id 1 :user-name "admin" :roles [{:role-key "admin" :data-scope 1}]})

(def mock-user-service
  {:query-fn (fn [q p]
               (case q
                 :list-users [{:user_id 1 :user_name "admin" :nick_name "管理员"
                               :email "admin@ruoyi.vip" :phonenumber "13800138000"
                               :sex "0" :status "0" :dept_id 1 :remark ""}]
                 :count-users {:total 1}
                 :find-user-by-id {:user_id 1 :user_name "admin" :nick_name "管理员"
                                   :dept_id 1 :user_type "00" :email "admin@ruoyi.vip"
                                   :phonenumber "13800138000" :sex "0" :avatar ""
                                   :status "0" :remark "" :password "hashed"}
                 :find-user-by-name nil
                 :find-user-by-phone nil
                 :find-user-by-email nil
                 :list-depts [{:dept_id 1 :parent_id 0 :ancestors "0"}]
                 :create-user! nil
                 :last-insert-rowid {:last_insert_rowid 2}
                 :update-user! nil
                 :delete-user! nil
                 :list-roles-by-user-id [{:role_id 1 :role_name "管理员"}]
                 :list-posts-by-user-id [{:post_id 1 :post_name "董事长"}]
                 :insert-user-role! nil
                 :insert-user-post! nil
                 :delete-user-roles! nil
                 :delete-user-posts! nil
                 nil))})

(deftest test-list-users
  (testing "查询用户列表"
    (let [request {:query-params {} :identity admin-identity}
          response (user/list-users {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-list-users-filter-query
  (testing "用户管理所有检索条件会传给领域 SQL 参数"
    (let [captured (atom nil)
          service {:query-fn (fn [q p]
                               (case q
                                 :find-user-by-id {:user_id 1 :user_name "admin" :roles [{:role_key "admin"}]}
                                 :list-depts [{:dept_id 4 :parent_id 2 :ancestors "0,1,2"}]
                                 :list-users (do (reset! captured p) [])
                                 :count-users {:total 0}
                                 []))}
          request {:query-params {"user_name" "admin"
                                  "phonenumber" "158"
                                  "status" "0"
                                  "dept_id" "4"
                                  "beginTime" "2026-06-02"
                                  "endTime" "2026-06-30"
                                  "page" "1"
                                  "size" "10"}
                   :identity admin-identity}
          response (user/list-users {:user-service service} request)]
      (is (= 200 (:status response)))
      (is (= "admin" (:user_name @captured)))
      (is (= "158" (:phonenumber @captured)))
      (is (= "0" (:status @captured)))
      (is (= 1 (:dept_filter_enabled @captured)))
      (is (= [4] (:dept_ids @captured)))
      (is (= "2026-06-02" (:begin_time @captured)))
      (is (= "2026-06-30 23:59:59" (:end_time @captured))))))

(deftest test-get-user
  (testing "获取用户详情"
    (let [request {:path-params {:id "1"} :identity admin-identity}
          response (user/get-user {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-get-user-not-found
  (testing "获取用户详情不存在"
    (let [service {:query-fn (fn [q p] (case q
                                        :find-user-by-id nil
                                        :list-roles-by-user-id []
                                        :list-posts-by-user-id []
                                        nil))}
          request {:path-params {:id "999"} :identity admin-identity}
          response (user/get-user {:user-service service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-create-user
  (testing "创建用户"
    (let [request {:body-params {:user_name "test" :nick_name "测试" :password "123456"}
                   :identity admin-identity}
          response (user/create-user {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-create-user-validation
  (testing "创建用户缺少用户名"
    (let [request {:body-params {:nick_name "测试" :password "123456"}
                   :identity admin-identity}
          response (user/create-user {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-update-user
  (testing "更新用户"
    (let [request {:path-params {:id "1"}
                   :body-params {:nick_name "更新后" :email "new@ruoyi.vip"}
                   :identity admin-identity}
          response (user/update-user {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-update-user-with-roles-posts
  (testing "更新用户带角色岗位"
    (let [request {:path-params {:id "1"}
                   :body-params {:nick_name "更新后" :roles [1 2] :posts [1]}
                   :identity admin-identity}
          response (user/update-user {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-delete-user
  (testing "删除用户"
    (let [request {:path-params {:id "1"} :identity admin-identity}
          response (user/delete-user {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-change-status
  (testing "修改用户状态"
    (let [request {:path-params {:id "1" :status "1"} :identity admin-identity}
          response (user/change-status {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-reset-password
  (testing "重置用户密码"
    (let [request {:path-params {:id "1"}
                   :body-params {:password "admin123"}
                   :identity admin-identity}
          response (user/reset-password {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-import-users
  (testing "导入用户"
    (let [request {:body-params {:rows [{:user_name "import1" :nick_name "导入1"}
                                        {:user_name "import2" :nick_name "导入2"}]}
                   :identity admin-identity}
          response (user/import-users {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-export-users
  (testing "导出用户CSV"
    (let [request {:query-params {} :identity admin-identity}
          response (user/export-users {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-auth-role
  (testing "获取用户角色列表"
    (let [request {:path-params {:id "1"} :identity admin-identity}
          response (user/auth-role {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-update-auth-role
  (testing "分配用户角色"
    (let [request {:path-params {:id "1"}
                   :body-params {:role_ids [1 2]}
                   :identity admin-identity}
          response (user/update-auth-role {:user-service mock-user-service} request)]
      (is (map? response))
      (is (= 200 (:status response))))))

(deftest test-import-template
  (testing "下载用户导入模板"
    (let [response (user/import-template {} {})]
      (is (map? response))
      (is (= 200 (:status response))))))
