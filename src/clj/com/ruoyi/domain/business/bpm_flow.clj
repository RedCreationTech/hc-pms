(ns com.ruoyi.domain.business.bpm-flow
  "BPMN XML ↔ 流程节点树 双向转换。
   纯 HTML/CSS flex 编辑器的工作模型是 vben 风格的节点树
   (childNode 主链 + conditionNodes 分支)，保存时转回 BPMN XML 供 Flowable 部署。"
  (:require [clojure.string :as str]))

;; ── BPMN XML 解析 ────────────────────────────────────────────────────

(defn- parse-xml
  "解析 BPMN XML：返回 {:nodes {id {:type :name}} :flows [{:id :src :tgt :cond?}]}"
  [x]
  (let [nodes (atom {}) flows (atom [])]
    (doseq [[_ tag id name]
            (re-seq #"<(startEvent|endEvent|userTask|exclusiveGateway|parallelGateway|inclusiveGateway|intermediateCatchEvent|serviceTask|callActivity|subProcess)[^>]*?id=\"([^\"]+)\"(?:[^>]*?name=\"([^\"]*)\")?[^>]*/?>" x)]
      (swap! nodes assoc id {:type tag :name (or name "")}))
    (doseq [f (re-seq #"<sequenceFlow[^>]*(?:>[^<]*(?:<[^>]*conditionExpression[^>]*>.*?</conditionExpression>)?</sequenceFlow>|/>)" x)]
      (swap! flows conj {:id (nth (re-find #"id=\"([^\"]+)\"" f) 1)
                         :src (nth (re-find #"sourceRef=\"([^\"]+)\"" f) 1)
                         :tgt (nth (re-find #"targetRef=\"([^\"]+)\"" f) 1)
                         :cond? (boolean (re-find #"conditionExpression" f))}))
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
    (let [{:keys [type name]} (get nodes node-id)
          outs (filter #(= node-id (:src %)) flows)
          conds (filter :cond? outs)
          dflt (first (remove :cond? outs))
          m-type (type-map type)
          base {:id node-id :type m-type :name (if (str/blank? name) node-id name)}
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
                            :name (if (str/blank? (:name ct)) (str "条件" (inc i)) (:name ct))
                            :child-node (recurse (:tgt c))}))
                       conds))
                 :child-node child))
        dflt (assoc base :child-node (recurse (:tgt dflt)))
        :else base))))

(defn bpmn->tree
  "BPMN XML 字符串 → 流程节点树（clojure 数据）。"
  [xml]
  (let [{:keys [nodes flows]} (parse-xml xml)
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
    "TRIGGER_NODE" "serviceTask"
    "CONDITION_BRANCH_NODE" "exclusiveGateway"
    "PARALLEL_BRANCH_NODE" "parallelGateway"
    "INCLUSIVE_BRANCH_NODE" "inclusiveGateway"
    "DELAY_TIMER_NODE" "intermediateCatchEvent"
    "CHILD_PROCESS_NODE" "subProcess"
    "userTask"))

(defn- escape-xml [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn tree->bpmn
  "流程节点树 → BPMN XML 字符串。"
  [root]
  (let [parts (atom [])
        flows (atom [])
        gw-counter (atom 0)
        emit (fn emit [node parent-id]
               (let [{:keys [id type name child-node condition-nodes]} node
                     tag (tree-node-type->bpmn type)
                     is-end (= type "END_EVENT_NODE")
                     el-id (cond
                             (and is-end (str/starts-with? (or id "") "reject"))
                             (str "rejectEnd" (swap! gw-counter inc))
                             is-end "end"
                             (str/starts-with? (or id "") "cond-")
                             (str "gw" (swap! gw-counter inc))
                             :else id)
                     attrs (str " id=\"" el-id "\" name=\"" (escape-xml (or name id)) "\"")]
                 (swap! parts conj (str "<" tag attrs "/>"))
                 (when parent-id
                   (swap! flows conj {:src parent-id :tgt el-id :cond? false}))
                 (if (and (str/includes? (or type "") "BRANCH") (seq condition-nodes))
                   (doseq [[_i cn] (map-indexed vector condition-nodes)]
                     (let [cid (emit (:child-node cn) nil)]
                       (swap! flows conj {:src el-id :tgt cid :cond? true})))
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
         (apply str (map (fn [{:keys [src tgt cond?]}]
                           (if cond?
                             (str "<sequenceFlow id=\"" src "_" tgt "\" sourceRef=\"" src "\" targetRef=\"" tgt "\">"
                                  "<conditionExpression xsi:type=\"tFormalExpression\">${approved == true}</conditionExpression></sequenceFlow>")
                             (str "<sequenceFlow id=\"" src "_" tgt "\" sourceRef=\"" src "\" targetRef=\"" tgt "\"/>")))
                         @flows))
         "</process></definitions>")))
