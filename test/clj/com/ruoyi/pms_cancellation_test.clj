(ns com.ruoyi.pms-cancellation-test
  "B17 审批驱动的项目/单机取消: 须关联已批准的外部变更依据, 冻结受影响对象, 审批通过后停止后续下发并列出待补偿对账项.
   外部系统真实补偿与 OA/ERP 回执未对接, 本地如实标注 not_configured."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.cancellation :as cancellation]
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
    (java.nio.file
      Files)
    (java.util
      UUID)))


(def ^:dynamic *service* nil)
(def ^:dynamic *handler* nil)
(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))

(def ^:private quality-role 9900)
(def ^:private owner 9901)
(def ^:private approver 9902)
(def ^:private outsider 9903)


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9900,'Cancel owner','pms-cancel-owner',90,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9900,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  ;; 审批人只有项目只读功能权限, 取消审批不应要求编辑权 (pms:project:transition).
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9902,'Cancel approver','pms-cancel-approver',91,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9902,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:quality:approve')"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9903,'Cancel outsider','pms-cancel-outsider',92,'0','0')"])
  (doseq [[id role] [[9901 9900] [9902 9902] [9903 9903]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "cancel-test-" id) (str "取消测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-cancellation-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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


(defn- request
  [method path uid payload]
  (let [req (cond-> (-> (mock/request method path) (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              payload (-> (mock/content-type "application/json") (mock/body (json/generate-string payload))))
        response (*handler* req)
        raw (:body response)
        text (cond (map? raw) (json/generate-string raw) (string? raw) raw
                   (bytes? raw) (String. ^bytes raw "UTF-8") :else (slurp raw))]
    {:status (:status response) :body (json/parse-string text true)}))


(defn- version
  [id]
  (:version (pms/project *service* (actor owner) id)))


(defn- gov!
  ([id resource action rid body] (gov! owner id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id))))))


(defn- create-cancel!
  [uid id body]
  (request :post (str "/api/pms/projects/" id "/cancellations") uid (assoc body :version (version id))))


(defn- command!
  ([id rid action body] (command! owner id rid action body))
  ([uid id rid action body]
   (request :post (str "/api/pms/projects/" id "/cancellations/" rid "/" action) uid
            (assoc body :version (version id)))))


(defn- result!
  "读取业务命令返回结果的平铺字段."
  [resp]
  (get-in resp [:body :data :result]))


(defn- error-status
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))


(defn- transition!
  [id status]
  (pms/transition-project! *service* (actor owner) id {:version (version id) :status status :reason "取消测试"}))


(defn- approved-change!
  "登记并独立批准一条外部变更, 作为取消依据."
  [id]
  (let [change (gov! id :changes :create nil {:title "客户变更取消单机" :reason "客户要求取消"
                                              :scope_impact "单机 M1 下线"
                                              :schedule_impact "工期缩短" :cost_impact "成本下降"
                                              :quality_impact "无" :resource_impact "人力释放"})
        _ (gov! id :changes :submit (:id change) {:reviewer_id approver})
        _ (gov! approver id :changes :decision (:id change) {:decision "approved" :reason "客户确认"})]
    change))


(defn- context!
  "执行中项目 + 子项目/单机结构 + 单机上未完成任务 + 已批准变更依据."
  []
  (let [project (pms/create-project! *service* (actor owner)
                                     {:project_no (str "CX-" (subs (str (UUID/randomUUID)) 0 8)) :name "取消验证"
                                      :customer "本地客户" :contract_no "CX-TEST" :project_type "equipment"
                                      :manager_id owner :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id project)
        _ (pms/set-member! *service* (actor owner) id {:user_id approver :role "viewer"})
        main (first (filter #(= "main" (:node_type %)) (:rows (pms/nodes *service* (actor owner) id))))
        sub (pms/create-node! *service* (actor owner) id {:parent_id (:node_id main) :node_type "sub" :node_code "U1" :name "主机单元"})
        machine (pms/create-node! *service* (actor owner) id {:parent_id (:node_id sub) :node_type "machine" :node_code "M1" :name "主机#1"})
        other (pms/create-node! *service* (actor owner) id {:parent_id (:node_id sub) :node_type "machine" :node_code "M2" :name "主机#2"})
        task (planning/create-task! *service* (actor owner) id
                                    {:version (version id) :wbs_code "C-1" :name "单机装配"
                                     :owner_id owner :start_date "2026-09-22" :duration_days 3
                                     :node_id (:node_id machine)})
        other-task (planning/create-task! *service* (actor owner) id
                                          {:version (version id) :wbs_code "C-2" :name "另一台装配"
                                           :owner_id owner :start_date "2026-09-22" :duration_days 3
                                           :node_id (:node_id other)})
        change (approved-change! id)]
    {:id id :machine machine :other other :task task :other-task other-task :change change}))


(deftest create-requires-approved-change-and-valid-scope
  (let [{:keys [id machine change]} (context!)]

    (testing "缺变更依据或未批准的变更不能发起取消"
      (is (= 400 (:status (create-cancel! owner id
                                          {:version (version id) :target "node" :node_id (:node_id machine)
                                           :reason "客户取消"}))))
      (let [draft (gov! id :changes :create nil {:title "未批准变更" :reason "草稿"
                                                 :scope_impact "无" :schedule_impact "无"
                                                 :cost_impact "无" :quality_impact "无"
                                                 :resource_impact "无"})]
        (is (= 409 (:status (create-cancel! owner id
                                            {:version (version id) :target "node" :node_id (:node_id machine)
                                             :basis_change_id (:id draft) :reason "客户取消"}))))))
    (testing "未知结构节点 404"
      (is (= 404 (:status (create-cancel! owner id
                                          {:version (version id) :target "node" :node_id "no-such-node"
                                           :basis_change_id (:id change) :reason "客户取消"})))))
    (testing "按单机登记取消申请, 冻结受影响节点, 外部补偿如实标注未配置"
      (let [created (request :post (str "/api/pms/projects/" id "/cancellations") owner
                             {:version (version id) :target "node" :node_id (:node_id machine)
                              :basis_change_id (:id change) :reason "客户取消单机 M1" :source "local"
                              :items [{:kind "node" :id (:node_id machine)}]})
            record (result! created)]
        (is (= 200 (:status created))
            (str "建单失败: " (:body created) " machine=" (:node_id machine)
                 " nodes=" (pr-str (mapv #(select-keys % [:node_id :node_code :parent_id])
                                         (:rows (pms/nodes *service* (actor owner) id))))))
        (is (= "pending" (:status record)))
        (is (= "not_applicable" (:source_sync record)))
        (is (= "not_configured" (:external_compensation record)))
        (is (= ["M1"] (mapv :code (:affected_nodes record))))
        (testing "引用不属于取消范围的对象被拒 409"
          (is (= 409 (:status (create-cancel! owner id
                                              {:version (version id) :target "node" :node_id (:node_id machine)
                                               :basis_change_id (:id change) :reason "越范围"
                                               :items [{:kind "task" :id "not-a-task"}]})))))))))


(deftest scope-excludes-unrelated-machine
  (let [{:keys [id machine other change]} (context!)]
    (let [record (result! (request :post (str "/api/pms/projects/" id "/cancellations") owner
                                   {:version (version id) :target "node" :node_id (:node_id machine)
                                    :basis_change_id (:id change) :reason "客户取消单机 M1" :source "local"}))]
      (testing "取消范围只含目标单机, 不含同级单机"
        (is (= #{"M1"} (set (map :code (:affected_nodes record)))))
        (is (not (contains? (set (map :code (:affected_nodes record))) (:node_code other)))))
      (testing "提交前可撤回, 撤回后不能重复撤回"
        (is (= 200 (:status (command! id (:id record) "withdraw" {:reason "客户暂缓"}))))
        (is (= 409 (:status (command! id (:id record) "withdraw" {:reason "再次撤回"}))))))))


(deftest approval-stops-dispatch-and-lists-compensation
  (let [{:keys [id machine change]} (context!)]
    (let [record (result! (request :post (str "/api/pms/projects/" id "/cancellations") owner
                                   {:version (version id) :target "node" :node_id (:node_id machine)
                                    :basis_change_id (:id change) :reason "客户取消单机 M1" :source "oa"
                                    :reference_no "OA-2026-001"}))]
      ;; 外部依据来源如实标注未对接.
      (is (= "not_configured" (:source_sync record)))
      (testing "提交人不能自任审批人; 提交后停止下发生效需审批通过"
        (is (= 409 (:status (command! id (:id record) "submit" {:reviewer_id owner}))))
        (let [submitted (command! id (:id record) "submit" {:reviewer_id approver :reason "提交取消"})]
          (is (= 200 (:status submitted)) (str "提交失败: " (:body submitted)))
          (is (= "submitted" (get-in submitted [:body :data :result :status])))
          (is (empty? (cancellation/cancelled-node-ids (:query-fn *service*) (pms/project *service* (actor owner) id)))
              "未批准前不应停止任何下发")))
      (testing "指定只读审批人批准后停止下发, 冻结受影响任务与待补偿项"
        (let [approved (command! approver id (:id record) "review" {:decision "approved" :reason "客户确认取消"})
              data (get-in approved [:body :data :result])]
          (is (= 200 (:status approved)) (str "审批失败: " (:body approved)))
          (is (= "approved" (:status data)))
          (is (true? (:dispatch_stopped data)))
          (is (= 1 (:stopped_tasks data)))
          (is (= "not_configured" (:external_compensation data)))
          (is (contains? (cancellation/cancelled-node-ids (:query-fn *service*) (pms/project *service* (actor owner) id))
                         (:node_id machine)))))
      (testing "工作台汇总: 已批准 1 条, 停止任务 1, 待补偿 0"
        (let [workspace (:data (:body (request :get (str "/api/pms/projects/" id "/cancellations") owner nil)))]
          (is (= 1 (get-in workspace [:summary :approved])))
          (is (= 1 (get-in workspace [:summary :stopped])))
          (is (= 0 (get-in workspace [:summary :pending_compensation])))
          (is (= "not_configured" (:external_compensation workspace))))))))


(deftest non-approver-cannot-decide
  (let [{:keys [id machine change]} (context!)]
    (let [record (result! (request :post (str "/api/pms/projects/" id "/cancellations") owner
                                   {:version (version id) :target "node" :node_id (:node_id machine)
                                    :basis_change_id (:id change) :reason "客户取消单机 M1"}))]
      (is (= 200 (:status (command! id (:id record) "submit" {:reviewer_id approver}))))
      (testing "非指定审批人 (含提交人) 不能决定"
        (is (= 403 (:status (command! owner id (:id record) "review" {:decision "approved" :reason "自批"}))))
        ;; 未加入项目的用户连项目都读不到
        (is (= 403 (:status (command! outsider id (:id record) "review" {:decision "approved" :reason "越权"})))))
      (testing "驳回后不停止下发"
        (let [rejected (command! approver id (:id record) "review" {:decision "rejected" :reason "客户恢复需求"})]
          (is (= 200 (:status rejected)))
          (is (empty? (cancellation/cancelled-node-ids (:query-fn *service*) (pms/project *service* (actor owner) id)))))))))


(deftest cancellation-http-contract
  (let [{:keys [id machine change]} (context!)]
    (testing "认证与权限契约"
      (is (= 401 (:status (request :get (str "/api/pms/projects/" id "/cancellations") nil nil))))
      (is (= 403 (:status (request :get (str "/api/pms/projects/" id "/cancellations") outsider nil))))
      (is (= 200 (:status (request :get (str "/api/pms/projects/" id "/cancellations") owner nil)))))
    (let [record (result! (request :post (str "/api/pms/projects/" id "/cancellations") owner
                                   {:version (version id) :target "node" :node_id (:node_id machine)
                                    :basis_change_id (:id change) :reason "客户取消单机 M1"}))]
      (testing "跨项目引用返回 404 而不是泄露其他项目数据"
        (let [other-id (:project_id (pms/create-project! *service* (actor owner)
                                                         {:project_no (str "CX2-" (subs (str (UUID/randomUUID)) 0 8)) :name "另一项目"
                                                          :customer "本地客户" :contract_no "CX-TEST2" :project_type "equipment"
                                                          :manager_id owner :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
          (is (= 404 (:status (request :get (str "/api/pms/projects/" other-id "/cancellations/" (:id record)) owner nil))))))
      (testing "明细返回记录与审批策略说明"
        (let [detail (:data (:body (request :get (str "/api/pms/projects/" id "/cancellations/" (:id record)) owner nil)))]
          (is (= (:id record) (get-in detail [:record :id])))
          (is (= "not_configured" (:external_compensation detail))))))))
