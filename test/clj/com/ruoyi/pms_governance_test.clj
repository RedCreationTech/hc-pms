(ns com.ruoyi.pms-governance-test
  "真实数据库上的治理审批,证据,整批导入和跨模块闭环测试."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [com.ruoyi.domain.pms.governance :as gov]
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
      (is (= 4 (count (:workflow_history closed)))))))


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
