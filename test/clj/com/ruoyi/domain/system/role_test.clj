(ns com.ruoyi.domain.system.role-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.system.role :as role]))


(def mock-roles
  [{:role_id 1 :role_name "管理员" :role_key "admin" :role_sort 1 :status "0"}
   {:role_id 2 :role_name "普通用户" :role_key "user" :role_sort 2 :status "0"}])


(def mock-menus
  [{:menu_id 1 :menu_name "系统管理" :parent_id 0}
   {:menu_id 2 :menu_name "用户管理" :parent_id 1}])


(defn- mock-query-fn
  [q p & rest]
  (case q
    :list-roles mock-roles
    :find-role-by-id (first mock-roles)
    :list-menus-by-role-id mock-menus
    :create-role! [{:role_id 3}]
    :last-insert-rowid {:last_insert_rowid 3}
    :update-role! nil
    :delete-role! nil
    :delete-role-menus! nil
    :insert-role-menu! nil
    :list-users-by-role [{:user_id 1 :user_name "admin"}]
    :list-users-not-in-role [{:user_id 2 :user_name "user1"}]
    :delete-user-role! nil
    :insert-user-role! nil
    []))


(def mock-service {:query-fn mock-query-fn})


(deftest test-list-roles
  (testing "查询角色列表"
    (let [result (role/list-roles mock-service {})]
      (is (seq result))
      (is (= 2 (count result))))))


(deftest test-find-role-by-id
  (testing "根据ID查询角色"
    (let [result (role/find-role-by-id mock-service 1)]
      (is (some? result))
      (is (= "管理员" (:role_name result)))
      (is (contains? result :menu-ids)))))


(deftest test-create-role
  (testing "创建角色"
    (let [captured (atom nil)
          query-fn (fn [q p]
                     (case q
                       :create-role! (do (reset! captured p) nil)
                       :last-insert-rowid {:last_insert_rowid 3}
                       :insert-role-menu! nil
                       []))
          result (role/create-role! {:query-fn query-fn}
                                    {:role_name "新角色"
                                     :role_key "new"
                                     :menu-ids [1 2]})]
      (is (= 3 result))
      (is (= "1" (:data_scope @captured)))
      (is (= true (:menu_check_strictly @captured)))
      (is (= true (:dept_check_strictly @captured)))
      (is (= "0" (:status @captured)))
      (is (contains? @captured :create_by)))))


(deftest test-update-role
  (testing "更新角色"
    (let [result (role/update-role! mock-service {:role-id 1 :role_name "更新后的角色" :menu-ids [1]})]
      (is (= 1 result)))))


(deftest test-update-role-with-menu-only
  (testing "只更新角色菜单"
    (let [result (role/update-role! mock-service {:role-id 1 :menu-ids [1 2 3]})]
      (is (= 1 result)))))


(deftest test-delete-role
  (testing "删除角色"
    (let [result (role/delete-role! mock-service 1)]
      (is (nil? result)))))


(deftest test-list-allocated-users
  (testing "查询已分配该角色的用户"
    (let [result (role/list-allocated-users mock-service {:role-id 1})]
      (is (seq result))
      (is (= 1 (count result))))))


(deftest test-list-unallocated-users
  (testing "查询未分配该角色的用户"
    (let [result (role/list-unallocated-users mock-service {:role-id 1})]
      (is (seq result))
      (is (= 1 (count result))))))


(deftest test-cancel-auth-user
  (testing "取消单个用户角色授权"
    (is (nil? (role/cancel-auth-user! mock-service {:role-id 1 :user-id 2})))))


(deftest test-cancel-auth-user-all
  (testing "批量取消用户角色授权"
    (is (nil? (role/cancel-auth-user-all! mock-service {:role-id 1 :user-ids [2 3]})))))


(deftest test-select-auth-user-all
  (testing "批量授权用户角色"
    (is (nil? (role/select-auth-user-all! mock-service {:role-id 1 :user-ids [2 3]})))))


(deftest test-dept-tree-by-role
  (testing "获取角色关联部门树: 全部有效部门 + 已选自定义部门"
    (let [svc {:query-fn (fn [q _]
                           (case q
                             :dept-options [{:dept_id 1 :parent_id 0 :dept_name "总部"} {:dept_id 2 :parent_id 1 :dept_name "研发"}]
                             :list-role-dept-ids [{:dept_id 2}]
                             nil))}
          result (role/dept-tree-by-role svc 7)]
      (is (= 2 (count (:depts result))))
      (is (= [2] (:checked-keys result))))))


(deftest test-update-data-scope
  (testing "自定义范围替换角色部门; 其他范围清空; 非法范围与空自定义部门拒绝"
    (let [calls (atom [])
          svc {:query-fn (fn [q p] (swap! calls conj [q p]) nil)}]
      (role/update-data-scope! svc 7 "2" ["4" 5 "4"])
      (is (= [[:insert-role-dept! {:role_id 7 :dept_id 4}] [:insert-role-dept! {:role_id 7 :dept_id 5}]]
             (filterv #(= :insert-role-dept! (first %)) @calls)))
      (is (= "2" (:data_scope (second (first (filter #(= :update-role! (first %)) @calls))))))
      (reset! calls [])
      (role/update-data-scope! svc 7 "3" [4])
      (is (some #(= :delete-role-depts! (first %)) @calls))
      (is (not-any? #(= :insert-role-dept! (first %)) @calls))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"无效" (role/update-data-scope! svc 7 "9" [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"至少选择" (role/update-data-scope! svc 7 "2" []))))))


(deftest test-get-role-perms
  (testing "获取角色权限标识"
    (let [result (role/get-role-perms mock-service 1)]
      (is (set? result)))))
