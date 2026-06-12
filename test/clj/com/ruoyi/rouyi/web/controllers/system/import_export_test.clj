(ns com.ruoyi.rouyi.web.controllers.system.import-export-test
  "导入导出控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.import-export :as import-export]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]))

(def mock-user-service
  {:query-fn (fn [q p] (case q
                          :list-users {:rows [{:user_id 1 :user_name "test" :nick_name "测试用户" :email "test@test.com" :phonenumber "13800138000" :sex "0" :status "0" :dept_id 1}] :total 1}
                          :create-user! [{:user_id 2}]
                          []))})

(def mock-role-service
  {:query-fn (fn [q p] (case q
                          :list-roles [{:role_id 1 :role_name "管理员"}]
                          []))})

(def mock-menu-service
  {:query-fn (fn [q p] (case q
                          :list-menus [{:menu_id 1 :menu_name "系统管理"}]
                          []))})

(def mock-dept-service
  {:query-fn (fn [q p] (case q
                          :list-depts [{:dept_id 1 :dept_name "总公司"}]
                          []))})

(def mock-post-service
  {:query-fn (fn [q p] (case q
                          :list-posts [{:post_id 1 :post_name "董事长"}]
                          []))})

(def mock-config-service
  {:query-fn (fn [q p] (case q
                          :list-configs [{:config_id 1 :config_name "test"}]
                          []))})

(def mock-dict-service
  {:query-fn (fn [q p] (case q
                          :list-dict-types [{:dict_id 1 :dict_name "test"}]
                          []))})

(deftest test-export-users
  (testing "导出用户数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-users {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-import-template
  (testing "下载导入模板"
    (let [request {}
          response (import-export/import-template {} request)]
      (is (map? response)))))

(deftest test-export-roles
  (testing "导出角色数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-roles {:role-service mock-role-service} request)]
      (is (map? response)))))

(deftest test-export-menus
  (testing "导出菜单数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-menus {:menu-service mock-menu-service} request)]
      (is (map? response)))))

(deftest test-export-depts
  (testing "导出部门数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-depts {:dept-service mock-dept-service} request)]
      (is (map? response)))))

(deftest test-export-posts
  (testing "导出岗位数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-posts {:post-service mock-post-service} request)]
      (is (map? response)))))

(deftest test-export-dict-types
  (testing "导出字典数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-dict-types {:dict-service mock-dict-service} request)]
      (is (map? response)))))

(deftest test-export-configs
  (testing "导出参数数据"
    (let [request {:identity {:user-id 1 :roles [{:role_id 1 :data_scope "1"}]} :query-params {}}
          response (import-export/export-configs {:config-service mock-config-service} request)]
      (is (map? response)))))
