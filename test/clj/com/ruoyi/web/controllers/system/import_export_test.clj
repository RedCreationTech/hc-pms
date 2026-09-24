(ns com.ruoyi.web.controllers.system.import-export-test
  "导入导出控制器测试."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.system.import-export :as ie]))


(defn- temp-csv-file
  [content]
  (let [file (java.io.File/createTempFile "test" ".csv")]
    (.deleteOnExit file)
    (spit file content)
    file))


(def mock-user-service
  {:query-fn
   (fn [q _]
     (case q
       :list-users [{:user_id 1
                     :user_name "admin"
                     :nick_name "管理员"
                     :email "admin@ruoyi.vip"
                     :phonenumber "13800138000"
                     :sex "0"
                     :status "0"
                     :dept_id 1
                     :remark ""}]
       :count-users {:total 1}
       :find-user-by-name nil
       :find-user-by-phone nil
       :find-user-by-email nil
       :list-depts [{:dept_id 1 :parent_id 0 :ancestors "0"}]
       :create-user! nil
       :last-insert-rowid {:last_insert_rowid 2}
       :insert-user-role! nil
       :insert-user-post! nil
       nil))})


(defn mock-service
  [list-query rows]
  {:query-fn (fn [q _] (if (= q list-query) rows []))})


(def mock-role-service
  (mock-service :list-roles [{:role_id 1
                              :role_name "管理员"
                              :role_key "admin"
                              :role_sort 1
                              :status "0"}]))


(def mock-menu-service
  (mock-service :list-menus [{:menu_id 1
                              :menu_name "系统管理"
                              :parent_id 0
                              :order_num 1
                              :path "/system"
                              :component "Layout"
                              :menu_type "M"
                              :status "0"}]))


(def mock-dept-service
  (mock-service :list-depts [{:dept_id 1
                              :parent_id 0
                              :dept_name "研发部"
                              :order_num 1
                              :leader "张三"
                              :status "0"}]))


(def mock-post-service
  (mock-service :list-posts [{:post_id 1
                              :post_code "dev"
                              :post_name "开发"
                              :post_sort 1
                              :status "0"}]))


(def mock-dict-service
  {:query-fn
   (fn [q _]
     (case q
       :list-dict-types [{:dict_id 1
                          :dict_name "用户性别"
                          :dict_type "sys_user_sex"
                          :status "0"}]
       :list-dict-data [{:dict_code 1
                         :dict_sort 1
                         :dict_label "男"
                         :dict_value "0"
                         :dict_type "sys_user_sex"
                         :status "0"}]
       []))})


(def mock-config-service
  (mock-service :list-configs [{:config_id 1
                                :config_name "系统版本"
                                :config_key "sys.version"
                                :config_value "1.0.0"
                                :config_type "Y"}]))


(deftest test-import-users-success
  (testing "成功导入用户 CSV"
    (let [file (temp-csv-file (str "user_name,nick_name,email,phonenumber,sex,status,dept_id,remark\n"
                                   "testuser,测试用户,test@example.com,13800138001,0,0,1,备注"))
          request {:multipart-params {"file" {:tempfile file :filename "users.csv"}}
                   :identity {:user_name "admin"} :actor {:admin? true}}
          response (ie/import-users {:user-service mock-user-service} request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= 1 (get-in response [:body :data :success])))
      (is (= 0 (get-in response [:body :data :failed]))))))


(deftest test-import-users-empty-file
  (testing "未上传文件时导入失败"
    (let [request {:multipart-params {} :identity {:user_name "admin"}}
          response (ie/import-users {:user-service mock-user-service} request)]
      (is (= 500 (get-in response [:body :code]))))))


(deftest test-export-users
  (testing "导出用户 CSV"
    (let [request {:query-params {}
                   :identity {:user-id 1
                              :user-name "admin"
                              :roles [{:role-key "admin"}]}}
          response (ie/export-users {:user-service mock-user-service} request)]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response)))
      (is (.startsWith (:body response) "\uFEFF")))))


(deftest test-import-template
  (testing "下载用户导入模板"
    (let [response (ie/import-template {} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "user_name")))))


(deftest test-export-roles
  (testing "导出角色 CSV"
    (let [response (ie/export-roles {:role-service mock-role-service} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "管理员")))))


(deftest test-export-menus
  (testing "导出菜单 CSV"
    (let [response (ie/export-menus {:menu-service mock-menu-service} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "系统管理")))))


(deftest test-export-depts
  (testing "导出部门 CSV"
    (let [response (ie/export-depts {:dept-service mock-dept-service} {:actor {:admin? true}})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "研发部")))))


(deftest test-export-posts
  (testing "导出岗位 CSV"
    (let [response (ie/export-posts {:post-service mock-post-service} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "开发")))))


(deftest test-export-dict-types
  (testing "导出字典类型 CSV"
    (let [response (ie/export-dict-types {:dict-service mock-dict-service} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "用户性别")))))


(deftest test-export-dict-data
  (testing "导出字典数据 CSV"
    (let [response (ie/export-dict-data {:dict-service mock-dict-service} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "男")))))


(deftest test-export-configs
  (testing "导出参数配置 CSV"
    (let [response (ie/export-configs {:config-service mock-config-service} {})]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (.contains (:body response) "系统版本")))))


(deftest test-import-users-respects-data-scope
  (testing "导入目标部门不在数据范围内时该行失败"
    (let [file (temp-csv-file (str "user_name,nick_name,email,phonenumber,sex,status,dept_id,remark\n"
                                   "scopeuser,范围外,s@example.com,13800138009,0,0,5,"))
          request {:multipart-params {"file" {:tempfile file :filename "users.csv"}}
                   :identity {:user_name "mgr"}
                   :actor {:user_id 7 :dept_id 4 :roles [{:role_id 3 :data_scope "3"}]}}
          response (ie/import-users {:user-service mock-user-service} request)]
      (is (= 0 (get-in response [:body :data :success])))
      (is (= 1 (get-in response [:body :data :failed]))))))
