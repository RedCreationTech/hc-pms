(ns com.ruoyi.web.controllers.system.profile-test
  "个人中心控制器测试。"
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.controllers.system.profile :as profile])
  (:import
    (java.nio.file
      Files)
    (java.nio.file.attribute
      FileAttribute)))


(defn mock-user-service
  "返回指定用户的 mock 用户服务。"
  [{:keys [password]}]
  {:query-fn (fn [q p]
               (case q
                 :find-user-by-id {:user_id (:user_id p)
                                   :user_name "admin"
                                   :nick_name "管理员"
                                   :avatar "/uploads/avatar/default.png"
                                   :email "admin@ruoyi.vip"
                                   :phonenumber "13800138000"
                                   :sex "0"
                                   :password password}
                 :list-roles-by-user-id [{:role_id 1 :role_name "超级管理员"}]
                 :list-posts-by-user-id [{:post_id 1 :post_name "董事长"}]
                 :update-user! nil
                 nil))})


(deftest test-get-profile
  (testing "获取当前用户个人信息"
    (let [user-service (mock-user-service {:password (security/hash-password "admin123")})
          request {:identity {:user-id 1}}
          response (profile/get-profile {:user-service user-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-get-profile-not-found
  (testing "获取个人信息时用户不存在"
    (let [user-service {:query-fn (fn [q _]
                                    (case q
                                      :find-user-by-id nil
                                      nil))}
          request {:identity {:user-id 999}}
          response (profile/get-profile {:user-service user-service} request)]
      (is (map? response))
      (is (= 500 (get-in response [:body :code]))))))


(deftest test-update-profile
  (testing "更新当前用户个人信息"
    (let [user-service (mock-user-service {:password (security/hash-password "admin123")})
          request {:identity {:user-id 1}
                   :body-params {:nick_name "新昵称" :email "new@ruoyi.vip"}}
          response (profile/update-profile {:user-service user-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-upload-avatar
  (testing "上传头像"
    (let [temp-dir (Files/createTempDirectory "avatar-test" (make-array FileAttribute 0))
          temp-file (Files/createTempFile temp-dir "avatar" ".png" (make-array FileAttribute 0))
          _ (spit (.toFile temp-file) "fake image content")
          _ (System/setProperty "app.upload.dir" (str temp-dir))
          user-service (mock-user-service {:password (security/hash-password "admin123")})
          request {:identity {:user-id 1}
                   :params-params {:avatarfile {:filename "test.png"
                                                :tempfile (.toFile temp-file)}}}
          response (profile/upload-avatar {:user-service user-service} request)]
      (try
        (is (map? response))
        (is (= 200 (get-in response [:body :code])))
        (is (some? (get-in response [:body :data :avatar])))
        (finally
          (doseq [f (.listFiles (.toFile temp-dir))]
            (.delete f))
          (Files/deleteIfExists temp-dir)
          (System/clearProperty "app.upload.dir"))))))


(deftest test-upload-avatar-without-file
  (testing "未选择头像文件"
    (let [user-service (mock-user-service {:password (security/hash-password "admin123")})
          request {:identity {:user-id 1}
                   :params-params {}}
          response (profile/upload-avatar {:user-service user-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-change-password
  (testing "修改当前用户密码成功"
    (let [old-password "admin123"
          user-service (mock-user-service {:password (security/hash-password old-password)})
          request {:identity {:user-id 1}
                   :body-params {:old_password old-password
                                 :new_password "newpass123"}}
          response (profile/change-password {:user-service user-service} request)]
      (is (map? response))
      (is (= 200 (get-in response [:body :code]))))))


(deftest test-change-password-blank
  (testing "修改密码时旧密码或新密码为空"
    (let [user-service (mock-user-service {:password (security/hash-password "admin123")})
          request {:identity {:user-id 1}
                   :body-params {:old_password ""
                                 :new_password "newpass123"}}
          response (profile/change-password {:user-service user-service} request)]
      (is (map? response))
      (is (= 500 (get-in response [:body :code]))))))


(deftest test-change-password-wrong-old
  (testing "修改密码时旧密码错误"
    (let [user-service (mock-user-service {:password (security/hash-password "admin123")})
          request {:identity {:user-id 1}
                   :body-params {:old_password "wrongpass"
                                 :new_password "newpass123"}}
          response (profile/change-password {:user-service user-service} request)]
      (is (map? response))
      (is (= 500 (get-in response [:body :code]))))))
