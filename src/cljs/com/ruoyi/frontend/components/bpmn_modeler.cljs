(ns com.ruoyi.frontend.components.bpmn-modeler
  "bpmn-js 流程建模器封装（Reagent hook 组件）。
   通过 index.html 引入 UMD 构建，使用全局 BpmnJS，避免 shadow-cljs 打包 bpmn-js。
   容器 div 用命令式创建并挂到 host 下，规避 React 对 ref 容器 reconciliation 造成 bpmn-js 内部错误。
   支持选中节点(on-select)回调，供外部属性面板编辑。
   注入中文翻译模块 + flowable moddle 扩展（对齐 vben bpmn-process-designer）。"
  (:require
   [clojure.string :as str]
   [reagent.hooks :as hooks]))

(defn- empty-bpmn
  "空白 BPMN 定义（含 start + 结束事件，并带 BPMNDI 图元，bpmn-js 才能渲染）。"
  []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
       " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
       " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
       " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
       " id=\"Definitions_1\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<bpmn:process id=\"Process_1\" isExecutable=\"true\">"
       "<bpmn:startEvent id=\"StartEvent_1\"/>"
       "<bpmn:endEvent id=\"EndEvent_1\"/>"
       "</bpmn:process>"
       "<bpmndi:BPMNDiagram id=\"BPMNDiagram_1\"><bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"Process_1\">"
       "<bpmndi:BPMNShape id=\"s_di\" bpmnElement=\"StartEvent_1\"><dc:Bounds x=\"180\" y=\"90\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>"
       "<bpmndi:BPMNShape id=\"e_di\" bpmnElement=\"EndEvent_1\"><dc:Bounds x=\"380\" y=\"90\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>"
       "</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>"
       "</bpmn:definitions>"))

(defn- bpmn-js
  "取全局 BpmnJS 构造器。"
  []
  (or (js* "globalThis.BpmnJS")
      (aget js/window "BpmnJS")))

