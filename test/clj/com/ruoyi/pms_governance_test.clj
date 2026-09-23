(ns com.ruoyi.pms-governance-test
  "真实数据库上的治理审批,证据,整批导入和跨模块闭环测试."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.collaboration :as collab]
    [com.ruoyi.domain.pms.governance.evidence :as evidence]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.service :as pms]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.routes.api :as api]
    [com.ruoyi.web.routes.pms :as routes]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [reitit.ring :as ring]
    [ring.mock.request :as mock])
  (:import
    (java.io
      ByteArrayInputStream)
    (java.math
      BigInteger)
    (java.nio.charset
      StandardCharsets)
    (java.nio.file
      Files)
    (java.security
      MessageDigest)
    (java.util
      UUID)
    (java.util.zip
      ZipInputStream)))


(def ^:dynamic *service* nil)


(def ^:dynamic *handler* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  "绑定治理以及会议行动转任务所需的真实参数化查询."
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  "使用专属用户编号避免与其他模块共用MySQL测试库时冲突."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9300,'Governance test','pms-gov-test',30,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9300,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (doseq [id [9301 9302 9303 9304 9305]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "gov-test-" id) (str "治理测试" id)])
    (when-not (= id 9305)
      (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,9300)" id]))))


(defn- database-fixture
  "全新SQLite或显式提供的MySQL库,迁移幂等且不删除其他模块表."
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-gov-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (seed! db)
      (let [svc {:db db :query-fn (query-function db)}
            handler (ring/ring-handler (ring/router [["/api" api/route-data
                                                      (routes/pms-routes {:pms-service svc})]]))]
        (binding [*service* svc *handler* handler] (f)))
      (finally (when file (Files/deleteIfExists file))))))


(use-fixtures :once database-fixture)


(defn- actor
  "使用真实数据库身份解析当前用户权限."
  [uid]
  (pms/actor *service* {:user-id uid}))


