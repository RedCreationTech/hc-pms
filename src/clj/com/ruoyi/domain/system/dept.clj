(ns com.ruoyi.domain.system.dept
  "部门领域服务: 部门树维护 (祖级路径, 移动, 停用与删除校验), 部门负责人 (用户) 与数据权限过滤."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.system.data-scope :as data-scope]
    [com.ruoyi.infra.db :as db]))


(defn- ->long
  [v]
  (cond
    (nil? v) nil
    (number? v) (long v)
    (str/blank? (str v)) nil
    :else (parse-long (str v))))


(defn- bad-request
  [msg]
  (ex-info msg {:status 400}))


(defn list-depts
  "查询部门列表; 传入 :scope 时只返回数据范围内的部门."
  [{:keys [query-fn]} params]
  (let [scope (:scope params)
        rows (query-fn :list-depts (merge {:status nil :dept_name nil} (dissoc params :scope)))]
    (if scope
      (filterv #(data-scope/dept-visible? scope (:dept_id %)) rows)
      rows)))


(defn find-dept-by-id
  "根据ID查询部门."
  [{:keys [query-fn]} dept-id]
  (query-fn :find-dept-by-id {:dept_id dept-id}))


(defn- ancestors-for
  "新的上级部门下的祖级路径: 上级的祖级 + 上级编号; 顶级为 \"0\"."
  [query-fn parent-id]
  (if (and parent-id (pos? parent-id))
    (let [parent (query-fn :find-dept-by-id {:dept_id parent-id})]
      (when-not parent (throw (bad-request "上级部门不存在")))
      (str (if (str/blank? (:ancestors parent)) "0" (:ancestors parent)) "," parent-id))
    "0"))


(defn- update-descendants-ancestors!
  "递归更新下级部门的祖级路径: 子部门祖级 = 本部门祖级 + 本部门编号."
  [query-fn dept-id dept-ancestors]
  (doseq [child (query-fn :list-depts-by-parent {:parent_id dept-id})]
    (let [child-ancestors (str dept-ancestors "," dept-id)]
      (query-fn :update-dept-ancestors! {:dept_id (:dept_id child) :ancestors child-ancestors})
      (update-descendants-ancestors! query-fn (:dept_id child) child-ancestors))))


(defn- check-name-unique!
  "同一上级下部门名称唯一."
  [query-fn parent-id dept-name dept-id]
  (when-not (str/blank? (str dept-name))
    (when-let [other (query-fn :find-sibling-dept-by-name {:parent_id (or parent-id 0) :dept_name dept-name})]
      (when (not= (->long (:dept_id other)) (->long dept-id))
        (throw (bad-request (str "部门名称'" dept-name "'已存在")))))))


(defn- leader-fields
  "部门负责人: 按用户编号解析显示名; nil 表示清空."
  [query-fn leader-id]
  (if-let [id (->long leader-id)]
    (let [user (query-fn :find-user-by-id {:user_id id})]
      (when-not user (throw (bad-request "负责人用户不存在")))
      {:leader_id id :leader (or (not-empty (:nick_name user)) (:user_name user))})
    {:leader_id nil :leader nil}))


(defn create-dept!
  "创建部门: 上级必须存在, 同级名称唯一, 维护祖级路径, 可指定负责人 (用户)."
  [{:keys [query-fn db]} params]
  (let [parent-id (or (->long (:parent_id params)) 0)
        _ (when (str/blank? (str (:dept_name params))) (throw (bad-request "部门名称不能为空")))
        _ (check-name-unique! query-fn parent-id (:dept_name params) nil)
        row (-> {:parent_id nil :ancestors nil :dept_name nil :order_num 0
                 :leader nil :phone nil :email nil :status "0" :create_by nil}
                (merge (select-keys params [:dept_name :order_num :phone :email :status :create_by]))
                (assoc :parent_id parent-id
                       :ancestors (ancestors-for query-fn parent-id)))
        dept-id (db/insert-and-get-id! query-fn db :create-dept! row)]
    (when (contains? params :leader_id)
      (query-fn :update-dept-leader! (assoc (leader-fields query-fn (:leader_id params)) :dept_id dept-id)))
    dept-id))


(defn update-dept!
  "更新部门. 移动时校验不能挂到自己或下级之下, 并级联更新下级祖级; 停用时要求下级部门均已停用."
  [{:keys [query-fn]} params]
  (let [dept-id (->long (:dept_id params))
        existing (query-fn :find-dept-by-id {:dept_id dept-id})
        _ (when-not existing (throw (ex-info "部门不存在" {:status 404})))
        parent-id (->long (:parent_id params))
        moving? (and (some? parent-id) (not= parent-id (->long (:parent_id existing))))]
    (when (= parent-id dept-id)
      (throw (bad-request "上级部门不能是自己")))
    (when moving?
      (let [below (data-scope/descendant-ids (query-fn :list-all-depts {}) dept-id)]
        (when (contains? below parent-id)
          (throw (bad-request "上级部门不能是自己的下级部门")))))
    (check-name-unique! query-fn (or parent-id (->long (:parent_id existing)))
                        (or (:dept_name params) (:dept_name existing)) dept-id)
    (when (and (= "1" (str (:status params))) (not= "1" (str (:status existing)))
               (pos? (long (or (:total (query-fn :count-enabled-child-depts {:dept_id dept-id})) 0))))
      (throw (bad-request "该部门包含未停用的下级部门, 不能停用")))
    (let [ancestors (when moving? (ancestors-for query-fn parent-id))
          row (merge {:parent_id nil :ancestors nil :dept_name nil :order_num nil
                      :leader nil :phone nil :email nil :status nil :update_by nil}
                     (select-keys params [:dept_name :order_num :phone :email :status :update_by])
                     {:dept_id dept-id
                      :parent_id (when moving? parent-id)
                      :ancestors ancestors})]
      (query-fn :update-dept! row)
      (when (contains? params :leader_id)
        (query-fn :update-dept-leader! (assoc (leader-fields query-fn (:leader_id params)) :dept_id dept-id)))
      (when moving?
        (update-descendants-ancestors! query-fn dept-id ancestors)))))


(defn delete-dept!
  "逻辑删除部门: 存在下级部门或部门内有用户时拒绝."
  [{:keys [query-fn]} dept-id]
  (when (pos? (long (or (:total (query-fn :count-child-depts {:dept_id dept-id})) 0)))
    (throw (bad-request "存在下级部门, 不允许删除")))
  (when (pos? (long (or (:total (query-fn :count-dept-users {:dept_id dept-id})) 0)))
    (throw (bad-request "部门存在用户, 不允许删除")))
  (query-fn :delete-dept! {:dept_id dept-id}))
