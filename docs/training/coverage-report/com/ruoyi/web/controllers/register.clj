✔ (ns com.ruoyi.web.controllers.register
?   "用户注册控制器。"
?   (:require
?    [com.ruoyi.domain.system.user :as user-service]
?    [ring.util.response :as response]))
  
✔ (defn- ok
✔   ([msg] (-> (response/response {:code 200 :msg msg})
✔              (response/content-type "application/json")))
✔   ([code msg] (-> (response/response {:code code :msg msg})
✔                   (response/content-type "application/json"))))
  
✔ (defn register
?   "用户注册。"
?   [{:keys [user-service]} request]
✔   (try
✔     (let [params (:body-params request)
✔           username (:username params)]
✔       (if-let [existing (user-service/find-user-by-name user-service username)]
✔         (ok 500 "注册账号已存在")
✔         (do (user-service/create-user! user-service (assoc params :user_name username))
✔             (ok "注册成功"))))
✔     (catch Exception e (ok 500 (.getMessage e)))))
