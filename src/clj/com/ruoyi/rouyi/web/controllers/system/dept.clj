(ns com.ruoyi.rouyi.web.controllers.system.dept
  "部门管理控制器。"
  (:require
   [com.ruoyi.rouyi.domain.system.dept :as dept-service]
   [com.ruoyi.rouyi.infra.data-perm :as data-perm]
   [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn list-depts
  "查询部门列表（带数据权限过滤）。"
  [{:keys [dept-service]} request]
  (let [params (:query-params request)
        identity (:identity request)
        data-perm-filter (data-perm/data-perm-filter identity "default" :alias "u")
        params (merge params (:params data-perm-filter))]
    (ok (dept-service/list-depts dept-service params))))

(defn dept-tree
  "获取部门树（用于用户管理左侧选择）。"
  [{:keys [dept-service]} _request]
  (ok (dept-service/list-depts dept-service {})))

(defn get-dept
  [{:keys [dept-service]} request]
  (let [dept-id (parse-long (get-in request [:path-params :id]))]
    (if-let [dept (dept-service/find-dept-by-id dept-service dept-id)]
      (ok dept)
      (fail "部门不存在"))))

(defn create-dept
  [{:keys [dept-service]} request]
  (try
    (let [dept-id (dept-service/create-dept! dept-service (:body-params request))]
      (ok (str "创建成功: " dept-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-dept
  [{:keys [dept-service]} request]
  (try
    (let [dept-id (parse-long (get-in request [:path-params :id]))
          params (assoc (:body-params request) :dept_id dept-id)]
      (dept-service/update-dept! dept-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-dept
  [{:keys [dept-service]} request]
  (let [dept-id (parse-long (get-in request [:path-params :id]))]
    (dept-service/delete-dept! dept-service dept-id)
    (ok "删除成功")))
