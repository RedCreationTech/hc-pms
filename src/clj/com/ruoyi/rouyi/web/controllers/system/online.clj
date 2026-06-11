(ns com.ruoyi.rouyi.web.controllers.system.online
  "在线用户控制器。")
  
(defn list-online
  "获取在线用户列表。"
  [{:keys [online-service]}]
  (fn [request]
    (let [params (:params-params request)
          result ((:list-online online-service) params)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body {:code 200 :rows (:rows result) :total (:total result)}})))

(defn force-logout
  "强退指定用户。"
  [{:keys [online-service]}]
  (fn [request]
    (let [token-id (get-in request [:path-params :token-id])]
      ((:force-logout online-service) token-id)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body {:code 200 :msg "操作成功"}})))
