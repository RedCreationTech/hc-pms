(ns com.ruoyi.domain.business.bpm-flow
  "BPMN XML ↔ 流程节点树 双向转换。
   纯 HTML/CSS flex 编辑器的工作模型是 vben 风格的节点树
   (childNode 主链 + conditionNodes 分支)，保存时转回 BPMN XML 供 Flowable 部署。
   节点配置(config，审批人/抄送/超时/条件/延迟等)以
   <flowable:property name=\"nodeConfig\" value=\"JSON\"/> 形式内嵌于元素，
   实现配置的保存/加载 round-trip。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ── BPMN XML 解析 ────────────────────────────────────────────────────

(def ^:private element-tags
  ["startEvent" "endEvent" "userTask" "exclusiveGateway" "parallelGateway"
   "inclusiveGateway" "intermediateCatchEvent" "serviceTask" "callActivity" "subProcess"])

(defn- attr
  "从字符串中提取第一个正则捕获组（无匹配返回 nil）。"
  [re s]
  (when s (second (re-find re s))))

(defn- unescape-xml [s]
  (-> s
      (str/replace "&quot;" "\"")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")))

(defn- parse-elements
  "解析 BPMN XML：
   {:nodes {id {:type :name :assignee :candidate-users :candidate-groups :config}}
    :flows [{:id :src :tgt :cond? :expr}]}
   P1：callActivity 的 multiInstanceLoopCharacteristics 回读为子流程多实例配置(mi-*)。"
  [x]
  (let [nodes (atom {}) flows (atom [])]
    (doseq [tag element-tags
            block (re-seq (re-pattern (str "(?s)<" tag "\\b[^>]*?(?:/>|>.*?</" tag ">)")) x)
            :let [id (attr #"id=\"([^\"]+)\"" block)]]
      (when id
        (let [body (or (attr (re-pattern (str "(?s)<" tag "\\b[^>]*?>(.*?)</" tag ">")) block) "")
              cfg (when-let [m (re-find #"name=\"nodeConfig\" value=\"([^\"]*)\"" body)]
                    (try (json/parse-string (unescape-xml (second m)) true) (catch Exception _ nil)))
              ;; 子流程多实例回读：miList_<id> collection + isSequential + completionCondition 比例
              mi (when (= "callActivity" tag)
                   (when-let [mb (re-find #"(?s)<multiInstanceLoopCharacteristics([^>]*)>(.*?)</multiInstanceLoopCharacteristics>" block)]
                     (let [attrs (second mb)
                           mi-body (nth mb 2)
                           seq? (= "true" (attr #"isSequential=\"([^\"]*)\"" attrs))
                           ratio (some-> (re-find #"nrOfCompletedInstances / nrOfInstances >= ([0-9.]+)" mi-body)
                                         second Double/parseDouble)]
                       {:mi-enable true
                        :mi-sequential seq?
                        :mi-ratio (when ratio (int (Math/round (* 100 ratio))))})))
              cfg (merge cfg mi)
              el (cond-> {:type tag :name (or (attr #"name=\"([^\"]*)\"" block) "")}
                   (attr #"flowable:assignee=\"([^\"]*)\"" block)
                   (assoc :assignee (attr #"flowable:assignee=\"([^\"]*)\"" block))
                   (attr #"flowable:candidateUsers=\"([^\"]*)\"" block)
                   (assoc :candidate-users (str/split (attr #"flowable:candidateUsers=\"([^\"]*)\"" block) #",\s*"))
                   (attr #"flowable:candidateGroups=\"([^\"]*)\"" block)
                   (assoc :candidate-groups (str/split (attr #"flowable:candidateGroups=\"([^\"]*)\"" block) #",\s*"))
                   cfg (assoc :config cfg))]
          (swap! nodes assoc id el))))
    (doseq [b (concat (re-seq #"<sequenceFlow[^>]*?/>" x)
                      (re-seq #"(?s)<sequenceFlow\b[^>]*?(?<!/)>(.*?)</sequenceFlow>" x))]
      (let [el (if (string? b) b (first b))
            body (if (string? b) "" (second b))
            id (attr #"id=\"([^\"]+)\"" el)
            expr (some-> (attr #"(?s)conditionExpression[^>]*>(.*?)</conditionExpression>" body) unescape-xml)]
        (when (and id (attr #"sourceRef=\"([^\"]+)\"" el))
          (swap! flows conj {:id id
                             :src (attr #"sourceRef=\"([^\"]+)\"" el)
                             :tgt (attr #"targetRef=\"([^\"]+)\"" el)
                             :cond? (boolean expr)
                             :expr expr}))))
    {:nodes @nodes :flows @flows}))

(defn- type-map
  "BPMN 元素类型 → 树节点类型（vben 风格）。"
  [tag]
  (case tag
    "startEvent" "START_USER_NODE"
    "endEvent" "END_EVENT_NODE"
    "userTask" "USER_TASK_NODE"
    "serviceTask" "TRIGGER_NODE"
    "exclusiveGateway" "CONDITION_BRANCH_NODE"
    "parallelGateway" "PARALLEL_BRANCH_NODE"
    "inclusiveGateway" "INCLUSIVE_BRANCH_NODE"
    "intermediateCatchEvent" "DELAY_TIMER_NODE"
    "callActivity" "CHILD_PROCESS_NODE"
    "subProcess" "CHILD_PROCESS_NODE"
    "USER_TASK_NODE"))

(defn- build-tree
  "BPMN 图 → 树。条件分支的默认出线目标若已被条件覆盖则不重复展开。"
  [nodes flows node-id seen]
  (when (and node-id (not (contains? seen node-id)))
    (let [{:keys [type name config]} (get nodes node-id)
          outs (filter #(= node-id (:src %)) flows)
          conds (filter :cond? outs)
          dflt (first (remove :cond? outs))
          m-type (if (= "COPY" (:node-kind config))
                     "COPY_TASK_NODE"
                     (if (= "TRANSACTOR" (:nodeType config))
                       ;; 办理人节点（生成时 nodeConfig 标 nodeType=TRANSACTOR）回读还原
                       "TRANSACTOR_NODE"
                       (if (and (= "exclusiveGateway" type) (seq (:groups config)))
                         ;; 路由分支节点展开生成的排他网关（带 :groups 配置）回读时还原为路由节点
                         "ROUTER_BRANCH_NODE"
                         (type-map type))))
          base (cond-> {:id node-id :type m-type :name (if (str/blank? name) node-id name)}
                 config (assoc :config config))
          recurse (fn [id] (build-tree nodes flows id (conj seen node-id)))]
      (cond
        (and (str/includes? m-type "BRANCH") (seq conds))
        (let [cond-targets (set (map :tgt conds))
              child (when (and dflt (not (contains? cond-targets (:tgt dflt))))
                      (recurse (:tgt dflt)))]
          (assoc base
                 :condition-nodes
                 (vec (keep-indexed
                       (fn [i c]
                         (when-let [ct (get nodes (:tgt c))]
                           {:id (str "cond-" node-id "-" i)
                            :name (str "条件" (inc i))
                            :expression (:expr c)
                            :child-node (recurse (:tgt c))}))
                       conds))
                 :child-node child))
        dflt (assoc base :child-node (recurse (:tgt dflt)))
        :else base))))

(defn bpmn->tree
  "BPMN XML 字符串 → 流程节点树（clojure 数据）。"
  [xml]
  (let [{:keys [nodes flows]} (parse-elements xml)
        start-id (ffirst (filter (fn [[_ n]] (= "startEvent" (:type n))) nodes))]
    (when start-id
      (build-tree nodes flows start-id #{}))))

;; ── 树 → BPMN XML 生成 ──────────────────────────────────────────────

(defn- tree-node-type->bpmn
  "树节点类型 → BPMN 元素标签。"
  [t]
  (case t
    "START_USER_NODE" "startEvent"
    "END_EVENT_NODE" "endEvent"
    "USER_TASK_NODE" "userTask"
    "TRANSACTOR_NODE" "userTask"
    "COPY_TASK_NODE" "userTask"
    "TRIGGER_NODE" "serviceTask"
    "CONDITION_BRANCH_NODE" "exclusiveGateway"
    "PARALLEL_BRANCH_NODE" "parallelGateway"
    "INCLUSIVE_BRANCH_NODE" "inclusiveGateway"
    "DELAY_TIMER_NODE" "intermediateCatchEvent"
    "CHILD_PROCESS_NODE" "callActivity"
    "ROUTER_BRANCH_NODE" "exclusiveGateway"
    "userTask"))

(defn- rules->expression
  "条件规则列表 → Flowable 表达式字符串（${days > 3 && amount < 100}）。
   后端独立实现（与设计器 cljs 同名函数保持一致），供路由分支节点展开使用。"
  [rules]
  (when (seq rules)
    (let [parts (keep (fn [{:keys [left-side op-code right-side]}]
                        (when (and (seq (str left-side)) (seq (str op-code)))
                          (str left-side " " op-code " " (or right-side ""))))
                      rules)]
      (when (seq parts)
        (str "${" (str/join " && " parts) "}")))))

(defn- listener-enabled?
  "nodeConfig.listeners 中某事件是否启用（兼容 keyword / 字符串 key）。"
  [listeners event]
  (boolean (or (get-in listeners [(keyword event) :enable])
               (get-in listeners [(keyword event) "enable"])
               (get-in listeners [event :enable])
               (get-in listeners [event "enable"]))))

(defn- escape-xml [s]
  (-> (or s "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- group-ids
  "把 id 列表转成 Flowable identity group 格式：prefix:id1,prefix:id2。"
  [prefix ids]
  (str/join "," (map #(str prefix ":" %) ids)))

(defn- config->candidate-attrs
  "节点 config → Flowable 候选人属性（审批人设置落地为运行时可用）。
   候选策略映射：
   USER                   → candidateUsers (逗号分隔用户名)
   ROLE/DEPT_MEMBER/POST  → candidateGroups (role:id / dept:id / post:id)
   DEPT_LEADER/MULTI...   → candidateGroups (dept-leader:id)
   USER_GROUP             → candidateUsers (分组 user_ids 展开为用户名，groups 为 {group-id [user-id]})
   抄送节点(copy-user-ids/copy-role-ids) → candidateUsers / candidateGroups(role:)
   INITIATOR_SELF/FORM_USER/FORM_DEPT_LEADER/EXPRESSION 等运行时策略
                          → 不生成静态候选，由 TaskListener create 事件解析(nodeConfig 保存配置)"
  [config users groups]
  (let [{:keys [candidate-strategy candidate-param copy-user-ids copy-role-ids]} config
        param (or candidate-param {})
        ids (fn [k] (or (get param k) []))
        unames (fn [k] (str/join "," (keep #(get users (str %)) (ids k))))
        group-unames (fn []
                       (str/join "," (keep users
                                           (distinct (mapcat #(get groups (str %) [])
                                                             (map str (:user-group-ids param)))))))]
    (cond
      (seq copy-user-ids)
      (str " flowable:candidateUsers=\"" (escape-xml (str/join "," (keep #(get users (str %)) copy-user-ids))) "\"")
      (seq copy-role-ids)
      (str " flowable:candidateGroups=\"" (escape-xml (group-ids "role" copy-role-ids)) "\"")
      :else
      (case candidate-strategy
        "USER" (str " flowable:candidateUsers=\"" (escape-xml (unames :user-ids)) "\"")
        "USER_GROUP" (str " flowable:candidateUsers=\"" (escape-xml (group-unames)) "\"")
        "ROLE" (str " flowable:candidateGroups=\"" (escape-xml (group-ids "role" (ids :role-ids))) "\"")
        "DEPT_MEMBER" (str " flowable:candidateGroups=\"" (escape-xml (group-ids "dept" (ids :dept-ids))) "\"")
        "DEPT_LEADER" (str " flowable:candidateGroups=\"" (escape-xml (group-ids "dept-leader" (ids :dept-ids))) "\"")
        "MULTI_LEVEL_DEPT_LEADER" (str " flowable:candidateGroups=\"" (escape-xml (group-ids "dept-leader" (ids :dept-ids))) "\"")
        "POST" (str " flowable:candidateGroups=\"" (escape-xml (group-ids "post" (ids :post-ids))) "\"")
        ""))))
(defn- delay-iso
  "延迟/超时 config → ISO8601 时长（如 PT6H、PT10S）。固定日期时间模式(:time-date)不适用。"
  [{:keys [time-duration time-unit]}]
  (when time-duration
    (let [suf (case (or time-unit "HOUR")
                "SECOND" "S" "MINUTE" "M" "DAY" "D" "H")]
      (str "PT" time-duration suf))))

(defn- delay-timer-body
  "P1 延迟器节点 body：固定日期时间模式(time-date)生成 timeDate，否则 timeDuration。
   附带 nodeConfig 属性保存模式配置，保证设计器回读还原。"
  [config]
  (let [cfg (select-keys config [:time-duration :time-unit :timer-type :time-date])]
    (str "<extensionElements>"
         "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
         (escape-xml (json/generate-string cfg))
         "\"/></flowable:properties></extensionElements>"
         "<timerEventDefinition>"
         (if (seq (str (:time-date config)))
           (str "<timeDate xsi:type=\"tFormalExpression\">"
                (escape-xml (str (:time-date config))) "</timeDate>")
           (str "<timeDuration xsi:type=\"tFormalExpression\">"
                (delay-iso config) "</timeDuration>"))
         "</timerEventDefinition>")))

(defn- child-multi-instance-el
  "P1 子流程多实例元素：mi-enable 时在 callActivity 内生成 multiInstanceLoopCharacteristics。
   collection = ${miList_<id>}（发起时按 mi-source 注入）；
   完成条件：mi-ratio ∈ [10,100) 时按比例通过，否则全部完成。"
  [el-id config]
  (when (:mi-enable config)
    (let [ratio (some-> (:mi-ratio config) long)
          ratio-expr (when (and ratio (>= ratio 10) (< ratio 100))
                       (format "${nrOfCompletedInstances / nrOfInstances >= %.2f}" (double (/ ratio 100.0))))]
      (str "<multiInstanceLoopCharacteristics isSequential=\"" (boolean (:mi-sequential config)) "\""
           " flowable:collection=\"${miList_" el-id "}\""
           " flowable:elementVariable=\"miItem\">"
           "<completionCondition>"
           (or ratio-expr "${nrOfCompletedInstances >= nrOfInstances}")
           "</completionCondition></multiInstanceLoopCharacteristics>"))))

(defn- multi-completion-condition
  "多实例审批完成条件：ANY 或签(任一完成)/ALL 会签(全部)/RATIO 按比例。"
  [method ratio]
  (case method
    "ANY" "${nrOfCompletedInstances >= 1}"
    "RATIO" (str "${nrOfCompletedInstances / nrOfInstances >= " (or ratio 0.6) "}")
    "${nrOfCompletedInstances >= nrOfInstances}"))

(defn- multi-instance-el
  "多实例审批元素：collection 按节点 id 命名（发起时注入 approverList_<id>）。
   RANDOM 随机一人不是多实例，由 TaskListener create 时指定 assignee，不生成该元素。"
  [el-id config]
  (when (and (= "USER" (:approve-type config))
             (not= "SEQUENTIAL" (or (:approve-method config) "SEQUENTIAL"))
             (not= "RANDOM" (:approve-method config)))
    (str "<multiInstanceLoopCharacteristics isSequential=\"false\""
         " flowable:collection=\"${approverList_" el-id "}\""
         " flowable:elementVariable=\"approver\">"
         "<completionCondition>"
         (multi-completion-condition (:approve-method config) (:approve-ratio config))
         "</completionCondition></multiInstanceLoopCharacteristics>")))

(defn- timeout-boundary-el
  "节点超时配置 → 非中断边界定时事件（触发时由 bpmTimeoutHandler 执行 REMINDER/AUTO_PASS/AUTO_REJECT）。
   超时动作存边界事件自己的 nodeConfig 属性，TimeoutHandler 直接读取。"
  [el-id config]
  (let [timeout (:timeout-handler config)]
    (when (and (:enable timeout)
               (pos? (or (:time-duration timeout) 0))
               (contains? #{"REMINDER" "AUTO_PASS" "AUTO_REJECT"} (:type timeout)))
      (str "<boundaryEvent id=\"timeout_" el-id "\" attachedToRef=\"" el-id "\" cancelActivity=\"false\">"
           "<extensionElements>"
           "<flowable:executionListener event=\"start\" delegateExpression=\"${bpmTimeoutHandler}\"/>"
           "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
           (escape-xml (json/generate-string {:timeout {:action (:type timeout)}}))
           "\"/></flowable:properties>"
           "</extensionElements>"
           "<timerEventDefinition><timeDuration xsi:type=\"tFormalExpression\">"
           (delay-iso timeout) "</timeDuration></timerEventDefinition>"
           "</boundaryEvent>"))))

(defn tree->bpmn
  "流程节点树 → BPMN XML 字符串。
   model-key 作为 BPMN process id，保证部署后流程定义 key 与模型 key 一致。
   users 是 {user-id user-name} 映射，用于 USER/USER_GROUP/抄送策略生成 candidateUsers 用户名。
   groups 是 {group-id [user-id ...]} 映射（可选），用于 USER_GROUP 策略展开。"
  ([root model-key] (tree->bpmn root model-key nil))
  ([root model-key users] (tree->bpmn root model-key users nil))
  ([root model-key users groups]
  (let [parts (atom [])
        flows (atom [])
        gw-counter (atom 0)
        emit (fn emit [node parent-id]
               (let [{:keys [id type name child-node condition-nodes config]} node
                     ;; 路由分支节点：展开为排他网关（条件出线指向树中已有节点，默认走 child-node）
                     router? (= type "ROUTER_BRANCH_NODE")
                     tag (if router? "exclusiveGateway" (tree-node-type->bpmn type))
                     is-end (= type "END_EVENT_NODE")
                     ;; 结束节点保留原 id（设计器 id 天然唯一，路由分支可指向指定 end）；
                     ;; 仅驳回类结束节点(reject 前缀)重命名避免冲突，cond- 前缀(旧条件分支)转网关
                     el-id (cond
                             (and is-end (str/starts-with? (or id "") "reject"))
                             (str "rejectEnd" (swap! gw-counter inc))
                             (str/starts-with? (or id "") "cond-")
                             (str "gw" (swap! gw-counter inc))
                             :else id)
                     branch? (and (str/includes? (or type "") "BRANCH") (seq condition-nodes))
                     cand (config->candidate-attrs config users groups)
                     ;; 先递归子节点拿到出线目标 id（网关 default 属性需要默认线 id）
                     cond-flows (when branch?
                                  (mapv (fn [cn]
                                          (let [cid (emit (:child-node cn) nil)]
                                            {:src el-id :tgt cid :cond? true
                                             :expr (or (:expression cn) "${approved == true}")}))
                                        condition-nodes))
                     default-cid (when (and branch? child-node)
                                   (emit child-node nil))
                     ;; 审批/办理人节点支持多实例与跳过表达式（办理人配置同构，仅 nodeType 不同）
                     user-task-like? (contains? #{"USER_TASK_NODE" "TRANSACTOR_NODE"} type)
                     multi-el (when user-task-like? (multi-instance-el el-id config))
                     multi-assignee (when multi-el
                                        " flowable:assignee=\"${approver}\"")
                     skip-expr (when (and user-task-like? (seq (get-in config [:skip-expression])))
                                 (str " flowable:skipExpression=\"" (escape-xml (get-in config [:skip-expression])) "\""))
                     attrs (str " id=\"" el-id "\" name=\"" (escape-xml (or name id)) "\""
                                multi-assignee skip-expr
                                ;; 触发器节点：统一 JavaDelegate 入口
                                (when (= type "TRIGGER_NODE")
                                  " flowable:delegateExpression=\"${bpmTriggerDelegate}\"")
                                ;; 子流程节点：callActivity 指向已部署的子流程定义 key
                                (when (and (= type "CHILD_PROCESS_NODE") (seq (:child-process-key config)))
                                  (str " flowable:calledElement=\""
                                       (escape-xml (str (:child-process-key config))) "\""))
                                (when default-cid
                                  (str " default=\"" el-id "_" default-cid "\"")))
                     ;; 所有带配置的人工节点都挂 create 监听器：
                     ;; 候选解析/为空策略/随机审批统一在 TaskListener 处理；
                     ;; 配置了 nodeConfig.listeners 的节点额外挂 assignment/complete 监听器
                     listener-el (when (and (#{"USER_TASK_NODE" "TRANSACTOR_NODE" "COPY_TASK_NODE"} type)
                                            (seq config))
                                   (str "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>"
                                        (when (listener-enabled? (:listeners config) "assign")
                                          "<flowable:taskListener event=\"assignment\" delegateExpression=\"${bpmTaskListener}\"/>")
                                        (when (listener-enabled? (:listeners config) "complete")
                                          "<flowable:taskListener event=\"complete\" delegateExpression=\"${bpmTaskListener}\"/>")))
                     ;; 抄送节点标 nodeType=COPY_TASK、办理人节点标 nodeType=TRANSACTOR，
                     ;; TaskListener create 时按标记分发（抄送自动完成/办理人默认按钮）
                     out-config (cond-> config
                                  (= "COPY_TASK_NODE" type) (assoc :nodeType "COPY_TASK")
                                  (= "TRANSACTOR_NODE" type) (assoc :nodeType "TRANSACTOR"))
                     timeout-el (when user-task-like? (timeout-boundary-el el-id config))
                     ;; 触发器节点：nodeConfig 供 bpmTriggerDelegate 按 trigger-type 分发
                     trigger-body (when (and (= type "TRIGGER_NODE") (seq config))
                                    (str "<extensionElements>"
                                         "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
                                         (escape-xml (json/generate-string config))
                                         "\"/></flowable:properties></extensionElements>"))
                     ;; 子流程节点：主→子 / 子→主 变量映射（发起人策略=START_USER 时自动透传 startUserId）
                     child-body (when (= type "CHILD_PROCESS_NODE")
                                  (let [cfg (or config {})
                                        in-mappings (vec (:in-mappings cfg))
                                        in-mappings (if (and (= "START_USER" (:initiator-strategy cfg))
                                                             (not (some #(= "startUserId" (str (:source %))) in-mappings)))
                                                      (conj in-mappings {:source "startUserId" :target "startUserId"})
                                                      in-mappings)
                                        out-mappings (vec (:out-mappings cfg))]
                                    (str "<extensionElements>"
                                         (apply str
                                                (map (fn [{:keys [source target]}]
                                                       (when (and (seq (str source)) (seq (str target)))
                                                         (str "<flowable:in source=\"" (escape-xml (str source))
                                                              "\" target=\"" (escape-xml (str target)) "\"/>")))
                                                     in-mappings))
                                         (apply str
                                                (map (fn [{:keys [source target]}]
                                                       (when (and (seq (str source)) (seq (str target)))
                                                         (str "<flowable:out source=\"" (escape-xml (str source))
                                                              "\" target=\"" (escape-xml (str target)) "\"/>")))
                                                     out-mappings))
                                         "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
                                         (escape-xml (json/generate-string (assoc cfg :in-mappings in-mappings)))
                                         "\"/></flowable:properties></extensionElements>"
                                        (child-multi-instance-el el-id cfg))))
                     ;; 路由分支节点：:groups 配置写入网关 nodeConfig（保存后可回读还原）
                     router-body (when (and router? (seq config))
                                   (str "<extensionElements>"
                                        "<flowable:properties><flowable:property name=\"nodeConfig\" value=\""
                                        (escape-xml (json/generate-string (select-keys config [:groups])))
                                        "\"/></flowable:properties></extensionElements>"))
                     body (cond
                            router-body
                            router-body
                            trigger-body
                            trigger-body
                            child-body
                            child-body
                            (and (#{"USER_TASK_NODE" "TRANSACTOR_NODE" "COPY_TASK_NODE"} type) (seq out-config))
                            (str "<extensionElements>"
                                 (when listener-el listener-el)
                                 "<flowable:properties>"
                                 "<flowable:property name=\"nodeConfig\" value=\""
                                 (escape-xml (json/generate-string out-config))
                                 "\"/></flowable:properties></extensionElements>"
                                 (when multi-el multi-el))
                            (= type "DELAY_TIMER_NODE")
                            (delay-timer-body config)
                            :else nil)]
                 (swap! parts conj
                        (if body
                          (str "<" tag attrs cand ">" body "</" tag ">")
                          (str "<" tag attrs cand "/>")))
                 (when timeout-el (swap! parts conj timeout-el))
                 (when parent-id
                   (swap! flows conj {:src parent-id :tgt el-id :cond? false}))
                 (if branch?
                   (do
                     (doseq [f cond-flows] (swap! flows conj f))
                     (when default-cid
                       (swap! flows conj {:src el-id :tgt default-cid :cond? false})))
                   (do
                     ;; 路由分支：每组 目标节点+条件规则 → 一条条件出线（目标是树中已有节点，
                     ;; 不再重复生成元素）；默认走 child-node 的无线条件出线
                     (when router?
                       (doseq [{:keys [target-node-id rules]} (:groups config)]
                         (when (seq (str target-node-id))
                           (swap! flows conj {:src el-id :tgt (str target-node-id)
                                              :cond? true
                                              :expr (or (rules->expression rules)
                                                        "${approved == true}")}))))
                     (when child-node
                       (emit child-node el-id))))
                 el-id))]
    (emit root nil)
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
         "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
         " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
         " xmlns:flowable=\"http://flowable.org/bpmn\""
         " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
         " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
         " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
         " id=\"def\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
         "<process id=\"" (escape-xml (or model-key "p")) "\" isExecutable=\"true\">"
         (apply str @parts)
         (apply str (map (fn [{:keys [src tgt cond? expr]}]
                           (if cond?
                             (str "<sequenceFlow id=\"" src "_" tgt "\" sourceRef=\"" src "\" targetRef=\"" tgt "\">"
                                  "<conditionExpression xsi:type=\"tFormalExpression\">"
                                  (escape-xml (or expr "${approved == true}"))
                                  "</conditionExpression></sequenceFlow>")
                             (str "<sequenceFlow id=\"" src "_" tgt "\" sourceRef=\"" src "\" targetRef=\"" tgt "\"/>")))
                         @flows))
         "</process></definitions>"))))
