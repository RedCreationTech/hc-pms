(ns com.ruoyi.pms-finance-test
  "验证真实数据库中的精确成本,工时审批和完整生命周期闭环."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.approval-chain :as chain]
    [com.ruoyi.domain.pms.closure :as closure]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance :as finance]
    [com.ruoyi.domain.pms.finance-allocation :as allocation]
    [com.ruoyi.domain.pms.finance-budget :as budget]
    [com.ruoyi.domain.pms.finance-cost :as cost]
    [com.ruoyi.domain.pms.finance-forecast :as forecast]
    [com.ruoyi.domain.pms.finance-money :as money]
    [com.ruoyi.domain.pms.finance-time :as time]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.lifecycle :as lifecycle]
    [com.ruoyi.domain.pms.planning :as plan]
    [com.ruoyi.domain.pms.reopen :as reopen]
    [com.ruoyi.domain.pms.service :as pms]
    [com.ruoyi.pms-delivery-scenario :as scenario]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]))


(def ^:dynamic *svc* nil)


(defn- fixture
  "在隔离SQLite或CI的MySQL中迁移并建立专属审批账户."
  [f]
  (let [provided (System/getenv "PMS_TEST_JDBC_URL")
        file (when-not provided (java.io.File/createTempFile "pms-finance-" ".db"))
        url (or provided (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9400,'Finance test','finance-test',40,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9400,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
      (doseq [id [9401 9402 9403]]
        (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(?,1,?,?,'0','0')" id (str "fin-" id) (str "财务测试" id)])
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9400)" id]))
      (let [files (->> (.listFiles (io/file "resources/sql"))
                       (filter #(re-matches #"pms.*\.sql" (.getName %)))
                       (map #(str "sql/" (.getName %))) sort)
            queries (:fns (apply conman/bind-connection-map db {} files))
            query (fn
                    ([k p] ((get-in queries [k :fn]) p))
                    ([tx k p] ((get-in queries [k :fn]) tx p)))]
        (binding [*svc* {:db db :query-fn query}] (f)))
      (finally (when file (.delete file))))))


(use-fixtures :once fixture)


(defn- actor
  "读取实际有效身份和权限."
  [id]
  (pms/actor *svc* {:user-id id}))


(defn- version
  "读取当前项目版本."
  [id]
  (:version ((:query-fn *svc*) :pms/project {:project_id id})))


(defn- error-status
  "提取业务错误状态."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- error-info
  "提取业务错误状态与文案, 成功时返回 nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- command!
  "以指定成员携带真实版本执行业务命令."
  ([f id args body] (command! 9401 f id args body))
  ([uid f id args body]
   (apply f *svc* (actor uid) id (concat args [(assoc body :version (version id))]))))


(defn- project!
  "创建项目并设置审批人和普通成员,不直接改生命周期状态."
  []
  (let [p (pms/create-project! *svc* (actor 9401)
                               {:project_no (str "FIN-" (kernel/id)) :name "生命周期验收"
                                :manager_id 9401 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id p)]
    (pms/set-member! *svc* (actor 9401) id {:user_id 9402 :role "viewer"})
    (pms/set-member! *svc* (actor 9401) id {:user_id 9403 :role "editor"})
    id))


(defn- transition!
  "通过受控命令推进生命周期."
  [id status]
  (command! pms/transition-project! id [] {:status status :reason "验收流程"}))


(defn- task!
  "创建可以报工和验收的实际WBS任务."
  [id code]
  (:result (command! plan/create-task! id [] {:wbs_code code :name code :duration_days 1 :owner_id 9401})))


(defn- gov!
  "调用治理的固定业务命令."
  ([id resource action rid body] (gov! 9401 id resource action rid body))
  ([uid id resource action rid body]
   (:result (command! uid gov/command! id [resource action rid] body))))


(defn- document!
  "创建真实不可变验收记录."
  [id]
  (gov! id :documents :create nil {:code (kernel/id) :title "验收记录" :filename "proof.txt" :content "实际测试通过\n"}))


(defn- gate!
  "从模板经检查和独立审批完成一个阶段Gate."
  [id stage document-id]
  (let [template (gov! id :gate-templates :create nil
                       {:code stage :title (str stage "审查") :stage stage :required true
                        :checks [{:code "proof" :title "实际证据" :required true}]})
        gate (gov! id :gates :create nil {:template_id (:id template) :title "质量审查" :reviewer_id 9402})]
    (gov! id :gates :checks (:id gate) {:checks [{:code "proof" :passed true :evidence_ids [document-id]}]})
    (gov! id :gates :submit (:id gate) {})
    (gov! 9402 id :gates :decision (:id gate) {:decision "approved" :reason "已独立检查证据"})))


(defn- execution-project!
  "真实通过章程,计划基线和Gate后进入执行,不绕过任何生命周期校验."
  []
  (let [id (project!) _ (transition! id "initiated") _ (transition! id "planning")
        task (task! id "1") document (document! id)
        charter (gov! id :charters :create nil
                      {:title "交付章程" :objective "验收交付" :scope "设备" :success_criteria "通过试验" :sponsor_id 9403})]
    (gov! id :charters :submit (:id charter) {:reviewer_id 9402})
    (gov! 9402 id :charters :decision (:id charter) {:decision "approved" :reason "范围明确"})
    (gate! id "execution" (:id document))
    (let [baseline (:result (command! plan/submit-plan! id [] {:comment "发布基线"}))]
      (command! 9402 plan/review-plan! id [(:baseline_id baseline)] {:decision "approved" :comment "已核验"}))
    (transition! id "execution")
    {:id id :task task :document document}))


(defn- time!
  "提交真实任务工时并可选执行独立审批."
  [id task hours date approve?]
  (let [entry (:result (command! time/submit! id []
                                 {:task_id (:task_id task) :work_date date :hours hours :note "实际设计工作" :reviewer_id 9402}))]
    (when approve? (command! 9402 time/review! id [(:id entry)] {:decision "approved" :reason "工作已核验"}))
    entry))


(defn- cost!
  "建立明确期间和币种的成本版本."
  [id kind]
  (:result (command! cost/create! id [] {:kind kind :period "2026-09" :currency "CNY"
                                         :name "九月成本" :revenue "100.00" :reviewer_id 9402})))


(defn- approve-cost!
  "提交已有成本版本并独立批准."
  [id cid]
  (command! cost/submit! id [cid] {})
  (command! 9402 cost/review! id [cid] {:decision "approved" :reason "核对完成"}))


(deftest exact-money-and-conservation
  (is (= 29 (money/amount! "0.29" "成本")))
  (is (= "-0.01" (money/money -1)))
  (is (= 400 (error-status #(money/amount! "1.001" "成本"))))
  (is (= 400 (error-status #(money/minutes! "0.01"))))
  (is (= 409 (error-status #(money/distribute 100 []))))
  (let [rows (money/distribute 100 [{:task_id "c" :minutes 1} {:task_id "a" :minutes 1} {:task_id "b" :minutes 1}])]
    (is (= 100 (reduce + (map :amount_minor rows))))
    (is (= {"a" 34 "b" 33 "c" 33} (into {} (map (juxt :task_id :amount_minor) rows))))))


(deftest costs-are-immutable-and-review-is-independent
  (let [id (project!) v (cost! id "budget") cid (:id v)]
    (command! cost/add-entry! id [cid] {:category "material" :label "原料" :amount "0.29" :source_ref "ERP-1"})
    (is (= 409 (error-status #(command! cost/add-entry! id [cid] {:category "material" :label "重复" :amount "1.00" :source_ref "ERP-1"}))))
    (command! cost/submit! id [cid] {})
    (is (= 403 (error-status #(command! cost/review! id [cid] {:decision "approved" :reason "自审"}))))
    (is (= 403 (error-status #(command! 9403 cost/review! id [cid] {:decision "approved" :reason "代审"}))))
    (command! 9402 cost/review! id [cid] {:decision "approved" :reason "通过"})
    (is (= 409 (error-status #(command! cost/add-entry! id [cid] {:category "other" :label "覆盖" :amount "1"}))))
    (let [copy (:result (command! cost/revise! id [cid] {:reviewer_id 9402}))
          readmodel (finance/overview *svc* (actor 9401) id)]
      (is (= 2 (:version_no copy)))
      (is (= "0.29" (:total copy)))
      (is (= "0.29" (get-in readmodel [:summary "budget" :amount]))))
    (is (= 403 (error-status #(finance/overview *svc* (update (actor 9403) :permissions disj "pms:finance:query") id))))))


(deftest allocation-is-idempotent-and-conserves-real-costs
  (let [{:keys [id task]} (execution-project!) task-b (task! id "2") v (cost! id "actual")
        body {:amount "1.01" :from_date "2026-09-01" :to_date "2026-09-22" :idempotency_key "pool-1" :label "研发池"}]
    (is (= 409 (error-status #(command! allocation/allocate! id [(:id v)] body))))
    (time! id task "1" "2026-09-01" true)
    (time! id task-b "2" "2026-09-01" true)
    (let [a (:result (command! allocation/allocate! id [(:id v)] body))
          b (:result (command! allocation/allocate! id [(:id v)] body))
          version (first (:cost_versions (finance/overview *svc* (actor 9401) id)))]
      (is (= (:id a) (:id b)))
      (is (= 101 (reduce + (map :amount_minor (:rows a)))))
      (is (= 2 (count (:entries version))))
      (is (= "1.01" (:total version)))
      (is (= 409 (error-status #(command! allocation/allocate! id [(:id v)] (assoc body :amount "2")))))
      (is (= 409 (error-status #(command! cost/delete-entry! id [(:id v) (:id (first (:entries version)))] {})))))))


(deftest day-cap-is-global-and-rejection-releases-reservation
  (let [a (execution-project!) b (execution-project!) date "2026-09-02"
        entry (time! (:id a) (:task a) "20" date false)]
    (is (= 409 (error-status #(time! (:id b) (:task b) "5" date false))))
    (is (= 403 (error-status #(command! time/review! (:id a) [(:id entry)] {:decision "approved"}))))
    (command! 9402 time/review! (:id a) [(:id entry)] {:decision "rejected" :reason "填写错误"})
    (is (= "submitted" (:status (time! (:id b) (:task b) "5" date false))))
    (is (empty? (:rows (finance/times *svc* (actor 9403) (:id b)))))
    (is (= 400 (error-status #(command! time/submit! (:id a) [] {:task_id (:task_id (:task b)) :work_date date :hours "1" :note "越界" :reviewer_id 9402}))))))


(deftest financial-write-and-audit-roll-back-together
  (let [id (project!) cid (:id (cost! id "estimate")) before (version id) query (:query-fn *svc*)
        failing (assoc *svc* :query-fn (fn
                                         ([k p] (query k p))
                                         ([tx k p] (when (= k :pms/insert-event!) (throw (ex-info "audit down" {})))
                                                   (query tx k p))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"audit down"
          (cost/add-entry! failing (actor 9401) id cid {:version before :category "labor" :label "设计" :amount "12.34"})))
    (is (= before (version id)))
    (is (empty? (:entries (first (:cost_versions (finance/overview *svc* (actor 9401) id))))))
    (is (= 409 (error-status #(cost/add-entry! *svc* (actor 9401) id cid {:version (dec before) :category "labor" :label "旧版本" :amount "1"}))))))


(defn- closure-ready-project!
  "从实际执行完成到收尾,填入独立财务及质量证据."
  []
  (let [{:keys [id task document] :as context} (execution-project!)
        cid (:id (cost! id "settlement"))
        requirement (gov! id :requirements :create nil
                          {:code "URS-DELIVERY" :text "交付符合验收准则" :category "交付" :priority "required" :owner_id 9401})]
    (scenario/complete-delivery! *svc* 9401 9402 id (:task_id task) (:id document) (:id requirement))
    (command! plan/task-feedback! id [(:task_id task)] {:status "done" :percent_complete 100 :remaining_days 0})
    (transition! id "closing")
    (gate! id "closure" (:id document))
    (command! cost/add-entry! id [cid] {:category "material" :label "决算" :amount "80"})
    (approve-cost! id cid)
    (let [item (:result (command! closure/create-item! id ["check"] {:title "交付资料归档" :required true}))]
      (command! closure/complete-item! id ["check" (:id item)] {:evidence_ref (:id document) :comment "已确认归档"}))
    context))


(deftest real-lifecycle-requires-all-evidence-and-independent-close
  (let [empty-id (project!)]
    (transition! empty-id "initiated")
    (transition! empty-id "planning")
    (is (= 409 (error-status #(transition! empty-id "execution")))))
  (let [{:keys [id]} (closure-ready-project!)]
    (is (:ready (closure/overview *svc* (actor 9403) id)))
    (is (= 409 (error-status #(transition! id "closed"))))
    (command! closure/submit! id [] {:reviewer_id 9402})
    (is (= 403 (error-status #(command! closure/review! id [] {:decision "approved" :reason "自审"}))))
    (command! 9402 closure/review! id [] {:decision "approved" :reason "独立批准关闭"})
    (is (= "closed" (:status (transition! id "closed"))))
    (is (= 409 (error-status #(command! cost/create! id [] {:kind "actual"}))))))


;; batch52 (mutator 方向: cover the uncovered): closure 守卫 — 必需标记400, 移交接收人403, 重复审批409, 审批时阻塞项409.
(deftest closure-item-and-review-guards
  (testing "收尾事项的必需标记必须是布尔值400"
    (let [id (project!)]
      (is (= 400 (error-status #(command! closure/create-item! id ["check"]
                                          {:title "标记校验" :required "yes"}))))))
  (testing "非指定接收人不能确认遗留移交403, 接收人本人可以"
    (let [id (project!)
          document (document! id)
          item (:result (command! closure/create-item! id ["handoff"]
                                  {:title "资料移交" :owner_id 9403 :due_date "2026-10-01"}))]
      (is (= 403 (error-status #(command! closure/complete-item! id ["handoff" (:id item)]
                                          {:evidence_ref (:id document) :comment "我来确认"}))))
      (is (= "completed" (:status (:result (command! 9403 closure/complete-item! id ["handoff" (:id item)]
                                                     {:evidence_ref (:id document) :comment "接收确认"})))))))
  (testing "关闭申请已审批后不能再次审批409"
    (let [{:keys [id]} (closure-ready-project!)]
      (command! closure/submit! id [] {:reviewer_id 9402})
      (command! 9402 closure/review! id [] {:decision "approved" :reason "通过"})
      (is (= 409 (error-status #(command! 9402 closure/review! id [] {:decision "approved" :reason "重复审批"}))))))
  (testing "审批通过前若新增了未完成必需项则被收尾缺口阻塞409"
    (let [{:keys [id]} (closure-ready-project!)]
      (command! closure/submit! id [] {:reviewer_id 9402})
      (command! closure/create-item! id ["check"] {:title "审批中新增必需项" :required true})
      (is (= 409 (error-status #(command! 9402 closure/review! id [] {:decision "approved" :reason "尚有缺口"})))))))


(deftest approved-closure-snapshot-cannot-be-reused-after-change
  (let [{:keys [id]} (closure-ready-project!)]
    (command! closure/submit! id [] {:reviewer_id 9402})
    (command! 9402 closure/review! id [] {:decision "approved" :reason "通过"})
    (command! closure/create-lesson! id [] {:title "新增依据" :category "交付" :content "完成经验复盘"})
    (is (= 409 (error-status #(transition! id "closed"))))
    (command! closure/submit! id [] {:reviewer_id 9402})
    (command! 9402 closure/review! id [] {:decision "approved" :reason "新快照通过"})
    (is (= "closed" (:status (transition! id "closed"))))))


(deftest revoked-creator-and-paused-project-cannot-write
  (let [id (project!)]
    (pms/update-project! *svc* (actor 9401) id {:version (version id) :manager_id 9403})
    (command! 9403 lifecycle/remove-member! id [9401] {})
    (is (= 403 (error-status #(pms/project *svc* (actor 9401) id))))
    (is (= 403 (error-status #(finance/overview *svc* (actor 9401) id)))))
  (let [{:keys [id]} (execution-project!)]
    (transition! id "paused")
    (is (= 409 (error-status #(command! cost/create! id [] {:kind "actual"}))))
    (is (= "execution" (:status (transition! id "execution"))))))


(deftest closed-project-reopening-requires-new-independent-close
  (let [{:keys [id]} (closure-ready-project!)]
    (command! closure/submit! id [] {:reviewer_id 9402})
    (command! 9402 closure/review! id [] {:decision "approved" :reason "初次关闭"})
    (transition! id "closed")
    (let [request (:result (command! reopen/request! id []
                                     {:reviewer_id 9402 :scope "纠正交付归档" :reason "客户提出资料更正"}))]
      (is (= 409 (error-status #(command! reopen/request! id []
                                          {:reviewer_id 9402 :scope "重复申请" :reason "已有待审批申请"}))))
      (is (= 403 (error-status #(command! reopen/review! id [(:id request)] {:decision "approved" :reason "自审"}))))
      (command! 9402 reopen/review! id [(:id request)] {:decision "approved" :reason "同意受控更正"})
      (is (= "closing" (:status (pms/project *svc* (actor 9401) id))))
      (is (= 409 (error-status #(transition! id "closed"))))
      (command! closure/create-lesson! id [] {:title "更正完成" :category "交付" :content "归档资料复核完成"})
      (command! closure/submit! id [] {:reviewer_id 9402})
      (command! 9402 closure/review! id [] {:decision "approved" :reason "重新确认新依据"})
      (is (= "closed" (:status (transition! id "closed")))))))


(deftest closed-project-exposes-derived-archive-state
  (let [{:keys [id]} (closure-ready-project!)
        archive #(get-in (closure/overview *svc* (actor 9403) id) [:archive])]
    (testing "收尾中未归档: 归档时间空, 关闭审批计数从提交开始累计"
      (is (= {:closed false :archived false :archived-at nil}
             (select-keys (archive) [:closed :archived :archived-at])))
      (command! closure/submit! id [] {:reviewer_id 9402})
      (is (= {"total" 1 "submitted" 1 "approved" 0 "rejected" 0}
             (into {} (map (fn [[k v]] [(name k) v]) (:approvals (archive))))))
      (is (= "submitted" (:approval-status (archive))))
      (command! 9402 closure/review! id [] {:decision "approved" :reason "独立批准关闭"}))
    (testing "正式关闭后归档成立, 归档时间来自生命周期状态"
      (transition! id "closed")
      (let [a (archive)]
        (is (true? (:closed a)))
        (is (true? (:archived a)))
        (is (string? (:archived-at a)))
        (is (= {"total" 1 "submitted" 0 "approved" 1 "rejected" 0}
               (into {} (map (fn [[k v]] [(name k) v]) (:approvals a)))))
        (is (zero? (:total (:reopens a))))
        (is (false? (:reopened a)))))
    (testing "受控重开清除归档时间, 历届关闭审批与重开申请全部保留"
      (let [request (:result (command! reopen/request! id []
                                       {:reviewer_id 9402 :scope "纠正交付归档" :reason "客户提出资料更正"}))]
        (command! 9402 reopen/review! id [(:id request)] {:decision "approved" :reason "同意受控更正"})
        (let [a (archive)]
          (is (= "closing" (:status a)))
          (is (false? (:archived a)))
          (is (nil? (:archived-at a)))
          (is (true? (:reopened a)))
          (is (= {:total 1 :approved 1 :submitted 0 :rejected 0}
                 (select-keys (:reopens a) [:total :approved :submitted :rejected])))
          (is (= "approved" (:latest-status (:reopens a))))
          (is (= "客户提出资料更正" (:latest-reason (:reopens a))))
          (is (= 1 (get-in a [:approvals :total])))))
      (command! closure/submit! id [] {:reviewer_id 9402})
      (command! 9402 closure/review! id [] {:decision "approved" :reason "重新确认新依据"})
      (transition! id "closed")
      (let [a (archive)]
        (is (true? (:archived a)))
        (is (= 2 (get-in a [:approvals :total])))
        (is (true? (:reopened a)))
        (is (pos? (long (:reopened-version a))))))))


(deftest terminal-project-stays-read-only-for-participants
  (let [{:keys [id]} (closure-ready-project!)]
    (command! closure/submit! id [] {:reviewer_id 9402})
    (command! 9402 closure/review! id [] {:decision "approved" :reason "独立批准关闭"})
    (transition! id "closed")
    (let [base (get-in (closure/overview *svc* (actor 9403) id) [:progress])]
      (testing "终态项目参与者仍可只读查看收尾工作台, 归档摘要一致"
        (let [after (closure/overview *svc* (actor 9403) id)]
          (is (= "closed" (:status (pms/project *svc* (actor 9403) id))))
          (is (true? (get-in after [:archive :archived])))
          (is (= "approved" (get-in after [:readiness :approval-state])))
          (is (= base (:progress after)))))
      (testing "只读审批看到终态时给出明确的没有待审申请, 而不是写入锁"
        (is (= [409 "没有待处理的关闭申请"]
               (error-info #(command! 9402 closure/review! id [] {:decision "approved" :reason "重复"})))))
      (testing "真正的写入仍被终态锁拒绝"
        (is (= 409 (error-status #(command! closure/create-item! id ["check"] {:title "关闭后追加"}))))
        (is (= 409 (error-status #(command! closure/create-lesson! id [] {:title "关闭后经验" :category "交付" :content "x"}))))
        (is (= 409 (error-status #(transition! id "execution"))))))))


(deftest pending-financial-reviewer-cannot-be-removed
  (let [id (project!) cost-version (cost! id "actual")]
    (is (= 409 (error-status #(command! lifecycle/remove-member! id [9402] {}))))
    (command! cost/cancel! id [(:id cost-version)] {:reason "本次不再提交"})
    (is (= "removed" (get-in (command! lifecycle/remove-member! id [9402] {}) [:result :status])))))


(deftest zero-revenue-profit-and-financial-audit-privacy
  (let [id (project!) version (:result (command! cost/create! id []
                                                 {:kind "actual" :period "2026-09" :currency "CNY" :name "无收入成本" :revenue "0" :reviewer_id 9402}))
        cid (:id version)]
    (command! cost/add-entry! id [cid] {:category "other" :label "明确成本" :amount "100"})
    (command! cost/submit! id [cid] {})
    (command! 9402 cost/review! id [cid] {:decision "approved" :reason "SENSITIVE_FINANCE_NOTE"})
    (is (= "-100.00" (:margin (first (:cost_versions (finance/overview *svc* (actor 9401) id))))))
    (is (not (.contains (json/generate-string (pms/events *svc* (actor 9403) id)) "SENSITIVE_FINANCE_NOTE"))))
  (let [id (project!) version (cost! id "settlement")]
    (command! cost/add-entry! id [(:id version)] {:category "other" :label "本项目零费用确认" :amount "0"})
    (is (= "approved" (get-in (approve-cost! id (:id version)) [:result :status])))))


;; batch55 (mutator 方向: cover the uncovered): cost 版本四门 — 口径400, 未知版本404, 删除明细成功, 非草稿取消409.
(deftest cost-version-lifecycle-gates
  (let [id (project!)
        version (:result (command! cost/create! id [] {:kind "estimate" :period "2026-11" :currency "CNY"
                                                       :name "十一月估算" :revenue "100.00" :reviewer_id 9402}))]
    (testing "费用口径受控400"
      (is (= 400 (error-status #(command! cost/create! id [] {:kind "weird" :period "2026-11" :currency "CNY"
                                                              :name "坏口径" :revenue "0" :reviewer_id 9402})))))
    (testing "未知费用版本404"
      (is (= 404 (error-status #(command! cost/add-entry! id ["no-such-version"]
                                          {:category "material" :label "x" :amount "1"})))))
    (testing "草稿明细可删除: 成功路径返回版本 dto 且明细消失"
      (let [with-entry (:result (command! cost/add-entry! id [(:id version)]
                                          {:category "material" :label "临时明细" :amount "10"}))
            entry (first (filter #(= "临时明细" (:label %)) (:entries with-entry)))]
        (is (some? entry))
        (let [after (:result (command! cost/delete-entry! id [(:id version) (:entry_id entry)] {}))]
          (is (some? after))
          (is (empty? (filter #(= "临时明细" (:label %)) (:entries after)))))))
    (testing "已批准版本不能取消409 (用另一份带明细的版本走审批)"
      (let [v2 (:result (command! cost/create! id [] {:kind "budget" :period "2026-11" :currency "CNY"
                                                      :name "十一月预算" :revenue "0.00" :reviewer_id 9402}))]
        (command! cost/add-entry! id [(:id v2)] {:category "material" :label "留用明细" :amount "5"})
        (approve-cost! id (:id v2))
        (is (= 409 (error-status #(command! cost/cancel! id [(:id v2)] {:reason "想取消已批准版本"}))))))))


(deftest period-lock-gates-cost-version-writes
  (let [id (project!)
        c1 (:id (:result (command! cost/create! id [] {:kind "budget" :period "2026-09" :currency "CNY" :name "预算A" :revenue "100.00" :reviewer_id 9402})))
        c2 (:id (:result (command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "CNY" :name "核算B" :revenue "0" :reviewer_id 9402})))
        add (command! cost/add-entry! id [c2] {:category "other" :label "成本" :amount "5"})
        e2 (get-in add [:result :entries 0 :id])]
    (command! cost/add-entry! id [c1] {:category "material" :label "原料" :amount "10"})
    (command! cost/submit! id [c1] {})
    (command! 9402 cost/review! id [c1] {:decision "approved" :reason "核对完成"})
    (config/create! *svc* (actor 9401) "period-lock" {:period "2026-09" :reason "月结封账"})
    (is (= 409 (error-status #(command! cost/create! id [] {:kind "estimate" :period "2026-09" :currency "CNY" :name "概算C" :revenue "0" :reviewer_id 9402}))))
    (is (= 409 (error-status #(command! cost/add-entry! id [c2] {:category "other" :label "追加" :amount "1"}))))
    (is (= 409 (error-status #(command! cost/delete-entry! id [c2 e2] {}))))
    (is (= 409 (error-status #(command! cost/submit! id [c2] {}))))
    (is (= 409 (error-status #(command! cost/cancel! id [c2] {:reason "想取消"}))))
    (is (= 409 (error-status #(command! cost/revise! id [c1] {:reviewer_id 9402}))))
    (is (some? (:id (:result (command! cost/create! id [] {:kind "estimate" :period "2026-10" :currency "CNY" :name "概算D" :revenue "0" :reviewer_id 9402})))))
    (is (some #{"2026-09"} (:locked_periods (finance/overview *svc* (actor 9401) id))))
    (let [lock (first (filter #(= "2026-09" (:period %)) (config/published (:query-fn *svc*) "period-lock")))]
      (config/retire! *svc* (actor 9401) "period-lock" (:id lock) {:reason "重开月结"}))
    (is (= "submitted" (:status (:result (command! cost/submit! id [c2] {})))))
    (is (not-any? #{"2026-09"} (:locked_periods (finance/overview *svc* (actor 9401) id))))))


(deftest period-lock-does-not-gate-independent-cost-review
  (let [id (project!)
        cid (:id (:result (command! cost/create! id [] {:kind "budget" :period "2026-09" :currency "CNY" :name "预算E" :revenue "100.00" :reviewer_id 9402})))]
    (command! cost/add-entry! id [cid] {:category "material" :label "原料" :amount "10"})
    (command! cost/submit! id [cid] {})
    (config/create! *svc* (actor 9401) "period-lock" {:period "2026-09" :reason "月结封账"})
    (is (= "approved" (:status (:result (command! 9402 cost/review! id [cid] {:decision "approved" :reason "封期后独立批准"})))))
    (let [lock (first (filter #(= "2026-09" (:period %)) (config/published (:query-fn *svc*) "period-lock")))]
      (config/retire! *svc* (actor 9401) "period-lock" (:id lock) {:reason "重开月结"}))))


(deftest lesson-summary-aggregates-categories-and-authors-read-only
  (let [empty (closure/lesson-summary [])]
    (is (false? (:available empty)))
    (is (= 0 (:total empty)))
    (is (= 0 (:concentration-pct empty)))
    (is (nil? (:dominant-category empty)))
    (is (= [] (:by-category empty))))
  (let [lessons [{:category "交付" :created_by 1}
                 {:category "交付" :created_by 1}
                 {:category "质量" :created_by 2}
                 {:category "成本" :created_by 3}
                 {:category nil :created_by 2}]
        s (closure/lesson-summary lessons)]
    (is (:available s))
    (is (= 5 (:total s)))
    (is (= 3 (:distinct-categories s)))
    (is (= 4 (:categorized s)))
    (is (= 1 (:uncategorized s)))
    (is (= "交付" (:dominant-category s)))
    (is (= 2 (:dominant-count s)))
    (is (= 40 (:concentration-pct s)))
    (is (= 3 (:author-count s)))
    (is (= 2 (:top-author-count s)))
    (is (= [{:category "交付" :count 2} {:category "成本" :count 1} {:category "质量" :count 1}]
           (:by-category s)))))


(deftest lesson-summary-in-closure-overview-is-read-only
  (let [id (project!)]
    (is (false? (:available (:lesson_summary (closure/overview *svc* (actor 9401) id)))))
    (command! closure/create-lesson! id [] {:title "经验一" :category "交付" :content "首次交付经验"})
    (command! closure/create-lesson! id [] {:title "经验二" :category "交付" :content "复盘交付"})
    (command! closure/create-lesson! id [] {:title "经验三" :category "质量" :content "质量改进"})
    (let [ov (closure/overview *svc* (actor 9401) id)
          ls (:lesson_summary ov)]
      (is (:available ls))
      (is (= 3 (:total ls)))
      (is (= 2 (:distinct-categories ls)))
      (is (= "交付" (:dominant-category ls)))
      (is (= 2 (:dominant-count ls)))
      (is (= 67 (:concentration-pct ls)))
      (is (= 1 (:author-count ls)))
      (is (= 3 (:top-author-count ls)))
      ;; 只读派生: 不改变 progress 的经验计数, 也不改动任何写路径
      (is (= 3 (:lessons (:progress ov))))
      (is (= 3 (count (:lessons ov)))))))


(deftest lesson-context-owner-persists-and-echoes-in-read-model
  (let [id (project!)]
    ;; 可选标注: 适用场景 + 有效项目成员 (9402) 作为跟进责任人
    (command! closure/create-lesson! id [] {:title "交付现场经验" :category "交付" :content "设备到货延迟"
                                            :applicable_stage "执行" :owner_id 9402})
    (command! closure/create-lesson! id [] {:title "风险登记经验" :category "风险" :content "概率低估"
                                            :applicable_stage "执行" :owner_id 9403})
    (command! closure/create-lesson! id [] {:title "复盘会议经验" :category "质量" :content "评审不足"
                                            :applicable_stage "收尾"})
    (command! closure/create-lesson! id [] {:title "未标注经验" :category "成本" :content "留空兼容"})
    ;; 写路径回显服务端规范化字段 (命令经 kernel/mutate! 返回 {:result <记录> :project_version})
    (let [created (:result (command! closure/create-lesson! id [] {:title "带责任人" :category "交付" :content "验收"
                                                                   :applicable_stage "监控" :owner_id 9402}))]
      (is (= "监控" (:applicable_stage created)))
      (is (= 9402 (:owner_id created))))
    ;; 读模型逐行回显 applicable_stage / owner_id / owner_name (LEFT JOIN sys_user)
    (let [ov (closure/overview *svc* (actor 9401) id)
          lessons (:lessons ov)
          by-title (reduce (fn [m l] (assoc m (:title l) l)) {} lessons)]
      (is (= "执行" (:applicable_stage (by-title "交付现场经验"))))
      (is (= 9402 (:owner_id (by-title "交付现场经验"))))
      (is (= "财务测试9402" (:owner_name (by-title "交付现场经验"))))
      (is (nil? (:owner_id (by-title "未标注经验"))))
      (is (nil? (:owner_name (by-title "未标注经验")))))
    ;; lesson-summary 追加只读派生: 场景分布与责任人落地度
    (let [ls (:lesson_summary (closure/overview *svc* (actor 9401) id))]
      (is (:available ls))
      (is (= 5 (:total ls)))
      (is (= 4 (:stages-declared ls)))
      (is (= 3 (:distinct-stages ls)))
      (is (= [{:stage "执行" :count 2} {:stage "收尾" :count 1} {:stage "监控" :count 1}] (:by-stage ls)))
      (is (= 3 (:owner-assigned ls)))
      (is (= 60 (:owner-coverage-pct ls))))))


(deftest lesson-invalid-stage-rejected
  (let [id (project!)]
    (is (= 400 (error-status #(command! closure/create-lesson! id [] {:title "坏场景" :category "交付" :content "x"
                                                                      :applicable_stage "未知场景"}))))
    ;; 合法场景仍然通过, 与枚举白名单一致
    (is (= "采购" (:applicable_stage (:result (command! closure/create-lesson! id [] {:title "好场景" :category "交付" :content "y"
                                                                                    :applicable_stage "采购"})))))))


(deftest lesson-owner-must-be-valid-member
  (let [id (project!)]
    ;; 不存在的用户作为跟进责任人 -> 400
    (is (= 400 (error-status #(command! closure/create-lesson! id [] {:title "坏责任人" :category "交付" :content "x"
                                                                      :owner_id 9999}))))
    ;; 留空 (缺省) 仍然零回归: 不写 owner, 汇总 owner-assigned 0
    (let [created (:result (command! closure/create-lesson! id [] {:title "无责任人" :category "交付" :content "y"}))]
      (is (nil? (:owner_id created)))
      (let [ls (:lesson_summary (closure/overview *svc* (actor 9401) id))]
        (is (= 1 (:total ls)))
        (is (= 0 (:owner-assigned ls)))
        (is (= 0 (:stages-declared ls)))
        (is (= [] (:by-stage ls)))
        (is (= 0 (:owner-coverage-pct ls)))))))


;; batch58 (mutator 方向: cover the uncovered): closure 审批链适配器 finalize! 全路径 —
;; 发布 "closure" 策略后走 chain/decide! 收口, 覆盖批准落定, 驳回落定, 批准前收尾缺口 409,
;; 批准前快照失配 409, 以及策略下提交的 reviewer 短路 (L262) 与首级审批人兜底 (L272).
(deftest closure-chain-finalize-covers-adapter-guards
  (let [admin (actor 9401)
        levels [{:name "结项审批" :rule "user" :user_ids [9402]}]]
    (try
      (let [existing (last (sort-by :revision (filter #(= "closure" (:code %))
                                                      (config/records (:query-fn *svc*) "approval-policy"))))
            draft (cond
                    (nil? existing) (config/create! *svc* admin "approval-policy" {:code "closure" :levels levels})
                    (= "draft" (:status existing)) existing
                    :else (config/revise! *svc* admin "approval-policy" (:id existing) {:levels levels}))]
        (config/publish! *svc* admin "approval-policy" (:id draft) {:reason "结项改走审批链"}))
      (testing "策略下提交无需指定审核人, 末级通过后适配器批准并可关闭"
        (let [{:keys [id]} (closure-ready-project!)
              approval (:result (command! closure/submit! id [] {}))]
          (is (= "submitted" (:status approval)))
          (is (= 9402 (:reviewer_id approval)) "策略解析的首级审批人落库, 提交人未指定")
          (let [flow (first (chain/project-flows *svc* (actor 9401) id {:biz_type "closure"}))]
            (is (= "pending" (:status flow)))
            (is (= [9402] (mapv :approver_id (:steps (first (:levels flow))))))
            (is (= "approved" (:status (chain/decide! *svc* (actor 9402) (:flow_id flow)
                                                      {:decision "approved"}))) "不填意见也可通过, review_note 落空串")
            (is (= "approved" (:status ((:query-fn *svc*) :closure/approval {:project_id id}))))
            (is (= "closed" (:status (transition! id "closed")))))))
      (testing "末级驳回时适配器把关闭申请置为 rejected, 项目不能关闭"
        (let [{:keys [id]} (closure-ready-project!)]
          (command! closure/submit! id [] {})
          (let [flow (first (chain/project-flows *svc* (actor 9401) id {:biz_type "closure"}))]
            (is (= "rejected" (:status (chain/decide! *svc* (actor 9402) (:flow_id flow)
                                                      {:decision "rejected" :comment "依据不全"}))))
            (is (= "rejected" (:status ((:query-fn *svc*) :closure/approval {:project_id id}))))
            (is (= [409 "尚未获得独立关闭批准"] (error-info #(transition! id "closed")))))))
      (testing "批准前新增未完成必需项: 适配器以收尾缺口拒绝且不留下半步决定"
        (let [{:keys [id]} (closure-ready-project!)]
          (command! closure/submit! id [] {})
          (command! closure/create-item! id ["check"] {:title "审批中补充归档" :required true})
          (let [flow (first (chain/project-flows *svc* (actor 9401) id {:biz_type "closure"}))]
            (is (= [409 "尚未完成: 审批中补充归档"]
                   (error-info #(chain/decide! *svc* (actor 9402) (:flow_id flow)
                                               {:decision "approved" :comment "通过"}))))
            (is (= "pending" (:status (chain/flow *svc* (actor 9401) (:flow_id flow)))) "决定回滚, 流仍待决")
            (is (= "submitted" (:status ((:query-fn *svc*) :closure/approval {:project_id id})))))))
      (testing "批准前关闭依据变化: 适配器以快照失配拒绝"
        (let [{:keys [id]} (closure-ready-project!)]
          (command! closure/submit! id [] {})
          (command! closure/create-lesson! id [] {:title "结项复盘" :category "交付" :content "审批中新增经验"})
          (let [flow (first (chain/project-flows *svc* (actor 9401) id {:biz_type "closure"}))]
            (is (= [409 "关闭依据已变化,请驳回并重新提交"]
                   (error-info #(chain/decide! *svc* (actor 9402) (:flow_id flow)
                                               {:decision "approved" :comment "通过"}))))
            (is (= "submitted" (:status ((:query-fn *svc*) :closure/approval {:project_id id})))))))
      (finally
        (when-let [p (config/published-by-code (:query-fn *svc*) "approval-policy" "closure")]
          (config/retire! *svc* admin "approval-policy" (:id p) {:reason "测试退役"}))))))


;; batch58 (mutator 方向: cover the uncovered): closure 提交, 复核与事项守卫 —
;; 收尾任务门 (L19/L22), 就绪复核缺口 (L65), 工作台带出审批且脱敏 (L125/L188),
;; 对象类型与移交期限 400 (L200/L208), 事项重复完成 404/409 (L220/L221),
;; 提交缺口 409 (L258), 重复提交 409 (L266), 自审 400 (L267), 审批后依据变化 409 (L289/L290).
(deftest closure-submit-review-and-item-guards
  (testing "尚有未完成叶级任务时不能进入收尾"
    (let [{:keys [id]} (execution-project!)]
      (is (= [409 "仍有1个未完成的叶级任务"] (error-info #(transition! id "closing"))))))
  (testing "收尾对象类型与移交期限受控400"
    (let [id (project!)]
      (is (= [400 "无效收尾对象类型"]
             (error-info #(command! closure/create-item! id ["bogus"] {:title "坏类型" :required true}))))
      (is (= [400 "移交期限不能为空"]
             (error-info #(command! closure/create-item! id ["handoff"] {:title "缺期限移交" :owner_id 9403}))))))
  (testing "收尾事项类型不符404, 已完成不能重复完成409"
    (let [id (project!) document (document! id)
          item (:result (command! closure/create-item! id ["check"] {:title "完成校验" :required false}))]
      (command! closure/complete-item! id ["check" (:id item)] {:evidence_ref (:id document) :comment "首次完成"})
      (is (= [404 "收尾事项不存在"]
             (error-info #(command! closure/complete-item! id ["handoff" (:id item)]
                                    {:evidence_ref (:id document) :comment "错类型"}))))
      (is (= [409 "收尾事项已完成"]
             (error-info #(command! closure/complete-item! id ["check" (:id item)]
                                    {:evidence_ref (:id document) :comment "重复完成"}))))))
  (testing "已有待处理的关闭申请不能重复提交, 提交后工作台带出脱敏审批"
    (let [{:keys [id]} (closure-ready-project!)]
      (command! closure/submit! id [] {:reviewer_id 9402})
      (is (= [409 "已有待处理的关闭申请"]
             (error-info #(command! closure/submit! id [] {:reviewer_id 9402}))))
      (let [ov (closure/overview *svc* (actor 9401) id)]
        (is (= "submitted" (get-in ov [:approval :status])))
        (is (nil? (:snapshot_json (:approval ov))))
        (is (= "submitted" (get-in ov [:readiness :approval-state])))
        (is (= "submitted" (get-in ov [:progress :approval-state]))))))
  (testing "不能审批自己提交的关闭申请400"
    (let [{:keys [id]} (closure-ready-project!)]
      (is (= [400 "不能审批自己提交的关闭申请"]
             (error-info #(command! closure/submit! id [] {:reviewer_id 9401}))))))
  (testing "存在收尾缺口时不能提交关闭申请409"
    (let [{:keys [id]} (closure-ready-project!)]
      (command! closure/create-item! id ["check"] {:title "提交前补充材料" :required true})
      (is (= [409 "尚未完成: 提交前补充材料"]
             (error-info #(command! closure/submit! id [] {:reviewer_id 9402}))))))
  (testing "关闭依据在审批后变化时旧审批不能通过409"
    (let [{:keys [id]} (closure-ready-project!)]
      (command! closure/submit! id [] {:reviewer_id 9402})
      (command! closure/create-lesson! id [] {:title "审批中新增依据" :category "交付" :content "复盘"})
      (is (= [409 "关闭依据已变化,请驳回并重新提交"]
             (error-info #(command! 9402 closure/review! id [] {:decision "approved" :reason "通过"}))))))
  (testing "批准后新增缺口时就绪复核先阻塞关闭409"
    (let [{:keys [id]} (closure-ready-project!)]
      (command! closure/submit! id [] {:reviewer_id 9402})
      (command! 9402 closure/review! id [] {:decision "approved" :reason "通过"})
      (command! closure/create-item! id ["check"] {:title "关闭前新增缺口" :required true})
      (is (= [409 "尚未完成: 关闭前新增缺口"] (error-info #(transition! id "closed")))))))


;; batch62 (mutator 方向: cover the uncovered): 分摊期间/金额校验 (L25/L26)
;; 与工时期间对齐费用版本期间 (L71).
(deftest allocation-period-amount-and-version-alignment-guards
  (let [id (project!)
        v (:result (command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "CNY"
                                                 :name "九月分摊" :revenue "0" :reviewer_id 9402}))]
    (testing "分摊期间无效400 (结束早于开始)"
      (is (= [400 "分摊期间无效"]
             (error-info #(command! allocation/allocate! id [(:id v)]
                                    {:amount "1.00" :from_date "2026-09-22" :to_date "2026-09-01"
                                     :idempotency_key "bad-period" :label "反序期间"})))))
    (testing "分摊费用必须为正数400"
      (is (= [400 "分摊费用必须为正数"]
             (error-info #(command! allocation/allocate! id [(:id v)]
                                    {:amount "0" :from_date "2026-09-01" :to_date "2026-09-05"
                                     :idempotency_key "zero-amount" :label "零额"})))))
    (testing "工时期间必须位于当前费用版本期间内400"
      (is (= [400 "工时期间必须位于当前费用版本期间内"]
             (error-info #(command! allocation/allocate! id [(:id v)]
                                    {:amount "1.00" :from_date "2026-10-01" :to_date "2026-10-05"
                                     :idempotency_key "wrong-month" :label "跨月期间"})))))))


;; batch66 (mutator 方向: cover the uncovered): 工时小数位/未来日期400 (money L29/time L32)
;; 与 closure-blockers 费用草稿/待审工时分支 (finance L216/L218), 预算规则跨项目停用403 (L141-142).
(deftest time-entry-format-and-date-guards
  (let [{:keys [id task]} (execution-project!)]
    (is (= [400 "工作日期不能为空或晚于今天"]
           (error-info #(time! id task "1" "2099-01-01" false))))
    (is (= [400 "工时必须是最多两位小数的正数"]
           (error-info #(time! id task "1.005" "2026-09-01" false))))))


(deftest closure-blockers-surface-draft-costs-and-pending-times
  (let [{:keys [id task]} (execution-project!)]
    (time! id task "1" "2026-09-01" false)
    (cost! id "estimate")
    (let [q (:query-fn *svc*) project (q :pms/project {:project_id id})
          msgs (finance/closure-blockers q project)]
      (is (some #(= "存在尚未处理的费用草稿或待审批版本" %) msgs))
      (is (some #(= "存在待审核工时" %) msgs)))))


(deftest budget-rule-disable-across-projects-is-forbidden
  (let [a (project!) b (project!)
        rule (:result (command! budget/upsert-rule! a [] {:baseline "budget" :threshold_pct 80
                                                          :action "warn" :project_scoped true}))]
    (is (some? (or (:id rule) (:rule_id rule))))
    (is (= [403 "只能停用本项目或系统默认规则"]
           (error-info #(command! budget/disable-rule! b [(or (:id rule) (:rule_id rule))]
                                  {:reason "越权停用"}))))))


;; batch70 (mutator 形式级甄别): 汇总任务不计入收尾任务门 —
;; done 叶级 + summary 并存时 transition closing 放行 (closure L19 谓词两分支).
(deftest summary-tasks-do-not-block-closing
  (let [{:keys [id task]} (execution-project!)]
    (command! plan/task-feedback! id [(:task_id task)] {:status "done" :percent_complete 100 :remaining_days 0})
    (command! plan/create-task! id [] {:wbs_code "SUM-70" :name "汇总节点" :task_type "summary"
                                       :duration_days 0 :owner_id 9401})
    (is (= "closing" (:status (transition! id "closing"))))))


;; batch71 (mutator 方向: cover the uncovered): 费用版本输入守卫群 (finance_cost
;; L62/L63/L64/L65/L66/L87/L108/L123/L124/L143/L157/L160 十二门).
(deftest cost-input-guards-cover-create-entry-submit-review-revise
  (let [id (project!)]
    (testing "创建门: 期间格式/无效月份/币种/负收入/自审"
      (is (= [400 "期间格式必须为YYYY-MM"]
             (error-info #(command! cost/create! id [] {:kind "actual" :period "2026-9" :currency "CNY"
                                                        :name "坏期间" :revenue "0" :reviewer_id 9402}))))
      (is (= [400 "期间无效"]
             (error-info #(command! cost/create! id [] {:kind "actual" :period "2026-13" :currency "CNY"
                                                        :name "坏月份" :revenue "0" :reviewer_id 9402}))))
      (is (= [400 "暂仅支持CNY/USD/EUR/GBP/HKD"]
             (error-info #(command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "JPY"
                                                        :name "坏币种" :revenue "0" :reviewer_id 9402}))))
      (is (= [400 "收入不能为负数"]
             (error-info #(command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "CNY"
                                                        :name "负收入" :revenue "-1" :reviewer_id 9402}))))
      (is (= [400 "财务审批人不能是提交者"]
             (error-info #(command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "CNY"
                                                        :name "自审版本" :revenue "0" :reviewer_id 9401})))))
    (let [v (:result (command! cost/create! id [] {:kind "actual" :period "2026-09" :currency "CNY"
                                                   :name "正常版本" :revenue "0" :reviewer_id 9403}))]
      (testing "明细分类受控400"
        (is (= [400 "无效费用分类"]
               (error-info #(command! cost/add-entry! id [(:id v)] {:category "bogus" :label "x" :amount "1"})))))
      (testing "空明细不能提交409"
        (is (= [409 "费用明细为空,不能提交"]
               (error-info #(command! cost/submit! id [(:id v)] {})))))
      (command! cost/add-entry! id [(:id v)] {:category "material" :label "原料" :amount "10"})
      (testing "指定审批人不能代提交403 (9403 为可写编辑者, 排除 access! 先拦)"
        (is (= [403 "指定审批人不能代替提交者提交"]
               (error-info #(command! 9403 cost/submit! id [(:id v)] {})))))
      (testing "草稿版本不能审批/修订"
        (is (= [409 "费用版本不在待审批状态"]
               (error-info #(command! 9403 cost/review! id [(:id v)] {:decision "approved" :reason "未提交"}))))
        (is (= [409 "只能修订已处理版本"]
               (error-info #(command! cost/revise! id [(:id v)] {:reviewer_id 9402})))))
      (testing "删除他版明细404 与 修订自审400"
        (let [v2 (:result (command! cost/create! id [] {:kind "budget" :period "2026-09" :currency "CNY"
                                                        :name "对照版本" :revenue "0" :reviewer_id 9402}))]
          (is (= [404 "费用项不存在"]
                 (error-info #(command! cost/delete-entry! id [(:id v2) "no-such-entry"] {})))))
        (command! cost/submit! id [(:id v)] {})
        (command! 9403 cost/review! id [(:id v)] {:decision "approved" :reason "核对完成"})
        (is (= [400 "不能指定自己审批"]
               (error-info #(command! cost/revise! id [(:id v)] {:reviewer_id 9401}))))))))


;; batch71: 工时单次上限24小时400 (finance_money L32 的范围分支).
(deftest time-entry-hours-range-guard
  (let [{:keys [id task]} (execution-project!)]
    (is (= [400 "工时必须在1分钟至24小时之间"]
           (error-info #(time! id task "25" "2026-09-01" false))))))


;; batch71: 重开三门 (reopen L12/L20/L34).
(deftest reopen-request-and-review-guards
  (testing "非关闭项目不能申请重开409"
    (let [id (project!)]
      (is (= [409 "仅已关闭项目可申请重开"]
             (error-info #(command! reopen/request! id [] {:reviewer_id 9402 :scope "纠正" :reason "申请"}))))))
  (let [{:keys [id]} (closure-ready-project!)]
    (command! closure/submit! id [] {:reviewer_id 9402})
    (command! 9402 closure/review! id [] {:decision "approved" :reason "独立批准关闭"})
    (transition! id "closed")
    (testing "重开审批人不能是申请人400"
      (is (= [400 "重开审批人不能是申请人"]
             (error-info #(command! reopen/request! id [] {:reviewer_id 9401 :scope "自审" :reason "申请"})))))
    (testing "已审批的重开申请不能再审409"
      (let [request (:result (command! reopen/request! id [] {:reviewer_id 9402 :scope "纠正" :reason "资料更正"}))]
        (command! 9402 reopen/review! id [(:id request)] {:decision "approved" :reason "同意受控更正"})
        (is (= [409 "该项目没有对应待审重开申请"]
               (error-info #(command! 9402 reopen/review! id [(:id request)]
                                      {:decision "approved" :reason "再次审批"}))))))))


;; batch71: 预算规则输入六门 (finance_budget L86/L87/L89/L106/L112/L113/L139).
(deftest budget-rule-input-guards
  (let [id (project!)]
    (testing "新建门: 基线/动作/阈值"
      (is (= [400 "基线口径只能为 estimate 或 budget"]
             (error-info #(command! budget/upsert-rule! id [] {:baseline "weird" :threshold_pct 80
                                                               :action "warn" :project_scoped true}))))
      (is (= [400 "动作只能为 warn, require_approval 或 block"]
             (error-info #(command! budget/upsert-rule! id [] {:baseline "budget" :threshold_pct 80
                                                               :action "explode" :project_scoped true}))))
      (is (= [400 "阈值必须在 5%-500% 之间"]
             (error-info #(command! budget/upsert-rule! id [] {:baseline "budget" :threshold_pct 3
                                                               :action "warn" :project_scoped true})))))
    (testing "更新/停用未知规则404 与 更新门400"
      (is (= [404 "预算控制规则不存在"]
             (error-info #(command! budget/upsert-rule! id [] {:rule_id "fake-rule" :threshold_pct 80
                                                               :action "warn"}))))
      (is (= [404 "预算控制规则不存在"]
             (error-info #(command! budget/disable-rule! id ["fake-rule"] {:reason "不存在"}))))
      (let [rule (:result (command! budget/upsert-rule! id [] {:baseline "budget" :threshold_pct 80
                                                               :action "warn" :project_scoped true}))]
        (is (= [400 "阈值必须在 5%-500% 之间"]
               (error-info #(command! budget/upsert-rule! id [] {:rule_id (or (:id rule) (:rule_id rule))
                                                                 :threshold_pct 1 :action "warn"}))))
        (is (= [400 "无效动作"]
               (error-info #(command! budget/upsert-rule! id [] {:rule_id (or (:id rule) (:rule_id rule))
                                                                 :threshold_pct 80 :action "explode"}))))))))


;; batch71: closure 策略 submitter_choice 未指定审批人时带后缀409
;; (approval_chain L104 的 '请指定审批人' 分支).
(deftest closure-submitter-choice-policy-requires-named-reviewer
  (let [admin (actor 9401)
        draft (config/create! *svc* admin "approval-policy"
                              {:code "closure" :levels [{:name "提交人选择" :rule "submitter_choice"}]})]
    (config/publish! *svc* admin "approval-policy" (:id draft) {:reason "结项走提交人选择"})
    (try
      (let [{:keys [id]} (closure-ready-project!)
            info (error-info #(command! closure/submit! id [] {}))]
        (is (= 409 (first info)))
        (is (.contains (str (second info)) "请指定审批人")))
      (finally
        (when-let [p (config/published-by-code (:query-fn *svc*) "approval-policy" "closure")]
          (config/retire! *svc* admin "approval-policy" (:id p) {:reason "测试退役"}))))))


;; batch73 (mutator 方向: cover the uncovered): 工时提交/审核/更正输入守卫
;; (finance_time L26/L33/L60/L61/L80/L90 六门).
(deftest time-submit-review-and-correction-input-guards
  (testing "非执行项目不能提交工时409 (先于任务与审核人检查)"
    (let [id (project!)]
      (is (= [409 "仅执行中的项目可以提交工时"]
             (error-info #(command! time/submit! id [] {:task_id "x" :work_date "2026-09-01" :hours "1"
                                                        :note "说明" :reviewer_id 9402}))))))
  (let [{:keys [id task]} (execution-project!)]
    (testing "审核人不能是提交者400"
      (is (= [400 "工时审核人不能是提交者"]
             (error-info #(command! time/submit! id [] {:task_id (:task_id task) :work_date "2026-09-01"
                                                        :hours "1" :note "说明" :reviewer_id 9401})))))
    (testing "审核与更正的记录存在性404"
      (is (= [404 "工时单不存在"]
             (error-info #(command! time/review! id ["no-such-entry"] {:decision "approved"}))))
      (is (= [404 "工时单不存在"]
             (error-info #(command! time/correct! id ["no-such-entry"]
                                    {:hours "1" :note "n" :reason "改" :reviewer_id 9402})))))
    (let [entry (time! id task "1" "2026-06-17" true)]
      (testing "已处理工时单不能再审409"
        (is (= [409 "工时单已处理"]
               (error-info #(command! time/review! id [(:id entry)] {:decision "approved" :reason "再审"})))))
      (testing "更正自审400 (批准态入口各门通过后落在审核人检查)"
        (is (= [400 "工时审核人不能是提交者"]
               (error-info #(command! time/correct! id [(:id entry)]
                                      {:hours "1" :note "n" :reason "改" :reviewer_id 9401}))))))))


;; batch73: 工时日容量两个不变量 — 更正加量超限 (L93) 与驳回恢复原单超限 (L69).
(deftest time-correction-capacity-invariants
  (testing "更正工时超出同日剩余容量409 (L93: 释放原单后加量重占失败)"
    (let [p1 (execution-project!) p2 (execution-project!)]
      (time! (:id p1) (:task p1) "1" "2026-06-15" true)
      (time! (:id p2) (:task p2) "22.5" "2026-06-15" true)
      (let [orig (first (:rows (finance/times *svc* (actor 9401) (:id p1))))]
        (is (= [409 "同一天跨项目累计工时不能超过24小时"]
               (error-info #(command! time/correct! (:id p1) [(:entry_id orig)]
                                      {:hours "2" :note "加量" :reason "需求增加" :reviewer_id 9402})))))))
  (testing "驳回更正恢复原单时容量不足409 (L69: 更正期间被第三方占满)"
    (let [p1 (execution-project!) p2 (execution-project!)]
      (time! (:id p1) (:task p1) "2" "2026-06-16" true)
      (let [orig (first (:rows (finance/times *svc* (actor 9401) (:id p1))))
            corr (:result (command! time/correct! (:id p1) [(:entry_id orig)]
                                    {:hours "1" :note "压低" :reason "压低工时" :reviewer_id 9402}))]
        (time! (:id p2) (:task p2) "22.5" "2026-06-16" true)
        (is (= [409 "恢复原工时单时同日容量不足"]
               (error-info #(command! 9402 time/review! (:id p1) [(:id corr)]
                                      {:decision "rejected" :reason "不予更正"}))))))))


;; ── H07 有效期费率表与批准工时人工成本只读派生 ─────────────────────

(deftest rate-table-validates-effective-windows
  (let [mk (fn [rates] #(config/create! *svc* (actor 9401) "rate-table" {:base_currency "CNY" :rates rates}))]
    (testing "同一角色有效期重叠400"
      (is (= [400 "同一对象的费率有效期重叠"]
             (error-info (mk [{:code "MGR-A" :scope "role" :role "manager" :amount_per_hour "100.00"
                               :effective_from "2026-01-01" :effective_to "2026-06-30"}
                              {:code "MGR-B" :scope "role" :role "manager" :amount_per_hour "120.00"
                               :effective_from "2026-06-01" :effective_to "2026-12-31"}])))))
    (testing "止日早于起日400"
      (is (= 400 (first (error-info (mk [{:code "BAD" :scope "role" :role "viewer" :amount_per_hour "10.00"
                                          :effective_from "2026-05-01" :effective_to "2026-04-01"}]))))))
    (testing "非法范围400"
      (is (= 400 (first (error-info (mk [{:code "X" :scope "team" :role "viewer" :amount_per_hour "10.00"
                                          :effective_from "2026-01-01"}]))))))
    (testing "非正费率400"
      (is (= 400 (first (error-info (mk [{:code "Z" :scope "role" :role "viewer" :amount_per_hour "0"
                                          :effective_from "2026-01-01"}]))))))
    (testing "人员范围必须给正整数人员400"
      (is (= 400 (first (error-info (mk [{:code "P" :scope "person" :amount_per_hour "10.00"
                                          :effective_from "2026-01-01"}]))))))
    (testing "非法日期格式400"
      (is (= 400 (first (error-info (mk [{:code "D" :scope "role" :role "viewer" :amount_per_hour "10.00"
                                          :effective_from "2026/01/01"}]))))))))


(deftest approved-times-derive-labor-cost-with-effective-rates
  (let [env (execution-project!) id (:id env) task (:task env)
        rates (fn [person-amount]
                [{:code "P-9401" :scope "person" :user_id 9401 :amount_per_hour person-amount :effective_from "2026-09-01"}
                 {:code "R-MGR" :scope "role" :role "manager" :amount_per_hour "100.00" :effective_from "2026-09-01"}
                 {:code "R-EDT" :scope "role" :role "editor" :amount_per_hour "80.00" :effective_from "2026-09-01"}])]
    (testing "创建并发布有效期费率表"
      (let [rec (config/create! *svc* (actor 9401) "rate-table"
                                {:base_currency "CNY" :reason "验收费率" :rates (rates "150.00")})]
        (config/publish! *svc* (actor 9401) "rate-table" (:id rec) {:reason "启用费率"})))
    (testing "已批准工时按人员费率, 角色费率与生效前未覆盖分别折算"
      (time! id task "2" "2026-09-08" true)
      (let [e (:result (command! 9403 time/submit! id []
                                 {:task_id (:task_id task) :work_date "2026-09-09" :hours "1" :note "编辑支持" :reviewer_id 9402}))]
        (command! 9402 time/review! id [(:id e)] {:decision "approved" :reason "核验"}))
      (time! id task "2" "2026-08-15" true)
      (let [lc (:labor_cost (finance/overview *svc* (actor 9401) id))]
        (is (:configured? lc))
        (is (= "CNY" (:base_currency lc)))
        (is (= 38000 (:covered_minor lc)) "9401 2h x150 + 9403 1h x80")
        (is (= 180 (:covered_minutes lc)))
        (is (= 120 (:uncovered_minutes lc)) "生效前的工时如实列为未覆盖")
        (let [person (some #(when (and (= 9401 (:user_id %)) (:covered %)) %) (:rows lc))
              role (some #(when (= 9403 (:user_id %)) %) (:rows lc))
              uncovered (some #(when (and (= 9401 (:user_id %)) (not (:covered %))) %) (:rows lc))]
          (is (= ["P-9401" "person"] [(:rate_code person) (:rate_scope person)]))
          (is (= ["R-EDT" "role"] [(:rate_code role) (:rate_scope role)]))
          (is (nil? (:amount uncovered)) "未覆盖行不伪造成本金额"))))
    (testing "修订费率并发布后按新费率重算 (版本化不改旧版)"
      (let [old (config/published-by-code (:query-fn *svc*) "rate-table" "RATE-CNY")
            revised (config/revise! *svc* (actor 9401) "rate-table" (:id old)
                                    {:base_currency "CNY" :reason "调价"
                                     :rates [{:code "P-9401" :scope "person" :user_id 9401 :amount_per_hour "200.00" :effective_from "2026-09-01"}
                                             {:code "R-MGR" :scope "role" :role "manager" :amount_per_hour "100.00" :effective_from "2026-09-01"}
                                             {:code "R-EDT" :scope "role" :role "editor" :amount_per_hour "80.00" :effective_from "2026-09-01"}]})]
        (config/publish! *svc* (actor 9401) "rate-table" (:id revised) {:reason "调价发布"})
        (is (= 48000 (:covered_minor (:labor_cost (finance/overview *svc* (actor 9401) id)))))))))


(deftest exchange-rate-validates-pairs-and-windows
  ;; 同一本位币只能有一张汇率表 (其余走修订), 故不同小用例换本位币避免编码冲突.
  (let [create (fn [base rates] #(config/create! *svc* (actor 9401) "exchange-rate" {:base_currency base :rates rates}))
        mk (fn [rates] (create "CNY" rates))]
    (testing "同一币种对有效期重叠400"
      (is (= [400 "同一币种对的汇率有效期重叠"]
             (error-info (mk [{:from_currency "USD" :to_currency "CNY" :rate "7.1"
                               :effective_from "2026-01-01" :effective_to "2026-06-30"}
                              {:from_currency "USD" :to_currency "CNY" :rate "7.2"
                               :effective_from "2026-06-01" :effective_to "2026-12-31"}])))))
    (testing "不同币种对可重叠 (互不冲突); 表编码与行编码均由后端按币种派生, 调用方不填"
      (let [rec ((create "EUR" [{:from_currency "USD" :to_currency "EUR" :rate "0.92" :effective_from "2026-01-01"}
                                {:from_currency "GBP" :to_currency "EUR" :rate "1.18" :effective_from "2026-01-01"}]))]
        (is (= "FX-EUR" (:code rec)))
        (is (= ["USD-EUR" "GBP-EUR"] (mapv :code (:rates rec))))))
    (testing "调用方自带行编码400 (编码不可由用户指定)"
      (is (= 400 (first (error-info (mk [{:code "X" :from_currency "USD" :to_currency "CNY" :rate "7.1"
                                          :effective_from "2026-01-01"}]))))))
    (testing "来源与目标币种相同400"
      (is (= 400 (first (error-info (mk [{:from_currency "USD" :to_currency "USD" :rate "1"
                                          :effective_from "2026-01-01"}]))))))
    (testing "不支持的币种400"
      (is (= 400 (first (error-info (mk [{:from_currency "JPY" :to_currency "CNY" :rate "1"
                                          :effective_from "2026-01-01"}]))))))
    (testing "非正汇率400"
      (is (= 400 (first (error-info (mk [{:from_currency "USD" :to_currency "CNY" :rate "0"
                                          :effective_from "2026-01-01"}]))))))
    (testing "超过8位小数400"
      (is (= 400 (first (error-info (mk [{:from_currency "USD" :to_currency "CNY" :rate "7.123456789"
                                          :effective_from "2026-01-01"}]))))))
    (testing "止日早于起日400"
      (is (= 400 (first (error-info (mk [{:from_currency "USD" :to_currency "CNY" :rate "7.1"
                                          :effective_from "2026-05-01" :effective_to "2026-04-01"}]))))))))


(deftest exchange-rate-resolves-and-converts-without-floating-point
  (let [rates [{:from_currency "USD" :to_currency "CNY" :rate "7.25"
                :effective_from "2026-01-01" :effective_to "2026-06-30"}
               {:from_currency "USD" :to_currency "CNY" :rate "7.50"
                :effective_from "2026-07-01"}
               {:from_currency "CNY" :to_currency "EUR" :rate "0.13"
                :effective_from "2026-01-01"}]]
    (testing "直接命中, 含端点"
      (is (= "7.25" (:rate (forecast/resolve-rate rates "USD" "CNY" "2026-06-30" "CNY"))))
      (is (= "7.50" (:rate (forecast/resolve-rate rates "USD" "CNY" "2026-07-01" "CNY")))))
    (testing "未命中有效期返回 nil, 不猜汇率"
      (is (nil? (forecast/resolve-rate rates "USD" "CNY" "2025-12-31" "CNY")))
      (is (nil? (forecast/resolve-rate rates "GBP" "CNY" "2026-03-01" "CNY"))))
    (testing "同币种恒为 1"
      (is (= "1" (:rate (forecast/resolve-rate rates "USD" "USD" "2026-03-01" "CNY")))))
    (testing "经本位币中转: USD->CNY->EUR, 乘积保留8位"
      (let [r (forecast/resolve-rate rates "USD" "EUR" "2026-03-01" "CNY")]
        (is (= "CNY" (second (:path r))))
        (is (true? (:via-base r)))
        (is (= "0.94250000" (:rate r)))))
    (testing "折算四舍五入到分, 不经浮点"
      (is (= 725 (forecast/convert-minor 100 "7.25")))
      (is (= 439 (forecast/convert-minor 35 "12.55")))
      (is (= 1 (forecast/convert-minor 1 "0.5")) "0.5 分向上取整到 1 分"))))


(deftest forecast-converts-approved-actual-with-published-rate
  (let [{:keys [id]} (execution-project!)
        body (fn [kind currency amount]
               (:result (command! cost/create! id [] {:kind kind :period "2026-09" :currency currency
                                                      :name (str kind "-fx") :revenue "0" :reviewer_id 9402})))
        entry (fn [cid amount] (command! cost/add-entry! id [cid] {:category "material" :label "材料" :amount amount}))
        approve (fn [cid] (command! cost/submit! id [cid] {}) (command! 9402 cost/review! id [cid] {:decision "approved" :reason "核对"}))
        budget (body "budget" "CNY" nil) actual (body "actual" "USD" nil)]
    (entry (:id budget) "1000")
    (approve (:id budget))
    (entry (:id actual) "100")
    (approve (:id actual))
    (testing "无已发布汇率 -> 不可算且标记币种不一致, 不隐式换汇"
      (let [f (forecast/forecast (:query-fn *svc*) id "budget" "2026-03-01")]
        (is (false? (:available f)))
        (is (true? (:currency_mismatch f)))
        (is (nil? (:eac_minor f)))
        (is (nil? (:fx f)))))
    (testing "发布汇率后 -> 实际折算到预算币种, 标注汇率与折算日期"
      (let [rec (config/create! *svc* (actor 9401) "exchange-rate"
                                {:base_currency "CNY" :reason "验汇率"
                                 :rates [{:from_currency "USD" :to_currency "CNY" :rate "7.25"
                                          :effective_from "2026-01-01"}]})]
        (config/publish! *svc* (actor 9401) "exchange-rate" (:id rec) {:reason "启用汇率"}))
      (let [f (forecast/forecast (:query-fn *svc*) id "budget" "2026-03-01")]
        (is (true? (:available f)))
        (is (false? (:currency_mismatch f)))
        (is (= 10000 (:ac_source_minor f)) "原始美元金额保留")
        (is (= 72500 (:ac_minor f)) "100 USD x 7.25 -> 725 CNY")
        (is (= 27500 (:etc_minor f)))
        (is (= 100000 (:eac_minor f)))
        (is (= "7.25" (get-in f [:fx :rate])))
        (is (= "2026-03-01" (get-in f [:fx :date])))
        (is (= "USD" (get-in f [:fx :from])))
        (is (true? (get-in f [:fx :converted])))))
    (testing "概览顶层 forecast 也走同一折算口径"
      (let [f (get-in (finance/overview *svc* (actor 9401) id) [:forecast :budget])]
        (is (true? (:available f)))
        (is (= "725.00" (:ac f)))))))


(deftest tax-rate-validates-scope-and-ceiling
  ;; 税率表: 编码由本位币派生; 比例必须 0-1 且最多 6 位小数; 净利率不得超过 1 - 增值税税率.
  (let [mk (fn [m] #(config/create! *svc* (actor 9401) "tax-rate" (merge {:reason "验税率"} m)))]
    (testing "净利率超过 1 - 税率 400"
      (is (= [400 "净利率不能超过 1 - 增值税税率"]
             (error-info (mk {:base_currency "CNY" :vat_rate "0.13" :net_rate "0.9"})))))
    (testing "增值税税率超过 1 400"
      (is (= 400 (first (error-info (mk {:base_currency "CNY" :vat_rate "1.2" :net_rate "0.1"}))))))
    (testing "比例不是小数 400"
      (is (= 400 (first (error-info (mk {:base_currency "CNY" :vat_rate "十三" :net_rate "0.1"}))))))
    (testing "不支持的本位币 400"
      (is (= 400 (first (error-info (mk {:base_currency "JPY" :vat_rate "0.13" :net_rate "0.1"}))))))
    (testing "合法税率表编码为 TAX-<本位币>, 比例规范化为两位小数"
      (let [rec ((mk {:base_currency "EUR" :vat_rate "0.2" :net_rate "0.05"}))]
        (is (= "TAX-EUR" (:code rec)))
        (is (= "0.20" (:vat_rate rec)))
        (is (= "0.05" (:net_rate rec)))))))


(deftest net-margin-requires-published-tax-rate
  ;; F08 税后净利只读派生: 无税率表时如实不可算; 发布后按 收入 - 税额 - 净利率×收入 派生.
  (let [{:keys [id]} (execution-project!)
        create (fn [kind revenue]
                 (:result (command! cost/create! id [] {:kind kind :period "2026-09" :currency "CNY"
                                                        :name (str kind "-tax") :revenue revenue :reviewer_id 9402})))
        entry (fn [cid amount] (command! cost/add-entry! id [cid] {:category "material" :label "材料" :amount amount}))
        approve (fn [cid] (command! cost/submit! id [cid] {}) (command! 9402 cost/review! id [cid] {:decision "approved" :reason "核对"}))
        estimate (create "estimate" "1000")]
    (entry (:id estimate) "600")
    (approve (:id estimate))
    (let [cm (:cost_margin (finance/overview *svc* (actor 9401) id))]
      (is (nil? (:tax cm)) "无已发布税率表时不给税率口径")
      (is (false? (:taxable cm)))
      (is (nil? (:net_margin (first (filter #(= "estimate" (:kind %)) (:by-kind cm))))) "净利不可算而非取默认税率"))
    ;; 发布税率表 13% 增值税 + 5% 净利率.
    (let [rec (config/create! *svc* (actor 9401) "tax-rate" {:base_currency "CNY" :vat_rate "0.13" :net_rate "0.05" :reason "验净利"})]
      (config/publish! *svc* (actor 9401) "tax-rate" (:id rec) {:reason "启用税率"}))
    (let [cm (:cost_margin (finance/overview *svc* (actor 9401) id))
          est (first (filter #(= "estimate" (:kind %)) (:by-kind cm)))]
      (is (= "TAX-CNY" (get-in cm [:tax :code])))
      (is (true? (:taxable cm)))
      (is (true? (:taxable est)))
      (is (= "0.13" (:vat_rate est)))
      (is (= "130.00" (:tax_amount est)) "1000 x 13% = 130.00")
      (is (= "820.00" (:net_margin est)) "1000 - 130 (13%) - 50 (5%) = 820.00")
      (is (= 82 (:net_margin_pct est)))
      (is (= "400.00" (:margin est)) "税前毛利不受税率影响"))
    (testing "税率表币种与版本币种不一致时不套用税率 (不猜折算税率)"
      (let [usd (create "budget" "1000")
            cm (do (entry (:id usd) "100") (approve (:id usd))
                   (:cost_margin (finance/overview *svc* (actor 9401) id)))
            budget (first (filter #(= "budget" (:kind %)) (:by-kind cm)))]
        (is (true? (:taxable budget)) "本币版本仍按 CNY 税率派生")))
    (testing "净利率上限: 加入 80% 净利率与 13% 税率组合仍合法, 1 - 0.13 = 0.87"
      (is (fn? #(config/create! *svc* (actor 9401) "tax-rate" {:base_currency "GBP" :vat_rate "0.13" :net_rate "0.87" :reason "边界"}))))))


(defn- change!
  "登记并独立批准一条正式变更, 返回服务端派生的业务编码及可选量化成本影响与变更类型."
  [id title & {:keys [cost type days]}]
  (let [body (cond-> {:title title :reason "客户要求" :scope_impact "范围" :schedule_impact "工期"
                      :cost_impact "成本" :quality_impact "质量" :resource_impact "资源"}
               cost (assoc :cost_impact_amount cost)
               type (assoc :change_type type)
               days (assoc :schedule_impact_days days))
        rec (gov! id :changes :create nil body)]
    (gov! id :changes :submit (:id rec) {:reviewer_id 9402})
    (gov! 9402 id :changes :decision (:id rec) {:decision "approved" :reason "影响可接受"})
    (:code rec)))


(defn- change-loss-entry!
  "在指定口径版本中新增一条变更损失明细."
  [id cid amount]
  (let [v (:result (command! cost/add-entry! id [cid]
                             {:category "change_loss" :label "变更损失" :amount amount}))]
    (last (filter #(and (= "change_loss" (:category %)) (= amount (:amount %))) (:entries v)))))


(deftest change-loss-binds-to-approved-change
  (let [id (project!)
        code (change! id "CC-1001" :cost "5000.00" :type "corrective" :days 6)
        actual (cost! id "actual")
        entry (change-loss-entry! id (:id actual) "5000.00")]
    (testing "损失明细可关联已批准变更"
      (is (nil? (:change_code entry)) "未关联时不含变更编码")
      (let [v (:result (command! cost/bind-change! id [(:id actual) (:id entry)] {:change_code code}))
            bound (first (filter #(= "change_loss" (:category %)) (:entries v)))]
        (is (= code (:change_code bound)))))
    (testing "同一变更在同版本内不重复计费 409"
      (let [other (:result (command! cost/add-entry! id [(:id actual)]
                                     {:category "change_loss" :label "重复损失" :amount "1.00"
                                      :source_ref "dup-loss"}))
            dup (first (filter #(= "重复损失" (:label %)) (:entries other)))]
        (is (= 409 (error-status #(command! cost/bind-change! id [(:id actual) (:id dup)]
                                            {:change_code code}))))))
    (testing "同一变更在同一版本内即使金额一致也不重复计费 409"
      (let [twin (:result (command! cost/add-entry! id [(:id actual)]
                                    {:category "change_loss" :label "同额损失" :amount "5000.00"
                                     :source_ref "twin-loss"}))
            same (first (filter #(= "同额损失" (:label %)) (:entries twin)))]
        (is (= 409 (error-status #(command! cost/bind-change! id [(:id actual) (:id same)]
                                            {:change_code code}))))))
    (testing "非变更损失分类拒绝 400"
      (let [v (:result (command! cost/add-entry! id [(:id actual)]
                                 {:category "material" :label "材料" :amount "1.00"}))
            material (first (filter #(= "material" (:category %)) (:entries v)))]
        (is (= 400 (error-status #(command! cost/bind-change! id [(:id actual) (:id material)]
                                            {:change_code code}))))))
    (testing "未批准的变更不能关联 409"
      (let [draft (gov! id :changes :create nil {:title "草案" :reason "草案" :scope_impact "s"
                                                 :schedule_impact "t" :cost_impact "c" :quality_impact "q"
                                                 :resource_impact "r"})
            extra (change-loss-entry! id (:id actual) "10.00")]
        (is (some? draft))
        (is (= 409 (error-status #(command! cost/bind-change! id [(:id actual) (:id extra)]
                                            {:change_code (:code draft)}))))))))


(deftest change-loss-scope-guards
  (let [id (project!)
        code (change! id "CC-3001" :cost "100.00")
        estimate (cost! id "estimate")
        entry (change-loss-entry! id (:id estimate) "100.00")]
    (testing "只有核算口径可以登记变更损失"
      (is (= 400 (error-status #(command! cost/bind-change! id [(:id estimate) (:id entry)]
                                          {:change_code code})))))
    (let [actual (cost! id "actual")
          e1 (change-loss-entry! id (:id actual) "120.00")
          e2 (change-loss-entry! id (:id actual) "140.00")]
      (testing "损失金额必须与变更已声明成本影响一致"
        (is (= 409 (error-status #(command! cost/bind-change! id [(:id actual) (:id e1)]
                                            {:change_code code})))))
      (testing "已批准版本的明细不可再关联"
        (let [ok (change-loss-entry! id (:id actual) "100.00")
              v (:result (command! cost/bind-change! id [(:id actual) (:id ok)] {:change_code code}))]
          (is (some? v))
          (command! cost/submit! id [(:id actual)] {})
          (command! 9402 cost/review! id [(:id actual)] {:decision "approved" :reason "核对"})
          (is (= 409 (error-status #(command! cost/bind-change! id [(:id actual) (:id e2)]
                                              {:change_code code})))))))))


(deftest change-loss-summary-rolls-up-by-product-line-and-type
  (let [id (project!)
        code-a (change! id "CC-A" :cost "300.00" :type "corrective" :days 4)
        code-b (change! id "CC-B" :cost "200.00" :type "updates" :days 2)
        actual (cost! id "actual")
        e1 (change-loss-entry! id (:id actual) "300.00") e2 (change-loss-entry! id (:id actual) "200.00")]
    (command! cost/bind-change! id [(:id actual) (:id e1)] {:change_code code-a})
    (command! cost/bind-change! id [(:id actual) (:id e2)] {:change_code code-b})
    (testing "未批准版本不计入汇总"
      (is (zero? (:total_count (:change_loss (finance/overview *svc* (actor 9401) id))))))
    (approve-cost! id (:id actual))
    (let [cl (:change_loss (finance/overview *svc* (actor 9401) id))]
      (is (true? (:available cl)))
      (is (true? (:reconciled cl)))
      (is (= 2 (:total_count cl)))
      (is (= "500.00" (:total_amount cl)))
      (is (= 6 (:total_schedule_days cl)))
      (is (= "CNY" (:currency cl)))
      (is (= 1 (count (:by_product_line cl))) "未填合同号时归入同一兜底产品线")
      (is (= "-" (:key (first (:by_product_line cl)))))
      (is (= "500.00" (:amount (first (:by_product_line cl)))))
      (let [types (into {} (map (juxt :key :count) (:by_type cl)))]
        (is (= 1 (get types "corrective")))
        (is (= 1 (get types "updates"))))
      (testing "汇总为只读派生, 不改变任何费用明细"
        (let [v (cost/version! (:query-fn *svc*) id (:id actual))]
          (is (empty? (filter #(and (= "change_loss" (:category %)) (nil? (:change_code %)))
                              ((:query-fn *svc*) :finance/entries {:project_id id :version_id (:id actual)})))))))))
