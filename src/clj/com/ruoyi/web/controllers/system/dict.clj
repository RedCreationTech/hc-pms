(ns com.ruoyi.web.controllers.system.dict
  "字典管理控制器。"
  (:require
   [clojure.walk :as walk]
   [com.ruoyi.domain.system.dict :as dict-service]
   [com.ruoyi.infra.data-perm :as data-perm]
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

(defn list-dict-types
  "查询字典类型列表（带数据权限过滤）。"
  [{:keys [dict-service]} request]
  (let [params (walk/keywordize-keys (:query-params request))
        identity (:identity request)
        data-perm-filter (data-perm/data-perm-filter identity "default" :alias "u")
        params (merge params (:params data-perm-filter))]
    (ok (dict-service/list-dict-types dict-service params))))

(defn get-dict-type
  [{:keys [dict-service]} request]
  (let [dict-id (parse-long (get-in request [:path-params :id]))]
    (if-let [dt (dict-service/find-dict-type-by-id dict-service dict-id)]
      (ok dt)
      (fail "字典类型不存在"))))

(defn create-dict-type
  [{:keys [dict-service]} request]
  (try
    (let [params (assoc (:body-params request) :create_by (current-user-name request))
          dict-id (dict-service/create-dict-type! dict-service params)]
      (ok (str "创建成功: " dict-id)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-dict-type
  [{:keys [dict-service]} request]
  (try
    (let [dict-id (parse-long (get-in request [:path-params :id]))
          params (-> (:body-params request)
                     (assoc :dict_id dict-id)
                     (assoc :update_by (current-user-name request)))]
      (dict-service/update-dict-type! dict-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-dict-type
  [{:keys [dict-service]} request]
  (let [dict-id (parse-long (get-in request [:path-params :id]))]
    (dict-service/delete-dict-type! dict-service dict-id)
    (ok "删除成功")))

(defn list-dict-data
  "查询字典数据列表（带数据权限过滤）。"
  [{:keys [dict-service]} request]
  (let [params (walk/keywordize-keys (:query-params request))
        identity (:identity request)
        data-perm-filter (data-perm/data-perm-filter identity "default" :alias "u")
        params (merge params (:params data-perm-filter))]
    (ok (dict-service/list-dict-data dict-service params))))

(defn get-dict-data
  [{:keys [dict-service]} request]
  (let [dict-code (parse-long (get-in request [:path-params :id]))]
    (if-let [dd (dict-service/find-dict-data-by-id dict-service dict-code)]
      (ok dd)
      (fail "字典数据不存在"))))

(defn create-dict-data
  [{:keys [dict-service]} request]
  (try
    (let [params (assoc (:body-params request) :create_by (current-user-name request))
          dict-code (dict-service/create-dict-data! dict-service params)]
      (ok (str "创建成功: " dict-code)))
    (catch Exception e (fail (.getMessage e)))))

(defn update-dict-data
  [{:keys [dict-service]} request]
  (try
    (let [dict-code (parse-long (get-in request [:path-params :id]))
          params (-> (:body-params request)
                     (assoc :dict_code dict-code)
                     (assoc :update_by (current-user-name request)))]
      (dict-service/update-dict-data! dict-service params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-dict-data
  [{:keys [dict-service]} request]
  (let [dict-code (parse-long (get-in request [:path-params :id]))]
    (dict-service/delete-dict-data! dict-service dict-code)
    (ok "删除成功")))

(defn option-select
  "获取字典类型选项列表（下拉框用）。"
  [{:keys [dict-service]} _]
  (ok (dict-service/list-dict-types dict-service {:limit 999 :offset 0})))

(defn refresh-cache
  "刷新字典缓存。"
  [_ _]
  (ok "缓存已刷新"))
