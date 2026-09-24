(ns com.ruoyi.web.controllers.system.dept
  "部门管理控制器. 列表, 部门树与全部写操作按当前用户的数据权限范围过滤或校验 (越权 403)."
  (:require
    [com.ruoyi.domain.system.data-scope :as data-scope]
    [com.ruoyi.domain.system.dept :as dept-service]
    [ring.util.response :as response]))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- fail
  "失败响应: 字符串为 500; 异常取 ex-data 中的整数 :status."
  [e]
  (let [status (when (instance? Throwable e) (:status (ex-data e)))
        msg (if (instance? Throwable e) (.getMessage ^Throwable e) (str e))]
    (-> (response/response {:code (if (integer? status) status 500) :msg msg})
        (response/content-type "application/json"))))


(defn- current-user-name
  [request]
  (get-in request [:identity :user-name] ""))


(defn- scope
  [dept-service request]
  (data-scope/scope-of (:query-fn dept-service) (:actor request)))


(defn- path-id
  [request]
  (parse-long (get-in request [:path-params :id])))


(defn list-depts
  "查询部门列表 (数据范围内)."
  [{:keys [dept-service]} request]
  (let [raw (:query-params request)
        params {:status (not-empty (get raw "status")) :dept_name (not-empty (get raw "dept_name"))}]
    (ok (dept-service/list-depts dept-service (assoc params :scope (scope dept-service request))))))


(defn dept-tree
  "部门树 (用户管理左侧, 选择上级部门等), 只含数据范围内的部门."
  [{:keys [dept-service]} request]
  (ok (dept-service/list-depts dept-service {:scope (scope dept-service request)})))


(defn dept-options
  "选部门组件: 有效部门的编号, 上级与名称 (全公司, 仅需登录)."
  [{:keys [dept-service]} _request]
  (ok ((:query-fn dept-service) :dept-options {})))


(defn get-dept
  [{:keys [dept-service]} request]
  (try
    (let [dept-id (path-id request)
          dept (dept-service/find-dept-by-id dept-service dept-id)]
      (when-not dept (throw (ex-info "部门不存在" {:status 404})))
      (data-scope/check-dept! (scope dept-service request) dept-id)
      (ok dept))
    (catch Exception e (fail e))))


(defn create-dept
  "新增部门: 上级部门必须在数据范围内 (全部范围可新建顶级部门)."
  [{:keys [dept-service]} request]
  (try
    (let [body (:body-params request)
          s (scope dept-service request)
          parent-id (or (some-> (:parent_id body) str not-empty parse-long) 0)]
      (if (zero? parent-id)
        (when-not (:all? s) (throw (ex-info "没有权限新建顶级部门" {:status 403})))
        (data-scope/check-dept! s parent-id))
      (let [dept-id (dept-service/create-dept! dept-service (assoc body :create_by (current-user-name request)))]
        (ok (str "创建成功: " dept-id))))
    (catch Exception e (fail e))))


(defn update-dept
  "修改部门: 本部门与新的上级部门都必须在数据范围内."
  [{:keys [dept-service]} request]
  (try
    (let [dept-id (path-id request)
          body (:body-params request)
          s (scope dept-service request)]
      (data-scope/check-dept! s dept-id)
      (when-let [parent-id (some-> (:parent_id body) str not-empty parse-long)]
        (when (pos? parent-id) (data-scope/check-dept! s parent-id)))
      (dept-service/update-dept! dept-service (assoc body :dept_id dept-id :update_by (current-user-name request)))
      (ok "更新成功"))
    (catch Exception e (fail e))))


(defn delete-dept
  [{:keys [dept-service]} request]
  (try
    (let [dept-id (path-id request)]
      (data-scope/check-dept! (scope dept-service request) dept-id)
      (dept-service/delete-dept! dept-service dept-id)
      (ok "删除成功"))
    (catch Exception e (fail e))))


(defn change-status
  "修改部门状态."
  [{:keys [dept-service]} request]
  (try
    (let [dept-id (path-id request)
          status (get-in request [:body-params :status])]
      (data-scope/check-dept! (scope dept-service request) dept-id)
      (dept-service/update-dept! dept-service {:dept_id dept-id :status status :update_by (current-user-name request)})
      (ok "状态修改成功"))
    (catch Exception e (fail e))))
