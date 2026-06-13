(ns com.ruoyi.web.controllers.system.menu
  "菜单管理控制器。"
  (:require
   [com.ruoyi.domain.system.menu :as menu-service]
   [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn- current-user-name [request]
  (get-in request [:identity :user-name] ""))

(defn list-menus
  "查询菜单列表。"
  [{:keys [menu-service]} request]
  (let [params (:query-params request)]
    (ok (menu-service/list-menus menu-service params))))

(defn menu-tree
  [{:keys [menu-service]} _]
  (ok (menu-service/menu-tree menu-service)))

(defn get-menu
  [{:keys [menu-service]} request]
  (let [menu-id (parse-long (get-in request [:path-params :id]))]
    (if-let [menu (menu-service/find-menu-by-id menu-service menu-id)]
      (ok menu)
      (fail "菜单不存在"))))

(defn create-menu
  [{:keys [menu-service]} request]
  (try
    (let [params (assoc (:body-params request) :create_by (current-user-name request))
          menu-id (menu-service/create-menu! menu-service params)]
      (ok (str "创建成功: " menu-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-menu
  [{:keys [menu-service]} request]
  (try
    (let [menu-id (parse-long (get-in request [:path-params :id]))
          params (-> (:body-params request)
                     (assoc :menu_id menu-id)
                     (assoc :update_by (current-user-name request)))]
      (menu-service/update-menu! menu-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-menu
  [{:keys [menu-service]} request]
  (let [menu-id (parse-long (get-in request [:path-params :id]))]
    (menu-service/delete-menu! menu-service menu-id)
    (ok "删除成功")))

(defn change-status
  "修改菜单状态。"
  [{:keys [menu-service]} request]
  (let [menu-id (parse-long (get-in request [:path-params :id]))
        status (get-in request [:body-params :status])]
    (menu-service/update-menu! menu-service {:menu_id menu-id :status status :update_by (current-user-name request)})
    (ok "状态修改成功")))
