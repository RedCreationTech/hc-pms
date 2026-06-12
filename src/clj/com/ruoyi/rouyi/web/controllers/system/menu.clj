(ns com.ruoyi.rouyi.web.controllers.system.menu
  "菜单管理控制器。"
  (:require
   [com.ruoyi.rouyi.domain.system.menu :as menu-service]
   [com.ruoyi.rouyi.infra.data-perm :as data-perm]
   [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-menus
  "查询菜单列表（带数据权限过滤）。"
  [{:keys [menu-service]} request]
  (let [params (:query-params request)
        identity (:identity request)
        data-perm-filter (data-perm/data-perm-filter identity "default" :alias "u")
        params (merge {:menu_name nil :status nil :menu_type nil}
                      params
                      (:params data-perm-filter))]
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
    (let [menu-id (menu-service/create-menu! menu-service (:body-params request))]
      (ok (str "创建成功: " menu-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-menu
  [{:keys [menu-service]} request]
  (try
    (let [menu-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :menu_id menu-id)]
      (menu-service/update-menu! menu-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-menu
  [{:keys [menu-service]} request]
  (let [menu-id (parse-long (get-in request [:path-params :id]))]
    (menu-service/delete-menu! menu-service menu-id)
    (ok "删除成功")))
