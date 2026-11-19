(ns com.ruoyi.pms-config-test
  "平台模板/编码规则/经营目标/封期配置及项目网络实例化 (A07/A09/A10/A11, B03/B06-B15 关口目录) 的真实数据库测试."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.finance-cost :as cost]
    [com.ruoyi.domain.pms.report :as report]
    [com.ruoyi.domain.pms.external-catalog :as external]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.gates :as gates]
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
    (java.nio.file
      Files)
    (java.util
      UUID)))


(def ^:dynamic *service* nil)


(def ^:dynamic *handler* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  "配置测试专属角色与用户: 9441 管理配置+项目, 9442 只读, 9443 审批人."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9440,'Config test','pms-config-test',40,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9440,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9445,'Config reader','pms-config-reader',41,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9445,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:config:list')"])
  (doseq [[id role] [[9441 9440] [9442 9445] [9443 9440]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "config-test-" id) (str "配置测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-config-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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
  [uid]
  (pms/actor *service* {:user-id uid}))


(defn- error-status
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- project!
  ([] (project! "line"))
  ([type]
   (let [id (:project_id (pms/create-project! *service* (actor 9441)
                                              {:project_no (str "CFG-" (subs (str (UUID/randomUUID)) 0 8)) :name "模板实例化验证"
                                               :customer "本地测试" :contract_no "CFG-TEST" :project_type type
                                               :manager_id 9441 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
     (pms/set-member! *service* (actor 9441) id {:user_id 9443 :role "viewer"})
     id)))


(defn- version
  [id]
  (:version (pms/project *service* (actor 9441) id)))


(defn- command!
  ([id resource action rid body] (command! 9441 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- import-and-publish!
  "从目录导入并发布一个模板, 返回生效记录; 同编码已发布时直接复用 (多个测试共享目录编码, 避免二次导入409)."
  [kind code]
  (or (config/published-by-code (:query-fn *service*) kind code)
      (let [draft (config/import-catalog! *service* (actor 9441) kind {:code code})]
        (config/publish! *service* (actor 9441) kind (:id draft) {:reason "发布用于测试"}))))


(defn- published
  [kind code]
  (config/published-by-code (:query-fn *service*) kind code))


(defn- gate-err
  "调用 Gate 模板校验: 通过返回归一后的模板, 失败返回 [status message]."
  [gate]
  (try (#'config/gate-template! gate)
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- guard
  "调用配置函数: 成功返回归一结果, 失败返回 [status message]."
  [f & args]
  (try (apply f args)
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- target-err
  "调用季度目标校验并返回 [status message] 或 nil (通过)."
  [body]
  (try (#'config/quarterly-target! body) nil
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- stages-err
  "调用阶段模板校验并返回 [status message] 或 nil (通过)."
  [stages]
  (try (#'config/stages! stages) nil
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


;; gate-template! 重构前的行为钉: 检查项列表与编码唯一, 阻断检查点, 必需项, 枚举与可选布尔, 纯函数.
(deftest gate-template-gates-checks-blocks-and-required
  (let [checks [{:code "C1" :title "检查一" :required true}
                {:code "C2" :title "检查二" :required false}]
        base {:code "G1" :title "关口一" :stage "execution" :required true :checks checks}]
    (testing "合法模板按词汇表归一: 类型缺省 generic, 可选键缺省不带"
      (let [row (gate-err base)]
        (is (= "G1" (:code row)))
        (is (= "generic" (:gate_type row)))
        (is (= "execution" (:stage row)))
        (is (= [true false] (mapv :required (:checks row))))
        (is (nil? (:require_released (first (:checks row)))))
        (is (= [] (:blocks row)))))
    (testing "检查项列表 1 到 30 项且编码唯一"
      (is (= [400 "关口模板需要1到30个检查项"] (gate-err (assoc base :checks []))))
      (is (= [400 "关口模板需要1到30个检查项"] (gate-err (assoc base :checks "C1"))))
      (is (= [400 "关口模板需要1到30个检查项"]
             (gate-err (assoc base :checks (vec (for [i (range 31)] {:code (str i) :title "t" :required true}))))))
      (is (= [400 "关口检查项编码重复"]
             (gate-err (assoc base :checks [{:code "C1" :title "a" :required true}
                                            {:code "C1" :title "b" :required false}])))))
    (testing "阻断检查点受控且不重复"
      (is (= [400 "阻断检查点取值不合法"] (gate-err (assoc base :blocks ["nope"]))))
      (is (= [400 "阻断检查点取值不合法"]
             (gate-err (assoc base :blocks ["test.FAT" "test.FAT"]))))
      (is (= ["test.FAT"] (:blocks (gate-err (assoc base :blocks ["test.FAT"]))))))
    (testing "必需关口至少一个必需检查项, 非必需关口不受限"
      (is (= [400 "必需关口模板至少应有一个必需检查项"]
             (gate-err (assoc base :required true :checks [{:code "C1" :title "t" :required false}]))))
      (is (map? (gate-err (assoc base :required false :checks [{:code "C1" :title "t" :required false}])))))
    (testing "关口类型与阶段受控枚举, 检查项可选布尔"
      (is (= [400 "gate_type取值不合法"] (gate-err (assoc base :gate_type "nope"))))
      (is (= [400 "stage取值不合法"] (gate-err (assoc base :stage "nope"))))
      (is (true? (:require_released (first (:checks (gate-err (assoc base :checks [{:code "C1" :title "t" :required true :require_released true}]))))))))))


(deftest template-catalog-import-publish-revise-and-retire
  (let [listing (config/listing *service* (actor 9442) "project-template")]
    (is (<= 6 (count (:catalog listing))))
    (is (some #(= "TPL-LINE" (:code %)) (:catalog listing)))
    ;; 只读用户不能维护配置.
    (is (= 403 (error-status #(config/import-catalog! *service* (actor 9442) "project-template" {:code "TPL-LINE"}))))
    (let [draft (config/import-catalog! *service* (actor 9441) "project-template" {:code "TPL-LINE"})]
      (is (= "draft" (:status draft)))
      (is (= 1 (:revision draft)))
      (is (= 100 (reduce + (map :weight (:stages draft)))))
      ;; 重复编码需走修订.
      (is (= 409 (error-status #(config/import-catalog! *service* (actor 9441) "project-template" {:code "TPL-LINE"}))))
      (let [live (config/publish! *service* (actor 9441) "project-template" (:id draft) {:reason "首版发布"})]
        (is (= "published" (:status live)))
        (is (= "published" (last (map :action (:history live)))))
        ;; 修订: 从最新版本派生草稿, 发布后旧版退役, 内容仍保留.
        (let [rev (config/revise! *service* (actor 9441) "project-template" (:id live)
                                  (-> (dissoc live :id :config_id :kind :revision :status :created_by :created_at :updated_at :created_by_name :history :previous_id)
                                      (assoc :title "整线工程订单项目 V2")))]
          (is (= 2 (:revision rev)))
          (is (= "draft" (:status rev)))
          (is (= (:id live) (:previous_id rev)))
          (config/publish! *service* (actor 9441) "project-template" (:id rev) {:reason "第二版"})
          (let [rows (:rows (config/listing *service* (actor 9441) "project-template"))
                v1 (first (filter #(= (:id live) (:id %)) rows))
                v2 (first (filter #(= (:id rev) (:id %)) rows))]
            (is (= "retired" (:status v1)))
            (is (= "整线工程订单项目" (:title v1)))
            (is (= "published" (:status v2)))
            (is (= "整线工程订单项目 V2" (:title (published "project-template" "TPL-LINE"))))
            ;; 对已退役版本修订被拒 (非最新), 退役当前版本后无生效模板.
            (is (= 409 (error-status #(config/revise! *service* (actor 9441) "project-template" (:id live) {:title "x"}))))
            (config/retire! *service* (actor 9441) "project-template" (:id rev) {:reason "停用"})
            (is (nil? (published "project-template" "TPL-LINE")))))))
    ;; 模板内容校验: 权重不为100, 非法项目类别, 非法检查点均 400.
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "project-template"
                                              {:code "BAD-1" :title "坏模板" :project_types ["line"]
                                               :stages [{:code "S1" :name "a" :weight 50}]}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "project-template"
                                              {:code "BAD-2" :title "坏模板" :project_types ["unknown"]
                                               :stages [{:code "S1" :name "a" :weight 100}]}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "project-template"
                                              {:code "BAD-3" :title "坏模板" :project_types ["line"]
                                               :stages [{:code "S1" :name "a" :weight 100}]
                                               :gate_templates [{:code "G" :title "g" :stage "execution" :required true
                                                                 :blocks ["nope"] :checks [{:code "C" :title "c" :required true}]}]}))))))


(deftest coding-rule-renders-next-code-and-enforces-format
  (let [q (:query-fn *service*)
        rule (config/create! *service* (actor 9441) "coding-rule"
                             {:code "RULE-PROJECT-STRICT" :object_type "project" :pattern "HC-{YYYY}-{SEQ:4}" :enforced true
                              :description "严格项目编号"})]
    ;; 草稿不生效: 任意编号仍可建项目.
    (is (string? (project!)))
    (config/publish! *service* (actor 9441) "coding-rule" (:id rule) {:reason "启用"})
    (let [suggestion (config/next-code *service* (actor 9441) "project" nil nil)
          year (str (.getYear (java.time.LocalDate/now)))]
      (is (re-matches (re-pattern (str "HC-" year "-[0-9]{4}")) (:code suggestion)))
      (is (true? (get-in suggestion [:rule :enforced])))
      ;; 强制规则: 不符合格式的项目编号被 400 拒绝, 符合的通过.
      (is (= 400 (error-status #(pms/create-project! *service* (actor 9441)
                                                     {:project_no "FREE-1" :name "不合规编号" :project_type "line"
                                                      :manager_id 9441 :dept_id 1}))))
      (is (= (:code suggestion) (:project_no (pms/create-project! *service* (actor 9441)
                                                                  {:project_no (:code suggestion) :name "合规编号" :project_type "line"
                                                                   :manager_id 9441 :dept_id 1})))))
    ;; 缺少流水占位符或未知占位符被拒.
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "coding-rule" {:code "R-X" :object_type "task" :pattern "T-{YYYY}"}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "coding-rule" {:code "R-Y" :object_type "task" :pattern "T-{FOO}-{SEQ:2}"}))))
    (is (some? (config/pattern-regex "DOC-{PROJECT}-{SEQ:3}")))
    (is (re-matches (config/pattern-regex "DOC-{PROJECT}-{SEQ:3}") "DOC-HC-2026-0001-007"))
    ;; 退役后不再强制.
    (config/retire! *service* (actor 9441) "coding-rule" (:id (published "coding-rule" "RULE-PROJECT-STRICT")) {:reason "停用"})
    (is (nil? (:code (config/next-code *service* (actor 9441) "project" nil nil))))
    (is (string? (:project_id (pms/create-project! *service* (actor 9441)
                                                   {:project_no (str "FREE-" (subs (str (UUID/randomUUID)) 0 6)) :name "自由编号" :project_type "line"
                                                    :manager_id 9441 :dept_id 1}))))
    (is (fn? q))))


;; batch49 (mutator 方向: cover the uncovered): 模板实例化的执行锁与节点编号占用两门.
;; batch51 (mutator 方向: cover the uncovered): config 剩余缺口 — strings! 上限, 模板 delivery 两门, 目录导入/列举, sequence-count 三分支.
(deftest config-template-arrays-delivery-gates-and-code-counts
  (let [id (project!)]
    (testing "团队角色数组超过上限400"
      (is (= 400 (error-status #(config/create! *service* (actor 9441) "project-template"
                                                {:code "TPL-BIG" :title "角色超限" :project_types ["line"]
                                                 :stages [{:code "S1" :name "a" :weight 100}]
                                                 :team_roles (vec (repeat 21 "角色"))})))))
    (testing "模板交付配置: 空数组与缺环节前置两门400"
      (is (= 400 (error-status #(config/create! *service* (actor 9441) "project-template"
                                                {:code "TPL-DELV1" :title "空环节" :project_types ["line"]
                                                 :stages [{:code "S1" :name "a" :weight 100}]
                                                 :delivery {:required_stages [] :required_test_types ["SIT"]}}))))
      (is (= 400 (error-status #(config/create! *service* (actor 9441) "project-template"
                                                {:code "TPL-DELV2" :title "缺前置" :project_types ["line"]
                                                 :stages [{:code "S1" :name "a" :weight 100}]
                                                 :delivery {:required_stages ["shipment"] :required_test_types ["SIT"]}})))))
    (testing "目录导入与列举走 coding-rule/approval-policy 分支"
      (let [draft (config/import-catalog! *service* (actor 9441) "coding-rule" {:code "RULE-SUB"})]
        (is (= "RULE-SUB" (:code draft)))
        (config/publish! *service* (actor 9441) "coding-rule" (:id draft) {:reason "启用"}))
      (is (seq (:catalog (config/listing *service* (actor 9441) "coding-rule"))))
      (is (seq (:catalog (config/listing *service* (actor 9441) "approval-policy")))))
    (testing "sequence-count 按对象类型取子项目/文档/任务现存数量"
      (doseq [code ["RULE-DOCUMENT" "RULE-TASK"]]
        (let [draft (config/import-catalog! *service* (actor 9441) "coding-rule" {:code code})]
          (config/publish! *service* (actor 9441) "coding-rule" (:id draft) {:reason "启用"})))
      (is (string? (:code (config/next-code *service* (actor 9441) "sub" id nil))))
      (is (string? (:code (config/next-code *service* (actor 9441) "document" id nil))))
      (is (string? (:code (config/next-code *service* (actor 9441) "task" id nil)))))))


;; batch56 (mutator 方向: cover the uncovered): config 校验层剩余分支 — record! 形状与类型404,
;; strings!/结构模板/Gate模板/交付配置/收尾清单的形状与枚举门, 目标与封期的数值格式门,
;; 审批策略级数, 修订两门, 冻结版退役, 目录导入404, 编码渲染占位符.
(deftest config-validation-remaining-guards
  (let [q (:query-fn *service*)]
    (testing "record! id 形状与类型匹配"
      (is (= [400 "配置ID必须为字符串"] (guard config/record! q "project-template" 123)))
      (is (= [404 "配置不存在或类型不匹配"] (guard config/record! q "project-template" (str (UUID/randomUUID)))))
      (let [rule (config/create! *service* (actor 9441) "coding-rule"
                                 {:code "RULE-KIND" :object_type "task" :pattern "T-{SEQ:2}"})]
        (is (= [404 "配置不存在或类型不匹配"] (guard config/record! q "project-template" (:id rule))))))
    (testing "strings! 非数组被拒"
      (is (= [400 "团队角色必须是最多20项的数组"] (guard #'config/strings! "roles" "团队角色" 20))))
    (testing "阶段默认工期非整数被拒"
      (is (= [400 "阶段默认工期必须是1到3650的整数"]
             (stages-err [{:code "S1" :name "n" :weight 100 :default_days 1.5}]))))
    (testing "结构模板: 形状/单机/枚举/编号唯一"
      (is (= [400 "结构模板最多50个子项目"] (guard #'config/structure! "S1")))
      (is (= [400 "结构模板最多50个子项目"]
             (guard #'config/structure! (vec (repeat 51 {:node_type "sub" :suffix "A" :name "n"})))))
      (is (= [400 "单机模板最多50台"]
             (guard #'config/structure! [{:node_type "sub" :suffix "A" :name "n" :machines "M1"}])))
      (is (= [400 "node_type取值不合法"]
             (guard #'config/structure! [{:node_type "machine" :suffix "A" :name "n"}])))
      (is (= [400 "结构编号后缀重复"]
             (guard #'config/structure! [{:node_type "sub" :suffix "A" :name "一"}
                                         {:node_type "sub" :suffix "A" :name "二"}])))
      (is (= [400 "结构编号后缀重复"]
             (guard #'config/structure! [{:node_type "sub" :suffix "A" :name "n" :machines [{:suffix "A" :name "机"}]}]))))
    (testing "Gate 模板数组与编码唯一"
      (is (= [400 "模板最多30个Gate"] (guard #'config/gate-templates! "G")))
      (is (= [400 "模板最多30个Gate"]
             (guard #'config/gate-templates!
                    (vec (repeat 31 {:code "G" :title "g" :stage "execution" :required false
                                     :checks [{:code "C" :title "c" :required true}]})))))
      (is (= [400 "Gate模板编码重复"]
             (guard #'config/gate-templates!
                    [{:code "G" :title "一" :stage "execution" :required false
                      :checks [{:code "C" :title "c" :required true}]}
                     {:code "G" :title "二" :stage "execution" :required false
                      :checks [{:code "C" :title "c" :required true}]}])))
      (is (= [400 "阻断检查点取值不合法"]
             (gate-err {:code "G1" :title "关口" :stage "execution" :required true :blocks "test.FAT"
                        :checks [{:code "C" :title "c" :required true}]}))))
    (testing "交付配置: 形状/枚举/前置链"
      (is (= [400 "适用流程与试验类型必须为非空不重复数组"]
             (guard #'config/delivery! {:required_stages "materials" :required_test_types ["SIT"]})))
      (is (= [400 "适用流程与试验类型必须为非空不重复数组"]
             (guard #'config/delivery! {:required_stages ["materials"] :required_test_types []})))
      (is (= [400 "适用流程与试验类型必须为非空不重复数组"]
             (guard #'config/delivery! {:required_stages ["materials" "materials"] :required_test_types ["SIT"]})))
      (is (= [400 "required_stages取值不合法"]
             (guard #'config/delivery! {:required_stages ["bogus"] :required_test_types ["SIT"]})))
      (is (= [400 "required_test_types取值不合法"]
             (guard #'config/delivery! {:required_stages ["materials"] :required_test_types ["BOGUS"]})))
      (is (= [400 "适用环节必须包含物料,装配,质量的必要前置"]
             (guard #'config/delivery! {:required_stages ["assembly"] :required_test_types ["SIT"]})))
      (is (= [400 "适用环节必须包含物料,装配,质量的必要前置"]
             (guard #'config/delivery! {:required_stages ["materials" "quality"] :required_test_types ["SIT"]})))
      (is (map? (#'config/delivery! {:required_stages ["materials" "assembly"] :required_test_types ["SIT"]}))))
    (testing "收尾清单: 形状/枚举/必填布尔"
      (is (= [400 "收尾清单最多30项"] (guard #'config/closure-items! "C")))
      (is (= [400 "收尾清单最多30项"]
             (guard #'config/closure-items! (vec (repeat 31 {:title "t" :required true})))))
      (is (= [400 "kind取值不合法"]
             (guard #'config/closure-items! [{:kind "bogus" :title "t"}])))
      (is (= [{:kind "check" :title "t" :required false} {:kind "handoff" :title "h" :required true}]
             (#'config/closure-items! [{:title "t" :required false} {:kind "handoff" :title "h"}]))))
    (testing "适用项目类别: 空数组与非数组"
      (is (= [400 "适用项目类别不合法"]
             (guard #'config/template! {:code "T1" :title "t" :project_types []
                                        :stages [{:code "S1" :name "a" :weight 100}]})))
      (is (= [400 "适用项目类别不合法"]
             (guard #'config/template! {:code "T2" :title "t" :project_types "line"
                                        :stages [{:code "S1" :name "a" :weight 100}]}))))
    (testing "季度目标: 数值与格式门"
      (is (= [400 "准时率目标必须是0到100"] (target-err {:year 2026 :quarter 1 :metric "on_time_rate" :target_value "abc"})))
      (is (= [400 "准时率目标必须是0到100"] (target-err {:year 2026 :quarter 1 :metric "on_time_rate" :target_value -1})))
      (is (= [400 "年份必须是2000到2100的整数"] (target-err {:year 2026.5 :quarter 1 :metric "revenue" :target_value "1"})))
      (is (= [400 "季度必须是1到4"] (target-err {:year 2026 :quarter 1.5 :metric "revenue" :target_value "1"}))))
    (testing "期间格式与费用池金额/币种"
      (is (= [400 "期间格式必须为 YYYY-MM"] (guard #'config/period! "2026-1")))
      (is (= [400 "期间格式必须为 YYYY-MM"] (guard #'config/period! 202611)))
      (is (= [400 "费用池金额必须大于0"] (guard #'config/rd-pool! {:period "2026-11" :amount 0})))
      (is (= [400 "暂仅支持CNY/USD/EUR/GBP/HKD"] (guard #'config/rd-pool! {:period "2026-11" :amount "100" :currency "JPY"}))))
    (testing "审批策略级数 1 到 6"
      (is (= [400 "审批策略需要1到6级"] (guard #'config/approval-policy! {:code "closure" :levels []})))
      (is (= [400 "审批策略需要1到6级"] (guard #'config/approval-policy! {:code "closure" :levels "L"})))
      (is (= [400 "审批策略需要1到6级"]
             (guard #'config/approval-policy! {:code "closure" :levels (vec (repeat 7 {:name "n" :rule "project_manager"}))}))))
    (testing "修订: 草稿 409 与改编码 400"
      (let [draft (config/create! *service* (actor 9441) "coding-rule"
                                  {:code "RULE-DRAFT" :object_type "task" :pattern "D-{SEQ:2}"})]
        (is (= [409 "当前版本仍是草稿, 可直接发布或作废"]
               (guard config/revise! *service* (actor 9441) "coding-rule" (:id draft)
                      {:code "RULE-DRAFT" :object_type "task" :pattern "D-{SEQ:3}"}))))
      (let [target (config/create! *service* (actor 9441) "quarterly-target"
                                   {:year 2027 :quarter 1 :metric "revenue" :target_value "100"})
            _ (config/publish! *service* (actor 9441) "quarterly-target" (:id target) {:reason "发布"})]
        (is (= [400 "修订不能改变配置编码"]
               (guard config/revise! *service* (actor 9441) "quarterly-target" (:id target)
                      {:year 2027 :quarter 1 :metric "closed_projects" :target_value 5})))))
    (testing "费用池修订发布后冻结旧版退役"
      (let [pool (config/create! *service* (actor 9441) "rd-pool"
                                 {:period "2026-12" :amount "100" :description "十二月池"})
            _ (config/publish! *service* (actor 9441) "rd-pool" (:id pool) {:reason "冻结"})
            rev (config/revise! *service* (actor 9441) "rd-pool" (:id pool)
                                {:period "2026-12" :amount "200" :description "十二月池"})
            _ (config/publish! *service* (actor 9441) "rd-pool" (:id rev) {:reason "再冻"})]
        (is (= "retired" (:status (config/record! q "rd-pool" (:id pool)))))
        (is (= "frozen" (:status (config/record! q "rd-pool" (:id rev)))))))
    (testing "目录导入: 未知项与未支持类型 404"
      (is (= [404 "目录项不存在"] (guard config/import-catalog! *service* (actor 9441) "project-template" {:code "NO-SUCH"})))
      (is (= [404 "目录项不存在"] (guard config/import-catalog! *service* (actor 9441) "rd-pool" {:code "POOL-X"}))))
    (testing "编码渲染: 占位符各分支与缺省回退"
      (let [today (java.time.LocalDate/now)
            code (config/render {:pattern "{YY}-{MM}-{TYPE}-{PROJECT}-{SEQ:3}"} {:type "line" :project_no "PRJ-1" :count 5})]
        (is (= (str (subs (str (.getYear today)) 2) "-" (format "%02d" (.getMonthValue today)) "-LINE-PRJ-1-006") code)))
      (is (= "EQUIPMENT/PRJ/01" (config/render {:pattern "{TYPE}/{PROJECT}/{SEQ:2}"} {})))
      (is (re-matches (config/pattern-regex "A-{YY}-{MM}-{SEQ:2}") "A-26-11-01"))
      (is (re-matches (config/pattern-regex "{TYPE}-{SEQ:2}") "LINE-01")))
    (testing "sequence-count 的 machine/requirement 分支与 next-code 显式类型"
      (doseq [code ["RULE-MACHINE" "RULE-REQUIREMENT"]]
        (let [draft (config/import-catalog! *service* (actor 9441) "coding-rule" {:code code})]
          (config/publish! *service* (actor 9441) "coding-rule" (:id draft) {:reason "启用"})))
      (let [id (project!)]
        (is (string? (:code (config/next-code *service* (actor 9441) "machine" id nil))))
        (is (string? (:code (config/next-code *service* (actor 9441) "requirement" id nil))))
        (let [rule (config/create! *service* (actor 9441) "coding-rule"
                                   {:code "RULE-TYPE" :object_type "project" :pattern "PR-{TYPE}-{SEQ:3}"})
              _ (config/publish! *service* (actor 9441) "coding-rule" (:id rule) {:reason "启用"})
              suggestion (config/next-code *service* (actor 9441) "project" id "line")]
          (is (string? (:code suggestion)))
          (config/retire! *service* (actor 9441) "coding-rule" (:id rule) {:reason "用完退役"}))))))


(deftest template-instantiation-guards-execution-and-node-collision
  (let [template (import-and-publish! "project-template" "TPL-EQUIPMENT")
        id (project! "equipment")
        project-no (:project_no (pms/project *service* (actor 9441) id))
        main (first (filter #(= "main" (:node_type %)) (:rows (pms/nodes *service* (actor 9441) id))))]
    (testing "结构节点编号被其它类型占用时409 (模板的单机位 M1 已被子项目占用)"
      (pms/create-node! *service* (actor 9441) id {:parent_id (:node_id main) :node_type "sub"
                                                   :node_code (str project-no "-M1") :name "抢占单机位"})
      (is (= 409 (error-status #(command! id :template-instances :create nil {:template_id (:id template)})))))
    (testing "执行开始后不可再实例化409 (状态门先于结构检查)"
      (jdbc/execute! (:db *service*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id])
      (is (= 409 (error-status #(command! id :template-instances :create nil {:template_id (:id template)})))))))


(deftest template-instantiation-builds-project-network-once
  (let [template (import-and-publish! "project-template" "TPL-EQUIPMENT")
        id (project! "equipment")
        other (project! "line")
        q (:query-fn *service*)]
    ;; 类别不匹配的项目不能套用.
    (is (= 409 (error-status #(command! other :template-instances :create nil {:template_id (:id template)}))))
    (let [instance (command! id :template-instances :create nil {:template_id (:id template) :reason "按设备模板建网"})
          nodes (:rows (pms/nodes *service* (actor 9441) id))
          plan (planning/read-plan *service* (actor 9441) id)
          ws (gov/workspace *service* (actor 9441) id)]
      (is (= "registered" (:status instance)))
      (is (= "TPL-EQUIPMENT" (:code instance)))
      (is (= (:revision template) (:template_revision instance)))
      ;; 结构: 主 + 2 子 + 1 单机.
      (is (= 3 (:node_count instance)))
      (is (= #{"main" "sub" "machine"} (set (map :node_type nodes))))
      (is (= 2 (count (filter #(= "sub" (:node_type %)) nodes))))
      ;; Gate 模板来自模板, 带类型与阻断检查点.
      (is (= 8 (:gate_template_count instance)))
      (let [kitting (first (filter #(= "kitting" (:gate_type %)) (:gate_templates ws)))]
        (is (= ["assembly.start"] (:blocks kitting)))
        (is (= "manufacturing" (:stage kitting))))
      (is (= 8 (count (:gate_progress ws))))
      (is (every? #(= "not_started" (:status %)) (:gate_progress ws)))
      ;; 计划容器: 8 个阶段 + 3 个结构节点计划, 带阶段/节点映射.
      (is (= 11 (:task_count instance)))
      (is (= 8 (count (filter :stage_code (:tasks plan)))))
      (is (= 3 (count (filter :node_id (:tasks plan)))))
      (is (every? #(= "summary" (:task_type %)) (:tasks plan)))
      ;; 交付要求与收尾清单.
      (is (true? (:delivery_configured instance)))
      (is (= 3 (:closure_item_count instance)))
      (is (= 3 (count (q :closure/items {:project_id id}))))
      ;; 只能实例化一次; 模板后续修订不影响实例快照.
      (is (= 409 (error-status #(command! id :template-instances :create nil {:template_id (:id template)}))))
      (let [rev (config/revise! *service* (actor 9441) "project-template" (:id template)
                                (-> (dissoc template :id :config_id :kind :revision :status :created_by :created_at :updated_at :created_by_name :history :previous_id)
                                    (assoc :stages [{:code "X" :name "单阶段" :weight 100}])))]
        (config/publish! *service* (actor 9441) "project-template" (:id rev) {:reason "改阶段"})
        (is (= 8 (count (:stages (first (:template_instances (gov/workspace *service* (actor 9441) id)))))))))
    ;; 未发布草稿不能应用.
    (let [draft (config/create! *service* (actor 9441) "project-template"
                                {:code "TPL-DRAFT" :title "草稿模板" :project_types ["line"]
                                 :stages [{:code "S1" :name "a" :weight 100}]})]
      (is (= 409 (error-status #(command! other :template-instances :create nil {:template_id (:id draft)})))))))


(deftest gate-catalog-creates-typed-template-and-blocks-checkpoint
  (let [id (project!) q (:query-fn *service*)
        project (pms/project *service* (actor 9441) id)
        template (command! id :gate-templates :from-catalog nil {:gate_type "kitting"})]
    (is (= "GT-KITTING" (:code template)))
    (is (= "kitting" (:gate_type template)))
    (is (= ["assembly.start"] (:blocks template)))
    (is (= 409 (error-status #(command! id :gate-templates :from-catalog nil {:gate_type "kitting"}))))
    (is (= 404 (error-status #(command! id :gate-templates :from-catalog nil {:gate_type "nope"}))))
    ;; 未通过实例前, 阻断检查点拒绝.
    (is (= 409 (error-status #(gates/checkpoint-ready! q project "assembly.start"))))
    (is (true? (gates/checkpoint-ready! q project "shipment.dispatch")))
    ;; 手工模板可声明 require_released 检查项与检查点.
    (let [manual (command! id :gate-templates :create nil
                           {:code "GT-FAT-X" :title "FAT放行" :gate_type "fat-confirm" :stage "delivery" :required true
                            :blocks ["shipment.dispatch"] :checks [{:code "F1" :title "FAT报告已发布" :required true :require_released true}]})
          document (command! id :documents :create nil {:code "FAT-RPT" :title "FAT报告" :filename "fat.txt" :content "FAT record"})
          gate (command! id :gates :create nil {:template_id (:id manual) :title "FAT放行检查" :reviewer_id 9443})]
      (is (true? (get-in manual [:checks 0 :require_released])))
      (command! id :gates :checks (:id gate) {:checks [{:code "F1" :passed true :evidence_ids [(:id document)]}]})
      ;; 证据未发布 -> 提交被拒; 发布后放行并解除阻断.
      (is (= 409 (error-status #(command! id :gates :submit (:id gate) {}))))
      (command! id :documents :submit (:id document) {:reviewer_id 9443})
      (command! 9443 id :documents :decision (:id document) {:decision "approved" :reason "发布"})
      (is (= "in_review" (:status (command! id :gates :submit (:id gate) {}))))
      (is (= 409 (error-status #(gates/checkpoint-ready! q project "shipment.dispatch"))))
      (is (= "approved" (:status (command! 9443 id :gates :decision (:id gate) {:decision "approved" :reason "放行"}))))
      (is (true? (gates/checkpoint-ready! q project "shipment.dispatch")))
      (let [progress (first (filter #(= "GT-FAT-X" (:code %)) (:gate_progress (gov/workspace *service* (actor 9441) id))))]
        (is (= "approved" (:status progress)))
        (is (= 1 (:passed_checks progress)))
        (is (true? (:passed progress)))))))


;; stages! 重构前的行为钉: 数组门/逐阶段三门/唯一性/合计与组装形状, 纯函数不依赖数据库.
(deftest stages-validation-gates-weights-levels-and-uniqueness
  (testing "合法模板按 cond-> 组装, 可选键缺省不带, levels 去重"
    (is (= [{:code "S1" :name "启动" :weight 60 :gate_type "gate-a" :levels ["main" "sub"] :default_days 5}
            {:code "S2" :name "交付" :weight 40}]
           (#'config/stages! [{:code "S1" :name "启动" :weight 60 :gate_type "gate-a"
                               :levels ["main" "sub" "sub"] :default_days 5}
                              {:code "S2" :name "交付" :weight 40}]))))
  (testing "阶段数组必须是 1 到 20 项"
    (is (= [400 "模板需要1到20个阶段"] (stages-err [])))
    (is (= [400 "模板需要1到20个阶段"] (stages-err "S1")))
    (is (= [400 "模板需要1到20个阶段"]
           (stages-err (vec (repeat 21 {:code "S" :name "n" :weight 100}))))))
  (testing "权重必须是 0 到 100 的整数"
    (is (= [400 "阶段权重必须是0到100的整数"] (stages-err [{:code "S1" :name "n" :weight 101}])))
    (is (= [400 "阶段权重必须是0到100的整数"] (stages-err [{:code "S1" :name "n" :weight 1.5}])))
    (is (= [400 "阶段权重必须是0到100的整数"] (stages-err [{:code "S1" :name "n"}]))))
  (testing "派生层级必须是非空的 main/sub/machine 数组"
    (is (= [400 "阶段派生层级只能是 main/sub/machine"]
           (stages-err [{:code "S1" :name "n" :weight 100 :levels "main"}])))
    (is (= [400 "阶段派生层级只能是 main/sub/machine"]
           (stages-err [{:code "S1" :name "n" :weight 100 :levels []}])))
    (is (= [400 "阶段派生层级只能是 main/sub/machine"]
           (stages-err [{:code "S1" :name "n" :weight 100 :levels ["bogus"]}]))))
  (testing "默认工期必须是 1 到 3650 的整数"
    (is (= [400 "阶段默认工期必须是1到3650的整数"]
           (stages-err [{:code "S1" :name "n" :weight 100 :default_days 0}])))
    (is (= [400 "阶段默认工期必须是1到3650的整数"]
           (stages-err [{:code "S1" :name "n" :weight 100 :default_days 3651}]))))
  (testing "阶段字段白名单与必填文本"
    (is (= [400 "请求包含不支持或不可修改的字段"]
           (stages-err [{:code "S1" :name "n" :weight 100 :evil 1}])))
    (is (= [400 "code不能为空或超出长度限制"] (stages-err [{:code "" :name "n" :weight 100}]))))
  (testing "编码唯一与权重合计 100"
    (is (= [400 "阶段编码重复"]
           (stages-err [{:code "S1" :name "a" :weight 50} {:code "S1" :name "b" :weight 50}])))
    (is (= [400 "阶段权重合计必须等于100"]
           (stages-err [{:code "S1" :name "a" :weight 30} {:code "S2" :name "b" :weight 30}])))))


;; quarterly-target! 重构前的行为钉: 四指标分支, 年季范围, 金额/币种/比率门控, 纯函数不依赖数据库.
(deftest quarterly-target-validates-every-metric-branch
  (testing "四个指标的合法目标值按各自口径归一"
    (is (= {:code "2026Q3-revenue" :year 2026 :quarter 3 :metric "revenue"
            :target_value "5000.00" :basis "测试" :currency "CNY"}
           (#'config/quarterly-target! {:year 2026 :quarter 3 :metric "revenue" :target_value "5000" :basis "测试"})))
    (is (= "5000.00" (:target_value (#'config/quarterly-target! {:year 2026 :quarter 3 :metric "gross_margin" :target_value "5000"}))))
    (is (= 7 (:target_value (#'config/quarterly-target! {:year 2026 :quarter 3 :metric "closed_projects" :target_value 7}))))
    (is (= 99.5 (:target_value (#'config/quarterly-target! {:year 2026 :quarter 3 :metric "on_time_rate" :target_value 99.5}))))
    (is (nil? (:currency (#'config/quarterly-target! {:year 2026 :quarter 3 :metric "closed_projects" :target_value 7})))))
  (testing "年份与季度范围"
    (is (= [400 "年份必须是2000到2100的整数"] (target-err {:year 1999 :quarter 1 :metric "revenue" :target_value "1"})))
    (is (= [400 "季度必须是1到4"] (target-err {:year 2026 :quarter 5 :metric "revenue" :target_value "1"}))))
  (testing "指标受控枚举"
    (is (= 400 (first (target-err {:year 2026 :quarter 1 :metric "bogus" :target_value "1"})))))
  (testing "结项数与准时率的数值门"
    (is (= [400 "结项数目标必须是非负整数"] (target-err {:year 2026 :quarter 1 :metric "closed_projects" :target_value 1.5})))
    (is (= [400 "结项数目标必须是非负整数"] (target-err {:year 2026 :quarter 1 :metric "closed_projects" :target_value -1})))
    (is (= [400 "准时率目标必须是0到100"] (target-err {:year 2026 :quarter 1 :metric "on_time_rate" :target_value 101}))))
  (testing "金额与币种门"
    (is (= [400 "目标金额不得为负数"] (target-err {:year 2026 :quarter 1 :metric "revenue" :target_value "-1"})))
    (is (= [400 "暂仅支持CNY/USD/EUR/GBP/HKD"] (target-err {:year 2026 :quarter 1 :metric "revenue" :target_value "1" :currency "JPY"})))))


(deftest quarterly-target-rd-pool-and-period-lock-lifecycle
  (let [target (config/create! *service* (actor 9441) "quarterly-target"
                               {:year 2026 :quarter 4 :metric "revenue" :target_value "1500000" :basis "年度经营计划分解"})]
    (is (= "2026Q4-revenue" (:code target)))
    (is (= "1500000.00" (:target_value target)))
    (is (= "CNY" (:currency target)))
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "quarterly-target" {:year 2026 :quarter 5 :metric "revenue" :target_value "1"}))))
    (is (= 400 (error-status #(config/create! *service* (actor 9441) "quarterly-target" {:year 2026 :quarter 1 :metric "closed_projects" :target_value 1.5}))))
    (config/publish! *service* (actor 9441) "quarterly-target" (:id target) {:reason "下达"})
    ;; 修订不改写历史: 旧版退役仍保留原目标值.
    (let [rev (config/revise! *service* (actor 9441) "quarterly-target" (:id target) {:year 2026 :quarter 4 :metric "revenue" :target_value "1800000"})]
      (config/publish! *service* (actor 9441) "quarterly-target" (:id rev) {:reason "上调"})
      (let [rows (:rows (config/listing *service* (actor 9441) "quarterly-target"))]
        (is (= "1500000.00" (:target_value (first (filter #(= (:id target) (:id %)) rows)))))
        (is (= "retired" (:status (first (filter #(= (:id target) (:id %)) rows)))))
        (is (= "1800000.00" (:target_value (published "quarterly-target" "2026Q4-revenue")))))))
  (let [pool (config/create! *service* (actor 9441) "rd-pool" {:period "2026-09" :amount "50000" :currency "CNY" :description "九月研发池"})]
    (is (= "POOL-2026-09" (:code pool)))
    (is (= "frozen" (:status (config/publish! *service* (actor 9441) "rd-pool" (:id pool) {:reason "冻结"})))))
  (let [lock (config/create! *service* (actor 9441) "period-lock" {:period "2026-08" :reason "八月工时封期"})]
    (is (= "locked" (:status lock)))
    (is (= 400 (error-status #(config/revise! *service* (actor 9441) "period-lock" (:id lock) {:period "2026-08" :reason "x"}))))
    (is (= "retired" (:status (config/retire! *service* (actor 9441) "period-lock" (:id lock) {:reason "解锁"}))))))


(defn- request
  [method path uid payload]
  (let [req (cond-> (-> (mock/request method path) (mock/content-type "application/json")
                        (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              payload (mock/body (json/generate-string payload)))
        response (*handler* req) raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw
                   (bytes? raw) (String. ^bytes raw "UTF-8") :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))


(deftest config-http-contract
  (is (= 401 (:status (request :get "/api/pms/config/project-template" nil nil))))
  (is (= 200 (:status (request :get "/api/pms/config/project-template" 9442 nil))))
  (is (= 403 (:status (request :post "/api/pms/config/project-template/import" 9442 {:code "TPL-SERVICE"}))))
  (let [created (request :post "/api/pms/config/project-template/import" 9441 {:code "TPL-SERVICE"})
        id (get-in created [:body :data :id])]
    (is (= 200 (:status created)))
    (is (= 200 (:status (request :post (str "/api/pms/config/project-template/" id "/publish") 9441 {:reason "http"}))))
    (is (= 409 (:status (request :post (str "/api/pms/config/project-template/" id "/publish") 9441 {:reason "again"}))))
    (is (= 400 (:status (request :get "/api/pms/config/unknown-kind" 9441 nil))))
    (let [next (request :get "/api/pms/coding-rules/next?object_type=project" 9441 nil)]
      (is (= 200 (:status next)))
      (is (nil? (get-in next [:body :data :code]))))))


(deftest external-catalog-structure-invariants
  "静态外部接口目录只读视图的结构不变量: 计数一致, 状态受控, 每行字段完整."
  (let [cat (external/catalog nil nil)
        rows (:rows cat)]
    (is (true? (:available cat)))
    (is (= (count rows) (:total cat)))
    (is (= 22 (:total cat)))
    (is (= (:total cat) (+ (:adapter-count cat) (:business-count cat))))
    (is (= (:total cat) (+ (:contract-count cat) (:rule-count cat))))
    (is (= (:total cat) (reduce + (map :count (:by-status cat)))))
    (is (= (:total cat) (reduce + (map :count (:by-system cat)))))
    ;; 状态取值受控.
    (is (= #{"待合同" "待规则"} (set (map :status rows))))
    ;; 归类取值受控.
    (is (= #{"adapter" "business"} (set (map :group rows))))
    ;; key 唯一.
    (is (= (count rows) (count (distinct (map :key rows)))))
    ;; 每行字段完整且所需接口字段非空.
    (doseq [r rows]
      (is (every? #(and (contains? r %) (some? (% r)))
                  [:key :capability :system :group :matrix-rows :direction :required-fields :owner :status :notes]))
      (is (seq (:required-fields r)))
      (is (every? (complement str/blank?) (:required-fields r)))
      (is (re-matches #"[0-9A-Z]{2,4}(, ?[0-9A-Z]{2,4})*" (:matrix-rows r))))))


(deftest external-catalog-http-contract
  "外部接口目录只读 HTTP 合同: 未登录 401, 登录只读用户 200, 信封 data.total 与结构一致."
  (is (= 401 (:status (request :get "/api/pms/external-interfaces" nil nil))))
  (let [resp (request :get "/api/pms/external-interfaces" 9442 nil)
        data (:data (:body resp))]
    (is (= 200 (:status resp)))
    (is (= 22 (:total data)))
    (is (= 22 (count (:rows data))))
    (is (= (:total data) (+ (:adapter-count data) (:business-count data))))
    (is (= (:total data) (+ (:contract-count data) (:rule-count data))))))
