(ns com.ruoyi.web.controllers.register-test
  "用户注册控制器测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.web.controllers.register :as register]))


(def mock-user-service
  "模拟用户领域服务,支持注册成功场景所需的查询."
  {:query-fn (fn [q p]
               (case q
                 :find-config-by-key (when (= "sys.account.registerUser" (:config_key p)) {:config_value "true"})
                 :find-user-by-name nil
                 :create-user! nil
                 :last-insert-rowid {:last_insert_rowid 1}
                 nil))})


(deftest test-register-success
  (testing "新用户注册成功"
    (let [request {:body-params {:username "newuser" :password "123456"}}
          response (register/register {:user-service mock-user-service} request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= "注册成功" (get-in response [:body :msg]))))))


(deftest test-register-existing-user
  (testing "注册账号已存在"
    (let [service (assoc mock-user-service :query-fn
                         (fn [q p]
                           (case q
                             :find-config-by-key {:config_value "true"}
                             :find-user-by-name {:user_id 1 :user_name "existing"}
                             nil)))
          request {:body-params {:username "existing" :password "123456"}}
          response (register/register {:user-service service} request)]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (= "注册账号已存在" (get-in response [:body :msg]))))))


(deftest test-register-exception
  (testing "创建用户时抛出异常"
    (let [service (assoc mock-user-service :query-fn
                         (fn [q p]
                           (case q
                             :find-config-by-key {:config_value "true"}
                             :find-user-by-name nil
                             :create-user! (throw (RuntimeException. "数据库错误"))
                             :last-insert-rowid {:last_insert_rowid 1}
                             nil)))
          request {:body-params {:username "newuser" :password "123456"}}
          response (register/register {:user-service service} request)]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (= "数据库错误" (get-in response [:body :msg]))))))


(deftest test-register-disabled-by-default
  (testing "参数未开启注册时拒绝"
    (let [service {:query-fn (fn [q _] (case q :find-config-by-key nil nil))}
          response (register/register {:user-service service} {:body-params {:username "newuser" :password "123456"}})]
      (is (= 403 (get-in response [:body :code]))))))


(deftest test-register-password-policy
  (testing "密码长度不满足策略时拒绝"
    (let [response (register/register {:user-service mock-user-service} {:body-params {:username "newuser" :password "123"}})]
      (is (= 400 (get-in response [:body :code]))))))
