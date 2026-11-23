(ns com.ruoyi.domain.business.bpm
  "BPM 业务领域服务.持有 Flowable 引擎 + 业务库 query-fn,
   提供流程分类/模型/表单/实例 的 CRUD 与流程运行操作."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.walk]
    [clojure.tools.logging :as log]
    [com.ruoyi.bpm.core :as bpm]
    [com.ruoyi.domain.business.bpm-flow :as bpm-flow]
    [com.ruoyi.domain.business.json-field :as jf]
    [com.ruoyi.domain.business.paging :as paging]
    [com.ruoyi.domain.business.webhook :as hook]
    [integrant.core :as ig]))


;; ── 抄送节点处理器(COPY_TASK)─────────────────────────────────────────

(declare resolve-candidate-users list-all-users)


(defn- expand-copy-candidates
  "把抄送节点配置(copy-user-ids/copy-role-ids)展开为用户名列表."
  [node-config users]
  (let [user-ids (set (map str (:copy-user-ids node-config)))
        role-ids (set (map str (:copy-role-ids node-config)))
        names (concat (map :user_name (filter #(contains? user-ids (str (:user_id %))) users))
                      (map :user_name (filter #(some (fn [rid] (contains? role-ids (str rid)))
                                                     (:role_ids %))
                                              users)))]
    (distinct (vec (keep identity names)))))


(defn- candidates-from-links
  "从 DelegateTask 的候选人(内存 identityLink)展开抄送用户:
   userId 直取;group 按 role:/dept:/post:/dept-leader: 展开."
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
                                                                  (= (str (:user_id u)) (str (or (:leader_id %) (:leader %)))))
                                                            depts))
                                                    users))
                         [])))))]
    (distinct (vec (keep identity (mapcat expand (.getCandidates task)))))))


