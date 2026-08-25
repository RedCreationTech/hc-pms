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

(defn- config->candidate-attrs
  "节点 config → Flowable 候选人属性（审批人设置落地为运行时可用）。"
  [config]
  (let [{:keys [candidate-strategy candidate-param]} config
        param (or candidate-param {})
        ids (fn [k] (str/join "," (or (get param k) [])))]
    (case candidate-strategy
      "USER" (str " flowable:candidateUsers=\"" (escape-xml (ids :user-ids)) "\"")
      "ROLE" (str " flowable:candidateGroups=\"" (escape-xml (ids :role-ids)) "\"")
      "POST" (str " flowable:candidateGroups=\"" (escape-xml (ids :post-ids)) "\"")
      "DEPT_MEMBER" (str " flowable:candidateGroups=\"" (escape-xml (ids :dept-ids)) "\"")
      "DEPT_LEADER" (str " flowable:candidateGroups=\"" (escape-xml (ids :dept-ids)) "\"")
      "MULTI_LEVEL_DEPT_LEADER" (str " flowable:candidateGroups=\"" (escape-xml (ids :dept-ids)) "\"")
      "")))

(defn- delay-iso
  "延迟器 config → ISO8601 时长（如 PT6H）。"
  [{:keys [time-duration time-unit]}]
  (when time-duration
    (let [suf (case (or time-unit "HOUR") "MINUTE" "M" "DAY" "D" "H")]
      (str "PT" time-duration suf))))

(defn tree->bpmn
  "流程节点树 → BPMN XML 字符串。"
  [root]
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
                     attrs (str " id=\"" el-id "\" name=\"" (escape-xml (or name id)) "\"")
                     cand (config->candidate-attrs config)
                     body (cond
                            (and (#{"USER_TASK_NODE" "COPY_TASK_NODE"} type) (seq config))
                            (str "<extensionElements><flowable:properties>"
                                 "<flowable:property name=\"nodeConfig\" value=\""
                                 (escape-xml (json/generate-string config))
                                 "\"/></flowable:properties></extensionElements>")
                            (and (= type "DELAY_TIMER_NODE") (delay-iso config))
                            (str "<timerEventDefinition><timeDuration xsi:type=\"tFormalExpression\">"
                                 (delay-iso config) "</timeDuration></timerEventDefinition>")
                            :else nil)]
                 (swap! parts conj
                        (if body
                          (str "<" tag attrs cand ">" body "</" tag ">")
                          (str "<" tag attrs cand "/>")))
                 (when parent-id
                   (swap! flows conj {:src parent-id :tgt el-id :cond? false}))
                 (if (and (str/includes? (or type "") "BRANCH") (seq condition-nodes))
                   (doseq [cn condition-nodes]
                     (let [cid (emit (:child-node cn) nil)]
                       (swap! flows conj {:src el-id :tgt cid :cond? true
                                          :expr (or (:expression cn) "${approved == true}")})))
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
         "<process id=\"p\" isExecutable=\"true\">"
         (apply str @parts)
         (apply str (map (fn [{:keys [src tgt cond? expr]}]
                           (if cond?
                             (str "<sequenceFlow id=\"" src "_" tgt "\" sourceRef=\"" src "\" targetRef=\"" tgt "\">"
                                  "<conditionExpression xsi:type=\"tFormalExpression\">"
                                  (escape-xml (or expr "${approved == true}"))
                                  "</conditionExpression></sequenceFlow>")
                             (str "<sequenceFlow id=\"" src "_" tgt "\" sourceRef=\"" src "\" targetRef=\"" tgt "\"/>")))
                         @flows))
         "</process></definitions>")))
