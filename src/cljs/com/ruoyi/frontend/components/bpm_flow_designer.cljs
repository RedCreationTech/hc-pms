(ns com.ruoyi.frontend.components.bpm-flow-designer
  "纯 HTML/CSS flex 流程编辑器(对齐 vben simple-process-design).
   节点树: {:id :type :name :config :child-node :condition-nodes}
   垂直 flex 布局 + 卡片节点 + 灰线箭头 + 蓝色'+'按钮 + 分支横向展开.
   点击节点打开配置抽屉:审批人/抄送/条件/延迟等设置(对齐 vben nodes-config)."
  (:require
    ["dayjs" :as dayjs]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [reagent.core :as r]))


;; ── 节点类型常量(颜色/图标/名称)───────────────────────────────────

(def ^:private node-color
  {"USER_TASK_NODE" "#ff943e" "TRANSACTOR_NODE" "#13c2c2" "COPY_TASK_NODE" "#3296fa" "CONDITION_BRANCH_NODE" "#67c23a"
   "PARALLEL_BRANCH_NODE" "#626aef" "INCLUSIVE_BRANCH_NODE" "#345da2" "DELAY_TIMER_NODE" "#e47470"
   "TRIGGER_NODE" "#3373d2" "CHILD_PROCESS_NODE" "#996633" "ROUTER_BRANCH_NODE" "#13a8a8"
   "START_USER_NODE" "#676565" "END_EVENT_NODE" "#676565"})


(def ^:private node-icon
  {"USER_TASK_NODE" "bpmn-icon-user-task" "TRANSACTOR_NODE" "bpmn-icon-user-task" "COPY_TASK_NODE" "bpmn-icon-user-task"
   "CONDITION_BRANCH_NODE" "bpmn-icon-gateway-none" "PARALLEL_BRANCH_NODE" "bpmn-icon-gateway-parallel"
   "INCLUSIVE_BRANCH_NODE" "bpmn-icon-gateway-or" "DELAY_TIMER_NODE" "bpmn-icon-intermediate-event-catch-timer"
   "TRIGGER_NODE" "bpmn-icon-service-task" "CHILD_PROCESS_NODE" "bpmn-icon-subprocess-expanded"
   "ROUTER_BRANCH_NODE" "bpmn-icon-gateway-xor" "START_USER_NODE" "bpmn-icon-user"
   "END_EVENT_NODE" "bpmn-icon-end-event-none"})


(def ^:private node-type-label
  {"USER_TASK_NODE" "审批人" "TRANSACTOR_NODE" "办理人" "COPY_TASK_NODE" "抄送" "CONDITION_BRANCH_NODE" "条件分支"
   "PARALLEL_BRANCH_NODE" "并行分支" "INCLUSIVE_BRANCH_NODE" "包容分支" "DELAY_TIMER_NODE" "延迟器"
   "TRIGGER_NODE" "触发器" "CHILD_PROCESS_NODE" "子流程" "ROUTER_BRANCH_NODE" "路由分支"
   "START_USER_NODE" "发起人" "END_EVENT_NODE" "结束"})


(def ^:private condition-operators
  [{:value "==" :label "等于"} {:value "!=" :label "不等于"}
   {:value ">" :label "大于"} {:value ">=" :label "大于等于"}
   {:value "<" :label "小于"} {:value "<=" :label "小于等于"}])


(defn- rules->expression
  "条件规则列表 → Flowable 表达式字符串(${days > 3 && amount < 100})."
  [rules]
  (when (seq rules)
    (let [parts (keep (fn [{:keys [left-side op-code right-side]}]
                        (when (and (seq left-side) (seq op-code))
                          (str left-side " " op-code " " (or right-side ""))))
                      rules)]
      (when (seq parts)
        (str "${" (str/join " && " parts) "}")))))


;; ── 配置枚举(对齐 vben consts.ts)─────────────────────────────────

(def ^:private approve-types
  [{:value "USER" :label "人工审批"} {:value "AUTO_PASS" :label "自动通过"} {:value "AUTO_REJECT" :label "自动拒绝"}])


(def ^:private candidate-strategies
  [{:value "USER" :label "指定用户"} {:value "ROLE" :label "指定角色"}
   {:value "DEPT_MEMBER" :label "指定部门成员"} {:value "DEPT_LEADER" :label "指定部门负责人"}
   {:value "POST" :label "指定岗位"} {:value "START_USER_DEPT_LEADER" :label "发起人部门负责人"}
   {:value "MULTI_LEVEL_DEPT_LEADER" :label "发起人部门负责人及上级"}
   {:value "INITIATOR_SELF" :label "发起人本人"} {:value "USER_GROUP" :label "用户组"}
   {:value "FORM_USER" :label "表单内用户字段"} {:value "FORM_DEPT_LEADER" :label "表单内部门负责人"}
   {:value "EXPRESSION" :label "流程表达式"}
   {:value "START_USER_SELECT" :label "发起人自选"} {:value "APPROVE_USER_SELECT" :label "审批人自选"}])


(def ^:private copy-candidate-strategies
  "抄送节点策略(复用审批人的 user-candidate-editor 参数编辑器;自选类策略不适用抄送)."
  [{:value "USER" :label "指定用户"} {:value "ROLE" :label "指定角色"}
   {:value "DEPT_MEMBER" :label "指定部门成员"} {:value "DEPT_LEADER" :label "指定部门负责人"}
   {:value "POST" :label "指定岗位"} {:value "USER_GROUP" :label "用户组"}
   {:value "FORM_USER" :label "表单内用户字段"} {:value "EXPRESSION" :label "流程表达式"}])


(def ^:private candidate-strategy-label
  (into {} (map (juxt :value :label)) candidate-strategies))


(def ^:private approve-methods
  [{:value "SEQUENTIAL" :label "依次审批"} {:value "ANY" :label "或签（一人同意即可）"}
   {:value "ALL" :label "会签（所有人同意）"} {:value "RATIO" :label "按比例通过"}
   {:value "RANDOM" :label "随机一人审批"}])


(def ^:private reject-handler-types
  [{:value "FINISH_PROCESS" :label "终止流程"} {:value "RETURN_USER_TASK" :label "驳回到指定节点"}])


(def ^:private timeout-handler-types
  [{:value "REMINDER" :label "自动提醒"} {:value "AUTO_PASS" :label "自动通过"} {:value "AUTO_REJECT" :label "自动拒绝"}])


(def ^:private assign-empty-handler-types
  [{:value "AUTO_PASS" :label "自动通过"} {:value "AUTO_REJECT" :label "自动拒绝"}
   {:value "TRANSFER_ADMIN" :label "转交管理员"} {:value "ASSIGN_USER" :label "指定用户"}])


(def ^:private assign-start-user-handler-types
  [{:value "TRANSFER_ADMIN" :label "转交管理员"} {:value "AUTO_APPROVE" :label "自动通过"} {:value "AUTO_REJECT" :label "自动拒绝"}])


(def ^:private time-unit-types
  [{:value "MINUTE" :label "分钟"} {:value "HOUR" :label "小时"} {:value "DAY" :label "天"}])


(def ^:private default-user-config
  {:approve-type "USER"
   :candidate-strategy "USER"
   :candidate-param {:user-ids []}
   :approve-method "SEQUENTIAL"
   :reject-handler {:type "FINISH_PROCESS" :return-node-id nil}
   :timeout-handler {:enable false :type "REMINDER" :time-duration 6 :time-unit "HOUR" :max-remind-count 1}
   :assign-empty-handler {:type "AUTO_PASS" :user-ids []}
   :assign-start-user-handler-type "TRANSFER_ADMIN"
   :sign-enable false :reason-require false :skip-expression "" :fields-permission {}
   :listeners {"create" {:enable false :url "" :params []}
               "assign" {:enable false :url "" :params []}
               "complete" {:enable false :url "" :params []}}
   :buttons {"approve" {"enable" true "displayName" "通过"}
             "reject" {"enable" true "displayName" "驳回"}
             "transfer" {"enable" true "displayName" "转办"}
             "delegate" {"enable" true "displayName" "委派"}
             "add-sign" {"enable" true "displayName" "加签"}
             "return" {"enable" true "displayName" "退回"}}})


(def ^:private default-transactor-config
  "办理人节点默认配置:与审批人同构,但按钮默认只开\"办理\"(其余操作隐藏)."
  (assoc default-user-config
         :buttons {"approve" {"enable" true "displayName" "办理"}
                   "reject" {"enable" false "displayName" "驳回"}
                   "transfer" {"enable" false "displayName" "转办"}
                   "delegate" {"enable" false "displayName" "委派"}
                   "add-sign" {"enable" false "displayName" "加签"}
                   "return" {"enable" false "displayName" "退回"}}))


