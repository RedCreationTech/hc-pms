(ns com.ruoyi.domain.system.data-scope
  "数据权限: 按当前用户有效角色的数据范围求并集, 得到可见部门与可见用户.

   角色 data_scope (对应若依 DataScopeAspect):
   1 全部, 2 自定义 (sys_role_dept 指定部门), 3 本部门, 4 本部门及以下, 5 仅本人.

   结果 {:all? true} 或 {:all? false :dept-ids #{..} :user-id id-or-nil}:
   用户可见 = 部门在 dept-ids 内, 或就是本人 (范围含 5);
   部门可见 = 在 dept-ids 内. 超级管理员与任一角色为 1 时可见全部;
   没有有效角色的用户只能看到本人.")


(defn- ->long
  [v]
  (cond
    (nil? v) nil
    (number? v) (long v)
    :else (some-> v str not-empty parse-long)))


(defn descendant-ids
  "部门本身及全部下级部门编号 (按 parent_id 递归, 不依赖 ancestors 字段)."
  [depts dept-id]
  (let [by-parent (group-by #(->long (:parent_id %)) depts)
        root (->long dept-id)]
    (when root
      (loop [todo [root] seen #{}]
        (if-let [id (first todo)]
          (if (contains? seen id)
            (recur (rest todo) seen)
            (recur (concat (rest todo) (map #(->long (:dept_id %)) (get by-parent id))) (conj seen id)))
          seen)))))


(defn scope-of
  "计算实时身份 (authz/load-actor 的结果) 的数据范围."
  [query-fn actor]
  (let [roles (:roles actor)
        scopes (set (map #(str (or (:data_scope %) "1")) roles))
        dept-id (->long (:dept_id actor))
        user-id (->long (:user_id actor))]
    (cond
      (nil? actor) {:all? false :dept-ids #{} :user-id nil}
      (:admin? actor) {:all? true}
      (empty? roles) {:all? false :dept-ids #{} :user-id user-id}
      (contains? scopes "1") {:all? true}
      :else
      (let [depts (when (contains? scopes "4") (query-fn :list-all-depts {}))
            custom-role-ids (vec (keep #(when (= "2" (str (:data_scope %))) (->long (:role_id %))) roles))
            custom (when (seq custom-role-ids)
                     (map #(->long (:dept_id %)) (query-fn :list-role-depts-for-roles {:role_ids custom-role-ids})))]
        {:all? false
         :dept-ids (cond-> (set (remove nil? custom))
                     (and dept-id (contains? scopes "3")) (conj dept-id)
                     (and dept-id (contains? scopes "4")) (into (descendant-ids depts dept-id)))
         :user-id (when (contains? scopes "5") user-id)}))))


(defn user-visible?
  [scope user]
  (boolean
    (or (:all? scope)
        (contains? (:dept-ids scope) (->long (:dept_id user)))
        (and (:user-id scope) (= (:user-id scope) (->long (:user_id user)))))))


(defn dept-visible?
  [scope dept-id]
  (boolean (or (:all? scope) (contains? (:dept-ids scope) (->long dept-id)))))


(defn sql-params
  "列表查询的数据范围参数 (list-users / count-users)."
  [scope]
  (if (:all? scope)
    {:scope_all 1 :scope_dept_ids [-1] :scope_user_id -1}
    {:scope_all 0
     :scope_dept_ids (if (seq (:dept-ids scope)) (vec (:dept-ids scope)) [-1])
     :scope_user_id (or (:user-id scope) -1)}))


(def unrestricted
  "系统内部调用 (无操作人) 使用的全量范围."
  {:all? true})


(defn check-user!
  "目标用户不在数据范围内时拒绝 (403); 用户不存在时 404."
  [query-fn scope user-id]
  (let [user (query-fn :find-user-by-id {:user_id (->long user-id)})]
    (when-not user
      (throw (ex-info "用户不存在" {:status 404})))
    (when-not (user-visible? scope user)
      (throw (ex-info "没有权限访问该用户数据" {:status 403})))
    user))


(defn check-dept!
  "部门不在数据范围内时拒绝 (403)."
  [scope dept-id]
  (when (and (some? dept-id) (not (dept-visible? scope dept-id)))
    (throw (ex-info "没有权限访问该部门数据" {:status 403}))))