;; 中文翻译（对齐 vben customTranslate）
(def ^:private zh-map
  (into {} (map (fn [[k v]] [(clojure.string/lower-case k) v]))
        {
  "Append EndEvent" "追加结束事件"
  "Append Gateway" "追加网关"
  "Append Task" "追加任务"
  "Append Intermediate/Boundary Event" "追加中间抛出事件/边界事件"
  "Activate the global connect tool" "激活全局连接工具"
  "Append {type}" "添加 {type}"
  "Add Lane above" "在上面添加道"
  "Divide into two Lanes" "分割成两个道"
  "Divide into three Lanes" "分割成三个道"
  "Add Lane below" "在下面添加道"
  "Append compensation activity" "追加补偿活动"
  "Change type" "修改类型"
  "Connect using Association" "使用关联连接"
  "Connect using Sequence/MessageFlow or Association" "使用顺序/消息流或者关联连接"
  "Connect using DataInputAssociation" "使用数据输入关联连接"
  "Activate the hand tool" "激活抓手工具"
  "Activate the lasso tool" "激活套索工具"
  "Activate the create/remove space tool" "激活创建/删除空间工具"
  "Create expanded SubProcess" "创建扩展子过程"
  "Create IntermediateThrowEvent/BoundaryEvent" "创建中间抛出事件/边界事件"
  "Create Pool/Participant" "创建池/参与者"
  "Participant Multiplicity" "参与者多重性"
  "Empty pool/participant (removes content)" "清空池/参与者（移除内容）"
  "Empty pool/participant" "收缩池/参与者"
  "Expanded pool/participant" "展开池/参与者"
  "Parallel Multi-Instance" "并行多重事件"
  "Sequential Multi-Instance" "时序多重事件"
  "Data object reference" "数据对象引用 "
  "Data store reference" "数据存储引用 "
  "Ad-hoc" "即席"
  "Create {type}" "创建 {type}"
  "Send Task" "发送任务"
  "Receive Task" "接收任务"
  "User Task" "用户任务"
  "Manual Task" "手工任务"
  "Business Rule Task" "业务规则任务"
  "Service Task" "服务任务"
  "Script Task" "脚本任务"
  "Call Activity" "调用活动"
  "Sub-Process (collapsed)" "子流程（折叠的）"
  "Sub-Process (expanded)" "子流程（展开的）"
  "Ad-hoc sub-process" "即席子流程"
  "Ad-hoc sub-process (collapsed)" "即席子流程（折叠的）"
  "Ad-hoc sub-process (expanded)" "即席子流程（展开的）"
  "Start Event" "开始事件"
  "Intermediate Throw Event" "中间事件"
  "End Event" "结束事件"
  "Create StartEvent" "创建开始事件"
  "Create EndEvent" "创建结束事件"
  "Create Task" "创建任务"
  "Create User Task" "创建用户任务"
  "Create Call Activity" "创建调用活动"
  "Create Service Task" "创建服务任务"
  "Create Gateway" "创建网关"
  "Create DataObjectReference" "创建数据对象"
  "Create DataStoreReference" "创建数据存储"
  "Create Group" "创建分组"
  "Create Intermediate/Boundary Event" "创建中间/边界事件"
  "Message Start Event" "消息开始事件"
  "Timer Start Event" "定时开始事件"
  "Conditional Start Event" "条件开始事件"
  "Signal Start Event" "信号开始事件"
  "Error Start Event" "错误开始事件"
  "Escalation Start Event" "升级开始事件"
  "Compensation Start Event" "补偿开始事件"
  "Message Start Event (non-interrupting)" "消息开始事件（非中断）"
  "Timer Start Event (non-interrupting)" "定时开始事件（非中断）"
  "Conditional Start Event (non-interrupting)" "条件开始事件（非中断）"
  "Signal Start Event (non-interrupting)" "信号开始事件（非中断）"
  "Escalation Start Event (non-interrupting)" "升级开始事件（非中断）"
  "Message Intermediate Catch Event" "消息中间捕获事件"
  "Message Intermediate Throw Event" "消息中间抛出事件"
  "Timer Intermediate Catch Event" "定时中间捕获事件"
  "Escalation Intermediate Throw Event" "升级中间抛出事件"
  "Conditional Intermediate Catch Event" "条件中间捕获事件"
  "Link Intermediate Catch Event" "链接中间捕获事件"
  "Link Intermediate Throw Event" "链接中间抛出事件"
  "Compensation Intermediate Throw Event" "补偿中间抛出事件"
  "Signal Intermediate Catch Event" "信号中间捕获事件"
  "Signal Intermediate Throw Event" "信号中间抛出事件"
  "Message End Event" "消息结束事件"
  "Escalation End Event" "定时结束事件"
  "Error End Event" "错误结束事件"
  "Cancel End Event" "取消结束事件"
  "Compensation End Event" "补偿结束事件"
  "Signal End Event" "信号结束事件"
  "Terminate End Event" "终止结束事件"
  "Message Boundary Event" "消息边界事件"
  "Message Boundary Event (non-interrupting)" "消息边界事件（非中断）"
  "Timer Boundary Event" "定时边界事件"
  "Timer Boundary Event (non-interrupting)" "定时边界事件（非中断）"
  "Escalation Boundary Event" "升级边界事件"
  "Escalation Boundary Event (non-interrupting)" "升级边界事件（非中断）"
  "Conditional Boundary Event" "条件边界事件"
  "Conditional Boundary Event (non-interrupting)" "条件边界事件（非中断）"
  "Error Boundary Event" "错误边界事件"
  "Cancel Boundary Event" "取消边界事件"
  "Signal Boundary Event" "信号边界事件"
  "Signal Boundary Event (non-interrupting)" "信号边界事件（非中断）"
  "Compensation Boundary Event" "补偿边界事件"
  "Exclusive Gateway" "互斥网关"
  "Parallel Gateway" "并行网关"
  "Inclusive Gateway" "相容网关"
  "Complex Gateway" "复杂网关"
  "Event-based Gateway" "事件网关"
  "sub-process" "子流程"
  "Event sub-process" "事件子流程"
  "Collapsed Pool" "折叠池"
  "Expanded Pool" "展开池"
  "no parent for {element} in {parent}" "在{parent}里，{element}没有父类"
  "no shape type specified" "没有指定的形状类型"
  "flow elements must be children of pools/participants" "流元素必须是池/参与者的子类"
  "out of bounds release" "out of bounds release"
  "more than {count} child lanes" "子道大于{count} "
  "element required" "元素不能为空"
  "diagram not part of bpmn:Definitions" "流程图不符合bpmn规范"
  "no diagram to display" "没有可展示的流程图"
  "no process or collaboration to display" "没有可展示的流程/协作"
  "element {element} referenced by {referenced}#{property} not yet drawn" "由{referenced}#{property}引用的{element}元素仍未绘制"
  "already rendered {element}" "{element} 已被渲染"
  "failed to import {element}" "导入{element}失败"
  "Message Name" "消息名称"
  "Asynchronous Continuations" "持续异步"
  "Asynchronous Before" "异步前"
  "Asynchronous After" "异步后"
  "Job Configuration" "工作配置"
  "Job Priority" "工作优先级"
  "Retry Time Cycle" "重试时间周期"
  "Element Documentation" "元素文档"
  "History Configuration" "历史配置"
  "History Time To Live" "历史的生存时间"
  "Form Key" "表单key"
  "Form Fields" "表单字段"
  "Business Key" "业务key"
  "Form Field" "表单字段"
  "Default Value" "默认值"
  "Default Flow" "默认流转路径"
  "Conditional Flow" "条件流转路径"
  "Sequence Flow" "普通流转路径"
  "Add Constraint" "添加约束"
  "Add Property" "添加属性"
  "Execution Listener" "执行监听"
  "Event Type" "事件类型"
  "Listener Type" "监听器类型"
  "Java Class" "Java类"
  "Must provide a value" "必须提供一个值"
  "Delegate Expression" "代理表达式"
  "Script Format" "脚本格式"
  "Script Type" "脚本类型"
  "Inline Script" "内联脚本"
  "External Script" "外部脚本"
  "Field Injection" "字段注入"
  "Input/Output" "输入/输出"
  "Input Parameters" "输入参数"
  "Output Parameters" "输出参数"
  "Output Parameter" "输出参数"
  "Timer Definition Type" "定时器定义类型"
  "Timer Definition" "定时器定义"
  "Signal Name" "信号名称"
  "Link Name" "链接名称"
  "Variable Name" "变量名称"
  "Variable Event" "变量事件"
  "Specify more than one variable change event as a comma separated list." "多个变量事件以逗号隔开"
  "Wait for Completion" "等待完成"
  "Activity Ref" "活动参考"
  "Version Tag" "版本标签"
  "External Task Configuration" "扩展任务配置"
  "Task Priority" "任务优先级"
  "Must configure Connector" "必须配置连接器"
  "Connector Id" "连接器编号"
  "Field Injections" "字段注入"
  "Result Variable" "结果变量"
  "Configure Connector" "配置连接器"
  "Input Parameter" "输入参数"
  "Candidate Users" "候选用户"
  "Candidate Groups" "候选组"
  "Due Date" "到期时间"
  "Follow Up Date" "跟踪日期"
  "The follow up date as an EL expression (e.g. ${someDate} or an ISO date (e.g. 2015-06-26T09:54:00)" "跟踪日期必须符合EL表达式，如： ${someDate} ,或者一个ISO标准日期，如：2015-06-26T09:54:00"
  "The due date as an EL expression (e.g. ${someDate} or an ISO date (e.g. 2015-06-26T09:54:00)" "跟踪日期必须符合EL表达式，如： ${someDate} ,或者一个ISO标准日期，如：2015-06-26T09:54:00"
  "Candidate Starter Configuration" "候选人起动器配置"
  "Candidate Starter Groups" "候选人起动器组"
  "This maps to the process definition key." "这映射到流程定义键。"
  "Candidate Starter Users" "候选人起动器的用户"
  "Specify more than one user as a comma separated list." "指定多个用户作为逗号分隔的列表。"
  "Tasklist Configuration" "Tasklist配置"
  "Specify more than one group as a comma separated list." "指定多个组作为逗号分隔的列表。"
  "create start event" "创建开始事件"
  "create end event" "创建结束事件"
  "create task" "创建任务"
  "activate hand tool" "激活抓手工具"
  "activate lasso tool" "激活套索工具"
  "activate create/remove space tool" "激活创建/删除空间工具"
  "activate global connect tool" "激活全局连接工具"
  "create intermediate/boundary event" "创建中间/边界事件"
  "create expanded sub-process" "创建子流程"
  "create participant" "创建泳道"
  "create data object" "创建数据对象"
  "create data store" "创建数据存储"
}))
(defn- zh-translate
  [template replacements]
  (let [text (or (get zh-map (clojure.string/lower-case (str template))) template)]
    (if (and replacements (seq replacements))
      (reduce (fn [s [k v]] (clojure.string/replace s (str "{" k "}") (str v)))
              text replacements)
      text)))
