(ns com.ruoyi.domain.pms.approval-chain
  "可配置多级审批链.

   平台配置 \"审批策略\" (approval-policy, 编码 = 业务类型) 发布后, 该类型的提交按策略生成审批流:
   每级按规则解析审批人 (排除提交人), 或签任一人通过即进入下一级, 会签需全部通过; 任一人驳回即驳回;
   末级通过后调用业务适配器完成批准 (与原业务状态机同一事务). 未发布策略的类型保持原 \"提交人指定审核人\" 行为.
   审批流保存发起时的策略快照, 之后修改或退役策略不影响已发起的审批.

   业务适配器由各业务命名空间在加载时登记 (register-adapter!), 避免循环依赖:
   {:finalize (fn [q actor project biz-id decision reason]) :label \"费用版本\"}."
  (:require
    [cheshire.core :as json]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.rules :as rules]))


(defonce ^:private adapters (atom {}))


(defn register-adapter!
  "业务命名空间登记审批完成时的回调."
  [biz-type adapter]
  (swap! adapters assoc biz-type adapter))


(defn policy
  "业务类型当前发布的审批策略; 没有则返回 nil (保持原单人审核)."
  [q biz-type]
  (config/published-by-code q "approval-policy" biz-type))


(defn active-policies
  "全部类型当前发布的策略 (前端据此决定提交时是否需要指定审核人)."
  [svc actor]
  (rules/permit! actor ["pms:project:list" "pms:project:query" "pms:config:list"])
  (let [q (:query-fn svc)]
    (into {} (keep (fn [t] (when-let [p (policy q t)]
                             [t {:id (:id p) :name (:name p) :revision (:revision p)
                                 :levels (:levels p)
                                 :needs_reviewer (boolean (some #(= "submitter_choice" (:rule %)) (:levels p)))}])))
          (keys config/approval-types))))


;; ── 审批人解析 ──────────────────────────────────────────────────

(defn- active-user-id
  [q id]
  (when id (:user_id (q :approval/active-user {:user_id id}))))


(defn- member?
  [q project uid]
  (or (= uid (:manager_id project))
      (some? (q :pms/member {:project_id (:project_id project) :user_id uid}))))


(defn- dept-leader
  "项目所属部门 (上溯 up 级) 的负责人."
  [q dept-id up]
  (loop [dept (q :approval/dept {:dept_id dept-id}) n up]
    (cond
      (nil? dept) nil
      (pos? n) (recur (when (pos? (long (or (:parent_id dept) 0))) (q :approval/dept {:dept_id (:parent_id dept)})) (dec n))
      :else (active-user-id q (:leader_id dept)))))


(defn- level-approvers
  "按规则解析一级的审批人 (有效用户, 去重, 排除提交人)."
  [q project level {:keys [submitter reviewer-id]}]
  (let [ids (case (:rule level)
              "role" (cond->> (map :user_id (q :approval/users-by-role {:role_key (:role_key level)}))
                       (:members_only level) (filter #(member? q project %)))
              "dept_leader" [(dept-leader q (:dept_id project) (or (:up level) 0))]
              "project_manager" [(active-user-id q (:manager_id project))]
              "user" (map #(active-user-id q %) (:user_ids level))
              "submitter_choice" [(active-user-id q reviewer-id)]
              [])]
    (->> ids (remove nil?) (remove #(= % submitter)) distinct vec)))


(defn- level-applies?
  "带金额条件的级别在金额达到阈值时才需要."
  [level amount]
  (or (nil? (:min_amount level))
      (and amount (>= (money/amount! amount "金额") (money/amount! (:min_amount level) "金额条件")))))


(defn plan
  "按策略解析各级审批人, 返回 [{:level_no :level_name :mode :approvers [..]}] (跳过的级别不含).
   某级无审批人: on_empty=reject 拒绝提交, skip 跳过本级."
  [q project policy ctx]
  (let [levels (->> (:levels policy)
                    (map-indexed (fn [i level] (assoc level :level_no (inc i))))
                    (filter #(level-applies? % (:amount ctx)))
                    (keep (fn [level]
                            (let [approvers (level-approvers q project level ctx)]
                              (cond
                                (seq approvers) {:level_no (:level_no level) :level_name (:name level)
                                                 :mode (:mode level) :approvers approvers}
                                (= "skip" (:on_empty level)) nil
                                :else (rules/fail! 409 (str "审批级别\"" (:name level) "\"没有可用审批人"
                                                            (when (= "submitter_choice" (:rule level)) ", 请指定审批人")
                                                            ", 请联系管理员调整审批策略"))))))
                    vec)]
    (when (empty? levels)
      (rules/fail! 409 "审批策略没有可用审批人, 请联系管理员调整审批策略"))
    levels))


;; ── 发起 ────────────────────────────────────────────────────────

(defn pending-flow
  [q biz-type biz-id]
  (q :approval/pending-flow {:biz_type biz-type :biz_id (str biz-id)}))


(defn start!
  "在业务提交的同一事务中按发布的策略发起审批流. 没有发布策略时返回 nil (调用方走原单人审核).
   ctx: {:submitter uid :reviewer-id 提交人选择的审批人 :amount 金额字符串 :title 标题}.
   返回 {:flow_id .. :first-approver uid}."
  [q actor project biz-type biz-id ctx]
  (when-let [p (policy q biz-type)]
    (when (pending-flow q biz-type biz-id) (rules/fail! 409 "该申请已在审批中"))
    (let [ctx (assoc ctx :submitter (:user_id actor))
          levels (plan q project p ctx)
          flow-id (kernel/id)
          first-level (:level_no (first levels))]
      (q :approval/insert-flow! {:flow_id flow-id :project_id (:project_id project) :biz_type biz-type
                                 :biz_id (str biz-id) :title (subs (str (:title ctx)) 0 (min 300 (count (str (:title ctx)))))
                                 :policy_id (:id p) :policy_revision (:revision p)
                                 :policy_json (json/generate-string (select-keys p [:code :name :levels]))
                                 :amount (:amount ctx) :current_level first-level :submitted_by (:user_id actor)
                                 :created_ms (System/currentTimeMillis)})
      (doseq [{:keys [level_no level_name mode approvers]} levels
              approver approvers]
        (q :approval/insert-step! {:step_id (kernel/id) :flow_id flow-id :level_no level_no :level_name level_name
                                   :mode mode :approver_id approver
                                   :status (if (= level_no first-level) "pending" "waiting")}))
      {:flow_id flow-id :first-approver (first (:approvers (first levels)))})))


(defn cancel!
  "业务侧撤销 (如费用版本作废) 时关闭仍在进行的审批流."
  [q biz-type biz-id]
  (when-let [flow (pending-flow q biz-type biz-id)]
    (q :approval/close-open-steps! {:flow_id (:flow_id flow) :status "cancelled"})
    (q :approval/update-flow! {:flow_id (:flow_id flow) :status "cancelled" :current_level (:current_level flow)})))


(defn guard-legacy-decision!
  "有审批流进行中时, 原单人审核接口拒绝直接决定."
  [q biz-type biz-id]
  (when (pending-flow q biz-type biz-id)
    (rules/fail! 409 "该申请按审批策略逐级审批, 请在\"我的待办\"中处理")))


;; ── 查看 ────────────────────────────────────────────────────────

(defn- flow-view
  [q flow]
  (when flow
    (let [steps (q :approval/steps {:flow_id (:flow_id flow)})
          policy (json/parse-string (:policy_json flow) true)]
      (-> flow
          (dissoc :policy_json)
          (assoc :policy_name (:name policy)
                 :type_label (get-in config/approval-types [(:biz_type flow) :label])
                 :levels (->> steps
                              (group-by :level_no)
                              (sort-by key)
                              (mapv (fn [[n rows]]
                                      {:level_no n :level_name (:level_name (first rows)) :mode (:mode (first rows))
                                       :status (cond
                                                 (some #(= "rejected" (:status %)) rows) "rejected"
                                                 (some #(= "pending" (:status %)) rows) "pending"
                                                 (every? #(= "waiting" (:status %)) rows) "waiting"
                                                 (some #(= "approved" (:status %)) rows) "approved"
                                                 :else (:status (first rows)))
                                       :steps (mapv #(select-keys % [:step_id :approver_id :approver_name :status :comment :decided_at])
                                                    rows)}))))))))


(defn- viewer!
  "项目读者或审批参与人可查看审批流."
  [q actor flow]
  (let [project (q :pms/project {:project_id (:project_id flow)})]
    (rules/access! q actor project false)
    project))


(defn project-flows
  "项目内的审批流 (可按业务过滤), 含逐级进度."
  [svc actor project-id {:keys [biz_type biz_id]}]
  (let [q (:query-fn svc)]
    (rules/access! q actor (q :pms/project {:project_id project-id}) false)
    (->> (q :approval/project-flows {:project_id project-id})
         (filter #(or (nil? biz_type) (= biz_type (:biz_type %))))
         (filter #(or (nil? biz_id) (= (str biz_id) (:biz_id %))))
         (mapv #(flow-view q %)))))


(defn flow
  [svc actor flow-id]
  (let [q (:query-fn svc)
        f (q :approval/flow {:flow_id flow-id})]
    (when-not f (rules/fail! 404 "审批不存在"))
    (viewer! q actor f)
    (flow-view q f)))


(defn my-pending
  "当前用户待处理的审批步骤 (统一待办)."
  [q actor]
  (mapv #(assoc % :type_label (get-in config/approval-types [(:biz_type %) :label]))
        (q :approval/my-pending {:user_id (:user_id actor)})))


;; ── 决定 ────────────────────────────────────────────────────────

(defn- level-done?
  [steps level-no mode]
  (let [rows (filter #(= level-no (:level_no %)) steps)]
    (if (= "all" mode)
      (every? #(= "approved" (:status %)) rows)
      (some #(= "approved" (:status %)) rows))))


(defn decide!
  "当前级审批人通过或驳回. 驳回必须填写意见; 末级通过后调用业务适配器完成批准.
   提交人不能审批, 只有当前级的待处理审批人可以决定 (管理员也不能代批)."
  [svc actor flow-id body]
  (rules/object! body [:decision :comment])
  (let [decision (:decision body)]
    (when-not (contains? #{"approved" "rejected"} decision) (rules/fail! 400 "无效审批决定"))
    (kernel/transaction! svc
      (fn [q]
        (let [f (q :approval/flow {:flow_id flow-id})
              _ (when-not f (rules/fail! 404 "审批不存在"))
              _ (when-not (= "pending" (:status f)) (rules/fail! 409 "审批已结束"))
              project (q :pms/project {:project_id (:project_id f)})
              uid (:user_id actor)
              _ (when (= uid (:submitted_by f)) (rules/fail! 403 "提交者不能审批自己的申请"))
              steps (q :approval/steps {:flow_id flow-id})
              mine (first (filter #(and (= uid (:approver_id %)) (= "pending" (:status %))
                                        (= (:current_level f) (:level_no %))) steps))
              _ (when-not mine (rules/fail! 403 "只有当前级的审批人可以作出此决定"))
              comment (rules/text! (:comment body) "审批意见" 1000 (= "rejected" decision))
              adapter (get @adapters (:biz_type f))
              _ (when-not adapter (rules/fail! 500 "审批业务未登记"))]
          (rules/changed! (q :approval/decide-step! {:step_id (:step_id mine) :status decision :comment comment}))
          (rules/changed! (q :pms/touch-project! project))
          (let [steps (q :approval/steps {:flow_id flow-id})
                level (:current_level f)
                finish! (fn [status]
                          (q :approval/close-open-steps! {:flow_id flow-id :status (if (= "approved" status) "skipped" "cancelled")})
                          (rules/changed! (q :approval/update-flow! {:flow_id flow-id :status status :current_level level}))
                          ((:finalize adapter) q actor project (:biz_id f) status comment))
                result (cond
                         (= "rejected" decision)
                         (do (finish! "rejected") "rejected")

                         (level-done? steps level (:mode mine))
                         (let [next-level (->> steps (map :level_no) (filter #(> % level)) sort first)]
                           (q :approval/set-level-status! {:flow_id flow-id :level_no level :status "skipped" :from_status "pending"})
                           (if next-level
                             (do (q :approval/set-level-status! {:flow_id flow-id :level_no next-level :status "pending" :from_status "waiting"})
                                 (rules/changed! (q :approval/update-flow! {:flow_id flow-id :status "pending" :current_level next-level}))
                                 "pending")
                             (do (finish! "approved") "approved")))

                         :else "pending")]
            (kernel/event! q actor project (str "approval." (:biz_type f) "." decision)
                           {:reason comment :result {:id flow-id :status result :decision decision}})
            (flow-view q (q :approval/flow {:flow_id flow-id}))))))))

