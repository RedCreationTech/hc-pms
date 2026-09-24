(ns com.ruoyi.web.controllers.register
  "用户注册控制器: 由参数 sys.account.registerUser 控制是否开放 (默认关闭); 只接受账号, 密码与昵称,
  注册用户不分配任何角色."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.system.user :as user-service]
    [com.ruoyi.web.controllers.auth :as auth]
    [ring.util.response :as response]))


(defn- ok
  ([msg] (-> (response/response {:code 200 :msg msg})
             (response/content-type "application/json")))
  ([code msg] (-> (response/response {:code code :msg msg})
                  (response/content-type "application/json"))))


(defn register
  "用户注册."
  [{:keys [user-service]} request]
  (try
    (let [params (:body-params request)
          username (some-> (:username params) str str/trim)
          password (some-> (:password params) str)]
      (cond
        (not (auth/register-enabled? (:query-fn user-service))) (ok 403 "当前系统没有开启注册功能")
        (or (str/blank? username) (not (<= 2 (count username) 20))) (ok 400 "账户长度必须在2到20个字符之间")
        (user-service/find-user-by-name user-service username) (ok 500 "注册账号已存在")
        :else
        (do (user-service/validate-password! password)
            (user-service/create-user! user-service
                                       {:user_name username :nick_name (or (some-> (:nick_name params) str str/trim not-empty) username)
                                        :password password :dept_id nil :user_type "00" :email "" :phonenumber "" :sex "0"
                                        :avatar "" :status "0" :remark "自助注册" :create_by username :roles [] :posts []})
            (ok "注册成功"))))
    (catch clojure.lang.ExceptionInfo e (ok 400 (.getMessage e)))
    (catch Exception e (ok 500 (.getMessage e)))))
