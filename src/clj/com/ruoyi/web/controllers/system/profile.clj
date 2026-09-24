(ns com.ruoyi.web.controllers.system.profile
  "个人中心控制器."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.system.user :as user-service]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [ring.util.response :as response]))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- fail
  [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))


(defn get-profile
  "获取当前用户个人信息."
  [{:keys [user-service]} request]
  (let [identity (:identity request)
        user-id (:user-id identity)]
    (if-let [user (user-service/find-user-by-id user-service user-id)]
      (ok (select-keys user [:user_id :user_name :nick_name :avatar :email :phonenumber :sex]))
      (fail "用户不存在"))))


(defn update-profile
  "更新当前用户个人信息."
  [{:keys [user-service]} request]
  (try
    (let [identity (:identity request)
          user-id (:user-id identity)
          ;; 个人中心只允许维护昵称, 邮箱, 手机与性别; 角色, 部门, 状态与密码走各自受控入口
          params (merge (select-keys (:body-params request) [:nick_name :email :phonenumber :sex])
                        {:user-id user-id :update_by (:user-name identity "")})]
      (user-service/update-user! user-service params)
      (ok "更新成功"))
    (catch Exception e
      (fail (.getMessage e)))))


(defn- save-avatar!
  "保存上传的头像文件."
  [upload]
  (let [ext (some->> (:filename upload) (re-find #"(?i)\.(jpg|jpeg|png|gif|webp|bmp)$") first str/lower-case)
        _ (when-not ext (throw (Exception. "头像只支持 jpg, png, gif, webp, bmp 图片")))
        ;; 存储文件名由服务端生成, 不使用客户端文件名
        filename (str (System/currentTimeMillis) "_" (java.util.UUID/randomUUID) ext)
        upload-dir (or (System/getProperty "app.upload.dir") "uploads/avatar")
        file (java.io.File. (str upload-dir "/" filename))]
    (.mkdirs (.getParentFile file))
    (clojure.java.io/copy (:tempfile upload) file)
    (str "/" upload-dir "/" filename)))


(defn upload-avatar
  "上传头像."
  [{:keys [user-service]} request]
  (try
    (let [identity (:identity request)
          user-id (:user-id identity)
          {:keys [avatarfile]} (:params-params request)
          avatar-url (when avatarfile (save-avatar! avatarfile))]
      (when avatar-url
        (user-service/update-user! user-service {:user-id user-id :avatar avatar-url}))
      (ok {:avatar avatar-url}))
    (catch Exception e
      (fail (.getMessage e)))))


(defn change-password
  "修改当前用户密码."
  [{:keys [user-service]} request]
  (try
    (let [identity (:identity request)
          user-id (:user-id identity)
          {:keys [old_password new_password]} (:body-params request)]
      (if (or (str/blank? old_password) (str/blank? new_password))
        (fail "旧密码和新密码不能为空")
        (let [user (user-service/find-user-by-id user-service user-id)]
          (if (security/verify-password old_password (:password user))
            (do
              (user-service/update-user! user-service {:user-id user-id :password new_password})
              ;; 改密后此前签发的全部令牌失效 (含当前会话), 前端提示重新登录
              (online/revoke-user! user-id (:user_name user))
              (ok 200 "密码修改成功, 请重新登录" {:relogin true}))
            (fail "旧密码错误")))))
    (catch Exception e
      (fail (.getMessage e)))))