(defn- copy-task-handler
  "COPY_TASK 节点 create 事件处理:为每个候选人插 biz_bpm_copy 记录,然后自动完成任务.
   候选人解析优先级:nodeConfig 抄送策略(candidate-strategy,策略制,复用审批人解析器)
   → 旧键 copy-user-ids/copy-role-ids 兼容回退 → BPMN 候选人 identityLink 展开."
  [engine query-fn ^org.flowable.task.service.delegate.DelegateTask task node-config]
  (let [ts (.getTaskService ^org.flowable.engine.ProcessEngine engine)
        tid (.getId task)
        pid (.getProcessInstanceId task)
        users (list-all-users query-fn)
        depts (query-fn :list-all-depts {})
        strategy (some-> (:candidate-strategy node-config) str)
        resolved (when (seq strategy)
                   (resolve-candidate-users engine query-fn task strategy
                                            (or (:candidate-param node-config) {})
                                            node-config users depts))
        legacy (expand-copy-candidates node-config users)
        candidates (or (seq resolved)
                       (seq legacy)
                       (candidates-from-links task users depts
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

(def ^:private dynamic-strategies
  "create 事件需要运行时解析的候选策略."
  #{"START_USER_DEPT_LEADER" "MULTI_LEVEL_DEPT_LEADER"
    "START_USER_SELECT" "APPROVE_USER_SELECT"
    "INITIATOR_SELF" "USER_GROUP" "FORM_USER" "FORM_DEPT_LEADER" "EXPRESSION"})


(def ^:private multi-instance-methods
  "多实例审批方式(由 collection 驱动,create 监听器不干预候选人)."
  #{"ANY" "ALL" "RATIO"})


(defn- list-all-users
  [query-fn]
  (query-fn :list-all-users {}))


(defn- resolve-candidate-users
  "按候选策略解析用户名列表(审批/办理节点 create 与抄送节点共用的策略解析器).
   返回 nil 表示解析不到候选(调用方决定为空策略/兼容回退)."
  [engine query-fn ^org.flowable.task.service.delegate.DelegateTask task strategy param node-config users depts]
  (let [start-user (some-> (.getVariable task "startUserId") str)
        user-name-of (fn [id]
                       (:user_name (first (filter #(= (str id) (str (:user_id %))) users))))
        user-names-of (fn [ids] (distinct (vec (keep user-name-of ids))))
        ;; 自选流程变量两种形态并存: 发起时传 user_id(数字), 审批时传 user_name;
        ;; 值能匹配用户名就原样保留, 否则按 user_id 换名, 最后只留真实存在的账号
        by-id (into {} (map (juxt (comp str :user_id) :user_name)) users)
        by-name (set (keep :user_name users))
        selected-names (fn [v]
                         (distinct (vec (filter by-name
                                                (map #(let [s (str %)]
                                                        (if (by-name s) s (by-id s)))
                                                     v)))))
        leaders-of (fn [dept-id]
                     (when-let [d (first (filter #(= (str dept-id) (str (:dept_id %))) depts))]
                       (when-let [leader (or (:leader_id d) (:leader d))]
                         (user-names-of [leader]))))
    start-user-dept-id (fn [start-user]
                         (:dept_id (first (filter #(= start-user (str (:user_name %))) users))))
    ;; 部门 id 可能是 Long 或 String, 一律按字符串比较
    dept-parent-id (fn [dept-id]
                     (some (fn [d] (when (= (str dept-id) (str (:dept_id d)))
                                     (:parent_id d)))
                           depts))]
    (case strategy
      "INITIATOR_SELF"
      (when (seq start-user) [start-user])
      "USER_GROUP"
      (let [gids (set (map str (:user-group-ids param)))
            groups (query-fn :bpmmgmt/group-list {:name nil :page_size 100000 :offset 0})
            ids (distinct (mapcat #(str/split (str (:user_ids %)) #"[,\s]+")
                                  (filter #(contains? gids (str (:group_id %))) groups)))]
        (user-names-of ids))
      "FORM_USER"
      (let [f (:form-user-field param)
            v (when f (.getVariable task (name f)))]
        (cond
          (and (coll? v) (seq v)) (user-names-of v)
          (and (string? v) (seq v))
          (if (some #(= v (:user_name %)) users) [v] (user-names-of [v]))
          (some? v) (user-names-of [v])
          :else nil))
      "FORM_DEPT_LEADER"
      (let [f (:form-dept-field param)
            v (when f (.getVariable task (name f)))]
        (leaders-of v))
      "EXPRESSION"
      (let [expr (or (:expression node-config)
                     (when-let [eid (:expression-id node-config)]
                       (:expression (query-fn :bpmmgmt/find-expression-by-id
                                              {:expression_id eid}))))
            execution (when-let [pid (.getProcessInstanceId task)]
                        (some-> (.createExecutionQuery (.getRuntimeService engine))
                                (.processInstanceId pid)
                                (.singleResult)))]
        (when (and expr execution)
          (let [v (try
                    (-> (.getExpressionManager (.getProcessEngineConfiguration engine))
                        (.createExpression expr)
                        (.getValue execution))
                    (catch Exception e
                      (log/warn "[bpm-node] 表达式求值失败:" (.getMessage e))))]
            (cond
              (and (coll? v) (seq v)) (mapv str v)
              (and (string? v) (seq v)) [v]
              (nil? v) nil
              :else [(str v)]))))
      "START_USER_DEPT_LEADER"
      (when-let [did (start-user-dept-id start-user)]
        (leaders-of did))
      "MULTI_LEVEL_DEPT_LEADER"
      (when-let [did (start-user-dept-id start-user)]
        (distinct
          (loop [did did n 0 acc []]
            (if (or (nil? did) (>= n 3))
              acc
              (recur (dept-parent-id did)
                     (inc n)
                     (concat acc (leaders-of did)))))))
      "START_USER_SELECT"
      (let [v (some-> (.getVariable task "startUserSelected") seq)]
        (when (seq v) (selected-names v)))
      "APPROVE_USER_SELECT"
      (let [v (some-> (.getVariable task "approveUserSelected") seq)]
        (when (seq v) (selected-names v)))
      nil)))


;; ── Phase 3 自动去重(模型级 auto_approval_type)────────────────────────

(defn- model-auto-approval-type
  "任务所属流程模型的 auto_approval_type(NONE/APPROVE_ONCE/CONSECUTIVE)."
  [engine query-fn ^org.flowable.task.service.delegate.DelegateTask task]
  (let [pd (some-> (.getRepositoryService engine)
                   (.createProcessDefinitionQuery)
                   (.processDefinitionId (.getProcessDefinitionId task))
                   .singleResult)]
    (when pd
      (or (some-> (query-fn :bpm/find-model-by-key {:model_key (.getKey pd)})
                  :auto_approval_type)
          "NONE"))))


(defn- auto-approved?
  "按去重类型判断当前节点是否应自动通过(依据 complete* 维护的流程变量,同命令内可见):
   APPROVE_ONCE -- 任一办理人在本实例已完成过任务;
   CONSECUTIVE  -- 本实例最近一个已办任务的办理人与当前办理人相同."
  [^org.flowable.task.service.delegate.DelegateTask task users auto-type]
  (let [approved-users (vec (or (.getVariable task "bpmApprovedUsers") []))
        last-approver (some-> (.getVariable task "bpmLastApprover") str)
        user-set (set (map str users))]
    (case auto-type
      "APPROVE_ONCE"
      (boolean (some #(contains? user-set (str %)) approved-users))
      "CONSECUTIVE"
      (boolean (and (seq last-approver) (contains? user-set last-approver)))
      false)))


(defn- apply-auto-approval
  "把节点 create 处理器算出的 action 再经过模型级自动去重过滤:
   命中去重规则时覆盖为 [:complete true](自动通过);否则原样返回.
   action 为 nil(静态候选由引擎烘焙)时,用任务的候选人 identityLink 判断."
  [engine query-fn ^org.flowable.task.service.delegate.DelegateTask task action]
  (let [auto-type (model-auto-approval-type engine query-fn task)]
    (if (= "NONE" auto-type)
      action
      (let [users (cond
                    (and (vector? action)
                         (contains? #{:assign :candidates} (first action))) (seq (second action))
                    (nil? action) (seq (keep (fn [^org.flowable.identitylink.api.IdentityLink l]
                                               (.getUserId l))
                                             (.getCandidates task)))
                    :else nil)]
        (if (and (seq users) (auto-approved? task users auto-type))
          [:complete true]
          action)))))


(defn- make-node-create-handler
  "构建节点 create 事件处理器(Phase 2 节点配置补全的核心):
   按 candidate-strategy 解析候选人(含 5 种新策略),随后依次应用:
     1) 多实例方式(ANY/ALL/RATIO) → 不干预(返回 nil)
     2) RANDOM 随机审批 → 从候选中随机指定一人为 assignee
     3) 审批人为空策略 assign-empty-handler:
        AUTO_PASS 自动通过 / AUTO_REJECT 自动驳回 / ASSIGN_USER 指定成员 / TO_ADMIN(默认) 转交管理员
   数据库查询按需延迟执行:静态策略且候选已烘焙时零查询."
  [engine query-fn]
  (let [user-names-of (fn [users ids]
                        (distinct (vec (keep (fn [id]
                                               (:user_name (first (filter #(= (str id) (str (:user_id %))) users))))
                                             ids))))
        empty-action (fn [node-config users]
                       (let [eh (:assign-empty-handler node-config)
                             etype (or (:type eh) "TO_ADMIN")]
                         (case etype
                           "AUTO_PASS" [:complete true]
                           "AUTO_REJECT" [:complete false]
                           "ASSIGN_USER" (let [names (user-names-of users (:user-ids eh))]
                                           (if (seq names) [:assign names] [:assign ["admin"]]))
                           [:assign ["admin"]])))]
    (fn [^org.flowable.task.service.delegate.DelegateTask task node-config]
      (let [strategy (:candidate-strategy node-config)
            param (or (:candidate-param node-config) {})
            method (:approve-method node-config)
            random? (= "RANDOM" method)
            users* (delay (list-all-users query-fn))
            depts* (delay (query-fn :list-all-depts {}))]
        (apply-auto-approval
          engine query-fn task
          (when-not (contains? multi-instance-methods method)
            (if (contains? dynamic-strategies strategy)
              ;; ── 动态解析策略(含 5 种新策略):create 时解析候选人 ──
              (let [users @users*
                    depts @depts*
                    start-user (some-> (.getVariable task "startUserId") str)
                    leaders-of (fn [dept-id]
                                 (when-let [d (first (filter #(= (str dept-id) (str (:dept_id %))) depts))]
                                   (when-let [leader (or (:leader_id d) (:leader d))]
                                     (user-names-of users [leader]))))
                    base (resolve-candidate-users engine query-fn task strategy param node-config users depts)
                    start-handler (:assign-start-user-handler-type node-config)
                    candidates (distinct (vec (keep identity base)))
                    candidates (cond
                                 (= start-handler "SKIP") (remove #(= start-user %) candidates)
                                 (= start-handler "ASSIGN_DEPT_LEADER")
                                 (if (some #(= start-user %) candidates)
                                   (concat (remove #(= start-user %) candidates)
                                           (when start-user
                                             (leaders-of (:dept_id (first (filter #(= start-user (str (:user_name %))) users))))))
                                   candidates)
                                 :else candidates)]
                (cond
                  (seq candidates) (if random? [:assign [(rand-nth candidates)]] [:candidates candidates])
                  (= start-handler "SKIP") [:complete nil]
                  :else (empty-action node-config users)))
              ;; ── 静态策略:候选由引擎从 BPMN 属性烘焙,监听器只做 RANDOM/为空兜底 ──
              (let [links (seq (.getCandidates task))
                    user-links (vec (keep (fn [^org.flowable.identitylink.api.IdentityLink l]
                                            (.getUserId l))
                                          links))]
                (cond
                  (seq user-links)
                  (if random? [:assign [(rand-nth user-links)]] nil)
                  (seq links)
                  (let [users @users*
                        cands (candidates-from-links task users @depts*
                                                     (query-fn :list-user-roles {})
                                                     (query-fn :list-user-posts {}))]
                    (if (seq cands)
                      (if random? [:assign [(rand-nth cands)]] nil)
                      (empty-action node-config users)))
                  :else (empty-action node-config @users*))))))))))


(declare fire-webhooks! fire-node-listener!)


(defmethod ig/init-key :app.business/bpm-service
  [_ {:keys [engine query-fn db]}]
  (let [service {:engine engine :query-fn query-fn :db db}]
    ;; 注入节点 create 处理器(TaskListener 在任务创建时调用:候选解析/为空策略/随机审批)
    (bpm/set-node-create-handler! (make-node-create-handler engine query-fn))
    ;; 注册抄送节点处理器(TaskListener create 时自动插抄送记录并完成任务)
    (bpm/set-copy-handler!
      (fn [task node-config]
        (copy-task-handler engine query-fn task node-config)))
    ;; Phase 4:模型级 Webhook + 节点监听器分发器(引擎封装层统一触发点转发到这里)
    (bpm/set-webhook-dispatcher!
      (fn [event info]
        (fire-webhooks! event service info)))
    ;; 流程实例结束回写:approve→"2"(通过) reject→"3"(驳回) cancel/terminate→CANCELED,
    ;; current_task 一并清空(挂在 complete* 的 process_end 检测点 + 取消/终止路径)
    (bpm/set-instance-end-handler!
      (fn [{:keys [process-instance-id result]}]
        (query-fn :bpm/update-instance-status
                  {:process_instance_id process-instance-id
                   :status (case result
                             :approve "2"
                             :reject "3"
                             "CANCELED")
                   :current_task ""})))
    (bpm/set-node-listener-dispatcher!
      (fn [event-name task]
        (fire-node-listener! service event-name task)))
    service))






;; ── Phase 3 治理能力:编号规则 / 标题渲染 / 摘要计算 ────────────────────


(defn- form-fields-of
  "动态表单 schema 的字段列表 [{:field :title}]."
  [query-fn form-id]
  (when-let [form (and form-id (query-fn :bpm/find-form-by-id {:form_id form-id}))]
    (:fields (jf/parse-field (:form_json form)))))


(defn summary-of
  "按模型 summary_fields(表单字段 id 列表)计算实例摘要,
   返回 [{:key :value :label}];未配置或无表单数据时返回 nil."
  [query-fn summary-fields form-id form-data]
  (let [fields (seq (jf/parse-field summary-fields))]
    (when (and (seq fields) (map? form-data))
      (let [labels (into {}
                         (keep (fn [f]
                                 (when-let [fid (or (:field f) (:id f))]
                                   [(name fid) (or (:title f) (:label f) (name fid))])))
                         (or (form-fields-of query-fn form-id) []))]
        (vec (keep (fn [fid]
                     (let [k (name fid)]
                       (when (contains? form-data (keyword k))
                         {:key k
                          :value (str (get form-data (keyword k)))
                          :label (str (get labels k k))})))
                   fields))))))


(defn- gen-bill-code
  "按模型 process_id_rule 生成流程单号:前缀+日期中缀+后缀+当日递增流水号(长度≥5).
   规则未启用时返回 nil."
  [query-fn model]
  (let [rule (jf/parse-field (:process_id_rule model))]
    (when (:enable rule)
      (let [now (java.time.LocalDateTime/now)
            full (.format now (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))
            infix (case (or (:infix rule) "DAY")
                    "DAY" (subs full 0 8)
                    "HOUR" (subs full 0 10)
                    "MINUTE" (subs full 0 12)
                    "SECOND" full
                    "")
            base (str (or (:prefix rule) "") infix (or (:suffix rule) ""))
            length (max 5 (long (or (:length rule) 5)))
            max-code (:max_code (query-fn :bpm/max-bill-code {:model_id (:model_id model)
                                                              :like_pattern (str base "%")}))
            tail (when max-code (re-find #"\d+$" max-code))
            seq-n (if tail (inc (Long/parseLong tail)) 1)]
        (str base (format (str "%0" length "d") seq-n))))))


(defn- render-instance-name
  "按模型 name_rule 渲染实例名.模板支持 {字段id},{发起人},{发起时间},{流程名称};
   未配置时回退为模型名."
  [model form-data starter]
  (let [tpl (:name_rule model)]
    (if (seq tpl)
      (let [fmap (into {} (map (fn [[k v]] [(name k) v])) (or form-data {}))
            now (java.time.LocalDateTime/now)
            fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")]
        (-> (str tpl)
            (str/replace #"\{([\w-]+)\}"
                         (fn [[_ k]] (str (get fmap k (str "{" k "}")))))
            (str/replace "{发起人}" (or starter ""))
            (str/replace "{发起时间}" (.format now fmt))
            (str/replace "{流程名称}" (or (:model_name model) ""))))
      (or (:model_name model) ""))))


;; ── 流程分类 ──────────────────────────────────────────────────────────
(defn category-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (paging/params params)
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
             :order_num (or (:order_num params) 0)
             :create_by (or user "") :remark (or (:remark params) "")}))


(defn category-update
  [{:keys [query-fn]} params user]
  (query-fn :bpm/update-category
            {:category_id (:category_id params) :name (:name params)
             :code (:code params) :sort (or (:sort params) 0)
             :order_num (:order_num params)
             :status (:status params) :update_by (or user "") :remark (:remark params)}))


(defn category-delete
  [{:keys [query-fn]} id]
  (query-fn :bpm/delete-category {:category_id id}))


(defn category-sort!
  "P1:批量保存分类排序(ids 按新顺序排列,order_num = 下标×10)."
  [{:keys [query-fn]} ids]
  (doseq [[i id] (map-indexed vector (or ids []))]
    (query-fn :bpm/update-category-order {:category_id id :order_num (* i 10)}))
  {:sorted (count (or ids []))})


;; ── 流程模型 ──────────────────────────────────────────────────────────
(defn- deploy-time-of
  "按 deployment_id 从 Flowable 查最新部署时间(未部署返回 nil)."
  [engine deployment-id]
  (when (seq (str (or deployment-id "")))
    (try
      (some-> (.getRepositoryService ^org.flowable.engine.ProcessEngine engine)
              (.createDeploymentQuery)
              (.deploymentId (str deployment-id))
              (.singleResult)
              (.getDeploymentTime)
              (str))
      (catch Exception _ nil))))


(defn- json-ids
  "JSON 文本 → id 字符串向量(nil/非法返回 [])."
  [v]
  (let [parsed (jf/parse-field v)]
    (if (sequential? parsed) (mapv str parsed) [])))


(defn model-list
  "P1:行附带最新部署时间(deploy_time),可发起人员/部门名简表(start_users/start_depts),
   分类名随 LEFT JOIN 返回;排序按 order_num;前端按 category_id 自行分组."
  [{:keys [engine query-fn]} params]
  (let [{:keys [offset size]} (paging/params params)
        p {:model_name (get params :model_name) :category_id (get params :category_id)
           :page_size size :offset offset}
        users (list-all-users query-fn)
        depts (query-fn :list-all-depts {})
        uname (into {} (map (juxt (comp str :user_id) :user_name)) users)
        dname (into {} (map (juxt (comp str :dept_id) :dept_name)) depts)]
    {:rows (mapv (fn [row]
                   (let [data (jf/parse-columns row [:form_json :bpmn_xml :webhooks
                                              :start_user_ids :start_dept_ids :manager_user_ids])]
                     (assoc data
                            :deploy_time (deploy-time-of engine (:deployment_id data))
                            :start_users (mapv uname (json-ids (:start_user_ids data)))
                            :start_depts (mapv dname (json-ids (:start_dept_ids data))))))
                 (query-fn :bpm/model-list p))
     :total (:total (query-fn :bpm/model-count p))}))


(defn model-get
  [{:keys [query-fn]} id]
  (-> (query-fn :bpm/find-model-by-id {:model_id id})
      (jf/parse-columns [:form_json :bpmn_xml :webhooks])))


(defn model-get-by-key
  [{:keys [query-fn]} key]
  (-> (query-fn :bpm/find-model-by-key {:model_key key})
      (jf/parse-columns [:form_json :bpmn_xml :webhooks])))


(def ^:private model-key-pattern
  "流程 key 校验:字母/下划线开头,可含字母数字 _ - . $."
  #"^[a-zA-Z_][-\w.$]*$")


(defn- default-model-bpmn
  "新建模型的默认 BPMN 骨架(发起人 → 结束),设计器打开即可继续添加节点."
  [model-key]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
       " xmlns:flowable=\"http://flowable.org/bpmn\" id=\"def\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<process id=\"" model-key "\" name=\"流程\" isExecutable=\"true\">"
       "<startEvent id=\"start\" name=\"发起人\"/>"
       "<endEvent id=\"end\" name=\"结束\"/>"
       "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/>"
       "</process></definitions>"))


(defn model-create
  "新建流程模型:校验 key 格式(字母/下划线开头,可含字母数字与 _ - . $)与重名,
   缺省字段补默认值(默认 BPMN 骨架,allow_cancel/allow_withdraw 默认 '1').
   P1:icon/order_num/start_user_ids/start_dept_ids/manager_user_ids 一并入库."
  [{:keys [query-fn]} params user]
  (let [key (some-> (:model_key params) str str/trim)]
    (when (str/blank? key)
      (throw (ex-info "流程key不能为空" {:model_key (:model_key params)})))
    (when-not (re-matches model-key-pattern key)
      (throw (ex-info "流程key格式不正确：须以字母或下划线开头，只能包含字母、数字、_ - . $" {})))
    (when (query-fn :bpm/find-model-by-key {:model_key key})
      (throw (ex-info (str "流程key已存在：" key) {:model_key key})))
    (query-fn :bpm/insert-model
              {:model_key key :model_name (or (:model_name params) key)
               :category_id (or (:category_id params) 0) :version 1
               :form_type (or (:form_type params) "0")
               :form_id (or (:form_id params) 0)
               :form_custom_create_path (or (:form_custom_create_path params) "")
               :form_custom_view_path (or (:form_custom_view_path params) "")
               :form_json (:form_json params) :fields_permission (:fields_permission params)
               :bpmn_xml (or (:bpmn_xml params) (default-model-bpmn key))
               :deployment_id (:deployment_id params) :status (or (:status params) "1")
               :icon (or (:icon params) "")
               :order_num (or (:order_num params) 0)
               :start_user_ids (:start_user_ids params)
               :start_dept_ids (:start_dept_ids params)
               :manager_user_ids (:manager_user_ids params)
               :process_id_rule (:process_id_rule params)
               :auto_approval_type (or (:auto_approval_type params) "NONE")
               :name_rule (:name_rule params) :summary_fields (:summary_fields params)
               :print_template_enable (or (:print_template_enable params) "0")
               :webhooks (:webhooks params)
               :print_template_html (:print_template_html params)
               :allow_cancel (or (:allow_cancel params) "1")
               :allow_withdraw (or (:allow_withdraw params) "1")
               :create_by (or user "") :remark (or (:remark params) "")})))


(defn model-update
  "P1:icon/start_user_ids/start_dept_ids/manager_user_ids/order_num 走 COALESCE,
   传 nil 时保留原值(model-save-tree! 等部分更新调用方无需关注新列)."
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
             :icon (:icon params)
             :order_num (:order_num params)
             :start_user_ids (:start_user_ids params)
             :start_dept_ids (:start_dept_ids params)
             :manager_user_ids (:manager_user_ids params)
             :process_id_rule (:process_id_rule params)
             :auto_approval_type (:auto_approval_type params)
             :name_rule (:name_rule params) :summary_fields (:summary_fields params)
             :print_template_enable (:print_template_enable params)
             :webhooks (:webhooks params)
             :print_template_html (:print_template_html params)
             :allow_cancel (:allow_cancel params)
             :allow_withdraw (:allow_withdraw params)
             :update_by (or user "") :remark (:remark params)}))


(defn model-sort!
  "P1:批量保存模型排序(ids 按新顺序排列,order_num = 下标×10)."
  [{:keys [query-fn]} ids]
  (doseq [[i id] (map-indexed vector (or ids []))]
    (query-fn :bpm/update-model-order {:model_id id :order_num (* i 10)}))
  {:sorted (count (or ids []))})


(defn model-delete
  [{:keys [engine query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (when-let [dep-id (:deployment_id m)]
      (try (bpm/delete-deployment! engine dep-id)
           (catch Exception _ nil)))
    (query-fn :bpm/delete-model {:model_id id})))


(defn- load-identity-data
  "加载系统用户/角色/部门/岗位关系,用于同步到 Flowable identity."
  [query-fn]
  {:users (query-fn :list-all-users {})
   :roles (query-fn :list-roles {:role_name nil :role_key nil :status nil})
   :depts (query-fn :list-all-depts {})
   :posts (query-fn :list-posts {:post_code nil :post_name nil :status nil})
   :user-roles (query-fn :list-user-roles {})
   :user-posts (query-fn :list-user-posts {})})


(defn model-deploy!
  "部署流程模型到 Flowable,并回写 deployment_id.返回新 deployment-id."
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
  "把模型 BPMN 转为流程节点树(HTML/flex 编辑器工作模型)."
  [{:keys [query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (bpm-flow/bpmn->tree (:bpmn_xml m))))


(defn model-save-tree!
  "保存流程节点树:转回 BPMN XML 并更新模型.返回新 XML.
   P1:新列(icon/start_user_ids/start_dept_ids/manager_user_ids)传 nil 走 COALESCE 保留原值."
  [{:keys [query-fn]} id tree user]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})
        users (query-fn :list-all-users {})
        user-map (into {} (map (juxt (comp str :user_id) :user_name)) users)
        groups (into {} (map (fn [g]
                               [(str (:group_id g))
                                (remove str/blank? (str/split (str (:user_ids g)) #"[,\s]+"))]))
                     (query-fn :bpmmgmt/group-list {:name nil :page_size 100000 :offset 0}))
        xml (bpm-flow/tree->bpmn (clojure.walk/keywordize-keys tree) (:model_key m) user-map groups)]
    (query-fn :bpm/update-model
              {:model_id id :model_name (:model_name m)
               :category_id (:category_id m) :form_type (:form_type m)
               :form_id (or (:form_id m) 0)
               :form_custom_create_path (or (:form_custom_create_path m) "")
               :form_custom_view_path (or (:form_custom_view_path m) "")
               :form_json (:form_json m) :fields_permission (:fields_permission m)
               :bpmn_xml xml :deployment_id nil
               :icon (:icon m)
               :order_num (:order_num m)
               :start_user_ids (:start_user_ids m)
               :start_dept_ids (:start_dept_ids m)
               :manager_user_ids (:manager_user_ids m)
               :process_id_rule (:process_id_rule m)
               :auto_approval_type (:auto_approval_type m)
               :name_rule (:name_rule m) :summary_fields (:summary_fields m)
               :print_template_enable (:print_template_enable m)
               :print_template_html (:print_template_html m)
               :webhooks (:webhooks m)
               :allow_cancel (:allow_cancel m)
               :allow_withdraw (:allow_withdraw m)
               :status "1" :update_by (or user "") :remark (:remark m)})
    {:bpmn_xml xml}))


;; ── 动态表单 ──────────────────────────────────────────────────────────
(defn form-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (paging/params params)
        p {:form_name (get params :form_name) :status (get params :status)
           :page_size size :offset offset}]
    {:rows (mapv #(jf/parse-columns % [:form_json]) (query-fn :bpm/form-list p))
     :total (:total (query-fn :bpm/form-count p))}))


(defn form-get
  [{:keys [query-fn]} id]
  (-> (query-fn :bpm/find-form-by-id {:form_id id})
      (jf/parse-columns [:form_json])))


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


;; ── 流程实例(发起 + 运行)────────────────────────────────────────────
(defn- candidate-names
  "按候选策略把配置 ids 转用户名列表."
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
  "收集流程树中多实例审批节点(approve-method 为 ANY/ALL/RATIO).
   RANDOM 随机审批不是多实例,由 TaskListener 在 create 时指定 assignee,不注入 approverList."
  [tree]
  (let [walk (fn walk
               [node acc]
               (if (nil? node)
                 acc
                 (let [cfg (:config node)
                       acc' (if (and cfg (= "USER" (:approve-type cfg))
                                     (contains? #{"ANY" "ALL" "RATIO"}
                                                (or (:approve-method cfg) "SEQUENTIAL")))
                              (conj acc {:id (:id node) :config cfg})
                              acc)]
                   (-> acc'
                       (into (walk (:child-node node) []))
                       (into (mapcat #(walk % []) (or (:condition-nodes node) [])))))))]
    (vec (walk tree []))))


(defn- collect-child-multi-nodes
  "P1:收集子流程多实例节点(mi-enable),返回 [{:id :config}]."
  [tree]
  (let [walk (fn walk
               [node acc]
               (if (nil? node)
                 acc
                 (let [cfg (:config node)
                       acc' (if (and (= "CHILD_PROCESS_NODE" (:type node))
                                     (:mi-enable cfg))
                              (conj acc {:id (:id node) :config cfg})
                              acc)]
                   (-> acc'
                       (into (walk (:child-node node) []))
                       (into (mapcat #(walk % []) (or (:condition-nodes node) [])))))))]
    (vec (walk tree []))))


(defn- child-multi-vars
  "P1:子流程多实例实例数量 → miList_<node-id> 列表变量:
   FIXED 固定数量 / NUMERIC_FIELD 数字表单字段 / MULTI_FIELD 多选表单字段."
  [tree fd]
  (into {}
        (keep (fn [{:keys [id config]}]
                (let [source (:mi-source config)
                      kw (keyword (str (:mi-field config)))
                      n (case source
                          "FIXED" (max 1 (long (or (:mi-count config) 1)))
                          "NUMERIC_FIELD" (max 1 (long (or (get fd kw) (get fd (str (:mi-field config))) 1)))
                          nil)]
                  [(str "miList_" id)
                   (case source
                     "MULTI_FIELD" (vec (or (get fd kw) (get fd (str (:mi-field config))) []))
                     (vec (repeat n 1)))])))
        (collect-child-multi-nodes tree)))


(defn- dept-and-parents
  "部门 id → 自身 + 全部上级部门 id 字符串列表."
  [query-fn dept-id]
  (let [depts (query-fn :list-all-depts {})
        by-id (into {} (map (juxt (comp str :dept_id) identity)) depts)]
    (loop [did (some-> dept-id str) acc [] seen #{}]
      (if (or (nil? did) (contains? seen did))
        acc
        (if-let [d (get by-id did)]
          (recur (some-> (:parent_id d) str) (conj acc did) (conj seen did))
          (conj acc did))))))


(defn- check-start-permission!
  "P1 发起校验:模型配置了 start_user_ids / start_dept_ids 时,
   当前用户必须在指定人员内,或其所在部门(含上级)在指定部门内,
   否则抛 500\"您没有权限发起该流程\".两者都未配置 = 全员可发起."
  [query-fn model starter]
  (let [uid-set (set (json-ids (:start_user_ids model)))
        did-set (set (json-ids (:start_dept_ids model)))]
    (when (or (seq uid-set) (seq did-set))
      (let [u (first (filter #(= (str starter) (str (:user_name %)))
                             (list-all-users query-fn)))
            dept-ok? (when (and u (seq did-set))
                       (some #(contains? did-set %)
                             (dept-and-parents query-fn (:dept_id u))))]
        (when-not (and u (or (and (seq uid-set) (contains? uid-set (str (:user_id u))))
                             dept-ok?))
          (throw (ex-info "您没有权限发起该流程" {:model_id (:model_id model)})))))))


(defn instance-start!
  "发起流程:用模型部署的 key 启动 Flowable 实例,写入 biz_bpm_instance.
   P1:模型配置 start_user_ids/start_dept_ids 时先校验发起权限;
   子流程多实例节点(mi-enable)注入 miList_<id> 实例数量列表变量."
  [{:keys [engine query-fn]} model-id business-key form-data starter]
  (let [m (query-fn :bpm/find-model-by-id {:model_id model-id})
        _ (when-not m (throw (ex-info "流程模型不存在" {:model_id model-id})))
        _ (check-start-permission! query-fn m starter)
        _ (when-not (:deployment_id m)
            (throw (ex-info "模型未部署，请先部署" {:model_id model-id :key (:model_key m)})))
        _ (when (:suspended? (bpm/latest-definition engine (:model_key m)))
            (throw (ex-info "流程定义已挂起，不可发起" {:model_id model-id :key (:model_key m)})))
        ;; 发起前同步组织身份 (角色/部门/岗位/部门负责人成员关系), 组织调整后无需重新部署模型
        _ (bpm/sync-identity! engine (load-identity-data query-fn))
        biz-key (or business-key (str "biz-" (System/currentTimeMillis)))
        fd (or form-data {})
        ;; 表单字段展开为流程变量(条件表达式 ${days > 3} 可直接引用),formData 保留完整 JSON
        field-vars (into {}
                         (keep (fn [[k v]]
                                 (when (not= (name k) "startUserSelected")
                                   [(name k) v])))
                         fd)
        users (query-fn :list-all-users {})
        tree (bpm-flow/bpmn->tree (:bpmn_xml m))
        multi-vars (into {}
                         (map (fn [{:keys [id config]}]
                                [(str "approverList_" id) (candidate-names config users)]))
                         (collect-multi-nodes tree))
        child-mi-vars (child-multi-vars tree fd)
        started (bpm/start! engine (:model_key m) biz-key
                            (cond-> (merge {"formData" (json/generate-string fd)
                                            "startUserId" (or starter "")}
                                           field-vars multi-vars child-mi-vars)
                              (seq (get fd :startUserSelected))
                              (assoc "startUserSelected" (vec (get fd :startUserSelected)))))
        pid (:process-instance-id started)
        bill-code (gen-bill-code query-fn m)
        inst-name (render-instance-name m fd starter)]
    (query-fn :bpm/insert-instance
              {:process_instance_id pid :model_id model-id :model_key (:model_key m)
               :business_key biz-key
               :form_data_json (json/generate-string (or form-data {}))
               :starter_id (or starter "") :status "1"
               :name inst-name :bill_code bill-code
               :current_task (->> (bpm/active-tasks-of engine pid)
                                  (map :name)
                                  (remove nil?)
                                  (str/join "、"))})
    ;; Phase 4 Webhook:流程发起钩子
    (fire-webhooks! "process_start" {:engine engine :query-fn query-fn}
                    {:process-instance-id pid})
    {:process-instance-id pid :business-key biz-key :bill-code bill-code :name inst-name}))


(defn instance-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (paging/params params)
        p {:starter_id (get params :starter_id) :model_key (get params :model_key)
           :page_size size :offset offset}]
    {:rows (mapv (fn [row]
                   (let [data (jf/parse-columns row [:form_data_json])]
                     (assoc data :summary (summary-of query-fn (:summary_fields data)
                                                      (:form_id data) (:form_data_json data)))))
                 (query-fn :bpm/instance-list p))
     :total (:total (query-fn :bpm/instance-count p))}))


(defn task-detail
  "任务详情:任务信息 + 实例表单数据 + 表单 schema(审批弹窗表单回显).
   Phase 2:附带当前节点操作按钮配置(buttons),签名/意见必填/默认驳回节点配置.
   P0:办理人节点(TRANSACTOR)默认按钮为\"办理\";模型权限开关 allow_cancel/allow_withdraw 一并返回."
  [{:keys [engine query-fn]} task-id]
  (let [task-obj (some-> (.taskId (.createTaskQuery (.getTaskService engine)) task-id) .singleResult)
        _ (when-not task-obj (throw (ex-info "任务不存在" {:task-id task-id})))
        task (bpm/task->map* task-obj)
        pid (:process-instance-id task)
        inst (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        model (query-fn :bpm/find-model-by-id {:model_id (:model_id inst)})
        ;; form_id>0 走表单记录;form_id=0/空且模型内嵌 form_json 时回退解析模型的表单
        form (when-let [fid (:form_id model)]
               (when (pos? (long (or fid 0)))
                 (query-fn :bpm/find-form-by-id {:form_id fid})))
        schema (when-let [fj (or (:form_json form) (:form_json model))]
                 (if (string? fj) (json/parse-string fj true) fj))
        inst-data (jf/parse-columns inst [:form_data_json])
        node-config (bpm/node-config-of engine task-obj)
        reject-handler (:reject-handler node-config)
        transactor? (= "TRANSACTOR" (get-in node-config [:nodeType]))]
    {:task task
     :model {:model-id (:model_id model)
             :model_name (:model_name model) :model_key (:model_key model)
             :allow_cancel (:allow_cancel model) :allow_withdraw (:allow_withdraw model)}
     :fields-permission (or (:fields-permission node-config) {})
     :buttons (if transactor?
                (bpm/buttons-of node-config bpm/default-transactor-buttons)
                (bpm/buttons-of node-config))
     :sign-enable (boolean (or (:sign-enable node-config) (:signEnable node-config)))
     :select-user-require (= "APPROVE_USER_SELECT"
                             (:candidate-strategy (bpm/next-node-config-of engine task-obj)))
     :reason-require (boolean (or (:reason-require node-config) (:reasonRequire node-config)))
     :reject-return-node (or (:return-node-id reject-handler) (:return-node reject-handler)
                             (:reject-return-node node-config) (:rejectReturnNode node-config))
     :form {:schema schema :values (:form_data_json inst-data)}}))


(defn- memoize-by
  "按 key-fn 记忆化的单参函数 (null 参数不缓存).
   列表接口逐行查实例/模型时会重复命中同一条记录, 缓存避免重复 SQL."
  [f key-fn]
  (let [cache (atom {})]
    (fn [x]
      (if (nil? x)
        (f x)
        (if-let [e (find @cache (key-fn x))]
          (val e)
          (let [v (f x)]
            (swap! cache assoc (key-fn x) v)
            v))))))


(defn- todo-row
  "单条待办行: 任务字段 + 节点按钮配置 + 实例名/单号/摘要."
  [engine query-fn inst-of model-of t]
  (let [inst (inst-of (some-> t .getProcessInstanceId str))
        model (when inst (model-of (:model_id inst)))
        node-config (bpm/node-config-of engine t)]
    (assoc (bpm/task->map* t)
           :buttons (if (= "TRANSACTOR" (get-in node-config [:nodeType]))
                      (bpm/buttons-of node-config bpm/default-transactor-buttons)
                      (bpm/buttons-of node-config))
           :instance-name (:name inst)
           :bill-code (:bill_code inst)
           :summary (summary-of query-fn (:summary_fields model)
                                (:form_id model) (:form_data_json inst)))))


(defn- todo-tasks
  "某人的待办任务查询 (候选人或已认领), 按创建时间倒序."
  ^org.flowable.task.api.TaskQuery
  [engine user]
  (-> (.createTaskQuery (.getTaskService engine))
      (.taskCandidateOrAssigned user)
      (.orderByTaskCreateTime)
      (.desc)))


(defn paged-params?
  "查询参数里是否显式给了 page 或 size (决定列表接口是否分页).
   分页与否只看显式参数: 调用方传 {} 时保持\"返回全部\"的旧语义."
  [params]
  (boolean (or (some? (get params :page)) (some? (get params :size)))))


(defn todo-list-with-buttons
  "某人待办(候选人或已认领),每行附带当前节点操作按钮配置(Buttons),
   实例名/单号与模型摘要(summary);办理人节点默认按钮为\"办理\".
   params 支持 :page/:size (与其它列表接口一致, 缺省全部返回)."
  ([service user] (todo-list-with-buttons service user {}))
  ([{:keys [engine query-fn]} user params]
   (let [{:keys [page size offset]} (paging/params params)
         paged? (paged-params? params)
         q (todo-tasks engine user)
         tasks (if paged? (.listPage q offset size) (.list q))
         inst-of (memoize-by #(some-> (query-fn :bpm/find-instance-by-pid
                                                {:process_instance_id %})
                                      (jf/parse-columns [:form_data_json]))
                             identity)
         model-of (memoize-by #(query-fn :bpm/find-model-by-id {:model_id %}) identity)]
     {:rows (mapv (partial todo-row engine query-fn inst-of model-of) tasks)
      :total (.count (todo-tasks engine user))
      :page (when paged? page) :size (when paged? size)})))



(defn done-list-with-model-flags
  "某人已办(Flowable 历史),每行附带所属模型的权限开关 allow_cancel/allow_withdraw
   (P0-4:前端据此显隐撤回按钮).实例/模型缺失时默认允许.
   params 支持 :page/:size; 返回 {:rows :total :page :size}."
  ([service user] (done-list-with-model-flags service user {}))
  ([{:keys [engine query-fn]} user params]
   (let [{:keys [page size offset]} (paging/params params)
         paged? (paged-params? params)
         rows (if paged?
                (bpm/done-list engine user {:limit size :offset offset})
                (bpm/done-list engine user))
         inst-of (memoize-by #(query-fn :bpm/find-instance-by-pid {:process_instance_id %}) identity)
         model-of (memoize-by #(query-fn :bpm/find-model-by-id {:model_id %}) identity)]
     {:rows (mapv (fn [row]
                    (let [inst (inst-of (some-> (:process-instance-id row) str))
                          model (when inst (model-of (:model_id inst)))]
                      (assoc row
                             :allow_cancel (or (:allow_cancel model) "1")
                             :allow_withdraw (or (:allow_withdraw model) "1"))))
                  rows)
      :total (bpm/done-count engine user)
      :page (when paged? page) :size (when paged? size)})))


;; ── 任务与实例的归属校验 ─────────────────────────────────────────────
;; 个人办理类接口只要求登录, 授权来自任务归属: 流程配置把谁设为审批人, 谁就能办理,
;; 不会因为角色未授权菜单而卡住流程; 越权操作返回 403.

(defn- forbidden
  [msg data]
  (ex-info msg (assoc data :status 403)))


(defn task-actor?
  "用户是否可以办理运行中的任务: 已指派给本人, 或未认领且本人是候选人 (含候选组)."
  [engine task-id user]
  (boolean
    (and (some? task-id) (not (str/blank? (str user)))
         (pos? (.count (.taskCandidateOrAssigned
                         (.taskId (.createTaskQuery (.getTaskService engine)) (str task-id))
                         (str user)))))))


(defn check-task-actor!
  "运行中任务必须存在且当前用户可以办理."
  [engine task-id user]
  (when-not (bpm/task-of engine (str task-id))
    (throw (ex-info "任务不存在或已完成" {:task-id task-id})))
  (when-not (task-actor? engine task-id user)
    (throw (forbidden "无权办理该任务(非本人)" {:task-id task-id}))))


(defn instance-participant?
  "用户是否参与了流程实例: 发起人, 抄送人, 历史或当前任务的办理人, 所有者或候选人."
  [{:keys [engine query-fn]} pid user]
  (let [user (str user)
        pid (str pid)]
    (boolean
      (and (not (str/blank? user)) (not (str/blank? pid))
           (or (= user (str (:starter_id (query-fn :bpm/find-instance-by-pid {:process_instance_id pid}))))
               (pos? (long (or (:total (query-fn :bpm/count-copy-of-user {:process_instance_id pid :user_id user})) 0)))
               (pos? (.count (-> (.createHistoricTaskInstanceQuery (.getHistoryService engine))
                                 (.processInstanceId pid)
                                 (.taskInvolvedUser user))))
               (pos? (.count (-> (.createTaskQuery (.getTaskService engine))
                                 (.processInstanceId pid)
                                 (.taskCandidateOrAssigned user)))))))))


(defn check-instance-viewer!
  "查看实例 (详情, 轨迹, 流程图, 打印) 需要是参与人或拥有流程管理权限 (manager? 由调用方按权限判断)."
  [service pid user manager?]
  (when-not (or manager? (instance-participant? service pid user))
    (throw (forbidden "无权查看该流程实例" {:process-instance-id pid}))))


(defn task-instance-id
  "运行中或历史任务所属的流程实例编号."
  [engine task-id]
  (or (:process-instance-id (bpm/task-of engine (str task-id)))
      (:process-instance-id (bpm/historic-task-of engine (str task-id)))))


(defn check-task-viewer!
  "查看任务相关信息: 任务所属实例的参与人或流程管理员."
  [{:keys [engine] :as service} task-id user manager?]
  (let [pid (task-instance-id engine task-id)]
    (when-not pid (throw (ex-info "任务不存在" {:task-id task-id})))
    (check-instance-viewer! service pid user manager?)))


(defn task-claim!
  "认领: 只有候选人可以认领未指派的任务."
  [{:keys [engine]} task-id user]
  (check-task-actor! engine task-id user)
  (bpm/claim! engine task-id user))


(defn- check-target-user!
  "转办/委派的目标必须是系统中启用且未删除的用户.
   不校验的话任务会被指派给不存在的用户名: 该用户永远不会登录,
   任务也不会再出现在任何人的待办里, 流程实例就此卡死."
  [query-fn task-id to-user]
  (let [to-user (some-> to-user str str/trim)
        target (when-not (str/blank? to-user)
                 (query-fn :find-user-by-name {:user_name to-user}))]
    (when (nil? target)
      (throw (ex-info (str "目标用户不存在或已停用: " to-user)
                      {:task-id task-id :to-user to-user :status 400})))
    to-user))


(defn task-transfer!
  "转办: 只有当前办理人 (或未认领任务的候选人) 可以转办, 目标必须是有效用户."
  [{:keys [engine query-fn]} task-id user to-user]
  (check-task-actor! engine task-id user)
  (bpm/transfer! engine task-id user (check-target-user! query-fn task-id to-user)))


(defn task-delegate!
  "委派: 只有当前办理人可以委派, 本人保留为任务所有者; 目标必须是有效用户."
  [{:keys [engine query-fn]} task-id user to-user]
  (check-task-actor! engine task-id user)
  (let [target (check-target-user! query-fn task-id to-user)
        t (bpm/task-of engine (str task-id))]
    (when (nil? (:assignee t))
      (bpm/claim! engine task-id user))
    (bpm/delegate! engine task-id target)))


(defn task-resolve!
  "委派办结: 只有被委派人 (当前办理人) 可以办结, 任务回到所有者."
  [{:keys [engine]} task-id user]
  (let [t (bpm/task-of engine (str task-id))]
    (when-not t (throw (ex-info "任务不存在或已完成" {:task-id task-id})))
    (when-not (= (str user) (str (:assignee t)))
      (throw (forbidden "只有被委派人可以办结" {:task-id task-id}))))
  (bpm/resolve! engine task-id))


(defn- reason-required?
  "任务节点是否配置审批意见必填."
  [engine task-id]
  (let [t (some-> (.taskId (.createTaskQuery (.getTaskService engine)) task-id) .singleResult)
        cfg (when t (bpm/node-config-of engine t))]
    (boolean (or (:reason-require cfg) (:reasonRequire cfg)))))


(defn task-approve!
  "审批通过:意见必填校验(nodeConfig.reason-require)+ 手写签名存任务局部变量.
   next-user-ids 非空时一并写入流程变量 approveUserSelected,供下一节点 APPROVE_USER_SELECT 使用."
  [{:keys [engine]} task-id user comment sign-pic-url next-user-ids]
  (check-task-actor! engine task-id user)
  (when (and (reason-required? engine task-id) (str/blank? (or comment "")))
    (throw (ex-info "当前节点要求填写审批意见" {:task-id task-id})))
  (bpm/approve! engine task-id user comment sign-pic-url next-user-ids))


(defn task-reject!
  "审批驳回:意见必填校验 + 手写签名存任务局部变量."
  [{:keys [engine]} task-id user comment return-node-id sign-pic-url]
  (check-task-actor! engine task-id user)
  (when (and (reason-required? engine task-id) (str/blank? (or comment "")))
    (throw (ex-info "当前节点要求填写审批意见" {:task-id task-id})))
  (bpm/reject! engine task-id user comment return-node-id sign-pic-url))


(defn instance-history
  "流程实例的完整历史轨迹:业务侧 + 活动轨迹 + 任务级审批历史 + 表单回显数据.
   P0-4:模型权限开关 allow_cancel/allow_withdraw 随模型返回,前端据此显隐取消/撤回按钮."
  [{:keys [engine query-fn]} pid]
  (let [biz (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        _ (when-not biz (throw (ex-info "流程实例不存在" {:pid pid})))
        model (query-fn :bpm/find-model-by-id {:model_id (:model_id biz)})
        ;; form_id>0 走表单记录;form_id=0/空且模型内嵌 form_json 时回退解析模型的表单
        form (when-let [fid (:form_id model)]
               (when (pos? (long (or fid 0)))
                 (query-fn :bpm/find-form-by-id {:form_id fid})))
        inst (jf/parse-columns biz [:form_data_json])]
    {:instance inst
     :model {:model_id (:model_id model) :model_name (:model_name model)
             :model_key (:model_key model) :form_type (:form_type model)
             :allow_cancel (:allow_cancel model) :allow_withdraw (:allow_withdraw model)}
     :form {:schema (when-let [fj (or (:form_json form) (:form_json model))]
                      (if (string? fj) (json/parse-string fj true) fj))
            :values (get inst :form_data_json)}
     :activities (bpm/history-of engine pid)
     :task-history (bpm/task-history-of engine pid)
     :running? (pos? (bpm/todo-count engine (or (:starter_id biz) "")))}))


(defn instance-diagram
  "流程实例的图示数据:BPMN XML + 进行中/已完成节点 id,供前端 bpmn-js 高亮."
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
  "办公一体化统计看板数据:请假/报销/流程/员工/客户."
  [{:keys [engine query-fn]} user]
  (let [leave-status (query-fn :stats/leave-by-status {})
        reimburse-status (query-fn :stats/reimburse-by-status {})
        leave-total (get (query-fn :stats/leave-total {}) :total 0)
        reimburse-total (get (query-fn :stats/reimburse-total {}) :total 0)
        employee-total (get (query-fn :stats/employee-total {}) :total 0)
        customer-total (get (query-fn :stats/customer-total {}) :total 0)
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


;; ── Phase 1 审批闭环:加签 / 减签 / 取消 / 撤回 / 抄送 / 可退回节点 ─────

(defn- check-sign-targets!
  "加签目标必须是系统中已存在的用户.
   加到不存在的用户名上时子任务无人可办, 只会阻塞后续节点(与转办同样的卡死风险)."
  [query-fn task-id user-names]
  (let [targets (mapv str user-names)
        missing (remove #(query-fn :find-user-by-name {:user_name %}) targets)]
    (when (seq missing)
      (throw (ex-info (str "加签用户不存在或已停用: " (str/join ", " missing))
                      {:task-id task-id :users missing :status 400})))
    targets))


(defn task-create-sign!
  "加签:仅任务当前办理人可操作.节点配置意见必填时 reason 不能为空, 加签人必须是有效用户."
  [{:keys [engine query-fn]} task-id user-names sign-type reason user]
  (when-not (bpm/task-of engine task-id) (throw (ex-info "任务不存在" {:task-id task-id})))
  (when-not (task-actor? engine task-id user)
    (throw (forbidden "只有任务办理人可以加签" {:task-id task-id})))
  (when (and (reason-required? engine task-id) (str/blank? (or reason "")))
    (throw (ex-info "当前节点要求填写审批意见" {:task-id task-id})))
  (bpm/create-sign! engine task-id (check-sign-targets! query-fn task-id user-names)
                    sign-type reason))


(defn task-delete-sign!
  "减签:仅任务当前办理人可操作."
  [{:keys [engine]} task-id user-names reason user]
  (when-not (bpm/task-of engine task-id) (throw (ex-info "任务不存在" {:task-id task-id})))
  (when-not (task-actor? engine task-id user)
    (throw (forbidden "只有任务办理人可以减签" {:task-id task-id})))
  (bpm/delete-sign! engine task-id user-names reason))


(defn task-sign-list
  "某任务的加签子任务列表."
  [{:keys [engine]} task-id]
  (bpm/sign-list engine task-id))


(defn task-return-list
  "当前任务之前已完成的用户任务节点列表(驳回可选目标)."
  [{:keys [engine]} task-id]
  (bpm/return-list engine task-id))


(defn instance-cancel!
  "取消流程实例:发起人或管理员.业务状态置为 CANCELED.
   P0-4:模型 allow_cancel=0 时发起人不可撤销审批中的申请(管理员不受限)."
  [{:keys [engine query-fn]} process-instance-id reason user admin?]
  (let [inst (query-fn :bpm/find-instance-by-pid {:process_instance_id process-instance-id})]
    (when-not inst (throw (ex-info "流程实例不存在" {:process-instance-id process-instance-id})))
    (when-not (or admin? (= user (:starter_id inst)))
      (throw (forbidden "只有发起人或管理员可以取消流程" {:process-instance-id process-instance-id})))
    (when-not admin?
      (let [model (query-fn :bpm/find-model-by-id {:model_id (:model_id inst)})]
        (when (and model (= "0" (str (:allow_cancel model))))
          (throw (ex-info "该流程模型已禁止发起人撤销审批中的申请"
                          {:process-instance-id process-instance-id})))))
    (bpm/cancel-instance! engine process-instance-id reason)
    (query-fn :bpm/update-instance-status {:process_instance_id process-instance-id
                                           :status "CANCELED" :current_task ""})))


(defn- check-model-withdraw-allowed!
  "模型 allow_withdraw=0 时禁止审批人撤回(P0-4 审批人权限开关)."
  [query-fn pid]
  (let [inst (query-fn :bpm/find-instance-by-pid {:process_instance_id pid})
        model (when inst (query-fn :bpm/find-model-by-id {:model_id (:model_id inst)}))]
    (when (and model (= "0" (str (:allow_withdraw model))))
      (throw (ex-info "该流程模型已禁止审批人撤回" {:process-instance-id pid})))))


(defn task-withdraw!
  "审批人撤回自己刚审完的任务(要求下一节点任务未完成)."
  [{:keys [engine query-fn]} task-id user]
  (let [ht (bpm/historic-task-of engine task-id)]
    (when ht
      (check-model-withdraw-allowed! query-fn (:process-instance-id ht))))
  (bpm/withdraw! engine task-id user))


(defn task-withdraw-to-start!
  "发起人撤回到起始节点重新编辑:发起人或管理员."
  [{:keys [engine query-fn]} process-instance-id user admin?]
  (let [inst (query-fn :bpm/find-instance-by-pid {:process_instance_id process-instance-id})]
    (when-not inst (throw (ex-info "流程实例不存在" {:process-instance-id process-instance-id})))
    (when-not (or admin? (= user (:starter_id inst)))
      (throw (forbidden "只有发起人或管理员可以撤回流程" {:process-instance-id process-instance-id})))
    (check-model-withdraw-allowed! query-fn process-instance-id)
    (bpm/withdraw-to-start! engine process-instance-id)))


(defn task-copy!
  "手动抄送:为每个抄送人插 biz_bpm_copy 记录."
  [{:keys [query-fn] :as service} process-instance-id user-names reason activity-id activity-name user]
  (when-not (instance-participant? service process-instance-id user)
    (throw (forbidden "只有流程参与人可以抄送" {:process-instance-id process-instance-id})))
  (when (empty? (seq user-names))
    (throw (ex-info "抄送人不能为空" {:process-instance-id process-instance-id})))
  (doseq [u user-names]
    (query-fn :bpm/insert-copy
              {:user_id (str u) :process_instance_id process-instance-id
               :activity_id (or activity-id "") :activity_name (or activity-name "")
               :reason (or reason "") :create_by (or user "")})))


(defn copy-page
  "我的抄送分页(当前登录用户)."
  [{:keys [query-fn]} params user]
  (let [{:keys [offset size]} (paging/params params)
        p {:user_id user :page_size size :offset offset}]
    {:rows (mapv (fn [row]
                   (let [data (jf/parse-columns row [:form_data_json])]
                     (assoc data :summary (summary-of query-fn (:summary_fields data)
                                                      (:form_id data) (:form_data_json data)))))
                 (query-fn :bpm/copy-page p))
     :total (:total (query-fn :bpm/copy-count p))}))


;; ── Phase 3 治理能力:定义版本页 / 模型启停·清理·复制 / 打印 ─────────────

(defn definition-page
  "流程定义分页(Flowable 侧,全部版本倒序).按 modelKey 过滤;
   附带模型表单绑定(form_type/form_id/form_name)与部署时间.
   P1:附带分类名(category_name)与发起权限简表(start_users)."
  [{:keys [engine query-fn]} params]
  (let [{:keys [offset size]} (paging/params params)
        key (get params :modelKey)
        result (bpm/definition-page engine key offset size)
        model (when (seq key) (query-fn :bpm/find-model-by-key {:model_key key}))
        form (when-let [fid (:form_id model)]
               (query-fn :bpm/find-form-by-id {:form_id fid}))
        cat (when-let [cid (:category_id model)]
              (query-fn :bpm/find-category-by-id {:category_id cid}))
        users (list-all-users query-fn)
        uname (into {} (map (juxt (comp str :user_id) :user_name)) users)]
    {:total (:total result)
     :rows (mapv (fn [row]
                   (assoc row
                          :model_name (:model_name model)
                          :form_type (:form_type model)
                          :form_id (:form_id model)
                          :form_name (:form_name form)
                          :category_name (:name cat)
                          :start_users (mapv uname (json-ids (:start_user_ids model)))))
                 (:rows result))}))


(defn definition-xml
  "流程定义的 BPMN XML(查看/恢复用)."
  [{:keys [engine]} definition-id]
  (let [key (bpm/definition-key-of engine definition-id)]
    (when-not key
      (throw (ex-info "流程定义不存在" {:definition-id definition-id})))
    {:definition-id definition-id
     :model-key key
     :xml (bpm/definition-xml engine definition-id)}))


(defn definition-restore!
  "把历史流程定义的 BPMN 反写回模型(bpmn_xml),清空 deployment_id 以便重新编辑部署."
  [{:keys [engine query-fn]} definition-id]
  (let [key (bpm/definition-key-of engine definition-id)]
    (when-not key
      (throw (ex-info "流程定义不存在" {:definition-id definition-id})))
    (let [xml (bpm/definition-xml engine definition-id)]
      (when-not (seq xml)
        (throw (ex-info "无法读取定义 BPMN" {:definition-id definition-id})))
      (let [model (query-fn :bpm/find-model-by-key {:model_key key})]
        (when-not model
          (throw (ex-info "找不到对应流程模型" {:model-key key})))
        (query-fn :bpm/restore-model {:model_id (:model_id model) :bpmn_xml xml})
        {:model_id (:model_id model) :model_key key}))))


(defn model-set-state!
  "挂起/激活该 key 的全部流程定义(state=2 挂起,1 激活;挂起后不可发起)."
  [{:keys [engine query-fn]} id state user]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (when-not m
      (throw (ex-info "流程模型不存在" {:model_id id})))
    (let [key (:model_key m)
          suspend? (= "2" (str state))]
      (if suspend?
        (bpm/suspend-definition-by-key! engine key)
        (bpm/activate-definition-by-key! engine key))
      (query-fn :bpm/update-model-status {:model_id id
                                          :status (if suspend? "2" "1")
                                          :update_by (or user "")})
      {:model_key key :suspended? suspend?})))


(defn model-clean!
  "清理该流程:删除全部历史实例+部署(Flowable 级联),并清理业务实例/抄送记录."
  [{:keys [engine query-fn]} id]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (when-not m
      (throw (ex-info "流程模型不存在" {:model_id id})))
    (let [key (:model_key m)
          pids (mapv :process_instance_id (query-fn :bpm/instances-by-model-key {:model_key key}))
          deployments (bpm/delete-deployments-by-key! engine key)]
      (when (seq pids)
        (query-fn :bpm/delete-copies-by-pids {:pids pids}))
      (query-fn :bpm/delete-instances-by-model-key {:model_key key})
      (query-fn :bpm/clear-model-deployment {:model_id id})
      {:deleted-deployments deployments
       :deleted-instances (count pids)})))


(defn model-copy!
  "复制模型:名称+“副本”,key+_copy(冲突时追加),BPMN/表单/规则配置一并复制."
  [{:keys [query-fn]} id user]
  (let [m (query-fn :bpm/find-model-by-id {:model_id id})]
    (when-not m
      (throw (ex-info "流程模型不存在" {:model_id id})))
    (let [new-key (loop [k (str (:model_key m) "_copy")]
                    (if (query-fn :bpm/find-model-by-key {:model_key k})
                      (recur (str k "_copy"))
                      k))]
      (query-fn :bpm/insert-model
                {:model_key new-key
                 :model_name (str (:model_name m) "副本")
                 :category_id (or (:category_id m) 0) :version 1
                 :form_type (or (:form_type m) "0")
                 :form_id (or (:form_id m) 0)
                 :form_custom_create_path (or (:form_custom_create_path m) "")
                 :form_custom_view_path (or (:form_custom_view_path m) "")
                 :form_json (:form_json m) :fields_permission (:fields_permission m)
                 :bpmn_xml (:bpmn_xml m) :deployment_id nil :status "1"
                 :icon (or (:icon m) "")
                 :order_num (or (:order_num m) 0)
                 :start_user_ids (:start_user_ids m)
                 :start_dept_ids (:start_dept_ids m)
                 :manager_user_ids (:manager_user_ids m)
                 :process_id_rule (:process_id_rule m)
                 :auto_approval_type (or (:auto_approval_type m) "NONE")
                 :name_rule (:name_rule m) :summary_fields (:summary_fields m)
                 :print_template_enable (or (:print_template_enable m) "0")
                 :webhooks (:webhooks m)
                 :print_template_html (:print_template_html m)
                 :allow_cancel (or (:allow_cancel m) "1")
                 :allow_withdraw (or (:allow_withdraw m) "1")
                 :create_by (or user "") :remark (or (:remark m) "")})
      (let [copied (query-fn :bpm/find-model-by-key {:model_key new-key})]
        {:model_id (:model_id copied)
         :model_key new-key
         :model_name (str (:model_name m) "副本")}))))


(defn instance-print-data
  "打印数据:实例(含单号/名称/表单值)+ 任务审批记录(意见/签名图/时间)+ 打印模板."
  [{:keys [engine query-fn]} instance-id]
  (let [biz (query-fn :bpm/find-instance-by-id {:instance_id instance-id})]
    (when-not biz
      (throw (ex-info "流程实例不存在" {:instance-id instance-id})))
    (let [model (query-fn :bpm/find-model-by-id {:model_id (:model_id biz)})
          form (when-let [fid (:form_id model)]
                 (query-fn :bpm/find-form-by-id {:form_id fid}))
          inst (jf/parse-columns biz [:form_data_json])]
      {:instance inst
       :model {:model_id (:model_id model)
               :model_name (:model_name model)
               :print_template_enable (:print_template_enable model)
               :print_template_html (:print_template_html model)}
       :form {:schema (jf/parse-field (:form_json form))
              :values (:form_data_json inst)}
       :task-history (bpm/task-history-of engine (:process_instance_id biz))})))

;; 模型级 Webhook / 节点监听器 / 响应回写:实现搬到 com.ruoyi.domain.business.webhook,
;; 本命名空间保留 IG 初始化处引用的分发器符号.
(def fire-webhooks! hook/fire-webhooks!)

(def fire-node-listener! hook/fire-node-listener!)
