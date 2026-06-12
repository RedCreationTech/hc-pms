(ns com.ruoyi.rouyi.web.controllers.system.profile-test
  "个人信息控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.profile :as profile]))

(def mock-user-service
  {:query-fn (fn [q p] (case q
                          :find-user-by-id {:user_id 1 :user_name "admin"}
                          :update-user! nil
                          []))})

(deftest test-get-profile
  (testing "获取个人信息"
    (let [request {:identity {:user-id 1}}
          response (profile/get-profile {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-update-profile
  (testing "更新个人信息"
    (let [request {:identity {:user-id 1} :body-params {:nick_name "updated"}}
          response (profile/update-profile {:user-service mock-user-service} request)]
      (is (map? response)))))

(deftest test-change-password
  (testing "修改密码"
    (let [request {:identity {:user-id 1} :body-params {:old_password "123456" :new_password "654321"}}
          response (profile/change-password {:user-service mock-user-service} request)]
      (is (map? response)))))
