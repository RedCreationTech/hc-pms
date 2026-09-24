(ns com.ruoyi.pms-portfolio-test
  "追踪覆盖率与偏差 (C03), 文档多层下钻 (C04), 密级访问 (C05), 逾期追溯升级 (C09), 我的待办 (C07), 全局检索 (G16),
   项目组合看板 (G02), 工时更正与封期 (F04), 跨项目研发费用池 (F05), 四算拉通 (F06), 经营目标 (F09) 的真实数据库测试."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.finance :as finance]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-pool :as pool]
            [com.ruoyi.domain.pms.finance-time :as time]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.planning :as planning]
            [com.ruoyi.domain.pms.portfolio :as portfolio]
            [com.ruoyi.domain.pms.queries :as queries]
            [com.ruoyi.domain.pms.service :as pms]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [java.nio.file Files]
           [java.time LocalDate]
           [java.util UUID]))


(def ^:dynamic *service* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  "9701 全权限, 9702 全权限审核人, 9703 无机密文档权限的普通成员 (仅列表/查询/编辑)."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9700,'Portfolio test','pms-portfolio-test',70,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9700,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9710,'Portfolio member','pms-portfolio-member',71,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9710,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:project:edit','pms:dashboard:query')"])
  (doseq [[id role] [[9701 9700] [9702 9700] [9703 9710]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "portfolio-test-" id) (str "组合测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-portfolio-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (seed! db)
      (binding [*service* {:db db :query-fn (query-function db)}] (f))
      (finally (when file (Files/deleteIfExists file))))))


(use-fixtures :once database-fixture)


(defn- actor [uid] (pms/actor *service* {:user-id uid}))


(defn- error-status
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- version [id] (:version (pms/project *service* (actor 9701) id)))


(defn- gov!
  ([id resource action rid body] (gov! 9701 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- project!
  ([] (project! "组合验证项目"))
  ([name]
   (let [id (:project_id (pms/create-project! *service* (actor 9701)
                                              {:project_no (str "PF-" (subs (str (UUID/randomUUID)) 0 8)) :name name
                                               :customer "本地测试" :contract_no "PF-TEST" :project_type "equipment"
                                               :manager_id 9701 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
     (pms/set-member! *service* (actor 9701) id {:user_id 9702 :role "viewer"})
     (pms/set-member! *service* (actor 9701) id {:user_id 9703 :role "editor"})
     id)))


(defn- transition!
  [id status]
  (pms/transition-project! *service* (actor 9701) id {:version (version id) :status status :reason "组合集成测试"}))


(defn- executing-project!
  "章程/Gate/基线均独立批准后进入执行, 返回 {:id :task_id :evidence}."
  [name]
  (let [id (project! name)
        document (gov! id :documents :create nil {:code "PF-EV" :title "证据" :filename "pf.txt" :content "record"})
        task (:result (planning/create-task! *service* (actor 9701) id {:version (version id) :wbs_code "T-1" :name "执行任务" :owner_id 9701 :start_date "2026-09-22" :duration_days 2}))
        charter (gov! id :charters :create nil {:title "章程" :objective "目标" :scope "范围" :success_criteria "准则" :sponsor_id 9702})
        template (gov! id :gate-templates :create nil {:code "EXEC-G" :title "执行前确认" :stage "execution" :required true :checks [{:code "C" :title "证据齐全" :required true}]})
        gate (gov! id :gates :create nil {:template_id (:id template) :title "执行确认" :reviewer_id 9702})]
    (gov! id :charters :submit (:id charter) {:reviewer_id 9702})
    (gov! 9702 id :charters :decision (:id charter) {:decision "approved" :reason "通过"})
    (gov! id :gates :checks (:id gate) {:checks [{:code "C" :passed true :evidence_ids [(:id document)]}]})
    (gov! id :gates :submit (:id gate) {})
    (gov! 9702 id :gates :decision (:id gate) {:decision "approved" :reason "独立验证"})
    (transition! id "initiated")
    (transition! id "planning")
    (let [baseline (:result (planning/submit-plan! *service* (actor 9701) id {:version (version id) :comment "提交"}))]
      (planning/review-plan! *service* (actor 9702) id (:baseline_id baseline) {:version (version id) :decision "approved" :comment "独立确认"}))
    (transition! id "execution")
    {:id id :task_id (:task_id task) :evidence (:id document)}))


(defn- today-minus [days] (str (.minusDays (LocalDate/now) days)))


(deftest traceability-coverage-phases-and-deviation-levels
  (let [id (project!)
        req1 (gov! id :requirements :create nil {:code "R-1" :text "需求一" :category "功能" :priority "required" :owner_id 9701})
        req2 (gov! id :requirements :create nil {:code "R-2" :text "需求二" :category "功能" :priority "required" :owner_id 9701})
        doc (gov! id :documents :create nil {:code "D-1" :title "设计文件" :filename "d.txt" :content "design"})]
    (is (= 400 (error-status #(gov! id :traces :create nil {:requirement_id (:id req1) :target_kind "document" :target_id (:id doc) :relation "satisfies" :deviation_level "major"}))))
    (is (= 400 (error-status #(gov! id :traces :create nil {:requirement_id (:id req1) :target_kind "document" :target_id (:id doc) :relation "satisfies" :phase "nope"}))))
    (gov! id :traces :create nil {:requirement_id (:id req1) :target_kind "document" :target_id (:id doc) :relation "satisfies" :phase "design"})
    (gov! id :traces :create nil {:requirement_id (:id req1) :target_kind "document" :target_id (:id doc) :relation "verifies" :phase "FAT" :deviation_level "major" :deviation_note "FAT 测得压力偏差 5%"})
    (let [ws (gov/workspace *service* (actor 9701) id) summary (:trace_summary ws)
          row (first (filter #(= (:id req1) (:requirement_id %)) (:traceability ws)))]
      (is (= 2 (:requirements summary)))
      (is (= 1 (:fully-traced summary)))
      (is (= 50 (get summary :coverage-pct)))
      (is (= 50 (get summary :design-pct)))
      (is (= 1 (get-in summary [:by-phase "FAT"])))
      (is (= 1 (get-in summary [:deviations "major"])))
      (is (= 0 (:blocking-deviations summary)))
      (is (= "major" (:worst_deviation row)))
      (is (= #{"design" "FAT"} (set (:phases row)))))
    (is (some? req2))))


(deftest document-tree-and-confidential-access
  (let [id (project!)
        public (gov! id :documents :create nil {:code "PUB-1" :title "公开文件" :filename "p.txt" :content "public" :classification "public" :stage "design" :structure_node "U1"})
        secret (gov! id :documents :create nil {:code "SEC-1" :title "机密文件" :filename "s.txt" :content "secret" :classification "confidential" :stage "design" :structure_node "U1"})
        tree (:document_tree (gov/workspace *service* (actor 9701) id))]
    (is (= 1 (count tree)))
    (is (= "design" (:stage (first tree))))
    (is (= 2 (:count (first tree))))
    (is (= #{"public" "confidential"} (set (map :classification (:classifications (first (:nodes (first tree))))))))
    ;; C05: 无密级权限成员可读公开文档, 机密文档正文 403; 批量打包含机密同样 403; 管理员/有权限者可读.
    (is (= "public" (:content (gov/document-content *service* (actor 9703) id (:id public)))))
    (is (= 403 (error-status #(gov/document-content *service* (actor 9703) id (:id secret)))))
    (is (= 403 (error-status #(gov/document-batch *service* (actor 9703) id {:record_ids [(:id public) (:id secret)]}))))
    (is (= "secret" (:content (gov/document-content *service* (actor 9701) id (:id secret)))))
    (is (= 2 (count (:documents (gov/document-batch *service* (actor 9701) id {:record_ids [(:id public) (:id secret)]})))))
    ;; 检索: 无密级权限者看不到机密文档, 有权限者可见; 密级过滤生效.
    (is (empty? (filter #(= "SEC-1" (:code %)) (:results (portfolio/search *service* (actor 9703) {:q "机密"})))))
    (is (= 1 (count (filter #(= "SEC-1" (:code %)) (:results (portfolio/search *service* (actor 9701) {:q "机密"}))))))
    (is (= 1 (count (:results (portfolio/search *service* (actor 9701) {:q "文件" :classification "public"})))))
    (is (= 400 (error-status #(portfolio/search *service* (actor 9701) {:q ""}))))))


(deftest overdue-issue-can-be-retroactively-escalated
  (let [id (project!)
        issue (gov! id :issues :create nil {:title "逾期严重问题" :severity "major" :owner_id 9703 :due_date (today-minus 3)})
        fresh (gov! id :issues :create nil {:title "未到期问题" :severity "major" :owner_id 9703 :due_date "2099-01-01"})]
    (is (nil? (:escalated issue)))
    (let [model (first (filter #(= (:id issue) (:id %)) (:issues (gov/workspace *service* (actor 9701) id))))]
      (is (true? (:issue_escalation_suggested model)))
      (is (true? (:issue_overdue model))))
    (is (= 409 (error-status #(gov! id :issues :escalate-overdue (:id fresh) {:reason "未逾期"}))))
    (let [escalated (gov! id :issues :escalate-overdue (:id issue) {:reason "客户催办"})]
      (is (true? (:escalated escalated)))
      (is (= "pending" (:escalation_state escalated)))
      (is (= "overdue_retroactive" (:escalation_source escalated)))
      (is (= 409 (error-status #(gov! id :issues :escalate-overdue (:id issue) {:reason "again"}))))
      ;; 升级待确认期间提交解决被门控; 独立审批人确认后放行.
      (is (= 409 (error-status #(gov! 9703 id :issues :resolve (:id issue) {:resolution "已处理" :evidence_ids [] :reviewer_id 9702}))))
      (is (= "acknowledged" (:escalation_state (gov! 9702 id :issues :escalate (:id issue) {:decision "approved" :note "确认升级处置"})))))
    ;; 待办: 审批人看到升级确认(已确认后消失), 责任人看到逾期问题.
    (let [todo (portfolio/todo *service* (actor 9703))]
      (is (some #(and (= (:id issue) (:id %)) (:overdue %)) (:owned todo)))
      (is (pos? (get-in todo [:summary :overdue]))))))


(deftest todo-collects-reviews-across-projects
  (let [a (project! "待办项目A") b (project! "待办项目B")
        charter (gov! a :charters :create nil {:title "章程A" :objective "目标" :scope "范围" :success_criteria "准则" :sponsor_id 9702})
        doc (gov! b :documents :create nil {:code "TD-1" :title "待发布文档" :filename "t.txt" :content "x"})]
    (gov! a :charters :submit (:id charter) {:reviewer_id 9702})
    (gov! b :documents :submit (:id doc) {:reviewer_id 9702})
    (let [todo (portfolio/todo *service* (actor 9702))
          reviews (:reviews todo)]
      (is (<= 2 (count reviews)))
      (is (some #(and (= a (:project_id %)) (= "章程" (:label %))) reviews))
      (is (some #(and (= b (:project_id %)) (= "文档发布" (:label %))) reviews))
      (is (= "local_only" (:delivery_status todo))))
    ;; 提交人自己看不到自审待办.
    (is (empty? (filter #(= (:id charter) (:id %)) (:reviews (portfolio/todo *service* (actor 9701))))))
    ;; 组合看板覆盖两个项目并给出结构进度.
    (let [board (portfolio/portfolio *service* (actor 9701))
          card (first (filter #(= a (:project_id %)) (:projects board)))]
      (is (<= 2 (get-in board [:summary :total])))
      (is (= 0 (:overall_percent card)))
      (is (= 1 (count (:nodes card))))
      (is (= 0 (:gates_total card)))
      (is (map? (:finance card))))
    ;; 无财务权限用户看不到成本摘要.
    (is (nil? (:finance (first (:projects (portfolio/portfolio *service* (actor 9703)))))))))


(deftest timesheet-correction-and-period-lock
  (let [{:keys [id task_id]} (executing-project! "工时更正项目")
        date (today-minus 1)
        submit (fn [] (time/submit! *service* (actor 9701) id {:version (version id) :task_id task_id :work_date date :hours 4 :note "开发" :reviewer_id 9702}))
        entry (:result (submit))]
    (is (= "submitted" (:status entry)))
    (time/review! *service* (actor 9702) id (:id entry) {:version (version id) :decision "approved" :reason "ok"})
    ;; 非本人不能更正; 更正后原单 corrected, 更正单待审核.
    (is (= 403 (error-status #(time/correct! *service* (actor 9703) id (:id entry) {:version (version id) :hours 6 :note "改" :reason "记错" :reviewer_id 9702}))))
    (let [correction (:result (time/correct! *service* (actor 9701) id (:id entry) {:version (version id) :hours 6 :note "开发(更正)" :reason "记错工时" :reviewer_id 9702}))
          rows (:rows (finance/times *service* (actor 9701) id))]
      (is (= "submitted" (:status correction)))
      (is (= (:id entry) (:corrects_entry_id correction)))
      (is (= "corrected" (:status (first (filter #(= (:id entry) (:id %)) rows)))))
      (is (= 409 (error-status #(time/correct! *service* (actor 9701) id (:id entry) {:version (version id) :hours 5 :note "x" :reason "y" :reviewer_id 9702}))))
      ;; 驳回更正单 -> 原单恢复已批准.
      (time/review! *service* (actor 9702) id (:id correction) {:version (version id) :decision "rejected" :reason "证据不足"})
      (is (= "approved" (:status (first (filter #(= (:id entry) (:id %)) (:rows (finance/times *service* (actor 9701) id)))))))
      ;; 再次更正并批准 -> 原单 corrected, 更正单 approved.
      (let [second-correction (:result (time/correct! *service* (actor 9701) id (:id entry) {:version (version id) :hours 5 :note "开发(更正2)" :reason "二次更正" :reviewer_id 9702}))]
        (time/review! *service* (actor 9702) id (:id second-correction) {:version (version id) :decision "approved" :reason "ok"})
        (let [rows (:rows (finance/times *service* (actor 9701) id))]
          (is (= "corrected" (:status (first (filter #(= (:id entry) (:id %)) rows)))))
          (is (= "approved" (:status (first (filter #(= (:id second-correction) (:id %)) rows))))))))
    ;; 封期: 锁定该期间后提交与更正均 409, 解锁后恢复.
    (let [lock (config/create! *service* (actor 9701) "period-lock" {:period (subs date 0 7) :reason "月度封账"})]
      (is (= 409 (error-status submit)))
      (config/retire! *service* (actor 9701) "period-lock" (:id lock) {:reason "解锁"})
      (is (= "submitted" (:status (:result (submit))))))))


(deftest rd-pool-allocates-across-projects-and-conserves
  (let [p1 (executing-project! "费用池项目一") p2 (executing-project! "费用池项目二")
        period "2026-07"
        approve! (fn [{:keys [id task_id]} date hours]
                   (let [entry (:result (time/submit! *service* (actor 9701) id {:version (version id) :task_id task_id :work_date date :hours hours :note "研发" :reviewer_id 9702}))]
                     (time/review! *service* (actor 9702) id (:id entry) {:version (version id) :decision "approved" :reason "ok"})))]
    (approve! p1 "2026-07-10" 6)
    (approve! p2 "2026-07-11" 2)
    (let [pool (config/create! *service* (actor 9701) "rd-pool" {:period period :amount "1000.01" :currency "CNY" :description "七月研发池"})]
      ;; 草稿不能分摊; 冻结后预览守恒 (3:1).
      (is (= 409 (error-status #(pool/allocate! *service* (actor 9701) (:id pool) {:reviewer_id 9702}))))
      (config/publish! *service* (actor 9701) "rd-pool" (:id pool) {:reason "冻结"})
      (let [preview (pool/preview *service* (actor 9701) (:id pool))]
        (is (true? (:conserved preview)))
        (is (= 480 (:total_minutes preview)))
        (is (= ["750.01" "250.00"] (mapv :amount (sort-by :minutes > (:rows preview))))))
      (is (= 400 (error-status #(pool/allocate! *service* (actor 9701) (:id pool) {:reviewer_id 9701}))))
      (let [result (pool/allocate! *service* (actor 9701) (:id pool) {:reviewer_id 9702})
            rows (get-in result [:allocation :rows])]
        (is (= 2 (count rows)))
        (is (= 100001 (reduce + 0 (map :amount_minor rows))))
        (is (= 409 (error-status #(pool/allocate! *service* (actor 9701) (:id pool) {:reviewer_id 9702}))))
        ;; 各项目生成待审核核算版本, 条目来源标注费用池.
        (let [overview (finance/overview *service* (actor 9701) (:id p1))
              version (first (filter #(= "actual" (:kind %)) (:cost_versions overview)))]
          (is (= "draft" (:status version)))
          (is (= "750.01" (:total version)))
          (is (= (str "rd-pool:" (:id pool)) (:source_ref (first (:entries version))))))))))


(deftest four-count-comparison-and-quarterly-targets
  (let [{:keys [id]} (executing-project! "四算项目")
        create (fn [kind revenue] (:result (cost/create! *service* (actor 9701) id {:version (version id) :kind kind :period "2026-09" :currency "CNY" :name (str kind "版本") :revenue revenue :reviewer_id 9702})))
        entry (fn [vid category amount] (cost/add-entry! *service* (actor 9701) id vid {:version (version id) :category category :label category :amount amount}))
        approve (fn [vid] (cost/submit! *service* (actor 9701) id vid {:version (version id)})
                  (cost/review! *service* (actor 9702) id vid {:version (version id) :decision "approved" :reason "ok"}))
        estimate (create "estimate" "1000") budget (create "budget" "1000") actual (create "actual" "1000")]
    (entry (:id estimate) "material" "300") (entry (:id estimate) "labor" "200") (approve (:id estimate))
    (entry (:id budget) "material" "320") (entry (:id budget) "labor" "180") (approve (:id budget))
    (entry (:id actual) "material" "350") (entry (:id actual) "labor" "190") (entry (:id actual) "travel" "10") (approve (:id actual))
    (let [fc (:four_count (finance/overview *service* (actor 9701) id))]
      (is (true? (:comparable fc)))
      (is (= "500.00" (get-in fc [:totals :estimate])))
      (is (= "500.00" (get-in fc [:totals :budget])))
      (is (= "550.00" (get-in fc [:totals :actual])))
      (is (nil? (get-in fc [:totals :settlement])))
      (is (= "0.00" (get-in fc [:variances :budget_vs_estimate])))
      (is (= "50.00" (get-in fc [:variances :actual_vs_budget])))
      (is (nil? (get-in fc [:variances :settlement_vs_actual])))
      (is (= "350.00" (:actual (first (filter #(= "material" (:category %)) (:rows fc))))))
      (is (= "10.00" (:actual (first (filter #(= "travel" (:category %)) (:rows fc))))))
      (is (= "0.00" (:budget (first (filter #(= "travel" (:category %)) (:rows fc)))))))
    ;; 经营目标看板: 已发布目标按季度汇总关闭项目 (当前无关闭项目 -> 实际 0, 达成 0%), 有财务权限取决算口径.
    (let [target (config/create! *service* (actor 9701) "quarterly-target" {:year 2026 :quarter 3 :metric "revenue" :target_value "5000" :basis "测试"})]
      (config/publish! *service* (actor 9701) "quarterly-target" (:id target) {:reason "下达"})
      (let [board (portfolio/targets *service* (actor 9701))
            row (first (filter #(= (:id target) (:id %)) (:rows board)))]
        (is (true? (:finance_visible board)))
        (is (= "0.00" (:actual row)))
        (is (= 0 (:achievement_pct row)))
        (is (= "approved_settlements" (:source row))))
      (is (false? (:finance_visible (portfolio/targets *service* (actor 9703))))))))
