(ns com.ruoyi.domain.pms.service
  "项目管理应用服务,所有写入和审计在同一数据库事务中完成."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.governance.store :as gov-store]
            [com.ruoyi.domain.pms.rules :as rules]
            [com.ruoyi.domain.pms.lifecycle :as lifecycle]
            [com.ruoyi.domain.pms.planning :as planning]
            [integrant.core :as ig]
            [com.ruoyi.domain.pms.transaction :as transaction])
  (:import [java.sql SQLException]
           [java.time LocalDate]
           [java.util UUID]))

(defmethod ig/init-key :app.pms/service
  [_ {:keys [query-fn db file-dir file-max-mb]}]
  {:query-fn query-fn :db db :file-dir file-dir :file-max-mb file-max-mb})

(defn- uuid
  "生成跨数据库一致的业务标识."
  []
  (str (UUID/randomUUID)))

(defn actor
  "读取当前请求的有效身份与功能权限."
  [{:keys [query-fn]} identity]
  (rules/actor query-fn identity))

(defn- sql-cause
  "从 Conman 包装异常中找到 JDBC 原因."
  [error]
  (first (filter #(instance? SQLException %)
                 (take-while some? (iterate #(.getCause ^Throwable %) error)))))

(defn- database-error!
  "把唯一约束与并发锁冲突转换为可重试的业务冲突."
  [error]
  (if-let [^SQLException cause (sql-cause error)]
    (cond
      (or (contains? #{"40001" "41000"} (.getSQLState cause))
          (contains? #{5 6 1205 1213} (.getErrorCode cause)))
      (rules/fail! 409 "存在并发修改,请刷新后重试")
      (or (= "23000" (.getSQLState cause))
          (re-find #"(?i)unique|duplicate|constraint" (or (.getMessage cause) "")))
      (rules/fail! 409 "数据约束冲突,项目或节点编号可能已存在")
      :else (throw error))
    (throw error)))

(defn- transaction!
  "执行事务,确保业务写入和审计同时提交或回滚."
  [{:keys [db query-fn]} f]
  (try
    (transaction/execute! db
      (fn [tx] (f (fn [query params] (query-fn tx query params)))))
    (catch Exception e (database-error! e))))

(defn- load-project!
  "读取项目并检查数据权限."
  [q actor id write?]
  (rules/access! q actor (q :pms/project {:project_id id}) write?))

(defn- event!
  "记录不可修改的领域事件及结构化变更内容."
  [q actor project event-type description payload]
  (q :pms/insert-event!
     {:event_id (uuid) :project_id (:project_id project)
      :event_type event-type :description description
      :actor_id (:user_id actor) :actor_name (:user_name actor)
      :from_status (:from_status payload) :to_status (:to_status payload)
      :payload (json/generate-string payload)
      :aggregate_version (:version (q :pms/project {:project_id (:project_id project)}))}))

(defn- unique-project-no!
  "避免重复项目编号,保留数据库唯一约束处理并发竞争."
  [q project]
  (let [existing (q :pms/project-no {:project_no (:project_no project)})]
    (when (and existing (not= (:project_id project) (:project_id existing)))
      (rules/fail! 409 "项目编号已存在"))))

(defn- save-member!
  "新增或调整项目成员的访问角色."
  [q member]
  (q (if (q :pms/member member) :pms/update-member! :pms/insert-member!) member))

(defn- sync-manager!
  "维护项目经理成员记录,旧经理保留编辑资格."
  [q old project]
  (when (and old (not= (:manager_id old) (:manager_id project)))
    (save-member! q {:project_id (:project_id project)
                     :user_id (:manager_id old) :role "editor"}))
  (save-member! q {:project_id (:project_id project)
                   :user_id (:manager_id project) :role "manager"}))

(defn projects
  "分页查询当前用户可见的项目."
  [{:keys [query-fn]} actor params]
  (rules/permit! actor "pms:project:list")
  (let [params (merge (rules/access-params actor) (rules/page-params! params))]
    {:rows (vec (query-fn :pms/projects params))
     :total (:total (query-fn :pms/project-count params))}))

(defn project
  "读取当前用户有权访问的项目详情."
  [{:keys [query-fn]} actor id]
  (rules/permit! actor "pms:project:query")
  (let [project (load-project! query-fn actor id false)
        instance (first (gov-store/records query-fn project "template-instance"))]
    (assoc project :resume_status (:resume_status (query-fn :lifecycle/state {:project_id id}))
           :template (when instance
                       (select-keys instance [:id :code :title :template_revision :template_config_id :node_count
                                              :gate_template_count :task_count :closure_item_count :stages :team_roles
                                              :document_categories :created_at])))))

(defn create-project!
  "创建草稿项目,主节点,经理与创建者成员及审计事件."
  [svc actor body]
  (rules/permit! actor "pms:project:add")
  (rules/object! body rules/project-fields)
  (transaction! svc
    (fn [q]
      (let [project (assoc (rules/project-input! q body)
                           :project_id (uuid) :created_by (:user_id actor)
                           :version 1 :status "draft")]
        (config/enforce! q "project" (:project_no project))
        (unique-project-no! q project)
        (q :pms/insert-project! project)
        (q :pms/insert-node! {:node_id (uuid) :project_id (:project_id project)
                              :parent_id nil :node_type "main"
                              :node_code (:project_no project) :name (:name project)})
        (when-not (= (:user_id actor) (:manager_id project))
          (save-member! q {:project_id (:project_id project)
                           :user_id (:user_id actor) :role "editor"}))
        (sync-manager! q nil project)
        (event! q actor project "project.created" "创建项目草稿"
                {:to_status "draft" :after project})
        (q :pms/project {:project_id (:project_id project)})))))

(defn update-project!
  "按客户端版本更新项目资料及经理成员关系."
  [svc actor id body]
  (rules/permit! actor "pms:project:edit")
  (rules/object! body (conj rules/project-fields :version))
  (transaction! svc
    (fn [q]
      (let [old (load-project! q actor id true)
            _ (rules/editable! old)
            version (rules/version! old (:version body))
            project (merge old (rules/project-input! q (merge old body)))]
        (unique-project-no! q project)
        (let [node (q :pms/node-code {:project_id id :node_code (:project_no project)})]
          (when (and node (not= "main" (:node_type node)))
            (rules/fail! 409 "项目编号与已有结构节点编号冲突")))
        (when (not= (select-keys old [:start_date :end_date])
                    (select-keys project [:start_date :end_date]))
          (planning/project-dates-changing! q old))
        (rules/changed! (q :pms/update-project! (assoc project :version version)))
        (rules/changed! (q :pms/update-root! project))
        (sync-manager! q old project)
        (event! q actor project "project.updated" "更新项目资料"
                {:before (select-keys old rules/project-fields)
                 :after (select-keys project rules/project-fields)})
        (q :pms/project {:project_id id})))))

(defn transition-project!
  "由计划,质量,财务与关闭审批证据约束真实生命周期迁移."
  [svc actor id body]
  (lifecycle/transition! svc actor id body))

(defn- node-tree
  "把同一项目的平面节点构建为主项目,子项目和单机树."
  [rows parent-id]
  (mapv #(assoc % :children (node-tree rows (:node_id %)))
        (filter #(= parent-id (:parent_id %)) rows)))

(defn nodes
  "返回项目结构的平面列表及树形视图."
  [{:keys [query-fn]} actor id]
  (rules/permit! actor "pms:project:query")
  (load-project! query-fn actor id false)
  (let [rows (vec (query-fn :pms/nodes {:project_id id}))]
    {:rows rows :tree (node-tree rows nil)}))

(defn- node-input!
  "校验节点类型和所属父节点,禁止跨项目挂接和跳级."
  [q id body]
  (rules/object! body [:parent_id :node_type :node_code :name])
  (when-not (and (string? (:parent_id body))
                 (re-matches #"[0-9a-fA-F-]{36}" (:parent_id body)))
    (rules/fail! 400 "父节点标识必须是有效 UUID"))
  (let [node-type (:node_type body)
        parent (q :pms/node {:project_id id :node_id (:parent_id body)})
        node {:node_id (uuid) :project_id id :parent_id (:parent_id body)
              :node_type node-type :node_code (rules/text! (:node_code body) "节点编号" 64 true)
              :name (rules/text! (:name body) "节点名称" 200 true)}]
    (when-not parent (rules/fail! 400 "父节点不存在或不属于当前项目"))
    (when-not (contains? #{["main" "sub"] ["sub" "machine"]}
                         [(:node_type parent) node-type])
      (rules/fail! 400 "节点必须按主项目 -> 子项目 -> 单机层级创建"))
    (when (q :pms/node-code node) (rules/fail! 409 "项目内节点编号已存在"))
    (config/enforce! q node-type (:node_code node))
    node))

(defn create-node!
  "在同一事务中添加项目节点,递增项目版本并记录审计."
  [svc actor id body]
  (rules/permit! actor "pms:node:add")
  (transaction! svc
    (fn [q]
      (let [project (load-project! q actor id true)
            _ (rules/editable! project)
            node (node-input! q id body)]
        (rules/changed! (q :pms/touch-project! project))
        (q :pms/insert-node! node)
        (event! q actor project "node.created" (str "新增结构节点: " (:name node)) node)
        (q :pms/node node)))))

(defn members
  "读取项目现有成员."
  [{:keys [query-fn]} actor id]
  (rules/permit! actor "pms:project:query")
  (load-project! query-fn actor id false)
  {:rows (vec (query-fn :pms/members {:project_id id}))})

(defn- member-input!
  "校验现有用户和项目角色,保护主项目经理的角色."
  [q project body]
  (rules/object! body [:user_id :role])
  (let [uid (rules/positive-id! (:user_id body) "成员")
        role (:role body)]
    (when-not (contains? #{"manager" "editor" "viewer"} role)
      (rules/fail! 400 "成员角色必须为 manager,editor 或 viewer"))
    (when-not (q :pms/user {:user_id uid}) (rules/fail! 400 "成员不存在或已停用"))
    (when (and (= uid (:manager_id project)) (not= "manager" role))
      (rules/fail! 409 "项目经理的角色必须为 manager,请先变更项目经理"))
    {:project_id (:project_id project) :user_id uid :role role}))

(defn set-member!
  "新增或调整成员并记录变更前后的角色."
  [svc actor id body]
  (rules/permit! actor "pms:member:edit")
  (transaction! svc
    (fn [q]
      (let [project (load-project! q actor id true)
            _ (rules/editable! project)
            member (member-input! q project body)
            old (q :pms/member member)]
        (rules/changed! (q :pms/touch-project! project))
        (save-member! q member)
        (event! q actor project "member.updated" "维护项目成员"
                {:user_id (:user_id member) :before (:role old) :after (:role member)})
        (some #(when (= (:user_id member) (:user_id %)) %)
              (q :pms/members {:project_id id}))))))

(defn events
  "读取项目审计事件并解码结构化详情."
  [{:keys [query-fn]} actor id]
  (rules/permit! actor "pms:project:query")
  (load-project! query-fn actor id false)
  {:rows (mapv #(update % :payload json/parse-string true)
               (query-fn :pms/events {:project_id id}))})

(defn dashboard
  "按当前用户可见项目计算驾驶舱指标."
  [{:keys [query-fn]} actor]
  (rules/permit! actor "pms:dashboard:query")
  (query-fn :pms/dashboard (assoc (rules/access-params actor) :today (str (LocalDate/now)))))

(defn options
  "提供表单所需的最少用户和部门字段."
  [{:keys [query-fn]} actor]
  (rules/permit! actor ["pms:project:list" "pms:project:query" "pms:project:add"
                        "pms:project:edit" "pms:member:edit"])
  {:users (vec (query-fn :pms/users {})) :depts (vec (query-fn :pms/depts {}))
   :currentUserId (:user_id actor)})
