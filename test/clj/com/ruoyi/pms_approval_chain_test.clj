(ns com.ruoyi.pms-approval-chain-test
  "S3 可配置审批链: 审批策略校验与版本化, 按规则解析审批人 (部门负责人/上溯/角色/项目经理/指定用户/提交人选择),
   或签/会签, 金额条件, 无审批人处理, 逐级决定与独立审核底线, 审批参与人只读访问, 统一待办,
   以及费用版本/项目章程/计划基线三类业务的落定. 在真实迁移后的 SQLite (或 CI MySQL) 上验证."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.approval-chain :as chain]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.governance.store :as store]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.planning :as plan]
            [com.ruoyi.domain.pms.portfolio :as portfolio]
            [com.ruoyi.domain.pms.service :as pms]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(def ^:dynamic *svc* nil)
(def ^:dynamic *db* nil)

;; 9510 项目经理/提交人, 9511 研发部门负责人, 9512 深圳总公司负责人, 9513 财务 (角色 chain-finance, 非项目成员),
;; 9514 项目成员 (被指定审批人), 9515 另一财务
(defn- fixture
  [f]
  (let [provided (System/getenv "PMS_TEST_JDBC_URL")
        file (when-not provided (java.io.File/createTempFile "pms-chain-" ".db"))
        url (or provided (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                        :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9520,'Chain PMS','chain-pms',40,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9521,'Chain finance','chain-finance',41,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9522,'Chain empty','chain-empty',42,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9520,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
      (doseq [[id dept] [[9510 4] [9511 4] [9512 2] [9513 6] [9514 4] [9515 6]]]
        (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(?,?,?,?,'0','0')"
                           id dept (str "chain-" id) (str "审批链" id)])
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9520)" id]))
      (doseq [id [9513 9515]] (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9521)" id]))
      (jdbc/execute! db ["UPDATE sys_dept SET leader_id = 9511 WHERE dept_id = 4"])
      (jdbc/execute! db ["UPDATE sys_dept SET leader_id = 9512 WHERE dept_id = 2"])
      (let [files (->> (.listFiles (io/file "resources/sql"))
                       (filter #(re-matches #"pms.*\.sql" (.getName %)))
                       (map #(str "sql/" (.getName %))) sort)
            queries (:fns (apply conman/bind-connection-map db {} files))
            query (fn ([k p] ((get-in queries [k :fn]) p))
                      ([tx k p] ((get-in queries [k :fn]) tx p)))]
        (binding [*svc* {:db db :query-fn query} *db* db] (f)))
      (finally (when file (.delete file))))))

(use-fixtures :once fixture)

(defn- actor [id] (pms/actor *svc* {:user-id id}))
(defn- version [id] (:version ((:query-fn *svc*) :pms/project {:project_id id})))
(defn- error-status [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(defn- command!
  ([f id args body] (command! 9510 f id args body))
  ([uid f id args body]
   (apply f *svc* (actor uid) id (concat args [(assoc body :version (version id))]))))

(defn- project!
  "研发部门 (4) 的项目, 9510 为项目经理, 9514 为编辑成员."
  []
  (let [p (pms/create-project! *svc* (actor 9510)
                               {:project_no (str "CHN-" (kernel/id)) :name "审批链验收"
                                :manager_id 9510 :dept_id 4 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id p)]
    (pms/set-member! *svc* (actor 9510) id {:user_id 9514 :role "editor"})
    id))

(defn- publish-policy!
  "发布 (替换) 某类型的审批策略."
  [code levels]
  (let [admin (actor 9510)
        existing (last (sort-by :revision (filter #(= code (:code %)) (config/records (:query-fn *svc*) "approval-policy"))))
        draft (if existing
                (if (= "draft" (:status existing))
                  existing
                  (config/revise! *svc* admin "approval-policy" (:id existing) {:levels levels}))
                (config/create! *svc* admin "approval-policy" {:code code :levels levels}))]
    (config/publish! *svc* admin "approval-policy" (:id draft) {:reason "测试发布"})))

(defn- retire-policy!
  [code]
  (when-let [p (config/published-by-code (:query-fn *svc*) "approval-policy" code)]
    (config/retire! *svc* (actor 9510) "approval-policy" (:id p) {:reason "测试退役"})))

(defn- cost-version!
  "建立并提交一个总额为 amount 的费用版本 (指定审批人 9514)."
  [id amount]
  (let [cv (:result (command! cost/create! id [] {:kind "budget" :period "2026-09" :currency "CNY"
                                                   :name (str "预算" amount) :revenue "0.00" :reviewer_id 9514}))]
    (command! cost/add-entry! id [(:id cv)] {:category "material" :label "原料" :amount amount})
    (command! cost/submit! id [(:id cv)] {})
    (:id cv)))

(defn- flow-of
  [id biz-type biz-id]
  (first (chain/project-flows *svc* (actor 9510) id {:biz_type biz-type :biz_id biz-id})))

(defn- decide!
  [uid flow decision & [comment]]
  (chain/decide! *svc* (actor uid) (:flow_id flow) (cond-> {:decision decision} comment (assoc :comment comment))))

(defn- cost-status
  [id cid]
  (:status ((:query-fn *svc*) :finance/version {:project_id id :version_id cid})))


(deftest policy-content-is-validated-and-versioned
  (let [admin (actor 9510)
        create #(config/create! *svc* admin "approval-policy" %)]
    (is (= 400 (error-status #(create {:code "unknown" :levels [{:name "x" :rule "user" :user_ids [9511]}]}))))
    (is (= 400 (error-status #(create {:code "closure" :levels []}))))
    (is (= 400 (error-status #(create {:code "closure" :levels [{:name "x" :rule "boss"}]}))))
    (is (= 400 (error-status #(create {:code "closure" :levels [{:name "x" :rule "dept_leader" :up 9}]}))))
    (is (= 400 (error-status #(create {:code "closure" :levels [{:name "x" :rule "user" :user_ids []}]}))))
    (is (= 400 (error-status #(create {:code "closure" :levels [{:name "金额" :rule "project_manager" :min_amount "10.00"}]})))
        "没有金额的类型不能设置金额条件")
    (testing "导入内置示例为草稿, 发布后生效, 修订发布后旧版本退役"
      (let [draft (config/import-catalog! *svc* admin "approval-policy" {:code "closure"})]
        (is (= "draft" (:status draft)))
        (is (= 2 (count (:levels draft))))
        (config/publish! *svc* admin "approval-policy" (:id draft) {:reason "启用"})
        (is (= (:id draft) (:id (chain/policy (:query-fn *svc*) "closure"))))
        (let [rev (config/revise! *svc* admin "approval-policy" (:id draft)
                                  {:levels [{:name "部门负责人" :rule "dept_leader"}]})]
          (config/publish! *svc* admin "approval-policy" (:id rev) {:reason "简化"})
          (is (= 2 (:revision (chain/policy (:query-fn *svc*) "closure"))))
          (is (= "retired" (:status (config/record! (:query-fn *svc*) "approval-policy" (:id draft))))))
        (retire-policy! "closure")
        (is (nil? (chain/policy (:query-fn *svc*) "closure")))))))


(deftest cost-version-multi-level-chain
  (publish-policy! "cost-version"
                   [{:name "部门负责人审批" :rule "dept_leader" :up 0 :mode "any"}
                    {:name "财务审批" :rule "role" :role_key "chain-finance" :mode "any"}
                    {:name "上级负责人审批 (1000元及以上)" :rule "dept_leader" :up 1 :mode "any" :min_amount "1000.00"}])
  (try
    (let [id (project!)
          cid (cost-version! id "500.00")
          flow (flow-of id "cost-version" cid)]
      (testing "按策略生成逐级审批, 金额未达阈值的级别不生成"
        (is (= "pending" (:status flow)))
        (is (= [1 2] (mapv :level_no (:levels flow))))
        (is (= [9511] (mapv :approver_id (:steps (first (:levels flow))))))
        (is (= #{9513 9515} (set (map :approver_id (:steps (second (:levels flow))))))))
      (testing "审批中原单人审核接口拒绝直接决定"
        (is (= 409 (error-status #(command! 9514 cost/review! id [cid] {:decision "approved" :reason "直接批准"})))))
      (testing "统一待办: 当前级审批人看到, 后续级审批人暂不出现"
        (is (some #(= (:flow_id flow) (:flow_id %)) (:approvals (portfolio/todo *svc* (actor 9511)))))
        (is (empty? (:approvals (portfolio/todo *svc* (actor 9513))))))
      (testing "提交人, 非当前级审批人, 管理员都不能代批"
        (is (= 403 (error-status #(decide! 9510 flow "approved"))))
        (is (= 403 (error-status #(decide! 9513 flow "approved"))))
        (is (= 403 (error-status #(decide! 9514 flow "approved")))))
      (testing "第一级通过后进入第二级; 非项目成员的财务审批人获得只读访问"
        (is (= 403 (error-status #(chain/project-flows *svc* (actor 9513) id {}))) "轮到之前没有访问权")
        (let [after (decide! 9511 flow "approved" "同意")]
          (is (= "pending" (:status after)))
          (is (= 2 (:current_level after))))
        (is (seq (chain/project-flows *svc* (actor 9513) id {})) "轮到后可只读查看")
        (is (= 403 (error-status #(command! 9513 cost/add-entry! id [cid] {:category "other" :label "越权" :amount "1.00"}))) "只读, 不能写"))
      (testing "驳回必须填写意见; 或签任一人通过即完成, 末级通过后费用版本批准"
        (is (= 400 (error-status #(decide! 9513 flow "rejected"))))
        (let [done (decide! 9513 flow "approved" "金额核对无误")]
          (is (= "approved" (:status done)))
          (is (= ["approved" "approved"] (mapv :status (:levels done))))
          (is (= "skipped" (:status (first (filter #(= 9515 (:approver_id %)) (:steps (second (:levels done))))))))
          (is (= "approved" (cost-status id cid))))
        (is (= 409 (error-status #(decide! 9515 flow "approved"))) "已结束的审批不能再决定")))
    (let [id (project!)
          cid (cost-version! id "1200.00")
          flow (flow-of id "cost-version" cid)]
      (testing "金额达到阈值时需要上级负责人; 任一级驳回即驳回费用版本"
        (is (= [1 2 3] (mapv :level_no (:levels flow))))
        (decide! 9511 flow "approved")
        (let [done (decide! 9515 flow "rejected" "超出预算")]
          (is (= "rejected" (:status done)))
          (is (= "cancelled" (:status (first (:steps (nth (:levels done) 2)))))))
        (is (= "rejected" (cost-status id cid)))))
    (finally (retire-policy! "cost-version"))))


(deftest countersign-empty-level-and-snapshot
  (try
    (testing "某级没有可用审批人且配置为拒绝时, 提交失败并回滚"
      (publish-policy! "cost-version" [{:name "空角色" :rule "role" :role_key "chain-empty" :on_empty "reject"}])
      (let [id (project!)
            cv (:result (command! cost/create! id [] {:kind "budget" :period "2026-09" :currency "CNY"
                                                       :name "空角色" :revenue "0.00" :reviewer_id 9514}))]
        (command! cost/add-entry! id [(:id cv)] {:category "material" :label "原料" :amount "1.00"})
        (is (= 409 (error-status #(command! cost/submit! id [(:id cv)] {}))))
        (is (= "draft" (cost-status id (:id cv))))))
    (testing "跳过空级别; 会签需全部通过; 提交人选择使用指定审批人; 策略快照不受后续退役影响"
      (publish-policy! "cost-version" [{:name "空角色" :rule "role" :role_key "chain-empty" :on_empty "skip"}
                                       {:name "会签" :rule "user" :user_ids [9511 9512 9510] :mode "all"}
                                       {:name "指定审批人" :rule "submitter_choice"}])
      (let [id (project!)
            cid (cost-version! id "10.00")
            flow (flow-of id "cost-version" cid)]
        (is (= [2 3] (mapv :level_no (:levels flow))))
        (is (= #{9511 9512} (set (map :approver_id (:steps (first (:levels flow)))))) "提交人自动排除")
        (retire-policy! "cost-version")
        (is (= 2 (:current_level (decide! 9511 flow "approved"))) "会签未全部通过前停留本级")
        (is (= 3 (:current_level (decide! 9512 flow "approved"))))
        (is (= "approved" (:status (decide! 9514 flow "approved"))))
        (is (= "approved" (cost-status id cid)))))
    (finally (retire-policy! "cost-version"))))


(deftest charter-and-plan-baseline-chains
  (let [id (project!)]
    (command! pms/transition-project! id [] {:status "initiated" :reason "立项"})
    (command! pms/transition-project! id [] {:status "planning" :reason "计划"})
    (try
      (testing "章程: 策略生效时提交无需指定审核人; 项目经理是提交人被排除后跳过; 部门负责人批准后章程生效"
        (publish-policy! "charter" [{:name "项目经理" :rule "project_manager" :on_empty "skip"}
                                    {:name "部门负责人" :rule "dept_leader"}])
        (let [charter (:result (command! gov/command! id [:charters :create nil]
                                         {:title "章程" :objective "交付" :scope "设备" :success_criteria "验收" :sponsor_id 9514}))
              _ (command! gov/command! id [:charters :submit (:id charter)] {})
              flow (flow-of id "charter" (:id charter))]
          (is (= [2] (mapv :level_no (:levels flow))))
          (is (= 409 (error-status #(command! 9511 gov/command! id [:charters :decision (:id charter)] {:decision "approved" :reason "直接"}))))
          (is (= "approved" (:status (decide! 9511 flow "approved" "范围明确"))))
          (is (= "approved" (:status (first (filter #(= (:id charter) (:id %))
                                                    (store/records (:query-fn *svc*) {:project_id id} "charter"))))))))
      (testing "计划基线: 原接口被拒, 部门负责人批准后基线生效"
        (publish-policy! "plan-baseline" [{:name "部门负责人" :rule "dept_leader"}])
        (let [_ (command! plan/create-task! id [] {:wbs_code "1" :name "设计" :duration_days 1 :owner_id 9510})
              baseline (:result (command! plan/submit-plan! id [] {:comment "发布基线"}))
              flow (flow-of id "plan-baseline" (:baseline_id baseline))]
          (is (= 409 (error-status #(command! 9514 plan/review-plan! id [(:baseline_id baseline)] {:decision "approved" :comment "直接"}))))
          (is (= "approved" (:status (decide! 9511 flow "approved"))))
          (is (= "approved" (:status (first (filter #(= (:baseline_id baseline) (:baseline_id %))
                                                    ((:query-fn *svc*) :planning/baselines {:project_id id}))))))))
      (finally
        (retire-policy! "charter")
        (retire-policy! "plan-baseline")))))
