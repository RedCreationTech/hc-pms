(ns com.ruoyi.pms-finance-test
  "验证真实数据库中的精确成本,工时审批和完整生命周期闭环."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.domain.pms.planning :as plan]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.finance :as finance]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-time :as time]
            [com.ruoyi.domain.pms.finance-allocation :as allocation]
            [com.ruoyi.domain.pms.closure :as closure]
            [com.ruoyi.domain.pms.approval-chain :as chain]
            [com.ruoyi.domain.pms.reopen :as reopen]
            [com.ruoyi.pms-delivery-scenario :as scenario]
            [com.ruoyi.domain.pms.lifecycle :as lifecycle]
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
            query (fn ([k p] ((get-in queries [k :fn]) p))
                      ([tx k p] ((get-in queries [k :fn]) tx p)))]
        (binding [*svc* {:db db :query-fn query}] (f)))
      (finally (when file (.delete file))))))

(use-fixtures :once fixture)

(defn- actor "读取实际有效身份和权限." [id] (pms/actor *svc* {:user-id id}))
(defn- version "读取当前项目版本." [id] (:version ((:query-fn *svc*) :pms/project {:project_id id})))
(defn- error-status "提取业务错误状态." [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))
(defn- error-info "提取业务错误状态与文案, 成功时返回 nil." [f]
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

(defn- transition! "通过受控命令推进生命周期." [id status]
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
        failing (assoc *svc* :query-fn (fn ([k p] (query k p))
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