(defn- translate-module
  []
  (clj->js {:translate ["value" zh-translate]}))

;; flowable moddle 扩展（让 flowable: 属性被 bpmn-js 正确建模，对齐 vben moddleExtensions）
(def ^:private flowable-moddle
  (clj->js
   {:name "Flowable" :prefix "flowable" :uri "http://flowable.org/bpmn"
    :xml {:tagAlias "lowerCase"}
    :associations []
    :types [{:name "FlowableRoot" :isAbstract true :extends ["bpmn:RootElement"] :properties []}
            {:name "UserTask" :isAbstract false :extends ["bpmn:UserTask"]
             :properties [{:name "assignee" :isAttr true :type "String"}
                          {:name "candidateUsers" :isAttr true :type "String"}
                          {:name "candidateGroups" :isAttr true :type "String"}
                          {:name "dueDate" :isAttr true :type "String"}
                          {:name "formKey" :isAttr true :type "String"}
                          {:name "priority" :isAttr true :type "String"}
                          {:name "class" :isAttr true :type "String"}
                          {:name "delegateExpression" :isAttr true :type "String"}]}
            {:name "ServiceTask" :extends ["bpmn:ServiceTask"]
             :properties [{:name "class" :isAttr true :type "String"}
                          {:name "expression" :isAttr true :type "String"}
                          {:name "delegateExpression" :isAttr true :type "String"}
                          {:name "resultVariable" :isAttr true :type "String"}]}
            {:name "Process" :extends ["bpmn:Process"]
             :properties [{:name "candidateStarterUsers" :isAttr true :type "String"}
                          {:name "candidateStarterGroups" :isAttr true :type "String"}
                          {:name "formKey" :isAttr true :type "String"}]}]}))