(defn- project!
  "创建项目并分离管理者,只读审批人和普通编辑者."
  []
  (let [project (pms/create-project! *service* (actor 1)
                                     {:project_no (str "GOV-" (UUID/randomUUID)) :name "治理闭环验证"
                                      :customer "本地测试" :contract_no "GOV-TEST" :project_type "equipment"
                                      :manager_id 9301 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id project)]
    (pms/set-member! *service* (actor 1) id {:user_id 9302 :role "viewer"})
    (pms/set-member! *service* (actor 1) id {:user_id 9303 :role "editor"})
    id))


(defn- version
  "读取用于下一条命令的项目聚合版本."
  [id]
  (:version (pms/project *service* (actor 1) id)))


(defn- command!
  "用当前项目版本执行真实类型化命令."
  ([id resource action rid body] (command! 9301 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- workspace
  "读取当前项目的安全治理模型."
  [id]
  (gov/workspace *service* (actor 9301) id))


(defn- error-status
  "提取预期业务异常的HTTP状态."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- document!
  "上传真实文本作为可核验的版本证据."
  [id code]
  (command! id :documents :create nil
            {:code code :title "实际验证记录" :filename "验收.txt" :content " 真实证据\n"}))


(defn- charter-body
  "生成各字段齐全的项目章程."
  []
  {:title "项目章程" :objective "完成验收目标" :scope "约定设备及培训"
   :success_criteria "验证清单全部通过" :sponsor_id 9303})


(defn- approve!
  "由项目经理提交,只读的指定审核人独立批准."
  [id resource rid]
  (command! id resource :submit rid {:reviewer_id 9302})
  (command! 9302 id resource :decision rid {:decision "approved" :reason "独立审查通过"}))


(deftest charter-review-is-independent-and-versioned
  (let [id (project!) charter (command! id :charters :create nil (charter-body)) rid (:id charter)]
    (is (= "draft" (:status charter)))
    (is (= 400 (error-status #(command! id :charters :revisions rid (assoc (charter-body) :status "approved")))))
    (is (= 409 (error-status #(command! id :charters :submit rid {:reviewer_id 9301}))))
    (is (= 403 (error-status #(command! id :charters :submit rid {:reviewer_id 9304}))))
    (command! id :charters :submit rid {:reviewer_id 9302})
    (is (= 403 (error-status #(command! 9303 id :charters :decision rid {:decision "approved" :reason "冒名审批"}))))
    (is (= 403 (error-status #(command! id :charters :decision rid {:decision "approved" :reason "自行审批"}))))
    (is (= "approved" (:status (command! 9302 id :charters :decision rid {:decision "approved" :reason "独立通过"}))))
    (let [revision (command! id :charters :revisions rid (assoc (charter-body) :scope "调整后的受控范围"))]
      (is (= 2 (:revision revision)))
      (is (= rid (:previous_id revision)))
      (is (= 409 (error-status #(command! id :charters :submit rid {:reviewer_id 9302}))))
      (is (= "approved" (:status (first (filter #(= rid (:id %)) (:charters (workspace id)))))))
      (is (re-find #"章程" (first (get-in (workspace id) [:blockers :execution])))))))


(deftest charter-initial-budget-is-validated-and-versioned
  (let [id (project!)]
    ;; 缺省币种: 填金额未选币种记为CNY, 金额规范化为两位小数并随版本不可变持久化.
    (let [charter (command! id :charters :create nil
                            (assoc (charter-body) :initial_budget "120000.5"))
          rid (:id charter)]
      (is (= "120000.50" (:initial_budget charter)))
      (is (= "CNY" (:budget_currency charter)))
      ;; 独立批准后预算不漂移, 仍回显在安全治理模型中.
      (approve! id :charters rid)
      (let [stored (first (filter #(= rid (:id %)) (:charters (workspace id))))]
        (is (= "approved" (:status stored)))
        (is (= "120000.50" (:initial_budget stored)))
        (is (= "CNY" (:budget_currency stored))))
      ;; 修订生成新不可变版本, 旧版本预算保持原值.
      (let [revision (command! id :charters :revisions rid
                               (assoc (charter-body) :initial_budget "88.9" :budget_currency "USD"))]
        (is (= 2 (:revision revision)))
        (is (= "88.90" (:initial_budget revision)))
        (is (= "USD" (:budget_currency revision)))
        (let [old (first (filter #(= rid (:id %)) (:charters (workspace id))))]
          (is (= "120000.50" (:initial_budget old)))
          (is (= "CNY" (:budget_currency old))))))
    ;; 未填预算: 章程仍可创建, 不含任何预算键.
    (let [plain (command! id :charters :create nil (charter-body))]
      (is (nil? (:initial_budget plain)))
      (is (nil? (:budget_currency plain))))
    ;; 非法金额, 负数, 非法币种均被真实类型边界拒绝.
    (is (= 400 (error-status #(command! id :charters :create nil (assoc (charter-body) :initial_budget "1.234")))))
    (is (= 400 (error-status #(command! id :charters :create nil (assoc (charter-body) :initial_budget "abc")))))
    (is (= 400 (error-status #(command! id :charters :create nil (assoc (charter-body) :initial_budget "-5")))))
    (is (= 400 (error-status #(command! id :charters :create nil
                                        (assoc (charter-body) :initial_budget "100" :budget_currency "RUB")))))
    ;; 预算是章程专属字段, 在合法的变更申请体上追加预算应被白名单拒绝.
    (is (= 400 (error-status #(command! id :changes :create nil
                                        {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                                         :schedule_impact "增加五日" :cost_impact "重新估价" :quality_impact "增加测试"
                                         :resource_impact "追加工程师" :initial_budget "100"}))))))


(deftest charter-authorized-pm-is-explicit-validated-and-versioned
  (let [id (project!)
        charter (command! id :charters :create nil (assoc (charter-body) :authorized_pm_id 9303))
        rid (:id charter)]
    (is (= 9303 (:authorized_pm_id charter)))
    ;; 独立批准后授权 PM 不漂移, 仍回显在冻结的章程版本中.
    (approve! id :charters rid)
    (let [stored (first (filter #(= rid (:id %)) (:charters (workspace id))))]
      (is (= "approved" (:status stored)))
      (is (= 9303 (:authorized_pm_id stored))))
    ;; 修订生成新不可变版本, 旧版本授权 PM 保持原值(批准依据不漂移).
    (let [revision (command! id :charters :revisions rid (assoc (charter-body) :authorized_pm_id 9301))
          old (first (filter #(= rid (:id %)) (:charters (workspace id))))]
      (is (= 2 (:revision revision)))
      (is (= 9301 (:authorized_pm_id revision)))
      (is (= 9303 (:authorized_pm_id old))))
    ;; 未指定授权 PM: 章程仍可创建, 不含该键(此时仍由项目 manager_id 隐含承载).
    (let [plain (command! id :charters :create nil (charter-body))]
      (is (nil? (:authorized_pm_id plain))))
    ;; 非法授权 PM(不存在或非本地用户)被真实人员边界拒绝.
    (is (= 400 (error-status #(command! id :charters :create nil (assoc (charter-body) :authorized_pm_id 99999)))))
    ;; 授权 PM 是章程专属字段, 在合法变更申请体上追加应被白名单拒绝.
    (is (= 400 (error-status #(command! id :changes :create nil
                                        {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                                         :schedule_impact "增加五日" :cost_impact "重新估价" :quality_impact "增加测试"
                                         :resource_impact "追加工程师" :authorized_pm_id 9303}))))))


(deftest evidence-is-real-immutable-and-scoped
  (let [id (project!) other (project!) document (document! id "DOC-1") rid (:id document)
        revised (command! id :documents :revisions rid
                          {:code "DOC-1" :title "新记录" :filename "new.txt" :content "不同的正文"})
        requirement (command! id :requirements :create nil
                              {:code "URS-1" :text "必须验证" :category "功能" :priority "required" :owner_id 9301})]
    (is (= " 真实证据\n" (:content (gov/document-content *service* (actor 9301) id rid))))
    (is (= 14 (:byte_size document)))
    (is (= "8dea4e680c9f59a2edddbac7b297dac40a430cc9329969c4b0300a58b625ebd6" (:sha256 document)))
    (is (not= (:sha256 document) (:sha256 revised)))
    (is (= 2 (:revision revised)))
    (is (every? #(not (contains? % :content)) (:documents (workspace id))))
    (is (= 403 (error-status #(gov/document-content *service* (actor 9304) id rid))))
    (is (= 404 (error-status #(gov/document-content *service* (actor 9301) other rid))))
    (is (= 400 (error-status #(command! id :documents :create nil {:code "BAD" :title "无文件" :filename "../x" :content "abc"}))))
    (is (= 400 (error-status #(command! id :documents :create nil {:code "EMPTY" :title "空" :filename "empty" :content "  "}))))
    (let [trace (command! id :traces :create nil
                          {:requirement_id (:id requirement) :target_kind "document" :target_id rid :relation "verifies"})]
      (is (= rid (:target_id trace)))
      (is (= 409 (error-status #(command! id :traces :create nil
                                          {:requirement_id (:id requirement) :target_kind "document" :target_id rid :relation "verifies"})))))
    (is (= 404 (error-status #(command! id :traces :create nil
                                        {:requirement_id (:id requirement) :target_kind "document"
                                         :target_id (:id (document! other "OTHER")) :relation "satisfies"}))))))


(deftest document-release-requires-independent-approval-and-does-not-drift
  (let [id (project!) doc (document! id "REL-1") rid (:id doc)]
    (is (= "registered" (:status doc)))
    ;; 提交发布: 审核人不得为提交人本人, 且必须具备质量审批权限.
    (is (= 409 (error-status #(command! id :documents :submit rid {:reviewer_id 9301}))))
    (is (= 403 (error-status #(command! id :documents :submit rid {:reviewer_id 9304}))))
    (is (= 400 (error-status #(command! id :documents :submit rid {:reviewer_id 9302 :decision "approved"}))))
    (command! id :documents :submit rid {:reviewer_id 9302})
    (is (= "in_review" (:status (first (filter #(= rid (:id %)) (:documents (workspace id)))))))
    ;; 决定: 只有指定审核人可作决定, 提交人不得自审.
    (is (= 403 (error-status #(command! 9303 id :documents :decision rid {:decision "approved" :reason "冒名签发"}))))
    (is (= 403 (error-status #(command! id :documents :decision rid {:decision "approved" :reason "自行签发"}))))
    (is (= 400 (error-status #(command! 9302 id :documents :decision rid {:decision "waived" :reason "非法决定取值"}))))
    (let [released (command! 9302 id :documents :decision rid {:decision "approved" :reason "独立审查通过, 正式签发"})]
      (is (= "approved" (:status released)))
      (is (= 9302 (:released_by released)))
      (is (= 9302 (:decided_by released))))
    ;; 新修订回到 registered, 旧批准版本不可变且不漂移.
    (let [v2 (command! id :documents :revisions rid
                       {:code "REL-1" :title "记录更新" :filename "rel-v2.txt" :content "第二版正文"})]
      (is (= 2 (:revision v2)))
      (is (= "registered" (:status v2)))
      ;; 在旧批准版本上再次提交被 latest! 拒绝.
      (is (= 409 (error-status #(command! id :documents :submit rid {:reviewer_id 9302}))))
      (let [docs (:documents (workspace id))
            old (first (filter #(= rid (:id %)) docs))
            new (first (filter #(= (:id v2) (:id %)) docs))]
        (is (= "approved" (:status old)) "旧批准版本保持已发布")
        (is (= "registered" (:status new)) "新修订尚未发布"))
      ;; 退回路径: 提交 V2 后审核人驳回, 记为 rejected 且不写 released_by; 可再次提交.
      (command! id :documents :submit (:id v2) {:reviewer_id 9302})
      (let [rejected (command! 9302 id :documents :decision (:id v2) {:decision "rejected" :reason "证据不足"})]
        (is (= "rejected" (:status rejected)))
        (is (nil? (:released_by rejected))))
      (is (= "in_review" (:status (command! id :documents :submit (:id v2) {:reviewer_id 9302})))))))


(deftest traceability-report-computes-per-version-link-gaps
  (let [reqs [{:id "r1" :code "URS-1" :revision 1 :priority "required"}
              {:id "r1v2" :code "URS-1" :revision 2 :priority "required"}
              {:id "r2" :code "URS-2" :revision 1 :priority "desired"}]
        traces [{:requirement_id "r1" :relation "satisfies" :target_kind "document"}
                {:requirement_id "r1" :relation "verifies" :target_kind "document"}
                {:requirement_id "r2" :relation "satisfies" :target_kind "document"}]
        report (evidence/traceability-report reqs traces)
        u1 (first (filter #(= "URS-1" (:code %)) report))
        u2 (first (filter #(= "URS-2" (:code %)) report))]
    (is (= 2 (count report)))
    (is (= 2 (:revision u1)) "只按最新版本聚合")
    (is (false? (:satisfied? u1)) "新版本尚未追踪即缺链")
    (is (= ["satisfies" "verifies"] (:missing u1)))
    (is (true? (:satisfied? u2)))
    (is (false? (:verified? u2)))
    (is (= 1 (:design_links u2)))
    (is (= ["verifies"] (:missing u2)))
    (is (= {:requirements 2 :fully-traced 0 :missing-design 1 :missing-verification 2}
           (evidence/trace-summary report)))))


(deftest workspace-traceability-reflects-real-requirement-traces
  (let [id (project!)
        doc (document! id "TR-DOC")
        r1 (command! id :requirements :create nil
                     {:code "URS-A" :text "必须可验证" :category "功能" :priority "required" :owner_id 9301})
        r2 (command! id :requirements :create nil
                     {:code "URS-B" :text "期望项" :category "性能" :priority "desired" :owner_id 9301})
        _ (command! id :traces :create nil
                    {:requirement_id (:id r1) :target_kind "document" :target_id (:id doc) :relation "satisfies"})
        _ (command! id :traces :create nil
                    {:requirement_id (:id r1) :target_kind "document" :target_id (:id doc) :relation "verifies"})
        ws (workspace id)
        ua (first (filter #(= "URS-A" (:code %)) (:traceability ws)))
        ub (first (filter #(= "URS-B" (:code %)) (:traceability ws)))]
    (is (= 2 (:requirements (:trace_summary ws))))
    (is (= 1 (:fully-traced (:trace_summary ws))))
    (is (= 1 (:missing-design (:trace_summary ws))))
    (is (true? (:satisfied? ua)))
    (is (true? (:verified? ua)))
    (is (= [] (:missing ua)))
    (is (= ["satisfies" "verifies"] (:missing ub)))
    (let [revised (command! id :requirements :revisions (:id r1)
                            {:code "URS-A" :text "必须可验证(细化)" :category "功能" :priority "required" :owner_id 9301})
          after (workspace id)
          ua2 (first (filter #(= "URS-A" (:code %)) (:traceability after)))]
      (is (= 2 (:revision ua2)))
      (is (= (:id revised) (:requirement_id ua2)))
      (is (= ["satisfies" "verifies"] (:missing ua2)) "修订新版本缺链")
      (is (= 0 (:fully-traced (:trace_summary after)))))))


(deftest csv-preflight-and-import-are-all-or-nothing
  (let [id (project!) header "code,text,category,priority,owner_id\n"
        bad (str header "R-1,验证,功能,required,9301\nR-1,重复,功能,required,9301\nR-2,非法责任人,功能,required,9304")
        before (version id) events (count (:rows (pms/events *service* (actor 1) id)))
        checked (gov/preview *service* (actor 9301) id {:csv bad})]
    (is (false? (:valid? checked)))
    (is (= [3 4] (mapv :line (:errors checked))))
    (is (= before (version id)))
    (is (= 400 (error-status #(command! id :requirements :import nil {:csv bad}))))
    (is (empty? (:requirements (workspace id))))
    (is (= before (version id)))
    (is (= events (count (:rows (pms/events *service* (actor 1) id)))))
    (let [good (str header "R-1,验证,功能,required,9301\nR-2,选配,性能,desired,9303")
          imported (command! id :requirements :import nil {:csv good})]
      (is (= 2 (:count imported)))
      (is (= 2 (count (:requirements (workspace id)))))
      (is (false? (:valid? (gov/preview *service* (actor 9301) id {:csv good})))))))


(deftest risk-becomes-one-issue-and-requires-independent-verification
  (let [id (project!) evidence (:id (document! id "FIX-1"))
        risk (command! id :risks :create nil {:title "关键调试风险" :probability 3 :impact 5
                                              :owner_id 9301 :mitigation "准备复测" :due_date "2026-10-01"})
        _ (command! id :risks :mitigate (:id risk) {:mitigation "复测已执行" :evidence_ids [evidence]})
        issue (command! id :risks :materialize (:id risk) {})
        again (command! id :risks :materialize (:id risk) {})]
    (is (= 15 (:score risk)))
    (is (= (:id issue) (:id again)))
    (is (= 1 (count (:issues (workspace id)))))
    (is (= "blocker" (:severity issue)))
    (is (re-find #"阻塞" (first (get-in (workspace id) [:blockers :closure]))))
    (is (= 409 (error-status #(command! id :issues :resolve (:id issue)
                                        {:resolution "修复" :evidence_ids [] :reviewer_id 9302}))))
    (command! id :issues :resolve (:id issue) {:resolution "重新接线并复测" :evidence_ids [evidence] :reviewer_id 9302})
    (is (= 403 (error-status #(command! id :issues :decision (:id issue) {:decision "approved" :reason "自己验证"}))))
    (is (= "closed" (:status (command! 9302 id :issues :decision (:id issue) {:decision "approved" :reason "独立复测通过"}))))
    (is (not (re-find #"阻塞" (first (get-in (workspace id) [:blockers :closure])))))))


(deftest risk-issue-bidirectional-source-link-is-surfaced
  (let [id (project!)
        risk (command! id :risks :create nil {:title "供应商交付风险" :probability 3 :impact 5
                                              :owner_id 9301 :mitigation "备选供应商" :due_date "2026-10-01"})
        plain (command! id :risks :create nil {:title "常规观察风险" :probability 2 :impact 3
                                               :owner_id 9301 :mitigation "例会关注" :due_date "2026-10-01"})
        issue (command! id :risks :materialize (:id risk) {:title "到货延迟整改"})
        manual (command! id :issues :create nil {:title "独立登记问题" :severity "minor"
                                                 :owner_id 9301 :due_date "2026-11-01"})
        gov (workspace id)
        pick (fn [rows rid] (first (filter #(= rid (:id %)) rows)))
        linked-issue (pick (:issues gov) (:id issue))
        source-risk (pick (:risks gov) (:id risk))
        plain-risk (pick (:risks gov) (:id plain))
        manual-issue (pick (:issues gov) (:id manual))]
    (is (= 15 (:score risk)))
    (is (= (:id risk) (:source_risk_id issue)))
    (is (= (:id risk) (:issue_source_risk_id linked-issue)))
    (is (= "供应商交付风险" (:issue_source_risk_title linked-issue)))
    (is (= (:id issue) (:risk_issue_id source-risk)))
    (is (= "到货延迟整改" (:risk_issue_title source-risk)))
    (is (nil? (:issue_source_risk_id manual-issue)))
    (is (nil? (:issue_source_risk_title manual-issue)))
    (is (nil? (:risk_issue_id plain-risk)))
    (is (nil? (:risk_issue_title plain-risk)))))


(deftest meeting-action-creates-one-real-task
  (let [id (project!) meeting (command! id :meetings :create nil
                                        {:title "设计评审" :held_on "2026-09-22" :minutes "补齐验证任务" :attendee_ids [9301 9302]})
        action (command! id :meetings :actions (:id meeting)
                         {:title "补充验证" :owner_id 9301 :due_date "2026-09-25"})
        task (command! id :actions :task (:id action) {:start_date "2026-09-23" :duration_days 2})
        repeated (command! id :actions :task (:id action) {})
        rows ((:query-fn *service*) :planning/tasks {:project_id id})]
    (is (= (:target_task_id task) (:target_task_id repeated)))
    (is (= 1 (count rows)))
    (is (= (:id action) (:source_id (first rows))))
    (is (= "meeting_action" (:source_type (first rows))))
    (is (= 409 (error-status #(planning/delete-task! *service* (actor 9301) id
                                                     (:target_task_id task) {:version (version id)}))))
    (is (= "converted" (:status (first (:actions (workspace id))))))
    (is (= (:id meeting) (:meeting_id (first (:actions (workspace id))))))))


(deftest meeting-can-reference-real-document-versions-as-pre-read-materials
  (let [id (project!)
        doc-a (document! id "MAT-A")
        doc-b (command! id :documents :create nil
                        {:code "MAT-B" :title "议程背景" :filename "b.txt" :content "背景正文\n"})
        other (project!)
        foreign (:id (document! other "MAT-FOREIGN"))
        m1 (command! id :meetings :create nil
                     {:title "启动会" :held_on "2026-09-22" :minutes "评审会前资料" :attendee_ids [9301 9302]
                      :material_ids [(:id doc-a) (:id doc-b)]})
        m0 (command! id :meetings :create nil
                     {:title "无资料会议" :held_on "2026-09-23" :minutes "仅口头讨论" :attendee_ids [9301]})]
    (is (= [(:id doc-a) (:id doc-b)] (:material_ids m1)))
    (is (= [] (:material_ids m0)))
    (let [read (first (filter #(= (:id m1) (:id %)) (:meetings (workspace id))))]
      (is (= [(:id doc-a) (:id doc-b)] (:material_ids read))))
    (is (= 404 (error-status #(command! id :meetings :create nil
                                        {:title "坏资料" :held_on "2026-09-24" :minutes "x" :attendee_ids [9301]
                                         :material_ids [(str (UUID/randomUUID))]}))))
    (is (= 404 (error-status #(command! id :meetings :create nil
                                        {:title "跨项目" :held_on "2026-09-24" :minutes "x" :attendee_ids [9301]
                                         :material_ids [foreign]}))))
    (is (= 404 (error-status #(command! id :meetings :create nil
                                        {:title "错类型" :held_on "2026-09-24" :minutes "x" :attendee_ids [9301]
                                         :material_ids [(:id m1)]}))))
    (is (= 400 (error-status #(command! id :meetings :create nil
                                        {:title "重复" :held_on "2026-09-24" :minutes "x" :attendee_ids [9301]
                                         :material_ids [(:id doc-a) (:id doc-a)]}))))
    (is (= 400 (error-status #(command! id :meetings :create nil
                                        {:title "超限" :held_on "2026-09-24" :minutes "x" :attendee_ids [9301]
                                         :material_ids (vec (repeat 51 (:id doc-a)))}))))
    (is (= 400 (error-status #(command! id :meetings :create nil
                                        {:title "多余" :held_on "2026-09-24" :minutes "x" :attendee_ids [9301]
                                         :materials [(:id doc-a)]}))))))


(deftest meeting-action-completion-verifies-independently-and-flags-overdue
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "周例会" :held_on "2026-09-10" :minutes "决定补齐接线图" :attendee_ids [9301 9302]})
        action (command! id :meetings :actions (:id meeting)
                         {:title "补齐接线图" :owner_id 9301 :due_date "2026-09-01"})
        aid (:id action)
        evidence (:id (document! id "ACT-DOC"))
        overdue-row (fn []
                      (->> (:actions (workspace id))
                           (filterv (fn [a] (= aid (:id a))))
                           first
                           :action_overdue))]
    (is (true? (overdue-row)))
    (is (= 409 (error-status #(command! id :actions :complete aid {:result "完成" :evidence_ids [] :reviewer_id 9302}))))
    (is (= 409 (error-status #(command! id :actions :complete aid {:result "完成" :evidence_ids [evidence] :reviewer_id 9301}))))
    (is (= 403 (error-status #(command! id :actions :complete aid {:result "完成" :evidence_ids [evidence] :reviewer_id 9304}))))
    (is (= 400 (error-status #(command! id :actions :complete aid {:result "完成" :evidence_ids [evidence]}))))
    (let [reviewed (command! id :actions :complete aid {:result "已按规范补全" :evidence_ids [evidence] :reviewer_id 9302})]
      (is (= "in_review" (:status reviewed)))
      (is (= 9302 (:reviewer_id reviewed)))
      (is (= 9301 (:submitted_by reviewed)))
      (is (= "action_closure" (:review_action reviewed)))
      (is (= 403 (error-status #(command! id :actions :verify aid {:decision "approved" :reason "自行核验"}))))
      (is (= 403 (error-status #(command! 9303 id :actions :verify aid {:decision "approved" :reason "冒名核验"}))))
      (is (= "rejected" (:status (command! 9302 id :actions :verify aid {:decision "rejected" :reason "证据不足"}))))
      (command! id :actions :complete aid {:result "重新补全并附实测记录" :evidence_ids [evidence] :reviewer_id 9302})
      (is (= "closed" (:status (command! 9302 id :actions :verify aid {:decision "approved" :reason "独立核验通过"}))))
      (is (false? (overdue-row))))))


(deftest issue-reassign-changes-owner-with-audit-and-guards-membership
  (let [id (project!)
        issue (command! id :issues :create nil
                        {:title "需转派的问题" :severity "major" :owner_id 9301 :due_date "2026-10-01"})
        iid (:id issue)
        issue-row #(first (filterv (fn [i] (= iid (:id i))) (:issues (workspace id))))]
    (is (= 9301 (:owner_id issue)))
    ;; 新责任人必须为当前项目成员, 非成员 9304 -> 400
    (is (= 400 (error-status #(command! id :issues :reassign iid {:owner_id 9304 :reason "转给外部人员"}))))
    ;; 缺转派原因 -> 400
    (is (= 400 (error-status #(command! id :issues :reassign iid {:owner_id 9303 :reason ""}))))
    ;; 只读成员无编辑权 -> 403
    (is (= 403 (error-status #(command! 9302 id :issues :reassign iid {:owner_id 9303 :reason "越权转派"}))))
    ;; 正常转派给项目编辑者 9303, 保留原责任人与原因, 状态不变
    (let [re (command! id :issues :reassign iid {:owner_id 9303 :reason "9301出差, 转9303跟进"})]
      (is (= 9303 (:owner_id re)))
      (is (= 9301 (:reassigned_from re)))
      (is (= "9301出差, 转9303跟进" (:reassign_reason re)))
      (is (= 9301 (:reassigned_by re)))
      (is (= "open" (:status re)))
      (is (= 9303 (:owner_id (issue-row)))))
    ;; 关闭后不可再转派 -> 409
    (let [evidence (:id (document! id "RSN-DOC"))]
      (command! id :issues :resolve iid {:resolution "已处理" :evidence_ids [evidence] :reviewer_id 9302})
      (command! 9302 id :issues :decision iid {:decision "approved" :reason "独立核验通过"})
      (is (= "closed" (:status (issue-row))))
      (is (= 409 (error-status #(command! id :issues :reassign iid {:owner_id 9301 :reason "关闭后转派"})))))))


(defn- sha256-of
  "对字节数组计算服务器同款SHA256十六进制串."
  [^bytes bytes]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes))))


(defn- read-zip
  "解包ZIP字节为 {entry-name {:content :sha256}}."
  [^bytes bytes]
  (let [zis (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop [acc {}]
      (if-let [entry (.getNextEntry zis)]
        (let [content (String. ^bytes (.readAllBytes zis) StandardCharsets/UTF_8)]
          (recur (assoc acc (.getName entry)
                        {:content content
                         :sha256 (sha256-of (.getBytes ^String content StandardCharsets/UTF_8))})))
        (do (.close zis) acc)))))


(deftest document-batch-download-packages-authorized-versions-and-rejects-invalid
  (let [id (project!) other (project!)
        d1 (document! id "BATCH-1")
        d2 (command! id :documents :create nil
                     {:code "BATCH-2" :title "第二份" :filename "第二.txt" :content "第二份正文\n"})
        ids [(:id d1) (:id d2)]]
    (let [docs (:documents (gov/document-batch *service* (actor 9301) id {:record_ids ids}))]
      (is (= 2 (count docs)))
      (is (= #{" 真实证据\n" "第二份正文\n"} (set (map :content docs))))
      (is (= (:sha256 d1) (:sha256 (first (filter #(= (:id d1) (:id %)) docs)))))
      (is (every? :filename docs))
      (is (every? #(not (contains? % :payload)) docs)))
    (is (= 403 (error-status #(gov/document-batch *service* (actor 9304) id {:record_ids ids}))))
    (is (= 404 (error-status #(gov/document-batch *service* (actor 9301) id {:record_ids [(:id d1) (str (UUID/randomUUID))]}))))
    (is (= 404 (error-status #(gov/document-batch *service* (actor 9301) other {:record_ids [(:id d1)]}))))
    (is (= 400 (error-status #(gov/document-batch *service* (actor 9301) id {:record_ids []}))))
    (is (= 400 (error-status #(gov/document-batch *service* (actor 9301) id {:record_ids [(:id d1) (:id d1)]}))))
    (is (= 400 (error-status #(gov/document-batch *service* (actor 9301) id {:record_ids (vec (repeat 51 (:id d1)))}))))
    (is (= 400 (error-status #(gov/document-batch *service* (actor 9301) id {:ids ids}))))
    (let [path (str "/api/pms/projects/" id "/governance")
          url (str path "/documents/batch-download")
          status (fn [uid payload]
                   (:status (*handler* (cond-> (-> (mock/request :post url)
                                                   (mock/content-type "application/json")
                                                   (mock/header "accept" "application/json"))
                                         uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
                                         payload (mock/body (json/generate-string payload))))))]
      (is (= 401 (status nil {:record_ids ids})))
      (is (= 403 (status 9304 {:record_ids ids})))
      (let [resp (*handler* (-> (mock/request :post url)
                                (mock/content-type "application/json")
                                (mock/header "accept" "application/json")
                                (mock/header "authorization" (str "Bearer " (security/generate-token 9301 "test" [])))
                                (mock/body (json/generate-string {:record_ids ids}))))
            entries (read-zip (:body resp))
            d1-entry (get entries (str (subs (:id d1) 0 8) "_验收.txt"))]
        (is (= 200 (:status resp)))
        (is (= "application/zip" (get-in resp [:headers "Content-Type"])))
        (is (= 2 (count (remove #(= "MANIFEST.tsv" (key %)) entries))))
        (is (some? d1-entry))
        (is (= " 真实证据\n" (:content d1-entry)))
        (is (= (:sha256 d1) (:sha256 d1-entry)))
        (is (re-find #"MANIFEST" (apply str (keys entries))))))))


(deftest document-classification-stage-and-structure-are-traceable
  (let [id (project!)
        dflt (document! id "CLS-DEFAULT")
        conf (command! id :documents :create nil
                       {:code "CLS-CONF" :title "涉密设计" :filename "机密.txt" :content "机密正文\n"
                        :classification "confidential" :stage "设计" :structure_node "主机/控制柜"})]
    (is (= "internal" (:classification dflt)))
    (is (= "" (:stage dflt)))
    (is (= "confidential" (:classification conf)))
    (is (= "设计" (:stage conf)))
    (is (= "主机/控制柜" (:structure_node conf)))
    (is (= 400 (error-status #(command! id :documents :create nil
                                        {:code "CLS-BAD" :title "非法密级" :filename "x.txt" :content "正文"
                                         :classification "top-secret"}))))
    (is (= 400 (error-status #(command! id :documents :create nil
                                        {:code "CLS-BAD2" :title "非法字段" :filename "x.txt" :content "正文"
                                         :unknown_field "x"}))))
    (let [rev (command! id :documents :revisions (:id conf)
                        {:code "CLS-CONF" :title "涉密设计v2" :filename "机密2.txt" :content "机密正文v2\n"
                         :classification "public" :stage "验证"})]
      (is (= 2 (:revision rev)))
      (is (= "CLS-CONF" (:code rev)))
      (is (= "public" (:classification rev)))
      (is (= "" (:structure_node rev))))
    (let [url (str "/api/pms/projects/" id "/governance/documents/batch-download")
          resp (*handler* (-> (mock/request :post url)
                              (mock/content-type "application/json")
                              (mock/header "accept" "application/json")
                              (mock/header "authorization" (str "Bearer " (security/generate-token 9301 "test" [])))
                              (mock/body (json/generate-string {:record_ids [(:id conf)]}))))
          manifest (:content (get (read-zip (:body resp)) "MANIFEST.tsv"))]
      (is (= 200 (:status resp)))
      (is (re-find #"classification\tstage\tstructure_node" manifest))
      (is (re-find #"confidential\t设计\t主机/控制柜" manifest)))))


(defn- gate!
  "建立包含一项必需检查的指定阶段关口."
  [id stage]
  (let [template (command! id :gate-templates :create nil
                           {:code (str "G-" stage) :title "必需关口" :stage stage :required true
                            :checks [{:code "C-1" :title "验证记录齐全" :required true}]})]
    (command! id :gates :create nil {:template_id (:id template) :title "阶段评审" :reviewer_id 9302})))


(deftest gate-uses-pinned-evidence-and-formal-waiver
  (let [id (project!) document (document! id "GATE-DOC") gate (gate! id "execution")
        charter (command! id :charters :create nil (charter-body))]
    (approve! id :charters (:id charter))
    (is (= 409 (error-status #(command! id :gates :submit (:id gate) {}))))
    (is (= 400 (error-status #(command! id :gates :checks (:id gate)
                                        {:checks [{:code "C-1" :passed true :required false :evidence_ids [(:id document)]}]}))))
    (command! id :gates :checks (:id gate) {:checks [{:code "C-1" :passed true :evidence_ids [(:id document)]}]})
    (command! id :gates :submit (:id gate) {})
    (is (= 403 (error-status #(command! id :gates :decision (:id gate) {:decision "approved" :reason "自审"}))))
    (command! 9302 id :gates :decision (:id gate) {:decision "approved" :reason "检查通过"})
    (command! id :documents :revisions (:id document) {:code "GATE-DOC" :title "修订" :filename "v2.txt" :content "新版本"})
    (is (= [(:id document)] (get-in (first (:gates (workspace id))) [:checks 0 :evidence_ids])))
    (is (empty? (get-in (workspace id) [:blockers :execution])))
    (let [closure (gate! id "closure")]
      (command! id :gates :submit (:id closure) {:waiver_reason "此设备不适用该项,已作风险评估"})
      (is (= 400 (error-status #(command! 9302 id :gates :decision (:id closure) {:decision "waived" :reason ""}))))
      (is (= "waived" (:status (command! 9302 id :gates :decision (:id closure) {:decision "waived" :reason "确认不适用并接受剩余风险"}))))
      (is (empty? (get-in (workspace id) [:blockers :closure]))))))


(deftest change-review-lock-and-audit-rollback
  (let [id (project!)
        body {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
              :schedule_impact "增加五日" :cost_impact "重新估价" :quality_impact "增加测试"
              :resource_impact "追加工程师"}
        change (command! id :changes :create nil body) stale (dec (version id))]
    (is (= 409 (error-status #(gov/command! *service* (actor 9301) id :changes :submit (:id change)
                                            {:version stale :reviewer_id 9302}))))
    (approve! id :changes (:id change))
    (is (= "approved" (:status (gov/approved-change! (:query-fn *service*)
                                                     (pms/project *service* (actor 1) id) (:id change)))))
    (let [original (:query-fn *service*) before (version id)
          broken (assoc *service* :query-fn
                        (fn
                          ([name params] (original name params))
                          ([tx name params] (if (= name :pms/insert-event!)
                                              (throw (ex-info "audit unavailable" {})) (original tx name params)))))]
      (is (thrown? clojure.lang.ExceptionInfo
            (gov/command! broken (actor 9301) id :documents :create nil
                          {:version before :code "ROLLBACK" :title "回滚" :filename "rollback.txt" :content "不应保留"})))
      (is (= before (version id)))
      (is (empty? (:documents (workspace id)))))))


(deftest change-impact-is-quantified-validated-and-high-impact-flagged
  (let [id (project!)
        base-body {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                   :schedule_impact "增加十日" :cost_impact "重新估价" :quality_impact "增加测试"
                   :resource_impact "追加工程师"}
        change (command! id :changes :create nil
                         (assoc base-body :schedule_impact_days 12 :cost_impact_amount "150000.5"))]
    ;; 量化影响回显并规范化(金额两位小数), 存于不可变版本.
    (is (= 12 (:schedule_impact_days change)))
    (is (= "150000.50" (:cost_impact_amount change)))
    ;; 高影响判定: 工期12>=10 或 成本150000.50>=100000 -> true.
    (is (true? (:change_high_impact (first (filter #(= (:id change) (:id %)) (:changes (workspace id)))))))
    ;; 低于阈值 -> 非高影响.
    (let [low (command! id :changes :create nil (assoc base-body :schedule_impact_days 3 :cost_impact_amount "99999.99"))
          low-row (first (filter #(= (:id low) (:id %)) (:changes (workspace id))))]
      (is (= 3 (:schedule_impact_days low-row)))
      (is (false? (:change_high_impact low-row))))
    ;; 未量化 -> 不含量化键且非高影响.
    (let [plain (command! id :changes :create nil base-body)
          plain-row (first (filter #(= (:id plain) (:id %)) (:changes (workspace id))))]
      (is (nil? (:schedule_impact_days plain-row)))
      (is (nil? (:cost_impact_amount plain-row)))
      (is (false? (:change_high_impact plain-row))))
    ;; 修订形成新不可变版本, 旧版本量化影响不漂移.
    (let [revision (command! id :changes :revisions (:id change)
                             (assoc base-body :schedule_impact_days 20 :cost_impact_amount "1.00"))
          old (first (filter #(= (:id change) (:id %)) (:changes (workspace id))))]
      (is (= 2 (:revision revision)))
      (is (= 20 (:schedule_impact_days revision)))
      (is (= 12 (:schedule_impact_days old))))
    ;; 非法量化值被拒: 非整数天 / 超范围天 / 负成本 / 超两位小数成本.
    (is (= 400 (error-status #(command! id :changes :create nil (assoc base-body :schedule_impact_days "abc")))))
    (is (= 400 (error-status #(command! id :changes :create nil (assoc base-body :schedule_impact_days 4000)))))
    (is (= 400 (error-status #(command! id :changes :create nil (assoc base-body :cost_impact_amount "-5")))))
    (is (= 400 (error-status #(command! id :changes :create nil (assoc base-body :cost_impact_amount "1.234")))))
    ;; 量化影响是变更专属字段, 追加到章程体被白名单拒绝.
    (is (= 400 (error-status #(command! id :charters :create nil (assoc (charter-body) :schedule_impact_days 12)))))))


(defn- request
  "经真实JWT及JSON中间件验证治理路由."
  [method path uid payload]
  (let [req (cond-> (-> (mock/request method path) (mock/content-type "application/json")
                        (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              payload (mock/body (json/generate-string payload)))
        response (*handler* req) raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw
                   (bytes? raw) (String. ^bytes raw "UTF-8") :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))


(deftest authenticated-http-contract-and-isolation
  (let [id (project!) document (document! id "HTTP-DOC") path (str "/api/pms/projects/" id "/governance")]
    (is (= 401 (:status (request :get path nil nil))))
    (is (= 403 (:status (request :get path 9304 nil))))
    (is (= 403 (:status (request :get path 9305 nil))))
    (is (= 200 (:status (request :get path 9302 nil))))
    (is (= 403 (:status (request :post (str path "/charters") 9302 (assoc (charter-body) :version (version id))))))
    (let [result (request :get (str path "/documents/" (:id document) "/content") 9302 nil)]
      (is (= 200 (:status result)))
      (is (= " 真实证据\n" (get-in result [:body :data :content])))
      (is (= (:sha256 document) (get-in result [:body :data :sha256]))))))


(deftest closed-issue-reopens-only-through-independent-review
  (let [id (project!) evidence (:id (document! id "REOPEN-EVIDENCE"))
        issue (command! id :issues :create nil {:title "需验证的问题" :severity "blocker" :owner_id 9301 :due_date "2026-10-01"})
        body {:reason "发现新的复现证据" :reviewer_id 9302 :evidence_ids [evidence]}]
    ;; 阻断级问题登记即自动升级, 须先由独立质量审批人确认处置方可提交解决.
    (command! 9302 id :issues :escalate (:id issue) {:decision "approved" :note "阻断级问题独立确认处置"})
    (command! id :issues :resolve (:id issue) {:resolution "初次整改" :reviewer_id 9302 :evidence_ids [evidence]})
    (command! 9302 id :issues :decision (:id issue) {:decision "approved" :reason "初次验证通过"})
    (is (= 400 (error-status #(command! id :issues :reopen (:id issue) (assoc body :reason "")))))
    (is (= 409 (error-status #(command! id :issues :reopen (:id issue) (assoc body :reviewer_id 9301)))))
    (is (= "reopen" (:review_action (command! id :issues :reopen (:id issue) body))))
    (is (= 403 (error-status #(command! id :issues :decision (:id issue) {:decision "approved" :reason "自行重开"}))))
    (is (= "closed" (:status (command! 9302 id :issues :decision (:id issue) {:decision "rejected" :reason "证据不足"}))))
    (command! id :issues :reopen (:id issue) body)
    (is (= "open" (:status (command! 9302 id :issues :decision (:id issue) {:decision "approved" :reason "复现已确认"}))))
    (command! id :issues :resolve (:id issue) {:resolution "再次处理根因" :reviewer_id 9302 :evidence_ids [evidence]})
    (let [closed (command! 9302 id :issues :decision (:id issue) {:decision "approved" :reason "复验通过"})]
      (is (= "closed" (:status closed)))
      (is (= "closure" (:review_action closed)))
      (is (= 5 (count (:workflow_history closed)))))))  ;; created + 升级确认 + 提交解决 + 复开 + 关闭


(deftest risk-review-requires-evidence-and-future-followup
  (let [id (project!) evidence (:id (document! id "RISK-REVIEW"))
        today (java.time.LocalDate/now) tomorrow (str (.plusDays today 10))
        risk (command! id :risks :create nil {:title "周期复审风险" :probability 2 :impact 3 :owner_id 9301
                                              :mitigation "定期验证" :due_date (str (.minusDays today 1))})
        body {:outcome "active" :review_note "复审后继续监控" :reviewer_id 9302 :evidence_ids [evidence]
              :next_review_date tomorrow}]
    (is (:review_overdue (first (:risks (workspace id)))))
    (is (= 400 (error-status #(command! id :risks :review (:id risk) (assoc body :next_review_date (str today))))))
    (is (= 409 (error-status #(command! id :risks :review (:id risk) (assoc body :evidence_ids [])))))
    (is (= "in_review" (:status (command! id :risks :review (:id risk) body))))
    (is (= 403 (error-status #(command! id :risks :decision (:id risk) {:decision "approved" :reason "自审"}))))
    (is (= "open" (:status (command! 9302 id :risks :decision (:id risk) {:decision "approved" :reason "监控有效"}))))
    (is (= tomorrow (:review_due_date (first (:risks (workspace id))))))
    (is (false? (:review_overdue (first (:risks (workspace id))))))
    (command! id :risks :review (:id risk) (assoc body :outcome "closed" :review_note "风险已解除"))
    (is (= "closed" (:status (command! 9302 id :risks :decision (:id risk) {:decision "approved" :reason "已确认风险解除"}))))
    (is (nil? (:review_due_date (first (:risks (workspace id))))))
    (is (false? (:review_overdue (first (:risks (workspace id))))))))


(deftest risk-review-due-countdown-flags-remaining-days
  (let [id (project!) evidence (:id (document! id "RR-DOC"))
        today (java.time.LocalDate/now)
        far (str (.plusDays today 30))
        soon (str (.plusDays today 2))
        over (str (.minusDays today 5))
        rf (command! id :risks :create nil {:title "远期复审风险" :probability 2 :impact 3 :owner_id 9301
                                             :mitigation "例会跟踪" :due_date far})
        rs (command! id :risks :create nil {:title "临期复审风险" :probability 2 :impact 3 :owner_id 9301
                                            :mitigation "例会跟踪" :due_date soon})
        ro (command! id :risks :create nil {:title "逾期复审风险" :probability 2 :impact 3 :owner_id 9301
                                            :mitigation "例会跟踪" :due_date over})
        row (fn [rid] (first (filterv #(= rid (:id %)) (:risks (workspace id)))))]
    ;; 远期风险: 剩余 30 天, 非临期且未逾期
    (let [r (row (:id rf))]
      (is (= 30 (:review_due_in_days r)))
      (is (false? (:review_due_soon r)))
      (is (false? (:review_overdue r))))
    ;; 临期风险: 剩余 2 天 -> 临期且未逾期
    (let [r (row (:id rs))]
      (is (= 2 (:review_due_in_days r)))
      (is (true? (:review_due_soon r)))
      (is (false? (:review_overdue r))))
    ;; 逾期风险: 剩余 -5 天 -> 不计临期但计逾期
    (let [r (row (:id ro))]
      (is (= -5 (:review_due_in_days r)))
      (is (false? (:review_due_soon r)))
      (is (true? (:review_overdue r))))
    ;; 对远期风险提交复审并把下次复评审成临期(+2天), 独立批准后倒计时按复审日重算
    (command! id :risks :review (:id rf) {:outcome "active" :review_note "复审继续监控"
                                           :reviewer_id 9302 :evidence_ids [evidence] :next_review_date soon})
    (command! 9302 id :risks :decision (:id rf) {:decision "approved" :reason "监控有效"})
    (let [r (row (:id rf))]
      (is (= soon (:review_due_date r)))
      (is (= 2 (:review_due_in_days r)))
      (is (true? (:review_due_soon r))))
    ;; 关闭风险后: 状态 closed -> 倒计时归 nil 且不临期
    (command! id :risks :review (:id rs) {:outcome "closed" :review_note "风险已解除"
                                          :reviewer_id 9302 :evidence_ids [evidence]})
    (command! 9302 id :risks :decision (:id rs) {:decision "approved" :reason "已确认解除"})
    (let [r (row (:id rs))]
      (is (= "closed" (:status r)))
      (is (nil? (:review_due_in_days r)))
      (is (false? (:review_due_soon r))))))


(deftest risk-escalation-requires-independent-acknowledgment-before-mitigation
  (let [id (project!) evidence (:id (document! id "ESC-1"))
        high (command! id :risks :create nil {:title "关键交付风险" :probability 5 :impact 5
                                              :owner_id 9301 :mitigation "备选供应商" :due_date "2026-10-10"})
        low (command! id :risks :create nil {:title "轻微风险" :probability 2 :impact 3
                                             :owner_id 9301 :mitigation "例会关注" :due_date "2026-10-10"})]
    (is (= 25 (:score high)))
    (is (true? (:escalated high)))
    (is (= "pending" (:escalation_state high)))
    (is (= "steering" (:escalation_level high)))
    (is (some? (:escalation_reason high)))
    (is (false? (:escalated low)))
    (is (= 409 (error-status #(command! id :risks :mitigate (:id high)
                                        {:mitigation "已联系备选供应商" :evidence_ids [evidence]}))))
    (is (= "mitigated" (:status (command! id :risks :mitigate (:id low)
                                          {:mitigation "已纳入例会跟踪" :evidence_ids [evidence]}))))
    (is (= 409 (error-status #(command! id :risks :escalate (:id low)
                                        {:decision "approved" :note "低风险未升级无需确认"}))))
    (is (= 403 (error-status #(command! id :risks :escalate (:id high)
                                        {:decision "approved" :note "登记人自确认"}))))
    (is (= 400 (error-status #(command! 9302 id :risks :escalate (:id high)
                                        {:decision "maybe" :note "无效决定"}))))
    (let [acked (command! 9302 id :risks :escalate (:id high)
                         {:decision "approved" :note "管理层责成启动备选供应商并加严来料检验"})]
      (is (= "acknowledged" (:escalation_state acked)))
      (is (= "open" (:status acked)))
      (is (= 9302 (:escalation_ack_by acked)))
      (is (= "approved" (:escalation_decision acked))))
    (is (= 409 (error-status #(command! 9302 id :risks :escalate (:id high)
                                        {:decision "approved" :note "重复确认"}))))
    (is (= "mitigated" (:status (command! id :risks :mitigate (:id high)
                                          {:mitigation "已启动备选供应商并加严检验" :evidence_ids [evidence]}))))
    (let [row (first (filter #(= (:id high) (:id %)) (:risks (workspace id))))]
      (is (true? (:escalated row)))
      (is (= "acknowledged" (:escalation_state row))))))


(deftest risk-review-rescore-recomputes-escalation-gate
  (let [id (project!)
        evidence (:id (document! id "RS-DOC"))
        next-date "2026-11-01"]
    ;; 上升重评: 3x5=15 未达阈值风险, 复评提议 5x5=25, 批准前评分与升级不变, 批准后重算触发升级门控.
    (let [risk (command! id :risks :create nil {:title "复评上升风险" :probability 3 :impact 5
                                                :owner_id 9301 :mitigation "例会跟踪" :due_date "2026-10-10"})
          rid (:id risk)]
      (is (= 15 (:score risk)))
      (is (false? (:escalated risk)))
      (let [submitted (command! id :risks :review rid {:outcome "active" :review_note "供应商产能下降需上调"
                                                       :reviewer_id 9302 :evidence_ids [evidence]
                                                       :next_review_date next-date :probability 5 :impact 5})]
        (is (= "in_review" (:status submitted)))
        (is (= 25 (:review_proposed_score submitted)))
        (is (= 15 (:score submitted)))
        (is (false? (:escalated submitted))))
      (let [decided (command! 9302 id :risks :decision rid {:decision "approved" :reason "确认上调概率与影响"})]
        (is (= 25 (:score decided)))
        (is (= 5 (:probability decided)))
        (is (= 5 (:impact decided)))
        (is (true? (:escalated decided)))
        (is (= "pending" (:escalation_state decided)))
        (is (= "steering" (:escalation_level decided)))
        (is (nil? (:review_proposed_score decided))))
      (is (= 409 (error-status #(command! id :risks :mitigate rid
                                          {:mitigation "启动备选" :evidence_ids [evidence]}))))
      (command! 9302 id :risks :escalate rid {:decision "approved" :note "管理层责成处置"})
      (is (= "mitigated" (:status (command! id :risks :mitigate rid
                                            {:mitigation "已启动备选供应商" :evidence_ids [evidence]})))))
    ;; 下降重评: 5x5=25 升级并确认后, 复评降至 1x1=1, 批准解除升级门控与 escalation 键.
    (let [risk (command! id :risks :create nil {:title "复评下降风险" :probability 5 :impact 5
                                                :owner_id 9301 :mitigation "备选供应商" :due_date "2026-10-10"})
          rid (:id risk)]
      (command! 9302 id :risks :escalate rid {:decision "approved" :note "确认升级"})
      (command! id :risks :review rid {:outcome "active" :review_note "根因已消除可降级"
                                       :reviewer_id 9302 :evidence_ids [evidence]
                                       :next_review_date next-date :probability 1 :impact 1})
      (let [decided (command! 9302 id :risks :decision rid {:decision "approved" :reason "确认降级"})]
        (is (= 1 (:score decided)))
        (is (false? (:escalated decided)))
        (is (nil? (:escalation_state decided)))
        (is (nil? (:escalation_level decided)))))
    ;; 校验: 只填概率或只填影响, 或非法概率 -> 400; 拒绝重评则评分不变且清理提议临时键.
    (let [risk (command! id :risks :create nil {:title "复评校验风险" :probability 2 :impact 3
                                                :owner_id 9301 :mitigation "观察" :due_date "2026-10-10"})
          rid (:id risk)
          base {:outcome "active" :review_note "校验" :reviewer_id 9302 :evidence_ids [evidence]
                :next_review_date next-date}]
      (is (= 400 (error-status #(command! id :risks :review rid (assoc base :probability 4)))))
      (is (= 400 (error-status #(command! id :risks :review rid (assoc base :impact 4)))))
      (is (= 400 (error-status #(command! id :risks :review rid (assoc base :probability 9 :impact 1)))))
      (let [submitted (command! id :risks :review rid (assoc base :probability 5 :impact 5))]
        (is (= 25 (:review_proposed_score submitted)))
        (is (= 6 (:score submitted))))
      (let [rejected (command! 9302 id :risks :decision rid {:decision "rejected" :reason "证据不足不予重评"})]
        (is (= "open" (:status rejected)))
        (is (= 6 (:score rejected)))
        (is (nil? (:review_proposed_score rejected)))))))


(deftest risk-library-instantiates-escalation-aware-risk
  (let [id (project!)]
    ;; 从内置典型风险库选用供应类高风险 (5x5=25), 继承标准评分/措施/阶段并复用超阈值升级门控.
    (let [risk (command! id :risks :from-library nil
                         {:template_key "supply-outage" :owner_id 9301 :due_date "2026-10-20"})
          rid (:id risk)]
      (is (= "关键物料断供" (:title risk)))
      (is (= 25 (:score risk)))
      (is (= "risk" (:kind risk)))
      (is (true? (:escalated risk)))
      (is (= "pending" (:escalation_state risk)))
      (is (= "steering" (:escalation_level risk)))
      (is (= "采购" (:stage risk)))
      (is (= "supply-outage" (:source_key risk)))
      (is (= "supply" (:source_category risk)))
      ;; 超阈值未确认前不得自行缓解, 门控随选用一并生效.
      (is (= 409 (error-status #(command! id :risks :mitigate rid
                                          {:mitigation "已联系备选" :evidence_ids [(:id (document! id "LIB-ESC"))]}))))
      (is (some #(= rid (:id %)) (:risks (workspace id)))))
    ;; 中风险库条目 (4x4=16) 达阈值进入经理层升级.
    (let [mid (command! id :risks :from-library nil
                        {:template_key "schedule-delay" :owner_id 9301 :due_date "2026-10-20"})]
      (is (= 16 (:score mid)))
      (is (true? (:escalated mid)))
      (is (= "management" (:escalation_level mid))))
    ;; 低风险库条目 (3x3=9) 不触发升级, 无升级状态.
    (let [low (command! id :risks :from-library nil
                        {:template_key "tech-uncertainty" :owner_id 9301 :due_date "2026-10-20"})]
      (is (= 9 (:score low)))
      (is (false? (:escalated low)))
      (is (nil? (:escalation_state low))))
    ;; 风险库经工作台只读暴露给前端, 且不落库为新的治理记录类型.
    (let [library (:risk_library (workspace id))]
      (is (seq library))
      (is (some #(= "supply-outage" (:key %)) library)))
    ;; 未知风险库键与缺责任人分别被真实边界拒绝.
    (is (= 404 (error-status #(command! id :risks :from-library nil
                                        {:template_key "no-such" :owner_id 9301 :due_date "2026-10-20"}))))
    (is (= 400 (error-status #(command! id :risks :from-library nil
                                        {:template_key "cost-overrun" :due_date "2026-10-20"}))))))


(deftest risk-response-strategy-is-optional-enum-persisted
  (let [id (project!)
        risk (command! id :risks :create nil {:title "供应中断风险" :probability 2 :impact 3
                                              :owner_id 9301 :mitigation "锁定备选供应商" :due_date "2026-10-20"
                                              :response_strategy "transfer"})]
    ;; 合法枚举回显并随 payload 不可变持久化, 读模型原样返回.
    (is (= "transfer" (:response_strategy risk)))
    (is (= "transfer" (:response_strategy (first (filter #(= (:id risk) (:id %)) (:risks (workspace id)))))))
    ;; 未填策略则不写入该键, 风险仍正常创建.
    (let [plain (command! id :risks :create nil {:title "常规观察风险" :probability 2 :impact 3
                                                 :owner_id 9301 :mitigation "持续观察" :due_date "2026-10-20"})]
      (is (nil? (:response_strategy plain)))
      (is (false? (:escalated plain))))
    ;; 非法枚举被白名单校验拒绝.
    (is (= 400 (error-status #(command! id :risks :create nil {:title "非法策略风险" :probability 2 :impact 3
                                                               :owner_id 9301 :mitigation "x" :due_date "2026-10-20"
                                                               :response_strategy "ignore"}))))
    ;; 从风险库实例化不含该可选键仍正常.
    (let [lib (command! id :risks :from-library nil {:template_key "cost-overrun" :owner_id 9301 :due_date "2026-10-20"})]
      (is (nil? (:response_strategy lib)))
      (is (= "cost-overrun" (:source_key lib))))))


(deftest comm-plan-log-advances-next-date-and-flags-overdue
  (let [id (project!)
        st (command! id :stakeholders :create nil
                     {:code "SH-1" :name "客户代表" :role "验收" :category "customer"
                      :interest "high" :influence "high" :owner_id 9301})
        plan (command! id :comm-plans :create nil
                       {:code "CP-1" :objective "周度进展同步" :channel "meeting" :frequency "weekly"
                        :audience [(:id st)] :next_date "2026-01-05" :owner_id 9301})
        pid (:id plan)]
    ;; 过去的下次沟通日期 -> 到期预警为真, 剩余天数为负.
    (let [before (first (filter #(= pid (:id %)) (:comm_plans (workspace id))))]
      (is (true? (:comm_overdue before)))
      (is (neg? (:comm_days_until before)))
      (is (= "2026-01-05" (:next_date before))))
    ;; 标记一次实际沟通, 按周频顺延下次日期并留痕.
    (let [logged (command! id :comm-plans :log pid {:on "2026-09-22" :note "已召开周会同步进展"})]
      (is (= "2026-09-29" (:next_date logged)))
      (is (= "2026-09-22" (:last_communicated_on logged)))
      (is (= "已召开周会同步进展" (:last_communication_note logged)))
      (is (= 1 (count (:communication_log logged)))))
    ;; 顺延后不再到期, 剩余天数为正.
    (let [after (first (filter #(= pid (:id %)) (:comm_plans (workspace id))))]
      (is (false? (:comm_overdue after)))
      (is (pos? (:comm_days_until after))))
    ;; 非法日期与不存在计划分别被拒.
    (is (= 400 (error-status #(command! id :comm-plans :log pid {:on "2026-13-99"}))))
    (is (= 404 (error-status #(command! id :comm-plans :log "no-such-plan" {:on "2026-09-22"}))))))


(deftest issue-read-model-flags-overdue-and-blocker
  (let [id (project!)
        open (command! id :issues :create nil
                       {:title "现场接线错误" :severity "blocker" :owner_id 9301 :due_date "2026-01-10"})
        future (command! id :issues :create nil
                         {:title "轻微外观瑕疵" :severity "minor" :owner_id 9301 :due_date "2099-01-10"})
        rows (:issues (workspace id))
        o (first (filter #(= (:id open) (:id %)) rows))
        f (first (filter #(= (:id future) (:id %)) rows))]
    (is (true? (:issue_overdue o)))
      (is (true? (:issue_critical o)))
      (is (false? (:issue_overdue f)))
      (is (false? (:issue_critical f)))))


(deftest stakeholder-quadrant-and-unbound-owner-read-model
  (let [id (project!)
        key-player (command! id :stakeholders :create nil
                             {:code "SH-K" :name "客户方决策人" :role "验收决策" :category "customer"
                              :interest "high" :influence "high" :owner_id 9301})
        satisfied (command! id :stakeholders :create nil
                            {:code "SH-S" :name "政府监管" :role "合规" :category "regulator"
                             :interest "low" :influence "high" :owner_id 9302})
        informed (command! id :stakeholders :create nil
                           {:code "SH-I" :name "一线用户" :role "使用反馈" :category "internal"
                            :interest "high" :influence "low"})
        monitor (command! id :stakeholders :create nil
                          {:code "SH-M" :name "外围供应商" :role "备件" :category "supplier"
                           :interest "low" :influence "low" :owner_id 9303})
        rows (:stakeholders (workspace id))
        find-row (fn [rec] (first (filter #(= (:code rec) (:code %)) rows)))]
    (is (= "manage-close" (:stakeholder_quadrant (find-row key-player))))
    (is (false? (:stakeholder_unbound (find-row key-player))))
    (is (= "keep-satisfied" (:stakeholder_quadrant (find-row satisfied))))
    (is (= "keep-informed" (:stakeholder_quadrant (find-row informed))))
    (is (true? (:stakeholder_unbound (find-row informed))))
    (is (= "monitor" (:stakeholder_quadrant (find-row monitor))))))


(deftest raci-r-load-and-overload-read-model
  (let [id (project!)
        s1 (command! id :stakeholders :create nil
                     {:code "SH-R1" :name "负责人甲" :role "设备工程师" :category "internal" :interest "high" :influence "medium" :owner_id 9301})
        s2 (command! id :stakeholders :create nil
                     {:code "SH-R2" :name "负责人乙" :role "测试工程师" :category "internal" :interest "high" :influence "medium" :owner_id 9302})
        s3 (command! id :stakeholders :create nil
                     {:code "SH-R3" :name "负责人丙" :role "质量" :category "internal" :interest "high" :influence "medium" :owner_id 9303})
        _ (doseq [act ["活动一" "活动二" "活动三"]]
            (command! id :raci :create nil {:activity act :stakeholder_id (:id s1) :responsibility "R"}))
        _ (command! id :raci :create nil {:activity "活动一" :stakeholder_id (:id s2) :responsibility "R"})
        _ (command! id :raci :create nil {:activity "活动一" :stakeholder_id (:id s3) :responsibility "A"})
        rows (:raci (workspace id))
        s1-rows (filterv #(= (:id s1) (:stakeholder_id %)) rows)]
    (is (= 3 (count s1-rows)))
    (is (every? #(= 3 (:raci_r_load %)) s1-rows))
    (is (every? #(true? (:raci_overloaded %)) s1-rows))
    (let [r2 (first (filter #(= (:id s2) (:stakeholder_id %)) rows))]
      (is (= 1 (:raci_r_load r2)))
      (is (false? (:raci_overloaded r2))))
    (let [r3 (first (filter #(= (:id s3) (:stakeholder_id %)) rows))]
      (is (= 0 (:raci_r_load r3)))
      (is (false? (:raci_overloaded r3))))))


(deftest meeting-action-closure-counts-and-overdue
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "月度例会" :held_on "2026-09-01" :minutes "确定三项行动" :attendee_ids [9301 9303]})
        mid (:id meeting)
        overdue (command! id :meetings :actions mid {:title "补齐接线图" :owner_id 9301 :due_date "2026-01-10"})
        future (command! id :meetings :actions mid {:title "更新验收计划" :owner_id 9303 :due_date "2099-12-31"})
        conv (command! id :meetings :actions mid {:title "转任务项" :owner_id 9301 :due_date "2026-02-01"})
        _ (command! id :actions :task (:id conv) {:start_date "2026-09-23" :duration_days 2})
        row (first (filter #(= mid (:id %)) (:meetings (workspace id))))]
    (is (= 3 (:meeting_action_total row)))
    (is (= 2 (:meeting_open_actions row)))
    (is (= 1 (:meeting_overdue_actions row)))
    (is (some? (:id overdue)))
    (is (some? (:id future)))))


(deftest owner-workload-aggregates-open-items-across-kinds
  (let [id (project!) evidence (:id (document! id "LOAD-DOC"))
        meeting (command! id :meetings :create nil
                          {:title "负载例会" :held_on "2026-09-01" :minutes "分派多项行动" :attendee_ids [9301 9303]})
        mid (:id meeting)
        ;; 责任人 9301 跨问题/风险/行动共 4 项未关闭事项 -> 达到阈值 4 判定过载
        i1 (command! id :issues :create nil {:title "问题甲" :severity "major" :owner_id 9301 :due_date "2026-10-01"})
        i2 (command! id :issues :create nil {:title "问题乙" :severity "major" :owner_id 9301 :due_date "2026-10-01"})
        r1 (command! id :risks :create nil {:title "风险甲" :probability 2 :impact 3 :owner_id 9301
                                            :mitigation "例会跟踪" :due_date "2026-10-01"})
        a1 (command! id :meetings :actions mid {:title "行动甲" :owner_id 9301 :due_date "2026-10-01"})
        ;; 责任人 9303 仅 1 项 -> 不过载
        i3 (command! id :issues :create nil {:title "问题丙" :severity "major" :owner_id 9303 :due_date "2026-10-01"})
        row-in (fn [section rid] (first (filterv #(= rid (:id %)) (section (workspace id)))))
        load-of (fn [section rid] (:owner_open_load (row-in section rid)))
        over-of (fn [section rid] (:owner_overloaded (row-in section rid)))]
    ;; 每类行都回显同一责任人的跨类未关闭负载 4, 并一致判定过载
    (is (= 4 (load-of :issues (:id i1))))
    (is (= 4 (load-of :risks (:id r1))))
    (is (= 4 (load-of :actions (:id a1))))
    (is (true? (over-of :issues (:id i1))))
    (is (true? (over-of :risks (:id r1))))
    (is (true? (over-of :actions (:id a1))))
    ;; 单事项责任人负载 1 不过载
    (is (= 1 (load-of :issues (:id i3))))
    (is (false? (over-of :issues (:id i3))))
    ;; 独立关闭 i2 -> 9301 跨类负载降到 3, 解除过载
    (command! id :issues :resolve (:id i2) {:resolution "已处理根因" :reviewer_id 9302 :evidence_ids [evidence]})
    (command! 9302 id :issues :decision (:id i2) {:decision "approved" :reason "复验通过"})
    (is (= 3 (load-of :issues (:id i1))))
    (is (false? (over-of :issues (:id i1))))
    ;; 行动转真实任务后同样从负载中剔除 -> 降到 2
    (command! id :actions :task (:id a1) {:start_date "2026-09-23" :duration_days 2})
    (is (= 2 (load-of :risks (:id r1))))
    ;; 无责任人行的纯函数边界: 负载 0 且不过载
    (let [bare (collab/owner-workload-read-model {9301 9} {:status "open"})]
      (is (= 0 (:owner_open_load bare)))
      (is (false? (:owner_overloaded bare))))))


(deftest issue-and-action-due-countdown-flags-remaining-days
  (let [id (project!) evidence (:id (document! id "CD-DOC"))
        today (java.time.LocalDate/now)
        far (str (.plusDays today 30))
        soon (str (.plusDays today 2))
        over (str (.minusDays today 5))
        meeting (command! id :meetings :create nil
                          {:title "倒计时例会" :held_on (str today) :minutes "行动到期跟踪" :attendee_ids [9301 9303]})
        mid (:id meeting)
        ifar (command! id :issues :create nil {:title "远期问题" :severity "major" :owner_id 9301 :due_date far})
        isoon (command! id :issues :create nil {:title "临期问题" :severity "major" :owner_id 9301 :due_date soon})
        iover (command! id :issues :create nil {:title "逾期问题" :severity "major" :owner_id 9301 :due_date over})
        asoon (command! id :meetings :actions mid {:title "临期行动" :owner_id 9301 :due_date soon})
        aclose (command! id :meetings :actions mid {:title "转任务行动" :owner_id 9301 :due_date far})
        row-in (fn [section rid] (first (filterv #(= rid (:id %)) (section (workspace id)))))]
    ;; 问题: 远期剩余 30 天, 非临期且未逾期
    (let [r (row-in :issues (:id ifar))]
      (is (= 30 (:issue_due_in_days r)))
      (is (false? (:issue_due_soon r)))
      (is (false? (:issue_overdue r))))
    ;; 问题: 剩余 2 天 -> 临期且未逾期
    (let [r (row-in :issues (:id isoon))]
      (is (= 2 (:issue_due_in_days r)))
      (is (true? (:issue_due_soon r)))
      (is (false? (:issue_overdue r))))
    ;; 问题: 逾期 5 天 -> 剩余 -5 天, 不计临期但计逾期
    (let [r (row-in :issues (:id iover))]
      (is (= -5 (:issue_due_in_days r)))
      (is (false? (:issue_due_soon r)))
      (is (true? (:issue_overdue r))))
    ;; 行动: 剩余 2 天 -> 临期且未逾期
    (let [r (row-in :actions (:id asoon))]
      (is (= 2 (:action_due_in_days r)))
      (is (true? (:action_due_soon r)))
      (is (false? (:action_overdue r))))
    ;; 行动转真实任务后 -> 不再计倒计时与临期
    (command! id :actions :task (:id aclose) {:start_date (str today) :duration_days 2})
    (let [r (row-in :actions (:id aclose))]
      (is (nil? (:action_due_in_days r)))
      (is (false? (:action_due_soon r)))
      (is (false? (:action_overdue r))))
    ;; 问题独立验证关闭后 -> 不再计倒计时
    (command! id :issues :resolve (:id ifar) {:resolution "已复验" :reviewer_id 9302 :evidence_ids [evidence]})
    (command! 9302 id :issues :decision (:id ifar) {:decision "approved" :reason "独立通过"})
    (let [r (row-in :issues (:id ifar))]
      (is (nil? (:issue_due_in_days r)))
      (is (false? (:issue_due_soon r))))))


(deftest appointment-snapshot-matches-current-team-and-is-immutable
  (let [id (project!)
        orig (command! id :appointments :create nil {:issued_on "2026-09-22" :note "正式任命"})]
    (is (= 1 (:revision orig)))
    (is (= "issued" (:status orig)))
    (is (= 4 (:headcount orig)))
    (is (= [1 9301 9302 9303] (mapv :user_id (:snapshot orig))))
    (is (= ["editor" "manager" "viewer" "editor"] (mapv :role (:snapshot orig))))
    (is (re-matches #"[0-9a-f]{64}" (:snapshot_sha256 orig)))
    (is (re-find #"项目成员任命书" (:content orig)))
    (is (re-find #"担任 manager" (:content orig)))
    (is (every? #(not (contains? % :content)) (:appointments (workspace id))))
    (pms/set-member! *service* (actor 1) id {:user_id 9304 :role "editor"})
    (let [reissued (command! id :appointments :create nil {:issued_on "2026-10-01"})
          appointments (:appointments (workspace id))
          kept (first (filter #(= (:id orig) (:id %)) appointments))]
      (is (= 2 (:revision reissued)))
      (is (= 5 (:headcount reissued)))
      (is (= [1 9301 9302 9303 9304] (mapv :user_id (:snapshot reissued))))
      (is (not= (:snapshot_sha256 orig) (:snapshot_sha256 reissued)))
      (is (= 2 (count appointments)))
      (is (= (:snapshot_sha256 orig) (:snapshot_sha256 kept)))
      (is (= [1 9301 9302 9303] (mapv :user_id (:snapshot kept)))))))


(deftest appointment-issues-are-controlled-and-isolated
  (let [id (project!) other (project!)
        issued (command! id :appointments :create nil {:issued_on "2026-09-22"})
        stale (dec (version id))]
    (is (= 403 (error-status #(command! 9302 id :appointments :create nil {:issued_on "2026-09-22"}))))
    (is (= 403 (error-status #(command! 9305 id :appointments :create nil {:issued_on "2026-09-22"}))))
    (is (= 409 (error-status #(gov/command! *service* (actor 9301) id :appointments :create nil
                                            {:version stale :issued_on "2026-09-22"}))))
    (is (= 400 (error-status #(command! id :appointments :create nil {:issued_on "2026-13-40"}))))
    (is (= 400 (error-status #(command! id :appointments :create nil {:issued_on "2026-09-22" :content "伪造"}))))
    (is (= 2 (:revision (command! id :appointments :create nil {:issued_on "2026-09-25"}))))
    (is (nil? (error-status #(gov/appointment-content *service* (actor 9301) id (:id issued)))))
    (is (= 403 (error-status #(gov/appointment-content *service* (actor 9305) id (:id issued)))))
    (is (= 404 (error-status #(gov/appointment-content *service* (actor 9301) other (:id issued)))))))


(deftest appointment-http-contract-and-download
  (let [id (project!) path (str "/api/pms/projects/" id "/governance")
        issued (command! id :appointments :create nil {:issued_on "2026-09-22"})]
    (is (= 401 (:status (request :post (str path "/appointments") nil {:issued_on "2026-09-22" :version (version id)}))))
    (is (= 403 (:status (request :post (str path "/appointments") 9302 {:issued_on "2026-09-22" :version (version id)}))))
    (let [result (request :get (str path "/appointments/" (:id issued) "/content") 9301 nil)]
      (is (= 200 (:status result)))
      (is (= (:snapshot_sha256 issued) (get-in result [:body :data :snapshot_sha256])))
      (is (= 4 (count (get-in result [:body :data :snapshot])))))
    (let [dl (*handler* (-> (mock/request :get (str path "/appointments/" (:id issued) "/download"))
                            (mock/header "authorization" (str "Bearer " (security/generate-token 9301 "test" [])))))]
      (is (= 200 (:status dl)))
      (is (= (:snapshot_sha256 issued) (get-in dl [:headers "X-Content-SHA256"])))
      (is (re-find #"项目成员任命书" (:body dl))))))


(defn- stakeholder!
  "登记一个绑定项目成员责任人的干系人."
  [id code owner]
  (command! id :stakeholders :create nil
            {:code code :name (str "干系人" code) :role "设备工程师"
             :category "internal" :interest "high" :influence "medium" :owner_id owner}))


(deftest stakeholder-raci-conflict-and-comm-plan-loop
  (let [id (project!)
        s1 (stakeholder! id "SH-1" 9301)
        s2 (stakeholder! id "SH-2" 9302)
        s3 (stakeholder! id "SH-3" 9303)]
    (is (= "active" (:status s1)))
    (is (= 409 (error-status #(stakeholder! id "SH-1" 9301))))
    (is (= 403 (error-status #(command! 9302 id :stakeholders :create nil
                                        {:code "SH-X" :name "越权" :role "r" :category "internal"
                                         :interest "high" :influence "low"}))))
    (let [revision (command! id :stakeholders :revisions (:id s1)
                             {:code "SH-1" :name "改名" :role "主管" :category "internal"
                              :interest "high" :influence "high" :owner_id 9301})]
      (is (= 2 (:revision revision)))
      (is (= (:id s1) (:previous_id revision)))
      (is (= 400 (error-status #(command! id :stakeholders :revisions (:id revision)
                                          {:code "SH-OTHER" :name "n" :role "r" :category "internal"
                                           :interest "low" :influence "low"})))))
    (command! id :raci :create nil {:activity "出厂验收" :stakeholder_id (:id s1) :responsibility "R"})
    (is (= 409 (error-status #(command! id :raci :create nil
                                        {:activity "出厂验收" :stakeholder_id (:id s1) :responsibility "A"}))))
    (command! id :raci :create nil {:activity "出厂验收" :stakeholder_id (:id s2) :responsibility "A"})
    (is (= 409 (error-status #(command! id :raci :create nil
                                        {:activity "出厂验收" :stakeholder_id (:id s3) :responsibility "A"}))))
    (is (empty? (filter #(= "出厂验收" (:activity %)) (:raci_conflicts (workspace id)))))
    (command! id :raci :create nil {:activity "现场调试" :stakeholder_id (:id s3) :responsibility "C"})
    (let [debug (first (filter #(= "现场调试" (:activity %)) (:raci_conflicts (workspace id))))]
      (is (:missing-accountable? debug))
      (is (:missing-responsible? debug)))
    (let [plan (command! id :comm-plans :create nil
                         {:code "CP-1" :objective "每周进度沟通" :channel "email" :frequency "weekly"
                          :audience [(:id s1) (:id s2)] :next_date "2026-09-25" :owner_id 9301})
          revised (command! id :comm-plans :revisions (:id plan)
                            {:code "CP-1" :objective "双周进度沟通" :channel "meeting" :frequency "biweekly"
                             :audience [(:id s1) (:id s2)] :next_date "2026-10-01" :owner_id 9301})]
      (is (= "active" (:status plan)))
      (is (= 2 (:revision revised)))
      (is (= (:id plan) (:previous_id revised)))
      (is (= 409 (error-status #(command! id :comm-plans :create nil
                                          {:code "CP-1" :objective "dup" :channel "email" :frequency "weekly"
                                           :audience [(:id s1)] :next_date "2026-09-25"}))))
      (is (= 400 (error-status #(command! id :comm-plans :create nil
                                          {:code "CP-2" :objective "无受众" :channel "email" :frequency "weekly"
                                           :audience [] :next_date "2026-09-25"}))))
      (is (= 409 (error-status #(command! id :comm-plans :meeting (:id plan) {:held_on "2026-09-26"}))))
      (let [meeting (command! id :comm-plans :meeting (:id revised) {:held_on "2026-09-26"})
            meetings (:meetings (workspace id))
            latest-plan (first (filter #(= (:id revised) (:id %)) (:comm_plans (workspace id))))]
        (is (= 1 (count meetings)))
        (is (= (:id meeting) (:last_meeting_id latest-plan)))
        (is (= [9301 9302] (sort (:attendee_ids (first meetings))))))
      (let [other (project!)]
        (is (empty? (:stakeholders (workspace other))))
        (is (= 403 (error-status #(gov/workspace *service* (actor 9305) id))))
        (is (= 404 (error-status #(gov/command! *service* (actor 9301) other :raci :create nil
                                                {:version (:version (pms/project *service* (actor 1) other))
                                                 :activity "出厂验收" :stakeholder_id (:id s1) :responsibility "A"}))))))))


(deftest stakeholder-comm-plan-http-contract
  (let [id (project!)
        path (str "/api/pms/projects/" id "/governance")
        s1 (stakeholder! id "HTTP-SH" 9301)]
    (is (= 401 (:status (request :post (str path "/stakeholders") nil
                                 {:code "SH" :name "n" :role "r" :category "internal"
                                  :interest "high" :influence "low" :version (version id)}))))
    (is (= 403 (:status (request :post (str path "/raci") 9302
                                 {:activity "a" :stakeholder_id (:id s1) :responsibility "R" :version (version id)}))))
    (let [result (request :post (str path "/raci") 9301
                          {:activity "发布" :stakeholder_id (:id s1) :responsibility "R" :version (version id)})]
      (is (= 200 (:status result)))
      (is (= "R" (get-in result [:body :data :result :responsibility]))))
    (let [ws (request :get path 9301 nil)]
      (is (= 200 (:status ws)))
      (is (= 1 (count (get-in ws [:body :data :stakeholders]))))
      (is (some #(= "发布" (:activity %)) (get-in ws [:body :data :raci]))))))


(deftest document-collection-aggregates-latest-versions-only
  (let [id (project!)
        reg (fn [code stage class node]
              (command! id :documents :create nil
                        {:code code :title (str "文档 " code) :filename (str code ".txt")
                         :content "真实正文" :stage stage :classification class :structure_node node}))
        a (reg "DOC-A" "设计准备" "confidential" "主机")
        _ (reg "DOC-B" "设计准备" "internal" "")
        _ (reg "DOC-C" "" "public" "附件")
        _ (reg "DOC-D" "装配" "internal" "主机")
        _ (command! id :documents :revisions (:id a)
                    {:code "DOC-A" :title "文档 A 修订" :filename "DOC-A.txt"
                     :content "修订正文" :stage "测试" :classification "public" :structure_node "主机"})
        col (:document_collection (workspace id))
        stage->count (into {} (map (juxt :key :count)) (:by-stage col))
        node->count (into {} (map (juxt :key :count)) (:by-structure-node col))
        class->count (into {} (map (juxt :classification :count)) (:by-classification col))]
    (is (= 4 (:total col)))
    (is (= {"设计准备" 1 "测试" 1 "装配" 1 "" 1} stage->count))
    (is (= {"主机" 2 "附件" 1 "" 1} node->count))
    (is (= {"public" 2 "internal" 2 "confidential" 0} class->count))
    (is (= "" (:key (last (:by-stage col)))))
    (is (= "" (:key (last (:by-structure-node col)))))
    (is (= ["public" "internal" "confidential"] (map :classification (:by-classification col))))))


(deftest requirement-discard-is-soft-and-reference-guarded
  (let [id (project!)
        doc (document! id "RD-1")
        req (command! id :requirements :create nil
                      {:code "URS-D" :text "可作废需求" :category "功能" :priority "required" :owner_id 9301})]
    (is (= "registered" (:status req)))
    (is (= 403 (error-status #(command! 9302 id :requirements :discard (:id req) {:reason "越权"}))))
    (is (= 400 (error-status #(command! id :requirements :discard (:id req) {:reason "缺少白名单外字段" :extra 1}))))
    (let [discarded (command! id :requirements :discard (:id req) {:reason "需求并入其它条目"})]
      (is (= "discarded" (:status discarded)))
      (is (= "需求并入其它条目" (:discard_reason discarded)))
      (is (= 9301 (:discarded_by discarded)))
      (is (some? (:discarded_on discarded)))
      (is (= "discarded" (->> (:requirements (workspace id)) (filter #(= (:id req) (:id %))) first :status))))
    (is (= 409 (error-status #(command! id :requirements :discard (:id req) {:reason "重复作废"}))))
    (let [id2 (project!)
          req2 (command! id2 :requirements :create nil
                         {:code "URS-T" :text "被追踪需求" :category "功能" :priority "required" :owner_id 9301})
          doc2 (document! id2 "RD-T")]
      (command! id2 :traces :create nil
                {:requirement_id (:id req2) :target_kind "document" :target_id (:id doc2) :relation "verifies"})
      (is (= 409 (error-status #(command! id2 :requirements :discard (:id req2) {:reason "仍被追踪引用"})))))))


(deftest document-discard-rejects-referenced-and-non-discardable-status
  (let [id (project!)
        referenced (document! id "DD-REF")
        free (document! id "DD-FREE")
        _ (command! id :meetings :create nil
                    {:title "含资料会议" :held_on "2026-09-22" :minutes "会前阅读" :attendee_ids [9301 9302]
                     :material_ids [(:id referenced)]})]
    (is (= 409 (error-status #(command! id :documents :discard (:id referenced) {:reason "被会议引用"}))))
    (let [discarded (command! id :documents :discard (:id free) {:reason "重复上传"})]
      (is (= "discarded" (:status discarded)))
      (is (= "重复上传" (:discard_reason discarded))))
    (let [pending (document! id "DD-PEND")]
      (command! id :documents :submit (:id pending) {:reviewer_id 9302})
      (is (= 409 (error-status #(command! id :documents :discard (:id pending) {:reason "评审中不可作废"})))))))


(deftest stakeholder-discard-rejects-referenced-record
  (let [id (project!)
        s1 (stakeholder! id "SD-1" 9301)
        s2 (stakeholder! id "SD-2" 9301)]
    (command! id :raci :create nil {:activity "出厂检验" :stakeholder_id (:id s1) :responsibility "R"})
    (is (= 409 (error-status #(command! id :stakeholders :discard (:id s1) {:reason "仍承担RACI"}))))
    (let [discarded (command! id :stakeholders :discard (:id s2) {:reason "人员退出项目"})]
      (is (= "discarded" (:status discarded)))
      (is (= 409 (error-status #(command! id :raci :create nil
                                          {:activity "新活动" :stakeholder_id (:id s2) :responsibility "A"})))))))


(deftest discarded-records-can-be-restored-with-audit
  (let [id (project!)
        req (command! id :requirements :create nil
                      {:code "URS-R" :text "可恢复需求" :category "功能" :priority "required" :owner_id 9301})
        doc (document! id "RR-1")
        sh (stakeholder! id "RR-S" 9301)]
    ;; 需求: 作废 -> 恢复到作废前 registered, 审计含 discarded+restored 两项; 越权 403; 非作废再恢复 409; 未知字段 400
    (command! id :requirements :discard (:id req) {:reason "先作废"})
    (is (= 403 (error-status #(command! 9302 id :requirements :restore (:id req) {:reason "越权恢复"}))))
    (let [restored (command! id :requirements :restore (:id req) {:reason "误操作恢复"})]
      (is (= "registered" (:status restored)))
      (is (= "误操作恢复" (:restore_reason restored)))
      (is (= 9301 (:restored_by restored)))
      (is (some? (:restored_on restored)))
      (is (= ["discarded" "restored"] (map :action (:workflow_history restored))))
      (is (= "registered" (:restored_to (last (:workflow_history restored))))))
    (is (= 409 (error-status #(command! id :requirements :restore (:id req) {:reason "非作废不可恢复"}))))
    (is (= 400 (error-status #(command! id :requirements :restore (:id req) {:reason "x" :extra 1}))))
    ;; 文档: 作废 -> 恢复到 registered
    (command! id :documents :discard (:id doc) {:reason "重复上传"})
    (is (= "registered" (:status (command! id :documents :restore (:id doc) {:reason "恢复归档"}))))
    ;; 干系人: 作废 -> 恢复到 active, 恢复后可再被 RACI 引用
    (command! id :stakeholders :discard (:id sh) {:reason "人员退出"})
    (is (= "active" (:status (command! id :stakeholders :restore (:id sh) {:reason "人员回归"}))))
    (is (some? (:id (command! id :raci :create nil
                              {:activity "回归活动" :stakeholder_id (:id sh) :responsibility "A"}))))))


(deftest document-collection-excludes-discarded-latest-versions
  (let [id (project!)
        reg (fn [code stage class node]
              (command! id :documents :create nil
                        {:code code :title (str "文档 " code) :filename (str code ".txt")
                         :content "真实正文" :stage stage :classification class :structure_node node}))
        keep-a (reg "CA-A" "设计准备" "internal" "主机")
        keep-b (reg "CA-B" "装配" "public" "附件")
        void (reg "CA-C" "测试" "confidential" "主机")
        col0 (:document_collection (workspace id))]
    (is (= 3 (:total col0)))
    (is (= 0 (:discarded-count col0)))
    (command! id :documents :discard (:id void) {:reason "重复上传"})
    (let [col1 (:document_collection (workspace id))
          class->count (into {} (map (juxt :classification :count)) (:by-classification col1))]
      (is (= 2 (:total col1)))
      (is (= 1 (:discarded-count col1)))
      (is (= {"public" 1 "internal" 1 "confidential" 0} class->count))
      (is (= #{"设计准备" "装配"} (into #{} (map :key) (:by-stage col1)))
          "已作废文档的阶段不再进入归集")
      (is (= #{"主机" "附件"} (into #{} (map :key) (:by-structure-node col1)))))
    ;; 恢复后重新计入归集
    (command! id :documents :restore (:id void) {:reason "误作废恢复"})
    (let [col2 (:document_collection (workspace id))]
      (is (= 3 (:total col2)))
      (is (= 0 (:discarded-count col2))))))


(deftest discard-preview-reports-guards-without-mutating
  (let [id (project!)
        doc (document! id "DP-DOC")
        free-req (command! id :requirements :create nil
                           {:code "URS-FREE" :text "可作废需求" :category "功能" :priority "required" :owner_id 9301})
        traced-req (command! id :requirements :create nil
                             {:code "URS-TRACED" :text "被追踪需求" :category "功能" :priority "required" :owner_id 9301})
        _ (command! id :traces :create nil
                    {:requirement_id (:id traced-req) :target_kind "document" :target_id (:id doc) :relation "verifies"})
        free-preview (gov/discard-preview *service* (actor 9301) id "requirement" (:id free-req))
        traced-preview (gov/discard-preview *service* (actor 9301) id "requirement" (:id traced-req))]
    (is (true? (:discardable? free-preview)))
    (is (true? (:latest? free-preview)))
    (is (true? (:status_discardable? free-preview)))
    (is (empty? (:references free-preview)))
    (is (false? (:discardable? traced-preview)))
    (is (= 1 (count (:references traced-preview))))
    (is (re-find #"需求追踪" (first (:references traced-preview))))
    ;; 文档被追踪指向 -> 有引用, 不可作废
    (let [doc-preview (gov/discard-preview *service* (actor 9301) id "document" (:id doc))]
      (is (false? (:discardable? doc-preview)))
      (is (pos? (count (:references doc-preview)))))
    ;; 预览只读: 不改变任何记录状态
    (is (= "registered" (:status (first (filter #(= (:id free-req) (:id %)) (:requirements (workspace id)))))))
    ;; 已作废记录: status_discardable? 为 false (不在可作废集合)
    (let [gone (document! id "DP-GONE")]
      (command! id :documents :discard (:id gone) {:reason "重复"})
      (let [p (gov/discard-preview *service* (actor 9301) id "document" (:id gone))]
        (is (= "discarded" (:status p)))
        (is (false? (:status_discardable? p)))
        (is (false? (:discardable? p)))))
    ;; 未知记录 404
    (is (= 404 (error-status #(gov/discard-preview *service* (actor 9301) id "requirement" (str (UUID/randomUUID))))))
    ;; 无 pms 功能权限用户 403
    (is (= 403 (error-status #(gov/discard-preview *service* (actor 9305) id "requirement" (:id free-req)))))))


(deftest issue-escalation-requires-independent-acknowledgment-before-resolution
  (let [id (project!) evidence (:id (document! id "ISS-ESC-1"))
        blocker (command! id :issues :create nil {:title "阻断级装配缺陷" :severity "blocker"
                                                  :owner_id 9301 :due_date "2026-10-10"})
        major (command! id :issues :create nil {:title "一般缺陷" :severity "major"
                                                :owner_id 9301 :due_date "2026-10-10"})]
    ;; 阻断级问题登记即自动升级到经理层待确认; 非阻断不写任何 escalation 键 (与既有用例兼容).
    (is (true? (:escalated blocker)))
    (is (= "pending" (:escalation_state blocker)))
    (is (= "management" (:escalation_level blocker)))
    (is (some? (:escalation_reason blocker)))
    (is (nil? (:escalated major)))
    ;; 未确认前不得提交解决; 非阻断问题可正常提交解决.
    (is (= 409 (error-status #(command! id :issues :resolve (:id blocker)
                                        {:resolution "重新装配并复测" :evidence_ids [evidence] :reviewer_id 9302}))))
    (is (= "in_review" (:status (command! id :issues :resolve (:id major)
                                          {:resolution "调整间隙" :evidence_ids [evidence] :reviewer_id 9302}))))
    ;; 升级确认门控: 非升级问题 409, 登记人自确认 403, 无效决定 400.
    (is (= 409 (error-status #(command! id :issues :escalate (:id major)
                                        {:decision "approved" :note "非阻断无需确认"}))))
    (is (= 403 (error-status #(command! id :issues :escalate (:id blocker)
                                        {:decision "approved" :note "登记人自确认"}))))
    (is (= 400 (error-status #(command! 9302 id :issues :escalate (:id blocker)
                                        {:decision "maybe" :note "无效决定"}))))
    ;; 独立质量审批人确认后状态翻转为 acknowledged, 问题仍 open, 记录确认人/决定与审计.
    (let [acked (command! 9302 id :issues :escalate (:id blocker)
                         {:decision "approved" :note "责成停线整改并复测"})]
      (is (= "acknowledged" (:escalation_state acked)))
      (is (= "open" (:status acked)))
      (is (= 9302 (:escalation_ack_by acked)))
      (is (= "approved" (:escalation_decision acked)))
      (is (some #(= "escalation_acknowledged" (:action %)) (:workflow_history acked))))
    ;; 重复确认 409.
    (is (= 409 (error-status #(command! 9302 id :issues :escalate (:id blocker)
                                        {:decision "approved" :note "重复确认"}))))
    ;; 确认后方可提交解决.
    (is (= "in_review" (:status (command! id :issues :resolve (:id blocker)
                                          {:resolution "重新装配并复测通过" :evidence_ids [evidence] :reviewer_id 9302}))))
    ;; workspace 回显升级字段.
    (let [row (first (filter #(= (:id blocker) (:id %)) (:issues (workspace id))))]
      (is (true? (:escalated row)))
      (is (= "acknowledged" (:escalation_state row))))
    ;; 登记时已逾期的阻断问题升级到管理层(steering); 经评估豁免(rejected)记为 waived 并解除门控.
    (let [late (command! id :issues :create nil {:title "逾期阻断" :severity "blocker"
                                                 :owner_id 9301 :due_date "2026-01-01"})]
      (is (= "steering" (:escalation_level late)))
      (is (= 409 (error-status #(command! id :issues :resolve (:id late)
                                          {:resolution "补做整改" :evidence_ids [evidence] :reviewer_id 9302}))))
      (let [waived (command! 9302 id :issues :escalate (:id late) {:decision "rejected" :note "评估后豁免"})]
        (is (= "waived" (:escalation_state waived)))
        (is (= "in_review" (:status (command! id :issues :resolve (:id late)
                                              {:resolution "补做整改并复测" :evidence_ids [evidence] :reviewer_id 9302}))))))))
