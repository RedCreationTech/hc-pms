(ns com.ruoyi.web.controllers.system.post
  "岗位管理控制器。"
  (:require
    [com.ruoyi.domain.system.post :as post-service]
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


(defn- current-user-name
  [request]
  (get-in request [:identity :user-name] ""))


(defn list-posts
  "查询岗位列表。"
  [{:keys [post-service]} request]
  (let [params (:query-params request)]
    (ok (post-service/list-posts post-service params))))


(defn get-post
  [{:keys [post-service]} request]
  (let [post-id (parse-long (get-in request [:path-params :id]))]
    (if-let [post (post-service/find-post-by-id post-service post-id)]
      (ok post)
      (fail "岗位不存在"))))


(defn create-post
  [{:keys [post-service]} request]
  (try
    (let [params (assoc (:body-params request) :create_by (current-user-name request))
          post-id (post-service/create-post! post-service params)]
      (ok (str "创建成功: " post-id)))
    (catch Exception e (fail (.getMessage e)))))


(defn update-post
  [{:keys [post-service]} request]
  (try
    (let [post-id (parse-long (get-in request [:path-params :id]))
          params (-> (:body-params request)
                     (assoc :post_id post-id)
                     (assoc :update_by (current-user-name request)))]
      (post-service/update-post! post-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))


(defn delete-post
  [{:keys [post-service]} request]
  (let [post-id (parse-long (get-in request [:path-params :id]))]
    (post-service/delete-post! post-service post-id)
    (ok "删除成功")))


(defn change-status
  "修改岗位状态。"
  [{:keys [post-service]} request]
  (let [post-id (parse-long (get-in request [:path-params :id]))
        status (get-in request [:body-params :status])]
    (post-service/update-post! post-service {:post_id post-id :status status :update_by (current-user-name request)})
    (ok "状态修改成功")))
