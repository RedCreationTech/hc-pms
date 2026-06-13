(ns com.ruoyi.web.controllers.system.online
  "在线用户控制器。"
  (:require
   [ring.util.response :as response]))

(defn- ok [data]
  (-> (response/response {:code 200 :msg "操作成功" :data data})
      (response/content-type "application/json")))

(defn- success [msg]
  (-> (response/response {:code 200 :msg msg})
      (response/content-type "application/json")))

(defn- parse-int [v]
  (when v (Integer/parseInt v)))

(defn list-online
  "获取在线用户列表。"
  [{:keys [online-service]} request]
  (let [params (:query-params request)
        page (or (parse-int (get params "pageNum")) 1)
        size (or (parse-int (get params "pageSize")) 10)
        result ((:list-online online-service)
                {:login-name (get params "user_name")
                 :ipaddr (get params "ipaddr")
                 :page-num page
                 :page-size size})]
    (ok {:rows (:rows result) :total (:total result)})))

(defn force-logout
  "强退指定用户。"
  [{:keys [online-service]} request]
  (let [token-id (get-in request [:path-params :token-id])]
    ((:force-logout online-service) token-id)
    (success "操作成功")))
