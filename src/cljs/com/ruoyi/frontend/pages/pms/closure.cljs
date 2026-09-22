(ns com.ruoyi.frontend.pages.pms.closure
  "结项检查,交付移交与独立关闭审批工作台."
  (:require [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.governance-forms :as forms]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.hooks :as hooks]))

(defn- evidence-field
  "结项证据必须选择一个已保存的不可变文档版本."
  [documents]
  {:key :evidence_ref :label "交付证据版本" :type :select :required? true
   :options (mapv #(hash-map :value (:id %) :label (str (:code %) " / " (:title %) " V" (:revision %))) documents)})

(defn- blocker-panel
  "明确显示服务端尚未通过的结项条件."
  [model]
  [shared/panel "结项准入检查" "检查任务,质量Gate,问题,工时,决算与正式移交" nil
   (if (seq (:blockers model))
     [:ul {:style {:margin 0 :paddingLeft 20 :lineHeight 2 :color "#b76537"}}
      (for [blocker (:blockers model)] ^{:key blocker} [:li blocker])]
     [:div {:style {:color "#45846c"}} "当前结项前置检查已通过."])])

(defn- checklist
  "逐条检查交付完成情况并绑定证据版本."
  [{:keys [base model documents editable? open!]}]
  [shared/panel "收尾检查清单" "必需检查项完成后才能提交正式关闭审批"
   (when editable? [antd/button {:on-click #(open! {:title "添加收尾检查项" :path (str base "/checks")
                                                   :transform (fn [data] (assoc data :required true))
                                                   :fields [{:key :title :label "检查项" :required? true}]})} "添加检查项"])
   [w/record-table (:checks model) [(w/text-column :title "检查项") (w/state-column) (w/text-column :comment "完成说明")]
    (when editable? (fn [row]
                     (when-not (contains? #{"done" "completed"} (:status row))
                       [w/edit-button "确认完成" #(open! {:title "完成收尾检查" :path (str base "/checks/" (:id row) "/complete")
                                                        :fields [(evidence-field documents) {:key :comment :label "完成说明" :type :textarea :required? true}]})]))) ]])

(defn- handoffs
  "移交事项保留负责人,到期日和签收证据."
  [{:keys [base model options documents editable? open!]}]
  [shared/panel "交付移交" "每项移交均需实际证据确认完成"
   (when editable? [antd/button {:on-click #(open! {:title "新增交付移交" :path (str base "/handoffs")
                                                   :fields [{:key :title :label "移交事项" :required? true}
                                                            (forms/owner-field (:users options))
                                                            {:key :due_date :label "计划移交日期" :type :date :required? true}]})} "新增移交事项"])
   [w/record-table (:handoffs model) [(w/text-column :title "移交事项") (w/text-column :due_date "计划日期") (w/state-column)]
    (when editable? (fn [row]
                     (when (and (= (:owner_id row) (:currentUserId options)) (not (contains? #{"done" "completed"} (:status row))))
                       [w/edit-button "确认移交" #(open! {:title "确认交付移交" :path (str base "/handoffs/" (:id row) "/complete")
                                                        :fields [(evidence-field documents) {:key :comment :label "签收说明" :type :textarea :required? true}]})]))) ]])

(defn- lessons
  "保留有内容的经验复盘,服务下一次交付."
  [{:keys [base model editable? open!]}]
  [shared/panel "经验复盘" "记录实际经验,原因和改进办法"
   (when editable? [antd/button {:on-click #(open! {:title "登记项目经验" :path (str base "/lessons")
                                                   :fields [{:key :title :label "经验主题" :required? true}
                                                            {:key :category :label "经验类别" :required? true}
                                                            {:key :content :label "经验与改进建议" :type :textarea :required? true}]})} "登记项目经验"])
   [w/record-table (:lessons model) [(w/text-column :title "主题") (w/text-column :category "类别") (w/text-column :content "经验内容")] nil]])

