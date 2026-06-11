(ns com.ruoyi.rouyi.web.controllers.system.post
  "岗位管理控制器。"
  (:require
    [com.ruoyi.rouyi.domain.system.post :as post-service]
    [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-posts
  [{:keys [post-service]} request]
  (ok (post-service/list-posts post-service (:query-params request))))

(defn get-post
  [{:keys [post-service]} request]
  (let [post-id (parse-long (get-in request [:path-params :id]))]
    (if-let [post (post-service/find-post-by-id post-service post-id)]
      (ok post)
      (fail "岗位不存在"))))

(defn create-post
  [{:keys [post-service]} request]
  (try
    (let [post-id (post-service/create-post! post-service (:body-params request))]
      (ok (str "创建成功: " post-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-post
  [{:keys [post-service]} request]
  (try
    (let [post-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :post_id post-id)]
      (post-service/update-post! post-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-post
  [{:keys [post-service]} request]
  (let [post-id (parse-long (get-in request [:path-params :id]))]
    (post-service/delete-post! post-service post-id)
    (ok "删除成功")))
