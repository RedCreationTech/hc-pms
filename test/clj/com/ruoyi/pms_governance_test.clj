(ns com.ruoyi.pms-governance-test
  "真实数据库上的治理审批,证据,整批导入和跨模块闭环测试."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.approval :as approval]
    [com.ruoyi.domain.pms.governance.collaboration :as collab]
    [com.ruoyi.domain.pms.governance.evidence :as evidence]
    [com.ruoyi.domain.pms.governance.gates :as gates]
    [com.ruoyi.domain.pms.governance.quality :as quality]
    [com.ruoyi.domain.pms.governance.stakeholders :as stakeholders]
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
           (select-keys (evidence/trace-summary report) [:requirements :fully-traced :missing-design :missing-verification])))
    (is (= 0 (get (evidence/trace-summary report) :coverage-pct)))
    (is (= 50 (get (evidence/trace-summary report) :design-pct)))))


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


(deftest requirement-verification-method-is-optional-enum-persisted
  (let [id (project!)
        req (command! id :requirements :create nil
                      {:code "URS-VM-1" :text "控制器固件须支持远程升级" :category "功能"
                       :priority "required" :owner_id 9301 :verification_method "test"})]
    ;; 合法枚举回显并随 payload 不可变持久化, 读模型原样返回.
    (is (= "test" (:verification_method req)))
    (is (= "test" (:verification_method (first (filter #(= (:id req) (:id %)) (:requirements (workspace id)))))))
    ;; 未填验证方式则不写入该键, 需求仍正常创建.
    (let [plain (command! id :requirements :create nil
                          {:code "URS-VM-2" :text "面板须达到防水等级" :category "功能"
                           :priority "desired" :owner_id 9301})]
      (is (nil? (:verification_method plain)))
      (is (= "registered" (:status plain))))
    ;; 非法枚举被白名单校验拒绝.
    (is (= 400 (error-status #(command! id :requirements :create nil
                                        {:code "URS-VM-3" :text "非法验证方式" :category "功能"
                                         :priority "required" :owner_id 9301 :verification_method "vibes"}))))
    ;; 修订生成新版本可改验证方式, 旧版本不漂移.
    (let [rev (command! id :requirements :revisions (:id req)
                        {:code "URS-VM-1" :text "控制器固件须支持远程升级 (补充)" :category "功能"
                         :priority "required" :owner_id 9301 :verification_method "demonstration"})]
      (is (= "demonstration" (:verification_method rev)))
      (is (= 2 (:revision rev)))
      (is (= "test" (:verification_method (first (filter #(= (:id req) (:id %)) (:requirements (workspace id))))))))
    ;; CSV 五列批量导入不受可选字段影响, 导入的需求不含验证方式键.
    (let [id2 (project!)
          header "code,text,category,priority,owner_id\n"
          _ (command! id2 :requirements :import nil {:csv (str header "URS-VM-CSV,批量导入需求,功能,required,9301\n")})
          csv-req (first (filter #(= "URS-VM-CSV" (:code %)) (:requirements (workspace id2))))]
      (is (= "URS-VM-CSV" (:code csv-req)))
      (is (nil? (:verification_method csv-req))))))


(deftest requirement-verification-method-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:verification_coverage (workspace id)))
        _ (command! id :requirements :create nil {:code "URS-COV-A" :text "需求甲" :category "功能"
                                                  :priority "required" :owner_id 9301 :verification_method "test"})
        b (command! id :requirements :create nil {:code "URS-COV-B" :text "需求乙" :category "功能"
                                                  :priority "required" :owner_id 9301 :verification_method "inspection"})
        c (command! id :requirements :create nil {:code "URS-COV-C" :text "需求丙" :category "功能"
                                                  :priority "desired" :owner_id 9301})
        d (command! id :requirements :create nil {:code "URS-COV-D" :text "需求丁" :category "功能"
                                                  :priority "required" :owner_id 9301 :verification_method "test"})
        m (fn [k] (:count (first (filter #(= k (:method %)) (:by-method (cov))))))]
    ;; 四类方法各自计数与覆盖率(按每个编号最新版本, 未声明计入分母不计入分子).
    (is (= 4 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 1 (:undeclared (cov))))
    (is (= 75 (:coverage-pct (cov))))
    (is (= 2 (m "test")))
    (is (= 1 (m "inspection")))
    (is (= 0 (m "demonstration")))
    (is (= 0 (m "analysis")))
    ;; 修订给未声明的需求补上验证方式: 覆盖度上升而需求总数不变(按最新版本去重).
    (command! id :requirements :revisions (:id c) {:code "URS-COV-C" :text "需求丙(补验证方式)" :category "功能"
                                                   :priority "desired" :owner_id 9301 :verification_method "demonstration"})
    (is (= 4 (:total (cov))))
    (is (= 4 (:declared (cov))))
    (is (= 100 (:coverage-pct (cov))))
    (is (= 1 (m "demonstration")))
    ;; 作废最新版本的某需求后, 其不再计入覆盖度分母.
    (command! id :requirements :discard (:id d) {:reason "并入需求甲"})
    (is (= 3 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 1 (m "test")))
    (is (= 100 (:coverage-pct (cov))))))


(deftest document-release-coverage-is-derived-read-only
  (let [id (project!)
        rel (fn [] (:release_coverage (workspace id)))
        a (document! id "REL-COV-A")
        b (document! id "REL-COV-B")
        c (document! id "REL-COV-C")
        _d (document! id "REL-COV-D")]
    ;; A 独立批准发布, B 提交待审, C 提交后被驳回, D 保持已登记未提交.
    (command! id :documents :submit (:id a) {:reviewer_id 9302})
    (command! 9302 id :documents :decision (:id a) {:decision "approved" :reason "独立签发"})
    (command! id :documents :submit (:id b) {:reviewer_id 9302})
    (command! id :documents :submit (:id c) {:reviewer_id 9302})
    (command! 9302 id :documents :decision (:id c) {:decision "rejected" :reason "证据不足"})
    (is (= 4 (:total (rel))))
    (is (= 1 (:approved (rel))))
    (is (= 1 (:in-review (rel))))
    (is (= 1 (:rejected (rel))))
    (is (= 1 (:registered (rel))))
    (is (= 25 (:released-pct (rel))))
    ;; 修订 A 产生新的未发布版本: 按最新有效版本聚合, A 回落为未提交, 已发布率下降而文档总数不变(按 code 去重).
    (command! id :documents :revisions (:id a) {:code "REL-COV-A" :title "更新" :filename "a-v2.txt" :content "第二版正文"})
    (is (= 4 (:total (rel))))
    (is (= 0 (:approved (rel))))
    (is (= 2 (:registered (rel))))
    (is (= 0 (:released-pct (rel))))
    ;; 作废处于已驳回(可作废状态)的 C 最新版本后, 其从覆盖度分母剔除; 待审的 B 仍在.
    (command! id :documents :discard (:id c) {:reason "重复证据"})
    (is (= 3 (:total (rel))))
    (is (= 0 (:rejected (rel))))
    (is (= 1 (:in-review (rel))))))


(deftest trace-read-model-derives-evidence-release-state
  (let [id (project!)
        doc (document! id "TR-REL")
        rid (:id doc)
        req (command! id :requirements :create nil
                      {:code "URS-TR" :text "需验证项" :category "功能" :priority "required" :owner_id 9301})
        _ (command! id :traces :create nil
                    {:requirement_id (:id req) :target_kind "document" :target_id rid :relation "verifies"})
        trace (fn [] (first (filter #(= rid (:target_id %)) (:traces (workspace id)))))]
    ;; 已登记未发布的证据文档: 追踪链标注待发布, evidence_released 为 false.
    (is (= "registered" (:evidence_status (trace))))
    (is (= "pending" (:evidence_release_state (trace))))
    (is (false? (:evidence_released (trace))))
    ;; 提交发布审核(in_review)仍按未发布口径标注 pending.
    (command! id :documents :submit rid {:reviewer_id 9302})
    (is (= "in_review" (:evidence_status (trace))))
    (is (= "pending" (:evidence_release_state (trace))))
    (is (false? (:evidence_released (trace))))
    ;; 独立批准后翻为已发布, 只读派生不改追踪记录本身.
    (command! 9302 id :documents :decision rid {:decision "approved" :reason "独立签发"})
    (is (= "approved" (:evidence_status (trace))))
    (is (= "released" (:evidence_release_state (trace))))
    (is (true? (:evidence_released (trace)))))
  ;; 纯函数直测: 任务目标不参与证据发布口径, 标注 n/a 且 released 为 nil.
  (let [t (evidence/trace-read-model {} {:target_kind "task" :target_id "t1"})]
    (is (= "n/a" (:evidence_release_state t)))
    (is (nil? (:evidence_status t)))
    (is (nil? (:evidence_released t))))
  ;; 纯函数直测: 引用缺失的文档版本标注 missing, 已驳回的版本标注 rejected.
  (is (= "missing" (:evidence_release_state (evidence/trace-read-model {} {:target_kind "document" :target_id "unknown"}))))
  (is (= "rejected" (:evidence_release_state
                      (evidence/trace-read-model {"d1" {:id "d1" :status "rejected"}}
                                                 {:target_kind "document" :target_id "d1"})))))


(deftest requirement-trace-state-is-derived-read-only
  ;; C03c: URS 需求台账内联只读"追踪状态"列 - 按已登记 satisfies(设计)/verifies(验证) 关联派生齐备状态, 免迁移读取时计算, 不写存储不门控.
  (let [id (project!)
        d1 (document! id "TRS-DES")
        d2 (document! id "TRS-VER")
        base {:category "功能" :priority "required" :owner_id 9301}
        req-u (command! id :requirements :create nil (assoc base :code "URS-U" :text "未追踪需求"))
        req-d (command! id :requirements :create nil (assoc base :code "URS-D" :text "仅设计需求"))
        req-f (command! id :requirements :create nil (assoc base :code "URS-F" :text "完整追踪需求"))
        _ (command! id :traces :create nil {:requirement_id (:id req-d) :target_kind "document" :target_id (:id d1) :relation "satisfies"})
        _ (command! id :traces :create nil {:requirement_id (:id req-f) :target_kind "document" :target_id (:id d1) :relation "satisfies"})
        _ (command! id :traces :create nil {:requirement_id (:id req-f) :target_kind "document" :target_id (:id d2) :relation "verifies"})
        rs (fn [rid] (first (filter #(= rid (:id %)) (:requirements (workspace id)))))]
    ;; 无任何追踪关联: 未追踪.
    (is (= "untracked" (:trace_state (rs (:id req-u)))))
    (is (= 0 (:trace_design_links (rs (:id req-u)))))
    (is (= 0 (:trace_verification_links (rs (:id req-u)))))
    ;; 仅 satisfies: 缺验证关联, 设计关联计数为 1.
    (is (= "missing-verification" (:trace_state (rs (:id req-d)))))
    (is (= 1 (:trace_design_links (rs (:id req-d)))))
    (is (= 0 (:trace_verification_links (rs (:id req-d)))))
    ;; satisfies + verifies 齐备: 追踪完整.
    (is (= "complete" (:trace_state (rs (:id req-f)))))
    (is (= 1 (:trace_design_links (rs (:id req-f)))))
    (is (= 1 (:trace_verification_links (rs (:id req-f)))))
    ;; 只读派生不漂移既有不可变版本字段: code/revision 原样.
    (is (= "URS-F" (:code (rs (:id req-f))))))
  ;; 纯函数直测: 仅 verifies 无 satisfies -> missing-design.
  (let [m (evidence/requirement-trace-model {"r1" [{:relation "verifies"}]} {} {:id "r1"})]
    (is (= "missing-design" (:trace_state m)))
    (is (= 0 (:trace_design_links m)))
    (is (= 1 (:trace_verification_links m))))
  ;; 修订产生新版本(新 id)不继承旧版追踪链接 -> 新版本未追踪.
  (is (= "untracked" (:trace_state (evidence/requirement-trace-model {"old" [{:relation "satisfies"}]} {} {:id "new"})))))


(deftest requirement-verification-evidence-alignment-is-derived-read-only
  ;; C01 延伸: 按需求编号最新有效版本交叉核对已声明验证方式与是否已配 verifies 验证证据关联的只读一致性(免迁移, 读取时派生, 不门控不写存储; 未声明方式不进分母).
  (let [id (project!)
        d1 (document! id "VA-VER")
        d2 (document! id "VA-VER2")
        base {:category "功能" :priority "required" :owner_id 9301}
        req-n (command! id :requirements :create nil (assoc base :code "URS-N" :text "未声明方式需求"))
        req-gap (command! id :requirements :create nil (assoc base :code "URS-G" :text "声明方式缺验证需求" :verification_method "test"))
        req-al (command! id :requirements :create nil (assoc base :code "URS-A" :text "声明方式已配验证需求" :verification_method "inspection"))
        _ (command! id :traces :create nil {:requirement_id (:id req-al) :target_kind "document" :target_id (:id d1) :relation "verifies"})
        rs (fn [rid] (first (filter #(= rid (:id %)) (:requirements (workspace id)))))
        al (fn [] (:verification_evidence_alignment (workspace id)))]
    ;; 逐版本内联: 未声明 -> not-applicable; 声明无 verifies -> declared-unverified; 声明且已挂 verifies -> aligned.
    (is (= "not-applicable" (:verification_alignment (rs (:id req-n)))))
    (is (= "declared-unverified" (:verification_alignment (rs (:id req-gap)))))
    (is (= "aligned" (:verification_alignment (rs (:id req-al)))))
    ;; 聚合只针对声明了验证方式的最新版本: 未声明方式者不进分母.
    (is (= 2 (:declared (al))))
    (is (= 1 (:aligned (al))))
    (is (= 1 (:gap (al))))
    (is (= 50 (:alignment-pct (al))))
    ;; 给缺验证的需求补一条 verifies 关联 -> 对齐率升到 100, 内联标注翻转为 aligned.
    (command! id :traces :create nil {:requirement_id (:id req-gap) :target_kind "document" :target_id (:id d2) :relation "verifies"})
    (is (= "aligned" (:verification_alignment (rs (:id req-gap)))))
    (is (= 2 (:declared (al))))
    (is (= 2 (:aligned (al))))
    (is (= 0 (:gap (al))))
    (is (= 100 (:alignment-pct (al))))
    ;; 修订产生新版本(新 id)不继承旧版 verifies 链接 -> 新版本回落 declared-unverified; 聚合按编号最新有效版本去重, declared 仍 2.
    (let [rev-gap (command! id :requirements :revisions (:id req-gap) (assoc base :code "URS-G" :text "声明方式缺验证需求(修订)" :verification_method "analysis"))]
      (is (= "declared-unverified" (:verification_alignment (rs (:id rev-gap)))))
      (is (= 2 (:declared (al))))
      (is (= 1 (:aligned (al))))
      (is (= 1 (:gap (al))))
      (is (= 50 (:alignment-pct (al))))
      ;; 作废未被追踪引用的最新修订版本 -> 该编号退出对齐分母, 只剩已对齐的 URS-A.
      (command! id :requirements :discard (:id rev-gap) {:reason "并入需求甲"})
      (is (= 1 (:declared (al))))
      (is (= 1 (:aligned (al))))
      (is (= 0 (:gap (al))))
      (is (= 100 (:alignment-pct (al)))))
    ;; 只读派生不改既有不可变版本字段.
    (is (= "URS-A" (:code (rs (:id req-al)))))
    (is (= "aligned" (:verification_alignment (rs (:id req-al))))))
  ;; 纯函数直测: 声明且带 verifies -> aligned; 声明无链接 -> declared-unverified; 未声明 -> not-applicable(不进 declared).
  (is (= "aligned" (:verification_alignment (evidence/requirement-trace-model {"r" [{:relation "verifies"}]} {} {:id "r" :verification_method "test"}))))
  (is (= "declared-unverified" (:verification_alignment (evidence/requirement-trace-model {} {} {:id "r" :verification_method "test"}))))
  (is (= "not-applicable" (:verification_alignment (evidence/requirement-trace-model {"r" [{:relation "verifies"}]} {} {:id "r"}))))
  (let [agg (evidence/verification-evidence-alignment
             [{:id "a" :code "A" :revision 1 :status "registered" :verification_method "test"}
              {:id "b" :code "B" :revision 1 :status "registered" :verification_method "analysis"}
              {:id "c" :code "C" :revision 1 :status "registered"}]
             [{:requirement_id "a" :relation "verifies"}] {})]
    (is (= 2 (:declared agg)))
    (is (= 1 (:aligned agg)))
    (is (= 1 (:gap agg)))
    (is (= 50 (:alignment-pct agg)))))


(deftest requirement-verification-evidence-release-is-derived-read-only
  ;; C01 延伸: 区分验证(verifies)关联所指向的证据文档是否已发布(approved)的只读派生(免迁移, 读取时计算, 不门控不写存储; 对齐口径 aligned 只看有无关联, 发布与否单列 evidence-released/pending).
  (let [id (project!)
        doc-rel (document! id "VER-REL")
        doc-pend (document! id "VER-PEND")
        base {:category "功能" :priority "required" :owner_id 9301}
        req-rel (command! id :requirements :create nil (assoc base :code "URS-RL" :text "验证证据已发布" :verification_method "test"))
        req-pend (command! id :requirements :create nil (assoc base :code "URS-PD" :text "验证证据待发布" :verification_method "inspection"))
        req-none (command! id :requirements :create nil (assoc base :code "URS-NN" :text "声明方式缺验证" :verification_method "analysis"))
        _ (command! id :traces :create nil {:requirement_id (:id req-rel) :target_kind "document" :target_id (:id doc-rel) :relation "verifies"})
        _ (command! id :traces :create nil {:requirement_id (:id req-pend) :target_kind "document" :target_id (:id doc-pend) :relation "verifies"})
        rs (fn [rid] (first (filter #(= rid (:id %)) (:requirements (workspace id)))))
        al (fn [] (:verification_evidence_alignment (workspace id)))]
    ;; 内联验证证据状态: 关联指向已登记未发布文档 -> pending; 无 verifies 关联 -> no-verification.
    (is (= "pending" (:verification_evidence_state (rs (:id req-rel)))))
    (is (= "pending" (:verification_evidence_state (rs (:id req-pend)))))
    (is (= "no-verification" (:verification_evidence_state (rs (:id req-none)))))
    ;; 聚合: declared=3, aligned=2(均有 verifies), gap=1, evidence-released=0, pending=2, released-pct=0.
    (is (= 3 (:declared (al))))
    (is (= 2 (:aligned (al))))
    (is (= 1 (:gap (al))))
    (is (= 0 (:evidence-released (al))))
    (is (= 2 (:evidence-pending (al))))
    (is (= 0 (:evidence-released-pct (al))))
    ;; 独立批准 doc-rel 发布 -> 其验证关联翻 released, 聚合 released=1/pending=1/released-pct=33; 对齐口径不因此改变仍 2.
    (approve! id :documents (:id doc-rel))
    (is (= "released" (:verification_evidence_state (rs (:id req-rel)))))
    (is (= "pending" (:verification_evidence_state (rs (:id req-pend)))))
    (is (= 2 (:aligned (al))))
    (is (= 1 (:evidence-released (al))))
    (is (= 1 (:evidence-pending (al))))
    (is (= 33 (:evidence-released-pct (al))))
    ;; 再批准 doc-pend -> released=2/pending=0, released-pct=67(分母仍为声明数 3 含缺验证项).
    (approve! id :documents (:id doc-pend))
    (is (= 2 (:evidence-released (al))))
    (is (= 0 (:evidence-pending (al))))
    (is (= 67 (:evidence-released-pct (al))))
    ;; 只读派生不改既有不可变版本字段.
    (is (= "URS-RL" (:code (rs (:id req-rel))))))
  ;; 纯函数直测: 声明且 verifies 指向已批准文档 -> released; 指向未批准文档 -> pending; 指向任务 -> pending; 未声明 -> not-applicable.
  (is (= "released" (:verification_evidence_state
                     (evidence/requirement-trace-model {"r" [{:relation "verifies" :target_kind "document" :target_id "d1"}]}
                                                        {"d1" {:id "d1" :status "approved"}}
                                                        {:id "r" :verification_method "test"}))))
  (is (= "pending" (:verification_evidence_state
                    (evidence/requirement-trace-model {"r" [{:relation "verifies" :target_kind "document" :target_id "d1"}]}
                                                       {"d1" {:id "d1" :status "registered"}}
                                                       {:id "r" :verification_method "test"}))))
  (is (= "pending" (:verification_evidence_state
                    (evidence/requirement-trace-model {"r" [{:relation "verifies" :target_kind "task" :target_id "t1"}]}
                                                       {}
                                                       {:id "r" :verification_method "test"}))))
  (is (= "no-verification" (:verification_evidence_state
                            (evidence/requirement-trace-model {} {} {:id "r" :verification_method "test"}))))
  (is (= "not-applicable" (:verification_evidence_state
                            (evidence/requirement-trace-model {"r" [{:relation "verifies" :target_kind "document" :target_id "d1"}]}
                                                               {"d1" {:id "d1" :status "approved"}}
                                                               {:id "r"}))))
  ;; 纯函数聚合直测: released/pending 口径与分母.
  (let [docs {"d-rel" {:id "d-rel" :status "approved"} "d-pend" {:id "d-pend" :status "in_review"}}
        agg (evidence/verification-evidence-alignment
             [{:id "a" :code "A" :revision 1 :status "registered" :verification_method "test"}
              {:id "b" :code "B" :revision 1 :status "registered" :verification_method "analysis"}
              {:id "c" :code "C" :revision 1 :status "registered" :verification_method "demonstration"}]
             [{:requirement_id "a" :relation "verifies" :target_kind "document" :target_id "d-rel"}
              {:requirement_id "b" :relation "verifies" :target_kind "document" :target_id "d-pend"}]
             docs)]
    (is (= 3 (:declared agg)))
    (is (= 2 (:aligned agg)))
    (is (= 1 (:gap agg)))
    (is (= 1 (:evidence-released agg)))
    (is (= 1 (:evidence-pending agg)))
    (is (= 33 (:evidence-released-pct agg)))))


(deftest discarded-evidence-latest-revision-is-flagged-in-trace-read-model
  ;; H18/C03 延伸: 追踪链只读标注其所引用证据文档业务编码的"最新版本"是否已被受控作废(discarded), 关闭"已作废证据对历史追踪快照的显式标注"边界; 免迁移读取时派生, 不写存储不门控不改不可变版本.
  (let [id (project!)
        doc-v1 (document! id "EV-VOID")
        req (command! id :requirements :create nil
                      {:code "URS-EV" :text "需证据验证" :category "功能" :priority "required" :owner_id 9301 :verification_method "test"})
        _ (command! id :traces :create nil
                    {:requirement_id (:id req) :target_kind "document" :target_id (:id doc-v1) :relation "verifies"})
        trace (fn [] (first (filter #(= (:id doc-v1) (:target_id %)) (:traces (workspace id)))))
        urs (fn [] (first (filter #(= (:id req) (:id %)) (:requirements (workspace id)))))]
    ;; 单版本且未作废: 追踪链 evidence_voided false, URS verification_evidence_voided false.
    (is (false? (:evidence_voided (trace))))
    (is (false? (:verification_evidence_voided (urs))))
    ;; 新增不可变修订 v2(同编号), 追踪仍指向 v1.
    (let [v2 (command! id :documents :revisions (:id doc-v1)
                       {:code "EV-VOID" :title "证据更新" :filename "ev-v2.txt" :content "第二版正文"})]
      (is (= 2 (:revision v2)))
      (is (false? (:evidence_voided (trace))) "v2 尚未作废时追踪标注不翻真")
      ;; 作废最新版本 v2: 追踪指向旧 v1 不构成对 v2 的引用, 引用守卫放行.
      (command! id :documents :discard (:id v2) {:reason "上传错误版本作废"})
      (is (true? (:evidence_voided (trace))) "编码最新版本被作废 -> 追踪标注 evidence_voided true")
      (is (true? (:verification_evidence_voided (urs))) "URS 验证证据编码最新版本作废 -> verification_evidence_voided true")
      ;; 追踪所指向的 v1 自身发布口径不因新版本作废而漂移.
      (is (= "registered" (:evidence_status (trace))))
      (is (= "EV-VOID" (:code (first (filter #(= (:id doc-v1) (:id %)) (:documents (workspace id)))))))
      ;; 受控恢复 v2 后标注回落 false, 只读派生无残留.
      (command! id :documents :restore (:id v2) {:reason "误作废恢复"})
      (is (false? (:evidence_voided (trace))))
      (is (false? (:verification_evidence_voided (urs))))))
  ;; 纯函数直测: 同编码最新版本 discarded -> 引用旧版本的追踪 evidence_voided true; 任务目标恒 false; 未作废编码 false.
  (let [docs {"a" {:id "a" :code "C1" :revision 1 :status "registered"}
              "b" {:id "b" :code "C1" :revision 2 :status "discarded"}}]
    (is (true? (:evidence_voided (evidence/trace-read-model docs {:target_kind "document" :target_id "a"}))))
    (is (false? (:evidence_voided (evidence/trace-read-model docs {:target_kind "task" :target_id "t1"}))))
    (is (false? (:evidence_voided (evidence/trace-read-model {"x" {:id "x" :code "CX" :revision 1 :status "approved"}}
                                                              {:target_kind "document" :target_id "x"})))))
  ;; 纯函数直测: requirement-trace-model verification_evidence_voided 随 verifies 指向编码最新版本作废而翻真; 任务目标恒 false.
  (let [docs {"a" {:id "a" :code "C1" :revision 1 :status "registered"}
              "b" {:id "b" :code "C1" :revision 2 :status "discarded"}}]
    (is (true? (:verification_evidence_voided
                (evidence/requirement-trace-model
                 {"r" [{:relation "verifies" :target_kind "document" :target_id "a"}]} docs {:id "r" :verification_method "test"}))))
    (is (false? (:verification_evidence_voided
                 (evidence/requirement-trace-model
                  {"r" [{:relation "verifies" :target_kind "task" :target_id "t"}]} docs {:id "r" :verification_method "test"}))))))


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


(deftest closed-meeting-action-reopens-only-through-independent-review
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "复盘会" :held_on "2026-09-10" :minutes "确认闭环质量" :attendee_ids [9301 9302]})
        action (command! id :meetings :actions (:id meeting)
                         {:title "补充联调报告" :owner_id 9301 :due_date "2026-09-20"})
        aid (:id action)
        evidence (:id (document! id "ACT-REOPEN"))
        reopen-body {:reason "发现报告遗漏关键缺陷" :reviewer_id 9302 :evidence_ids [evidence]}]
    ;; 先走正常完成并经独立核验关闭, 形成 closed 行动.
    (command! id :actions :complete aid {:result "已提交联调报告" :evidence_ids [evidence] :reviewer_id 9302})
    (is (= "closed" (:status (command! 9302 id :actions :verify aid {:decision "approved" :reason "独立核验通过"}))))
    ;; 重开申请校验: 空理由 400, 空证据 409, 审核人为提交人 409, 无审批权 403.
    (is (= 400 (error-status #(command! id :actions :reopen aid (assoc reopen-body :reason "")))))
    (is (= 409 (error-status #(command! id :actions :reopen aid (assoc reopen-body :evidence_ids [])))))
    (is (= 409 (error-status #(command! id :actions :reopen aid (assoc reopen-body :reviewer_id 9301)))))
    (is (= 403 (error-status #(command! id :actions :reopen aid (assoc reopen-body :reviewer_id 9304)))))
    ;; 提交重开: 进入 in_review 标记 action_reopen, 保留前次关闭结果供审计.
    (let [reopened (command! id :actions :reopen aid reopen-body)]
      (is (= "in_review" (:status reopened)))
      (is (= "action_reopen" (:review_action reopened)))
      (is (= 9302 (:reviewer_id reopened)))
      (is (= 9301 (:submitted_by reopened)))
      (is (= "已提交联调报告" (:prior_closure_result reopened))))
    ;; 决策职责分离: 提交人本人不可决定 403; 驳回维持 closed; 批准回到 open.
    (is (= 403 (error-status #(command! id :actions :verify aid {:decision "approved" :reason "自行重开"}))))
    (is (= "closed" (:status (command! 9302 id :actions :verify aid {:decision "rejected" :reason "证据不足"}))))
    (command! id :actions :reopen aid reopen-body)
    (is (= "open" (:status (command! 9302 id :actions :verify aid {:decision "approved" :reason "缺陷复现确认"}))))
    (is (= "open" (:status (first (filterv (fn [a] (= aid (:id a))) (:actions (workspace id)))))))))


(deftest meeting-minutes-release-requires-independent-approval
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "阶段评审会" :held_on "2026-09-12" :minutes "形成阶段结论" :attendee_ids [9301 9302]})
        mid (:id meeting)
        submit-body {:reviewer_id 9302}]
    ;; 提交校验: 审核人为提交人 409, 无项目访问 403.
    (is (= 409 (error-status #(command! id :meetings :submit mid {:reviewer_id 9301}))))
    (is (= 403 (error-status #(command! id :meetings :submit mid {:reviewer_id 9304}))))
    ;; 提交进入 in_review, 记录独立审核人与提交人.
    (let [submitted (command! id :meetings :submit mid submit-body)]
      (is (= "in_review" (:status submitted)))
      (is (= 9302 (:reviewer_id submitted)))
      (is (= 9301 (:submitted_by submitted))))
    ;; 审批中不可重复提交 409.
    (is (= 409 (error-status #(command! id :meetings :submit mid submit-body))))
    ;; 提交人自行批准 403, 非指定审核人批准 403.
    (is (= 403 (error-status #(command! id :meetings :decision mid {:decision "approved" :reason "自批"}))))
    (is (= 403 (error-status #(command! 9303 id :meetings :decision mid {:decision "approved" :reason "冒名批准"}))))
    ;; 独立审核人批准 -> released 不可变发布态, 记录发布人与结论.
    (let [released (command! 9302 id :meetings :decision mid {:decision "approved" :reason "纪要完整可归档"})]
      (is (= "approved" (:status released)))
      (is (= "approved" (:release_decision released)))
      (is (= 9302 (:released_by released))))
    ;; 已发布不可再次提交发布 409.
    (is (= 409 (error-status #(command! id :meetings :submit mid submit-body))))
    ;; 驳回路径: 新会议提交后由独立审核人驳回退回 recorded, 补充后可再次提交并批准.
    (let [m2 (command! id :meetings :create nil
                       {:title "整改例会" :held_on "2026-09-13" :minutes "待补充附件" :attendee_ids [9301 9302]})
          m2id (:id m2)]
      (command! id :meetings :submit m2id submit-body)
      (is (= "recorded" (:status (command! 9302 id :meetings :decision m2id {:decision "rejected" :reason "缺少结论"}))))
      (is (= "in_review" (:status (command! id :meetings :submit m2id submit-body))))
      (is (= "approved" (:status (command! 9302 id :meetings :decision m2id {:decision "approved" :reason "补充后通过"})))))))


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


(deftest gate-read-model-derives-check-readiness
  (let [id (project!)
        keys [:gate_total :gate_passed :gate_waived :blocking_checks :ready_to_sign]
        template (command! id :gate-templates :create nil
                           {:code "G-RM" :title "就绪度关口" :stage "execution" :required true
                            :checks [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}
                                     {:code "O-1" :title "可选一" :required false}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "评审" :reviewer_id 9302})
        find-gate (fn [] (first (filter #(= (:id gate) (:id %)) (:gates (workspace id)))))]
    (is (= {:gate_total 3 :gate_passed 0 :gate_waived 0 :blocking_checks ["R-1" "R-2"] :ready_to_sign false}
           (select-keys (find-gate) keys)))
    (command! id :gates :checks (:id gate) {:checks [{:code "R-1" :passed true :evidence_ids []}
                                                     {:code "R-2" :passed false :evidence_ids []}
                                                     {:code "O-1" :passed false :evidence_ids []}]})
    (is (= ["R-2"] (:blocking_checks (find-gate))))
    (is (false? (:ready_to_sign (find-gate))))
    (is (= 1 (:gate_passed (find-gate))))
    (command! id :gates :checks (:id gate) {:checks [{:code "R-1" :passed true :evidence_ids []}
                                                     {:code "R-2" :passed false :waived true :waiver_reason "剩余风险已接受" :evidence_ids []}
                                                     {:code "O-1" :passed false :evidence_ids []}]})
    (is (= {:gate_total 3 :gate_passed 2 :gate_waived 1 :blocking_checks [] :ready_to_sign true}
           (select-keys (find-gate) keys)))))


(deftest discarded-evidence-latest-revision-is-flagged-in-gate-snapshot-read-model
  (let [id (project!)
        doc (document! id "GATE-EV")
        template (command! id :gate-templates :create nil
                           {:code "G-VE" :title "证据作废关口" :stage "execution" :required true
                            :checks [{:code "E-1" :title "评审记录" :required true}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "验收" :reviewer_id 9302})
        find-gate (fn [] (first (filter #(= (:id gate) (:id %)) (:gates (workspace id)))))]
    ;; 检查项绑定 v1 证据 -> 编码最新版本未作废, 标注 false.
    (command! id :gates :checks (:id gate) {:checks [{:code "E-1" :passed true :evidence_ids [(:id doc)]}]})
    (is (false? (:gate_evidence_voided (find-gate))))
    (is (= 0 (:gate_voided_checks (find-gate))))
    ;; 修订到 v2 并作废 v2 (引用守卫只护 v1, v2 未引用可作废) -> 编码最新版本作废.
    (let [v2 (command! id :documents :revisions (:id doc)
                       {:code "GATE-EV" :title "更新记录" :filename "验收2.txt" :content "第二版正文\n"})]
      (is (= 2 (:revision v2)))
      (command! id :documents :discard (:id v2) {:reason "证据撤回"})
      (is (true? (:gate_evidence_voided (find-gate))))
      (is (= 1 (:gate_voided_checks (find-gate))))
      ;; 检查项自身快照口径不漂移: 仍引用 v1 且其状态未变.
      (is (= [(:id doc)] (get-in (find-gate) [:checks 0 :evidence_ids])))
      ;; 恢复 v2 -> 标注复归 false.
      (command! id :documents :restore (:id v2) {:reason "误作废回退"})
      (is (false? (:gate_evidence_voided (find-gate))))
      (is (= 0 (:gate_voided_checks (find-gate)))))
    ;; 纯函数直测 voided-document-codes + gate-evidence-voided-model 命中/未命中/缺失.
    (let [docs {"a" {:id "a" :code "A" :revision 1 :status "registered"}
                "b" {:id "b" :code "B" :revision 2 :status "discarded"}}
          vc (evidence/voided-document-codes docs)]
      (is (= #{"B"} vc))
      (is (true? (:gate_evidence_voided (gates/gate-evidence-voided-model vc docs {:checks [{:code "X" :evidence_ids ["b"]}]}))))
      (is (= 1 (:gate_voided_checks (gates/gate-evidence-voided-model vc docs {:checks [{:code "X" :evidence_ids ["a" "b"]}]}))))
      (is (false? (:gate_evidence_voided (gates/gate-evidence-voided-model vc docs {:checks [{:code "X" :evidence_ids ["a"]}]}))))
      (is (= 0 (:gate_voided_checks (gates/gate-evidence-voided-model vc docs {:checks [{:code "X" :evidence_ids ["missing"]}]})))))))


(deftest passed-evidence-not-yet-released-is-flagged-in-gate-snapshot-read-model
  (let [id (project!)
        doc-a (document! id "GATE-RA")
        doc-b (document! id "GATE-RB")
        template (command! id :gate-templates :create nil
                           {:code "G-RE" :title "证据发布关口" :stage "execution" :required true
                            :checks [{:code "E-1" :title "设计记录" :required true}
                                     {:code "E-2" :title "测试记录" :required true}
                                     {:code "E-3" :title "无证据自检" :required false}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "验收" :reviewer_id 9302})
        find-gate (fn [] (first (filter #(= (:id gate) (:id %)) (:gates (workspace id)))))]
    ;; 两项通过且各绑定一份尚未签发 (registered) 的证据, 另加一项通过但无证据.
    (command! id :gates :checks (:id gate)
              {:checks [{:code "E-1" :passed true :evidence_ids [(:id doc-a)]}
                        {:code "E-2" :passed true :evidence_ids [(:id doc-b)]}
                        {:code "E-3" :passed true :evidence_ids []}]})
    (is (= 2 (:gate_evidence_checks (find-gate))))
    (is (= 2 (:gate_evidence_pending (find-gate))))
    (is (true? (:gate_evidence_unreleased (find-gate))))
    ;; 独立签发 doc-a -> 该项证据已发布, 待发布数降至 1.
    (command! id :documents :submit (:id doc-a) {:reviewer_id 9302})
    (command! 9302 id :documents :decision (:id doc-a) {:decision "approved" :reason "独立签发 A"})
    (is (= 2 (:gate_evidence_checks (find-gate))))
    (is (= 1 (:gate_evidence_pending (find-gate))))
    (is (true? (:gate_evidence_unreleased (find-gate))))
    ;; 独立签发 doc-b -> 两项证据均已发布, 待发布归零, 布尔翻回 false.
    (command! id :documents :submit (:id doc-b) {:reviewer_id 9302})
    (command! 9302 id :documents :decision (:id doc-b) {:decision "approved" :reason "独立签发 B"})
    (is (= 2 (:gate_evidence_checks (find-gate))))
    (is (= 0 (:gate_evidence_pending (find-gate))))
    (is (false? (:gate_evidence_unreleased (find-gate))))
    ;; 与"证据已作废"正交: 发布后再修订并作废新版本, 快照仍指向已发布的 v1 (unreleased 不变 false), 作废标注才翻转.
    (let [v2 (command! id :documents :revisions (:id doc-a)
                       {:code "GATE-RA" :title "更新记录" :filename "a-v2.txt" :content "第二版正文\n"})]
      (command! id :documents :discard (:id v2) {:reason "撤回新版"})
      (is (false? (:gate_evidence_unreleased (find-gate))))
      (is (= 0 (:gate_evidence_pending (find-gate))))
      (is (true? (:gate_evidence_voided (find-gate))))))
  ;; 纯函数直测 gate-evidence-release-model 的通过/未通过/空证据/缺档/驳回口径.
  (let [docs {"a" {:id "a" :code "A" :status "approved"}
              "b" {:id "b" :code "B" :status "registered"}
              "r" {:id "r" :code "R" :status "rejected"}}
        m (fn [checks] (gates/gate-evidence-release-model docs {:checks checks}))]
    (is (= 0 (:gate_evidence_pending (m [{:code "X" :passed true :evidence_ids ["a"]}]))))
    (is (false? (:gate_evidence_unreleased (m [{:code "X" :passed true :evidence_ids ["a"]}]))))
    (is (= 1 (:gate_evidence_pending (m [{:code "X" :passed true :evidence_ids ["a" "b"]}]))))
    (is (= 1 (:gate_evidence_pending (m [{:code "X" :passed true :evidence_ids ["r"]}]))))
    (is (= 1 (:gate_evidence_pending (m [{:code "X" :passed true :evidence_ids ["missing"]}]))))
    (is (= 0 (:gate_evidence_checks (m [{:code "X" :passed false :evidence_ids ["b"]}]))))
    (is (= 0 (:gate_evidence_checks (m [{:code "X" :passed true :evidence_ids []}]))))
    (is (= 2 (:gate_evidence_checks (m [{:code "1" :passed true :evidence_ids ["a"]}
                                        {:code "2" :passed true :evidence_ids ["b"]}]))))))


(deftest discarded-deliverable-latest-revision-is-flagged-in-dq-snapshot-read-model
  (let [id (project!)
        doc (document! id "DQ-DLV")
        dq (command! id :dqs :create nil
                     {:code "DQ-VE" :title "确认交付件" :owner_id 9301
                      :checklist [{:code "C-1" :title "交付件核对" :required true}]
                      :deliverable_ids [(:id doc)]})
        find-dq (fn [] (first (filter #(= (:id dq) (:id %)) (:dqs (workspace id)))))]
    ;; 交付件编码最新版本未作废 -> 标注 false.
    (is (false? (:dq_deliverable_voided (find-dq))))
    (is (= 0 (:dq_voided_deliverables (find-dq))))
    ;; 修订到 v2 (DQ 交付件引用守卫不护任何版本, 但仍演示引用快照不漂移) -> 有更新版本使 stale=true 而尚未作废 voided=false.
    (let [v2 (command! id :documents :revisions (:id doc)
                       {:code "DQ-DLV" :title "更新交付件" :filename "交付2.txt" :content "第二版正文\n"})]
      (is (= 2 (:revision v2)))
      (is (true? (:dq_stale (find-dq))))
      (is (false? (:dq_deliverable_voided (find-dq))))
      ;; 作废编码最新版本 v2 -> 交付件业务编码现已整体作废, 标注转 true.
      (command! id :documents :discard (:id v2) {:reason "交付件撤回"})
      (is (true? (:dq_deliverable_voided (find-dq))))
      (is (= 1 (:dq_voided_deliverables (find-dq))))
      ;; DQ 自身快照口径不漂移: 仍引用 v1.
      (is (= [(:id doc)] (:deliverable_ids (find-dq))))
      ;; 恢复 v2 -> 标注复归 false (仍 stale, 因存在更新版本).
      (command! id :documents :restore (:id v2) {:reason "误作废回退"})
      (is (false? (:dq_deliverable_voided (find-dq))))
      (is (= 0 (:dq_voided_deliverables (find-dq)))))
    ;; 纯函数直测 voided-document-codes + dq-deliverable-voided-model 命中/未命中/缺失/空.
    (let [docs {"a" {:id "a" :code "A" :revision 1 :status "registered"}
                "b" {:id "b" :code "B" :revision 2 :status "discarded"}}
          vc (evidence/voided-document-codes docs)]
      (is (= #{"B"} vc))
      (is (true? (:dq_deliverable_voided (quality/dq-deliverable-voided-model vc docs {:deliverable_ids ["b"]}))))
      (is (= 1 (:dq_voided_deliverables (quality/dq-deliverable-voided-model vc docs {:deliverable_ids ["a" "b"]}))))
      (is (false? (:dq_deliverable_voided (quality/dq-deliverable-voided-model vc docs {:deliverable_ids ["a"]}))))
      (is (= 0 (:dq_voided_deliverables (quality/dq-deliverable-voided-model vc docs {:deliverable_ids ["missing"]}))))
      (is (= 0 (:dq_voided_deliverables (quality/dq-deliverable-voided-model vc docs {})))))))


(deftest dq-required-check-readiness-is-flagged-in-read-model
  (let [id (project!)
        dq (command! id :dqs :create nil
                     {:code "DQ-RR" :title "必需检查就绪度" :owner_id 9301
                      :checklist [{:code "R-1" :title "必需一" :required true}
                                  {:code "R-2" :title "必需二" :required true}
                                  {:code "O-1" :title "可选一" :required false}]
                      :deliverable_ids []})
        find-dq (fn [] (first (filter #(= (:id dq) (:id %)) (:dqs (workspace id)))))]
    ;; 初始: 全部未通过 -> 必需 2 项缺 2 未就绪, 总通过 0/3.
    (is (= 2 (:dq_required_total (find-dq))))
    (is (= 0 (:dq_required_passed (find-dq))))
    (is (= 2 (:dq_required_missing (find-dq))))
    (is (false? (:dq_required_met (find-dq))))
    (is (= 0 (:dq_passed (find-dq))))
    (is (= 3 (:dq_total (find-dq))))
    ;; 通过 R-1 + 可选 O-1, R-2 仍未通过 -> 必需缺 1 未就绪, 但总通过 2/3.
    (command! id :dqs :checks (:id dq)
              {:results [{:code "R-1" :passed true :note ""}
                         {:code "R-2" :passed false :note ""}
                         {:code "O-1" :passed true :note ""}]})
    (is (= 1 (:dq_required_passed (find-dq))))
    (is (= 1 (:dq_required_missing (find-dq))))
    (is (false? (:dq_required_met (find-dq))))
    (is (= 2 (:dq_passed (find-dq))))
    ;; 补齐最后必需 R-2, 可选 O-1 退回未通过 -> 必需全通过 met=true 就绪 (与可选无关), 总通过 2/3.
    (command! id :dqs :checks (:id dq)
              {:results [{:code "R-1" :passed true :note ""}
                         {:code "R-2" :passed true :note ""}
                         {:code "O-1" :passed false :note ""}]})
    (is (= 2 (:dq_required_passed (find-dq))))
    (is (= 0 (:dq_required_missing (find-dq))))
    (is (true? (:dq_required_met (find-dq))))
    (is (= 2 (:dq_passed (find-dq))))
    (is (= "ready" (:status (find-dq))))
    ;; 纯函数直测: 无必需项视为已就绪 (与 check-dq! every? 口径一致), 各计数为 0.
    (let [row (quality/dq-read-model [] {:checklist [{:code "X" :required false :passed false}]})]
      (is (= 0 (:dq_required_total row)))
      (is (true? (:dq_required_met row)))
      (is (= 0 (:dq_required_missing row)))
      (is (= 0 (:dq_required_passed row))))))


(deftest dq-required-check-exception-waiver-counts-as-satisfied
  (let [id (project!)
        doc (document! id "DQ-XW")
        dq (command! id :dqs :create nil
                     {:code "DQ-XW" :title "必需检查例外放行" :owner_id 9301
                      :checklist [{:code "R-1" :title "必需一" :required true}
                                  {:code "R-2" :title "必需二" :required true}
                                  {:code "O-1" :title "可选一" :required false}]
                      :deliverable_ids [(:id doc)]})
        find-dq (fn [] (first (filter #(= (:id dq) (:id %)) (:dqs (workspace id)))))]
    ;; 仅通过 R-1, R-2 既未通过也未例外 -> 必需未满足, 仍草稿, 无法提交签认 (409).
    (command! id :dqs :checks (:id dq)
              {:results [{:code "R-1" :passed true :note ""}
                         {:code "R-2" :passed false :note ""}
                         {:code "O-1" :passed false :note ""}]})
    (is (= 1 (:dq_required_passed (find-dq))))
    (is (= 1 (:dq_required_satisfied (find-dq))))
    (is (= 0 (:dq_required_waived (find-dq))))
    (is (= 1 (:dq_required_missing (find-dq))))
    (is (false? (:dq_required_met (find-dq))))
    (is (= "draft" (:status (find-dq))))
    (is (= 409 (error-status #(command! id :dqs :submit (:id dq) {:reviewer_id 9302}))))
    ;; R-2 经有理由例外放行 -> 必需按 通过 OR 例外 计为全满足 -> ready, 提交签认并独立批准放行.
    (command! id :dqs :checks (:id dq)
              {:results [{:code "R-1" :passed true :note ""}
                         {:code "R-2" :passed false :note "" :waived true :waiver_reason "计量器具送检中, 责任人同意先行签认"}
                         {:code "O-1" :passed false :note ""}]})
    (is (= 1 (:dq_required_passed (find-dq))))
    (is (= 1 (:dq_required_waived (find-dq))))
    (is (= 2 (:dq_required_satisfied (find-dq))))
    (is (= 0 (:dq_required_missing (find-dq))))
    (is (true? (:dq_required_met (find-dq))))
    (is (= 1 (:dq_waived (find-dq))))
    (is (= 1 (:dq_passed (find-dq))))
    (is (= "ready" (:status (find-dq))))
    (is (= "in_review" (:status (command! id :dqs :submit (:id dq) {:reviewer_id 9302}))))
    (is (= "approved" (:status (command! 9302 id :dqs :decision (:id dq) {:decision "approved" :reason "独立确认例外合理"}))))
    ;; 项目级闭环汇总: 必需达成 1, 靠例外满足 1.
    (let [s (:dq_summary (workspace id))]
      (is (= 1 (:required-met s)))
      (is (= 1 (:exception-met s))))
    ;; 例外守卫: 通过且例外 / 例外缺说明 / waived 非布尔 均 400; 合法例外带说明 -> ready.
    (let [dq2 (command! id :dqs :create nil
                        {:code "DQ-XG" :title "例外守卫" :owner_id 9301
                         :checklist [{:code "R" :title "必需" :required true}]
                         :deliverable_ids []})
          find-dq2 (fn [] (first (filter #(= (:id dq2) (:id %)) (:dqs (workspace id)))))]
      (is (= 400 (error-status #(command! id :dqs :checks (:id dq2)
                                          {:results [{:code "R" :passed true :note "" :waived true :waiver_reason "矛盾"}]}))))
      (is (= 400 (error-status #(command! id :dqs :checks (:id dq2)
                                          {:results [{:code "R" :passed false :note "" :waived true}]}))))
      (is (= 400 (error-status #(command! id :dqs :checks (:id dq2)
                                          {:results [{:code "R" :passed false :note "" :waived "yes"}]}))))
      (is (false? (:dq_required_met (find-dq2))))
      (command! id :dqs :checks (:id dq2)
                {:results [{:code "R" :passed false :note "" :waived true :waiver_reason "责任人同意先行"}]})
      (is (= "ready" (:status (find-dq2))))
      (is (= 1 (:dq_required_waived (find-dq2)))))
    ;; 纯函数直测: 必需例外满足 met=true, 通过数不含例外.
    (let [row (quality/dq-read-model [] {:checklist [{:code "R" :required true :passed false :waived true}]})]
      (is (= 1 (:dq_required_waived row)))
      (is (= 1 (:dq_required_satisfied row)))
      (is (= 0 (:dq_required_missing row)))
      (is (true? (:dq_required_met row)))
      (is (= 0 (:dq_required_passed row)))
      (is (= 1 (:dq_waived row))))
    ;; 零回归: 无例外时 satisfied 与 passed 完全一致.
    (let [row (quality/dq-read-model [] {:checklist [{:code "R" :required true :passed true :waived false}
                                                     {:code "S" :required true :passed false :waived false}]})]
      (is (= 1 (:dq_required_satisfied row)))
      (is (= 1 (:dq_required_passed row)))
      (is (= 0 (:dq_required_waived row)))
      (is (= 1 (:dq_required_missing row)))
      (is (false? (:dq_required_met row)))
      (is (= 0 (:dq_waived row))))
    ;; dq-summary exception-met 直测: 仅 met 且 waived>0 才计入, 未 met 者即便有例外也不计.
    (let [m (quality/dq-summary [{:status "approved" :dq_required_met true :dq_required_waived 1}
                                 {:status "ready" :dq_required_met true :dq_required_waived 0}
                                 {:status "in_review" :dq_required_met false :dq_required_waived 2}])]
      (is (= 2 (:required-met m)))
      (is (= 1 (:exception-met m))))))


(deftest dq-failed-required-check-materializes-tracked-remediation-action
  (let [id (project!)
        dq (command! id :dqs :create nil
                     {:code "DQ-RA" :title "整改落实验证" :owner_id 9301
                      :checklist [{:code "R-1" :title "必需一" :required true}
                                  {:code "R-2" :title "必需二" :required true}
                                  {:code "O-1" :title "可选一" :required false}]
                      :deliverable_ids []})
        find-dq (fn [] (first (filter #(= (:id dq) (:id %)) (:dqs (workspace id)))))]
    ;; 默认: 省略标题/责任人/检查项 -> 取首个未通过必需项 R-1, 标题回退"整改: ...", 责任人沿用 DQ 责任人 9301, 记录来源 DQ, 新建为开放行动项.
    (let [a (command! id :dqs :remediation-action (:id dq) {:due_date "2026-10-20"})]
      (is (= "open" (:status a)))
      (is (= 9301 (:owner_id a)))
      (is (= "2026-10-20" (:due_date a)))
      (is (= (:id dq) (:source_dq_id a)))
      (is (= "R-1" (:source_check_code a)))
      (is (true? (.startsWith (:title a) "整改: ")))
      (is (true? (.contains (:title a) "整改落实验证")))
      (is (true? (.contains (:title a) "必需一"))))
    ;; 显式改写标题/责任人, 指定具体未通过必需项 R-2 -> source_check_code R-2.
    (let [ov (command! id :dqs :remediation-action (:id dq)
                       {:title "补做计量校准" :check_code "R-2" :owner_id 9303 :due_date "2026-12-01"})]
      (is (= "补做计量校准" (:title ov)))
      (is (= 9303 (:owner_id ov)))
      (is (= "R-2" (:source_check_code ov)))
      (is (= (:id dq) (:source_dq_id ov))))
    ;; 非法 check_code: 可选未通过项 / 不存在编码 均 400.
    (is (= 400 (error-status #(command! id :dqs :remediation-action (:id dq) {:check_code "O-1" :due_date "2026-12-01"}))))
    (is (= 400 (error-status #(command! id :dqs :remediation-action (:id dq) {:check_code "R-9" :due_date "2026-12-01"}))))
    ;; 缺必填到期日 -> 400.
    (is (= 400 (error-status #(command! id :dqs :remediation-action (:id dq) {:title "缺期"}))))
    ;; 门控: 没有未通过必需项的 DQ (仅可选项, 仍草稿) -> 409.
    (let [na (command! id :dqs :create nil
                       {:code "DQ-NA" :title "无必需项" :owner_id 9301
                        :checklist [{:code "O" :title "仅可选" :required false}]
                        :deliverable_ids []})]
      (is (= "draft" (:status (first (filter #(= (:id na) (:id %)) (:dqs (workspace id)))))))
      (is (= 409 (error-status #(command! id :dqs :remediation-action (:id na) {:due_date "2026-12-01"})))))
    ;; 状态门控: 已就绪(必需全通过)的 DQ 不在 draft/rejected -> 409.
    (let [rd (command! id :dqs :create nil
                       {:code "DQ-RD" :title "已就绪" :owner_id 9301
                        :checklist [{:code "R" :title "必需" :required true}]
                        :deliverable_ids []})]
      (command! id :dqs :checks (:id rd) {:results [{:code "R" :passed true :note ""}]})
      (is (= "ready" (:status (first (filter #(= (:id rd) (:id %)) (:dqs (workspace id)))))))
      (is (= 409 (error-status #(command! id :dqs :remediation-action (:id rd) {:due_date "2026-12-01"})))))
    ;; 跨项目 DQ -> 404.
    (let [other (project!)
          other-dq (command! other :dqs :create nil
                             {:code "DQ-OT" :title "他项目DQ" :owner_id 9301
                              :checklist [{:code "R" :title "必需" :required true}]
                              :deliverable_ids []})]
      (is (= 404 (error-status #(command! id :dqs :remediation-action (:id other-dq) {:due_date "2026-12-01"})))))
    ;; 落实整改不改变来源 DQ 状态 (仍 draft).
    (is (= "draft" (:status (find-dq))))))


(deftest dq-remediation-action-rollup-is-derived-read-only
  (let [id (project!)
        dq (command! id :dqs :create nil
                     {:code "DQ-RM" :title "整改落实汇总" :owner_id 9301
                      :checklist [{:code "R-1" :title "必需一" :required true}
                                  {:code "R-2" :title "必需二" :required true}]
                      :deliverable_ids []})
        other (command! id :dqs :create nil
                        {:code "DQ-OR" :title "无整改DQ" :owner_id 9301
                         :checklist [{:code "O" :title "仅可选" :required false}]
                         :deliverable_ids []})
        find-dq (fn [x] (first (filter #(= (:id x) (:id %)) (:dqs (workspace id)))))]
    ;; 登记后尚未落实整改: unremediated, total 0; 另一 DQ 同样 unremediated.
    (is (= "unremediated" (:dq_remediation_state (find-dq dq))))
    (is (= 0 (:dq_remediation_total (find-dq dq))))
    (is (= "unremediated" (:dq_remediation_state (find-dq other))))
    ;; 落实一条整改: in-progress, total/open 各 1; 汇总 remediated 1 remediation-open 1; 另一 DQ 不受影响.
    (let [a1 (command! id :dqs :remediation-action (:id dq) {:check_code "R-1" :due_date "2026-10-20"})]
      (is (= 1 (:dq_remediation_total (find-dq dq))))
      (is (= 1 (:dq_remediation_open (find-dq dq))))
      (is (= "in-progress" (:dq_remediation_state (find-dq dq))))
      (is (= "unremediated" (:dq_remediation_state (find-dq other))))
      (let [s (:dq_summary (workspace id))]
        (is (= 1 (:remediated s)))
        (is (= 1 (:remediation-open s)))
        ;; 再落实一条: total 2 open 2; 关闭第一条(独立核验): open 减到 1, 仍 in-progress.
        (let [a2 (command! id :dqs :remediation-action (:id dq) {:check_code "R-2" :due_date "2026-12-01"})
              ev (:id (document! id "RM-DOC-A"))]
          (is (= 2 (:dq_remediation_open (find-dq dq))))
          (command! id :actions :complete (:id a1) {:result "整改完成并附记录" :evidence_ids [ev] :reviewer_id 9302})
          (command! 9302 id :actions :verify (:id a1) {:decision "approved" :reason "独立核验通过"})
          (is (= 2 (:dq_remediation_total (find-dq dq))))
          (is (= 1 (:dq_remediation_open (find-dq dq))))
          (is (= "in-progress" (:dq_remediation_state (find-dq dq))))
          ;; 第二条转真实任务(converted): open 0 -> completed; 汇总 remediated 1, remediation-open 0.
          (command! id :actions :task (:id a2) {:start_date "2026-09-23" :duration_days 2})
          (is (= 0 (:dq_remediation_open (find-dq dq))))
          (is (= "completed" (:dq_remediation_state (find-dq dq))))
          (let [s2 (:dq_summary (workspace id))]
            (is (= 1 (:remediated s2)))
            (is (= 0 (:remediation-open s2)))))))
    ;; 只读派生不回写 DQ 状态, 重复读取稳定.
    (is (= "draft" (:status (find-dq dq))))
    (is (= (:dq_remediation_state (find-dq dq)) (:dq_remediation_state (find-dq dq))))
    ;; 逾期: 到期日已过且未完成 -> overdue 计, 且永不超过 open.
    (let [od (command! id :dqs :create nil
                       {:code "DQ-OD" :title "逾期整改DQ" :owner_id 9301
                        :checklist [{:code "R" :title "必需" :required true}]
                        :deliverable_ids []})]
      (command! id :dqs :remediation-action (:id od) {:due_date "2020-01-01"})
      (is (= 1 (:dq_remediation_overdue (find-dq od))))
      (is (= 1 (:dq_remediation_open (find-dq od))))
      (is (<= (:dq_remediation_overdue (find-dq od)) (:dq_remediation_open (find-dq od)))))
    ;; 纯函数聚合: closed/converted 视为完成不计 open, 无 source_dq_id 不计入.
    (is (= {"d1" {:total 4 :open 2 :overdue 0}}
           (collab/remediation-rollup-by-dq
            [{:source_dq_id "d1" :status "open"}
             {:source_dq_id "d1" :status "closed"}
             {:source_dq_id "d1" :status "converted"}
             {:source_dq_id "d1" :status "in_review"}
             {:status "open"}])))
    ;; 纯函数聚合含逾期: overdue 仅计到期不晚于运行日且未关闭未转任务者.
    (is (= {"d1" {:total 5 :open 3 :overdue 2}}
           (collab/remediation-rollup-by-dq
            [{:source_dq_id "d1" :status "open" :due_date "2020-01-01"}
             {:source_dq_id "d1" :status "in_review" :due_date "2020-01-01"}
             {:source_dq_id "d1" :status "open" :due_date "2099-01-01"}
             {:source_dq_id "d1" :status "closed" :due_date "2020-01-01"}
             {:source_dq_id "d1" :status "converted" :due_date "2020-01-01"}])))
    ;; dq-remediation-read-model 纯映射三态 + overdue 缺省 0.
    (is (= "in-progress" (:dq_remediation_state (collab/dq-remediation-read-model {"d1" {:total 2 :open 1 :overdue 1}} {:id "d1"}))))
    (is (= "completed" (:dq_remediation_state (collab/dq-remediation-read-model {"d1" {:total 2 :open 0 :overdue 0}} {:id "d1"}))))
    (let [rm (collab/dq-remediation-read-model {} {:id "d2"})]
      (is (= "unremediated" (:dq_remediation_state rm)))
      (is (= 0 (:dq_remediation_overdue rm))))
    ;; dq-summary 直测: remediated 计 total>0, remediation-open 计 open>0.
    (let [m (quality/dq-summary [{:status "draft" :dq_remediation_total 1 :dq_remediation_open 1}
                                 {:status "draft" :dq_remediation_total 2 :dq_remediation_open 0}
                                 {:status "ready" :dq_remediation_total 0 :dq_remediation_open 0}])]
      (is (= 2 (:remediated m)))
      (is (= 1 (:remediation-open m))))))


(deftest gate-failed-required-check-materializes-tracked-remediation-action
  (let [id (project!)
        template (command! id :gate-templates :create nil
                           {:code "G-RA" :title "整改落实关口" :stage "design" :required true
                            :checks [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}
                                     {:code "O-1" :title "可选一" :required false}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "阶段评审" :reviewer_id 9302})
        find-gate (fn [] (first (filter #(= (:id gate) (:id %)) (:gates (workspace id)))))]
    ;; 默认: 省略标题/责任人/检查项 -> 取首个未通过必需项 R-1, 标题回退"整改: ...", 责任人沿用关口审批人 9302, 记录来源关口, 新建为开放行动项.
    (let [a (command! id :gates :remediation-action (:id gate) {:due_date "2026-10-20"})]
      (is (= "open" (:status a)))
      (is (= 9302 (:owner_id a)))
      (is (= "2026-10-20" (:due_date a)))
      (is (= (:id gate) (:source_gate_id a)))
      (is (= "R-1" (:source_check_code a)))
      (is (true? (.startsWith (:title a) "整改: ")))
      (is (true? (.contains (:title a) "阶段评审")))
      (is (true? (.contains (:title a) "必需一"))))
    ;; 显式改写标题/责任人, 指定具体未通过必需项 R-2 -> source_check_code R-2.
    (let [ov (command! id :gates :remediation-action (:id gate)
                       {:title "补齐设计评审记录" :check_code "R-2" :owner_id 9303 :due_date "2026-12-01"})]
      (is (= "补齐设计评审记录" (:title ov)))
      (is (= 9303 (:owner_id ov)))
      (is (= "R-2" (:source_check_code ov)))
      (is (= (:id gate) (:source_gate_id ov))))
    ;; 非法 check_code: 可选未通过项 / 不存在编码 均 400.
    (is (= 400 (error-status #(command! id :gates :remediation-action (:id gate) {:check_code "O-1" :due_date "2026-12-01"}))))
    (is (= 400 (error-status #(command! id :gates :remediation-action (:id gate) {:check_code "R-9" :due_date "2026-12-01"}))))
    ;; 缺必填到期日 -> 400.
    (is (= 400 (error-status #(command! id :gates :remediation-action (:id gate) {:title "缺期"}))))
    ;; 门控: 没有未通过必需项的关口 (仅可选项, 仍草稿) -> 409.
    (let [na-tpl (command! id :gate-templates :create nil
                           {:code "G-NA" :title "无必需关口" :stage "delivery" :required false
                            :checks [{:code "O" :title "仅可选" :required false}]})
          na (command! id :gates :create nil {:template_id (:id na-tpl) :title "无必需" :reviewer_id 9302})]
      (is (= "draft" (:status (first (filter #(= (:id na) (:id %)) (:gates (workspace id)))))))
      (is (= 409 (error-status #(command! id :gates :remediation-action (:id na) {:due_date "2026-12-01"})))))
    ;; 状态门控: 已提交评审 (in_review, 仍有未通过必需项) 的关口不可再落实整改 -> 409.
    (let [sg-tpl (command! id :gate-templates :create nil
                           {:code "G-SG" :title "已评审关口" :stage "manufacturing" :required true
                            :checks [{:code "R" :title "必需" :required true}]})
          sg (command! id :gates :create nil {:template_id (:id sg-tpl) :title "已评审" :reviewer_id 9302})]
      (command! id :gates :submit (:id sg) {:waiver_reason "整体风险已接受"})
      (is (= "in_review" (:status (first (filter #(= (:id sg) (:id %)) (:gates (workspace id)))))))
      (is (= 409 (error-status #(command! id :gates :remediation-action (:id sg) {:due_date "2026-12-01"})))))
    ;; 跨项目关口 -> 404 (关口归属 other 项目, 对 id 项目发整改命令时关口记录查找失败).
    (let [other (project!)
          other-tpl (command! other :gate-templates :create nil
                              {:code "G-RA-OTHER" :title "他项目整改落实关口" :stage "design" :required true
                               :checks [{:code "R-1" :title "必需一" :required true}]})
          other-gate (command! other :gates :create nil {:template_id (:id other-tpl) :title "他项目关口" :reviewer_id 9302})]
      (is (= 404 (error-status #(command! id :gates :remediation-action (:id other-gate) {:due_date "2026-12-01"})))))
    ;; 落实整改不改变来源关口状态 (仍 draft).
    (is (= "draft" (:status (find-gate))))))


(deftest gate-remediation-action-rollup-is-derived-read-only
  (let [id (project!)
        template (command! id :gate-templates :create nil
                           {:code "G-RM" :title "整改落实汇总关口" :stage "design" :required true
                            :checks [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "汇总评审" :reviewer_id 9302})
        or-tpl (command! id :gate-templates :create nil
                         {:code "G-OR" :title "无整改关口" :stage "delivery" :required false
                          :checks [{:code "O" :title "仅可选" :required false}]})
        other (command! id :gates :create nil {:template_id (:id or-tpl) :title "无整改" :reviewer_id 9302})
        find-gate (fn [x] (first (filter #(= (:id x) (:id %)) (:gates (workspace id)))))]
    ;; 登记后尚未落实整改: unremediated, total 0; 另一关口同样 unremediated.
    (is (= "unremediated" (:gate_remediation_state (find-gate gate))))
    (is (= 0 (:gate_remediation_total (find-gate gate))))
    (is (= "unremediated" (:gate_remediation_state (find-gate other))))
    ;; 落实一条整改: in-progress, total/open 各 1; 汇总 remediated 1 remediation-open 1; 另一关口不受影响.
    (let [a1 (command! id :gates :remediation-action (:id gate) {:check_code "R-1" :due_date "2026-10-20"})]
      (is (= 1 (:gate_remediation_total (find-gate gate))))
      (is (= 1 (:gate_remediation_open (find-gate gate))))
      (is (= "in-progress" (:gate_remediation_state (find-gate gate))))
      (is (= "unremediated" (:gate_remediation_state (find-gate other))))
      (let [s (:gate_closure (workspace id))]
        (is (= 1 (:remediated s)))
        (is (= 1 (:remediation-open s)))
        ;; 再落实一条: total 2 open 2; 关闭第一条(独立核验): open 减到 1, 仍 in-progress.
        (let [a2 (command! id :gates :remediation-action (:id gate) {:check_code "R-2" :due_date "2026-12-01"})
              ev (:id (document! id "GRM-DOC-A"))]
          (is (= 2 (:gate_remediation_open (find-gate gate))))
          (command! id :actions :complete (:id a1) {:result "整改完成并附记录" :evidence_ids [ev] :reviewer_id 9302})
          (command! 9302 id :actions :verify (:id a1) {:decision "approved" :reason "独立核验通过"})
          (is (= 2 (:gate_remediation_total (find-gate gate))))
          (is (= 1 (:gate_remediation_open (find-gate gate))))
          (is (= "in-progress" (:gate_remediation_state (find-gate gate))))
          ;; 第二条转真实任务(converted): open 0 -> completed; 汇总 remediated 1, remediation-open 0.
          (command! id :actions :task (:id a2) {:start_date "2026-09-23" :duration_days 2})
          (is (= 0 (:gate_remediation_open (find-gate gate))))
          (is (= "completed" (:gate_remediation_state (find-gate gate))))
          (let [s2 (:gate_closure (workspace id))]
            (is (= 1 (:remediated s2)))
            (is (= 0 (:remediation-open s2)))))))
    ;; 只读派生不回写关口状态, 重复读取稳定.
    (is (= "draft" (:status (find-gate gate))))
    (is (= (:gate_remediation_state (find-gate gate)) (:gate_remediation_state (find-gate gate))))
    ;; 逾期: 到期日已过且未完成 -> overdue 计, 且永不超过 open.
    (let [od-tpl (command! id :gate-templates :create nil
                           {:code "G-OD" :title "逾期整改关口" :stage "site" :required true
                            :checks [{:code "R" :title "必需" :required true}]})
          od (command! id :gates :create nil {:template_id (:id od-tpl) :title "逾期" :reviewer_id 9302})]
      (command! id :gates :remediation-action (:id od) {:due_date "2020-01-01"})
      (is (= 1 (:gate_remediation_overdue (find-gate od))))
      (is (= 1 (:gate_remediation_open (find-gate od))))
      (is (<= (:gate_remediation_overdue (find-gate od)) (:gate_remediation_open (find-gate od)))))
    ;; 纯函数聚合: closed/converted 视为完成不计 open, 无 source_gate_id 不计入.
    (is (= {"g1" {:total 4 :open 2 :overdue 0}}
           (collab/remediation-rollup-by-gate
            [{:source_gate_id "g1" :status "open"}
             {:source_gate_id "g1" :status "closed"}
             {:source_gate_id "g1" :status "converted"}
             {:source_gate_id "g1" :status "in_review"}
             {:status "open"}])))
    ;; 纯函数聚合含逾期: overdue 仅计到期不晚于运行日且未关闭未转任务者.
    (is (= {"g1" {:total 5 :open 3 :overdue 2}}
           (collab/remediation-rollup-by-gate
            [{:source_gate_id "g1" :status "open" :due_date "2020-01-01"}
             {:source_gate_id "g1" :status "in_review" :due_date "2020-01-01"}
             {:source_gate_id "g1" :status "open" :due_date "2099-01-01"}
             {:source_gate_id "g1" :status "closed" :due_date "2020-01-01"}
             {:source_gate_id "g1" :status "converted" :due_date "2020-01-01"}])))
    ;; gate-remediation-read-model 纯映射三态 + overdue 缺省 0.
    (is (= "in-progress" (:gate_remediation_state (collab/gate-remediation-read-model {"g1" {:total 2 :open 1 :overdue 1}} {:id "g1"}))))
    (is (= "completed" (:gate_remediation_state (collab/gate-remediation-read-model {"g1" {:total 2 :open 0 :overdue 0}} {:id "g1"}))))
    (let [rm (collab/gate-remediation-read-model {} {:id "g2"})]
      (is (= "unremediated" (:gate_remediation_state rm)))
      (is (= 0 (:gate_remediation_overdue rm))))
    ;; gate-closure-summary 直测: remediated 计 total>0, remediation-open 计 open>0.
    (let [m (gates/gate-closure-summary [{:status "draft" :gate_remediation_total 1 :gate_remediation_open 1}
                                         {:status "ready" :gate_remediation_total 2 :gate_remediation_open 0}
                                         {:status "approved" :gate_remediation_total 0 :gate_remediation_open 0}])]
      (is (= 2 (:remediated m)))
      (is (= 1 (:remediation-open m))))))


(deftest project-remediation-overview-aggregates-all-sources-read-only
  (let [actions [{:source_gate_id "g1" :status "open" :due_date "2020-01-01"}
                 {:source_gate_id "g1" :status "closed" :due_date "2099-01-01"}
                 {:source_dq_id "d1" :status "in_review" :due_date "2099-01-01"}
                 {:source_risk_id "r1" :status "converted" :due_date "2020-01-01"}
                 {:variance_kind "schedule" :status "open" :due_date "2020-01-01"}
                 {:variance_kind "cost" :status "closed" :due_date "2099-01-01"}
                 {:status "open" :due_date "2020-01-01"}]  ;; 无来源标记的会议行动不计入任何整改来源
        issues [{:id "i1" :code "ISS-1" :revision 1 :status "open" :source_test_id "t1" :due_date "2020-01-01"}
                {:id "i1" :code "ISS-1" :revision 2 :status "closed" :source_test_id "t1" :due_date "2020-01-01"}
                {:id "i2" :code "ISS-2" :revision 1 :status "open" :source_test_id "t2" :due_date "2020-01-01"}
                {:id "i3" :code "ISS-3" :revision 1 :status "open" :due_date "2020-01-01"}]  ;; 试验来源之外的问题不计入
        ov (collab/project-remediation-overview actions issues)
        by (zipmap (map :key (:by-source ov)) (:by-source ov))]
    (is (true? (:available ov)))
    (is (= 5 (:sources-with-remediation ov)))
    ;; 试验来源: 堆叠修订去重后 t1 closed, t2 open 逾期; i3 无 source_test_id 不计入.
    (is (= {:key "test" :label "试验不合格整改" :total 2 :open 1 :closed 1 :overdue 1 :closure-pct 50} (get by "test")))
    (is (= {:key "gate" :label "关口检查整改" :total 2 :open 1 :closed 1 :overdue 1 :closure-pct 50} (get by "gate")))
    (is (= {:key "dq" :label "质量检查整改" :total 1 :open 1 :closed 0 :overdue 0 :closure-pct 0} (get by "dq")))
    (is (= {:key "risk" :label "风险预防整改" :total 1 :open 0 :closed 1 :overdue 0 :closure-pct 100} (get by "risk")))
    (is (= {:key "variance" :label "绩效偏差纠正" :total 2 :open 1 :closed 1 :overdue 1 :closure-pct 50} (get by "variance")))
    ;; 全局: total 8, closed 4 (去重后 test1+gate1+risk1+variance1), open 4, overdue 3 (risk converted 已闭环且 dq 未到期故不计), 闭环率 50.
    (is (= 8 (:total ov)))
    (is (= 4 (:closed ov)))
    (is (= 4 (:open ov)))
    (is (= 3 (:overdue ov)))
    (is (= 4 (:sources-with-open ov)))
    (is (= 50 (:closure-pct ov)))
    (is (<= (:overdue ov) (:open ov)))
    ;; 空项目各计数 0 且 available false, by-source 空.
    (let [empty (collab/project-remediation-overview [] [])]
      (is (false? (:available empty)))
      (is (= {:total 0 :open 0 :closed 0 :overdue 0 :closure-pct 0}
             (select-keys empty [:total :open :closed :overdue :closure-pct])))
      (is (empty? (:by-source empty))))))


(deftest project-remediation-overview-in-workspace-spans-sources-and-does-not-write-back
  (let [id (project!)
        tpl (command! id :gate-templates :create nil
                      {:code "G-PO" :title "总览关口" :stage "design" :required true
                       :checks [{:code "R-1" :title "必需一" :required true}]})
        gate (command! id :gates :create nil {:template_id (:id tpl) :title "总览评审" :reviewer_id 9302})
        dq (command! id :dqs :create nil
                     {:code "DQ-PO" :title "总览DQ" :owner_id 9301
                      :checklist [{:code "D-1" :title "必需" :required true}] :deliverable_ids []})
        risk (command! id :risks :create nil
                       {:title "总览风险" :probability 2 :impact 2 :owner_id 9301
                        :mitigation "提前排期" :due_date "2026-12-01"})
        overview #(select-keys (:project_remediation_overview (workspace id))
                               [:available :total :open :closed :sources-with-remediation :sources-with-open])]
    ;; 空态: 尚无整改项.
    (is (= {:available false :total 0 :open 0 :closed 0 :sources-with-remediation 0 :sources-with-open 0}
           (overview)))
    ;; 三类各落实一条整改: 三个来源, 全部未完成.
    (let [ga (command! id :gates :remediation-action (:id gate) {:due_date "2020-01-01"})
          da (command! id :dqs :remediation-action (:id dq) {:check_code "D-1" :due_date "2099-01-01"})
          ra (command! id :risks :mitigation-action (:id risk) {:title "落实预防措施" :due_date "2099-01-01"})]
      (is (= {:available true :total 3 :open 3 :closed 0 :sources-with-remediation 3 :sources-with-open 3}
             (overview)))
      ;; 关口那条逾期未闭环 (全局 overdue >= 1).
      (is (<= 1 (:overdue (:project_remediation_overview (workspace id)))))
      ;; 独立核验关闭 DQ 那条: closed 1, open 2, 仍有未完成来源 2.
      (let [ev (:id (document! id "PO-DOC"))]
        (command! id :actions :complete (:id da) {:result "整改完成" :evidence_ids [ev] :reviewer_id 9302})
        (command! 9302 id :actions :verify (:id da) {:decision "approved" :reason "独立核验通过"})
        (let [ov (:project_remediation_overview (workspace id))
              dq-group (first (filter #(= "dq" (:key %)) (:by-source ov)))]
          (is (= 1 (:closed ov)))
          (is (= 2 (:open ov)))
          (is (= 2 (:sources-with-open ov)))
          (is (= {:total 1 :open 0 :closed 1 :overdue 0 :closure-pct 100}
                 (select-keys dq-group [:total :open :closed :overdue :closure-pct])))
          ;; 只读派生不回写来源对象状态: 关口仍 draft, DQ 仍 draft, 风险仍 open.
          (is (= "draft" (:status (first (filter #(= (:id gate) (:id %)) (:gates (workspace id)))))))
          (is (= "draft" (:status (first (filter #(= (:id dq) (:id %)) (:dqs (workspace id)))))))
          (is (= "open" (:status (first (filter #(= (:id risk) (:id %)) (:risks (workspace id)))))))
          (is (some #(= (:id ga) (:id %)) (:actions (workspace id)))))))))


(deftest dq-failed-required-checks-materialize-all-tracked-remediation-actions-in-one-call
  (let [id (project!)
        dq (command! id :dqs :create nil
                     {:code "DQ-RAB" :title "批量整改落实" :owner_id 9301
                      :checklist [{:code "R-1" :title "必需一" :required true}
                                  {:code "R-2" :title "必需二" :required true}
                                  {:code "R-3" :title "必需三" :required true}
                                  {:code "O-1" :title "可选一" :required false}]
                      :deliverable_ids []})
        find-dq (fn [] (first (filter #(= (:id dq) (:id %)) (:dqs (workspace id)))))]
    ;; 默认负责人沿用 DQ 责任人 9301, 一次性为三条未通过必需项各生成一条独立 open 行动, 共用到期日, 各不相同 source_check_code, 可选项不落实.
    (let [acts (command! id :dqs :remediation-actions (:id dq) {:due_date "2026-11-15"})]
      (is (= 3 (count acts)))
      (is (every? #(= "open" (:status %)) acts))
      (is (every? #(= 9301 (:owner_id %)) acts))
      (is (every? #(= "2026-11-15" (:due_date %)) acts))
      (is (every? #(= (:id dq) (:source_dq_id %)) acts))
      (is (= #{"R-1" "R-2" "R-3"} (set (map :source_check_code acts))))
      (is (every? #(true? (.startsWith (:title %) "整改: ")) acts))
      (is (every? #(true? (.contains (:title %) "批量整改落实")) acts))
      (is (= (distinct (map :id acts)) (map :id acts)))
      ;; 批量后读模型聚合: total 3 open 3 in-progress; 汇总 remediated 1 remediation-open 1.
      (is (= 3 (:dq_remediation_total (find-dq))))
      (is (= 3 (:dq_remediation_open (find-dq))))
      (is (= "in-progress" (:dq_remediation_state (find-dq))))
      (let [s (:dq_summary (workspace id))]
        (is (= 1 (:remediated s)))
        (is (= 1 (:remediation-open s))))
      ;; 转任务一条 (converted): total 不变 3, open 减到 2, 仍 in-progress.
      (command! id :actions :task (:id (first acts)) {:start_date "2026-09-23" :duration_days 2})
      (is (= 3 (:dq_remediation_total (find-dq))))
      (is (= 2 (:dq_remediation_open (find-dq))))
      (is (= "in-progress" (:dq_remediation_state (find-dq)))))
    ;; 显式统一负责人覆盖 (editor 成员 9303) -> 全部行动同负责人.
    (let [id2 (project!)
          dq2 (command! id2 :dqs :create nil
                        {:code "DQ-RAB2" :title "批量改负责人" :owner_id 9301
                         :checklist [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}]
                         :deliverable_ids []})
          acts (command! id2 :dqs :remediation-actions (:id dq2) {:owner_id 9303 :due_date "2026-12-01"})]
      (is (= 2 (count acts)))
      (is (every? #(= 9303 (:owner_id %)) acts)))
    ;; 仅未通过的必需项被落实: 先把 R-1 标记通过 (仍 draft) -> 批量只生成 R-2 一条.
    (let [id3 (project!)
          dq3 (command! id3 :dqs :create nil
                        {:code "DQ-RAB3" :title "部分通过" :owner_id 9301
                         :checklist [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}]
                         :deliverable_ids []})]
      (command! id3 :dqs :checks (:id dq3) {:results [{:code "R-1" :passed true :note ""}
                                                       {:code "R-2" :passed false :note ""}]})
      (is (= "draft" (:status (first (filter #(= (:id dq3) (:id %)) (:dqs (workspace id3)))))))
      (let [acts (command! id3 :dqs :remediation-actions (:id dq3) {:due_date "2026-12-01"})]
        (is (= 1 (count acts)))
        (is (= "R-2" (:source_check_code (first acts))))))
    ;; 白名单外字段 (title/check_code) -> input! 拒绝 400.
    (is (= 400 (error-status #(command! id :dqs :remediation-actions (:id dq) {:title "多余" :due_date "2026-12-01"}))))
    (is (= 400 (error-status #(command! id :dqs :remediation-actions (:id dq) {:check_code "R-1" :due_date "2026-12-01"}))))
    ;; 缺必填到期日 -> 400.
    (is (= 400 (error-status #(command! id :dqs :remediation-actions (:id dq) {:owner_id 9303}))))
    ;; 非法负责人 (有效但未加入本项目的用户 9304) -> k/user! 校验 400.
    (is (= 400 (error-status #(command! id :dqs :remediation-actions (:id dq) {:owner_id 9304 :due_date "2026-12-01"}))))
    ;; 门控: 没有未通过必需项的 DQ (仅可选项, 仍草稿) -> 409.
    (let [na (command! id :dqs :create nil
                       {:code "DQ-NAB" :title "无必需项批量" :owner_id 9301
                        :checklist [{:code "O" :title "仅可选" :required false}]
                        :deliverable_ids []})]
      (is (= 409 (error-status #(command! id :dqs :remediation-actions (:id na) {:due_date "2026-12-01"})))))
    ;; 状态门控: 已就绪 (必需全通过, status ready) 的 DQ 不在 draft/rejected -> 409.
    (let [rd (command! id :dqs :create nil
                       {:code "DQ-RDB" :title "已就绪批量" :owner_id 9301
                        :checklist [{:code "R" :title "必需" :required true}]
                        :deliverable_ids []})]
      (command! id :dqs :checks (:id rd) {:results [{:code "R" :passed true :note ""}]})
      (is (= "ready" (:status (first (filter #(= (:id rd) (:id %)) (:dqs (workspace id)))))))
      (is (= 409 (error-status #(command! id :dqs :remediation-actions (:id rd) {:due_date "2026-12-01"})))))
    ;; 跨项目 DQ -> 404.
    (let [other (project!)
          other-dq (command! other :dqs :create nil
                            {:code "DQ-OTB" :title "他项目批量" :owner_id 9301
                             :checklist [{:code "R" :title "必需" :required true}]
                             :deliverable_ids []})]
      (is (= 404 (error-status #(command! id :dqs :remediation-actions (:id other-dq) {:due_date "2026-12-01"})))))
    ;; 批量落实不改变来源 DQ 状态 (仍 draft).
    (is (= "draft" (:status (find-dq))))))


(deftest gate-failed-required-checks-materialize-all-tracked-remediation-actions-in-one-call
  (let [id (project!)
        template (command! id :gate-templates :create nil
                           {:code "G-RAB" :title "批量整改关口" :stage "design" :required true
                            :checks [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}
                                     {:code "R-3" :title "必需三" :required true}
                                     {:code "O-1" :title "可选一" :required false}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "阶段评审批量" :reviewer_id 9302})
        find-gate (fn [] (first (filter #(= (:id gate) (:id %)) (:gates (workspace id)))))]
    ;; 缺省负责人沿用关口审批人 9302, 一次性为三条未通过必需项各生成一条独立 open 行动, 共用到期日, 各不相同检查编码, 可选项不落实.
    (let [acts (command! id :gates :remediation-actions (:id gate) {:due_date "2026-11-15"})]
      (is (= 3 (count acts)))
      (is (every? #(= "open" (:status %)) acts))
      (is (every? #(= 9302 (:owner_id %)) acts))
      (is (every? #(= "2026-11-15" (:due_date %)) acts))
      (is (every? #(= (:id gate) (:source_gate_id %)) acts))
      (is (= #{"R-1" "R-2" "R-3"} (set (map :source_check_code acts))))
      (is (every? #(true? (.startsWith (:title %) "整改: ")) acts))
      (is (every? #(true? (.contains (:title %) "阶段评审批量")) acts))
      (is (= (distinct (map :id acts)) (map :id acts)))
      ;; 批量后读模型聚合: total 3 open 3 in-progress; 汇总 remediated 1 remediation-open 1.
      (is (= 3 (:gate_remediation_total (find-gate))))
      (is (= 3 (:gate_remediation_open (find-gate))))
      (is (= "in-progress" (:gate_remediation_state (find-gate))))
      (let [s (:gate_closure (workspace id))]
        (is (= 1 (:remediated s)))
        (is (= 1 (:remediation-open s))))
      ;; 转任务一条 (converted): total 不变 3, open 减到 2, 仍 in-progress.
      (command! id :actions :task (:id (first acts)) {:start_date "2026-09-23" :duration_days 2})
      (is (= 3 (:gate_remediation_total (find-gate))))
      (is (= 2 (:gate_remediation_open (find-gate))))
      (is (= "in-progress" (:gate_remediation_state (find-gate)))))
    ;; 白名单外字段 (title/check_code) -> input! 拒绝 400.
    (is (= 400 (error-status #(command! id :gates :remediation-actions (:id gate) {:title "多余" :due_date "2026-12-01"}))))
    (is (= 400 (error-status #(command! id :gates :remediation-actions (:id gate) {:check_code "R-1" :due_date "2026-12-01"}))))
    ;; 缺必填到期日 -> 400.
    (is (= 400 (error-status #(command! id :gates :remediation-actions (:id gate) {:owner_id 9303}))))
    ;; 非法负责人 (有效但未加入本项目的用户 9304) -> k/user! 校验 400.
    (is (= 400 (error-status #(command! id :gates :remediation-actions (:id gate) {:owner_id 9304 :due_date "2026-12-01"}))))
    ;; 显式统一负责人覆盖 (editor 成员 9303) -> 全部同负责人.
    (let [g2-tpl (command! id :gate-templates :create nil
                           {:code "G-RAB2" :title "批量改负责人关口" :stage "delivery" :required false
                            :checks [{:code "R-1" :title "必需一" :required true}
                                     {:code "R-2" :title "必需二" :required true}]})
          g2 (command! id :gates :create nil {:template_id (:id g2-tpl) :title "改负责人" :reviewer_id 9302})
          acts (command! id :gates :remediation-actions (:id g2) {:owner_id 9303 :due_date "2026-12-01"})]
      (is (= 2 (count acts)))
      (is (every? #(= 9303 (:owner_id %)) acts)))
    ;; 门控: 没有未通过必需项 (仅可选项, 仍草稿) -> 409.
    (let [na-tpl (command! id :gate-templates :create nil
                           {:code "G-NAB" :title "无必需批量" :stage "manufacturing" :required false
                            :checks [{:code "O" :title "仅可选" :required false}]})
          na (command! id :gates :create nil {:template_id (:id na-tpl) :title "无必需批量" :reviewer_id 9302})]
      (is (= 409 (error-status #(command! id :gates :remediation-actions (:id na) {:due_date "2026-12-01"})))))
    ;; 状态门控: 已提交评审 (in_review) 的关口不可批量落实 -> 409.
    (let [sg-tpl (command! id :gate-templates :create nil
                           {:code "G-SGB" :title "已评审批量关口" :stage "site" :required true
                            :checks [{:code "R" :title "必需" :required true}]})
          sg (command! id :gates :create nil {:template_id (:id sg-tpl) :title "已评审批量" :reviewer_id 9302})]
      (command! id :gates :submit (:id sg) {:waiver_reason "整体风险已接受"})
      (is (= "in_review" (:status (first (filter #(= (:id sg) (:id %)) (:gates (workspace id)))))))
      (is (= 409 (error-status #(command! id :gates :remediation-actions (:id sg) {:due_date "2026-12-01"})))))
    ;; 跨项目关口 -> 404.
    (let [other (project!)
          other-tpl (command! other :gate-templates :create nil
                              {:code "G-RAB-OT" :title "他项目批量关口" :stage "design" :required true
                               :checks [{:code "R-1" :title "必需一" :required true}]})
          other-gate (command! other :gates :create nil {:template_id (:id other-tpl) :title "他项目批量" :reviewer_id 9302})]
      (is (= 404 (error-status #(command! id :gates :remediation-actions (:id other-gate) {:due_date "2026-12-01"})))))
    ;; 批量落实不改变来源关口状态 (仍 draft).
    (is (= "draft" (:status (find-gate))))))


(deftest dq-check-method-and-responsible-role-are-optional-declarations
  (let [id (project!)
        declared (command! id :dqs :create nil
                           {:code "DQ-MR" :title "方法与角色声明" :owner_id 9301
                            :checklist [{:code "R" :title "必需" :required true}]
                            :deliverable_ids []
                            :check_method "inspection" :responsible_role "质检工程师"})
        find-dq (fn [rid] (first (filter #(= rid (:id %)) (:dqs (workspace id)))))]
    ;; 声明的检验方法与执行角色随 payload 持久化, 命令结果与读模型逐条回显.
    (is (= "inspection" (:check_method declared)))
    (is (= "质检工程师" (:responsible_role declared)))
    (let [row (find-dq (:id declared))]
      (is (= "inspection" (:check_method row)))
      (is (= "质检工程师" (:responsible_role row))))
    ;; 未声明时不写键 (零回归), 读模型该记录不含 check_method / responsible_role.
    (let [bare (command! id :dqs :create nil
                         {:code "DQ-MB" :title "未声明" :owner_id 9301
                          :checklist [{:code "R" :title "必需" :required true}]
                          :deliverable_ids []})]
      (is (false? (contains? bare :check_method)))
      (is (false? (contains? bare :responsible_role)))
      (let [brow (find-dq (:id bare))]
        (is (false? (contains? brow :check_method)))
        (is (false? (contains? brow :responsible_role)))))
    ;; 非法检验方法 -> 400; 空白执行角色仍视为未声明不写键.
    (is (= 400 (error-status #(command! id :dqs :create nil
                                        {:code "DQ-ME" :title "非法方法" :owner_id 9301
                                         :checklist [{:code "R" :title "必需" :required true}]
                                         :deliverable_ids [] :check_method "audit"}))))
    (let [blank (command! id :dqs :create nil
                          {:code "DQ-MW" :title "空白角色" :owner_id 9301
                           :checklist [{:code "R" :title "必需" :required true}]
                           :deliverable_ids [] :check_method "test" :responsible_role "   "})]
      (is (= "test" (:check_method blank)))
      (is (false? (contains? blank :responsible_role))))
    ;; 项目级闭环汇总: methods-declared / roles-declared 仅计入真正声明者 (此处 2 条有方法, 1 条有角色).
    (let [s (:dq_summary (workspace id))]
      (is (= 2 (:methods-declared s)))
      (is (= 1 (:roles-declared s))))
    ;; 纯函数直测: 缺键记录不计入声明覆盖度.
    (let [m (quality/dq-summary [{:check_method "inspection"} {:check_method nil} {} {:responsible_role "质检员"}])]
      (is (= 1 (:methods-declared m)))
      (is (= 1 (:roles-declared m))))))


(deftest dq-check-closure-summary-is-derived-read-only
  (let [id (project!)
        doc (document! id "DQ-SUM")
        did (:id doc)
        mk (fn [code] (command! id :dqs :create nil
                                {:code code :title (str "闭环 " code) :owner_id 9301
                                 :checklist [{:code "C" :title "必需检查" :required true}]
                                 :deliverable_ids [did]}))
        pass (fn [dq] (command! id :dqs :checks (:id dq) {:results [{:code "C" :passed true :note ""}]}))
        d1 (mk "DQ-1")
        d2 (mk "DQ-2")
        d3 (mk "DQ-3")
        d4 (mk "DQ-4")
        d5 (mk "DQ-5")]
    ;; d1 保持 draft (未检查); d2 ready; d3 in_review; d4 approved; d5 rejected.
    (pass d2)
    (pass d3) (command! id :dqs :submit (:id d3) {:reviewer_id 9302})
    (pass d4) (command! id :dqs :submit (:id d4) {:reviewer_id 9302})
    (command! 9302 id :dqs :decision (:id d4) {:decision "approved" :reason "独立签认通过"})
    (pass d5) (command! id :dqs :submit (:id d5) {:reviewer_id 9302})
    (command! 9302 id :dqs :decision (:id d5) {:decision "rejected" :reason "证据不足退回"})
    (let [ver (version id)
          s (:dq_summary (workspace id))]
      (is (true? (:available s)))
      (is (= 5 (:total s)))
      (is (= 1 (:draft s)))
      (is (= 1 (:ready s)))
      (is (= 1 (:in-review s)))
      (is (= 1 (:approved s)))
      (is (= 1 (:rejected s)))
      (is (= 4 (:required-met s)))
      (is (= 20 (:closure-pct s)))
      (is (= 0 (:stale s)))
      (is (= 0 (:voided s)))
      (is (= ver (version id)) "只读汇总不得漂移项目聚合版本"))
    ;; 纯函数直测: 空集与混合状态计数, 无 DQ 时 closure-pct 为 0.
    (let [e (quality/dq-summary [])]
      (is (false? (:available e)))
      (is (= 0 (:total e)))
      (is (= 0 (:closure-pct e)))
      (is (= 0 (:required-met e))))
    (let [m (quality/dq-summary [{:status "approved" :dq_required_met true}
                                 {:status "in_review" :dq_required_met true :dq_stale true}
                                 {:status "draft"}
                                 {:status "ready" :dq_required_met true}
                                 {:status "rejected" :dq_deliverable_voided true}])]
      (is (= 5 (:total m)))
      (is (= 3 (:required-met m)))
      (is (= 1 (:stale m)))
      (is (= 1 (:voided m)))
      (is (= 20 (:closure-pct m))))))


(deftest gate-closure-summary-is-derived-read-only
  (let [id (project!)
        doc-reg (document! id "GATE-CR")
        doc-rel (document! id "GATE-CL")
        _ (do (command! id :documents :submit (:id doc-rel) {:reviewer_id 9302})
              (command! 9302 id :documents :decision (:id doc-rel) {:decision "approved" :reason "独立签发"}))
        mk (fn [code]
             (let [template (command! id :gate-templates :create nil
                                      {:code code :title "闭环关口" :stage "execution" :required true
                                       :checks [{:code "R" :title "必需检查" :required true}]})]
               (command! id :gates :create nil {:template_id (:id template) :title "评审" :reviewer_id 9302})))
        check! (fn [g doc] (command! id :gates :checks (:id g)
                                     {:checks [{:code "R" :passed true :evidence_ids [(:id doc)]}]}))
        g1 (mk "G-C1")
        g2 (mk "G-C2")
        g3 (mk "G-C3")
        g4 (mk "G-C4")]
    ;; g1 保持 draft (未提交检查); g2 ready; g3 in_review (绑定未发布证据); g4 approved (绑定已发布证据).
    (check! g2 doc-reg)
    (check! g3 doc-reg) (command! id :gates :submit (:id g3) {})
    (check! g4 doc-rel) (command! id :gates :submit (:id g4) {})
    (command! 9302 id :gates :decision (:id g4) {:decision "approved" :reason "独立签核通过"})
    (let [ver (version id)
          s (:gate_closure (workspace id))]
      (is (true? (:available s)))
      (is (= 4 (:total s)))
      (is (= 1 (:draft s)))
      (is (= 1 (:ready s)))
      (is (= 1 (:in-review s)))
      (is (= 1 (:approved s)))
      (is (= 0 (:waived s)))
      (is (= 0 (:rejected s)))
      (is (= 1 (:signed s)))
      (is (= 25 (:closure-pct s)))
      (is (= 1 (:blocked s)))
      (is (= 0 (:evidence-voided s)))
      (is (= 2 (:evidence-pending s)))
      (is (= ver (version id)) "只读汇总不得漂移项目聚合版本"))
    ;; 纯函数直测: 空集与混合状态计数, 无关口时 closure-pct 为 0.
    (let [e (gates/gate-closure-summary [])]
      (is (false? (:available e)))
      (is (= 0 (:total e)))
      (is (= 0 (:closure-pct e)))
      (is (= 0 (:blocked e))))
    (let [m (gates/gate-closure-summary [{:status "approved" :ready_to_sign true}
                                         {:status "waived" :ready_to_sign true}
                                         {:status "in_review" :ready_to_sign false}
                                         {:status "draft" :ready_to_sign false :gate_evidence_unreleased true}
                                         {:status "rejected" :ready_to_sign false :gate_evidence_voided true}])]
      (is (= 5 (:total m)))
      (is (= 2 (:signed m)))
      (is (= 40 (:closure-pct m)))
      (is (= 3 (:blocked m)))
      (is (= 1 (:evidence-pending m)))
      (is (= 1 (:evidence-voided m))))))


(deftest gate-exception-check-waiver-is-derived-read-only
  (let [id (project!)
        mk (fn [code]
             (let [template (command! id :gate-templates :create nil
                                      {:code code :title "例外关口" :stage "execution" :required true
                                       :checks [{:code "R1" :title "检查一" :required true}
                                                {:code "R2" :title "检查二" :required true}]})]
               (command! id :gates :create nil {:template_id (:id template) :title "评审" :reviewer_id 9302})))
        check! (fn [g results] (command! id :gates :checks (:id g) {:checks results}))
        pass (fn [code] {:code code :passed true :evidence_ids []})
        waiv (fn [code] {:code code :passed false :waived true :waiver_reason "剩余风险已接受" :evidence_ids []})
        none (fn [code] {:code code :passed false :evidence_ids []})
        g1 (mk "G-E1")   ; R1 通过 + R2 例外放行 -> 就绪且靠例外才就绪
        g2 (mk "G-E2")   ; R1 + R2 均真实通过 -> 就绪且无例外
        g3 (mk "G-E3")   ; R1 例外放行但 R2 未满足 -> 含例外但尚未就绪(去例外不影响就绪判定)
        g4 (mk "G-E4")]  ; 草稿未检查
    (check! g1 [(pass "R1") (waiv "R2")])
    (check! g2 [(pass "R1") (pass "R2")])
    (check! g3 [(waiv "R1") (none "R2")])
    (let [ver (version id)
          s (:gate_exception_summary (workspace id))]
      (is (true? (:available s)))
      (is (= 4 (:total s)))
      (is (= 8 (:required-checks s)))          ; 4 关口 x 2 必需项
      (is (= 3 (:passed-checks s)))            ; g1 R1 + g2 R1 R2
      (is (= 2 (:exception-checks s)))         ; g1 R2 + g3 R1
      (is (= 2 (:gates-with-exception s)))     ; g1, g3
      (is (= 1 (:gates-exception-dependent s))) ; 仅 g1 (就绪且含例外); g3 未就绪故不计
      (is (= 0 (:reason-missing s)))           ; 写路径强制例外须带说明
      (is (= 25 (:waiver-pct s)))              ; round(100*2/8)
      (is (= ver (version id)) "只读例外汇总不得漂移项目聚合版本"))
    ;; 纯函数直测: 空集 available=false 且各计数为 0.
    (let [e (gates/gate-exception-summary [])]
      (is (false? (:available e)))
      (is (= 0 (:total e)))
      (is (= 0 (:required-checks e)))
      (is (= 0 (:waiver-pct e))))
    ;; 纯函数直测: 就绪且含例外=exception-dependent; 未就绪即便含例外也不计入; 例外缺说明计入 reason-missing.
    (let [m (gates/gate-exception-summary
             [{:checks [{:required true :passed true}
                        {:required true :passed false :waived true :waiver_reason "已接受"}]}   ; gA 靠例外才就绪
              {:checks [{:required true :passed false :waived true :waiver_reason "已接受"}
                        {:required true :passed false}]}                                        ; gB 含例外但未就绪 -> 不算依赖
              {:checks [{:required true :passed true}
                        {:required true :passed true}]}                                          ; gC 全通过 -> 无例外
              {:checks [{:required false :passed false}]
               :status "draft"}                                                                  ; gD 非必需项不计入分母
              {:checks [{:required true :passed false :waived true}]}]                           ; gE 例外缺说明且就绪 -> reason-missing + 依赖
             )]
      (is (= 5 (:total m)))
      (is (= 7 (:required-checks m)))            ; 2+2+2+0+1
      (is (= 3 (:passed-checks m)))              ; gA R1 + gC R1 R2 = 3
      (is (= 3 (:exception-checks m)))           ; gA R2 + gB R1 + gE R1 = 3
      (is (= 3 (:gates-with-exception m)))       ; gA, gB(未就绪仍含例外), gE
      (is (= 2 (:gates-exception-dependent m)))  ; gA 与 gE 均就绪且含例外; gB 未就绪不计
      (is (= 1 (:reason-missing m)))             ; 仅 gE 例外无说明
      (is (= 43 (:waiver-pct m))))))             ; round(100*3/7)=43



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


(deftest high-impact-change-auto-escalates-and-gates-approval
  (let [id (project!)
        base-body {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                   :schedule_impact "增加十日" :cost_impact "重新估价" :quality_impact "增加测试"
                   :resource_impact "追加工程师"}
        change (command! id :changes :create nil
                         (assoc base-body :schedule_impact_days 12 :cost_impact_amount "150000.5"))
        rid (:id change)]
    ;; 提交: 高影响变更自动进入升级待确认 (escalated + pending, level ccb), 状态 in_review.
    (command! id :changes :submit rid {:reviewer_id 9302})
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= "in_review" (:status row)))
      (is (true? (:escalated row)))
      (is (= "pending" (:escalation_state row)))
      (is (= "ccb" (:escalation_level row)))
      ;; 批准前必须先由变更控制独立确认: 指定审核人直接批准被 409 门控拒绝.
      (is (= 409 (error-status #(command! 9302 id :changes :decision rid {:decision "approved" :reason "试图直接批准"})))))
    ;; 升级确认不得由登记人/提交人本人完成 (登记与提交均为 9301).
    (is (= 403 (error-status #(command! 9301 id :changes :escalation rid {:decision "approved" :note "本人确认被拒"}))))
    ;; 由变更控制独立审批人(9303)确认后 -> acknowledged, 指定审核人方可批准.
    (let [acked (command! 9303 id :changes :escalation rid {:decision "approved" :note "变更控制确认责成处置"})]
      (is (= "acknowledged" (:escalation_state acked)))
      (is (= 409 (error-status #(command! 9303 id :changes :escalation rid {:decision "approved" :note "重复确认"}))))
      (is (= "approved" (:status (command! 9302 id :changes :decision rid {:decision "approved" :reason "确认后独立通过"}))))
      (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
        (is (= "approved" (:status row)))
        (is (= "acknowledged" (:escalation_state row)))))))


(deftest low-impact-and-high-impact-reject-are-not-escalation-gated
  (let [id (project!)
        base-body {:title "小范围调整" :reason "现场微调" :scope_impact "少量设备"
                   :schedule_impact "增加两日" :cost_impact "小幅" :quality_impact "无额外"
                   :resource_impact "无"}]
    ;; 低量化影响: 提交不触发升级, 指定审核人可直接批准.
    (let [low (command! id :changes :create nil (assoc base-body :schedule_impact_days 3 :cost_impact_amount "99999.99"))
          lid (:id low)]
      (command! id :changes :submit lid {:reviewer_id 9302})
      (let [row (first (filter #(= lid (:id %)) (:changes (workspace id))))]
        (is (nil? (:escalated row)))
        (is (= "approved" (:status (command! 9302 id :changes :decision lid {:decision "approved" :reason "低影响直接通过"}))))))
    ;; 高影响变更在升级待确认时仍可被驳回 (驳回不受门控).
    (let [hi (command! id :changes :create nil (assoc base-body :title "重大范围变更" :schedule_impact_days 15 :cost_impact_amount "200000"))
          hid (:id hi)]
      (command! id :changes :submit hid {:reviewer_id 9302})
      (let [row (first (filter #(= hid (:id %)) (:changes (workspace id))))]
        (is (= "pending" (:escalation_state row)))
        (is (= 409 (error-status #(command! 9302 id :changes :decision hid {:decision "approved" :reason "未确认不得批准"}))))
        (is (= "rejected" (:status (command! 9302 id :changes :decision hid {:decision "rejected" :reason "高影响但直接否决无需确认"}))))))))



(deftest ccb-ballot-quorum-gates-change-approval
  (let [id (project!)
        base-body {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                   :schedule_impact "增加五日" :cost_impact "重新估价" :quality_impact "增加测试"
                   :resource_impact "追加工程师"}
        change (command! id :changes :create nil base-body)
        rid (:id change)]
    ;; 提交后进入 in_review; 未设立委员会时汇总 state 为 none.
    (command! id :changes :submit rid {:reviewer_id 9302})
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= "in_review" (:status row)))
      (is (= "none" (get-in row [:ccb_summary :state]))))
    ;; 登记委员会: 成员 9302/9303, 通过门槛 2, 清空既有表决.
    (let [roster (command! id :changes :ccb rid {:members [9302 9303] :required 2})]
      (is (= [9302 9303] (:ccb_members roster)))
      (is (= 2 (:ccb_required roster)))
      (is (empty? (:ccb_ballots roster))))
    ;; 门槛未达: 零赞成票时批准被 409 门控拒绝, 汇总 state voting.
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= 2 (get-in row [:ccb_summary :members])))
      (is (= 0 (get-in row [:ccb_summary :approve])))
      (is (false? (get-in row [:ccb_summary :quorum_met])))
      (is (= "voting" (get-in row [:ccb_summary :state])))
      (is (= 409 (error-status #(command! 9302 id :changes :decision rid {:decision "approved" :reason "票数不足"})))))
    ;; 一名成员赞成: 仍差一票, 批准继续被拒.
    (command! 9302 id :changes :ballot rid {:vote "approve" :note "评估可行"})
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= 1 (get-in row [:ccb_summary :approve])))
      (is (= 409 (error-status #(command! 9302 id :changes :decision rid {:decision "approved" :reason "仍差一票"})))))
    ;; 第二名成员赞成: 达到门槛, 汇总 passed, 独立批准成功.
    (command! 9303 id :changes :ballot rid {:vote "approve" :note "同意"})
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= 2 (get-in row [:ccb_summary :approve])))
      (is (true? (get-in row [:ccb_summary :quorum_met])))
      (is (= "passed" (get-in row [:ccb_summary :state])))
      (is (= "approved" (:status (command! 9302 id :changes :decision rid {:decision "approved" :reason "达到门槛后独立通过"})))))
    ;; 驳回不受表决门控: 另一变更零票时可直接否决.
    (let [id2 id
          other (command! id2 :changes :create nil (assoc base-body :title "另一项变更"))
          oid (:id other)]
      (command! id2 :changes :submit oid {:reviewer_id 9302})
      (command! id2 :changes :ccb oid {:members [9302 9303] :required 2})
      (is (= "rejected" (:status (command! 9302 id2 :changes :decision oid {:decision "rejected" :reason "多数反对无需凑票"})))))))


(deftest ccb-roster-and-ballot-rules-are-enforced
  (let [id (project!)
        base-body {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                   :schedule_impact "增加五日" :cost_impact "重新估价" :quality_impact "增加测试"
                   :resource_impact "追加工程师"}
        change (command! id :changes :create nil base-body)
        rid (:id change)]
    ;; draft 状态不能表决 (要求 in_review) -> 409.
    (is (= 409 (error-status #(command! 9302 id :changes :ballot rid {:vote "approve"}))))
    ;; 提交后未设立委员会即表决 -> 409.
    (command! id :changes :submit rid {:reviewer_id 9302})
    (is (= 409 (error-status #(command! 9302 id :changes :ballot rid {:vote "approve" :note "无委员会"}))))
    ;; 名单校验: 空/重复/门槛越界/非法门槛/非法成员 均 400.
    (is (= 400 (error-status #(command! id :changes :ccb rid {:members [] :required 1}))))
    (is (= 400 (error-status #(command! id :changes :ccb rid {:members [9302 9302] :required 1}))))
    (is (= 400 (error-status #(command! id :changes :ccb rid {:members [9302 9303] :required 3}))))
    (is (= 400 (error-status #(command! id :changes :ccb rid {:members [9302 9303] :required 0}))))
    (is (= 400 (error-status #(command! id :changes :ccb rid {:members [999999] :required 1}))))
    ;; 合法登记: 单成员 9302, 门槛 1.
    (command! id :changes :ccb rid {:members [9302] :required 1})
    ;; 登记人(created_by=9301)不得表决 -> 403.
    (is (= 403 (error-status #(command! 9301 id :changes :ballot rid {:vote "approve" :note "本人"}))))
    ;; 非成员(9303)不得表决 -> 403.
    (is (= 403 (error-status #(command! 9303 id :changes :ballot rid {:vote "approve" :note "外人和票"}))))
    ;; 非法表决取值 -> 400.
    (is (= 400 (error-status #(command! 9302 id :changes :ballot rid {:vote "maybe" :note "非法"}))))
    ;; 成员重复投票覆盖上一票: 先赞成后反对, 赞成计数归零, 汇总 failed.
    (command! 9302 id :changes :ballot rid {:vote "approve" :note "初投赞成"})
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= 1 (get-in row [:ccb_summary :approve]))))
    (command! 9302 id :changes :ballot rid {:vote "reject" :note "改投反对"})
    (let [row (first (filter #(= rid (:id %)) (:changes (workspace id))))]
      (is (= 0 (get-in row [:ccb_summary :approve])))
      (is (= 1 (get-in row [:ccb_summary :reject])))
      (is (= "failed" (get-in row [:ccb_summary :state]))))
    ;; 零赞成票时批准仍被门控 -> 409.
    (is (= 409 (error-status #(command! 9302 id :changes :decision rid {:decision "approved" :reason "无赞成票"}))))
    ;; 重置名单清空既有表决.
    (let [reset (command! id :changes :ccb rid {:members [9302 9303] :required 1})]
      (is (empty? (:ccb_ballots reset)))
      (is (= 0 (get-in (first (filter #(= rid (:id %)) (:changes (workspace id)))) [:ccb_summary :approve]))))
    ;; 未设立委员会的普通变更不受表决门控: 可直接独立批准, 汇总仍为 none.
    (let [other (command! id :changes :create nil (assoc base-body :title "无需委员会的变更"))
          oid (:id other)]
      (approve! id :changes oid)
      (let [orow (first (filter #(= oid (:id %)) (:changes (workspace id))))]
        (is (= "approved" (:status orow)))
        (is (= "none" (get-in orow [:ccb_summary :state])))))))


(deftest change-closure-summary-is-derived-read-only
  (let [id (project!)
        sum (fn [] (:change_closure_summary (workspace id)))
        base-body {:title "更改设备范围" :reason "合同调整" :scope_impact "增加设备"
                   :schedule_impact "增加五日" :cost_impact "重新估价" :quality_impact "增加测试"
                   :resource_impact "追加工程师"}
        high-body (assoc base-body :schedule_impact_days 15 :cost_impact_amount "200000")
        c1 (command! id :changes :create nil base-body)             ; 停在 draft
        c2 (command! id :changes :create nil base-body)             ; 提交 -> in_review
        c3 (command! id :changes :create nil base-body)             ; approve! -> approved
        c4 (command! id :changes :create nil base-body)             ; 提交后驳回 -> rejected
        c5 (command! id :changes :create nil high-body)             ; 高影响: 提交升级->确认->批准 -> approved+acknowledged
        c6 (command! id :changes :create nil base-body)             ; 提交+委员会+一票赞成 -> voting
        c7 (command! id :changes :create nil base-body)             ; 提交+委员会门槛1+一票赞成 -> passed+quorum
        c8 (command! id :changes :create nil high-body)]            ; 高影响: 提交升级->确认豁免 -> in_review+waived
    ;; 只读聚合不改变工作流: 先建后逐步推进.
    (command! id :changes :submit (:id c2) {:reviewer_id 9302})
    (approve! id :changes (:id c3))
    (command! id :changes :submit (:id c4) {:reviewer_id 9302})
    (command! 9302 id :changes :decision (:id c4) {:decision "rejected" :reason "证据不足"})
    ;; c5: 高影响提交自动升级 pending, 独立确认(9303)责成处置后独立批准.
    (command! id :changes :submit (:id c5) {:reviewer_id 9302})
    (command! 9303 id :changes :escalation (:id c5) {:decision "approved" :note "变更控制确认责成处置"})
    (command! 9302 id :changes :decision (:id c5) {:decision "approved" :reason "确认后独立通过"})
    ;; c6: 提交后设门槛2委员会, 仅一票赞成 -> voting.
    (command! id :changes :submit (:id c6) {:reviewer_id 9302})
    (command! id :changes :ccb (:id c6) {:members [9302 9303] :required 2})
    (command! 9302 id :changes :ballot (:id c6) {:vote "approve" :note "初投赞成"})
    ;; c7: 提交后设门槛1委员会, 一票赞成 -> passed 且达门槛.
    (command! id :changes :submit (:id c7) {:reviewer_id 9302})
    (command! id :changes :ccb (:id c7) {:members [9302 9303] :required 1})
    (command! 9302 id :changes :ballot (:id c7) {:vote "approve" :note "同意"})
    ;; c8: 高影响提交自动升级 pending, 独立确认驳回 -> waived (状态仍 in_review).
    (command! id :changes :submit (:id c8) {:reviewer_id 9302})
    (command! 9303 id :changes :escalation (:id c8) {:decision "rejected" :note "评估后豁免"})
    (is (true? (:available (sum))))
    (is (= 8 (:total (sum))))
    (is (= 1 (:draft (sum))))          ; c1
    (is (= 4 (:in-review (sum))))      ; c2 c6 c7 c8
    (is (= 2 (:approved (sum))))       ; c3 c5
    (is (= 1 (:rejected (sum))))       ; c4
    ;; approval-pct = round(100*2/8) = 25.
    (is (= 25 (:approval-pct (sum))))
    ;; 高影响两条(c5 c8), 均触发升级: 一条确认责成(acknowledged) 一条豁免(waived), 无 pending.
    (is (= 2 (:high-impact (sum))))
    (is (= 2 (:escalated (sum))))
    (is (= 0 (:pending (sum))))
    (is (= 1 (:acknowledged (sum))))
    (is (= 1 (:waived (sum))))
    ;; CCB 表决态: 仅 c6 voting 与 c7 passed, 其余六条 none, 达门槛一条.
    (is (= 6 (:ccb-none (sum))))
    (is (= 1 (:ccb-voting (sum))))
    (is (= 1 (:ccb-passed (sum))))
    (is (= 0 (:ccb-failed (sum))))
    (is (= 1 (:ccb-quorum (sum))))
    ;; 只读派生不改变变更状态: 重复读取分布稳定, 既有变更状态与升级不漂移.
    (is (= (sum) (:change_closure_summary (workspace id))))
    (let [row (first (filter #(= (:id c5) (:id %)) (:changes (workspace id))))]
      (is (= "approved" (:status row)))
      (is (= true (:escalated row)))
      (is (= "acknowledged" (:escalation_state row))))
    ;; 修订去重: 给草稿 c1 出一版新修订, 同一 code 只计最新有效版本, 总数与 draft 计数不变.
    (command! id :changes :revisions (:id c1) (assoc base-body :title "更改设备范围(修订)"))
    (is (= 8 (:total (sum))))
    (is (= 1 (:draft (sum))))
    ;; 纯函数直测: 空输入 available false 且各计数 0.
    (let [empty (approval/change-closure-summary [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:approval-pct empty)))
      (is (= 0 (:draft empty)))
      (is (= 0 (:ccb-none empty))))
    ;; 纯函数直测: 同 code 修订只计最新有效版本(此例最新版为 approved, 旧版 draft 不计).
    (let [one (approval/change-closure-summary
                [{:code "R" :revision 1 :status "draft"} {:code "R" :revision 2 :status "approved"}])]
      (is (= 1 (:total one)))
      (is (= 0 (:draft one)))
      (is (= 1 (:approved one)))
      (is (= 100 (:approval-pct one))))
    ;; 纯函数直测: 表决未通过(反对票使门槛不可达) 归 failed, 与达门槛(quorum)互斥.
    (let [failed (approval/change-closure-summary
                   [{:status "in_review" :ccb_members [{:user_id 1} {:user_id 2}]
                     :ccb_required 2 :ccb_ballots [{:member_id 1 :vote "reject"}]}])]
      (is (= 1 (:ccb-failed failed)))
      (is (= 0 (:ccb-quorum failed))))
    ;; 非破坏性: 直测纯函数不写回输入记录.
    (let [rows [{:code "K" :revision 1 :status "draft"}]
          snapshot (approval/change-closure-summary rows)]
      (is (= {:code "K" :revision 1 :status "draft"} (first rows)))
      (is (= 1 (:total snapshot))))))


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


(deftest risk-review-frequency-auto-advances-next-review-date
  (let [id (project!) evidence (:id (document! id "RF-DOC"))
        today (java.time.LocalDate/now)
        monthly (str (.plusDays today 30))
        weekly (str (.plusDays today 7))
        row (fn [rid] (first (filterv #(= rid (:id %)) (:risks (workspace id)))))]
    ;; 登记时声明"每月"复审频率, 复评不手填下次复审日期时按 30 天从今天自动顺延, 批准后复审到期日与之对齐.
    (let [risk (command! id :risks :create nil {:title "供应稳定性风险" :probability 2 :impact 3
                                                :owner_id 9301 :mitigation "定期复评" :due_date (str (.plusDays today 5))
                                                :review_frequency "monthly"})]
      (is (= "monthly" (:review_frequency risk)))
      (is (= "monthly" (:review_frequency (row (:id risk)))))
      (command! id :risks :review (:id risk) {:outcome "active" :review_note "按声明节奏自动排期"
                                              :reviewer_id 9302 :evidence_ids [evidence]})
      (is (= "in_review" (:status (row (:id risk)))))
      (command! 9302 id :risks :decision (:id risk) {:decision "approved" :reason "节奏合理"})
      (let [r (row (:id risk))]
        (is (= monthly (:review_due_date r)))
        (is (= 30 (:review_due_in_days r)))))
    ;; 手填下次复审日期优先于声明节奏 (显式日期覆盖自动顺延).
    (let [risk (command! id :risks :create nil {:title "手动排期风险" :probability 2 :impact 3
                                                :owner_id 9301 :mitigation "手工指定" :due_date (str (.plusDays today 5))
                                                :review_frequency "monthly"})]
      (command! id :risks :review (:id risk) {:outcome "active" :review_note "手动指定下周"
                                              :reviewer_id 9302 :evidence_ids [evidence] :next_review_date weekly})
      (command! 9302 id :risks :decision (:id risk) {:decision "approved" :reason "已按手填日期"})
      (is (= weekly (:review_due_date (row (:id risk))))))
    ;; 关闭结论即便声明了频率也不写下次复审日期.
    (let [risk (command! id :risks :create nil {:title "即将关闭风险" :probability 2 :impact 3
                                                :owner_id 9301 :mitigation "关闭" :due_date (str (.plusDays today 5))
                                                :review_frequency "monthly"})]
      (command! id :risks :review (:id risk) {:outcome "closed" :review_note "风险已解除"
                                              :reviewer_id 9302 :evidence_ids [evidence]})
      (command! 9302 id :risks :decision (:id risk) {:decision "approved" :reason "确认解除"})
      (is (nil? (:review_due_date (row (:id risk))))))
    ;; 未声明频率且未填日期: 复评仍要求下次复审日期 (零回归 400).
    (let [plain (command! id :risks :create nil {:title "无节奏风险" :probability 2 :impact 3
                                                 :owner_id 9301 :mitigation "未设频率" :due_date (str (.plusDays today 5))})]
      (is (nil? (:review_frequency plain)))
      (is (= 400 (error-status #(command! id :risks :review (:id plain) {:outcome "active" :review_note "缺下次复审日期"
                                                                        :reviewer_id 9302 :evidence_ids [evidence]})))))
    ;; 非法复审频率登记被白名单校验拒绝.
    (is (= 400 (error-status #(command! id :risks :create nil {:title "非法频率风险" :probability 2 :impact 3
                                                               :owner_id 9301 :mitigation "x" :due_date (str (.plusDays today 5))
                                                               :review_frequency "yearly"}))))
    ;; 从风险库实例化不含复审频率仍正常 (库来源不写该键).
    (let [lib (command! id :risks :from-library nil {:template_key "cost-overrun" :owner_id 9301
                                                     :due_date (str (.plusDays today 5))})]
      (is (nil? (:review_frequency lib)))
      (is (= "cost-overrun" (:source_key lib))))))


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


(deftest risk-category-is-optional-enum-persisted
  (let [id (project!)
        ;; 合法风险类别(RBS)随登记持久化并在命令结果回显.
        typed (command! id :risks :create nil {:title "关键技术选型风险" :probability 2 :impact 3
                                               :owner_id 9301 :mitigation "提前原型验证" :due_date "2026-11-30"
                                               :risk_category "technical"})]
    (is (= "technical" (:risk_category typed)))
    ;; workspace 风险读模型逐条回显 risk_category (payload 字段自动透传).
    (is (= "technical" (:risk_category (first (filter #(= (:id typed) (:id %)) (:risks (workspace id)))))))
    ;; 未选风险类别则不写入该键 (零回归, 视为未设定), 风险仍正常创建.
    (let [plain (command! id :risks :create nil {:title "常规观察风险" :probability 2 :impact 3
                                                 :owner_id 9301 :mitigation "持续观察" :due_date "2026-11-30"})]
      (is (nil? (:risk_category plain)))
      (is (= "open" (:status plain))))
    ;; 非法风险类别 400.
    (is (= 400 (error-status #(command! id :risks :create nil {:title "非法类别风险" :probability 2 :impact 3
                                                               :owner_id 9301 :mitigation "x" :due_date "2026-11-30"
                                                               :risk_category "not-a-real-category"}))))
    ;; 从风险库实例化不含该可选键仍正常 (库来源记 source_category, 不写 risk_category).
    (let [lib (command! id :risks :from-library nil {:template_key "cost-overrun" :owner_id 9301 :due_date "2026-11-30"})]
      (is (nil? (:risk_category lib)))
      (is (= "cost-overrun" (:source_key lib))))))


(deftest meeting-action-priority-is-optional-enum-persisted
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "设计评审" :held_on "2026-09-22" :minutes "补齐验证任务" :attendee_ids [9301 9302]})
        mid (:id meeting)
        action (command! id :meetings :actions mid
                         {:title "补充验证" :owner_id 9301 :due_date "2026-09-25" :priority "high"})]
    ;; 合法优先级枚举回显并随 payload 持久化, 读模型原样返回.
    (is (= "high" (:priority action)))
    (is (= "high" (:priority (first (filter #(= (:id action) (:id %)) (:actions (workspace id)))))))
    ;; 未选优先级则不写入该键, 行动仍正常创建为 open.
    (let [plain (command! id :meetings :actions mid
                          {:title "常规跟进行动" :owner_id 9301 :due_date "2026-09-28"})]
      (is (nil? (:priority plain)))
      (is (= "open" (:status plain))))
    ;; 非法优先级枚举被白名单校验拒绝.
    (is (= 400 (error-status #(command! id :meetings :actions mid
                                        {:title "非法优先级" :owner_id 9301 :due_date "2026-09-25" :priority "urgent"}))))
    ;; 转真实任务后优先级随记录保留不漂移.
    (let [conv (command! id :meetings :actions mid
                         {:title "需转任务行动" :owner_id 9301 :due_date "2026-09-30" :priority "medium"})
          _ (command! id :actions :task (:id conv) {:start_date "2026-09-23" :duration_days 2})
          row (first (filter #(= (:id conv) (:id %)) (:actions (workspace id))))]
      (is (= "medium" (:priority row)))
      (is (= "converted" (:status row))))))


(deftest risk-response-strategy-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:risk_response_coverage (workspace id)))
        risk (fn [title strategy]
               (command! id :risks :create nil
                         (cond-> {:title title :probability 2 :impact 3
                                  :owner_id 9301 :mitigation "常规措施" :due_date "2026-10-20"}
                           strategy (assoc :response_strategy strategy))))
        s (fn [k] (:count (first (filter #(= k (:strategy %)) (:by-strategy (cov))))))]
    ;; 四类策略按每个风险最新有效版本统计声明覆盖度: 已声明计入分子, 未设定只计入分母.
    (risk "供应中断风险" "transfer")
    (risk "技术选型风险" "avoid")
    (risk "到货延迟风险" "transfer")
    (risk "常规观察风险" nil)
    (is (= 4 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 1 (:undeclared (cov))))
    (is (= 75 (:coverage-pct (cov))))
    (is (= 2 (s "transfer")))
    (is (= 1 (s "avoid")))
    (is (= 0 (s "mitigate")))
    (is (= 0 (s "accept")))
    ;; 再登记一条声明 mitigate 的风险: 覆盖度随已声明数上升, 四类顺序固定.
    (risk "质量整改风险" "mitigate")
    (is (= 5 (:total (cov))))
    (is (= 4 (:declared (cov))))
    (is (= 80 (:coverage-pct (cov))))
    (is (= 1 (s "mitigate")))
    ;; 从典型风险库实例化的风险同样计入分母 (库实例化默认不含策略, 记为未设定).
    (command! id :risks :from-library nil {:template_key "cost-overrun" :owner_id 9301 :due_date "2026-10-20"})
    (is (= 6 (:total (cov))))
    (is (= 4 (:declared (cov))))
    (is (= 2 (:undeclared (cov))))
    (is (= 67 (:coverage-pct (cov))))
    ;; 只读派生不改变风险状态: 重复读取覆盖度稳定, 既有风险仍为登记态且应对策略不漂移.
    (is (= (cov) (:risk_response_coverage (workspace id))))
    (let [row (first (filter #(= "供应中断风险" (:title %)) (:risks (workspace id))))]
      (is (= "open" (:status row)))
      (is (= "transfer" (:response_strategy row))))))


(deftest risk-category-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:risk_category_coverage (workspace id)))
        risk (fn [title category]
               (command! id :risks :create nil
                         (cond-> {:title title :probability 2 :impact 3
                                  :owner_id 9301 :mitigation "常规措施" :due_date "2026-10-20"}
                           category (assoc :risk_category category))))
        c (fn [k] (:count (first (filter #(= k (:category %)) (:by-category (cov))))))]
    ;; 六类 RBS 类别按每个风险最新有效版本统计声明覆盖度: 已声明计入分子, 未设定只计入分母.
    (risk "接口依赖风险" "external")
    (risk "技术选型风险" "technical")
    (risk "进度压缩风险" "schedule")
    (risk "常规观察风险" nil)
    (is (= 4 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 1 (:undeclared (cov))))
    (is (= 75 (:coverage-pct (cov))))
    (is (= 1 (c "external")))
    (is (= 1 (c "technical")))
    (is (= 1 (c "schedule")))
    (is (= 0 (c "organizational")))
    (is (= 0 (c "cost")))
    (is (= 0 (c "quality")))
    ;; 再登记一条声明 cost 的风险: 覆盖度随已声明数上升, 六类顺序固定.
    (risk "成本超支风险" "cost")
    (is (= 5 (:total (cov))))
    (is (= 4 (:declared (cov))))
    (is (= 80 (:coverage-pct (cov))))
    (is (= 1 (c "cost")))
    ;; 从典型风险库实例化的风险同样计入分母 (库实例化默认不含 risk_category, 记为未设定).
    (command! id :risks :from-library nil {:template_key "cost-overrun" :owner_id 9301 :due_date "2026-10-20"})
    (is (= 6 (:total (cov))))
    (is (= 4 (:declared (cov))))
    (is (= 2 (:undeclared (cov))))
    (is (= 67 (:coverage-pct (cov))))
    ;; 只读派生不改变风险状态: 重复读取覆盖度稳定, 既有风险仍为登记态且类别不漂移.
    (is (= (cov) (:risk_category_coverage (workspace id))))
    (let [row (first (filter #(= "接口依赖风险" (:title %)) (:risks (workspace id))))]
      (is (= "open" (:status row)))
      (is (= "external" (:risk_category row))))))


(deftest risk-escalation-disposition-summary-is-derived-read-only
  (let [id (project!)
        sum (fn [] (:risk_escalation_summary (workspace id)))
        lv (fn [x] (:count (first (filter #(= x (:level %)) (:by-level (sum))))))
        create (fn [title p i]
                 (command! id :risks :create nil
                           {:title title :probability p :impact i
                            :owner_id 9301 :mitigation "常规措施" :due_date "2026-10-20"}))
        steering (create "关键交付断供风险" 5 5)
        management (create "成本超支风险" 4 4)
        mild (create "人员波动风险" 3 5)]
    ;; 5x5=25 升 steering, 4x4=16 升 management, 3x5=15 未达阈值 16 不升级; 只读聚合不改变风险状态.
    (is (= 3 (:total (sum))))
    (is (= 2 (:escalated (sum))))
    (is (= 1 (:not-escalated (sum))))
    (is (= 2 (:pending (sum))))
    (is (= 0 (:acknowledged (sum))))
    (is (= 0 (:waived (sum))))
    (is (= 1 (lv "steering")))
    (is (= 1 (lv "management")))
    ;; 独立审批人批准责成处置 steering 升级: 待确认减一, 已确认加一, 升级总数与分级不变.
    (command! 9302 id :risks :escalate (:id steering)
              {:decision "approved" :note "管理层责成启动备选供应商并加严来料检验"})
    (is (= 1 (:pending (sum))))
    (is (= 1 (:acknowledged (sum))))
    (is (= 2 (:escalated (sum))))
    (is (= 1 (lv "steering")))
    ;; 独立审批人评估后豁免 management 升级: 待确认归零, 已豁免加一.
    (command! 9302 id :risks :escalate (:id management)
              {:decision "rejected" :note "影响可控, 评估后豁免专项处置"})
    (is (= 0 (:pending (sum))))
    (is (= 1 (:acknowledged (sum))))
    (is (= 1 (:waived (sum))))
    ;; 只读派生不改变风险状态: 重复读取汇总稳定, 升级分级不漂移, 未升级风险仍为登记态.
    (is (= (sum) (:risk_escalation_summary (workspace id))))
    (is (= 1 (lv "steering")))
    (is (= 1 (lv "management")))
    (let [row (first (filter #(= (:id mild) (:id %)) (:risks (workspace id))))]
      (is (false? (:escalated row)))
      (is (= "open" (:status row))))))


(deftest risk-score-distribution-is-derived-read-only
  (let [id (project!)
        dist (fn [] (:risk_score_distribution (workspace id)))
        b (fn [k] (:count (first (filter #(= k (:band %)) (:by-band (dist))))))
        create (fn [title p i]
                 (command! id :risks :create nil
                           {:title title :probability p :impact i
                            :owner_id 9301 :mitigation "常规措施" :due_date "2026-10-20"}))]
    ;; 四档边界: 低 1-5, 中 6-9, 高 10-15, 极高 >=16(达升级阈值). 每档各布两条并含边界值.
    (create "观察项A" 1 3)   ; 3 低
    (create "观察项B" 2 2)   ; 4 低
    (create "一般风险A" 2 3) ; 6 中
    (create "一般风险B" 3 3) ; 9 中(上界)
    (create "较大风险A" 2 5) ; 10 高(下界)
    (create "较大风险B" 3 5) ; 15 高(上界)
    (create "重大风险A" 4 4) ; 16 极高(=阈值, 下界)
    (create "重大风险B" 5 5) ; 25 极高
    (is (true? (:available (dist))))
    (is (= 8 (:total (dist))))
    (is (= 2 (b "low")))
    (is (= 2 (b "medium")))
    (is (= 2 (b "high")))
    (is (= 2 (b "critical")))
    (is (= 4 (:high-or-above (dist))))
    (is (= 2 (:critical (dist))))
    ;; 平均评分 = round((3+4+6+9+10+15+16+25)/8) = round(11.0) = 11.
    (is (= 11 (:avg-score (dist))))
    ;; 再登记一条中档风险: 分档计数随登记实时翻转, 平均评分被拉低.
    (create "一般风险C" 3 2) ; 6 中
    (is (= 9 (:total (dist))))
    (is (= 3 (b "medium")))
    (is (= 2 (b "low")))
    (is (= 4 (:high-or-above (dist))))
    (is (= 10 (:avg-score (dist)))) ; round(94/9)=10
    ;; 只读派生不改变风险状态: 重复读取分布稳定, 既有风险仍登记态且评分不漂移.
    (is (= (dist) (:risk_score_distribution (workspace id))))
    (let [row (first (filter #(= "重大风险B" (:title %)) (:risks (workspace id))))]
      (is (= 25 (:score row)))
      (is (= "open" (:status row))))
    ;; 纯函数直测: 空输入 available false/total 0/avg 0/四档全 0; 同 code 修订只计最新有效版本不重复计数.
    (let [empty (collab/risk-score-distribution [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:avg-score empty)))
      (is (= [0 0 0 0] (map :count (:by-band empty)))))
    (let [one (collab/risk-score-distribution
                [{:code "R" :revision 1 :score 4} {:code "R" :revision 2 :score 20}])
        bc (fn [k] (:count (first (filter #(= k (:band %)) (:by-band one)))))]
      (is (= 1 (:total one)))
      (is (= 0 (bc "low")))
      (is (= 1 (bc "critical")))
      (is (= 1 (:high-or-above one)))
      (is (= 20 (:avg-score one))))))


(deftest risk-review-cadence-summary-is-derived-read-only
  (let [id (project!)
        cad (fn [] (:risk_review_cadence (workspace id)))
        today (java.time.LocalDate/now)
        create (fn [title due]
                 (command! id :risks :create nil
                           {:title title :probability 2 :impact 3
                            :owner_id 9301 :mitigation "常规措施" :due_date due}))]
    ;; 真实工作台路径: 三条未关闭风险分别覆盖已逾期/临期(<=3天)/未来到期(登记风险必填到期日, 无未设定桶).
    (create "已逾期复审风险" (str (.minusDays today 5)))
    (create "临期复审风险" (str (.plusDays today 2)))
    (create "未来复审风险" (str (.plusDays today 30)))
    (is (true? (:available (cad))))
    (is (= 3 (:total (cad))))
    (is (= 3 (:open (cad))))
    (is (= 0 (:closed (cad))))
    (is (= 1 (:overdue (cad))))
    (is (= 1 (:due-soon (cad))))
    (is (= 1 (:upcoming (cad))))
    ;; 只读派生不改变风险状态: 重复读取汇总稳定, 逾期风险仍为登记态.
    (is (= (cad) (:risk_review_cadence (workspace id))))
    (let [row (first (filter #(= "已逾期复审风险" (:title %)) (:risks (workspace id))))]
      (is (true? (:review_overdue row)))
      (is (= "open" (:status row))))
    ;; 纯函数直测: 空输入 available false/全 0.
    (let [empty (collab/risk-review-cadence-summary [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:open empty)))
      (is (= 0 (:closed empty)))
      (is (= 0 (:overdue empty)))
      (is (= 0 (:due-soon empty)))
      (is (= 0 (:upcoming empty))))
    ;; 固定合成(已带派生键): 一条已关闭不计入 open, 四类 open 拆为逾期1/临期1/未来2.
    (let [fixed (collab/risk-review-cadence-summary
                  [{:code "F1" :revision 1 :status "open" :review_overdue true :review_due_soon false}
                   {:code "F2" :revision 1 :status "open" :review_overdue false :review_due_soon true}
                   {:code "F3" :revision 1 :status "open" :review_overdue false :review_due_soon false}
                   {:code "F4" :revision 1 :status "open" :review_overdue false :review_due_soon false}
                   {:code "F5" :revision 1 :status "closed" :review_overdue false :review_due_soon false}])]
      (is (= 5 (:total fixed)))
      (is (= 4 (:open fixed)))
      (is (= 1 (:closed fixed)))
      (is (= 1 (:overdue fixed)))
      (is (= 1 (:due-soon fixed)))
      (is (= 2 (:upcoming fixed))))
    ;; 同一 code 修订链只计最新有效版本: 旧版本已逾期但最新已关闭, 归为 closed 不计入 overdue.
    (let [rev (collab/risk-review-cadence-summary
                [{:code "R" :revision 1 :status "open" :review_overdue true :review_due_soon false}
                 {:code "R" :revision 2 :status "closed" :review_overdue false :review_due_soon false}])]
      (is (= 1 (:total rev)))
      (is (= 0 (:open rev)))
      (is (= 1 (:closed rev)))
      (is (= 0 (:overdue rev)))
      (is (= 0 (:due-soon rev)))
      (is (= 0 (:upcoming rev))))))


(deftest comm-cadence-summary-is-derived-read-only
  (let [id (project!)
        cad (fn [] (:comm_cadence_summary (workspace id)))
        fq (fn [k] (:count (first (filter #(= k (:frequency %)) (:by-frequency (cad))))))
        today (java.time.LocalDate/now)
        st (command! id :stakeholders :create nil
                     {:code "SH-CC" :name "客户代表" :role "验收" :category "customer"
                      :interest "high" :influence "high" :owner_id 9301})
        sid (:id st)
        create (fn [code freq next]
                 (command! id :comm-plans :create nil
                           {:code code :objective "进度同步" :channel "meeting" :frequency freq
                            :audience [sid] :next_date next :owner_id 9301}))]
    ;; 真实工作台路径: 三条沟通计划分别覆盖已逾期/临期(<=7天)/未来到期, 三种频率各一条.
    (create "CP-OV" "weekly" (str (.minusDays today 5)))
    (create "CP-SOON" "monthly" (str (.plusDays today 2)))
    (create "CP-FUT" "daily" (str (.plusDays today 30)))
    (is (true? (:available (cad))))
    (is (= 3 (:total (cad))))
    (is (= 1 (:overdue (cad))))
    (is (= 1 (:due-soon (cad))))
    (is (= 1 (:upcoming (cad))))
    (is (= 1 (fq "weekly")))
    (is (= 1 (fq "monthly")))
    (is (= 1 (fq "daily")))
    (is (= 0 (fq "quarterly")))
    ;; 只读派生不改变计划状态: 重复读取汇总稳定.
    (is (= (cad) (:comm_cadence_summary (workspace id))))
    ;; 纯函数直测: 空输入 available false/全 0, 五档频率计数均为 0.
    (let [empty (collab/comm-cadence-summary [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:overdue empty)))
      (is (= 0 (:due-soon empty)))
      (is (= 0 (:upcoming empty)))
      (is (= [0 0 0 0 0] (mapv :count (:by-frequency empty)))))
    ;; 固定合成(已带派生键): 逾期1 + 临期2 + 未来1; 频率分布 weekly2/daily1/monthly1.
    (let [fixed (collab/comm-cadence-summary
                  [{:code "S1" :revision 1 :frequency "weekly" :comm_overdue true :comm_days_until -3}
                   {:code "S2" :revision 1 :frequency "daily" :comm_overdue false :comm_days_until 1}
                   {:code "S3" :revision 1 :frequency "weekly" :comm_overdue false :comm_days_until 7}
                   {:code "S4" :revision 1 :frequency "monthly" :comm_overdue false :comm_days_until 20}])]
      (is (= 4 (:total fixed)))
      (is (= 1 (:overdue fixed)))
      (is (= 2 (:due-soon fixed)))
      (is (= 1 (:upcoming fixed)))
      (is (= 2 (:count (first (filter #(= "weekly" (:frequency %)) (:by-frequency fixed))))))
      (is (= 1 (:count (first (filter #(= "daily" (:frequency %)) (:by-frequency fixed))))))
      (is (= 1 (:count (first (filter #(= "monthly" (:frequency %)) (:by-frequency fixed)))))))
    ;; 边界: 剩余天数恰为窗口 7 计入临期, 8 计入未来到期.
    (let [edge (collab/comm-cadence-summary
                 [{:code "E7" :revision 1 :frequency "weekly" :comm_overdue false :comm_days_until 7}
                  {:code "E8" :revision 1 :frequency "weekly" :comm_overdue false :comm_days_until 8}])]
      (is (= 1 (:due-soon edge)))
      (is (= 1 (:upcoming edge))))
    ;; 同一 code 修订链只计最新有效版本: 旧版逾期 + 新版未来 -> latest 折叠 total 1/overdue 0/upcoming 1, 频率按最新.
    (let [rev (collab/comm-cadence-summary
                [{:code "R" :revision 1 :frequency "weekly" :comm_overdue true :comm_days_until -10}
                 {:code "R" :revision 2 :frequency "monthly" :comm_overdue false :comm_days_until 40}])]
      (is (= 1 (:total rev)))
      (is (= 0 (:overdue rev)))
      (is (= 0 (:due-soon rev)))
      (is (= 1 (:upcoming rev)))
      (is (= 1 (:count (first (filter #(= "monthly" (:frequency %)) (:by-frequency rev))))))
      (is (= 0 (:count (first (filter #(= "weekly" (:frequency %)) (:by-frequency rev)))))))))


(deftest comm-audience-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:comm_audience_coverage (workspace id)))
        sh (fn [code] (:id (command! id :stakeholders :create nil
                                     {:code code :name (str "干系人-" code) :role "评审" :category "internal"
                                      :interest "medium" :influence "medium" :owner_id 9301})))
        plan (fn [code audience]
               (command! id :comm-plans :create nil
                         {:code code :objective "进度同步" :channel "meeting" :frequency "weekly"
                          :audience audience :next_date "2026-10-15" :owner_id 9301}))
        unc-code? (fn [code] (some? (first (filter #(= code (:code %)) (:uncovered-stakeholders (cov))))))
        s1 (sh "AC-1") s2 (sh "AC-2") s3 (sh "AC-3") s4 (sh "AC-4") s5 (sh "AC-5")]
    ;; 真实工作台路径: 初始无沟通计划, 五个干系人均未被覆盖, 覆盖率 0.
    (is (true? (:available (cov))))
    (is (= 5 (:total (cov))))
    (is (= 0 (:plans (cov))))
    (is (= 0 (:covered (cov))))
    (is (= 5 (:uncovered (cov))))
    (is (= 0 (:coverage-pct (cov))))
    ;; 计划一覆盖 AC-1 + AC-2: 覆盖 2 / 未覆盖 3 / 40%, AC-1 已从缺件清单消失.
    (plan "AC-P1" [s1 s2])
    (is (= 1 (:plans (cov))))
    (is (= 2 (:covered (cov))))
    (is (= 3 (:uncovered (cov))))
    (is (= 40 (:coverage-pct (cov))))
    (is (false? (unc-code? "AC-1")))
    (is (true? (unc-code? "AC-3")))
    ;; 计划二覆盖 AC-3: 覆盖升到 3 / 未覆盖 AC-4 与 AC-5 / 60%.
    (let [p2 (plan "AC-P2" [s3])]
      (is (= 2 (:plans (cov))))
      (is (= 3 (:covered (cov))))
      (is (= 2 (:uncovered (cov))))
      (is (= 60 (:coverage-pct (cov))))
      (is (false? (unc-code? "AC-3")))
      (is (true? (unc-code? "AC-4")))
      ;; 修订计划二只覆盖 AC-4(去掉 AC-3): s/latest 只计最新有效版本受众, AC-3 重回缺件而 AC-4 转覆盖.
      (command! id :comm-plans :revisions (:id p2)
                {:code "AC-P2" :objective "进度同步" :channel "meeting" :frequency "weekly"
                 :audience [s4] :next_date "2026-10-15" :owner_id 9301})
      (is (= 2 (:plans (cov))))
      (is (= 3 (:covered (cov))))
      (is (= 2 (:uncovered (cov))))
      (is (true? (unc-code? "AC-3")))
      (is (false? (unc-code? "AC-4"))))
    ;; 受控作废未被任何计划覆盖的 AC-5: 该干系人从分母收缩, 覆盖率上升而覆盖数不变.
    (command! id :stakeholders :discard s5 {:reason "人员退出项目"})
    (is (= 4 (:total (cov))))
    (is (= 3 (:covered (cov))))
    (is (= 1 (:uncovered (cov))))
    (is (= 75 (:coverage-pct (cov))))
    ;; 只读派生不改变记录: 重复读取覆盖度稳定, 既有已覆盖干系人仍 active.
    (is (= (cov) (:comm_audience_coverage (workspace id))))
    (let [row (first (filter #(= "AC-1" (:code %)) (:stakeholders (workspace id))))]
      (is (= "active" (:status row))))
    ;; 纯函数直测: 空输入 available false/全 0, 缺件清单为空.
    (let [empty (stakeholders/comm-audience-coverage [] [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:plans empty)))
      (is (= 0 (:covered empty)))
      (is (= 0 (:uncovered empty)))
      (is (= 0 (:coverage-pct empty)))
      (is (empty? (:uncovered-stakeholders empty))))
    ;; 合成: 修订链只计最新版受众, 旧版覆盖的 b 随最新版去掉而重回缺件.
    (let [rev (stakeholders/comm-audience-coverage
                [{:id "a" :code "S-a" :revision 1 :status "active" :name "甲"}
                 {:id "b" :code "S-b" :revision 1 :status "active" :name "乙"}]
                [{:code "P" :revision 1 :status "active" :audience ["a" "b"]}
                 {:code "P" :revision 2 :status "active" :audience ["a"]}])]
      (is (= 2 (:total rev)))
      (is (= 1 (:plans rev)))
      (is (= 1 (:covered rev)))
      (is (= 1 (:uncovered rev)))
      (is (= 50 (:coverage-pct rev)))
      (is (= ["S-b"] (mapv :code (:uncovered-stakeholders rev)))))
    ;; 合成: 最新版被作废的干系人从分母剔除, 其受众引用不影响覆盖计数.
    (let [disc (stakeholders/comm-audience-coverage
                 [{:id "a" :code "S-a" :revision 1 :status "active" :name "甲"}
                  {:id "b" :code "S-b" :revision 1 :status "discarded" :name "乙"}]
                 [{:code "P" :revision 1 :status "active" :audience ["a" "b"]}])]
      (is (= 1 (:total disc)))
      (is (= 1 (:covered disc)))
      (is (= 0 (:uncovered disc)))
      (is (= 100 (:coverage-pct disc))))
    ;; 合成: 最新版被作废的沟通计划不计入, 其受众不再覆盖任何干系人.
    (let [discp (stakeholders/comm-audience-coverage
                  [{:id "a" :code "S-a" :revision 1 :status "active" :name "甲"}]
                  [{:code "P" :revision 1 :status "discarded" :audience ["a"]}])]
      (is (= 1 (:total discp)))
      (is (= 0 (:plans discp)))
      (is (= 0 (:covered discp)))
      (is (= 1 (:uncovered discp)))
      (is (= 0 (:coverage-pct discp))))))


(deftest comm-execution-coverage-is-derived-read-only
  (let [id (project!)
        exec (fn [] (:comm_execution_coverage (workspace id)))
        sh (fn [code] (:id (command! id :stakeholders :create nil
                                     {:code code :name (str "干系人-" code) :role "评审" :category "internal"
                                      :interest "medium" :influence "medium" :owner_id 9301})))
        plan (fn [code audience]
               (command! id :comm-plans :create nil
                         {:code code :objective (str "沟通-" code) :channel "meeting" :frequency "weekly"
                          :audience audience :next_date "2026-10-15" :owner_id 9301}))
        ne-code? (fn [code] (some? (first (filter #(= code (:code %)) (:not-executed-plans (exec))))))
        s1 (sh "CX-1") s2 (sh "CX-2") s3 (sh "CX-3")
        p1 (plan "CX-P1" [s1]) p2 (plan "CX-P2" [s2]) p3 (plan "CX-P3" [s3])]
    ;; 初始三条计划均未执行: total 3/executed 0/not-executed 3/logged 0/met 0/pct 0, 三条都在缺件清单.
    (is (true? (:available (exec))))
    (is (= 3 (:total (exec))))
    (is (= 0 (:executed (exec))))
    (is (= 3 (:not-executed (exec))))
    (is (= 0 (:logged (exec))))
    (is (= 0 (:met (exec))))
    (is (= 0 (:execution-pct (exec))))
    (is (true? (ne-code? "CX-P1")))
    (is (true? (ne-code? "CX-P3")))
    ;; 标记 CX-P2 一次实际沟通: executed 1/logged 1/met 0/pct 33, P2 从缺件清单消失.
    (command! id :comm-plans :log (:id p2) {:on (str (java.time.LocalDate/now)) :note "已开周会"})
    (is (= 1 (:executed (exec))))
    (is (= 1 (:logged (exec))))
    (is (= 0 (:met (exec))))
    (is (= 33 (:execution-pct (exec))))
    (is (false? (ne-code? "CX-P2")))
    ;; 由 CX-P3 生成会议: executed 2/logged 1/met 1/pct 67, 缺件清单仅剩 CX-P1.
    (command! id :comm-plans :meeting (:id p3) {:held_on "2026-09-20"})
    (is (= 2 (:executed (exec))))
    (is (= 1 (:logged (exec))))
    (is (= 1 (:met (exec))))
    (is (= 67 (:execution-pct (exec))))
    (is (true? (ne-code? "CX-P1")))
    (is (false? (ne-code? "CX-P3")))
    ;; 对已生成会议的 CX-P3 再标记一次沟通: 同时 logged+met 仍只算一条 executed (不重复计).
    (command! id :comm-plans :log (:id p3) {:on (str (java.time.LocalDate/now)) :note "补发纪要"})
    (is (= 2 (:executed (exec))))
    (is (= 2 (:logged (exec))))
    (is (= 1 (:met (exec))))
    (is (= 67 (:execution-pct (exec))))
    ;; 只读派生不改变记录: 重复读取稳定, 未执行的 CX-P1 仍 active.
    (is (= (exec) (:comm_execution_coverage (workspace id))))
    (let [row (first (filter #(= "CX-P1" (:code %)) (:comm_plans (workspace id))))]
      (is (= "active" (:status row))))
    ;; 纯函数直测: 空输入 available false/全 0/缺件清单空.
    (let [empty (stakeholders/comm-execution-coverage [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:executed empty)))
      (is (= 0 (:not-executed empty)))
      (is (= 0 (:logged empty)))
      (is (= 0 (:met empty)))
      (is (= 0 (:execution-pct empty)))
      (is (empty? (:not-executed-plans empty))))
    ;; 合成: logged 或 met 任一即 executed, 两者都有仍计一条.
    (let [mix (stakeholders/comm-execution-coverage
                [{:code "A" :revision 1 :status "active" :communication_log [{:on "2026-09-01"}] :last_meeting_id "m1"}
                 {:code "B" :revision 1 :status "active" :communication_log [{:on "2026-09-02"}]}
                 {:code "C" :revision 1 :status "active" :last_meeting_id "m2"}
                 {:code "D" :revision 1 :status "active" :objective "未执行"}])]
      (is (= 4 (:total mix)))
      (is (= 3 (:executed mix)))
      (is (= 1 (:not-executed mix)))
      (is (= 2 (:logged mix)))
      (is (= 2 (:met mix)))
      (is (= 75 (:execution-pct mix)))
      (is (= ["D"] (mapv :code (:not-executed-plans mix)))))
    ;; 合成: s/latest 只计最新修订版执行状态, 旧版有 log 而最新版无 -> 视为未执行.
    (let [rev (stakeholders/comm-execution-coverage
                [{:code "P" :revision 1 :status "active" :communication_log [{:on "2026-09-01"}]}
                 {:code "P" :revision 2 :status "active" :objective "新目标"}])]
      (is (= 1 (:total rev)))
      (is (= 0 (:executed rev)))
      (is (= 1 (:not-executed rev)))
      (is (= 0 (:logged rev)))
      (is (= ["P"] (mapv :code (:not-executed-plans rev)))))
    ;; 合成: 最新版被作废的沟通计划不计入.
    (let [disc (stakeholders/comm-execution-coverage
                 [{:code "P" :revision 1 :status "discarded" :communication_log [{:on "2026-09-01"}]}
                  {:code "Q" :revision 1 :status "active"}])]
      (is (= 1 (:total disc)))
      (is (= 0 (:executed disc)))
      (is (= 1 (:not-executed disc)))
      (is (= 0 (:logged disc)))
      (is (= ["Q"] (mapv :code (:not-executed-plans disc)))))))


(deftest raci-assignment-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:raci_assignment_coverage (workspace id)))
        sh (fn [code name] (:id (command! id :stakeholders :create nil
                                          {:code code :name name :role "评审" :category "internal"
                                           :interest "high" :influence "medium" :owner_id 9301})))
        raci (fn [act sid resp] (command! id :raci :create nil
                                          {:activity act :stakeholder_id sid :responsibility resp}))
        s1 (sh "RA-1" "甲") s2 (sh "RA-2" "乙") s3 (sh "RA-3" "丙")]
    ;; 完整甲: 同时有 R 和 A -> 完整.
    (raci "完整甲" s1 "R")
    (raci "完整甲" s2 "A")
    ;; 缺执行: 只有 A 无 R -> 缺执行.
    (raci "缺执行" s2 "A")
    ;; 缺负责: 只有 R 无 A -> 缺负责.
    (raci "缺负责" s1 "R")
    ;; 双缺: 只有 C -> 缺负责且缺执行.
    (raci "双缺" s3 "C")
    (is (true? (:available (cov))))
    (is (= 4 (:total (cov))))
    (is (= 1 (:complete (cov))))
    (is (= 2 (:missing-accountable (cov))))
    (is (= 2 (:missing-responsible (cov))))
    (is (= 25 (:coverage-pct (cov))))
    (let [inc (zipmap (mapv :activity (:incomplete-activities (cov)))
                      (mapv #(vector (:missing-accountable %) (:missing-responsible %))
                            (:incomplete-activities (cov))))]
      (is (= 3 (count (:incomplete-activities (cov)))))
      (is (false? (contains? inc "完整甲")))
      (is (= [false true] (get inc "缺执行")))
      (is (= [true false] (get inc "缺负责")))
      (is (= [true true] (get inc "双缺"))))
    ;; 补上"缺负责"的 A 后该活动转完整: complete 2/覆盖率 50/缺负责降到 1.
    (raci "缺负责" s3 "A")
    (is (= 4 (:total (cov))))
    (is (= 2 (:complete (cov))))
    (is (= 1 (:missing-accountable (cov))))
    (is (= 50 (:coverage-pct (cov))))
    ;; 只读派生不改变记录: 重复读取稳定, RACI 行仍 assigned.
    (is (= (cov) (:raci_assignment_coverage (workspace id))))
    (is (every? #(= "assigned" (:status %)) (:raci (workspace id))))
    ;; 纯函数直测: 空输入 available false/全 0/覆盖率 0/缺件清单空.
    (let [empty (stakeholders/raci-assignment-completeness [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:complete empty)))
      (is (= 0 (:missing-accountable empty)))
      (is (= 0 (:missing-responsible empty)))
      (is (= 0 (:coverage-pct empty)))
      (is (empty? (:incomplete-activities empty))))
    ;; 合成: 同活动多条 R/A/C 只按是否含 A 与含 R 判定, 三活动两完整 -> 覆盖率 67.
    (let [mix (stakeholders/raci-assignment-completeness
                [{:activity "X" :responsibility "R"} {:activity "X" :responsibility "A"} {:activity "X" :responsibility "C"}
                 {:activity "Y" :responsibility "R"} {:activity "Y" :responsibility "A"}
                 {:activity "Z" :responsibility "I"}])]
      (is (= 3 (:total mix)))
      (is (= 2 (:complete mix)))
      (is (= 1 (:missing-accountable mix)))
      (is (= 1 (:missing-responsible mix)))
      (is (= 67 (:coverage-pct mix)))
      (is (= ["Z"] (mapv :activity (:incomplete-activities mix)))))))


(deftest raci-engagement-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:raci_engagement_coverage (workspace id)))
        sh (fn [code name] (:id (command! id :stakeholders :create nil
                                          {:code code :name name :role "评审" :category "internal"
                                           :interest "high" :influence "medium" :owner_id 9301})))
        raci (fn [act sid resp] (command! id :raci :create nil
                                          {:activity act :stakeholder_id sid :responsibility resp}))
        s1 (sh "RE-1" "甲") s2 (sh "RE-2" "乙") s3 (sh "RE-3" "丙") s4 (sh "RE-4" "丁")]
    ;; 充分活动: 有 R 与 A 之外还配了 C 与 I -> 咨询与知会齐备.
    (raci "充分活动" s1 "R")
    (raci "充分活动" s2 "A")
    (raci "充分活动" s3 "C")
    (raci "充分活动" s4 "I")
    ;; 只缺知会: 配了 C 无 I -> with-consult 但缺知会.
    (raci "只缺知会" s1 "C")
    ;; 只缺咨询: 配了 I 无 C -> with-inform 但缺咨询.
    (raci "只缺咨询" s2 "I")
    ;; 双缺: 只有 R 与 A -> 既无咨询也无知会.
    (raci "双缺" s1 "R")
    (raci "双缺" s2 "A")
    (is (true? (:available (cov))))
    (is (= 4 (:total (cov))))
    (is (= 2 (:with-consult (cov))) "充分活动 + 只缺知会含 C")
    (is (= 2 (:with-inform (cov))) "充分活动 + 只缺咨询含 I")
    (is (= 1 (:fully-engaged (cov))))
    (is (= 25 (:engagement-pct (cov))))
    (let [thin (zipmap (mapv :activity (:thin-activities (cov)))
                       (mapv #(vector (:missing-consult %) (:missing-inform %))
                             (:thin-activities (cov))))]
      (is (= 3 (count (:thin-activities (cov)))))
      (is (false? (contains? thin "充分活动")) "齐备活动不进单薄清单")
      (is (= [false true] (get thin "只缺知会")))
      (is (= [true false] (get thin "只缺咨询")))
      (is (= [true true] (get thin "双缺"))))
    ;; 给"只缺知会"补一条 I -> 充分升到 2, 覆盖率 50, 单薄清单去掉该活动.
    (raci "只缺知会" s4 "I")
    (is (= 4 (:total (cov))))
    (is (= 2 (:fully-engaged (cov))))
    (is (= 3 (:with-inform (cov))))
    (is (= 50 (:engagement-pct (cov))))
    (is (= ["双缺" "只缺咨询"] (mapv :activity (:thin-activities (cov)))))
    ;; 只读派生不改记录: 重复读取稳定, RACI 行仍 assigned.
    (is (= (cov) (:raci_engagement_coverage (workspace id))))
    (is (every? #(= "assigned" (:status %)) (:raci (workspace id))))
    ;; 纯函数直测: 空输入 available false/全 0/覆盖 0/单薄清单空.
    (let [empty (stakeholders/raci-engagement-coverage [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:with-consult empty)))
      (is (= 0 (:with-inform empty)))
      (is (= 0 (:fully-engaged empty)))
      (is (= 0 (:engagement-pct empty)))
      (is (empty? (:thin-activities empty))))
    ;; 合成: 两活动一齐备(C+I)一只有C -> 覆盖 50, 单薄只剩后者缺知会.
    (let [mix (stakeholders/raci-engagement-coverage
                [{:activity "X" :responsibility "C"} {:activity "X" :responsibility "I"}
                 {:activity "Y" :responsibility "C"} {:activity "Y" :responsibility "R"}])]
      (is (= 2 (:total mix)))
      (is (= 2 (:with-consult mix)))
      (is (= 1 (:with-inform mix)))
      (is (= 1 (:fully-engaged mix)))
      (is (= 50 (:engagement-pct mix)))
      (is (= ["Y"] (mapv :activity (:thin-activities mix)))))))


(deftest issue-escalation-disposition-summary-is-derived-read-only
  (let [id (project!)
        sum (fn [] (:issue_escalation_summary (workspace id)))
        lv (fn [x] (:count (first (filter #(= x (:level %)) (:by-level (sum))))))
        create (fn [title sev due]
                 (command! id :issues :create nil
                           {:title title :severity sev :owner_id 9301 :due_date due}))
        steering (create "阻断级逾期缺陷" "blocker" "2020-01-10")
        management (create "阻断级在办缺陷" "blocker" "2099-12-31")
        mild (create "一般缺陷" "major" "2099-12-31")]
    ;; 阻断级逾期升 steering, 阻断级未逾期升 management, 非阻断不升级; 只读聚合不改变问题状态.
    (is (= 3 (:total (sum))))
    (is (= 2 (:escalated (sum))))
    (is (= 1 (:not-escalated (sum))))
    (is (= 2 (:pending (sum))))
    (is (= 0 (:acknowledged (sum))))
    (is (= 0 (:waived (sum))))
    (is (= 1 (lv "steering")))
    (is (= 1 (lv "management")))
    ;; 独立审批人批准责成处置 steering 升级: 待确认减一, 已确认加一, 升级总数与分级不变.
    (command! 9302 id :issues :escalate (:id steering)
              {:decision "approved" :note "管理层责成停线整改并复测"})
    (is (= 1 (:pending (sum))))
    (is (= 1 (:acknowledged (sum))))
    (is (= 2 (:escalated (sum))))
    (is (= 1 (lv "steering")))
    ;; 独立审批人评估后豁免 management 升级: 待确认归零, 已豁免加一.
    (command! 9302 id :issues :escalate (:id management)
              {:decision "rejected" :note "影响可控, 评估后豁免专项处置"})
    (is (= 0 (:pending (sum))))
    (is (= 1 (:acknowledged (sum))))
    (is (= 1 (:waived (sum))))
    ;; 只读派生不改变问题状态: 重复读取汇总稳定, 升级分级不漂移, 未升级问题仍为登记态.
    (is (= (sum) (:issue_escalation_summary (workspace id))))
    (is (= 1 (lv "steering")))
    (is (= 1 (lv "management")))
    (let [row (first (filter #(= (:id mild) (:id %)) (:issues (workspace id))))]
      (is (nil? (:escalated row)))
      (is (= "open" (:status row))))))


(deftest meeting-release-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:meeting_release_coverage (workspace id)))
        mk (fn [title] (command! id :meetings :create nil
                                 {:title title :held_on "2026-09-12" :minutes "形成结论" :attendee_ids [9301 9302]}))
        draft (mk "阶段评审会")
        pending (mk "整改例会")
        published (mk "设计评审会")
        voided (mk "误登记的重复会议")]
    ;; draft 留 recorded; pending 提交进 in_review; published 提交并批准进 approved; voided 作废进 discarded.
    (command! id :meetings :submit (:id pending) {:reviewer_id 9302})
    (command! id :meetings :submit (:id published) {:reviewer_id 9302})
    (command! 9302 id :meetings :decision (:id published) {:decision "approved" :reason "纪要完整可归档"})
    (command! id :meetings :discard (:id voided) {:reason "误登记的重复会议"})
    ;; 发布率分母排除已作废: approved 1 / (4-1)=33%; 四态各一.
    (is (= 4 (:total (cov))))
    (is (= 1 (:recorded (cov))))
    (is (= 1 (:in-review (cov))))
    (is (= 1 (:approved (cov))))
    (is (= 1 (:discarded (cov))))
    (is (= 33 (:release-pct (cov))))
    ;; 独立审批人批准 pending -> approved 加一, in_review 归零, 发布率升到 2/3=67%.
    (command! 9302 id :meetings :decision (:id pending) {:decision "approved" :reason "补充后通过"})
    (is (= 0 (:in-review (cov))))
    (is (= 2 (:approved (cov))))
    (is (= 67 (:release-pct (cov))))
    ;; 作废 draft -> discarded 加一, recorded 归零, 分母降到 2, 发布率升到 100%.
    (command! id :meetings :discard (:id draft) {:reason "确认无需保留"})
    (is (= 0 (:recorded (cov))))
    (is (= 2 (:discarded (cov))))
    (is (= 2 (:approved (cov))))
    (is (= 100 (:release-pct (cov))))
    ;; 只读派生不改变会议状态: 重复读取汇总稳定, 已发布会议仍为 approved 不漂移.
    (is (= (cov) (:meeting_release_coverage (workspace id))))
    (let [row (first (filter #(= (:id published) (:id %)) (:meetings (workspace id))))]
      (is (= "approved" (:status row))))))


(deftest risk-mitigation-materializes-tracked-prevention-action
  (let [id (project!)
        risk (command! id :risks :create nil
                       {:title "关键物料断供风险" :probability 2 :impact 3 :owner_id 9301
                        :mitigation "启用备选供应商并加严来料检验" :due_date "2026-10-20"})
        risks (fn [] (:risks (workspace id)))
        action (fn [aid] (first (filter #(= aid (:id %)) (:actions (workspace id)))))]
    ;; 默认沿用风险责任人与到期日, 记录来源风险 id, 新建为开放行动项, 且风险本身不被改变.
    (let [a (command! id :risks :mitigation-action (:id risk) {:title "锁定备选供应商名单"})]
      (is (= "open" (:status a)))
      (is (= "锁定备选供应商名单" (:title a)))
      (is (= 9301 (:owner_id a)))
      (is (= "2026-10-20" (:due_date a)))
      (is (= (:id risk) (:source_risk_id a)))
      (is (= "open" (:status (first (filter #(= (:id risk) (:id %)) (risks)))))))
    ;; 省略标题时回退为以风险标题派生的预防措施行动, 仍默认沿用责任人与到期日.
    (let [fb (command! id :risks :mitigation-action (:id risk) {})]
      (is (some? (:id fb)))
      (is (true? (.startsWith (:title fb) "落实预防措施: ")))
      (is (true? (.contains (:title fb) "关键物料断供风险")))
      (is (= 9301 (:owner_id fb)))
      (is (= "2026-10-20" (:due_date fb))))
    ;; 允许显式改写责任人与到期日覆盖风险默认值.
    (let [ov (command! id :risks :mitigation-action (:id risk) {:title "专属跟进" :owner_id 9303 :due_date "2026-12-01"})]
      (is (= 9303 (:owner_id ov)))
      (is (= "2026-12-01" (:due_date ov))))
    ;; 只读标注把来源风险标题回显到行动台账, 会议行动无来源风险则为空.
    (let [r (action (:id (command! id :risks :mitigation-action (:id risk) {:title "带来源标注"})))]
      (is (= (:id risk) (:action_source_risk_id r)))
      (is (= "关键物料断供风险" (:action_source_risk_title r))))
    (let [meeting (command! id :meetings :create nil
                            {:title "评审会" :held_on "2026-09-22" :minutes "形成会议行动" :attendee_ids [9301 9302]})
          ma (command! id :meetings :actions (:id meeting) {:title "会议行动" :owner_id 9301 :due_date "2026-09-30"})]
      (is (nil? (:action_source_risk_title (action (:id ma))))))
    ;; 复用既有行动生命周期: 预防行动可转真实WBS任务并置为 converted.
    (let [a (command! id :risks :mitigation-action (:id risk) {:title "转任务预防项"})
          t (command! id :actions :task (:id a) {:start_date "2026-09-23" :duration_days 2})]
      (is (some? (:target_task_id t)))
      (is (= "converted" (:status (action (:id a))))))
    ;; 门控: 仅对进行中或已缓解风险开放, 已转问题(materialized)后不得再落实; 跨项目风险 404.
    (command! id :risks :materialize (:id risk) {})
    (is (= 409 (error-status #(command! id :risks :mitigation-action (:id risk) {:title "越门控"}))))
    (let [other (project!)
          other-risk (command! other :risks :create nil
                                {:title "他项目风险" :probability 2 :impact 3 :owner_id 9301
                                 :mitigation "其它" :due_date "2026-10-20"})]
      (is (= 404 (error-status #(command! id :risks :mitigation-action (:id other-risk) {:title "跨项目"})))))))


(deftest risk-mitigation-action-rollup-is-derived-read-only
  (let [id (project!)
        risk (command! id :risks :create nil
                       {:title "关键物料断供风险" :probability 2 :impact 3 :owner_id 9301
                        :mitigation "启用备选供应商并加严来料检验" :due_date "2026-10-20"})
        other (command! id :risks :create nil
                        {:title "进度延误风险" :probability 2 :impact 3 :owner_id 9301
                         :mitigation "预留进度缓冲" :due_date "2026-10-20"})
        risk-row (fn [] (first (filter (fn [x] (= (:id risk) (:id x))) (:risks (workspace id)))))
        other-row (fn [] (first (filter (fn [x] (= (:id other) (:id x))) (:risks (workspace id)))))]
    ;; 登记后仅有应对措施尚无落实行动: 该风险 unimplemented, 计数 0/0.
    (is (= "unimplemented" (:mitigation_action_state (risk-row))))
    (is (= 0 (:mitigation_action_total (risk-row))))
    (is (= 0 (:mitigation_action_open (risk-row))))
    ;; 落实一条预防行动: in-progress, total/open 各 1; 另一风险不受影响.
    (let [a1 (command! id :risks :mitigation-action (:id risk) {:title "锁定备选供应商名单"})]
      (is (= 1 (:mitigation_action_total (risk-row))))
      (is (= 1 (:mitigation_action_open (risk-row))))
      (is (= "in-progress" (:mitigation_action_state (risk-row))))
      (is (= "unimplemented" (:mitigation_action_state (other-row))))
      ;; 再落实一条: total 2 open 2.
      (let [a2 (command! id :risks :mitigation-action (:id risk) {:title "加严来料检验"})
            ev (:id (document! id "MR-DOC-A"))]
        (is (= 2 (:mitigation_action_open (risk-row))))
        ;; 独立核验关闭第一条: closed 计完成, open 减到 1, 仍 in-progress.
        (command! id :actions :complete (:id a1) {:result "已完成并附记录" :evidence_ids [ev] :reviewer_id 9302})
        (command! 9302 id :actions :verify (:id a1) {:decision "approved" :reason "独立核验通过"})
        (is (= 2 (:mitigation_action_total (risk-row))))
        (is (= 1 (:mitigation_action_open (risk-row))))
        (is (= "in-progress" (:mitigation_action_state (risk-row))))
        ;; 第二条转真实任务置为 converted 亦计完成: open 0 -> completed.
        (command! id :actions :task (:id a2) {:start_date "2026-09-23" :duration_days 2})
        (is (= 0 (:mitigation_action_open (risk-row))))
        (is (= "completed" (:mitigation_action_state (risk-row))))))
    ;; 只读派生不回写风险状态, 重复读取稳定.
    (is (= "open" (:status (risk-row))))
    (is (= (:mitigation_action_state (risk-row)) (:mitigation_action_state (risk-row))))
    ;; 纯函数按 source_risk_id 聚合: closed/converted 视为完成, 无来源风险的行动不计入.
    (is (= {"r1" {:total 4 :open 2 :overdue 0}}
           (collab/mitigation-rollup-by-risk
            [{:source_risk_id "r1" :status "open"}
             {:source_risk_id "r1" :status "closed"}
             {:source_risk_id "r1" :status "converted"}
             {:source_risk_id "r1" :status "in_review"}
             {:status "open"}])))
    ;; mitigation-read-model 纯映射: 由 rollup 计数直接得出三态.
    (is (= "in-progress" (:mitigation_action_state (collab/mitigation-read-model {"r1" {:total 2 :open 1}} {:id "r1"}))))
    (is (= "completed" (:mitigation_action_state (collab/mitigation-read-model {"r1" {:total 2 :open 0}} {:id "r1"}))))
    (is (= "unimplemented" (:mitigation_action_state (collab/mitigation-read-model {} {:id "r2"}))))))


(deftest risk-mitigation-action-overdue-is-derived-read-only
  (let [id (project!)
        risk (command! id :risks :create nil
                       {:title "设备到货延迟风险" :probability 2 :impact 3 :owner_id 9301
                        :mitigation "提前锁定交期并分批到货" :due_date "2099-10-20"})
        risk-row (fn [] (first (filter (fn [x] (= (:id risk) (:id x))) (:risks (workspace id)))))]
    ;; 登记后无落实行动: overdue 0.
    (is (= 0 (:mitigation_action_overdue (risk-row))))
    ;; 落实一条到期日已过且未完成的预防行动: total 1 open 1 overdue 1.
    (let [a-over (command! id :risks :mitigation-action (:id risk) {:title "催办紧急到货" :due_date "2020-01-01"})]
      (is (= 1 (:mitigation_action_total (risk-row))))
      (is (= 1 (:mitigation_action_open (risk-row))))
      (is (= 1 (:mitigation_action_overdue (risk-row))))
      (is (= "in-progress" (:mitigation_action_state (risk-row))))
      ;; 再落实一条未到期行动: total 2 open 2, overdue 仍 1.
      (let [a-future (command! id :risks :mitigation-action (:id risk) {:title "分批到货排期" :due_date "2099-01-01"})]
        (is (= 2 (:mitigation_action_total (risk-row))))
        (is (= 2 (:mitigation_action_open (risk-row))))
        (is (= 1 (:mitigation_action_overdue (risk-row))))
        ;; 关闭那条逾期的(独立核验): closed 既不计 open 也不计 overdue, open 减到 1, overdue 归 0, 仍 in-progress.
        (let [ev (:id (document! id "MRO-DOC-A"))]
          (command! id :actions :complete (:id a-over) {:result "已催办并到货" :evidence_ids [ev] :reviewer_id 9302})
          (command! 9302 id :actions :verify (:id a-over) {:decision "approved" :reason "独立核验通过"})
          (is (= 2 (:mitigation_action_total (risk-row))))
          (is (= 1 (:mitigation_action_open (risk-row))))
          (is (= 0 (:mitigation_action_overdue (risk-row))))
          (is (= "in-progress" (:mitigation_action_state (risk-row))))
          ;; 把最后一条转真实任务(converted): open 0 overdue 0 -> completed.
          (command! id :actions :task (:id a-future) {:start_date "2026-09-23" :duration_days 2})
          (is (= 0 (:mitigation_action_open (risk-row))))
          (is (= 0 (:mitigation_action_overdue (risk-row))))
          (is (= "completed" (:mitigation_action_state (risk-row)))))))
    ;; 只读派生不回写风险状态.
    (is (= "open" (:status (risk-row))))
    ;; 纯函数聚合: overdue 仅计到期日不晚于运行日且未关闭未转任务的行动, 且必为 open 子集.
    (is (= {"r1" {:total 5 :open 3 :overdue 2}}
           (collab/mitigation-rollup-by-risk
            [{:source_risk_id "r1" :status "open" :due_date "2020-01-01"}
             {:source_risk_id "r1" :status "in_review" :due_date "2020-01-01"}
             {:source_risk_id "r1" :status "open" :due_date "2099-01-01"}
             {:source_risk_id "r1" :status "closed" :due_date "2020-01-01"}
             {:source_risk_id "r1" :status "converted" :due_date "2020-01-01"}])))
    ;; read-model 无命中时 overdue 缺省 0; overdue 永不超过 open.
    (is (= 0 (:mitigation_action_overdue (collab/mitigation-read-model {} {:id "r2"}))))
    (let [rm (collab/mitigation-read-model {"r1" {:total 3 :open 2 :overdue 2}} {:id "r1"})]
      (is (= 2 (:mitigation_action_overdue rm)))
      (is (= 2 (:mitigation_action_open rm)))
      (is (<= (:mitigation_action_overdue rm) (:mitigation_action_open rm))))))


(deftest meeting-action-closure-summary-is-derived-read-only
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "行动闭环评审" :held_on "2026-09-22" :minutes "统一行动闭环" :attendee_ids [9301 9302]})
        mid (:id meeting)
        mk (fn [due] (command! id :meetings :actions mid {:title "行动项" :owner_id 9301 :due_date due}))
        ev (:id (document! id "AC-DOC"))
        a-future (mk "2099-01-01")
        a-past-open (mk "2020-01-01")
        a-closed (mk "2020-01-01")
        a-converted (mk "2020-01-01")
        a-rejected (mk "2020-01-01")
        closure (fn [] (:action_closure (workspace id)))]
    ;; 独立核验通过后关闭.
    (command! id :actions :complete (:id a-closed) {:result "已完成并附记录" :evidence_ids [ev] :reviewer_id 9302})
    (command! 9302 id :actions :verify (:id a-closed) {:decision "approved" :reason "独立核验通过"})
    ;; 转为真实WBS任务(converted).
    (command! id :actions :task (:id a-converted) {:start_date "2026-09-23" :duration_days 2})
    ;; 核验驳回(rejected): 仍属未完成, 逾期则计入逾期.
    (command! id :actions :complete (:id a-rejected) {:result "补交证据" :evidence_ids [ev] :reviewer_id 9302})
    (command! 9302 id :actions :verify (:id a-rejected) {:decision "rejected" :reason "证据不足"})
    (let [c (closure)]
      (is (= 5 (:total c)))
      (is (= 2 (:closed c)))
      (is (= 3 (:open c)))
      (is (= 1 (:converted c)))
      (is (= 2 (:overdue c)))
      (is (= 40 (:closure-pct c)))
      (is (<= (:overdue c) (:open c))))
    ;; 只读派生不回写行动状态.
    (is (= "open" (:status (first (filter #(= (:id a-past-open) (:id %)) (:actions (workspace id)))))))
    ;; 纯函数直测: 相同状态与到期日组合得到一致聚合; 空集各计数为0且闭环率为0.
    (is (= {:total 5 :closed 2 :open 3 :converted 1 :overdue 2 :closure-pct 40}
           (collab/action-closure-summary
            [{:status "open" :due_date "2099-01-01"}
             {:status "open" :due_date "2020-01-01"}
             {:status "closed" :due_date "2020-01-01"}
             {:status "converted" :due_date "2020-01-01"}
             {:status "rejected" :due_date "2020-01-01"}])))
    (is (= {:total 0 :closed 0 :open 0 :converted 0 :overdue 0 :closure-pct 0}
           (collab/action-closure-summary [])))))


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
    ;; 标记一次实际沟通(以运行日为沟通日), 按周频顺延下次日期并留痕; 使用相对今天的日期以免受运行日漂移影响.
    (let [today (java.time.LocalDate/now)
          on-str (str today)
          next-str (str (.plusDays today 7))
          logged (command! id :comm-plans :log pid {:on on-str :note "已召开周会同步进展"})]
      (is (= next-str (:next_date logged)))
      (is (= on-str (:last_communicated_on logged)))
      (is (= "已召开周会同步进展" (:last_communication_note logged)))
      (is (= 1 (count (:communication_log logged)))))
    ;; 顺延后不再到期, 剩余天数为正.
    (let [after (first (filter #(= pid (:id %)) (:comm_plans (workspace id))))]
      (is (false? (:comm_overdue after)))
      (is (pos? (:comm_days_until after))))
    ;; 非法日期与不存在计划分别被拒.
    (is (= 400 (error-status #(command! id :comm-plans :log pid {:on "2026-13-99"}))))
    (is (= 404 (error-status #(command! id :comm-plans :log "no-such-plan" {:on "2026-09-22"}))))))


(deftest comm-plan-log-channel-is-optional-and-falls-back-to-plan
  (let [id (project!)
        st (command! id :stakeholders :create nil
                     {:code "SH-1" :name "客户代表" :role "验收" :category "customer"
                      :interest "high" :influence "high" :owner_id 9301})
        plan (command! id :comm-plans :create nil
                       {:code "CP-1" :objective "周度进展同步" :channel "meeting" :frequency "weekly"
                        :audience [(:id st)] :next_date "2026-01-05" :owner_id 9301})
        pid (:id plan)
        today (java.time.LocalDate/now)
        on-str (str today)]
    ;; 标注本次实际渠道 email -> 末条留痕与 last_communication_channel 均回显 email, 顺延逻辑不受影响.
    (let [logged (command! id :comm-plans :log pid {:on on-str :channel "email"})]
      (is (= "email" (:last_communication_channel logged)))
      (is (= "email" (:channel (last (:communication_log logged)))))
      (is (= (str (.plusDays today 7)) (:next_date logged))))
    ;; 不标注渠道 -> 缺省沿用计划渠道 meeting 记入本次留痕, 累计两条.
    (let [logged2 (command! id :comm-plans :log pid {:on (str (.plusDays today 7))})]
      (is (= "meeting" (:last_communication_channel logged2)))
      (is (= "meeting" (:channel (last (:communication_log logged2)))))
      (is (= 2 (count (:communication_log logged2)))))
    ;; 非法渠道 -> 400, 不污染计划渠道与最近沟通渠道.
    (is (= 400 (error-status #(command! id :comm-plans :log pid {:on on-str :channel "smoke-signal"}))))
    (let [after (first (filter #(= pid (:id %)) (:comm_plans (workspace id))))]
      (is (= "meeting" (:channel after)))
      (is (= "meeting" (:last_communication_channel after))))))


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


(deftest stakeholder-engagement-is-optional-enum-persisted
  (let [id (project!)
        supporter (command! id :stakeholders :create nil
                            {:code "SH-EG-1" :name "支持方客户" :role "验收配合" :category "customer"
                             :interest "high" :influence "high" :engagement "supportive" :owner_id 9301})]
    ;; 合法参与态度枚举回显并随 payload 持久化, 读模型原样返回.
    (is (= "supportive" (:engagement supporter)))
    (is (= "supportive" (:engagement (first (filter #(= (:id supporter) (:id %)) (:stakeholders (workspace id)))))))
    ;; 未选参与态度则不写入该键, 干系人仍正常创建为 active.
    (let [plain (command! id :stakeholders :create nil
                          {:code "SH-EG-2" :name "未标注干系人" :role "观察" :category "internal"
                           :interest "low" :influence "low" :owner_id 9301})]
      (is (nil? (:engagement plain)))
      (is (= "active" (:status plain))))
    ;; 非法参与态度枚举被白名单校验拒绝.
    (is (= 400 (error-status #(command! id :stakeholders :create nil
                                        {:code "SH-EG-3" :name "非法态度" :role "x" :category "external"
                                         :interest "high" :influence "high" :engagement "champion" :owner_id 9301}))))
    ;; 修订可改参与态度而旧版本不漂移.
    (let [revised (command! id :stakeholders :revisions (:id supporter)
                            {:code "SH-EG-1" :name "支持方客户" :role "验收配合" :category "customer"
                             :interest "high" :influence "high" :engagement "leading" :owner_id 9301})]
      (is (= "leading" (:engagement revised)))
      (is (= 2 (:revision revised)))
      (is (= "supportive" (:engagement (first (filter #(= (:id supporter) (:id %)) (:stakeholders (workspace id))))))))))


(deftest stakeholder-engagement-coverage-is-derived-read-only
  (let [id (project!)
        cov (fn [] (:stakeholder_engagement_coverage (workspace id)))
        sh (fn [code engagement]
             (command! id :stakeholders :create nil
                       (cond-> {:code code :name (str "干系人-" code) :role "评审" :category "internal"
                                :interest "medium" :influence "medium" :owner_id 9301}
                         engagement (assoc :engagement engagement))))
        by-code (fn [code] (first (filter #(= code (:code %)) (:stakeholders (workspace id)))))
        e (fn [k] (:count (first (filter #(= k (:engagement %)) (:by-engagement (cov))))))]
    ;; PMBOK五类参与态度按每个干系人业务编码最新有效版本统计覆盖度: 已声明计入分子, 未设定只计入分母.
    (sh "EC-A" "supportive")
    (sh "EC-B" "leading")
    (sh "EC-C" "resistant")
    (sh "EC-D" nil)
    (is (= 4 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 1 (:undeclared (cov))))
    (is (= 75 (:coverage-pct (cov))))
    (is (= 1 (e "supportive")))
    (is (= 1 (e "leading")))
    (is (= 1 (e "resistant")))
    (is (= 0 (e "unaware")))
    (is (= 0 (e "neutral")))
    ;; 修订同一编码: 最新有效版本取代旧版参与分母, 覆盖度按最新态度重算而非累加.
    (command! id :stakeholders :revisions (:id (by-code "EC-A"))
              {:code "EC-A" :name "干系人-EC-A" :role "评审" :category "internal"
               :interest "medium" :influence "medium" :engagement "unaware" :owner_id 9301})
    (is (= 4 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 0 (e "supportive")))
    (is (= 1 (e "unaware")))
    ;; 新增声明 neutral 的干系人: 分母与分子同步上升.
    (sh "EC-E" "neutral")
    (is (= 5 (:total (cov))))
    (is (= 4 (:declared (cov))))
    (is (= 80 (:coverage-pct (cov))))
    (is (= 1 (e "neutral")))
    ;; 受控作废最新版本: 该编码从分母与分子中剔除, 覆盖度回到修订后的口径.
    (command! id :stakeholders :discard (:id (by-code "EC-E")) {:reason "人员退出项目"})
    (is (= 4 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 75 (:coverage-pct (cov))))
    (is (= 0 (e "neutral")))
    ;; 只读派生不改变干系人状态: 重复读取覆盖度稳定, 既有未作废干系人仍 active 且态度不漂移.
    (is (= (cov) (:stakeholder_engagement_coverage (workspace id))))
    (let [row (by-code "EC-B")]
      (is (= "active" (:status row)))
      (is (= "leading" (:engagement row))))))


(deftest stakeholder-desired-engagement-is-optional-enum-persisted
  (let [id (project!)
        target (command! id :stakeholders :create nil
                         {:code "SH-DE-1" :name "待争取监管方" :role "合规审查" :category "regulator"
                          :interest "high" :influence "high" :engagement "resistant"
                          :desired_engagement "supportive" :owner_id 9301})]
    ;; 合法期望参与态度枚举回显并随 payload 持久化, 读模型原样返回.
    (is (= "resistant" (:engagement target)))
    (is (= "supportive" (:desired_engagement target)))
    (let [echo (first (filter #(= (:id target) (:id %)) (:stakeholders (workspace id))))]
      (is (= "supportive" (:desired_engagement echo)))
      ;; 当前抵制 -> 期望支持, 评估差距为需提升 2 档.
      (is (= "up" (:stakeholder_engagement_state echo)))
      (is (= 2 (:stakeholder_engagement_gap echo))))
    ;; 未选期望态度则不写入该键, 干系人仍正常创建为 active.
    (let [plain (command! id :stakeholders :create nil
                          {:code "SH-DE-2" :name "未标注期望干系人" :role "观察" :category "internal"
                           :interest "low" :influence "low" :owner_id 9301})]
      (is (nil? (:desired_engagement plain)))
      (is (= "active" (:status plain)))
      (is (= "unmarked" (:stakeholder_engagement_state
                         (first (filter #(= (:id plain) (:id %)) (:stakeholders (workspace id))))))))
    ;; 非法期望参与态度枚举被白名单校验拒绝.
    (is (= 400 (error-status #(command! id :stakeholders :create nil
                                        {:code "SH-DE-3" :name "非法期望态度" :role "x" :category "external"
                                         :interest "high" :influence "high" :desired_engagement "champion"
                                         :owner_id 9301}))))
    ;; 修订可改期望态度而旧版本不漂移.
    (let [revised (command! id :stakeholders :revisions (:id target)
                            {:code "SH-DE-1" :name "待争取监管方" :role "合规审查" :category "regulator"
                             :interest "high" :influence "high" :engagement "resistant"
                             :desired_engagement "leading" :owner_id 9301})]
      (is (= "leading" (:desired_engagement revised)))
      (is (= 2 (:revision revised)))
      (is (= "supportive" (:desired_engagement
                           (first (filter #(= (:id target) (:id %)) (:stakeholders (workspace id))))))))))


(deftest stakeholder-engagement-assessment-matrix-is-derived-read-only
  (let [id (project!)
        matrix (fn [] (:stakeholder_engagement_matrix (workspace id)))
        sh (fn [code engagement desired]
             (command! id :stakeholders :create nil
                       (cond-> {:code code :name (str "干系人-" code) :role "评审" :category "internal"
                                :interest "medium" :influence "medium" :owner_id 9301}
                         engagement (assoc :engagement engagement)
                         desired (assoc :desired_engagement desired))))
        by-code (fn [code] (first (filter #(= code (:code %)) (:stakeholders (workspace id)))))]
    ;; PMBOK 投入度评估矩阵: 当前 vs 期望按每个业务编码最新有效版本计算差距档数并分类.
    (sh "EM-UP2" "resistant" "leading")    ;; gap +3 -> up
    (sh "EM-UP1" "unaware" "neutral")      ;; gap +2 -> up
    (sh "EM-ON" "supportive" "supportive") ;; gap 0  -> on
    (sh "EM-DOWN" "leading" "neutral")     ;; gap -2 -> down
    (sh "EM-MARK" "supportive" nil)        ;; 缺期望 -> unmarked
    (is (true? (:available (matrix))))
    (is (= 5 (:total (matrix))))
    (is (= 4 (:marked (matrix))))
    (is (= 1 (:on-target (matrix))))
    (is (= 2 (:need-up (matrix))))
    (is (= 1 (:need-down (matrix))))
    (is (= 1 (:unmarked (matrix))))
    (is (= 5 (:up-steps (matrix))) "需提升合计档数 3+2")
    (is (= 25 (:on-target-pct (matrix))) "达标/已标注 1/4")
    ;; 需提升清单按差距降序 (EM-UP2 3档 -> EM-UP1 2档).
    (is (= ["EM-UP2" "EM-UP1"] (mapv :code (:need-up-stakeholders (matrix)))))
    ;; 修订把 EM-UP1 从需提升改为达标: 最新有效版本取代旧版, 计数重算而非累加.
    (command! id :stakeholders :revisions (:id (by-code "EM-UP1"))
              {:code "EM-UP1" :name "干系人-EM-UP1" :role "评审" :category "internal"
               :interest "medium" :influence "medium" :engagement "neutral"
               :desired_engagement "neutral" :owner_id 9301})
    (is (= 5 (:total (matrix))))
    (is (= 2 (:on-target (matrix))))
    (is (= 1 (:need-up (matrix))))
    (is (= 3 (:up-steps (matrix))))
    (is (= ["EM-UP2"] (mapv :code (:need-up-stakeholders (matrix)))))
    ;; 受控作废最新版本: 该编码从总数与分类中剔除, 只读派生不改变不可变版本.
    (command! id :stakeholders :discard (:id (by-code "EM-DOWN")) {:reason "人员退出项目"})
    (is (= 4 (:total (matrix))))
    (is (= 0 (:need-down (matrix))))
    ;; 只读派生稳定且不改记录: 重复读取一致, 既有未作废干系人仍 active 且态度不漂移.
    (is (= (matrix) (:stakeholder_engagement_matrix (workspace id))))
    (let [row (by-code "EM-UP2")]
      (is (= "active" (:status row)))
      (is (= "up" (:stakeholder_engagement_state row)))
      (is (= 3 (:stakeholder_engagement_gap row))))
    ;; 纯函数直测: 空输入 available false/全 0; 合成覆盖 up/down/on/unmarked 与 discarded 剔除.
    (let [empty (stakeholders/engagement-assessment-matrix [])]
      (is (false? (:available empty)))
      (is (= 0 (:total empty)))
      (is (= 0 (:on-target-pct empty)))
      (is (empty? (:need-up-stakeholders empty))))
    (let [syn (stakeholders/engagement-assessment-matrix
                [{:code "A" :name "a" :revision 1 :status "active" :engagement "resistant" :desired_engagement "leading"}
                 {:code "B" :name "b" :revision 1 :status "active" :engagement "leading" :desired_engagement "unaware"}
                 {:code "C" :name "c" :revision 1 :status "active" :engagement "neutral" :desired_engagement "neutral"}
                 {:code "D" :name "d" :revision 1 :status "active" :engagement "supportive"}
                 {:code "E" :name "e" :revision 2 :status "discarded" :engagement "unaware" :desired_engagement "leading"}])]
      (is (= 4 (:total syn)))
      (is (= 1 (:need-up syn)))
      (is (= 1 (:need-down syn)))
      (is (= 1 (:on-target syn)))
      (is (= 1 (:unmarked syn)))
      (is (= 3 (:up-steps syn)))
      (is (= 33 (:on-target-pct syn))))))


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


(deftest meeting-discard-is-guarded-and-restorable
  ;; 未被引用的登记态会议可受控作废与恢复, 审计回写作废前状态
  (let [id (project!)
        meeting (command! id :meetings :create nil
                          {:title "设计评审" :held_on "2026-09-22" :minutes "补齐验证任务" :attendee_ids [9301 9302]})
        mid (:id meeting)]
    (is (= 403 (error-status #(command! 9302 id :meetings :discard mid {:reason "越权作废"}))))
    (is (= 400 (error-status #(command! id :meetings :discard mid {:reason "字段越界" :extra 1}))))
    (let [p (gov/discard-preview *service* (actor 9301) id "meeting" mid)]
      (is (true? (:status_discardable? p)))
      (is (true? (:discardable? p)))
      (is (empty? (:references p))))
    (let [discarded (command! id :meetings :discard mid {:reason "误登记的重复会议"})]
      (is (= "discarded" (:status discarded)))
      (is (= "误登记的重复会议" (:discard_reason discarded)))
      (is (= 9301 (:discarded_by discarded)))
      (is (some? (:discarded_on discarded)))
      (is (= "recorded" (:prior_status (last (:workflow_history discarded)))))
      (is (= "discarded" (:status (first (filter #(= mid (:id %)) (:meetings (workspace id))))))))
    (is (= 409 (error-status #(command! id :meetings :discard mid {:reason "重复作废"}))))
    (is (= 409 (error-status #(command! id :meetings :actions mid {:title "越界行动" :owner_id 9301 :due_date "2026-10-01"}))))
    (let [restored (command! id :meetings :restore mid {:reason "误作废恢复"})]
      (is (= "recorded" (:status restored)))
      (is (= "误作废恢复" (:restore_reason restored)))
      (is (= 9301 (:restored_by restored)))
      (is (= ["discarded" "restored"] (map :action (:workflow_history restored))))
      (is (= "recorded" (:restored_to (last (:workflow_history restored))))))
    (is (= 409 (error-status #(command! id :meetings :restore mid {:reason "非作废不可恢复"})))))
  ;; 发布审批中与已发布会议不在可作废集合, 直接作废被状态门控 409
  (let [id (project!)
        m (command! id :meetings :create nil {:title "待发布纪要" :held_on "2026-09-22" :minutes "正式结论" :attendee_ids [9301 9302]})
        mid (:id m)]
    (command! id :meetings :submit mid {:reviewer_id 9302})
    (is (= 409 (error-status #(command! id :meetings :discard mid {:reason "审批中不可作废"}))))
    (let [p (gov/discard-preview *service* (actor 9301) id "meeting" mid)]
      (is (false? (:status_discardable? p)))
      (is (false? (:discardable? p))))
    (command! 9302 id :meetings :decision mid {:decision "approved" :reason "纪要完整可归档"})
    (is (= 409 (error-status #(command! id :meetings :discard mid {:reason "已发布不可作废"})))))
  ;; 仍派生行动的会议不可作废, 级联预览列出"行动"引用
  (let [id (project!)
        m (command! id :meetings :create nil {:title "含行动会议" :held_on "2026-09-22" :minutes "结论" :attendee_ids [9301 9302]})
        mid (:id m)
        _ (command! id :meetings :actions mid {:title "补充验证" :owner_id 9301 :due_date "2026-09-25"})
        p (gov/discard-preview *service* (actor 9301) id "meeting" mid)]
    (is (true? (:status_discardable? p)))
    (is (false? (:discardable? p)))
    (is (some #(.contains ^String % "行动") (:references p)))
    (is (= 409 (error-status #(command! id :meetings :discard mid {:reason "仍有行动"})))))
  ;; 由沟通计划生成的会议被 last_meeting_id 引用, 不可作废, 预览列出"沟通计划"
  (let [id (project!)
        sh (stakeholder! id "SH-MG" 9301)
        plan (command! id :comm-plans :create nil
                       {:code "CP-MG" :objective "月度沟通" :channel "meeting" :frequency "monthly"
                        :audience [(:id sh)] :next_date "2026-09-25" :owner_id 9301})
        gen (command! id :comm-plans :meeting (:id plan) {:held_on "2026-09-26"})
        p (gov/discard-preview *service* (actor 9301) id "meeting" (:id gen))]
    (is (= 409 (error-status #(command! id :meetings :discard (:id gen) {:reason "被沟通计划引用"}))))
    (is (false? (:discardable? p)))
    (is (some #(.contains ^String % "沟通计划") (:references p)))))


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


(deftest issue-closure-summary-is-derived-read-only
  (let [id (project!)
        evidence (:id (document! id "ISS-CL-1"))
        i-open (command! id :issues :create nil {:title "待处理一般项" :severity "major" :owner_id 9301 :due_date "2026-12-31"})
        i-overdue (command! id :issues :create nil {:title "逾期未关闭" :severity "major" :owner_id 9301 :due_date "2026-01-01"})
        i-inreview (command! id :issues :create nil {:title "验证中" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})
        i-closed (command! id :issues :create nil {:title "已闭环" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})
        i-rejected (command! id :issues :create nil {:title "验证驳回" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})
        i-blocker (command! id :issues :create nil {:title "阻断未闭环" :severity "blocker" :owner_id 9301 :due_date "2026-12-31"})]
    ;; 三条 minor 分别推进到 in_review / closed / rejected; 阻断级仅登记不解决 (自动升级 pending 不影响闭环计数).
    (command! id :issues :resolve (:id i-inreview) {:resolution "初步整改" :evidence_ids [evidence] :reviewer_id 9302})
    (command! id :issues :resolve (:id i-closed) {:resolution "整改完成" :evidence_ids [evidence] :reviewer_id 9302})
    (is (= "closed" (:status (command! 9302 id :issues :decision (:id i-closed) {:decision "approved" :reason "独立复验通过"}))))
    (command! id :issues :resolve (:id i-rejected) {:resolution "尝试整改" :evidence_ids [evidence] :reviewer_id 9302})
    (is (= "rejected" (:status (command! 9302 id :issues :decision (:id i-rejected) {:decision "rejected" :reason "证据不足"}))))
    (let [ver (version id)
          s (:issue_closure (workspace id))]
      (is (true? (:available s)))
      (is (= 6 (:total s)))
      (is (= 1 (:closed s)))
      (is (= 5 (:open s)))
      (is (= 3 (:pending s)))
      (is (= 1 (:in-review s)))
      (is (= 1 (:rejected s)))
      (is (= 1 (:overdue s)))
      (is (= 1 (:blocker-open s)))
      (is (= 17 (:closure-pct s)))
      (is (= [{:severity "blocker" :count 1} {:severity "major" :count 2} {:severity "minor" :count 3}] (:by-severity s)))
      ;; 闭环汇总为纯读取, 不得漂移项目聚合版本.
      (is (= ver (version id))))
    ;; 纯函数直测: 空集 available=false 且 closure-pct=0; 混合状态按 code 最新有效版本聚合, 修订不重复计数.
    (let [e (collab/issue-closure-summary [])]
      (is (false? (:available e)))
      (is (= 0 (:total e)))
      (is (= 0 (:closure-pct e))))
    (let [m (collab/issue-closure-summary
              [{:code "P1" :revision 1 :status "closed" :severity "blocker"}
               {:code "P1" :revision 2 :status "closed" :severity "blocker"}
               {:code "P2" :revision 1 :status "open" :severity "major" :issue_overdue true}])]
      (is (= 2 (:total m)) "同 code 修订只计最新有效版本")
      (is (= 1 (:closed m)))
      (is (= 1 (:overdue m)))
      (is (= 50 (:closure-pct m))))))


(deftest issue-resolution-type-is-optional-enum-persisted
  (let [id (project!)
        evidence (:id (document! id "ISS-RT-1"))
        with-type (command! id :issues :create nil {:title "带解决方式" :severity "major" :owner_id 9301 :due_date "2026-12-31"})
        without-type (command! id :issues :create nil {:title "未选解决方式" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})]
    ;; 合法解决方式随提交解决持久化并在命令结果回显.
    (let [res (command! id :issues :resolve (:id with-type)
                        {:resolution "更换密封件并复测" :resolution_type "fixed" :evidence_ids [evidence] :reviewer_id 9302})]
      (is (= "in_review" (:status res)))
      (is (= "fixed" (:resolution_type res))))
    ;; workspace 读模型逐条回显 resolution_type (payload 字段自动透传).
    (let [row (first (filter #(= (:id with-type) (:id %)) (:issues (workspace id))))]
      (is (= "fixed" (:resolution_type row))))
    ;; 非法解决方式 400, 且不改变问题状态 (仍 open).
    (is (= 400 (error-status #(command! id :issues :resolve (:id without-type)
                                        {:resolution "尝试" :resolution_type "invalid-type" :evidence_ids [evidence] :reviewer_id 9302}))))
    (is (= "open" (:status (first (filter #(= (:id without-type) (:id %)) (:issues (workspace id)))))))
    ;; 未选择解决方式则不写入该键 (零回归, 视为未设定).
    (let [res (command! id :issues :resolve (:id without-type)
                        {:resolution "仅文字说明" :evidence_ids [evidence] :reviewer_id 9302})]
      (is (= "in_review" (:status res)))
      (is (nil? (:resolution_type res))))))


(deftest issue-resolution-coverage-is-derived-read-only
  (let [id (project!)
        evidence (:id (document! id "ISS-RC-1"))
        cov (fn [] (:issue_resolution_coverage (workspace id)))
        row (fn [rid] (first (filter #(= rid (:id %)) (:issues (workspace id)))))
        r (fn [k] (:count (first (filter #(= k (:resolution %)) (:by-resolution (cov))))))]
    ;; 五条问题: 三条提交解决时声明解决方式, 一条解决但未选方式, 一条尚未解决 -> 覆盖度按声明数/总数.
    (doseq [[t ty] [["修复项" "fixed"] ["规避项" "workaround"] ["重复项" "duplicate"]]]
      (let [i (command! id :issues :create nil {:title t :severity "major" :owner_id 9301 :due_date "2026-12-31"})]
        (command! id :issues :resolve (:id i)
                  {:resolution "整改说明" :resolution_type ty :evidence_ids [evidence] :reviewer_id 9302})))
    (let [i (command! id :issues :create nil {:title "解决未选方式" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})]
      (command! id :issues :resolve (:id i) {:resolution "仅文字" :evidence_ids [evidence] :reviewer_id 9302}))
    (command! id :issues :create nil {:title "尚未解决" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})
    (is (= 5 (:total (cov))))
    (is (= 3 (:declared (cov))))
    (is (= 2 (:undeclared (cov))))
    (is (= 60 (:coverage-pct (cov))))
    (is (= 1 (r "fixed")))
    (is (= 1 (r "workaround")))
    (is (= 1 (r "duplicate")))
    (is (= 0 (r "by-design")))
    (is (= 0 (r "cannot-reproduce")))
    (is (= 0 (r "wont-fix")))
    ;; 独立验证通过后问题闭环, 但解决方式仍在 -> declared 不随闭环下降.
    (let [i (command! id :issues :create nil {:title "闭环仍计" :severity "minor" :owner_id 9301 :due_date "2026-12-31"})
          iid (:id i)]
      (command! id :issues :resolve iid {:resolution "整改" :resolution_type "by-design" :evidence_ids [evidence] :reviewer_id 9302})
      (is (= "closed" (:status (command! 9302 id :issues :decision iid {:decision "approved" :reason "复验通过"}))))
      (is (= 6 (:total (cov))))
      (is (= 4 (:declared (cov))))
      (is (= 1 (r "by-design"))))
    ;; 只读派生稳定且不漂移项目版本/问题状态.
    (let [ver (version id)]
      (is (= (cov) (:issue_resolution_coverage (workspace id))))
      (is (= "open" (:status (first (filterv #(and (= "尚未解决" (:title %)) (nil? (:resolution_type %))) (:issues (workspace id)))))))
      (is (= ver (version id))))
    ;; 纯函数直测: 空集 total=0 覆盖率=0; 同 code 修订只计最新有效版本不重复计数.
    (let [e (collab/issue-resolution-coverage [])]
      (is (= 0 (:total e)))
      (is (= 0 (:declared e)))
      (is (= 0 (:coverage-pct e))))
    (let [m (collab/issue-resolution-coverage
              [{:code "R1" :revision 1 :resolution_type "fixed"}
               {:code "R1" :revision 2 :resolution_type "duplicate"}
               {:code "R2" :revision 1}])]
      (is (= 2 (:total m)) "同 code 修订只计最新有效版本")
      (is (= 1 (:declared m)))
      (is (= 1 (:undeclared m)))
      (is (= 50 (:coverage-pct m)))
      (is (= 0 (:count (first (filter #(= "fixed" (:resolution %)) (:by-resolution m))))) "旧版本的 fixed 被最新 duplicate 取代")
      (is (= 1 (:count (first (filter #(= "duplicate" (:resolution %)) (:by-resolution m)))))))))


(deftest custom-risk-template-crud-instantiation-and-discard
  (let [id (project!)
        tpl (command! id :risk-templates :create nil
                      {:title "供应商交付延误" :category "schedule" :probability 3 :impact 4
                       :mitigation "提前锁定备选供应商并设置里程碑预警" :stage "执行"})]
    ;; 新建模板真实落库并回显按概率x影响派生的评分.
    (is (:id tpl))
    (is (= "供应商交付延误" (:title tpl)))
    (is (= 12 (:score tpl)))
    (is (= "active" (:status tpl)))
    (is (= "schedule" (:category tpl)))
    (is (= "执行" (:stage tpl)))
    ;; 工作台只读暴露有效模板供前端选择实例化.
    (is (some #(= (:id tpl) (:id %)) (:risk_templates (workspace id))))
    ;; 更新评分与措施后派生评分随之变化, 仍为同一模板id.
    (let [upd (command! id :risk-templates :update (:id tpl)
                        {:title "供应商交付延误" :category "schedule" :probability 4 :impact 5
                         :mitigation "已升级为双源供应并加入合同违约条款" :stage "执行"})]
      (is (= (:id tpl) (:id upd)))
      (is (= 20 (:score upd))))
    ;; 从模板实例化为真实风险: 继承评分/措施/阶段/类别并标注自定义来源.
    (let [risk (command! id :risks :from-custom-template nil
                         {:template_id (:id tpl) :owner_id 9301 :due_date "2026-11-30"})]
      (is (= "risk" (:kind risk)))
      (is (= "供应商交付延误" (:title risk)))
      (is (= 20 (:score risk)))
      (is (= "执行" (:stage risk)))
      (is (= (str "custom:" (:id tpl)) (:source_key risk)))
      (is (= "schedule" (:source_category risk)))
      ;; 4x5=20 达阈值自动升级并挂起自行缓解.
      (is (true? (:escalated risk)))
      (is (= "pending" (:escalation_state risk)))
      (is (= "steering" (:escalation_level risk)))
      (is (= 409 (error-status #(command! id :risks :mitigate (:id risk)
                                          {:mitigation "已联系备选" :evidence_ids [(:id (document! id "CT-ESC"))]})))))
    ;; 受控作废模板: 软置已作废并从可实例化列表移除, 已登记的历史风险不受影响.
    (command! id :risk-templates :discard (:id tpl) {})
    (is (not-any? #(= (:id tpl) (:id %)) (:risk_templates (workspace id))))
    (is (some #(= "供应商交付延误" (:title %)) (:risks (workspace id))))
    (is (= 404 (error-status #(command! id :risks :from-custom-template nil
                                        {:template_id (:id tpl) :owner_id 9301 :due_date "2026-11-30"}))))))


(deftest custom-risk-template-instantiation-escalation-thresholds
  (let [id (project!)
        low (command! id :risk-templates :create nil
                      {:title "低风险观察项" :probability 2 :impact 3 :mitigation "持续观察"})
        high (command! id :risk-templates :create nil
                       {:title "高风险阻断项" :probability 5 :impact 5 :mitigation "成立专项攻关组"})]
    ;; 低分模板实例化不触发升级, 登记人可直接缓解.
    (let [r1 (command! id :risks :from-custom-template nil
                       {:template_id (:id low) :owner_id 9301 :due_date "2026-11-30"})]
      (is (= 6 (:score r1)))
      (is (false? (:escalated r1)))
      (is (nil? (:escalation_state r1)))
      (is (= "mitigated" (:status (command! id :risks :mitigate (:id r1)
                                            {:mitigation "已消除" :evidence_ids [(:id (document! id "CT-LOW"))]})))))
    ;; 高分模板实例化进入指导层升级, 缓解须先由独立审批人确认.
    (let [r2 (command! id :risks :from-custom-template nil
                       {:template_id (:id high) :owner_id 9301 :due_date "2026-11-30"})]
      (is (= 25 (:score r2)))
      (is (= "steering" (:escalation_level r2)))
      (is (= "pending" (:escalation_state r2)))
      (is (= 409 (error-status #(command! id :risks :mitigate (:id r2)
                                          {:mitigation "临时绕行" :evidence_ids [(:id (document! id "CT-HI"))]}))))
      (is (= 403 (error-status #(command! id :risks :escalate (:id r2)
                                          {:decision "approved" :note "登记人自确认"}))))
      (let [acked (command! 9302 id :risks :escalate (:id r2)
                            {:decision "approved" :note "管理层责成启动攻关组"})]
        (is (= "acknowledged" (:escalation_state acked))))
      (is (= "mitigated" (:status (command! id :risks :mitigate (:id r2)
                                            {:mitigation "按升级方案处置" :evidence_ids [(:id (document! id "CT-HI2"))]})))))))


(deftest custom-risk-template-validation-scoping-and-guards
  (let [id (project!)
        other (project!)]
    ;; 概率与影响必须为1到5整数, 越界或非整数被拒.
    (is (= 400 (error-status #(command! id :risk-templates :create nil
                                        {:title "越界概率" :probability 6 :impact 3 :mitigation "无"}))))
    (is (= 400 (error-status #(command! id :risk-templates :create nil
                                        {:title "非整数影响" :probability 3 :impact "高" :mitigation "无"}))))
    ;; 标题与应对措施为必填.
    (is (= 400 (error-status #(command! id :risk-templates :create nil
                                        {:probability 3 :impact 3 :mitigation "无"}))))
    (is (= 400 (error-status #(command! id :risk-templates :create nil
                                        {:title "缺措施" :probability 3 :impact 3}))))
    ;; 白名单外的字段被拒.
    (is (= 400 (error-status #(command! id :risk-templates :create nil
                                        {:title "多余字段" :probability 3 :impact 3 :mitigation "无" :bogus 1}))))
    (let [tpl (command! id :risk-templates :create nil
                        {:title "跨项目隔离模板" :probability 2 :impact 3 :mitigation "只在本项目可用"})]
      ;; 实例化缺责任人或期限分别被拒.
      (is (= 400 (error-status #(command! id :risks :from-custom-template nil
                                          {:template_id (:id tpl) :due_date "2026-11-30"}))))
      ;; 跨项目引用他项目模板返回404 (按 project_id 隔离).
      (is (= 404 (error-status #(command! other :risks :from-custom-template nil
                                          {:template_id (:id tpl) :owner_id 9301 :due_date "2026-11-30"}))))
      ;; 他项目读取本模板与更新/作废未知或非本项目模板分别404.
      (is (= 404 (error-status #(command! other :risk-templates :update (:id tpl)
                                          {:title "篡改" :probability 2 :impact 3 :mitigation "x"}))))
      ;; 更新/作废不存在的模板id返回404.
      (is (= 404 (error-status #(command! id :risk-templates :discard (str "no-" (:id tpl)) {}))))
      ;; 已作废模板不可再更新.
      (command! id :risk-templates :discard (:id tpl) {})
      (is (= 404 (error-status #(command! id :risk-templates :update (:id tpl)
                                          {:title "复活" :probability 2 :impact 3 :mitigation "x"})))))))
