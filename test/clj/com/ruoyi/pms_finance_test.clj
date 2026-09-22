(ns com.ruoyi.pms-finance-test
  "验证真实数据库中的精确成本,工时审批和完整生命周期闭环."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
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