(defn selected-props
  "读取选中元素属性，返回 {:id :type :name :candidate-users :condition}。"
  [^js element]
  (when element
    (let [bo (.-businessObject element)
          ce (.-conditionExpression bo)]
      {:id (.-id element)
       :type (.-$type bo)
       :name (.-name bo)
       :candidate-users (aget (.-$attrs bo) "flowable:candidateUsers")
       :condition (when ce (.-body ce))})))

(defn update-selected!
  "更新选中元素属性（名称/审批人/条件）。candidate-users 写 flowable:candidateUsers；condition 写 conditionExpression.body。"
  [^js modeler ^js element {:keys [name candidate-users condition]}]
  (when element
    (let [modeling (.get modeler "modeling")
          bo (.-businessObject element)
          attrs (.-$attrs bo)]
      (when (and candidate-users (not= candidate-users (aget attrs "flowable:candidateUsers")))
        (aset attrs "flowable:candidateUsers" candidate-users))
      (when condition
        (if-let [ce (.-conditionExpression bo)]
          (set! (.-body ce) condition)
          ;; 无 conditionExpression 时，用 bpmn factory 创建
          (let [moddle (.get modeler "moddle")
                ce (.create moddle "bpmn:FormalExpression" #js {:body condition})]
            (.updateProperties modeling element #js {:conditionExpression ce}))))
      (.updateProperties modeling element #js {:name name}))))

(defn- connection-midpoint
  [^js conn]
  (let [^js waypoints (.-waypoints conn)
        f (aget waypoints 0)
        l (aget waypoints (dec (.-length waypoints)))]
    {:x (/ (+ (.-x f) (.-x l)) 2)
     :y (/ (+ (.-y f) (.-y l)) 2)}))

(def ^:private plus-node-types
  "连线上加号浮层可追加的节点类型（对齐 vben simple-process-design 的圆形图标菜单）。"
  [{:label "审批人" :bpmn-type "bpmn:UserTask" :icon "bpmn-icon-user-task" :color "#ff943e"}
   {:label "办理人" :bpmn-type "bpmn:UserTask" :icon "bpmn-icon-user-task" :color "#330099"}
   {:label "抄送" :bpmn-type "bpmn:UserTask" :icon "bpmn-icon-user-task" :color "#3296fa"}
   {:label "条件分支" :bpmn-type "bpmn:ExclusiveGateway" :icon "bpmn-icon-gateway-none" :color "#67c23a"}
   {:label "并行分支" :bpmn-type "bpmn:ParallelGateway" :icon "bpmn-icon-gateway-parallel" :color "#626aef"}
   {:label "包容分支" :bpmn-type "bpmn:InclusiveGateway" :icon "bpmn-icon-gateway-or" :color "#345da2"}
   {:label "延迟器" :bpmn-type "bpmn:IntermediateCatchEvent" :icon "bpmn-icon-intermediate-event-catch-timer" :color "#e47470"}
   {:label "路由分支" :bpmn-type "bpmn:ExclusiveGateway" :icon "bpmn-icon-gateway-xor" :color "#ca3a31"}
   {:label "触发器" :bpmn-type "bpmn:CallActivity" :icon "bpmn-icon-call-activity" :color "#3373d2"}
   {:label "子流程" :bpmn-type "bpmn:SubProcess" :icon "bpmn-icon-subprocess-expanded" :color "#996633"}])

(defn- build-plus-menu!
  "构建连线上加号旁的浮动圆形图标菜单（对齐 vben simple-process-design handler 浮层）。
   返回 [menu-el hide-fn]，on-add 签名为 (on-add conn bpmn-type name)。"
  [conn on-add]
  (let [wrap (js/document.createElement "div")
        hide (fn [] (set! (.-display (.-style wrap)) "none"))]
    (set! (.-className wrap) "bpmn-plus-menu")
    (set! (.-style wrap)
          (str "position:absolute;z-index:3000;width:320px;display:flex;flex-wrap:wrap;"
               "background:#fff;border:1px solid #e2e2e2;border-radius:8px;padding:10px;"
               "box-shadow:0 4px 16px rgba(0,0,0,.15);cursor:pointer;"))
    (doseq [{:keys [label bpmn-type icon color]} plus-node-types]
      (let [item (js/document.createElement "div")
            ico (js/document.createElement "span")
            txt (js/document.createElement "div")]
        (set! (.-className ico) (str "bpmn-icon " icon))
        (set! (.-style ico)
              (str "width:50px;height:50px;display:block;text-align:center;user-select:none;background:#fff;"
                   "border:1px solid #e2e2e2;border-radius:50%;font-size:25px;line-height:50px;color:" color ";"))
        (set! (.-textContent txt) label)
        (set! (.-style txt) "width:80px;margin-top:4px;font-size:13px;text-align:center;color:#333;")
        (set! (.-style item) "display:flex;flex-direction:column;align-items:center;margin:6px;")
        (.appendChild item ico)
        (.appendChild item txt)
        (.appendChild wrap item)
        (.addEventListener ico "click" (fn [e] (.stopPropagation e) (hide) (on-add conn bpmn-type label)))
        (.addEventListener item "mouseenter" (fn [] (set! (.-background (.-style ico)) "#e2e2e2")))
        (.addEventListener item "mouseleave" (fn [] (set! (.-background (.-style ico)) "#fff")))))
    [wrap hide]))

(defn add-plus-overlays!
  "在所有连线上加 '+' 按钮；hover 按钮弹出圆形图标节点菜单（对齐 vben），
   点击菜单项回调 (on-add conn bpmn-type name)。"
  [^js modeler on-add]
  (when modeler
    (let [^js registry (.get modeler "elementRegistry")
          ^js overlays (.get modeler "overlays")
          conns (filter (fn [^js el] (.-waypoints el)) (array-seq (.getAll registry)))]
      (doseq [^js conn conns]
        (let [{:keys [x y]} (connection-midpoint conn)
              html (js/document.createElement "button")
              _ (set! (.-innerHTML html) "+")
              _ (set! (.-style html) "cursor:pointer;width:24px;height:24px;border-radius:50%;"
                                     "border:1px solid #409eff;background:#fff;color:#409eff;"
                                     "font-size:17px;line-height:22px;text-align:center;padding:0;"
                                     "box-shadow:0 1px 3px rgba(0,0,0,.2);z-index:200;")
              _ (set! (.-title html) "在此添加节点")
              menu-state (atom nil)
              show-menu (fn []
                          (let [rect (.getBoundingClientRect html)
                                [menu hide] (if-let [[m h] @menu-state] [m h] (build-plus-menu! conn on-add))
                                _ (when-not @menu-state
                                    (reset! menu-state [menu hide])
                                    (.appendChild js/document.body menu))]
                            (set! (.-left (.-style menu)) (str (+ (.-left rect) 10) "px"))
                            (set! (.-top (.-style menu)) (str (+ (.-bottom rect) 4) "px"))
                            (set! (.-display (.-style menu)) "flex")))]
          (.addEventListener html "mouseenter" (fn [e] (.stopPropagation e) (show-menu)))
          (.addEventListener html "mouseleave"
                             (fn [] (js/setTimeout
                                     (fn []
                                       (when-let [[_ h] @menu-state]
                                         (h)))
                                     300)))
          (.add overlays (.-id conn) #js {:position #js {:x (- x 12) :y (- y 12)} :html html}))))))

(defn- import-with-fallback
  "导入 BPMN，失败（如缺 BPMNDI）时回退空白画布。"
  [^js modeler xml on-error]
  (let [p (.importXML modeler xml)]
    (.catch p
            (fn [err]
              (when on-error
                (on-error (str "导入BPMN失败，已载入空白画布: " (.-message err))))
              (.importXML modeler (empty-bpmn))))))

(defn bpmn-modeler
  "渲染 bpmn-js 建模器。参数: {:xml :modeler-ref :on-error :on-select :on-add-node :on-zoom-change}"
  [{:keys [xml modeler-ref on-error on-select on-add-node on-zoom-change]}]
  (let [host-ref (hooks/use-ref nil)]
    (hooks/use-effect
     (fn []
       (let [host (.-current host-ref)
             BpmnJS (bpmn-js)]
         (if (and host BpmnJS (seq xml))
           (let [container (js/document.createElement "div")
                 _ (set! (.-style.height container) "780px")
                 _ (set! (.-style.width container) "100%")
                 _ (.appendChild host container)
                 m (BpmnJS. #js {:container container
                                     :additionalModules (to-array [(translate-module)])
                                     :moddleExtensions (clj->js {:flowable flowable-moddle})})]
             (set! (.-current modeler-ref) m)
             (.on m "selection.changed"
                  (fn [^js e]
                    (let [sel (aget e "newSelection")
                          el (when (and sel (.-length sel)) (aget sel 0))]
                      (when on-select (on-select el)))))
             (.on m "canvas.viewbox.changed"
                  (fn []
                    (when on-zoom-change
                      (let [^js canvas (.get m "canvas")
                            scale (.-scale (.viewbox canvas))]
                        (on-zoom-change (str (int (* (or scale 1) 100)) "%"))))))
             (import-with-fallback m xml on-error)
             (js/setTimeout #(add-plus-overlays! m on-add-node) 800)
             (fn []
               (set! (.-current modeler-ref) nil)
               (.destroy m)
               (.removeChild host container)))
           (fn []))))
     [xml])
    [:div {:ref host-ref :style {:height "780px" :width "100%"
                                 :border "1px solid #dcdfe6" :borderRadius 4 :background "#fff"}}]))

(defn save-bpmn!
  "保存当前流程图，回调返回 XML 字符串。"
  [^js modeler on-saved on-error]
  (when modeler
    (let [p (.saveXML modeler #js {:format true})]
      (-> (.then p (fn [result] (on-saved (.-xml result))))
          (.catch (fn [err] (when on-error (on-error (str "保存BPMN失败: " (.-message err))))))))))

;; ── 工具栏操作 ──────────────────────────────────────────────────────

(defn undo!
  [^js modeler]
  (when modeler (.undo (.get modeler "commandStack"))))

(defn redo!
  [^js modeler]
  (when modeler (.redo (.get modeler "commandStack"))))

(defn zoom-in!
  [^js modeler]
  (when modeler (.zoom (.get modeler "canvas") 1.25)))

(defn zoom-out!
  [^js modeler]
  (when modeler (.zoom (.get modeler "canvas") 0.8)))

(defn fit-viewport!
  [^js modeler]
  (when modeler (.zoom (.get modeler "canvas") "fit-viewport")))

(defn save-svg!
  "保存为 SVG 字符串，回调 (on-saved svg)。"
  [^js modeler on-saved on-error]
  (when modeler
    (-> (.saveSVG modeler)
        (.then (fn [result] (on-saved (.-svg result))))
        (.catch (fn [err] (when on-error (on-error (str "保存SVG失败: " (.-message err)))))))))

(defn- download-file!
  "触发浏览器下载。"
  [filename href]
  (let [a (.createElement js/document "a")]
    (set! (.-download a) filename)
    (set! (.-href a) href)
    (.click a)
    (.revokeObjectURL js/URL href)))

(defn export-bpmn!
  "导出为文件。type ∈ :xml | :svg | :bpmn。"
  [^js modeler type on-error]
  (when modeler
    (if (= type :svg)
      (save-svg! modeler
                 (fn [svg]
                   (let [encoded (js/encodeURIComponent svg)
                         href (str "data:application/text/xml;charset=UTF-8," encoded)]
                     (download-file! (str "diagram." (name type)) href)))
                 on-error)
      (save-bpmn! modeler
                  (fn [xml]
                    (let [encoded (js/encodeURIComponent xml)
                          href (str "data:application/bpmn20-xml;charset=UTF-8," encoded)]
                      (download-file! (str "diagram." (name type)) href)))
                  on-error))))

(defn align-elements!
  "对齐选中的多个元素。align ∈ left|right|top|bottom|center|middle。返回是否执行。"
  [^js modeler align]
  (when modeler
    (let [^js selection (.get modeler "selection")
          els (.get selection)]
      (if (or (nil? els) (<= (.-length els) 1))
        false
        (do (.trigger (.get modeler "alignElements") (array-seq els) align)
            true)))))

(defn import-xml!
  "导入 XML 字符串，重新渲染画布。"
  [^js modeler xml on-imported on-error]
  (when modeler
    (-> (.importXML modeler xml)
        (.then (fn [] (when on-imported (on-imported))))
        (.catch (fn [err] (when on-error (on-error (str "导入BPMN失败: " (.-message err)))))))))

(defn import-local-file!
  "读取本地 XML/BPMN 文件并导入。返回 promise。"
  [^js modeler file on-imported on-error]
  (let [reader (js/FileReader.)]
    (.addEventListener reader "load"
                       (fn [] (import-xml! modeler (.-result reader) on-imported on-error)))
    (.readAsText reader file)))

(defn new-diagram!
  "重新绘制空白流程图。"
  [^js modeler on-imported on-error]
  (when modeler
    (-> (.importXML modeler (empty-bpmn))
        (.then (fn [] (when on-imported (on-imported))))
        (.catch (fn [err] (when on-error (on-error (str "重新绘制失败: " (.-message err)))))))))

(defn preview-xml!
  "预览 XML，回调 (on-preview xml)。"
  [^js modeler on-preview on-error]
  (when modeler
    (-> (.saveXML modeler #js {:format true})
        (.then (fn [result] (on-preview (.-xml result))))
        (.catch (fn [err] (when on-error (on-error (str "预览失败: " (.-message err)))))))))

(defn selected-count
  "当前选中的元素数量。"
  [^js modeler]
  (if modeler
    (.-length (.get (.get modeler "selection")))
    0))


(defn- bpmn-tag [bpmn-type]
  (case bpmn-type
    "bpmn:ExclusiveGateway" "exclusiveGateway"
    "bpmn:ParallelGateway" "parallelGateway"
    "bpmn:InclusiveGateway" "inclusiveGateway"
    "bpmn:IntermediateCatchEvent" "intermediateCatchEvent"
    "bpmn:CallActivity" "callActivity"
    "bpmn:SubProcess" "subProcess"
    "userTask"))

(defn insert-node!
  "在连线中间插入节点（XML 操作 + 重新导入）。返回 promise。on-imported 在重新导入成功后回调。"
  [^js modeler ^js conn bpmn-type name on-error on-imported]
  (when (and modeler conn)
    (let [p (.saveXML modeler #js {:format true})]
      (-> (.then p
                 (fn [result]
                   (let [xml (.-xml result)
                         src-id (.-id (.-source conn))
                         tgt-id (.-id (.-target conn))
                         flow-id (.-id (.-businessObject conn))
                         new-id (str "n" (subs (str (random-uuid)) 0 8))
                         tag (bpmn-tag bpmn-type)
                         sx (.-x (.-source conn)) sy (.-y (.-source conn))
                         tx (.-x (.-target conn)) ty (.-y (.-target conn))
                         mid-x (int (/ (+ sx tx) 2)) mid-y (int (/ (+ sy ty) 2))
                         node-xml (str "<" tag " id=\"" new-id "\" name=\"" name "\"/>")
                         f1-id (str new-id "_f1") f2-id (str new-id "_f2")
                         f1-xml (str "<sequenceFlow id=\"" f1-id "\" sourceRef=\"" src-id "\" targetRef=\"" new-id "\"/>")
                         f2-xml (str "<sequenceFlow id=\"" f2-id "\" sourceRef=\"" new-id "\" targetRef=\"" tgt-id "\"/>")
                         re-flow (js/RegExp
                                  (str "(<[a-zA-Z0-9:]*sequenceFlow[^>]*id=\"" flow-id "\"[^>]*/>"
                                       "|<[a-zA-Z0-9:]*sequenceFlow[^>]*id=\"" flow-id "\"[^>]*>.*?</[a-zA-Z0-9:]*sequenceFlow>)")
                                  "s")
                         xml2 (str/replace xml re-flow (str node-xml f1-xml f2-xml))
                         shape-di (str "<bpmndi:BPMNShape id=\"" new-id "_di\" bpmnElement=\"" new-id "\"><dc:Bounds x=\"" (- mid-x 50) "\" y=\"" (- mid-y 40) "\" width=\"100\" height=\"80\"/></bpmndi:BPMNShape>")
                         e1-di (str "<bpmndi:BPMNEdge id=\"" new-id "_e1_di\" bpmnElement=\"" f1-id "\"><di:waypoint x=\"" sx "\" y=\"" sy "\"/><di:waypoint x=\"" mid-x "\" y=\"" mid-y "\"/></bpmndi:BPMNEdge>")
                         e2-di (str "<bpmndi:BPMNEdge id=\"" new-id "_e2_di\" bpmnElement=\"" f2-id "\"><di:waypoint x=\"" mid-x "\" y=\"" mid-y "\"/><di:waypoint x=\"" tx "\" y=\"" ty "\"/></bpmndi:BPMNEdge>")
                         xml3 (str/replace xml2 "</bpmndi:BPMNPlane>"
                                             (str shape-di e1-di e2-di "</bpmndi:BPMNPlane>"))]
                     (-> (.importXML modeler xml3)
                         (.then (fn [] (when on-imported (on-imported))))))))
          (.catch (fn [err] (when on-error (on-error (str "添加节点失败: " (.-message err))))))))))
