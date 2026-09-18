(ns com.ruoyi.domain.business.bpm
  "BPM 业务领域服务。持有 Flowable 引擎 + 业务库 query-fn，
   提供流程分类/模型/表单/实例 的 CRUD 与流程运行操作。"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.ruoyi.bpm.core :as bpm]
   [com.ruoyi.domain.business.bpm-flow :as bpm-flow]
   [integrant.core :as ig]))

;; ── 抄送节点处理器（COPY_TASK）─────────────────────────────────────────

(defn- expand-copy-candidates
  "把抄送节点配置(copy-user-ids/copy-role-ids)展开为用户名列表。"
  [node-config users]
  (let [user-ids (set (map str (:copy-user-ids node-config)))
        role-ids (set (map str (:copy-role-ids node-config)))
        names (concat (map :user_name (filter #(contains? user-ids (str (:user_id %))) users))
                      (map :user_name (filter #(some (fn [rid] (contains? role-ids (str rid)))
                                                     (:role_ids %))
                                              users)))]
    (distinct (vec (keep identity names)))))

(defn- candidates-from-links
  "从 DelegateTask 的候选人(内存 identityLink)展开抄送用户：
   userId 直取；group 按 role:/dept:/post:/dept-leader: 展开。"
  [^org.flowable.task.service.delegate.DelegateTask task users depts user-roles user-posts]
  (let [expand (fn [^org.flowable.identitylink.api.IdentityLink l]
                 (if-let [u (.getUserId l)]
                   [u]
                   (when-let [g (.getGroupId l)]
                     (let [[prefix did] (str/split g #":" 2)]
                       (case prefix
                         "role" (map :user_name (filter #(= did (str (:role_id %))) user-roles))
                         "dept" (map :user_name (filter #(= did (str (:dept_id %))) users))
                         "post" (map :user_name (filter #(some (fn [up]
                                                                 (and (= did (str (:post_id up)))
                                                                      (= (str (:user_id %)) (str (:user_id up)))))
                                                               user-posts)
                                                        users))
                         "dept-leader" (map :user_name
                                            (filter (fn [u]
                                                      (some #(and (= did (str (:dept_id %)))
                                                                  (= (str (:user_id u)) (str (:leader %))))
                                                            depts))
                                                    users))
                         [])))))]
    (distinct (vec (keep identity (mapcat expand (.getCandidates task)))))))

(defn- copy-task-handler
  "COPY_TASK 节点 create 事件处理：为每个候选人插 biz_bpm_copy 记录，然后自动完成任务。
   候选人优先取 nodeConfig 的 copy-user-ids/copy-role-ids，否则从 identityLink 展开。"
  [engine query-fn ^org.flowable.task.service.delegate.DelegateTask task node-config]
  (let [ts (.getTaskService ^org.flowable.engine.ProcessEngine engine)
        tid (.getId task)
        pid (.getProcessInstanceId task)
        users (query-fn :list-users {:user_name nil :phonenumber nil :status nil
                                     :begin_time nil :end_time nil :dept_filter_enabled 0
                                     :dept_ids [0] :data_user_id nil :page_size 100000 :offset 0})
        from-config (expand-copy-candidates node-config users)
        candidates (if (seq from-config)
                     from-config
                     (candidates-from-links task users
                                            (query-fn :list-all-depts {})
                                            (query-fn :list-user-roles {})
                                            (query-fn :list-user-posts {})))
        create-by (or (some-> (.getVariable task "startUserId") str) "")]
    (doseq [u candidates]
      (query-fn :bpm/insert-copy
                {:user_id u :process_instance_id pid
                 :activity_id (str (.getTaskDefinitionKey task))
                 :activity_name (str (.getName task))
                 :reason "" :create_by create-by}))
    (try
      (.complete ts tid (java.util.HashMap.))
      (catch Exception e
        (log/error "[bpm-copy] 自动完成抄送任务失败:" (.getMessage e))))))

;; ── Integrant 组件 ────────────────────────────────────────────────────
(defmethod ig/init-key :app.business/bpm-service
  [_ {:keys [engine query-fn db]}]
  ;; 注入动态候选策略解析器（TaskListener 在任务创建时调用）
  (bpm/set-candidate-resolver!
   (fn [strategy task]
     (let [pid (.getProcessInstanceId ^org.flowable.task.service.delegate.DelegateTask task)
           start-user (some-> (.getVariable ^org.flowable.task.service.delegate.DelegateTask task "startUserId") str)
           users (query-fn :list-users {:user_name nil :phonenumber nil :status nil
                                        :begin_time nil :end_time nil :dept_filter_enabled 0
                                        :dept_ids [0] :data_user_id nil :page_size 100000 :offset 0})
           depts (query-fn :list-all-depts {})
           user-dept (fn [uname] (:dept_id (first (filter #(= uname (str (:user_name %))) users))))
           leaders-of (fn [dept-id]
                        (when-let [d (first (filter #(= dept-id (:dept_id %)) depts))]
                          (when-let [leader (:leader d)]
                            (map :user_name (filter #(= (str leader) (str (:user_id %))) users)))))
           base (case strategy
                  "START_USER_DEPT_LEADER"
                  (when start-user (leaders-of (user-dept start-user)))
                  "MULTI_LEVEL_DEPT_LEADER"
                  (when start-user
                    (loop [did (user-dept start-user) n 0 acc []]
                      (if (or (nil? did) (>= n 3))
                        (distinct acc)
                        (recur (:parent_id (first (filter #(= did (:dept_id %)) depts)))
                               (inc n)
                               (concat acc (leaders-of did))))))
                  "START_USER_SELECT"
                  (let [v (some-> (.getVariable ^org.flowable.task.service.delegate.DelegateTask task "startUserSelected") seq)]
                    (when (seq v)
                      (let [ids (set (map str v))]
                        (map :user_name (filter #(contains? ids (str (:user_id %))) users)))))
                  "APPROVE_USER_SELECT"
                  (let [v (some-> (.getVariable ^org.flowable.task.service.delegate.DelegateTask task "approveUserSelected") seq)]
                    (when (seq v)
                      (let [ids (set (map str v))]
                        (map :user_name (filter #(contains? ids (str (:user_id %))) users)))))
                  nil)
           base-v (distinct (vec (keep identity base)))
           node-config (bpm/node-config-of engine task)
           start-handler (get-in node-config [:assign-start-user-handler-type])
           after-start (cond
                         (= start-handler "SKIP")
                         (remove #(= start-user %) base-v)
                         (= start-handler "ASSIGN_DEPT_LEADER")
                         (if (some #(= start-user %) base-v)
                           (concat (remove #(= start-user %) base-v)
                                   (when start-user (leaders-of (user-dept start-user))))
                           base-v)
                         :else base-v)
           empty-handler (get-in node-config [:assign-empty-handler])
           after-empty (if (empty? after-start)
                         (case (get-in empty-handler [:type])
                           "ASSIGN_USER"
                           (let [ids (set (map str (get-in empty-handler [:user-ids])))]
                             (map :user_name (filter #(contains? ids (str (:user_id %))) users)))
                           "TRANSFER_ADMIN"
                           (map :user_name (filter #(= "admin" (str (:user_name %))) users))
                           after-start)
                         after-start)]
       (distinct after-empty))))
  ;; 注册抄送节点处理器（TaskListener create 时自动插抄送记录并完成任务）
  (bpm/set-copy-handler!
   (fn [task node-config]
     (copy-task-handler engine query-fn task node-config)))
  {:engine engine :query-fn query-fn :db db})

;; ── 分页工具 ──────────────────────────────────────────────────────────
(defn- page-params
  "统一分页参数。"
  [params]
  (let [page (or (some-> (get params :page) Integer/parseInt) 1)
        size (or (some-> (get params :size) Integer/parseInt) 10)]
    {:page page :size size :offset (* (dec page) size)}))

(defn- row->json
  "把表的 JSON 文本字段解析为 Clojure 数据。"
  [row ks]
  (reduce (fn [m k]
            (if-let [v (get row k)]
              (assoc m k (try (json/parse-string v true) (catch Exception _ v)))
              m))
          row ks))

;; ── 流程分类 ──────────────────────────────────────────────────────────
(defn category-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:name (get params :name) :page_size size :offset offset}]
    {:rows (query-fn :bpm/category-list p)
     :total (:total (query-fn :bpm/category-count p))}))

(defn category-get
  [{:keys [query-fn]} id]
  (query-fn :bpm/find-category-by-id {:category_id id}))

(defn category-create
  [{:keys [query-fn]} params user]
  (query-fn :bpm/insert-category
            {:name (:name params) :code (:code params)
             :sort (or (:sort params) 0) :status (or (:status params) "0")
             :create_by (or user "") :remark (or (:remark params) "")}))

(defn category-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-category
            {:category_id (:category_id params) :name (:name params)
             :code (:code params) :sort (or (:sort params) 0)
             :status (:status params) :update_by (or user "") :remark (:remark params)}))

(defn category-delete
  [{:keys [query-fn]} id]
  (query-fn :bpm/delete-category {:category_id id}))

;; ── 流程模型 ──────────────────────────────────────────────────────────
(defn model-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:model_name (get params :model_name) :category_id (get params :category_id)
           :page_size size :offset offset}]
    {:rows (mapv #(row->json % [:form_json :bpmn_xml])
                 (query-fn :bpm/model-list p))
     :total (:total (query-fn :bpm/model-count p))}))

(defn model-get
  [{:keys [query-fn]} id]
  (-> (query-fn :bpm/find-model-by-id {:model_id id})
      (row->json [:form_json :bpmn_xml])))

(defn model-get-by-key
  [{:keys [query-fn]} key]
  (-> (query-fn :bpm/find-model-by-key {:model_key key})
      (row->json [:form_json :bpmn_xml])))

(defn model-create
  [{:keys [query-fn]} params user]
  (query-fn :bpm/insert-model
            {:model_key (:model_key params) :model_name (:model_name params)
             :category_id (or (:category_id params) 0) :version 1
             :form_type (or (:form_type params) "0")
             :form_id (or (:form_id params) 0)
             :form_custom_create_path (or (:form_custom_create_path params) "")
             :form_custom_view_path (or (:form_custom_view_path params) "")
             :form_json (:form_json params) :fields_permission (:fields_permission params)
             :bpmn_xml (:bpmn_xml params)
             :deployment_id (:deployment_id params) :status (or (:status params) "1")
             :create_by (or user "") :remark (or (:remark params) "")}))

(defn model-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-model
            {:model_id (:model_id params) :model_name (:model_name params)
             :category_id (or (:category_id params) 0) :form_type (:form_type params)
             :form_id (or (:form_id params) 0)
             :form_custom_create_path (or (:form_custom_create_path params) "")
             :form_custom_view_path (or (:form_custom_view_path params) "")
             :form_json (:form_json params) :fields_permission (:fields_permission params)
             :bpmn_xml (:bpmn_xml params)
             :deployment_id (:deployment_id params) :status (:status params)
             :update_by (or user "") :remark (:remark params)}))

(defn model-delete
  [{:keys [engine query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (when-let [dep-id (:deployment_id m)]
      (try (bpm/delete-deployment! engine dep-id)
           (catch Exception _ nil)))
    (query-fn :bpm/delete-model {:model_id id})))

(defn- load-identity-data
  "加载系统用户/角色/部门/岗位关系，用于同步到 Flowable identity。"
  [query-fn]
  {:users (query-fn :list-users {:user_name nil :phonenumber nil :status nil
                                 :begin_time nil :end_time nil :dept_filter_enabled 0
                                 :dept_ids [0] :data_user_id nil :page_size 100000 :offset 0})
   :roles (query-fn :list-roles {:role_name nil :role_key nil :status nil})
   :depts (query-fn :list-all-depts {})
   :posts (query-fn :list-posts {:post_code nil :post_name nil :status nil})
   :user-roles (query-fn :list-user-roles {})
   :user-posts (query-fn :list-user-posts {})})

(defn model-deploy!
  "部署流程模型到 Flowable，并回写 deployment_id。返回新 deployment-id。"
  [{:keys [engine query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})
        _ (when-not m (throw (ex-info "流程模型不存在" {:model_id id})))
        _ (when-not (:bpmn_xml m) (throw (ex-info "模型未定义 BPMN" {:model_id id})))
        _ (bpm/sync-identity! engine (load-identity-data query-fn))
        dep-id (bpm/deploy! engine (:bpmn_xml m) (:model_key m) (:model_name m))
        new-version (inc (or (:version m) 1))]
    (query-fn :bpm/update-model-deployment
              {:model_id id :deployment_id dep-id :version new-version :status "1"})
    {:deployment-id dep-id :version new-version}))

(defn model-tree
  "把模型 BPMN 转为流程节点树（HTML/flex 编辑器工作模型）。"
  [{:keys [query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (bpm-flow/bpmn->tree (:bpmn_xml m))))

(defn model-save-tree!
  "保存流程节点树：转回 BPMN XML 并更新模型。返回新 XML。"
  [{:keys [query-fn]} id tree user]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})
        users (query-fn :list-users {:user_name nil :phonenumber nil :status nil
                                     :begin_time nil :end_time nil :dept_filter_enabled 0
                                     :dept_ids [0] :data_user_id nil :page_size 100000 :offset 0})
        user-map (into {} (map (juxt (comp str :user_id) :user_name)) users)
        xml (bpm-flow/tree->bpmn (clojure.walk/keywordize-keys tree) (:model_key m) user-map)]
    (query-fn :bpm/update-model
              {:model_id id :model_name (:model_name m)
               :category_id (:category_id m) :form_type (:form_type m)
               :form_id (or (:form_id m) 0)
               :form_custom_create_path (or (:form_custom_create_path m) "")
               :form_custom_view_path (or (:form_custom_view_path m) "")
               :form_json (:form_json m) :fields_permission (:fields_permission m)
               :bpmn_xml xml :deployment_id nil
               :status "1" :update_by (or user "") :remark (:remark m)})
    {:bpmn_xml xml}))

;; ── 动态表单 ──────────────────────────────────────────────────────────
(defn form-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:form_name (get params :form_name) :status (get params :status)
           :page_size size :offset offset}]
    {:rows (mapv #(row->json % [:form_json]) (query-fn :bpm/form-list p))
     :total (:total (query-fn :bpm/form-count p))}))

(defn form-get
  [{:keys [query-fn]} id]
  (-> (query-fn :bpm/find-form-by-id {:form_id id})
      (row->json [:form_json])))

(defn form-create
  [{:keys [query-fn]} params user]
  (query-fn :bpm/insert-form
            {:form_name (:form_name params) :form_key (:form_key params)
             :form_json (:form_json params) :status (or (:status params) "0")
             :create_by (or user "") :remark (or (:remark params) "")}))

(defn form-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-form
            {:form_id (:form_id params) :form_name (:form_name params)
             :form_key (:form_key params) :form_json (:form_json params)
             :status (:status params) :update_by (or user "") :remark (:remark params)}))

(defn form-delete
  [{:keys [query-fn]} id]
  (query-fn :bpm/delete-form {:form_id id}))

;; ── 流程实例（发起 + 运行）────────────────────────────────────────────
(defn- candidate-names
  "按候选策略把配置 ids 转用户名列表。"
  [config users]
  (let [ids (fn [k] (or (get-in config [:candidate-param k]) []))
        names (case (:candidate-strategy config)
                "USER" (keep #(when (contains? (set (map str (ids :user-ids))) (str (:user_id %))) (:user_name %)) users)
                "ROLE" (keep #(when (some (set (map str (ids :role-ids))) (map str (:role_ids %))) (:user_name %)) users)
                "DEPT_MEMBER" (keep #(when (contains? (set (map str (ids :dept-ids))) (str (:dept_id %))) (:user_name %)) users)
                "POST" (keep #(when (some (set (map str (ids :post-ids))) (map str (:post_ids %))) (:user_name %)) users)
                nil)]
    (distinct (vec (keep identity names)))))

(defn- collect-multi-nodes
  "收集流程树中多实例审批节点（approve-method 非 SEQUENTIAL）。返回 [{:id :config}]。"
  [tree]
  (let [walk (fn walk [node acc]
               (if (nil? node)
                 acc
                 (let [cfg (:config node)
                       acc' (if (and cfg (= "USER" (:approve-type cfg))
                                    (not= "SEQUENTIAL" (or (:approve-method cfg) "SEQUENTIAL")))
                              (conj acc {:id (:id node) :config cfg})
                              acc)]
                   (-> acc'
                       (into (walk (:child-node node) []))
                       (into (mapcat #(walk % []) (or (:condition-nodes node) [])))))))]
    (vec (walk tree []))))

(defn instance-start!
  "发起流程：用模型部署的 key 启动 Flowable 实例，写入 biz_bpm_instance。"
  [{:keys [engine query-fn]} model-id business-key form-data starter]
  (let [m (query-fn :bpm/find-model-by-id {:model_id model-id})
        _ (when-not m (throw (ex-info "流程模型不存在" {:model_id model-id})))
        _ (when-not (:deployment_id m)
            (throw (ex-info "模型未部署，请先部署" {:model_id model-id :key (:model_key m)})))
        biz-key (or business-key (str "biz-" (System/currentTimeMillis)))
        fd (or form-data {})
        ;; 表单字段展开为流程变量（条件表达式 ${days > 3} 可直接引用），formData 保留完整 JSON
        field-vars (into {}
                          (keep (fn [[k v]]
                                  (when (not= (name k) "startUserSelected")
                                    [(name k) v])))
                          fd)
        users (query-fn :list-users {:user_name nil :phonenumber nil :status nil
                                     :begin_time nil :end_time nil :dept_filter_enabled 0
                                     :dept_ids [0] :data_user_id nil :page_size 100000 :offset 0})
        tree (bpm-flow/bpmn->tree (:bpmn_xml m))
        multi-vars (into {}
                           (map (fn [{:keys [id config]}]
                                  [(str "approverList_" id) (candidate-names config users)]))
                           (collect-multi-nodes tree))
        started (bpm/start! engine (:model_key m) biz-key
                            (cond-> (merge {"formData" (json/generate-string fd)
                                            "startUserId" (or starter "")}
                                           field-vars multi-vars)
                              (seq (get fd :startUserSelected))
                              (assoc "startUserSelected" (vec (get fd :startUserSelected)))))
        pid (:process-instance-id started)]
    (query-fn :bpm/insert-instance
              {:process_instance_id pid :model_id model-id :model_key (:model_key m)
               :business_key biz-key
               :form_data_json (json/generate-string (or form-data {}))
               :starter_id (or starter "") :status "1"
               :current_task (-> (first (bpm/todo-list engine (or starter ""))) :name (or ""))})
    {:process-instance-id pid :business-key biz-key}))

(defn instance-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:starter_id (get params :starter_id) :model_key (get params :model_key)
           :page_size size :offset offset}]
    {:rows (mapv #(row->json % [:form_data_json]) (query-fn :bpm/instance-list p))
     :total (:total (query-fn :bpm/instance-count p))}))

(defn task-detail
  "任务详情：任务信息 + 实例表单数据 + 表单 schema（审批弹窗表单回显）。"
  [{:keys [engine query-fn]} task-id]
  (let [task-obj (some-> (.taskId (.createTaskQuery (.getTaskService engine)) task-id) .singleResult)
        _ (when-not task-obj (throw (ex-info "任务不存在" {:task-id task-id})))
        task (bpm/task->map* task-obj)
        pid (:process-instance-id task)
        inst (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        model (query-fn :bpm/find-model-by-id {:model_id (:model_id inst)})
        form (when-let [fid (:form_id model)]
               (query-fn :bpm/find-form-by-id {:form_id fid}))
        schema (when-let [fj (:form_json form)]
                 (if (string? fj) (json/parse-string fj true) fj))
        inst-data (row->json inst [:form_data_json])
        node-config (bpm/node-config-of engine task-obj)]
    {:task task
     :model {:model-id (:model_id model)
             :model_name (:model_name model) :model_key (:model_key model)}
     :fields-permission (or (:fields-permission node-config) {})
     :form {:schema schema :values (:form_data_json inst-data)}}))

(defn instance-history
  "流程实例的完整历史轨迹：业务侧 + 活动轨迹 + 任务级审批历史 + 表单回显数据。"
  [{:keys [engine query-fn]} pid]
  (let [biz (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        _ (when-not biz (throw (ex-info "流程实例不存在" {:pid pid})))
        model (query-fn :bpm/find-model-by-id {:model_id (:model_id biz)})
        form (when-let [fid (:form_id model)]
               (query-fn :bpm/find-form-by-id {:form_id fid}))
        inst (row->json biz [:form_data_json])]
    {:instance inst
     :model {:model_id (:model_id model) :model_name (:model_name model)
             :model_key (:model_key model) :form_type (:form_type model)}
     :form {:schema (when-let [fj (:form_json form)]
                      (if (string? fj) (json/parse-string fj true) fj))
            :values (get inst :form_data_json)}
     :activities (bpm/history-of engine pid)
     :task-history (bpm/task-history-of engine pid)
     :running? (pos? (bpm/todo-count engine (or (:starter_id biz) "")))}))

(defn instance-diagram
  "流程实例的图示数据：BPMN XML + 进行中/已完成节点 id，供前端 bpmn-js 高亮。"
  [{:keys [engine query-fn]} pid]
  (let [biz (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        _ (when-not biz (throw (ex-info "流程实例不存在" {:pid pid})))
        model (query-fn :bpm/find-model-by-id {:model_id (:model_id biz)})
        active (bpm/active-activity-ids engine pid)
        completed (bpm/completed-activity-ids engine pid)]
    {:process-instance-id pid
     :model-name (:model_name model)
     :bpmn-xml (:bpmn_xml model)
     :active-activity-ids active
     :completed-activity-ids completed
     :running? (seq active)}))

(defn office-stats
  "办公一体化统计看板数据：请假/报销/流程/员工/客户。"
  [{:keys [engine query-fn]} user]
  (let [leave-status (query-fn :stats/leave-by-status {})
        reimburse-status (query-fn :stats/reimburse-by-status {})
        leave-total (get (query-fn :stats/leave-total {}) :total 0)
        reimburse-total (get (query-fn :stats/reimburse-total {}) :total 0)
        employee-total (get (query-fn :stats/employee-total {}) :total 0)
        customer-total (get (query-fn :stats/customer-total {}) :total 0)
        st (fn [rows k] (or (some #(= k (:status %)) rows) 0))
        cnt (fn [rows k] (:cnt (first (filter #(= k (:status %)) rows)) 0))
        reimb-amount (fn [rows k] (or (:total_amount (first (filter #(= k (:status %)) rows))) 0))
        total-amount (reduce + (map #(or (:total_amount %) 0) reimburse-status))]
    {:leave {:total leave-total
             :pending (cnt leave-status "1")
             :approved (cnt leave-status "2")
             :rejected (cnt leave-status "3")}
     :reimburse {:total reimburse-total
                 :pending (cnt reimburse-status "1")
                 :approved (cnt reimburse-status "2")
                 :rejected (cnt reimburse-status "3")
                 :total-amount total-amount
                 :pending-amount (reimb-amount reimburse-status "1")
                 :approved-amount (reimb-amount reimburse-status "2")}
     :process {:running (bpm/instance-count engine)
               :definitions (count (bpm/definitions engine))
               :my-todo (bpm/todo-count engine (or user ""))}
     :hrm {:employee-total employee-total}
     :crm {:customer-total customer-total}}))

;; ── Phase 1 审批闭环：加签 / 减签 / 取消 / 撤回 / 抄送 / 可退回节点 ─────

(defn task-create-sign!
  "加签：仅任务当前办理人可操作。"
  [{:keys [engine]} task-id user-names sign-type reason user]
  (let [t (bpm/task-of engine task-id)]
    (when-not t (throw (ex-info "任务不存在" {:task-id task-id})))
    (when (and (:assignee t) (not= (:assignee t) user))
      (throw (ex-info "只有任务办理人可以加签" {:task-id task-id}))))
  (bpm/create-sign! engine task-id user-names sign-type reason))

(defn task-delete-sign!
  "减签：仅任务当前办理人可操作。"
  [{:keys [engine]} task-id user-names reason user]
  (let [t (bpm/task-of engine task-id)]
    (when-not t (throw (ex-info "任务不存在" {:task-id task-id})))
    (when (and (:assignee t) (not= (:assignee t) user))
      (throw (ex-info "只有任务办理人可以减签" {:task-id task-id}))))
  (bpm/delete-sign! engine task-id user-names reason))

(defn task-sign-list
  "某任务的加签子任务列表。"
  [{:keys [engine]} task-id]
  (bpm/sign-list engine task-id))

(defn task-return-list
  "当前任务之前已完成的用户任务节点列表（驳回可选目标）。"
  [{:keys [engine]} task-id]
  (bpm/return-list engine task-id))

(defn instance-cancel!
  "取消流程实例：发起人或管理员。业务状态置为 CANCELED。"
  [{:keys [engine query-fn]} process-instance-id reason user admin?]
  (let [inst (query-fn :bpm/find-instance-by-pid {:process_instance_id process-instance-id})]
    (when-not inst (throw (ex-info "流程实例不存在" {:process-instance-id process-instance-id})))
    (when-not (or admin? (= user (:starter_id inst)))
      (throw (ex-info "只有发起人或管理员可以取消流程" {:process-instance-id process-instance-id})))
    (bpm/cancel-instance! engine process-instance-id reason)
    (query-fn :bpm/update-instance-status {:process_instance_id process-instance-id
                                           :status "CANCELED" :current_task ""})))

(defn task-withdraw!
  "审批人撤回自己刚审完的任务（要求下一节点任务未完成）。"
  [{:keys [engine]} task-id user]
  (bpm/withdraw! engine task-id user))

(defn task-withdraw-to-start!
  "发起人撤回到起始节点重新编辑：发起人或管理员。"
  [{:keys [engine query-fn]} process-instance-id user admin?]
  (let [inst (query-fn :bpm/find-instance-by-pid {:process_instance_id process-instance-id})]
    (when-not inst (throw (ex-info "流程实例不存在" {:process-instance-id process-instance-id})))
    (when-not (or admin? (= user (:starter_id inst)))
      (throw (ex-info "只有发起人或管理员可以撤回流程" {:process-instance-id process-instance-id})))
    (bpm/withdraw-to-start! engine process-instance-id)))

(defn task-copy!
  "手动抄送：为每个抄送人插 biz_bpm_copy 记录。"
  [{:keys [query-fn]} process-instance-id user-names reason activity-id activity-name user]
  (when (empty? (seq user-names))
    (throw (ex-info "抄送人不能为空" {:process-instance-id process-instance-id})))
  (doseq [u user-names]
    (query-fn :bpm/insert-copy
              {:user_id (str u) :process_instance_id process-instance-id
               :activity_id (or activity-id "") :activity_name (or activity-name "")
               :reason (or reason "") :create_by (or user "")})))

(defn copy-page
  "我的抄送分页（当前登录用户）。"
  [{:keys [query-fn]} params user]
  (let [{:keys [offset size]} (page-params params)
        p {:user_id user :page_size size :offset offset}]
    {:rows (query-fn :bpm/copy-page p)
     :total (:total (query-fn :bpm/copy-count p))}))
