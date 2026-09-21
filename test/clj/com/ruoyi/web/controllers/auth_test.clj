(ns com.ruoyi.web.controllers.auth-test
  "认证控制器测试."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.controllers.auth :as auth]
    [com.ruoyi.web.controllers.captcha :as captcha]))


(def hashed-password (security/hash-password "admin123"))


(def base-user
  {:user_id 1
   :user_name "admin"
   :password hashed-password
   :status "0"
   :nick_name "Admin"
   :avatar ""
   :email ""
   :phonenumber ""
   :sex "0"})


(def base-roles
  [{:role_id 1 :role_key "admin" :perms "system:user:list"}])


(def base-menus
  [{:menu_id 1 :menu_name "System" :parent_id 0 :perms "system:user:list" :status "0"}])


(defn- make-query-fn
  ([] (make-query-fn base-user))
  ([user]
   (fn [q p]
     (case q
       :find-user-by-name user
       :find-user-by-id (when (= (:user_id p) (:user_id user))
                          (dissoc user :password))
       :list-roles-by-user-id base-roles
       :list-posts-by-user-id []
       :list-menus-by-role-id base-menus
       :list-menus-by-role-ids base-menus
       :create-login-log! nil
       :create-online-user! nil
       :delete-online-user! nil
       nil))))


(defn- make-user-service
  ([] (make-user-service base-user))
  ([user]
   {:query-fn (make-query-fn user)}))


(def mock-menu-service
  {:query-fn (fn [q _]
               (case q
                 :list-menus-by-role-ids base-menus
                 nil))})


(use-fixtures :each
  (fn [test-fn]
    (online/set-query-fn! (fn [_q _p] nil))
    (reset! captcha/captcha-store {})
    (test-fn)))


(deftest test-login-success
  (testing "使用正确用户名密码登录成功"
    (let [request {:body-params {:username "admin" :password "admin123"}
                   :remote-addr "127.0.0.1"}
          response (auth/login {:user-service (make-user-service)
                                :log-service (make-user-service)}
                               request)]
      (is (= 200 (-> response :body :code)))
      (is (string? (-> response :body :data :token))))))


(deftest test-login-success-with-captcha
  (testing "验证码正确时登录成功"
    (let [uuid "test-uuid"]
      (swap! captcha/captcha-store assoc uuid {:code "abcd"
                                               :expire (+ (System/currentTimeMillis) 60000)})
      (let [request {:body-params {:username "admin" :password "admin123"
                                   :captcha "AbCd" :uuid uuid}
                     :remote-addr "127.0.0.1"}
            response (auth/login {:user-service (make-user-service)
                                  :log-service (make-user-service)}
                                 request)]
        (is (= 200 (-> response :body :code)))
        (is (string? (-> response :body :data :token)))))))


(deftest test-login-invalid-captcha
  (testing "验证码错误返回 400"
    (let [uuid "bad-uuid"]
      (swap! captcha/captcha-store assoc uuid {:code "abcd"
                                               :expire (+ (System/currentTimeMillis) 60000)})
      (let [request {:body-params {:username "admin" :password "admin123"
                                   :captcha "wrong" :uuid uuid}
                     :remote-addr "127.0.0.1"}
            response (auth/login {:user-service (make-user-service)
                                  :log-service (make-user-service)}
                                 request)]
        (is (= 400 (-> response :body :code)))
        (is (= "验证码错误或已过期" (-> response :body :msg)))))))


(deftest test-login-blank-credentials
  (testing "用户名或密码为空返回 400"
    (let [request {:body-params {:username "" :password "admin123"}
                   :remote-addr "127.0.0.1"}
          response (auth/login {:user-service (make-user-service)
                                :log-service (make-user-service)}
                               request)]
      (is (= 400 (-> response :body :code)))
      (is (= "用户名和密码不能为空" (-> response :body :msg))))))


(deftest test-login-user-not-found
  (testing "用户不存在返回 400"
    (let [request {:body-params {:username "nobody" :password "admin123"}
                   :remote-addr "127.0.0.1"}
          response (auth/login {:user-service (make-user-service nil)
                                :log-service (make-user-service nil)}
                               request)]
      (is (= 400 (-> response :body :code)))
      (is (= "用户不存在" (-> response :body :msg))))))


(deftest test-login-wrong-password
  (testing "密码错误返回 400"
    (let [request {:body-params {:username "admin" :password "wrongpass"}
                   :remote-addr "127.0.0.1"}
          response (auth/login {:user-service (make-user-service)
                                :log-service (make-user-service)}
                               request)]
      (is (= 400 (-> response :body :code)))
      (is (= "密码错误" (-> response :body :msg))))))


(deftest test-login-disabled
  (testing "停用用户返回 403"
    (let [request {:body-params {:username "admin" :password "admin123"}
                   :remote-addr "127.0.0.1"}
          response (auth/login {:user-service (make-user-service (assoc base-user :status "1"))
                                :log-service (make-user-service (assoc base-user :status "1"))}
                               request)]
      (is (= 403 (-> response :body :code)))
      (is (= "用户已被停用" (-> response :body :msg))))))


(deftest test-get-info-success
  (testing "获取当前登录用户信息成功"
    (let [request {:identity {:user-id 1}}
          response (auth/get-info {:user-service (make-user-service)
                                   :menu-service mock-menu-service}
                                  request)]
      (is (= 200 (-> response :body :code)))
      (is (= "admin" (-> response :body :data :user :user_name)))
      (is (seq (-> response :body :data :roles)))
      (is (seq (-> response :body :data :permissions)))
      (is (vector? (-> response :body :data :menus))))))


(deftest test-get-info-user-not-found
  (testing "获取信息时用户不存在返回 401"
    (let [request {:identity {:user-id 999}}
          response (auth/get-info {:user-service (make-user-service)
                                   :menu-service mock-menu-service}
                                  request)]
      (is (= 401 (-> response :body :code)))
      (is (= "用户不存在" (-> response :body :msg))))))


(deftest test-logout-with-token
  (testing "携带 token 登出成功"
    (let [token (security/generate-token 1 "admin" [1])
          request {:headers {"authorization" (str "Bearer " token)}}
          response (auth/logout request)]
      (is (= 200 (-> response :body :code))))))


(deftest test-logout-without-token
  (testing "未携带 token 登出也返回成功"
    (let [request {}
          response (auth/logout request)]
      (is (= 200 (-> response :body :code))))))
