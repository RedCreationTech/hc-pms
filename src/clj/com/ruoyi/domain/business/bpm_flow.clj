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
    :flows [{:id :src :tgt :cond? :expr}]}"
  [x]
  (let [nodes (atom {}) flows (atom [])]
    (doseq [tag element-tags
            block (re-seq (re-pattern (str "(?s)<" tag "\\b[^>]*?(?:/>|>.*?</" tag ">)")) x)
            :let [id (attr #"id=\"([^\"]+)\"" block)]]
      (when id
        (let [body (or (attr (re-pattern (str "(?s)<" tag "\\b[^>]*?>(.*?)</" tag ">")) block) "")
              cfg (when-let [m (re-find #"name=\"nodeConfig\" value=\"([^\"]*)\"" body)]
                    (try (json/parse-string (unescape-xml (second m)) true) (catch Exception _ nil)))
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
          m-type (if (= "COPY" (:node-kind config)) "COPY_TASK_NODE" (type-map type))
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
    "COPY_TASK_NODE" "userTask"
    "TRIGGER_NODE" "serviceTask"
    "CONDITION_BRANCH_NODE" "exclusiveGateway"
    "PARALLEL_BRANCH_NODE" "parallelGateway"
    "INCLUSIVE_BRANCH_NODE" "inclusiveGateway"
    "DELAY_TIMER_NODE" "intermediateCatchEvent"
    "CHILD_PROCESS_NODE" "subProcess"
    "userTask"))

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
  "延迟/超时 config → ISO8601 时长（如 PT6H、PT10S）。"
  [{:keys [time-duration time-unit]}]
  (when time-duration
    (let [suf (case (or time-unit "HOUR")
                "SECOND" "S" "MINUTE" "M" "DAY" "D" "H")]
      (str "PT" time-duration suf))))

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
                     tag (tree-node-type->bpmn type)
                     is-end (= type "END_EVENT_NODE")
                     el-id (cond
                             (and is-end (str/starts-with? (or id "") "reject"))
                             (str "rejectEnd" (swap! gw-counter inc))
                             is-end "end"
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
                     multi-el (when (= type "USER_TASK_NODE") (multi-instance-el el-id config))
                     multi-assignee (when multi-el
                                        " flowable:assignee=\"${approver}\"")
                     skip-expr (when (and (= type "USER_TASK_NODE") (seq (get-in config [:skip-expression])))
                                 (str " flowable:skipExpression=\"" (escape-xml (get-in config [:skip-expression])) "\""))
                     attrs (str " id=\"" el-id "\" name=\"" (escape-xml (or name id)) "\""
                                multi-assignee skip-expr
                                (when default-cid
                                  (str " default=\"" el-id "_" default-cid "\"")))
                     ;; 所有带配置的人工节点都挂 create 监听器：
                     ;; 候选解析/为空策略/随机审批统一在 TaskListener 处理
                     listener-el (when (and (#{"USER_TASK_NODE" "COPY_TASK_NODE"} type)
                                            (seq config))
                                   "<flowable:taskListener event=\"create\" delegateExpression=\"${bpmTaskListener}\"/>")
                     ;; 抄送节点在 nodeConfig 标 nodeType=COPY_TASK，TaskListener create 时自动抄送并完成
                     out-config (cond-> config
                                  (= "COPY_TASK_NODE" type) (assoc :nodeType "COPY_TASK"))
                     timeout-el (when (= type "USER_TASK_NODE") (timeout-boundary-el el-id config))
                     body (cond
                            (and (#{"USER_TASK_NODE" "COPY_TASK_NODE"} type) (seq out-config))
                            (str "<extensionElements>"
                                 (when listener-el listener-el)
                                 "<flowable:properties>"
                                 "<flowable:property name=\"nodeConfig\" value=\""
                                 (escape-xml (json/generate-string out-config))
                                 "\"/></flowable:properties></extensionElements>"
                                 (when multi-el multi-el))
                            (and (= type "DELAY_TIMER_NODE") (delay-iso config))
                            (str "<timerEventDefinition><timeDuration xsi:type=\"tFormalExpression\">"
                                 (delay-iso config) "</timeDuration></timerEventDefinition>")
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
                   (when child-node
                     (emit child-node el-id)))
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
