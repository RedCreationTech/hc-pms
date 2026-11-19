(ns com.ruoyi.pms-direct-ship-test
  "D04 层流罩直发与 D05 非层流罩直发: 任务发起, 物料编码关联采购订单, 既有发运工单与
   已发运冲突检查, 独立审批与外部对账状态如实标注的真实数据库测试."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.delivery :as delivery]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.service :as pms]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc])
  (:import
    (java.nio.file
      Files)
    (java.util
      UUID)))


(def ^:dynamic *service* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9650,'Direct ship test','pms-direct-ship-test',61,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9650,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (doseq [id [9651 9652 9653]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "direct-ship-test-" id) (str "直发测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,9650)" id])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-direct-ship-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (seed! db)
      (binding [*service* {:db db :query-fn (query-function db)}] (f))
      (finally (when file (Files/deleteIfExists file))))))


(use-fixtures :once database-fixture)


(defn- actor
  [uid]
  (pms/actor *service* {:user-id uid}))


(defn- error-status
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- error-info
  "提取业务错误状态与文案, 成功时返回 nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- version
  [id]
  (:version (pms/project *service* (actor 9651) id)))


(defn- gov!
  ([id resource action rid body] (gov! 9651 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- command!
  ([id resource action rid body] (command! 9651 id resource action rid body))
  ([uid id resource action rid body]
   (:result (delivery/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- transition!
  [id status]
  (pms/transition-project! *service* (actor 9651) id {:version (version id) :status status :reason "直发集成测试"}))


(defn- approve!
  [id resource rid]
  (gov! id resource :submit rid {:reviewer_id 9652})
  (gov! 9652 id resource :decision rid {:decision "approved" :reason "独立审查通过"}))


(defn- context!
  "建立执行中的执行阶段项目: 主项目/单元/单机, 独立审批人 9652 与第三方 9653 为只读成员."
  []
  (let [project (pms/create-project! *service* (actor 9651)
                                     {:project_no (str "DSR-" (subs (str (UUID/randomUUID)) 0 8)) :name "直发闭环验证"
                                      :customer "本地测试" :contract_no "DSR-TEST" :project_type "equipment"
                                      :manager_id 9651 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id project)
        _ (pms/set-member! *service* (actor 9651) id {:user_id 9652 :role "viewer"})
        _ (pms/set-member! *service* (actor 9651) id {:user_id 9653 :role "viewer"})
        main (first (filter #(= "main" (:node_type %)) (:rows (pms/nodes *service* (actor 9651) id))))
        sub (pms/create-node! *service* (actor 9651) id {:parent_id (:node_id main) :node_type "sub" :node_code "U1" :name "主机单元"})
        machine (pms/create-node! *service* (actor 9651) id {:parent_id (:node_id sub) :node_type "machine" :node_code "M1" :name "主机#1"})
        other (pms/create-node! *service* (actor 9651) id {:parent_id (:node_id sub) :node_type "machine" :node_code "M2" :name "主机#2"})
        document (gov! id :documents :create nil {:code "DSR-EV" :title "直发证据" :filename "dsr.txt" :content "直发记录"})
        req (gov! id :requirements :create nil {:code "URS-DS" :text "直发须可追溯" :category "交付" :priority "required" :owner_id 9651})
        task (:result (planning/create-task! *service* (actor 9651) id
                                             {:version (version id) :wbs_code "D-1" :name "直发任务" :owner_id 9651
                                              :start_date "2026-09-22" :duration_days 1 :node_id (:node_id machine)}))
        task2 (:result (planning/create-task! *service* (actor 9651) id
                                              {:version (version id) :wbs_code "D-2" :name "兄弟设备任务" :owner_id 9651
                                               :start_date "2026-09-22" :duration_days 1 :node_id (:node_id other)}))
        charter (gov! id :charters :create nil {:title "章程" :objective "目标" :scope "范围" :success_criteria "准则" :sponsor_id 9653})
        template (gov! id :gate-templates :create nil {:code "EXEC-DS" :title "执行前确认" :stage "execution" :required true
                                                       :checks [{:code "C" :title "证据齐全" :required true}]})
        gate (gov! id :gates :create nil {:template_id (:id template) :title "执行确认" :reviewer_id 9652})]
    (command! id :configuration :update nil {:required_stages ["materials" "assembly" "quality" "shipment"]
                                             :required_test_types ["SIT" "FAT" "SAT"] :reason "直发测试配置"})
    (approve! id :charters (:id charter))
    (gov! id :gates :checks (:id gate) {:checks [{:code "C" :passed true :evidence_ids [(:id document)]}]})
    (gov! id :gates :submit (:id gate) {})
    (gov! 9652 id :gates :decision (:id gate) {:decision "approved" :reason "独立验证"})
    (transition! id "initiated")
    (transition! id "planning")
    (let [baseline (:result (planning/submit-plan! *service* (actor 9651) id {:version (version id) :comment "提交"}))]
      (planning/review-plan! *service* (actor 9652) id (:baseline_id baseline) {:version (version id) :decision "approved" :comment "独立确认"}))
    (transition! id "execution")
    {:id id :evidence (:id document) :requirement_ids [(:id req)]
     :task_id (:task_id task) :task2_id (:task_id task2) :machine machine :other other}))


(defn- refs
  [ctx]
  (select-keys ctx [:task_id :requirement_ids]))


(defn- workspace
  [id]
  (delivery/workspace *service* (actor 9651) id))


(defn- create!
  ([ctx body] (create! ctx body (refs ctx)))
  ([ctx body extra]
   (command! (:id ctx) :direct-ships :create nil (merge extra body))))


(deftest laminar-direct-ship-requires-task-source-and-independent-approval
  (testing "层流罩直发: 由任务发起, 类型与节点归属固定, 独立审批后外部状态如实标注"
    (let [ctx (context!) id (:id ctx)
          request (create! ctx {:code "DS-L1" :title "层流罩直发" :direct_ship_type "laminar" :owner_id 9651
                                :assembly_requirement "随货附装配说明" :ship_address "客户现场 A 区"
                                :items [{:code "LF-1" :name "层流罩" :quantity 1 :unit "台"}]})]
      (is (= "draft" (:status request)))
      (is (= "laminar" (:direct_ship_type request)))
      (is (= (:task_id ctx) (:task_id request)))
      (is (= (:node_id (:machine ctx)) (:node_id request)))
      (is (= "not_configured" (:external_sync_status request)))
      (testing "任务来源必须真实存在且属于本项目"
        (is (= 400 (error-status #(create! ctx {:code "DS-L0" :title "无任务" :direct_ship_type "laminar" :owner_id 9651
                                                :items [{:code "LF-1" :name "罩" :quantity 1 :unit "台"}]}
                                           {})))
            (is (= 404 (error-status #(create! ctx {:code "DS-L0B" :title "错任务" :direct_ship_type "laminar" :owner_id 9651
                                                    :task_id "no-such-task"
                                                    :items [{:code "LF-1" :name "罩" :quantity 1 :unit "台"}]})))))
        (is (nil? (first (filter #(= "DS-L0" (:code %)) (:direct_ship_ledgers (workspace id)))))))
      (testing "类型白名单与物料清单校验"
        (is (= 400 (error-status #(command! id :direct-ships :create nil
                                            (merge (refs ctx) {:code "DS-L9" :title "非法类型" :direct_ship_type "silo" :owner_id 9651
                                                               :items [{:code "X" :name "件" :quantity 1 :unit "个"}]})))))
        (is (= 400 (error-status #(create! ctx {:code "DS-L8" :title "重复编码" :direct_ship_type "laminar" :owner_id 9651
                                                :items [{:code "LF-1" :name "罩" :quantity 1 :unit "台"}
                                                        {:code "LF-1" :name "罩" :quantity 1 :unit "台"}]})))))
      (testing "提交须指定独立审核人且审批人不得为提交人"
        (is (= 409 (error-status #(command! id :direct-ships :submit (:id request)
                                            {:reviewer_id 9651 :evidence_ids [(:evidence ctx)]})))))
      (let [submitted (command! id :direct-ships :submit (:id request) {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})]
        (is (= "in_review" (:status submitted)))
        (is (= 403 (error-status #(command! 9653 id :direct-ships :decision (:id request) {:decision "approved" :reason "冒名"}))))
        (testing "审批中内容不可再改"
          (is (= 409 (error-status #(command! id :direct-ships :update (:id request)
                                              {:title "改标题" :direct_ship_type "laminar" :owner_id 9651})))))
        (let [approved (command! 9652 id :direct-ships :decision (:id request) {:decision "approved" :reason "同意直发"})]
          (is (= "approved" (:status approved)))
          (is (= "not_configured" (:external_sync_status approved)))))
      (testing "工作台汇总按类别统计并把待对账冲突如实暴露"
        (let [summary (:direct_ship_summary (workspace id))]
          (is (= 1 (:total summary)))
          (is (= 1 (:laminar summary)))
          (is (= 1 (:approved summary)))
          (is (= "not_configured" (:external_sync_status summary))))
        (is (some #(= "DS-L1" (:code %)) (:direct_ship_ledgers (workspace id))))
        (is (some #(and (= "DS-L1" (:code %))
                        (= "approved" (:status %)))
                  (get (:task_links (workspace id)) (:task_id ctx))))))))


(deftest non-laminar-direct-ship-requires-purchase-orders-per-item-code
  (testing "非层流罩直发: 物料编码必须关联采购订单, 缺失在提交与批准两处都被拒绝"
    (let [ctx (context!) id (:id ctx)
          items [{:code "NP-1" :name "箱体" :quantity 2 :unit "台"}
                 {:code "NP-2" :name "风机" :quantity 4 :unit "台"}]]
      (is (= 400 (error-status #(create! ctx {:code "DS-N0" :title "无订单" :direct_ship_type "non_laminar" :owner_id 9651
                                              :items items}))))
      (is (= 400 (error-status #(create! ctx {:code "DS-N0" :title "订单非文本" :direct_ship_type "non_laminar" :owner_id 9651
                                              :purchase_orders {"NP-1" 1 "NP-2" 2} :items items}))))
      (let [request (create! ctx {:code "DS-N1" :title "非层流罩直发" :direct_ship_type "non_laminar" :owner_id 9651
                                  :purchase_orders {"NP-1" "PO-1001" "NP-2" "PO-1002"
                                                    "UNKNOWN" "PO-9999"} :items items})]
        (is (= "draft" (:status request)))
        (is (= {"NP-1" "PO-1001" "NP-2" "PO-1002" "UNKNOWN" "PO-9999"} (:purchase_orders request)))
        (testing "清单内每个编码都必须有订单, 只缺一项也阻断提交并点名缺料编码"
          (let [partial (command! id :direct-ships :update (:id request)
                                  {:title "非层流罩直发" :direct_ship_type "non_laminar" :owner_id 9651
                                   :purchase_orders {"NP-1" "PO-1001"} :items items})
                blocked (error-info #(command! id :direct-ships :submit (:id partial)
                                               {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]}))]
            (is (= 409 (first blocked)))
            (is (re-find #"NP-2" (second blocked)))))
        (testing "补齐订单后可以提交并由独立审核人批准"
          (command! id :direct-ships :update (:id request)
                    {:title "非层流罩直发" :direct_ship_type "non_laminar" :owner_id 9651
                     :purchase_orders {"NP-1" "PO-1001" "NP-2" "PO-1002"} :items items})
          (command! id :direct-ships :submit (:id request) {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})
          (let [approved (command! 9652 id :direct-ships :decision (:id request) {:decision "approved" :reason "订单齐备"})]
            (is (= "approved" (:status approved)))
            (is (= "PO-1002" (get (:purchase_orders approved) "NP-2")))
            (is (= "not_configured" (:external_sync_status approved)))))))))


(deftest existing-work-order-and-dispatched-shipment-block-direct-ship
  (testing "既有发运工单与已发运事实阻断直发协调, 且不影响兄弟设备"
    (let [ctx (context!) id (:id ctx)
          bom (let [material (command! id :material-requests :create nil
                                       (merge (refs ctx) {:code "MR-DS" :title "直发物料" :request_type "standard" :owner_id 9651
                                                          :needed_on "2026-10-01"
                                                          :items [{:code "M-1" :name "件" :quantity 1 :unit "个"}]}))]
                (command! id :material-requests :submit (:id material) {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})
                (command! 9652 id :material-requests :decision (:id material) {:decision "approved" :reason "独立核实"})
                (let [bom (command! id :boms :create nil {:code "BOM-DS" :title "直发配置" :material_request_id (:id material)})]
                  (command! id :boms :freeze (:id bom) {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})
                  (command! 9652 id :boms :decision (:id bom) {:decision "approved" :reason "冻结"})
                  (command! id :boms :kit (:id bom) {:items [{:code "M-1" :available_quantity 1}] :evidence_ids [(:evidence ctx)]})
                  (first (filter #(= "BOM-DS" (:code %)) (:boms (workspace id))))))
          assembly (command! id :assemblies :create nil (merge (refs ctx) {:code "AS-DS" :title "直发装配" :bom_id (:id bom) :owner_id 9651}))]
      (command! id :assemblies :start (:id assembly) {:evidence_ids [(:evidence ctx)]})
      (command! id :assemblies :submit (:id assembly) {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})
      (command! 9652 id :assemblies :decision (:id assembly) {:decision "approved" :reason "交检通过"})
      ;; 同节点登记发运准备记录 (即既有发运工单), 尚未发运.
      (command! id :shipments :create nil (merge (refs ctx) {:code "SH-DS" :title "既有发运工单"
                                                             :assembly_ids [(:id assembly)]
                                                             :consignee "客户" :delivery_address "客户现场"
                                                             :planned_date "2026-11-01" :reviewer_id 9652}))
      (let [request (create! ctx {:code "DS-C1" :title "冲突直发" :direct_ship_type "laminar" :owner_id 9651
                                  :items [{:code "LF-C" :name "罩" :quantity 1 :unit "台"}]})]
        (testing "既有发运工单阻断直发提交"
          (is (= 409 (error-status #(command! id :direct-ships :submit (:id request)
                                              {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})))))
        (testing "冲突清单如实列出既有工单, 汇总计入冲突数"
          (let [ledger (first (filter #(= "DS-C1" (:code %)) (:direct_ship_ledgers (workspace id))))]
            (is (= 1 (count (:existing_work_orders ledger))))
            (is (= "SH-DS" (:code (first (:existing_work_orders ledger)))))
            (is (true? (:blocked ledger))))
          (is (= 1 (:conflicts (:direct_ship_summary (workspace id)))))))
      (testing "兄弟设备无既有工单, 直发不受影响"
        (let [sibling (command! id :direct-ships :create nil
                                (merge {:task_id (:task2_id ctx) :requirement_ids (:requirement_ids ctx)}
                                       {:code "DS-C2" :title "兄弟设备直发" :direct_ship_type "laminar" :owner_id 9651
                                        :items [{:code "LF-C2" :name "罩" :quantity 1 :unit "台"}]}))]
          (is (= (:node_id (:other ctx)) (:node_id sibling)))
          (is (= "in_review" (:status (command! id :direct-ships :submit (:id sibling)
                                                {:reviewer_id 9652 :evidence_ids [(:evidence ctx)]})))))))))
