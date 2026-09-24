(ns com.ruoyi.frontend.pages.pms.approval
  "可配置审批: 审批策略编辑 (模板与规则页 \"审批策略\" 页签), 审批进度展示与逐级审批弹窗."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def type-options
  [{:value "cost-version" :label "费用版本" :amount? true}
   {:value "charter" :label "项目章程" :amount? true}
   {:value "plan-baseline" :label "计划基线" :amount? false}
   {:value "closure" :label "项目结项" :amount? false}])


(def type-labels (into {} (map (juxt :value :label) type-options)))


(def rule-labels
  {"dept_leader" "项目所属部门负责人" "role" "指定角色" "project_manager" "项目经理"
   "user" "指定用户" "submitter_choice" "提交人选择"})


(def step-status-labels
  {"waiting" "未开始" "pending" "待审批" "approved" "已通过" "rejected" "已驳回" "skipped" "无需处理" "cancelled" "已取消"})


(defn- amount-type?
  [code]
  (:amount? (some #(when (= code (:value %)) %) type-options)))


(defn level-summary
  "一级审批的可读描述."
  [level {:keys [users roles]}]
  (let [user-name (fn [id] (or (some #(when (= id (:user_id %)) (or (not-empty (:nick_name %)) (:user_name %))) users) (str id)))
        role-name (fn [k] (or (some #(when (= k (:role_key %)) (:role_name %)) roles) k))]
    (str (:name level) ": "
         (case (:rule level)
           "dept_leader" (if (pos? (or (:up level) 0)) (str "项目所属部门上溯" (:up level) "级的负责人") "项目所属部门负责人")
           "role" (str "角色 " (role-name (:role_key level)) (when (:members_only level) " (限项目成员)"))
           "user" (str/join ", " (map user-name (:user_ids level)))
           (get rule-labels (:rule level) (:rule level)))
         (if (= "all" (:mode level)) " · 会签" " · 或签")
         (when (:min_amount level) (str " · 金额 >= " (:min_amount level)))
         (when (= "skip" (:on_empty level)) " · 无人时跳过"))))


;; ── 策略编辑 ───────────────────────────────────────────────────

(defn- blank-level
  []
  {:key (str (random-uuid)) :name "" :rule "dept_leader" :up 0 :mode "any" :on_empty "reject"})


(defn- level-row
  "一级审批的编辑行."
  [{:keys [level index amount? users roles on-change on-remove]}]
  (let [put! (fn [k v] (on-change (assoc level k v)))]
    [:div {:style {:border "1px solid #e4e8ee" :borderRadius 8 :padding 12 :display "grid" :gap 8}}
     [:div {:style {:display "flex" :gap 8 :alignItems "center" :flexWrap "wrap"}}
      [antd/tag {:color "blue"} (str "第" (inc index) "级")]
      [antd/input {:value (:name level) :placeholder "级别名称, 如: 部门负责人审批" :style {:width 240}
                   :aria-label (str "第" (inc index) "级名称")
                   :onChange #(put! :name (.. % -target -value))}]
      [antd/select {:value (:rule level) :style {:width 180} :aria-label (str "第" (inc index) "级审批人规则")
                    :options (clj->js (mapv (fn [[v l]] {:value v :label l}) rule-labels))
                    :onChange #(on-change (-> level (assoc :rule %) (dissoc :role_key :user_ids :up :members_only)))}]
      (case (:rule level)
        "dept_leader" [antd/select {:value (or (:up level) 0) :style {:width 160} :aria-label "上溯级数"
                                    :options (clj->js (mapv (fn [n] {:value n :label (if (zero? n) "本部门" (str "上溯" n "级"))}) (range 0 6)))
                                    :onChange #(put! :up %)}]
        "role" [:<>
                [antd/select {:value (:role_key level) :style {:width 200} :placeholder "选择角色" :showSearch true
                              :optionFilterProp "label" :aria-label "审批角色"
                              :options (clj->js (mapv (fn [r] {:value (:role_key r) :label (:role_name r)}) roles))
                              :onChange #(put! :role_key %)}]
                [antd/checkbox {:checked (true? (:members_only level)) :onChange #(put! :members_only (.. % -target -checked))}
                 "限项目成员"]]
        "user" [antd/select {:value (clj->js (:user_ids level)) :mode "multiple" :style {:minWidth 260} :placeholder "选择审批人"
                             :optionFilterProp "label" :aria-label "指定审批人"
                             :options (clj->js (w/user-options users))
                             :onChange #(put! :user_ids (vec (js->clj %)))}]
        nil)
      [antd/button {:danger true :type "link" :on-click on-remove} "删除本级"]]
     [:div {:style {:display "flex" :gap 16 :alignItems "center" :flexWrap "wrap"}}
      [antd/radio-group {:value (:mode level) :onChange #(put! :mode (.. % -target -value))}
       [antd/radio {:value "any"} "或签 (任一人通过)"]
       [antd/radio {:value "all"} "会签 (全部通过)"]]
      [antd/select {:value (:on_empty level) :style {:width 200} :aria-label "无审批人时"
                    :options (clj->js [{:value "reject" :label "无审批人时拒绝提交"} {:value "skip" :label "无审批人时跳过本级"}])
                    :onChange #(put! :on_empty %)}]
      (when amount?
        [antd/input {:value (:min_amount level) :placeholder "金额达到 (元) 才需要本级, 留空为总是需要"
                     :style {:width 300} :aria-label "金额条件"
                     :onChange #(put! :min_amount (not-empty (.. % -target -value)))}])]]))


(defn- use-org-options
  "审批规则编辑所需的用户与角色选项."
  []
  (let [pms-options (shared/use-resource "/options" {} [])
        [roles set-roles!] (hooks/use-state [])]
    (hooks/use-effect
      (fn []
        (api/role-options {} #(set-roles! (vec (get-in % [:data :rows]))) (fn [_]))
        js/undefined)
      [])
    {:users (get-in pms-options [:data :users] []) :roles roles}))


(defn policy-editor
  "新建或修订审批策略: 类型, 名称与 1 到 6 级规则."
  [{:keys [record on-close on-saved]}]
  (let [{:keys [users roles] :as org} (use-org-options)
        [code set-code!] (hooks/use-state (or (:code record) "cost-version"))
        [policy-name set-name!] (hooks/use-state (or (:name record) ""))
        [levels set-levels!] (hooks/use-state (if record (mapv #(assoc % :key (str (random-uuid))) (:levels record)) [(blank-level)]))
        {:keys [busy? error run!]} (shared/use-action on-saved)
        amount? (amount-type? code)
        body {:code code :name (if (str/blank? policy-name) (str (type-labels code) "审批") policy-name)
              :levels (mapv #(-> % (dissoc :key) (cond-> (not amount?) (dissoc :min_amount))) levels)}]
    [antd/modal {:title (if record (str "修订审批策略 · " (type-labels code)) "新建审批策略")
                 :open true :width 900 :style {:maxWidth "calc(100vw - 32px)"}
                 :okText "保存为草稿" :cancelText "返回" :confirmLoading busy? :onCancel on-close
                 :onOk #(run! :post (if record (str "/config/approval-policy/" (:id record) "/revisions") "/config/approval-policy")
                              (if record (dissoc body :code) body) "审批策略已保存为草稿")}
     [:p {:style {:color "#718096" :lineHeight 1.8 :marginTop 0}}
      "提交时按顺序逐级审批: 每级按规则找到审批人 (自动排除提交人), 或签任一人通过即进入下一级, 会签需全部通过; 任一人驳回即退回. "
      "保存后为草稿, 发布后对新提交生效; 已发起的审批按发起时的策略继续."]
     (when error [shared/error-panel error nil])
     [:div {:style {:display "flex" :gap 12 :marginBottom 12 :flexWrap "wrap"}}
      [antd/select {:value code :disabled (some? record) :style {:width 180} :aria-label "审批类型"
                    :options (clj->js (mapv #(select-keys % [:value :label]) type-options)) :onChange set-code!}]
      [antd/input {:value policy-name :placeholder "策略名称" :style {:width 320} :aria-label "策略名称"
                   :onChange #(set-name! (.. % -target -value))}]]
     [:div {:style {:display "grid" :gap 10}}
      (doall
        (map-indexed
          (fn [i level]
            ^{:key (:key level)}
            [level-row {:level level :index i :amount? amount? :users users :roles roles
                        :on-change (fn [l] (set-levels! (assoc levels i l)))
                        :on-remove (fn [] (set-levels! (vec (concat (subvec levels 0 i) (subvec levels (inc i))))))}])
          levels))]
     (when (< (count levels) 6)
       [antd/button {:type "dashed" :style {:marginTop 10 :width "100%"} :on-click #(set-levels! (conj levels (blank-level)))}
        "+ 添加一级审批"])
     [:div {:style {:marginTop 12 :color "#475467"}}
      [:div {:style {:fontWeight 600 :marginBottom 4}} "预览"]
      (for [[i l] (map-indexed vector (:levels body))] ^{:key i} [:div (str (inc i) ". " (level-summary l org))])]]))


(defn policy-detail
  [record on-close]
  (let [org (use-org-options)]
    [antd/modal {:title (str (:name record) " / V" (:revision record)) :open true :onCancel on-close :footer nil :width 720}
     [:div {:style {:display "grid" :gap 8}}
      [antd/space [antd/tag {:color "blue"} (type-labels (:code record) (:code record))] [antd/tag (:status record)]]
      (for [[i l] (map-indexed vector (:levels record))] ^{:key i} [:div (str (inc i) ". " (level-summary l org))])
      (when (seq (:history record))
        [:ul {:style {:margin "8px 0 0" :paddingLeft 20 :color "#667085"}}
         (for [h (:history record)] ^{:key (:at h)} [:li (str (:at h) " · " (:action h) " · " (:actor_name h) (when (seq (:reason h)) (str " · " (:reason h))))])])]]))


(defn policy-section
  "模板与规则页 \"审批策略\" 页签: 内置示例, 各类型策略版本与发布/修订/退役."
  [{:keys [data editable? open! refresh!]}]
  (let [org (use-org-options)
        [editor set-editor!] (hooks/use-state nil)]
    [:div {:style {:display "grid" :gap 20}}
     [shared/panel "审批策略" "按公司组织为计划基线, 项目章程, 费用版本, 项目结项配置多级审批; 未发布策略的类型保持 \"提交人指定一位审核人\""
      (when editable?
        [antd/button {:type "primary" :on-click #(set-editor! {:record nil})} "新建审批策略"])
      [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(300px, 1fr))" :gap 12 :marginBottom 16}}
       (for [item (:catalog data)] ^{:key (:code item)}
         [:div {:style {:border "1px solid #e4e8ee" :borderRadius 8 :padding 14}}
          [:div {:style {:fontWeight 600}} (str (:name item) " · " (type-labels (:code item)))]
          [:div {:style {:fontSize 12 :color "#718096" :margin "6px 0"}}
           (for [[i l] (map-indexed vector (:levels item))] ^{:key i} [:div (str (inc i) ". " (level-summary l org))])]
          (when editable?
            [antd/button {:size "small" :on-click #(open! {:title (str "导入示例 " (:name item)) :path "/config/approval-policy/import"
                                                           :initial {:code (:code item)} :fields [{:key :code :label "审批类型" :required? true}]})}
             "导入为草稿"])])]
      [w/record-table (:rows data)
       [{:title "审批类型" :dataIndex "code" :width 110 :render #(type-labels % %)}
        (assoc (w/text-column :revision "版本") :width 64) (assoc (w/text-column :name "策略名称") :width 180)
        {:title "审批级别" :key "levels" :render (fn [_ ^js row]
                                                  (let [levels (js->clj (aget row "levels") :keywordize-keys true)]
                                                    (r/as-element (into [:div {:style {:whiteSpace "normal" :textAlign "left"}}]
                                                                        (map-indexed (fn [i l] [:div {:key i} (str (inc i) ". " (level-summary l org))]) levels)))))}
        {:title "状态" :dataIndex "status" :width 90 :render (fn [s] (r/as-element [antd/tag {:color (case s "published" "green" "retired" "default" "blue")}
                                                                                    (get {"draft" "草稿" "published" "已发布" "retired" "已退役"} s s)]))}]
       (fn [row]
         (let [base (str "/config/approval-policy/" (:id row))]
           [antd/space {:wrap true}
            (when (and editable? (= "draft" (:status row)))
              [w/edit-button "发布" #(open! {:title "发布审批策略" :path (str base "/publish") :fields [{:key :reason :label "发布说明" :type :textarea}]})])
            (when (and editable? (contains? #{"published" "retired"} (:status row)))
              [w/edit-button "修订" #(set-editor! {:record row})])
            (when (and editable? (contains? #{"draft" "published"} (:status row)))
              [w/edit-button "退役" #(open! {:title "退役审批策略" :path (str base "/retire") :fields [{:key :reason :label "原因" :type :textarea :required? true}]})])]))]]
     (when editor
       [policy-editor {:record (:record editor) :on-close #(set-editor! nil)
                       :on-saved (fn [_] (set-editor! nil) (when refresh! (refresh!)))}])]))


;; ── 审批进度与决定 ─────────────────────────────────────────────

(defn flow-steps
  "逐级审批进度."
  [flow]
  [:div {:className "approval-flow" :style {:display "grid" :gap 6}}
   [antd/space {:wrap true}
    [antd/tag {:color (case (:status flow) "approved" "green" "rejected" "red" "cancelled" "default" "processing")}
     (get {"pending" "审批中" "approved" "已通过" "rejected" "已驳回" "cancelled" "已取消"} (:status flow))]
    [:span {:style {:color "#667085"}} (str (:policy_name flow) " · 提交人 " (:submitted_by_name flow))]]
   [antd/steps {:size "small" :current (max 0 (dec (count (take-while #(= "approved" (:status %)) (:levels flow)))))
                :status (case (:status flow) "rejected" "error" "approved" "finish" "process")
                :items (clj->js (mapv (fn [l]
                                        {:title (:level_name l)
                                         :status (case (:status l) "approved" "finish" "rejected" "error" "pending" "process" "wait")
                                         :description (str/join "; " (map #(str (:approver_name %) " " (get step-status-labels (:status %) (:status %))
                                                                              (when (seq (:comment %)) (str " (" (:comment %) ")")))
                                                                         (:steps l)))})
                                      (:levels flow)))}]])


(defn biz-flow
  "某业务对象最近一次审批流的进度 (无审批流时不显示). base 为项目接口前缀 /projects/<id>."
  [base biz-type biz-id]
  (let [resource (shared/use-resource (str base "/approvals") {:biz_type biz-type :biz_id biz-id} [biz-id])
        flow (first (:data resource))]
    (when flow
      [:div {:style {:marginTop 8 :padding 10 :background "#f8fafc" :borderRadius 8}}
       [flow-steps flow]])))


(defn decide-dialog
  "当前级审批人通过或驳回 (驳回需填写意见)."
  [{:keys [item on-close on-saved]}]
  (let [flow (shared/use-resource (str "/approvals/" (:flow_id item)) {} [(:flow_id item)])
        [comment set-comment!] (hooks/use-state "")
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title (str "审批 · " (:type_label item) " · " (:level_name item)) :open true :width 760
                 :onCancel on-close :footer nil :style {:maxWidth "calc(100vw - 32px)"}}
     [:div {:style {:display "grid" :gap 12}}
      [:div [:b (:title item)] [:div {:style {:color "#667085"}} (str (:project_no item) " " (:project_name item) " · 提交人 " (:submitted_by_name item))]]
      (when-let [data (:data flow)] [flow-steps data])
      (when error [shared/error-panel error nil])
      [antd/text-area {:rows 3 :value comment :placeholder "审批意见 (驳回时必填)" :aria-label "审批意见"
                             :onChange #(set-comment! (.. % -target -value))}]
      [antd/space
       [antd/button {:type "primary" :loading busy?
                     :on-click #(run! :post (str "/approvals/" (:flow_id item) "/decide") {:decision "approved" :comment comment} "已通过")}
        "通过"]
       [antd/button {:danger true :loading busy?
                     :on-click #(run! :post (str "/approvals/" (:flow_id item) "/decide") {:decision "rejected" :comment comment} "已驳回")}
        "驳回"]
       [antd/button {:on-click on-close} "返回"]]]]))


(defn use-policies
  "当前发布的审批策略 {类型 -> {:needs_reviewer ..}} (未发布的类型保持单人审核)."
  []
  (let [resource (shared/use-resource "/approval-policies" {} [])]
    (or (:data resource) {})))


(defn use-project-flows
  "项目内某业务类型的审批流 (最新在前); refresh-key 变化时重新读取 (如业务记录状态变化)."
  [base biz-type refresh-key]
  (let [resource (shared/use-resource (str base "/approvals") {:biz_type biz-type} [biz-type refresh-key])]
    {:flows (or (:data resource) []) :refresh! (:refresh! resource)}))


(defn pending-ids
  "进行中的审批流所对应的业务对象编号集合 (原单人审核按钮对其隐藏)."
  [flows]
  (set (keep #(when (= "pending" (:status %)) (:biz_id %)) flows)))


(defn reviewer-fields
  "提交审批时的审核人字段: 未发布策略时必选独立审批人; 策略含 \"提交人选择\" 级别时必选其审批人; 否则不需要."
  [policies biz-type reviewer-field]
  (if-let [p (get policies (keyword biz-type))]
    (if (:needs_reviewer p)
      [(assoc reviewer-field :label "指定审批人 (审批策略 \"提交人选择\" 级别)")]
      [])
    [reviewer-field]))


(defn latest-flow-panel
  "最近一次审批流的进度."
  [flows title]
  (when-let [flow (first flows)]
    [:div {:style {:marginTop 12 :padding 12 :background "#f8fafc" :borderRadius 8}}
     [:div {:style {:fontWeight 600 :marginBottom 6}} (str title " · " (:title flow))]
     [flow-steps flow]]))