(defn- approval
  "所有前置项通过后提交独立结项审批."
  [{:keys [base model options project editable? approve? open!]}]
  (let [record (:approval model) current (:currentUserId options)]
    [shared/panel "正式关闭审批" "批准后项目经理可执行关闭,审批本身不隐式改变生命周期" nil
     [w/badge (:status record)]
     (when (:review_note record) [:p (:review_note record)])
     [antd/space
      (when (and editable? (= "closing" (:status project)) (empty? (:blockers model))
                 (not (contains? #{"submitted" "approved"} (:status record))))
        [antd/button {:type "primary" :on-click #(open! {:title "提交项目关闭审批" :path (str base "/submit")
                                                        :fields [(forms/reviewer-field options)]})} "提交关闭审批"])
      (when (and approve? (= "closing" (:status project)) (= "submitted" (:status record)) (= current (:reviewer_id record)) (not= current (:submitted_by record)))
        [:<>
         [antd/button {:type "primary" :on-click #(open! (forms/decision-dialog (str base "/review") "approved" "批准项目关闭"))} "批准关闭"]
         [antd/button {:danger true :on-click #(open! (forms/decision-dialog (str base "/review") "rejected" "驳回项目关闭"))} "驳回关闭"]])]]))

(defn- reopen-panel
  "关闭后通过明确修正范围和独立决定受控重开."
  [{:keys [root model options project reopen? open!]}]
  (let [record (:reopen_request model) current (:currentUserId options)
        closed? (= "closed" (:status project))]
    (when (or closed? record)
      [shared/panel "受控重开" "批准后返回收尾,历史归档仍保留,再次关闭须重新审批" nil
       (when record [:div [w/badge (:status record)] [:p (str "申请原因: " (:reason record))]
                      [:p (str "修正范围: " (:scope record))] [:p (:review_note record)]])
       (when (and closed? reopen? (not= "submitted" (:status record)))
         [antd/button {:on-click #(open! {:title "申请受控重开" :path (str root "/reopen-requests")
                                          :fields [{:key :reason :label "重开原因" :type :textarea :required? true}
                                                   {:key :scope :label "修正范围" :type :textarea :required? true}
                                                   (forms/reviewer-field options)]})} "申请受控重开"])
       (when (and closed? reopen? (= "submitted" (:status record))
                  (= current (:reviewer_id record)) (not= current (:submitted_by record)))
         [antd/space
          [antd/button {:type "primary" :on-click #(open! (forms/decision-dialog
            (str root "/reopen-requests/" (:request_id record) "/review") "approved" "批准项目重开"))} "批准重开"]
          [antd/button {:danger true :on-click #(open! (forms/decision-dialog
            (str root "/reopen-requests/" (:request_id record) "/review") "rejected" "驳回项目重开"))} "驳回重开"]])])) )

(defn- closure-content
  "按检查,移交,复盘与正式审批组织结项."
  [context]
  [:div {:style {:display "grid" :gap 20}}
   [blocker-panel (:model context)] [checklist context] [handoffs context]
   [lessons context] [approval context] [reopen-panel context]])

(defn closure-workspace
  "基于真实读模型维护结项证据与关闭决策."
  [project revision options changed!]
  (let [root (str "/projects/" (:project_id project)) base (str root "/closure")
        resource (shared/use-resource base {} [revision])
        governance (shared/use-resource (str root "/governance") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil)
        context {:root root :base base :model (:data resource) :documents (get-in governance [:data :documents])
                 :project project :options options :open! set-dialog!
                 :editable? (and (shared/use-permission "pms:project:edit") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
                 :reopen? (shared/use-permission "pms:project:reopen")
                 :approve? (shared/use-permission "pms:project:close")}]
    [:div
     [w/resource-view resource (fn [_] [closure-content context])]
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                   :on-saved (fn [_] (set-dialog! nil) (changed!))})])]))
