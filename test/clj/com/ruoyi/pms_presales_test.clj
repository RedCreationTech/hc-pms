(ns com.ruoyi.pms-presales-test
  "A04 合同/售前资料交接与产品线资料检查: 按适用产品线校验应交文件, 缺资料退回, 完整资料经独立审批后才可用于立项.
   外部 CRM/OA 回执未对接, 本地如实标注 not_configured."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.config :as config]
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
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9900,'Presales owner','pms-presales-owner',90,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9900,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9901,'Presales member','pms-presales-member',91,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9901,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:project:edit')"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9902,'Presales none','pms-presales-none',92,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9904,'Presales observer','pms-presales-observer',93,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9904,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query','pms:quality:approve')"])
  (doseq [[id role] [[9900 9900] [9901 9901] [9902 9902] [9903 9901] [9909 9902] [9904 9904]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "presales-test-" id) (str "售前测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-presales-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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


(defn- reset-data!
  "清空治理记录/成员/清单配置, 使各用例互不干扰."
  []
  (jdbc/execute! (:db *service*) ["DELETE FROM pms_gov_record"])
  (jdbc/execute! (:db *service*) ["DELETE FROM pms_member"])
  (jdbc/execute! (:db *service*) ["DELETE FROM pms_config_record WHERE kind = 'presales-checklist'"]))


(defn- project!
  "建立合同号即产品线的项目; 建项目需 pms:project:add, 由全权限用户创建."
  [product-line]
  (let [id (:project_id (pms/create-project! *service* (actor 9900)
                                             {:project_no (str "PS-" (subs (str (UUID/randomUUID)) 0 8)) :name "售前资料项目"
                                              :customer "本地客户" :contract_no product-line :project_type "equipment"
                                              :manager_id 9903 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
    (pms/set-member! *service* (actor 9900) id {:user_id 9901 :role "viewer"})
    id))


(defn- publish-checklist!
  "发布一条产品线应交资料清单 (编码即产品线)."
  [code required optional]
  (let [created (config/create! *service* (actor 9900) "presales-checklist"
                                {:code code :name (str code " 应交资料") :product_line code
                                 :required_docs required :optional_docs optional})]
    (config/publish! *service* (actor 9900) "presales-checklist" (:id created) {:reason "生效"})))


(defn- version!
  "读取项目当前聚合版本; 每条写命令都携带该版本."
  [id]
  (:version (pms/project *service* (actor 9903) id)))


(defn- create-doc!
  "在项目内登记一份文本证据文档, 返回版本ID."
  [id code]
  (let [current (version! id)
        resp (request :post (str "/api/pms/projects/" id "/governance/documents") 9903
                      {:code code :title code :filename (str code ".txt") :content (str "以 " code " 交接")
                       :version current})]
    (when-not (= 200 (:status resp)) (throw (ex-info (str "文档登记失败: " (:body resp)) {:status (:status resp)})))
    (get-in resp [:body :data :result :record_id])))


(defn- created!
  "读取业务命令返回的记录."
  [resp]
  (:data (:body resp)))


(defn- result!
  "读取业务命令返回结果的平铺字段."
  [resp]
  (get-in resp [:body :data :result]))


(defn- command!
  ([id rid action payload] (command! id rid action payload 9903))
  ([id rid action payload uid]
   (when-not rid (throw (ex-info "未取得交接记录ID" {})))
   (request :post (str "/api/pms/projects/" id "/presales/" rid "/" action) uid
            (assoc payload :version (version! id)))))


(deftest checklist-config-is-versioned-and-validated
  (reset-data!)
  (testing "不同产品线的必交项不同, 可选与必交不得重复"
    (let [line (publish-checklist! "LINE-A" ["技术协议" "URS"] ["报价单"])]
      (is (= "LINE-A" (:code line)))
      (is (= ["技术协议" "URS"] (:required_docs line)))))
  (is (= 400 (:status (request :post "/api/pms/config/presales-checklist" 9900
                               {:code "LINE-B" :product_line "LINE-B"
                                :required_docs ["URS"] :optional_docs ["URS"]}))))
  (is (= 403 (:status (request :post "/api/pms/config/presales-checklist" 9903
                               {:code "LINE-B" :product_line "LINE-B" :required_docs ["URS"]}))))
  (testing "清单可由全权限用户发布, 且仅当前生效版本参与校验"
    (publish-checklist! "LINE-B" ["技术协议"] [])
    (is (= #{"LINE-A" "LINE-B"} (set (map :code (config/published (:query-fn *service*) "presales-checklist")))))))


(deftest missing-items-block-submission-and-return-is-traced
  (reset-data!)
  (publish-checklist! "LINE-A" ["技术协议" "URS"] [])
  (let [id (project! "LINE-A")
        doc (create-doc! id "URS-001")
        created (:body (request :post (str "/api/pms/projects/" id "/presales") 9903
                                {:title "合同交接" :owner_id 9903
                                 :items [{:name "URS" :document_ids [doc]}]
                                 :version (version! id)}))
        rid (get-in created [:data :result :record_id])]

    (testing "缺必交项时提交 409 且列出缺料"
      (is (= "pending" (get-in created [:data :result :status])))
      (is (= ["技术协议"] (:rule_missing (first (:rows (:data (:body (request :get (str "/api/pms/projects/" id "/presales") 9903 nil))))))))
      (let [resp (command! id rid "submit" {:reviewer_id 9900})]
        (is (= 409 (:status resp)))
        (is (re-find #"技术协议" (get-in resp [:body :msg])))))
    (testing "清单外资料拒绝登记"
      (is (= 400 (:status (request :post (str "/api/pms/projects/" id "/presales/" rid) 9903
                                   {:items [{:name "不存在的资料"}] :version (version! id)})))))
    (testing "退回记录原因与缺料明细, 可再次补交"
      (let [returned (command! id rid "return" {:reason "缺技术协议"})]
        (is (= 200 (:status returned)))
        (is (= "returned" (:status (result! returned))))
        (is (= ["技术协议"] (:return_missing (result! returned))))
        (is (= 1 (count (:returns (result! returned))))))
      (let [completing (request :post (str "/api/pms/projects/" id "/presales/" rid) 9903
                                {:items [{:name "URS" :document_ids [doc]}
                                         {:name "技术协议" :document_ids [(create-doc! id "TA-001")]}]
                                 :version (:version (pms/project *service* (actor 9903) id))})
            submitted (command! id rid "submit" {:reviewer_id 9900})]
        (is (= 200 (:status completing)))
        (is (= "returned" (:status (result! completing))) "补交保持退回态, 直到重新提交")
        (is (= [] (:rule_missing (first (:rows (:data (:body (request :get (str "/api/pms/projects/" id "/presales") 9903 nil))))))))
        (is (= "submitted" (:status (result! submitted))))))))


(deftest approval-requires-independent-reviewer-and-complete-pack
  (reset-data!)
  (publish-checklist! "LINE-C" ["技术协议"] [])
  (let [id (project! "LINE-C")
        doc (create-doc! id "TA-002")
        rid (get-in (request :post (str "/api/pms/projects/" id "/presales") 9903
                             {:title "合同交接" :owner_id 9903
                              :items [{:name "技术协议" :document_ids [doc]}]
                              :version (version! id)})
                    [:body :data :result :record_id])]
    (testing "审核人不得为提交人"
      (is (= 409 (:status (command! id rid "submit" {:reviewer_id 9903})))))
    (testing "齐备提交后由指定独立审核人批准, 非指定人不能决定"
      (is (= 200 (:status (command! id rid "submit" {:reviewer_id 9900}))))
      (is (= 403 (:status (command! id rid "approve" {:reason "就绪"}))))
      (let [approved (request :post (str "/api/pms/projects/" id "/presales/" rid "/approve") 9900
                              {:reason "资料齐备" :version (version! id)})]
        (is (= 200 (:status approved)))
        (is (= "approved" (:status (result! approved))))
        (is (= 9900 (:decided_by (result! approved))))))
    (testing "已批准交接只读, 不能再退回或补交"
      (is (= 409 (:status (command! id rid "return" {:reason "再审"}))))
      (is (= 409 (:status (command! id rid "submit" {:reviewer_id 9900})))))))


(deftest approver-only-needs-read-scope
  (reset-data!)
  (publish-checklist! "LINE-C2" ["技术协议"] [])
  (let [id (project! "LINE-C2")
        doc (create-doc! id "TA-003")
        rid (get-in (request :post (str "/api/pms/projects/" id "/presales") 9903
                             {:title "合同交接" :owner_id 9903
                              :items [{:name "技术协议" :document_ids [doc]}]
                              :version (version! id)})
                    [:body :data :result :record_id])]
    ;; 9904 只是项目观察者 (viewer) 且有质量审批权限, 无项目编辑权: 审批不应要求编辑权.
    (pms/set-member! *service* (actor 9900) id {:user_id 9904 :role "viewer"})
    (testing "审批人只需要项目只读范围"
      (is (= "viewer" (:role (first (filter #(= 9904 (:user_id %))
                                            (:rows (pms/members *service* (actor 9903) id)))))))
      (is (= 200 (:status (command! id rid "submit" {:reviewer_id 9904}))))
      (let [approved (command! id rid "approve" {:reason "齐备"} 9904)]
        (is (= 200 (:status approved)) (str "审批失败: " (:body approved)))
        (is (= "approved" (:status (result! approved))))
        (is (= 9904 (:decided_by (result! approved))))))))


(deftest handover-http-contract-and-scope
  (reset-data!)
  (let [id (project! "LINE-D")]
    (testing "认证与权限契约"
      (is (= 401 (:status (request :get (str "/api/pms/projects/" id "/presales") nil nil))))
      (is (= 403 (:status (request :get (str "/api/pms/projects/" id "/presales") 9902 nil)))))
    (testing "未发布该产品线清单时不臆造必交项, 如实标注"
      (let [data (:data (:body (request :get (str "/api/pms/projects/" id "/presales") 9903 nil)))]
        (is (true? (:checklist_missing data)))
        (is (nil? (:checklist data)))
        (is (= "LINE-D" (:product_line data)))))
    (testing "跨项目引用被拒"
      (publish-checklist! "LINE-E" ["技术协议"] [])
      (let [other (project! "LINE-E")
            rid (get-in (request :post (str "/api/pms/projects/" other "/presales") 9903
                                 {:title "交接" :owner_id 9903 :items [] :version (version! other)})
                        [:body :data :result :record_id])]
        (is (= 404 (:status (request :post (str "/api/pms/projects/" id "/presales/" rid "/return") 9903
                                     {:reason "跨项目" :version (version! id)}))))))))
