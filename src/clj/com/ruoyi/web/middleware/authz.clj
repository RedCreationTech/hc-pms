(ns com.ruoyi.web.middleware.authz
  "路由级实时权限校验 (对应若依 @PreAuthorize(\"@ss.hasPermi(...)\")).

  每个受保护接口在路由数据中声明 :perms:
  - 权限字符串, 例如 \"system:user:list\";
  - 权限字符串向量, 满足任意一个即可;
  - :login, 仅要求登录 (个人中心, 选人选部门组件, 字典数据等);
  - 函数 (fn [request] perms), 按路径参数决定所需权限.
  未声明 :perms 的接口一律拒绝 (fail closed).

  身份每次请求从数据库读取: 有效用户, 有效角色, 启用菜单上的权限字符串. 拥有 admin 角色的超级管理员
  拥有全部权限. 结果以 :actor 附加到请求, 供数据权限等后续逻辑使用."
  (:require
    [ring.util.response :as response]))


(def super-admin-role-key
  "超级管理员角色标识 (与 PMS 同一口径)."
  "admin")


(defn load-actor
  "读取当前有效用户, 角色与权限; 用户不存在, 已停用或已删除返回 nil."
  [query-fn user-id]
  (when user-id
    (when-let [user (query-fn :authz-user {:user_id user-id})]
      (let [roles (vec (query-fn :authz-user-roles {:user_id user-id}))
            admin? (boolean (some #(= super-admin-role-key (:role_key %)) roles))]
        (assoc user
               :roles roles
               :role_keys (set (map :role_key roles))
               :admin? admin?
               :permissions (if admin?
                              #{"*:*:*"}
                              (set (keep :perms (query-fn :authz-user-perms {:user_id user-id})))))))))


(defn permitted?
  "当前身份是否拥有所需权限中的任意一个."
  [actor perms]
  (let [owned (:permissions actor)
        required (if (sequential? perms) perms [perms])]
    (boolean
      (or (:admin? actor)
          (contains? owned "*:*:*")
          (some owned required)))))


(defn deny
  "构造 401/403 JSON 响应."
  [status msg]
  (-> (response/response {:code status :msg msg})
      (response/status status)
      (response/content-type "application/json")))


(defn perms-middleware
  "Reitit 中间件: 按接口声明的 :perms 校验实时权限."
  [query-fn]
  {:name ::perms
   :compile (fn [{:keys [perms]} _]
              (fn [handler]
                (fn [request]
                  (let [identity (:identity request)]
                    (if-not identity
                      (deny 401 "未登录或令牌已过期")
                      (if-let [actor (load-actor query-fn (:user-id identity))]
                        (let [required (if (fn? perms) (perms request) perms)
                              request (assoc request :actor actor)]
                          (cond
                            (= :login required) (handler request)
                            (nil? required) (deny 403 "接口未配置访问权限")
                            (permitted? actor required) (handler request)
                            :else (deny 403 "没有操作权限")))
                        (deny 401 "用户不存在或已停用")))))))})