(def ^:private button-config-items
  "可配置的操作按钮(nodeConfig.buttons)."
  [{:key "approve" :label "通过"} {:key "reject" :label "驳回"}
   {:key "transfer" :label "转办"} {:key "delegate" :label "委派"}
   {:key "add-sign" :label "加签"} {:key "return" :label "退回"}])


;; ── 配置表单辅助函数 ────────────────────────────────────────────────

(defn- f-label
  ([s] [:div.bpm-f-label s])
  ([style s] [:div.bpm-f-label style s]))


(defn- opt-user
  [users]
  (doall (for [u @users] ^{:key (:user_id u)}
              [antd/select-option {:value (:user_id u)} (:nick_name u)])))


(defn- opt-role
  [roles]
  (doall (for [ro @roles] ^{:key (:role_id ro)}
              [antd/select-option {:value (:role_id ro)} (:role_name ro)])))


(defn- opt-dept
  [depts]
  (doall (for [d @depts] ^{:key (:dept_id d)}
              [antd/select-option {:value (:dept_id d)} (:dept_name d)])))


(defn- opt-post
  [posts]
  (doall (for [po @posts] ^{:key (:post_id po)}
              [antd/select-option {:value (:post_id po)} (:post_name po)])))


(defn- multi-select
  "通用多选下拉.opts 为已构建的 select-option 序列."
  [placeholder value on-change opts]
  [antd/select {:mode "multiple" :allowClear true :style {:width "100%" :marginTop 8}
                :placeholder placeholder :value (or value []) :onChange on-change}
   opts])


(def ^:private trigger-types
  [{:value "HTTP_REQUEST" :label "HTTP 请求"}
   {:value "HTTP_CALLBACK" :label "HTTP 回调（等待外部触发）"}
   {:value "UPDATE_FORM" :label "修改表单数据"}
   {:value "DELETE_FORM" :label "删除表单数据"}])


(def ^:private listener-events
  [{:key "create" :label "Create（任务创建）"}
   {:key "assign" :label "Assign（任务分配）"}
   {:key "complete" :label "Complete（任务完成）"}])


(defn- remove-idx
  "按序号移除集合中的元素."
  [coll i]
  (vec (keep-indexed (fn [j row] (when (not= j i) row)) (or coll []))))


(defn- kv-rows-editor
  "通用 key-value 行编辑器(headers / bodyParams / params / 变量映射).
   coll: [{:key :value} ...];on-change 回写整列."
  [label coll on-change]
  [:div {:style {:marginTop 8}}
   (f-label label)
   (doall
     (for [[i row] (map-indexed vector (or coll []))]
       ^{:key i}
       [:div {:style {:display "flex" :gap 6 :marginBottom 6}}
        [antd/input {:style {:flex 1} :size "small" :placeholder "参数名"
                     :value (:key row)
                     :onChange #(on-change (assoc (vec (or coll [])) i
                                                  (assoc row :key (-> % .-target .-value))))}]
        [antd/input {:style {:flex 1} :size "small" :placeholder "值（支持 ${字段}）"
                     :value (:value row)
                     :onChange #(on-change (assoc (vec (or coll [])) i
                                                  (assoc row :value (-> % .-target .-value))))}]
        [antd/button {:size "small" :type "text" :danger true
                      :on-click #(on-change (remove-idx coll i))}
         "✕"]]))
   [antd/button {:size "small" :type "dashed" :block true
                 :on-click #(on-change (conj (vec (or coll [])) {:key "" :value ""}))}
    "＋ 添加一行"]])


(defn- rule-rows-editor
  "条件规则行编辑器(left-side 字段 / op / right-side 值),更新 (swap! cfg assoc-in path rows)."
  [cfg path form-fields]
  (let [rules (or (get-in @cfg path) [])]
    [:div
     (doall
       (for [[i r] (map-indexed vector rules)]
         ^{:key i}
         [:div {:style {:display "flex" :gap 6 :marginBottom 6}}
          [antd/select {:style {:flex 1} :size "small" :value (:left-side r)
                        :placeholder "选择字段" :allowClear true
                        :onChange #(swap! cfg assoc-in (conj path i :left-side) (or % ""))}
           (doall
             (for [ff @form-fields]
               (when-let [fld (:field ff)]
                 ^{:key fld}
                 [antd/select-option {:value fld} (:title ff)])))
           [antd/select-option {:value "approved"} "审批结果 approved"]
           [antd/select-option {:value "startUserId"} "发起人"]]
          [antd/select {:style {:width 90} :size "small" :value (or (:op-code r) ">")
                        :onChange #(swap! cfg assoc-in (conj path i :op-code) %)}
           (doall (for [{:keys [value label]} condition-operators]
                    ^{:key value} [antd/select-option {:value value} label]))]
          [antd/input {:style {:flex 1} :size "small" :value (:right-side r)
                       :placeholder "值如 3"
                       :onChange #(swap! cfg assoc-in (conj path i :right-side) (-> % .-target .-value))}]
          [antd/button {:size "small" :type "text" :danger true
                        :on-click #(swap! cfg assoc-in path (remove-idx rules i))}
           "✕"]]))
     (let [gen (rules->expression rules)]
       (when gen
         [:div {:style {:fontSize 12 :color "#909399" :background "#f8f9fa"
                        :padding "6px 8px" :borderRadius 4 :marginBottom 6}}
          gen]))
     [antd/button {:size "small" :type "dashed" :block true
                   :on-click #(swap! cfg assoc-in path
                                     (conj (vec rules) {:left-side "" :op-code ">" :right-side ""}))}
      "＋ 添加条件"]]))


(defn- listeners-editor
  "节点监听器(Create/Assign/Complete HTTP 回调)配置面板,存 nodeConfig.listeners."
  [cfg]
  [:div {:style {:marginTop 14}}
   (f-label "节点监听器（HTTP 回调）")
   (doall
     (for [{:keys [key label]} listener-events]
       (let [lc (get-in @cfg [:listeners key])
             enabled? (boolean (:enable lc))]
         ^{:key key}
         [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6 :padding 10 :marginBottom 8}}
          [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
           [antd/switch {:size "small" :checked enabled?
                         :onChange #(swap! cfg assoc-in [:listeners key :enable] (boolean %))}]
           [:span {:style {:fontSize 13 :fontWeight 500}} label]]
          (when enabled?
            [:div {:style {:marginTop 8}}
             [antd/input {:size "small" :placeholder "回调 URL（POST）"
                          :value (or (:url lc) "")
                          :onChange #(swap! cfg assoc-in [:listeners key :url] (-> % .-target .-value))}]
             [kv-rows-editor "回调参数" (:params lc)
              #(swap! cfg assoc-in [:listeners key :params] %)]])])))])


(defn- user-candidate-editor
  "审批人设置:按候选策略渲染参数编辑器.
   静态策略(USER/ROLE/DEPT/POST)直接生成 BPMN 候选属性;
   动态/新策略(INITIATOR_SELF/USER_GROUP/FORM_USER/FORM_DEPT_LEADER/EXPRESSION 等)配置进
   nodeConfig,运行时由 TaskListener create 事件解析."
  [cfg users roles depts posts groups expressions form-fields]
  (let [s (:candidate-strategy @cfg)
        pi (fn [k v]
             (swap! cfg assoc-in [:candidate-param k] v)
             (when (nil? v) (swap! cfg update :candidate-param dissoc k)))]
    (case s
      "USER"
      (multi-select "请选择用户" (get-in @cfg [:candidate-param :user-ids])
                    #(pi :user-ids (vec %)) (opt-user users))
      "ROLE"
      (multi-select "请选择角色" (get-in @cfg [:candidate-param :role-ids])
                    #(pi :role-ids (vec %)) (opt-role roles))
      ("DEPT_MEMBER" "DEPT_LEADER")
      (multi-select "请选择部门" (get-in @cfg [:candidate-param :dept-ids])
                    #(pi :dept-ids (vec %)) (opt-dept depts))
      "MULTI_LEVEL_DEPT_LEADER"
      [:div
       (multi-select "请选择部门（发起人部门向上取级）" (get-in @cfg [:candidate-param :dept-ids])
                     #(pi :dept-ids (vec %)) (opt-dept depts))
       [:div {:style {:marginTop 8}}
        (f-label "向上层级")
        [antd/select {:style {:width "100%"}
                      :value (or (get-in @cfg [:candidate-param :dept-level]) 1)
                      :onChange #(pi :dept-level (or % 1))}
         (doall (for [i (range 1 16)]
                  ^{:key i} [antd/select-option {:value i} (str "向上 " i " 级")]))]]]
      "POST"
      (multi-select "请选择岗位" (get-in @cfg [:candidate-param :post-ids])
                    #(pi :post-ids (vec %)) (opt-post posts))
      "USER_GROUP"
      [antd/select {:mode "multiple" :style {:width "100%" :marginTop 8} :allowClear true
                    :placeholder "请选择用户组"
                    :value (or (get-in @cfg [:candidate-param :user-group-ids]) [])
                    :onChange #(pi :user-group-ids (vec %))}
       (doall (for [g @groups] ^{:key (:group_id g)}
                   [antd/select-option {:value (:group_id g)} (:name g)]))]
      "FORM_USER"
      [antd/select {:style {:width "100%" :marginTop 8} :allowClear true
                    :placeholder "请选择表单用户字段（发起时该字段值为用户名/用户ID）"
                    :value (get-in @cfg [:candidate-param :form-user-field])
                    :onChange #(pi :form-user-field %)}
       (doall (for [ff @form-fields]
                (when-let [fld (:field ff)]
                  ^{:key fld} [antd/select-option {:value fld} (:title ff)])))]
      "FORM_DEPT_LEADER"
      [antd/select {:style {:width "100%" :marginTop 8} :allowClear true
                    :placeholder "请选择表单部门字段（发起时该字段值为部门ID）"
                    :value (get-in @cfg [:candidate-param :form-dept-field])
                    :onChange #(pi :form-dept-field %)}
       (doall (for [ff @form-fields]
                (when-let [fld (:field ff)]
                  ^{:key fld} [antd/select-option {:value fld} (:title ff)])))]
      "EXPRESSION"
      [antd/select {:style {:width "100%" :marginTop 8} :allowClear true
                    :placeholder "请选择流程表达式（求值结果为用户名列表）"
                    :value (get-in @cfg [:candidate-param :expression-id])
                    :onChange #(pi :expression-id %)}
       (doall (for [e @expressions] ^{:key (:expression_id e)}
                   [antd/select-option {:value (:expression_id e)}
                    (str (:name e) " (" (:expression e) ")")]))]
      "START_USER_SELECT"
      [:div {:style {:marginTop 8 :color "#909399" :fontSize 12}}
       "发起人在发起流程时自行选择审批人（无需在此配置，发起页将提供选择器）"]
      "APPROVE_USER_SELECT"
      [:div {:style {:marginTop 8 :color "#909399" :fontSize 12}}
       "当前审批人在办理时自行指定下一节点审批人（无需在此配置）"]
      "INITIATOR_SELF"
      [:div {:style {:marginTop 8 :color "#909399" :fontSize 12}}
       "审批人为流程发起人本人（运行时解析，无需配置）"]
      [antd/input {:disabled true :style {:marginTop 8} :value "审批人为发起人的部门负责人"}])))


(defn- user-buttons-editor
  "操作按钮配置:每节点可配 approve/reject/transfer/delegate/add-sign/return 的启用与显示名."
  [cfg]
  [:div {:style {:marginTop 14}}
   (f-label "操作按钮配置")
   (doall
     (for [{:keys [key label]} button-config-items]
       (let [b (get-in @cfg [:buttons key])]
         ^{:key key}
         [:div {:style {:display "flex" :gap 8 :alignItems "center" :marginBottom 6}}
          [antd/switch {:size "small" :checked (boolean (or (:enable b) (get b "enable")))
                        :onChange #(swap! cfg assoc-in [:buttons key "enable"] %)}]
          [:span {:style {:width 56 :fontSize 13}} label]
          [antd/input {:size "small" :style {:flex 1}
                       :value (or (:displayName b) (get b "displayName") label)
                       :placeholder "按钮显示名称"
                       :onChange (fn [e]
                                   (swap! cfg assoc-in [:buttons key "displayName"]
                                          (-> e .-target .-value)))}]])))])


(defn- user-reject-editor
  "审批人拒绝时设置."
  [cfg node user-task-nodes]
  [:div
   (f-label "审批人拒绝时")
   [antd/radio-group {:value (get-in @cfg [:reject-handler :type])
                      :onChange #(swap! cfg assoc-in [:reject-handler :type] (-> % .-target .-value))}
    (doall (for [{:keys [value label]} reject-handler-types]
             ^{:key value} [antd/radio {:value value} label]))]
   (when (= (get-in @cfg [:reject-handler :type]) "RETURN_USER_TASK")
     [:div {:style {:marginTop 8}}
      (f-label "驳回节点")
      [antd/select {:allowClear true :style {:width "100%"} :placeholder "选择驳回目标节点"
                    :value (get-in @cfg [:reject-handler :return-node-id])
                    :onChange #(swap! cfg assoc-in [:reject-handler :return-node-id] %)}
       (doall (for [{:keys [id name]} user-task-nodes]
                (when (not= id (:id node))
                  ^{:key id} [antd/select-option {:value id} name])))]])])


(defn- user-timeout-editor
  "审批人超时未处理设置."
  [cfg]
  [:div
   (f-label "审批人超时未处理")
   [antd/switch {:checked (get-in @cfg [:timeout-handler :enable])
                 :checkedChildren "开" :unCheckedChildren "关"
                 :onChange #(swap! cfg assoc-in [:timeout-handler :enable] %)}]
   (when (get-in @cfg [:timeout-handler :enable])
     [:div {:style {:marginTop 8}}
      [antd/radio-group {:value (get-in @cfg [:timeout-handler :type])
                         :onChange #(swap! cfg assoc-in [:timeout-handler :type] (-> % .-target .-value))}
       (doall (for [{:keys [value label]} timeout-handler-types]
                ^{:key value} [antd/radio {:value value} label]))]
      [:div {:style {:display "flex" :gap 8 :marginTop 8}}
       [:div {:style {:flex 1}}
        (f-label "超时时间")
        [antd/input-number {:style {:width "100%"} :min 1
                            :value (get-in @cfg [:timeout-handler :time-duration])
                            :onChange #(swap! cfg assoc-in [:timeout-handler :time-duration] (or % 1))}]]
       [:div {:style {:flex 1}}
        (f-label "时间单位")
        [antd/select {:style {:width "100%"} :value (get-in @cfg [:timeout-handler :time-unit])
                      :onChange #(swap! cfg assoc-in [:timeout-handler :time-unit] %)}
         (doall (for [{:keys [value label]} time-unit-types]
                  ^{:key value} [antd/select-option {:value value} label]))]]]
      (when (= (get-in @cfg [:timeout-handler :type]) "REMINDER")
        [:div {:style {:marginTop 8}}
         (f-label "最大提醒次数")
         [antd/input-number {:style {:width "100%"} :min 1 :max 10
                             :value (get-in @cfg [:timeout-handler :max-remind-count])
                             :onChange #(swap! cfg assoc-in [:timeout-handler :max-remind-count] (or % 1))}]])])])


(defn- user-empty-editor
  "审批人为空时设置."
  [cfg users]
  [:div
   (f-label "审批人为空时")
   [antd/radio-group {:value (get-in @cfg [:assign-empty-handler :type])
                      :onChange #(swap! cfg assoc-in [:assign-empty-handler :type] (-> % .-target .-value))}
    (doall (for [{:keys [value label]} assign-empty-handler-types]
             ^{:key value} [antd/radio {:value value} label]))]
   (when (= (get-in @cfg [:assign-empty-handler :type]) "ASSIGN_USER")
     [:div {:style {:marginTop 8}}
      (f-label "指定用户")
      (multi-select "请选择用户" (get-in @cfg [:assign-empty-handler :user-ids])
                    #(swap! cfg assoc-in [:assign-empty-handler :user-ids] (vec %))
                    (opt-user users))])])


;; ── 节点渲染(递归,path 用于定位编辑)──────────────────────────────

(declare render-node)


(defn- render-connector
  [on-add read-only?]
  [:div.bpm-connector
   (when-not read-only?
     [:button.bpm-plus-btn {:title "在此添加节点" :on-click (fn [e] (.stopPropagation e) (on-add))} "+"])
   [:div.bpm-connector-arrow "▼"]])


(defn- render-card
  [node path on-edit on-delete show-text-fn {:keys [read-only? active-ids completed-ids]}]
  (let [color (get node-color (:type node) "#909399")
        icon (get node-icon (:type node) "bpmn-icon-task")
        hint (get node-type-label (:type node))
        text (:show-text node)
        cls (str "bpm-node-card"
                 (when (contains? active-ids (:id node)) " is-active")
                 (when (contains? completed-ids (:id node)) " is-completed"))]
    [:div {:class cls :on-click (when-not read-only? #(on-edit path))}
     [:div.bpm-node-title-row
      [:div.bpm-node-icon {:style {:color color}} [:i {:class (str "iconfont " icon)}]]
      [:div.bpm-node-name (:name node)]]
     [:div.bpm-node-content {:on-click (when-not read-only? #(on-edit path))}
      [:div.bpm-node-text (if (seq text) text (or (show-text-fn node) (str "请配置" hint)))]]
     (when-not read-only?
       [:div.bpm-node-toolbar
        [:span.bpm-node-del {:title "删除"
                             :on-click (fn [e] (.stopPropagation e) (on-delete path))} "✕"]])]))


(defn- render-capsule
  [node end? on-edit {:keys [read-only? active-ids completed-ids]}]
  (let [cls (str "bpm-capsule"
                 (when end? " end")
                 (when (contains? active-ids (:id node)) " is-active")
                 (when (contains? completed-ids (:id node)) " is-completed"))]
    [:div {:class cls :on-click (when-not read-only? on-edit)} (:name node)]))


(defn- render-branch
  [node path on-edit on-add on-delete add-condition! show-text-fn opts]
  (let [conditions (or (:condition-nodes node) [])]
    [:div.bpm-branch-wrapper
     [:div.bpm-branch-container
      (when-not (:read-only? opts)
        [:div.bpm-branch-add {:on-click #(add-condition! path) :title "添加条件分支"}
         [:div.bpm-branch-add-icon "+"]
         [:div.bpm-branch-add-text "添加条件"]])
      (for [[i cn] (map-indexed vector conditions)]
        (let [first? (= i 0)
              last? (= i (dec (count conditions)))]
          ^{:key (:id cn)}
          [:div.bpm-branch-item {:class (str (when first? "first ") (when last? "last"))}
           [:div.bpm-branch-line-top]
           [:div.bpm-branch-label {:on-click (when-not (:read-only? opts) #(on-edit path)) :title "编辑条件"}
            [:span.bpm-branch-label-name (:name cn)]
            (when-let [expr (:expression cn)]
              [:span.bpm-branch-label-expr expr])]
           (when-let [child (:child-node cn)]
             [:div.bpm-node-column
              (render-node child (conj path :condition-nodes i :child-node) on-edit on-add on-delete add-condition! show-text-fn opts)
              (render-connector #(on-add (conj path :condition-nodes i :child-node)) (:read-only? opts))])]))]]))


(defn render-node
  "递归渲染节点树.path 为从根到当前节点的 assoc-in 路径.opts: {:read-only? :active-ids :completed-ids}"
  [node path on-edit on-add on-delete add-condition! show-text-fn opts]
  (if (nil? node)
    [:div]
    (let [type (:type node)]
      [:div.bpm-node-column
       (cond
         (= type "START_USER_NODE") (render-capsule node false #(on-edit path) opts)
         (= type "END_EVENT_NODE") (render-capsule node true #(on-edit path) opts)
         (and (str/includes? (or type "") "BRANCH") (seq (:condition-nodes node)))
         (render-branch node path on-edit on-add on-delete add-condition! show-text-fn opts)
         :else (render-card node path on-edit on-delete show-text-fn opts))
       (when-let [child (:child-node node)]
         [:div.bpm-node-column
          (render-connector #(on-add (conj path :child-node)) (:read-only? opts))
          (render-node child (conj path :child-node) on-edit on-add on-delete add-condition! show-text-fn opts)])])))


;; ── 配置辅助函数 ─────────────────────────────────────────────────────

(defn- user-show-text
  "审批/办理/抄送节点 → 卡片内容区文本."
  [t cfg]
  (let [at (:approve-type cfg)]
    (cond
      (= t "COPY_TASK_NODE")
      (if-let [s (:candidate-strategy cfg)]
        (str "抄送 · " (get candidate-strategy-label s "指定用户"))
        (str "抄送 " (count (or (:copy-user-ids cfg) [])) " 人"))
      (not= at "USER")
      (case at "AUTO_PASS" "自动通过" "AUTO_REJECT" "自动拒绝" "自动审批")
      :else
      (get candidate-strategy-label (:candidate-strategy cfg) "指定用户"))))


(defn- delay-show-text
  "延迟器 → 卡片内容区文本.P1:支持固定日期时间模式(time-date)."
  [{:keys [time-duration time-unit timer-type time-date]}]
  (if (= "DATE" timer-type)
    (when (seq (str time-date))
      (str "至 " (str time-date) " 后继续"))
    (when time-duration
      (str "延迟 " time-duration (get {"MINUTE" "分钟" "HOUR" "小时" "DAY" "天"} time-unit "小时")))))


;; ── 设计器组件(r/atom + with-let component-did-mount)────────────────

(def ^:private add-node-types
  "可添加的节点类型(对齐 vben node-handler)."
  [{:type "USER_TASK_NODE" :label "审批人"} {:type "TRANSACTOR_NODE" :label "办理人"}
   {:type "COPY_TASK_NODE" :label "抄送"} {:type "CONDITION_BRANCH_NODE" :label "条件分支"}
   {:type "PARALLEL_BRANCH_NODE" :label "并行分支"} {:type "INCLUSIVE_BRANCH_NODE" :label "包容分支"}
   {:type "DELAY_TIMER_NODE" :label "延迟器"} {:type "TRIGGER_NODE" :label "触发器"}
   {:type "CHILD_PROCESS_NODE" :label "子流程"} {:type "ROUTER_BRANCH_NODE" :label "路由分支"}])


(defn bpm-flow-designer
  "HTML/flex 流程编辑器.参数 {:model-id :on-saved :read-only? :active-ids :completed-ids}
   只读模式(read-only?)用于流程详情/追踪:隐藏添加/删除/编辑,节点高亮进行中/已完成."
  [{:keys [model-id on-saved read-only? active-ids completed-ids]}]
  (r/with-let [tree (r/atom nil)
               loading (r/atom true)
               config-path (r/atom nil)
               node-name (r/atom "")
               cfg (r/atom nil)
               add-path (r/atom nil)
               scale (r/atom 1)
               users (r/atom [])
               roles (r/atom [])
               depts (r/atom [])
               posts (r/atom [])
               groups (r/atom [])
               expressions (r/atom [])
               form-fields (r/atom [])
               rows-or-vec (fn [res]
                             (let [d (:data res)]
                               (if (map? d) (:rows d) d)))
               _ (when model-id
                   (api/bpm-model-tree model-id
                                       (fn [res]
                                         (reset! tree (walk/keywordize-keys (:data res)))
                                         (reset! loading false))
                                       (fn [e] (reset! loading false) (antd/error! (str "加载流程失败: " e)))))
               _ (when model-id
                   (api/bpm-get-model model-id
                                      (fn [res]
                                        (let [d (:data res)
                                              fid (:form_id d)]
                                          (when (and (= "1" (:form_type d)) fid)
                                            (api/bpm-get-form fid
                                                              (fn [fr]
                                                                (let [j (:form_json (:data fr))
                                                                      schema (if (string? j)
                                                                               (js->clj (js/JSON.parse j) :keywordize-keys true)
                                                                               (walk/keywordize-keys j))]
                                                                  (reset! form-fields (or (:fields schema) []))))
                                                              #()))))
                                      #()))
               _ (api/user-options {:page 1 :size 1000}
                                 #(reset! users (walk/keywordize-keys (rows-or-vec %))) #())
               _ (api/role-options {:page 1 :size 1000}
                                 #(reset! roles (walk/keywordize-keys (rows-or-vec %))) #())
               _ (api/dept-options {:page 1 :size 1000}
                                 #(reset! depts (walk/keywordize-keys (rows-or-vec %))) #())
               _ (api/post-options {:page 1 :size 1000}
                                 #(reset! posts (walk/keywordize-keys (rows-or-vec %))) #())
               _ (api/bpmmgmt-list "user-group" {:page 1 :size 1000}
                                   #(reset! groups (walk/keywordize-keys (rows-or-vec %))) #())
               _ (api/bpmmgmt-list "expression" {:page 1 :size 1000}
                                   #(reset! expressions (walk/keywordize-keys (rows-or-vec %))) #())
               delete-node (fn [path]
                             (when (seq path)
                               (let [child (:child-node (get-in @tree path))]
                                 (swap! tree assoc-in (vec path) child))))
               add-node (fn [path type label]
                          (when path
                            (let [new-node {:id (str "n" (subs (str (random-uuid)) 0 8)) :type type :name label}]
                              (swap! tree update-in (vec path)
                                     (fn [existing] (assoc new-node :child-node existing))))
                            (reset! add-path nil)))
               find-end (fn find-end
                          [node path]
                          (if (:child-node node)
                            (find-end (:child-node node) (conj path :child-node))
                            (conj path :child-node)))
               open-config (fn [path]
                             (let [node (get-in @tree path)]
                               (reset! config-path path)
                               (reset! node-name (:name node))
                               (reset! cfg
                                       (case (:type node)
                                         "CONDITION_BRANCH_NODE"
                                         {:conditions (mapv (fn [cn]
                                                              (let [expr (or (:expression cn) "${approved == true}")]
                                                                {:name (:name cn)
                                                                 :expression expr
                                                                 :condition-type (if (str/includes? expr "&&") "RULE" "EXPRESSION")
                                                                 :rules [{:left-side "" :op-code ">" :right-side ""}]}))
                                                            (:condition-nodes node))}
                                         "DELAY_TIMER_NODE"
                                         (merge {:time-duration 6 :time-unit "HOUR" :timer-type "DURATION"}
                                                (:config node))
                                         "COPY_TASK_NODE"
                                         (merge {:copy-user-ids [] :copy-role-ids []} (:config node))
                                         "TRIGGER_NODE"
                                         (merge {:trigger-type "HTTP_REQUEST" :url "" :method "POST"
                                                 :headers [] :body-params [] :response-mappings []}
                                                (:config node))
                                         "CHILD_PROCESS_NODE"
                                         (merge {:child-process-key nil :initiator-strategy "START_USER"
                                                 :in-mappings [] :out-mappings []
                                                 :mi-enable false :mi-sequential false :mi-ratio 100
                                                 :mi-source "FIXED" :mi-count 2 :mi-field nil}
                                                (:config node))
                                         "ROUTER_BRANCH_NODE"
                                         {:groups (mapv (fn [g]
                                                          {:target-node-id (:target-node-id g)
                                                           :rules (or (:rules g) [])})
                                                        (or (:groups (:config node)) []))}
                                         "USER_TASK_NODE"
                                         (let [c (:config node)]
                                           (merge default-user-config
                                                  (cond-> c
                                                    (:candidate-param c)
                                                    (update :candidate-param
                                                            (fn [p] (into {} (map (fn [[k v]] [k (if (coll? v) (filterv some? v) v)])) p))))))
                                         "TRANSACTOR_NODE"
                                         (let [c (:config node)]
                                           (merge default-transactor-config
                                                  (cond-> c
                                                    (:candidate-param c)
                                                    (update :candidate-param
                                                            (fn [p] (into {} (map (fn [[k v]] [k (if (coll? v) (filterv some? v) v)])) p))))))
                                         nil))))
               cfg-set! (fn [k v] (swap! cfg assoc k v))
               defs (r/atom [])
               all-nodes (fn []
                           (let [acc (atom [])]
                             (letfn [(walk-n
                                       [n]
                                       (when n
                                         (when (#{"USER_TASK_NODE" "COPY_TASK_NODE" "TRIGGER_NODE"
                                                  "CHILD_PROCESS_NODE" "END_EVENT_NODE"} (:type n))
                                           (swap! acc conj {:id (:id n) :name (:name n)}))
                                         (when-let [c (:child-node n)] (walk-n c))
                                         (doseq [cn (:condition-nodes n)]
                                           (when-let [c (:child-node cn)] (walk-n c)))))]
                               (walk-n @tree))
                             @acc))
               _ (api/bpm-definition-page {:page 1 :size 100}
                                          #(reset! defs (walk/keywordize-keys (get-in % [:data :rows]))) #())
               user-task-nodes (fn []
                                 (let [acc (atom [])]
                                   (letfn [(walk-n
                                             [n]
                                             (when n
                                               (when (#{"USER_TASK_NODE" "TRANSACTOR_NODE" "COPY_TASK_NODE"} (:type n))
                                                 (swap! acc conj {:id (:id n) :name (:name n)}))
                                               (when-let [c (:child-node n)] (walk-n c))
                                               (doseq [cn (:condition-nodes n)]
                                                 (when-let [c (:child-node cn)] (walk-n c)))))]
                                     (walk-n @tree))
                                   @acc))
               save-config (fn []
                             (when-let [p @config-path]
                               (let [node (get-in @tree p)
                                     t (:type node)]
                                 (cond
                                   (= t "CONDITION_BRANCH_NODE")
                                   (swap! tree assoc-in p
                                          (assoc node :name @node-name
                                                 :condition-nodes
                                                 (mapv (fn [cn c]
                                                         (let [expr (if (= (:condition-type c) "RULE")
                                                                      (rules->expression (:rules c))
                                                                      (:expression c))]
                                                           (merge cn (select-keys c [:name])
                                                                  {:expression (or expr (:expression cn))})))
                                                       (:condition-nodes node) (:conditions @cfg))))
                                   (= t "DELAY_TIMER_NODE")
                                   (swap! tree assoc-in p
                                          (assoc node :name @node-name
                                                 :config (select-keys @cfg [:time-duration :time-unit
                                                                            :timer-type :time-date])
                                                 :show-text (delay-show-text @cfg)))
                                   (#{"USER_TASK_NODE" "TRANSACTOR_NODE" "COPY_TASK_NODE"} t)
                                   (swap! tree assoc-in p
                                          (assoc node :name @node-name
                                                 :config @cfg
                                                 :show-text (user-show-text t @cfg)))
                                   (= t "TRIGGER_NODE")
                                   (swap! tree assoc-in p
                                          (assoc node :name @node-name :config @cfg
                                                 :show-text (some #(when (= (:value %) (:trigger-type @cfg)) (:label %))
                                                                  trigger-types)))
                                   (= t "CHILD_PROCESS_NODE")
                                   (swap! tree assoc-in p
                                          (assoc node :name @node-name :config @cfg
                                                 :show-text (str "子流程 "
                                                                 (or (:child-process-key @cfg) "未选择"))))
                                   (= t "ROUTER_BRANCH_NODE")
                                   (swap! tree assoc-in p
                                          (assoc node :name @node-name
                                                 :config (select-keys @cfg [:groups])
                                                 :show-text (str (count (or (:groups @cfg) [])) " 组路由分支")))
                                   :else
                                   (swap! tree assoc-in p (assoc node :name @node-name))))
                               (reset! config-path nil)
                               (reset! cfg nil)))
               save-tree (fn []
                           (when (and model-id @tree)
                             (api/bpm-save-model-tree model-id @tree
                                                      (fn [_] (antd/success! "流程已保存") (when on-saved (on-saved)))
                                                      (fn [e] (antd/error! (str "保存失败: " e))))))
               add-condition! (fn [path]
                                (let [cond-path (conj path :condition-nodes)
                                      conds (or (get-in @tree cond-path) [])]
                                  (swap! tree assoc-in cond-path
                                         (conj (vec conds)
                                               {:id (str "cond_" (random-uuid))
                                                :name (str "条件" (inc (count conds)))
                                                :type "condition"
                                                :child-node nil}))))
               show-text-of (fn [node]
                              (or (:show-text node)
                                  (let [t (:type node) cfg (:config node)]
                                    (case t
                                      "COPY_TASK_NODE"
                                      (if-let [s (:candidate-strategy cfg)]
                                        (str "抄送 · " (get candidate-strategy-label s "指定用户"))
                                        (let [uc (count (or (:copy-user-ids cfg) []))
                                              rc (count (or (:copy-role-ids cfg) []))]
                                          (str "抄送" (when (pos? uc) (str " " uc " 用户"))
                                               (when (and (pos? uc) (pos? rc)) " +")
                                               (when (pos? rc) (str " " rc " 角色")))))
                                      "DELAY_TIMER_NODE"
                                      (delay-show-text cfg)
                                      ("USER_TASK_NODE" "TRANSACTOR_NODE")
                                      (let [at (:approve-type cfg)]
                                        (cond
                                          (nil? at) "请配置审批人"
                                          (= at "USER")
                                          (case (:candidate-strategy cfg)
                                            "USER" (let [ids (set (get-in cfg [:candidate-param :user-ids]))]
                                                     (str "指定用户：" (str/join "," (take 3 (map :nick_name (filter #(ids (:user_id %)) @users))))))
                                            "ROLE" (let [ids (set (get-in cfg [:candidate-param :role-ids]))]
                                                     (str "指定角色：" (str/join "," (take 3 (map :role_name (filter #(ids (:role_id %)) @roles))))))
                                            "DEPT_MEMBER" (let [ids (set (get-in cfg [:candidate-param :dept-ids]))]
                                                            (str "部门成员：" (str/join "," (take 3 (map :dept_name (filter #(ids (:dept_id %)) @depts))))))
                                            "POST" (let [ids (set (get-in cfg [:candidate-param :post-ids]))]
                                                     (str "指定岗位：" (str/join "," (take 3 (map :post_name (filter #(ids (:post_id %)) @posts))))))
                                            "DEPT_LEADER" "部门负责人"
                                            "START_USER_DEPT_LEADER" "发起人部门负责人"
                                            "MULTI_LEVEL_DEPT_LEADER" (str "发起人部门负责人及上级 向上 " (get-in cfg [:candidate-param :dept-level] 1) " 级")
                                            "INITIATOR_SELF" "发起人本人"
                                            "USER_GROUP" "用户组审批"
                                            "FORM_USER" "表单内用户"
                                            "FORM_DEPT_LEADER" "表单内部门负责人"
                                            "EXPRESSION" "流程表达式"
                                            (get candidate-strategy-label (:candidate-strategy cfg) "请配置审批人"))
                                          (= at "AUTO_PASS") "自动通过"
                                          (= at "AUTO_REJECT") "自动拒绝"
                                          :else "自动审批"))
                                      nil))))]
              [:div.bpm-flow-wrap
               [:div.bpm-toolbar
                [:span.bpm-toolbar-title (if read-only? "流程追踪" "流程设计")]
                [:div.bpm-toolbar-right
                 (when-not read-only?
                   [antd/button {:size "small" :on-click #(reset! add-path (find-end @tree []))} "＋ 添加节点"])
                 [antd/button {:size "small" :on-click #(swap! scale (fn [s] (max 0.5 (- s 0.1))))} "−"]
                 [:span.bpm-zoom (str (int (* @scale 100)) "%")]
                 [antd/button {:size "small" :on-click #(swap! scale (fn [s] (min 2 (+ s 0.1))))} "＋"]
                 [antd/button {:size "small" :on-click #(reset! scale 1)} "重置"]
                 (when-not read-only?
                   [antd/button {:size "small" :type "primary" :on-click save-tree} "保存流程"])]]
               (if @loading
                 [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
                 [:div.bpm-flow-root
                  (when-let [t @tree]
                    [:div {:style {:transform (str "scale(" @scale ")") :transformOrigin "50% 0"}}
                     (render-node t [] open-config (fn [path] (reset! add-path path)) delete-node add-condition! show-text-of
                                  {:read-only? read-only?
                                   :active-ids (set (or active-ids []))
                                   :completed-ids (set (or completed-ids []))})])])
               ;; ── 节点配置抽屉(对齐 vben Drawer 配置面板)──────────────────
               [antd/drawer {:open (boolean @config-path)
                             :onClose #(reset! config-path nil)
                             :title (str "节点配置 · " (get node-type-label (get-in @tree (conj @config-path :type)) ""))
                             :size 460
                             :destroyOnHidden true}
                (when-let [p @config-path]
                  (let [node (get-in @tree p)
                        t (:type node)]
                    [:div.bpm-config
                     (f-label "节点名称")
                     [antd/input {:value @node-name
                                  :onChange (fn [e] (reset! node-name (-> e .-target .-value)))}]
                     (cond
                       (= t "CONDITION_BRANCH_NODE")
                       (doall
                         (for [[i c] (map-indexed vector (or (:conditions @cfg) []))]
                           (let [rule-mode? (= (:condition-type c) "RULE")]
                             ^{:key i}
                             [:div {:style {:marginTop 16}}
                              (f-label (str "条件 " (inc i)))
                              [antd/input {:value (:name c) :placeholder "条件名称" :style {:marginBottom 8}
                                           :onChange (fn [e] (swap! cfg assoc-in [:conditions i :name] (-> e .-target .-value)))}]
                              [antd/select {:style {:width "100%" :marginBottom 8} :size "small"
                                            :value (or (:condition-type c) "EXPRESSION")
                                            :onChange #(swap! cfg assoc-in [:conditions i :condition-type] %)}
                               [antd/select-option {:value "EXPRESSION"} "条件表达式"]
                               [antd/select-option {:value "RULE"} "条件规则"]]
                              (if rule-mode?
                                [:div
                                 (doall
                                   (for [[ri r] (map-indexed vector (or (:rules c) [{:left-side "" :op-code ">" :right-side ""}]))]
                                     ^{:key ri}
                                     [:div {:style {:display "flex" :gap 6 :marginBottom 6}}
                                      [antd/select {:style {:flex 1} :size "small" :value (:left-side r)
                                                    :placeholder "选择字段" :allowClear true
                                                    :onChange #(swap! cfg assoc-in [:conditions i :rules ri :left-side] (or % ""))}
                                       (doall
                                         (for [ff @form-fields]
                                           (when-let [fld (:field ff)]
                                             ^{:key fld}
                                             [antd/select-option {:value fld} (:title ff)])))
                                       [antd/select-option {:value "approved"} "审批结果 approved"]
                                       [antd/select-option {:value "startUserId"} "发起人"]]
                                      [antd/select {:style {:width 90} :size "small" :value (or (:op-code r) ">")
                                                    :onChange #(swap! cfg assoc-in [:conditions i :rules ri :op-code] %)}
                                       (doall (for [{:keys [value label]} condition-operators]
                                                ^{:key value} [antd/select-option {:value value} label]))]
                                      [antd/input {:style {:flex 1} :size "small" :value (:right-side r) :placeholder "值如 3"
                                                   :onChange (fn [e] (swap! cfg assoc-in [:conditions i :rules ri :right-side] (-> e .-target .-value)))}]]))
                                 (let [gen (rules->expression (:rules c))]
                                   (when gen
                                     [:div {:style {:fontSize 12 :color "#909399" :background "#f8f9fa"
                                                    :padding "6px 8px" :borderRadius 4 :marginTop 4}}
                                      gen]))]
                                [antd/text-area {:value (:expression c) :placeholder "如 ${days} > 3" :rows 2
                                                 :onChange (fn [e] (swap! cfg assoc-in [:conditions i :expression] (-> e .-target .-value)))}])])))
                       (= t "DELAY_TIMER_NODE")
                       [:div {:style {:marginTop 16}}
                        (f-label "延迟模式")
                        [antd/radio-group {:value (or (:timer-type @cfg) "DURATION")
                                           :onChange #(swap! cfg assoc :timer-type (-> % .-target .-value))}
                         [antd/radio {:value "DURATION"} "时长"]
                         [antd/radio {:value "DATE"} "固定日期时间"]]
                        (if (= "DATE" (or (:timer-type @cfg) "DURATION"))
                          [:div {:style {:marginTop 8}}
                           (f-label "到达该时间后继续（生成 timeDate 定时器）")
                           [antd/date-picker {:style {:width "100%"} :showTime true
                                              :allowClear false
                                              :value (when (seq (str (:time-date @cfg)))
                                                       (dayjs (str (:time-date @cfg))))
                                              :onChange (fn [d _]
                                                          (swap! cfg assoc :time-date
                                                                 (if d (.toISOString d) "")))}]]
                          [:div {:style {:display "flex" :gap 8 :marginTop 8}}
                           [antd/input-number {:style {:width "50%"} :min 1 :value (:time-duration @cfg)
                                               :onChange #(swap! cfg assoc :time-duration (or % 1))}]
                           [antd/select {:style {:width "50%"} :value (:time-unit @cfg)
                                         :onChange #(swap! cfg assoc :time-unit %)}
                            (doall (for [{:keys [value label]} time-unit-types]
                                     ^{:key value} [antd/select-option {:value value} label]))]])]
                       (= t "COPY_TASK_NODE")
                       [:div {:style {:marginTop 16}}
                        (let [legacy? (and (str/blank? (str (:candidate-strategy @cfg)))
                                           (or (seq (:copy-user-ids @cfg)) (seq (:copy-role-ids @cfg))))]
                          (if legacy?
                            [:div
                             (f-label "抄送人（用户）")
                             (multi-select "请选择抄送用户" (:copy-user-ids @cfg)
                                           #(cfg-set! :copy-user-ids (vec %)) (opt-user users))
                             (f-label {:style {:marginTop 12}} "抄送人（角色）")
                             (multi-select "请选择抄送角色" (:copy-role-ids @cfg)
                                           #(cfg-set! :copy-role-ids (vec %)) (opt-role roles))]
                            [:div
                             (f-label "抄送策略")
                             [antd/select {:style {:width "100%"} :value (or (:candidate-strategy @cfg) "USER")
                                           :onChange #(do (cfg-set! :candidate-strategy %)
                                                          (cfg-set! :candidate-param {}))}
                              (doall (for [{:keys [value label]} copy-candidate-strategies]
                                       ^{:key value} [antd/select-option {:value value} label]))]
                             (f-label {:style {:marginTop 8}} "抄送对象")
                             [user-candidate-editor cfg users roles depts posts groups expressions form-fields]]))]
                       (= t "TRIGGER_NODE")
                       [:div {:style {:marginTop 16}}
                        (f-label "触发器类型")
                        [antd/select {:style {:width "100%" :marginBottom 8}
                                      :value (or (:trigger-type @cfg) "HTTP_REQUEST")
                                      :onChange #(cfg-set! :trigger-type %)}
                         (doall (for [{:keys [value label]} trigger-types]
                                  ^{:key value} [antd/select-option {:value value} label]))]
                        (case (or (:trigger-type @cfg) "HTTP_REQUEST")
                          "HTTP_REQUEST"
                          [:div
                           [antd/input {:value (or (:url @cfg) "")
                                        :placeholder "请求 URL（支持 ${字段}）"
                                        :onChange #(cfg-set! :url (-> % .-target .-value))}]
                           [:div {:style {:marginTop 8}}
                            [antd/select {:style {:width "100%"} :size "small"
                                          :value (or (:method @cfg) "POST")
                                          :onChange #(cfg-set! :method %)}
                             [antd/select-option {:value "POST"} "POST"]
                             [antd/select-option {:value "GET"} "GET"]]]
                           [kv-rows-editor "请求头 Headers" (:headers @cfg) #(cfg-set! :headers %)]
                           [kv-rows-editor "请求体 Body 参数" (:body-params @cfg) #(cfg-set! :body-params %)]
                           [kv-rows-editor "响应回写（JSON 路径 → 流程变量/表单字段）" (:response-mappings @cfg)
                            #(cfg-set! :response-mappings %)]]
                          "HTTP_CALLBACK"
                          [:div.bpm-cfg-tip "等待外部系统回调触发（本期降级：节点进入时记录日志后直接通过，不阻塞流程）。"]
                          "UPDATE_FORM"
                          [:div
                           (f-label "满足以下条件时（全部 AND，留空则始终执行）")
                           [rule-rows-editor cfg [:conditions] form-fields]
                           (f-label {:style {:marginTop 8}} "更新字段（字段 = 值，支持 ${字段}）")
                           [kv-rows-editor "字段 = 值" (:fields @cfg) #(cfg-set! :fields %)]]
                          "DELETE_FORM"
                          [:div
                           (f-label "清除以下表单字段（流程变量）")
                           [antd/select {:mode "multiple" :style {:width "100%"} :allowClear true
                                         :placeholder "选择要清除的字段"
                                         :value (or (:fields @cfg) [])
                                         :onChange #(cfg-set! :fields (vec %))}
                            (doall
                              (for [ff @form-fields]
                                (when-let [fld (:field ff)]
                                  ^{:key fld}
                                  [antd/select-option {:value fld} (:title ff)])))]])]
                       (= t "CHILD_PROCESS_NODE")
                       [:div {:style {:marginTop 16}}
                        (f-label "子流程定义（已部署）")
                        [antd/select {:style {:width "100%"} :allowClear true
                                      :placeholder "选择子流程定义"
                                      :value (:child-process-key @cfg)
                                      :onChange #(cfg-set! :child-process-key %)}
                         (doall (for [d @defs] ^{:key (:id d)}
                                     [antd/select-option {:value (:key d)}
                                      (str (:name d) "（" (:key d) " v" (:version d) "）")]))]
                        (f-label {:style {:marginTop 12}} "子流程发起人策略")
                        [antd/radio-group {:value (or (:initiator-strategy @cfg) "START_USER")
                                           :onChange #(cfg-set! :initiator-strategy (-> % .-target .-value))}
                         [antd/radio {:value "START_USER"} "主流程发起人（透传 startUserId）"]
                         [antd/radio {:value "NONE"} "不处理"]]
                        [:div {:style {:marginTop 14}}
                         (f-label "多实例设置（并发发起多个子流程实例）")
                         [antd/switch {:checked (boolean (:mi-enable @cfg))
                                       :checkedChildren "开" :unCheckedChildren "关"
                                       :onChange #(cfg-set! :mi-enable (boolean %))}]
                         (when (:mi-enable @cfg)
                           [:div {:style {:marginTop 8}}
                            [antd/radio-group {:value (boolean (:mi-sequential @cfg))
                                               :onChange #(cfg-set! :mi-sequential (boolean (-> % .-target .-value)))}
                             [antd/radio {:value false} "并行"]
                             [antd/radio {:value true} "串行"]]
                            (f-label {:style {:marginTop 8}} "完成比例（100% = 全部实例完成）")
                            [antd/slider {:min 10 :max 100 :step 5
                                          :value (or (:mi-ratio @cfg) 100)
                                          :onChange #(cfg-set! :mi-ratio (or % 100))
                                          :tooltip (clj->js {:formatter (fn [v] (str v "%"))})}]
                            (f-label {:style {:marginTop 8}} "实例数量来源")
                            [antd/radio-group {:value (or (:mi-source @cfg) "FIXED")
                                               :onChange #(cfg-set! :mi-source (-> % .-target .-value))}
                             [antd/radio {:value "FIXED"} "固定数量"]
                             [antd/radio {:value "NUMERIC_FIELD"} "数字表单字段"]
                             [antd/radio {:value "MULTI_FIELD"} "多选表单字段"]]
                            (case (or (:mi-source @cfg) "FIXED")
                              "FIXED" [antd/input-number {:style {:width "100%" :marginTop 8} :min 1 :max 99
                                                          :value (or (:mi-count @cfg) 2)
                                                          :onChange #(cfg-set! :mi-count (or % 2))}]
                              [antd/select {:style {:width "100%"} :allowClear true :placeholder "选择表单字段"
                                            :value (:mi-field @cfg)
                                            :onChange #(cfg-set! :mi-field %)}
                               (doall (for [ff @form-fields]
                                        (when-let [fld (:field ff)]
                                          ^{:key fld} [antd/select-option {:value fld} (:title ff)])))])])]
                        (f-label {:style {:marginTop 12}} "主 → 子 变量映射")
                        [kv-rows-editor "主流程变量 → 子流程变量" (:in-mappings @cfg)
                         #(cfg-set! :in-mappings %)]
                        (f-label {:style {:marginTop 12}} "子 → 主 变量映射")
                        [kv-rows-editor "子流程变量 → 主流程变量" (:out-mappings @cfg)
                         #(cfg-set! :out-mappings %)]]
                       (= t "ROUTER_BRANCH_NODE")
                       [:div {:style {:marginTop 16}}
                        (f-label "路由分支（按条件跳转到目标节点，均不满足时走默认连线）")
                        (doall
                          (for [[i g] (map-indexed vector (or (:groups @cfg) []))]
                            ^{:key i}
                            [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6 :padding 10 :marginBottom 10}}
                             [:div {:style {:display "flex" :gap 6 :marginBottom 8}}
                              [:div {:style {:flex 1}}
                               (f-label "目标节点")
                               [antd/select {:style {:width "100%"} :size "small" :allowClear true
                                             :placeholder "跳转目标节点"
                                             :value (:target-node-id g)
                                             :onChange #(swap! cfg assoc-in [:groups i :target-node-id] %)}
                                (doall (for [n (all-nodes)]
                                         (when (not= (:id n) (:id node))
                                           ^{:key (:id n)}
                                           [antd/select-option {:value (:id n)} (:name n)])))]]
                              [antd/button {:size "small" :type "text" :danger true
                                            :on-click #(swap! cfg assoc :groups (remove-idx (:groups @cfg) i))}
                               "删除"]]
                             (rule-rows-editor cfg [:groups i :rules] form-fields)]))
                        [antd/button {:size "small" :type "dashed" :block true
                                      :on-click #(swap! cfg assoc :groups
                                                        (conj (vec (or (:groups @cfg) []))
                                                              {:target-node-id nil
                                                               :rules [{:left-side "" :op-code ">" :right-side ""}]}))}
                         "＋ 添加路由分支"]]
                       (= t "TRANSACTOR_NODE")
                       [:div {:style {:marginTop 16}}
                        (f-label "办理人设置")
                        [antd/radio-group {:value (:candidate-strategy @cfg)
                                           :onChange #(do (cfg-set! :candidate-strategy (-> % .-target .-value))
                                                          (cfg-set! :candidate-param
                                                                    {:user-ids [] :role-ids [] :dept-ids [] :post-ids []
                                                                     :dept-level 1 :user-group-ids []
                                                                     :form-user-field nil :form-dept-field nil
                                                                     :expression-id nil}))}
                         (doall (for [{:keys [value label]} candidate-strategies]
                                  ^{:key value} [antd/radio {:value value} label]))]
                        (user-candidate-editor cfg users roles depts posts groups expressions form-fields)
                        (f-label {:style {:marginTop 14}} "多人办理方式")
                        [antd/radio-group {:value (:approve-method @cfg)
                                           :onChange #(cfg-set! :approve-method (-> % .-target .-value))}
                         (doall (for [{:keys [value label]} approve-methods]
                                  ^{:key value} [antd/radio {:value value} label]))]
                        (when (= (:approve-method @cfg) "RATIO")
                          [:div {:style {:marginTop 8}}
                           (f-label "通过比例（%）")
                           [antd/input-number {:style {:width "100%"} :min 10 :max 100 :step 10
                                               :value (:approve-ratio @cfg)
                                               :onChange #(cfg-set! :approve-ratio (or % 100))}]])
                        (user-empty-editor cfg users)
                        (user-buttons-editor cfg)
                        [:div {:style {:display "flex" :gap 24 :marginTop 14}}
                         [:div (f-label "是否需要签名")
                          [antd/switch {:checked (:sign-enable @cfg) :checkedChildren "是" :unCheckedChildren "否"
                                        :onChange #(cfg-set! :sign-enable %)}]]
                         [:div (f-label "办理意见")
                          [antd/switch {:checked (:reason-require @cfg) :checkedChildren "必填" :unCheckedChildren "非必填"
                                        :onChange #(cfg-set! :reason-require %)}]]]
                        (when (seq @form-fields)
                          [:div {:style {:marginTop 14}}
                           (f-label "表单字段权限")
                           [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6}}
                            (doall
                              (for [f @form-fields]
                                (let [field (:field f)]
                                  ^{:key (or field (str "fp-" (random-uuid)))}
                                  [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                                                 :padding "5px 10px" :borderBottom "1px solid #f5f5f5"}}
                                   [:span {:style {:fontSize 13}} (:title f)]
                                   [antd/select {:style {:width 110} :size "small"
                                                 :value (or (get-in @cfg [:fields-permission field]) "edit")
                                                 :onChange #(cfg-set! :fields-permission
                                                                      (assoc (or (get-in @cfg [:fields-permission]) {}) field %))}
                                    [antd/select-option {:value "edit"} "可编辑"]
                                    [antd/select-option {:value "readonly"} "只读"]
                                    [antd/select-option {:value "hidden"} "隐藏"]]])))]])]
                       (= t "USER_TASK_NODE")
                       [:div
                        (when (not= (:approve-type @cfg) "USER")
                          [:div.bpm-cfg-tip "当前为自动审批，保存后卡片显示自动通过/拒绝。"])
                        (f-label {:style {:marginTop 12}} "审批类型")
                        [antd/radio-group {:value (:approve-type @cfg)
                                           :onChange #(cfg-set! :approve-type (-> % .-target .-value))}
                         (doall (for [{:keys [value label]} approve-types]
                                  ^{:key value} [antd/radio {:value value} label]))]
                        (when (= (:approve-type @cfg) "USER")
                          [:div {:style {:marginTop 14}}
                           (f-label "审批人设置")
                           [antd/radio-group {:value (:candidate-strategy @cfg)
                                              :onChange #(do (cfg-set! :candidate-strategy (-> % .-target .-value))
                                                             (cfg-set! :candidate-param
                                                                       {:user-ids [] :role-ids [] :dept-ids [] :post-ids []
                                                                        :dept-level 1 :user-group-ids []
                                                                        :form-user-field nil :form-dept-field nil
                                                                        :expression-id nil}))}
                            (doall (for [{:keys [value label]} candidate-strategies]
                                     ^{:key value} [antd/radio {:value value} label]))]
                           (user-candidate-editor cfg users roles depts posts groups expressions form-fields)
                           (f-label {:style {:marginTop 14}} "多人审批方式")
                           [antd/radio-group {:value (:approve-method @cfg)
                                              :onChange #(cfg-set! :approve-method (-> % .-target .-value))}
                            (doall (for [{:keys [value label]} approve-methods]
                                     ^{:key value} [antd/radio {:value value} label]))]
                           (when (= (:approve-method @cfg) "RATIO")
                             [:div {:style {:marginTop 8}}
                              (f-label "通过比例（%）")
                              [antd/input-number {:style {:width "100%"} :min 10 :max 100 :step 10
                                                  :value (:approve-ratio @cfg)
                                                  :onChange #(cfg-set! :approve-ratio (or % 100))}]])
                           (user-reject-editor cfg node (user-task-nodes))
                           (user-timeout-editor cfg)
                           (user-empty-editor cfg users)
                           (user-buttons-editor cfg)
                           (f-label {:style {:marginTop 14}} "审批人与提交人为同一人时")
                           [antd/radio-group {:value (:assign-start-user-handler-type @cfg)
                                              :onChange #(cfg-set! :assign-start-user-handler-type (-> % .-target .-value))}
                            (doall (for [{:keys [value label]} assign-start-user-handler-types]
                                     ^{:key value} [antd/radio {:value value} label]))]
                           [:div {:style {:display "flex" :gap 24 :marginTop 14}}
                            [:div (f-label "是否需要签名")
                             [antd/switch {:checked (:sign-enable @cfg) :checkedChildren "是" :unCheckedChildren "否"
                                           :onChange #(cfg-set! :sign-enable %)}]]
                            [:div (f-label "审批意见")
                             [antd/switch {:checked (:reason-require @cfg) :checkedChildren "必填" :unCheckedChildren "非必填"
                                           :onChange #(cfg-set! :reason-require %)}]]]
                           (f-label {:style {:marginTop 14}} "跳过表达式")
                           [antd/text-area {:value (:skip-expression @cfg) :rows 2
                                            :placeholder "填写后满足条件则自动跳过本节点"
                                            :onChange #(cfg-set! :skip-expression (-> % .-target .-value))}]
                           [listeners-editor cfg]
                           (when (seq @form-fields)
                             [:div {:style {:marginTop 14}}
                              (f-label "表单字段权限")
                              [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6}}
                               (doall
                                 (for [f @form-fields]
                                   (let [field (:field f)]
                                     ^{:key (or field (str "fp-" (random-uuid)))}
                                     [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                                                    :padding "5px 10px" :borderBottom "1px solid #f5f5f5"}}
                                      [:span {:style {:fontSize 13}} (:title f)]
                                      [antd/select {:style {:width 110} :size "small"
                                                    :value (or (get-in @cfg [:fields-permission field]) "edit")
                                                    :onChange #(cfg-set! :fields-permission
                                                                         (assoc (or (get-in @cfg [:fields-permission]) {}) field %))}
                                       [antd/select-option {:value "edit"} "可编辑"]
                                       [antd/select-option {:value "readonly"} "只读"]
                                       [antd/select-option {:value "hidden"} "隐藏"]]])))]])])]
                       :else nil)
                     [:div {:style {:marginTop 16}}
                      [antd/button {:type "primary" :block true :on-click save-config} "保存配置"]]]))]
               ;; ── 添加节点对话框 ─────────────────────────────────────────────
               [antd/modal {:title "在此添加节点" :open (boolean @add-path) :footer nil
                            :width 480 :onCancel #(reset! add-path nil)}
                [:div.bpm-addmenu
                 (doall
                   (for [{:keys [type label]} add-node-types]
                     ^{:key label}
                     [:div.bpm-addmenu-item {:on-click #(add-node @add-path type label)}
                      [:div.bpm-addmenu-icon {:style {:color (get node-color type "#909399")}}
                       [:i {:class (str "iconfont " (get node-icon type "bpmn-icon-task"))}]]
                      [:span label]]))]]]))
