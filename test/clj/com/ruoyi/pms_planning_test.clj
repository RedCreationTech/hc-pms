(ns com.ruoyi.pms-planning-test
  "计划工作台的隔离数据库集成测试和排程算法验收."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.finance-time :as finance-time]
            [com.ruoyi.domain.pms.governance :as gov]
            [com.ruoyi.domain.pms.governance.approval :as approval]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.domain.pms.planning :as plan]
            [com.ruoyi.domain.pms.planning.capacity :as capacity]
            [com.ruoyi.domain.pms.planning.schedule :as schedule]
            [com.ruoyi.domain.pms.planning.tasks :as tasks]
            [com.ruoyi.infra.security :as security]
            [com.ruoyi.web.middleware.auth :as auth]
            [com.ruoyi.web.routes.api :as api]
            [com.ruoyi.web.routes.pms-planning :as routes]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]))

(def ^:dynamic *svc* nil)
(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))

(defn- query-function
  "为扩展生命周期加载全部现有PMS查询,同时支持事务连接."
  [db]
  (let [files (->> (.listFiles (io/file "resources/sql"))
                   (filter #(re-matches #"pms.*\.sql" (.getName %)))
                   (map #(str "sql/" (.getName %))) sort)
        queries (:fns (apply conman/bind-connection-map db {} files))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))

(defn- fixture
  "在独立SQLite或CI专用MySQL中建立不与其他模块冲突的用户."
  [f]
  (let [file (when-not *jdbc-url* (java.io.File/createTempFile "pms-planning-" ".db"))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9200,'Planning test','planning-test',20,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9200,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
      (doseq [id [9201 9202 9203 9204]]
        (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(?,1,?,?,'0','0')"
                           id (str "plan-user-" id) (str "计划测试" id)])
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9200)" id]))
      (binding [*svc* {:db db :query-fn (query-function db)}] (f))
      (finally (when file (.delete file))))))

(use-fixtures :once fixture)

(defn- actor
  "取得当前测试用户的有效权限."
  [id]
  (pms/actor *svc* {:user-id id}))

(defn- project!
  "建立计划阶段项目及独立的只读审批成员."
  []
  (let [project (pms/create-project! *svc* (actor 1)
                                     {:project_no (str "PLAN-" (kernel/id)) :name "计划验收"
                                      :manager_id 9201 :dept_id 1 :start_date "2026-09-21"})
        id (:project_id project)]
    (pms/set-member! *svc* (actor 1) id {:user_id 9202 :role "viewer"})
    (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='planning' WHERE project_id=?" id])
    id))

(defn- version
  "读取真实项目当前版本."
  [id]
  (:version ((:query-fn *svc*) :pms/project {:project_id id})))

(defn- command!
  "以项目经理身份执行携带最新版本的命令."
  [f id args body]
  (apply f *svc* (actor 9201) id (concat args [(assoc body :version (version id))])))

(defn- task!
  "创建具备有效负责人的普通测试任务."
  [id code days]
  (:result (command! plan/create-task! id [] {:wbs_code code :name code :duration_days days :owner_id 9201})))

(defn- status
  "读取可预期业务异常的HTTP状态."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(defn- pure-tasks
  "构造不依赖数据库的两个排程任务."
  []
  [{:task_id "a" :name "前项" :task_type "task" :duration_days 3 :start_date "2026-09-21"}
   {:task_id "b" :name "后项" :task_type "task" :duration_days 2 :start_date "2026-09-21"}])

(deftest four-dependency-types-and-calendar
  (doseq [[type lag start end] [["FS" 0 "2026-09-24" "2026-09-25"]
                                ["SS" 1 "2026-09-22" "2026-09-23"]
                                ["FF" 0 "2026-09-22" "2026-09-23"]
                                ["SF" 4 "2026-09-23" "2026-09-24"]
                                ["FS" -1 "2026-09-23" "2026-09-24"]]]
    (let [result (schedule/schedule {} (pure-tasks)
                                    [{:predecessor_id "a" :successor_id "b" :dependency_type type :lag_days lag}]
                                    schedule/default-calendar)
          task (first (filter #(= "b" (:task_id %)) (:tasks result)))]
      (is (= start (:start_date task))) (is (= end (:end_date task)))))
  (let [calendar (assoc schedule/default-calendar :holidays ["2026-09-22"] :extra_workdays ["2026-09-27"])
        result (schedule/schedule {} (pure-tasks)
                                  [{:predecessor_id "a" :successor_id "b" :dependency_type "FS" :lag_days 0}] calendar)]
    (is (= "2026-09-27" (:end_date result)))
    (is (= ["a" "b"] (:critical_path result))))
  (is (= 409 (status #(schedule/topological-order (pure-tasks)
                                                  [{:predecessor_id "a" :successor_id "b"}
                                                   {:predecessor_id "b" :successor_id "a"}])))))

(deftest wbs-isolation-and-cycle-guards
  (let [id (project!) other (project!)
        parent (:result (command! plan/create-task! id [] {:wbs_code "WBS" :name "阶段" :task_type "summary" :duration_days 0}))
        child (:result (command! plan/create-task! id [] {:wbs_code "WBS.1" :name "子阶段" :task_type "summary"
                                                         :duration_days 0 :parent_id (:task_id parent)}))
        task (task! id "A" 2) foreign (task! other "X" 1)]
    (is (= 409 (status #(command! plan/update-task! id [(:task_id parent)] {:parent_id (:task_id child)}))))
    (is (= 404 (status #(command! plan/update-task! id [(:task_id task)] {:parent_id (:task_id foreign)}))))
    (is (= 400 (status #(command! plan/update-task! id [(:task_id task)] {:parent_id {}}))))
    (is (= 409 (status #(task! id "A" 1))))
    (is (= 409 (status #(command! plan/delete-task! id [(:task_id parent)] {}))))
    (is (= 403 (status #(plan/read-plan *svc* (actor 9203) id))))
    (is (= 409 (status #(plan/update-task! *svc* (actor 9201) id (:task_id task) {:version 1 :name "旧版本"}))))))

(deftest dependency-dag-and-resource-overload
  (let [id (project!) a (task! id "A" 2) b (task! id "B" 2)
        resource (:result (command! plan/create-resource! id [] {:name "负责人" :resource_type "person" :user_id 9201 :daily_capacity 8}))
        allocation (fn [task] (command! plan/create-allocation! id [] {:task_id (:task_id task)
                                                                       :resource_id (:resource_id resource) :hours_per_day 6}))]
    (allocation a) (allocation b)
    (is (= 2 (count (:overallocations (plan/read-plan *svc* (actor 9201) id)))))
    (is (= 409 (status #(command! plan/submit-plan! id [] {:comment "资源超配"}))))
    (command! plan/set-capacity! id [(:resource_id resource)] {:date "2026-09-21" :capacity_hours 12})
    (is (= 1 (count (:overallocations (plan/read-plan *svc* (actor 9201) id)))))
    (let [edge (:result (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b)
                                                               :dependency_type "FS" :lag_days 0}))]
      (is (empty? (:overallocations (plan/read-plan *svc* (actor 9201) id))))
      (is (= 409 (status #(command! plan/create-dependency! id [] {:predecessor_id (:task_id b) :successor_id (:task_id a)
                                                                  :dependency_type "SS" :lag_days 0}))))
      (is (= 409 (status #(command! plan/delete-task! id [(:task_id a)] {}))))
      (command! plan/delete-dependency! id [(:dependency_id edge)] {}))
    (is (= 409 (status #(command! plan/create-resource! id [] {:name "重复" :resource_type "person" :user_id 9201}))))))

(deftest independent-approval-snapshot-and-revision
  (let [id (project!) task (task! id "A" 3)
        submitted (:result (command! plan/submit-plan! id [] {:comment "初版"}))
        baseline-id (:baseline_id submitted)
        review (fn [user decision comment]
                 (plan/review-plan! *svc* (actor user) id baseline-id
                                    {:version (version id) :decision decision :comment comment}))]
    (is (= "submitted" (:status submitted)))
    (is (= 409 (status #(command! plan/update-task! id [(:task_id task)] {:name "审批中修改"}))))
    (is (= 403 (status #(review 9201 "approved" "自审"))))
    (is (= 400 (status #(review 9202 "rejected" ""))))
    (is (= "approved" (get-in (review 9202 "approved" "独立审查通过") [:result :status])))
    (let [snapshot (:snapshot (plan/read-baseline *svc* (actor 9201) id baseline-id))
          q (:query-fn *svc*) project (q :pms/project {:project_id id})]
      (is (= baseline-id (:baseline_id (plan/execution-ready! q project))))
      (is (false? (:changed (plan/baseline-diff *svc* (actor 9201) id baseline-id))))
      (command! plan/update-task! id [(:task_id task)] {:duration_days 4})
      (is (= 409 (status #(plan/execution-ready! q project))))
      (is (:changed (plan/baseline-diff *svc* (actor 9201) id baseline-id)))
      (is (= snapshot (:snapshot (plan/read-baseline *svc* (actor 9201) id baseline-id))))
      (is (= 409 (status #(review 9202 "rejected" "不可改旧决定")))))))

(deftest rejection-and-progress-do-not-rewrite-baseline
  (let [id (project!) task (task! id "A" 2)
        pending (:result (command! plan/submit-plan! id [] {:comment "待审"}))]
    (plan/review-plan! *svc* (actor 9202) id (:baseline_id pending)
                      {:version (version id) :decision "rejected" :comment "修订工期"})
    (command! plan/update-task! id [(:task_id task)] {:duration_days 3})
    (let [approved (:result (command! plan/submit-plan! id [] {:comment "修订版"}))]
      (plan/review-plan! *svc* (actor 9202) id (:baseline_id approved)
                        {:version (version id) :decision "approved" :comment "通过"})
      (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id])
      (command! plan/task-feedback! id [(:task_id task)] {:status "in_progress" :percent_complete 50 :remaining_days 2 :comment "实际完成一半"})
      (is (false? (:changed (plan/baseline-diff *svc* (actor 9201) id (:baseline_id approved)))))
      (command! plan/task-feedback! id [(:task_id task)] {:status "done" :percent_complete 100 :remaining_days 0})
      (is (= 409 (status #(command! plan/task-feedback! id [(:task_id task)] {:status "in_progress" :percent_complete 80 :remaining_days 1}))))
      (let [readmodel (plan/read-plan *svc* (actor 9201) id)]
        (is (= 2 (count (:feedback readmodel))))
        (is (= ["done" "in_progress"] (mapv :status (:feedback readmodel))))
        (is (apply > (map :project_version (:feedback readmodel))))
        (is (= "done" (:status (first (:tasks readmodel)))))
        (is (= 2 (count (:baselines readmodel))))))))

(defn- request
  "经实际路由,身份认证和JSON格式中间件调用计划接口."
  [method path user-id body]
  (let [handler (ring/ring-handler
                 (ring/router [["/api" api/route-data
                                (into ["/pms" {:middleware [(auth/auth-middleware {:required? true})]}]
                                      (routes/planning-routes *svc*))]]))
        req (cond-> (-> (mock/request method path)
                         (mock/content-type "application/json")
                         (mock/header "accept" "application/json"))
              user-id (mock/header "authorization" (str "Bearer " (security/generate-token user-id "plan" [])))
              body (mock/body (json/generate-string body)))
        response (handler req) raw (:body response)
        body (cond (map? raw) raw
                   (bytes? raw) (json/parse-string (String. ^bytes raw "UTF-8") true)
                   :else (json/parse-string (if (string? raw) raw (slurp raw)) true))]
    {:status (:status response) :body body}))

(deftest parallel-critical-path-summary-and-milestone
  (let [task (fn [id days parent]
               {:task_id id :name id :task_type "task" :duration_days days
                :parent_id parent :start_date "2026-09-21"})
        tasks [(task "a" 3 "s") (task "b" 2 "s")
               (assoc (task "s" 0 nil) :task_type "summary")
               (assoc (task "m" 0 nil) :task_type "milestone")]
        edges (mapv #(hash-map :predecessor_id % :successor_id "m" :dependency_type "FS" :lag_days 0) ["a" "b"])
        result (schedule/schedule {} tasks edges schedule/default-calendar)
        rows (into {} (map (juxt :task_id identity) (:tasks result)))]
    (is (= ["a" "m"] (:critical_path result)))
    (is (= 1 (:total_float (rows "b"))))
    (is (false? (:critical (rows "b"))))
    (is (= "2026-09-24" (:start_date (rows "m"))))
    (is (empty? (:working_dates (rows "m"))))
    (is (= ["2026-09-21" "2026-09-23" 3]
           ((juxt :start_date :end_date :duration_days) (rows "s"))))))

(defn- fail-audit-service
  "只在审计写入时注入故障,验证真实数据库事务回滚."
  []
  (let [query (:query-fn *svc*)]
    (assoc *svc* :query-fn
           (fn
             ([key params] (query key params))
             ([tx key params]
              (when (= :pms/insert-event! key) (throw (ex-info "audit unavailable" {})))
              (query tx key params))))))

(deftest audit-failure-rolls-back-design-and-review
  (let [id (project!) before (plan/read-plan *svc* (actor 9201) id)
        failing (fail-audit-service)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"audit unavailable"
                         (plan/create-task! failing (actor 9201) id
                                            {:version (version id) :wbs_code "FAIL" :name "不可落库"})))
    (is (= before (plan/read-plan *svc* (actor 9201) id)))
    (task! id "A" 2)
    (let [pending (:result (command! plan/submit-plan! id [] {}))
          baseline-id (:baseline_id pending) before-version (version id)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"audit unavailable"
                           (plan/review-plan! failing (actor 9202) id baseline-id
                                              {:version before-version :decision "approved"})))
      (is (= before-version (version id)))
      (is (= "submitted" (:status (plan/read-baseline *svc* (actor 9201) id baseline-id)))))))

(deftest http-contract-and-error-boundaries
  (let [id (project!) base (str "/api/pms/projects/" id)
        post (fn [suffix body] (request :post (str base suffix) 9201 (assoc body :version (version id))))]
    (is (= 401 (:status (request :get (str base "/planning") nil nil))))
    (is (= 403 (:status (request :get (str base "/planning") 9203 nil))))
    (is (= 400 (:status (request :post (str base "/tasks") 9201 []))))
    (is (= 400 (:status (post "/tasks" {:wbs_code "A" :name "A" :parent_id {}}))))
    (is (= 400 (:status (post "/tasks" {:wbs_code "A" :name "A" :duration_days 1.2}))))
    (is (= 400 (:status (post "/resources" {:name "设备" :resource_type "equipment" :daily_capacity 1.111}))))
    (let [response (post "/tasks" {:wbs_code "A" :name "A" :owner_id 9201})
          task (get-in response [:body :data :result])
          readmodel (request :get (str base "/planning") 9201 nil)]
      (is (= 200 (:status response)))
      (is (= (version id) (get-in response [:body :data :project_version])))
      (is (= (:task_id task) (get-in readmodel [:body :data :tasks 0 :task_id])))
      (is (= 409 (:status (request :put (str base "/tasks/" (:task_id task)) 9201 {:version 1 :name "过期修改"}))))
      (is (= 400 (:status (request :put (str base "/calendar") 9201
                                  {:version (version id) :working_days []})))))))

(defn- approve-plan!
  "以只读独立成员批准本项目已提交计划."
  [id baseline-id]
  (plan/review-plan! *svc* (actor 9202) id baseline-id
                    {:version (version id) :decision "approved" :comment "批准"}))

(defn- reject-obsolete-change-baseline!
  "变更产生新版本后,旧依据不能批准但必须允许驳回解除计划冻结."
  [id task change]
  (command! plan/update-task! id [(:task_id task)] {:duration_days 4})
  (let [pending (:result (command! plan/submit-plan! id [] {:change_id (:id change)}))]
    (approval/revise! *svc* (actor 9201) id "change" (:id change)
                      (assoc (select-keys change approval/change-fields) :version (version id)))
    (is (= 409 (status #(approve-plan! id (:baseline_id pending)))))
    (is (= "rejected" (get-in (plan/review-plan! *svc* (actor 9202) id (:baseline_id pending)
                                                {:version (version id) :decision "rejected" :comment "变更依据已修订"})
                                [:result :status])))))

(deftest execution-rebaseline-requires-approved-change
  (let [id (project!) task (task! id "A" 2)
        baseline (:result (command! plan/submit-plan! id [] {}))]
    (approve-plan! id (:baseline_id baseline))
    (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id])
    (command! plan/update-task! id [(:task_id task)] {:duration_days 3})
    (is (= 409 (status #(command! plan/submit-plan! id [] {}))))
    (let [change (:result (approval/create! *svc* (actor 9201) id "change"
                                            {:version (version id) :title "工期变更" :reason "现场条件改变"
                                             :scope_impact "范围不变" :schedule_impact "工期增加1天" :cost_impact "成本不变"
                                             :quality_impact "质量要求不变" :resource_impact "延长资源占用"}))]
      (is (= 409 (status #(command! plan/submit-plan! id [] {:change_id (:id change)}))))
      (approval/submit! *svc* (actor 9201) id "change" (:id change) {:version (version id) :reviewer_id 9202})
      (approval/decide! *svc* (actor 9202) id "change" (:id change)
                        {:version (version id) :decision "approved" :reason "同意调整"})
      (is (= [{:id (:id change) :title "工期变更"}] (:approved_changes (plan/read-plan *svc* (actor 9201) id))))
      (let [next (:result (command! plan/submit-plan! id [] {:change_id (:id change)}))]
        (is (= (:id change) (:change_id next)))
        (approve-plan! id (:baseline_id next))
        (is (= (:baseline_id next) (:baseline_id (plan/execution-ready! (:query-fn *svc*)
                                                                                     ((:query-fn *svc*) :pms/project {:project_id id}))))))
      (reject-obsolete-change-baseline! id task change))))

(deftest timesheet-references-prevent-task-deletion
  (let [id (project!) task (task! id "TIME" 1)]
    (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id])
    (finance-time/submit! *svc* (actor 9201) id {:version (version id) :task_id (:task_id task)
                                              :work_date "2020-01-01" :hours 1 :note "实际工作" :reviewer_id 9202})
    (is (= 409 (status #(command! plan/delete-task! id [(:task_id task)] {}))))
    (is (= (:task_id task) (:task_id ((:query-fn *svc*) :planning/task {:project_id id :task_id (:task_id task)}))))))

(defn- shared-allocation!
  "为多个项目建立相同人员的真实资源与分配."
  [id hours capacity]
  (pms/set-member! *svc* (actor 1) id {:user_id 9204 :role "viewer"})
  (let [task (task! id "SHARED" 1)
        resource (:result (command! plan/create-resource! id []
                                    {:name "共享工程师" :resource_type "person" :user_id 9204 :daily_capacity capacity}))]
    (command! plan/create-allocation! id [] {:task_id (:task_id task) :resource_id (:resource_id resource) :hours_per_day hours})
    resource))

(deftest shared-person-capacity-preserves-other-project-privacy
  (let [id (project!) other (project!)
        local (shared-allocation! id 5 8) _ (shared-allocation! other 4 8)]
    (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET manager_id=9203 WHERE project_id=?" other])
    (jdbc/execute! (:db *svc*) ["DELETE FROM pms_member WHERE project_id=? AND user_id=9201" other])
    (is (= 403 (status #(plan/read-plan *svc* (actor 9201) other))))
    (let [rows (:overallocations (plan/read-plan *svc* (actor 9201) id)) row (first rows)]
      (is (= 1 (count rows)))
      (is (= (:resource_id local) (:resource_id row)))
      (is (every? true? (map == [9 5 4 8 1]
                             ((juxt :planned_hours :project_hours :other_project_hours :capacity_hours :excess_hours) row))))
      (is (not (.contains (json/generate-string rows) other)))
      (is (= 409 (status #(command! plan/submit-plan! id [] {})))))
    (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='cancelled' WHERE project_id=?" other])
    (is (empty? (:overallocations (plan/read-plan *svc* (actor 9201) id))))
    (is (= "submitted" (:status (:result (command! plan/submit-plan! id [] {})))))))


(deftest scope-coverage-is-derived-read-only
  (testing "纯函数口径: 叶节点为非汇总任务, 被 satisfies 指向即覆盖, 覆盖率四舍五入, 未覆盖清单按 WBS 编号"
    (let [pure-tasks [{:task_id "s" :task_type "summary" :wbs_code "1"}
                      {:task_id "a" :task_type "task" :wbs_code "1.1"}
                      {:task_id "b" :task_type "task" :wbs_code "1.2"}]
          pure-traces [{:target_kind "task" :target_id "a" :relation "satisfies"}
                       {:target_kind "document" :target_id "a" :relation "satisfies"}
                       {:target_kind "task" :target_id "b" :relation "verifies"}]
          summary (tasks/scope-coverage pure-tasks pure-traces)]
      (is (= {:total-leaves 2 :covered-leaves 1 :uncovered-leaves 1 :coverage-pct 50}
             (select-keys summary [:total-leaves :covered-leaves :uncovered-leaves :coverage-pct])))
      (is (= ["1.2"] (:uncovered-codes summary)))
      (is (false? (:scope_leaf (tasks/scope-read-model {} {:task_id "s" :task_type "summary"}))))
      (is (true? (:scope_leaf (tasks/scope-read-model {} {:task_id "m" :task_type "milestone"}))))
      (is (= {:scope_covered true :scope_satisfies_count 1 :scope_verifies_count 0}
             (select-keys (tasks/scope-read-model {"a" [{:relation "satisfies"}]} {:task_id "a" :task_type "task"})
                          [:scope_covered :scope_satisfies_count :scope_verifies_count])))
      (is (false? (:scope_covered (tasks/scope-read-model {"b" [{:relation "verifies"}]} {:task_id "b" :task_type "task"}))))))
  (testing "read-plan 集成: 一汇总两叶任务, 需求 satisfies 覆盖其一, 派生只读且无版本漂移"
    (let [id (project!)
          _ (:result (command! plan/create-task! id [] {:wbs_code "1" :name "汇总" :task_type "summary" :duration_days 0 :owner_id 9201}))
          leaf-a (task! id "1.1" 3)
          leaf-b (task! id "1.2" 3)
          requirement (:result (gov/command! *svc* (actor 9201) id :requirements :create nil
                                             {:code "URS-SC-1" :text "覆盖验收" :category "功能" :priority "required"
                                              :owner_id 9201 :version (version id)}))]
      (gov/command! *svc* (actor 9201) id :traces :create nil
                    {:requirement_id (:id requirement) :target_kind "task" :target_id (:task_id leaf-a)
                     :relation "satisfies" :version (version id)})
      (let [v-before (version id)
            model (plan/read-plan *svc* (actor 9201) id)
            coverage (:scope_coverage model)
            find-task #(first (filter (fn [t] (= (:wbs_code t) %)) (:tasks model)))]
        (is (= {:total-leaves 2 :covered-leaves 1 :uncovered-leaves 1 :coverage-pct 50}
               (select-keys coverage [:total-leaves :covered-leaves :uncovered-leaves :coverage-pct])))
        (is (= ["1.2"] (:uncovered-codes coverage)))
        (is (true? (:scope_covered (find-task "1.1"))))
        (is (false? (:scope_covered (find-task "1.2"))))
        (is (false? (:scope_leaf (find-task "1"))))
        (is (= v-before (version id)))
        (is (= v-before (:project_version model)))))))


(deftest baseline-schedule-variance-is-derived-read-only
  (testing "无已批准基线时 available=false 且不逐任务标注"
    (let [id (project!) task (task! id "A" 2) model (plan/read-plan *svc* (actor 9201) id)]
      (is (false? (get-in model [:baseline_variance :available])))
      (is (nil? (:baseline_state (first (filter #(= (:task_id task) (:task_id %)) (:tasks model))))))))
  (testing "批准基线且设计未变时逐任务 on_baseline, 汇总计数一致"
    (let [id (project!) task (task! id "A" 3)
          submitted (:result (command! plan/submit-plan! id [] {:comment "初版"}))
          _ (approve-plan! id (:baseline_id submitted))
          model (plan/read-plan *svc* (actor 9201) id)
          summary (:baseline_variance model)
          row (first (filter #(= (:task_id task) (:task_id %)) (:tasks model)))]
      (is (true? (:available summary)))
      (is (= (:baseline_id submitted) (:baseline_id summary)))
      (is (= 1 (:baseline_revision summary)))
      (is (= "on_baseline" (:baseline_state row)))
      (is (= 0 (:baseline_finish_variance row)))
      (is (= 0 (:baseline_start_variance row)))
      (is (= 1 (:total summary)))
      (is (= 1 (:on-baseline summary)))
      (is (= 0 (:behind summary)))
      (is (= 0 (:worst-finish-slip summary)))))
  (testing "延长工期使完成日延后为 behind, 新增任务标注 added, 只读不改版本"
    (let [id (project!) task (task! id "A" 3)
          submitted (:result (command! plan/submit-plan! id [] {:comment "初版"}))
          _ (approve-plan! id (:baseline_id submitted))
          _ (command! plan/update-task! id [(:task_id task)] {:duration_days 5})
          c (task! id "C" 2)
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          summary (:baseline_variance model)
          row-a (first (filter #(= (:task_id task) (:task_id %)) (:tasks model)))
          row-c (first (filter #(= (:task_id c) (:task_id %)) (:tasks model)))]
      (is (= "behind" (:baseline_state row-a)))
      (is (pos? (:baseline_finish_variance row-a)))
      (is (= "added" (:baseline_state row-c)))
      (is (nil? (:baseline_finish_variance row-c)))
      (is (= 2 (:total summary)))
      (is (= 1 (:behind summary)))
      (is (= 1 (:added summary)))
      (is (= (:baseline_finish_variance row-a) (:worst-finish-slip summary)))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))


(deftest resource-overload-summary-is-derived-read-only
  (testing "人员与设备同时超配时给出项目级只读汇总"
    (let [id (project!)
          _ (pms/set-member! *svc* (actor 1) id {:user_id 9203 :role "viewer"})
          a (task! id "A" 2) b (task! id "B" 2)
          person (:result (command! plan/create-resource! id [] {:name "工程师" :resource_type "person" :user_id 9203 :daily_capacity 8}))
          equip (:result (command! plan/create-resource! id [] {:name "机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id a) :resource_id (:resource_id person) :hours_per_day 6})
      (command! plan/create-allocation! id [] {:task_id (:task_id b) :resource_id (:resource_id person) :hours_per_day 6})
      (command! plan/create-allocation! id [] {:task_id (:task_id a) :resource_id (:resource_id equip) :hours_per_day 10})
      ;; 设备在 09-22 放宽到 12, 只在 09-21 超配, 使峰值日唯一
      (command! plan/set-capacity! id [(:resource_id equip)] {:date "2026-09-22" :capacity_hours 12})
      (let [v-before (version id)
            s (:overload_summary (plan/read-plan *svc* (actor 9201) id))]
        (is (true? (:available s)))
        (is (= 3 (:total-rows s)))
        (is (= 2 (:distinct-resources s)))
        (is (= 2 (:person-rows s)))
        (is (= 1 (:equipment-rows s)))
        (is (== 4 (:worst-excess-hours s)))
        (is (= "2026-09-21" (:peak-date s)))
        (is (= v-before (version id))))))
  (testing "无超配时汇总 available=false 且明细与旧行为一致"
    ;; 无超配用例只用设备资源(无 user_id), 不参与跨项目同人归集, 避免污染同库其它用例
    (let [id (project!) a (task! id "A" 2)
          equip (:result (command! plan/create-resource! id [] {:name "轻量机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id a) :resource_id (:resource_id equip) :hours_per_day 4})
      (let [model (plan/read-plan *svc* (actor 9201) id) s (:overload_summary model)]
        (is (false? (:available s)))
        (is (= 0 (:total-rows s)))
        (is (= 0 (:distinct-resources s)))
        (is (== 0 (:worst-excess-hours s)))
        (is (nil? (:peak-date s)))
        (is (empty? (:overallocations model)))))))


(deftest task-allocation-coverage-is-derived-read-only
  (testing "纯函数口径: 仅 task_type=task 参与分母, 里程碑与汇总排除, 同任务多条分配只算已投入, 覆盖率四舍五入"
    (let [pure-tasks [{:task_id "s" :task_type "summary" :wbs_code "1" :name "汇总"}
                      {:task_id "a" :task_type "task" :wbs_code "1.1" :name "甲"}
                      {:task_id "b" :task_type "task" :wbs_code "1.2" :name "乙"}
                      {:task_id "m" :task_type "milestone" :wbs_code "2" :name "里程碑"}]
          pure-allocs [{:task_id "a" :resource_id "r1" :hours_per_day 4}
                       {:task_id "a" :resource_id "r2" :hours_per_day 2}]
          cov (capacity/allocation-coverage pure-tasks pure-allocs)]
      (is (true? (:available cov)))
      (is (= 2 (:total-tasks cov)))
      (is (= 1 (:with-allocations cov)))
      (is (= 1 (:without-allocations cov)))
      (is (= 50 (:coverage-pct cov)))
      (is (= ["1.2"] (map :wbs_code (:unallocated-tasks cov))))))
  (testing "无 task_type=task 时 available=false 且覆盖率为 0"
    (let [cov (capacity/allocation-coverage [{:task_id "m" :task_type "milestone"}] [])]
      (is (false? (:available cov)))
      (is (= 0 (:total-tasks cov)))
      (is (= 0 (:coverage-pct cov)))
      (is (empty? (:unallocated-tasks cov)))))
  (testing "read-plan 集成: 一汇总一里程碑三叶任务两叶已分配使覆盖率 67%, 派生只读且无版本漂移"
    (let [id (project!)
          _ (:result (command! plan/create-task! id [] {:wbs_code "1" :name "汇总" :task_type "summary" :duration_days 0 :owner_id 9201}))
          a (task! id "A" 2) b (task! id "B" 2) c (task! id "C" 2)
          _ (:result (command! plan/create-task! id [] {:wbs_code "M" :name "里程碑" :task_type "milestone" :duration_days 0 :owner_id 9201}))
          equip (:result (command! plan/create-resource! id [] {:name "机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id a) :resource_id (:resource_id equip) :hours_per_day 4})
      (command! plan/create-allocation! id [] {:task_id (:task_id b) :resource_id (:resource_id equip) :hours_per_day 4})
      (let [v-before (version id)
            model (plan/read-plan *svc* (actor 9201) id)
            cov (:allocation_coverage model)]
        (is (true? (:available cov)))
        (is (= 3 (:total-tasks cov)))
        (is (= 2 (:with-allocations cov)))
        (is (= 1 (:without-allocations cov)))
        (is (= 67 (:coverage-pct cov)))
        (is (= ["C"] (map :wbs_code (:unallocated-tasks cov))))
        (is (= v-before (version id)))
        (is (= v-before (:project_version model)))))))

(deftest critical-path-staffing-is-derived-read-only
  (testing "纯函数口径: 仅关键路径上的 task_type=task 参与分母, 里程碑/汇总/非关键任务排除, 同任务多条分配只算已投入, 投入率四舍五入"
    (let [pure-tasks [{:task_id "s" :task_type "summary" :wbs_code "1" :name "汇总"}
                      {:task_id "a" :task_type "task" :wbs_code "1.1" :name "甲"}
                      {:task_id "b" :task_type "task" :wbs_code "1.2" :name "乙"}
                      {:task_id "c" :task_type "task" :wbs_code "1.3" :name "丙"}
                      {:task_id "m" :task_type "milestone" :wbs_code "2" :name "里程碑"}]
          ;; 关键路径含 a(已分配) b(未分配) 与里程碑 m(排除在分母外), c 不在关键路径
          pure-allocs [{:task_id "a" :resource_id "r1" :hours_per_day 4}
                       {:task_id "c" :resource_id "r1" :hours_per_day 2}]
          staff (capacity/critical-path-staffing pure-tasks pure-allocs ["a" "b" "m"])]
      (is (true? (:available staff)))
      (is (= 2 (:critical-tasks staff)))
      (is (= 1 (:staffed staff)))
      (is (= 1 (:unstaffed staff)))
      (is (= 50 (:staffing-pct staff)))
      (is (= ["1.2"] (map :wbs_code (:unstaffed-tasks staff))))))
  (testing "关键路径无可分配叶任务时 available=false 且投入率 0"
    (let [staff (capacity/critical-path-staffing [{:task_id "m" :task_type "milestone"}] [] ["m"])]
      (is (false? (:available staff)))
      (is (= 0 (:critical-tasks staff)))
      (is (= 0 (:staffing-pct staff)))
      (is (empty? (:unstaffed-tasks staff)))))
  (testing "read-plan 集成: 关键路径长任务未分配而短任务已分配 -> 缺口命中关键任务, 派生只读无版本漂移"
    (let [id (project!)
          lt (task! id "LONG" 4) st (task! id "SHORT" 1)
          equip (:result (command! plan/create-resource! id [] {:name "机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id st) :resource_id (:resource_id equip) :hours_per_day 4})
      (let [v-before (version id)
            model (plan/read-plan *svc* (actor 9201) id)
            staff (:critical_path_staffing model)]
        (is (= ["LONG"] (map :wbs_code (:unstaffed-tasks staff))))
        (is (true? (:available staff)))
        (is (= 1 (:critical-tasks staff)))
        (is (= 0 (:staffed staff)))
        (is (= 1 (:unstaffed staff)))
        (is (= 0 (:staffing-pct staff)))
        (is (= v-before (version id)))
        (is (= v-before (:project_version model)))))))

(deftest critical-path-sensitivity-is-derived-read-only
  (testing "纯函数口径: 非汇总叶任务按总时差分档 (关键=0/近关键 0<f<=band/宽松>band), band 随跨度按比例 (20 天 -> 2), 近关键清单按时差升序并带 WBS"
    (let [sched {:working_days 20
                 :tasks [{:task_id "c1" :name "关键甲" :task_type "task" :duration_days 3 :total_float 0}
                         {:task_id "c2" :name "关键乙" :task_type "task" :duration_days 2 :total_float 0}
                         {:task_id "n1" :name "近关键甲" :task_type "task" :duration_days 4 :total_float 2}
                         {:task_id "n2" :name "近关键乙" :task_type "task" :duration_days 1 :total_float 1}
                         {:task_id "k1" :name "宽松" :task_type "task" :duration_days 5 :total_float 8}
                         {:task_id "s" :name "汇总" :task_type "summary" :duration_days 0 :total_float 0}]}
          raws [{:task_id "c1" :wbs_code "1.1"} {:task_id "n1" :wbs_code "1.2"} {:task_id "n2" :wbs_code "1.3"}]
          sens (schedule/float-sensitivity sched raws)]
      (is (true? (:available sens)))
      (is (= 2 (:band sens)))
      (is (= 5 (:leaf-count sens)))
      (is (= 2 (:critical-count sens)))
      (is (= 2 (:near-critical-count sens)))
      (is (= 1 (:comfortable-count sens)))
      (is (= [1 2] (map :total_float (:near-critical-tasks sens))))
      (is (= "1.3" (:wbs_code (first (:near-critical-tasks sens)))))
      (is (= 1 (:min-near-float sens)))))
  (testing "band 随项目工作日跨度按比例放大: 50 天 -> 5, 使总时差 4 的任务落入近关键 (20 天时它算宽松)"
    (let [sched {:working_days 50 :tasks [{:task_id "x" :name "时差4" :task_type "task" :duration_days 2 :total_float 4}]}
          sens (schedule/float-sensitivity sched [])]
      (is (= 5 (:band sens)))
      (is (= 1 (:near-critical-count sens)))
      (is (= 0 (:comfortable-count sens)))))
  (testing "空排程 available=false, 计数全 0, 清单空, 最小近关键为 nil, band 取下限 2"
    (let [sens (schedule/float-sensitivity {:working_days nil :tasks []} [])]
      (is (false? (:available sens)))
      (is (= 2 (:band sens)))
      (is (= 0 (:leaf-count sens)))
      (is (zero? (+ (:critical-count sens) (:near-critical-count sens) (:comfortable-count sens))))
      (is (empty? (:near-critical-tasks sens)))
      (is (nil? (:min-near-float sens)))))
  (testing "read-plan 集成: 关键链与松弛链共存, 三档划分穷尽所有叶任务且无版本漂移"
    (let [id (project!)
          a (task! id "S1A" 3) b (task! id "S1B" 3)
          _ (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b) :dependency_type "FS" :lag_days 0})
          c (task! id "S2" 2)
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          sens (:schedule_sensitivity model)]
      (is (some? sens))
      (is (true? (:available sens)))
      (is (= (:leaf-count sens)
             (+ (:critical-count sens) (:near-critical-count sens) (:comfortable-count sens))))
      (is (pos? (:critical-count sens)))
      (is (pos? (:band sens)))
      (is (every? #(and (pos? (:total_float %)) (<= (:total_float %) (:band sens))) (:near-critical-tasks sens)))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))

(deftest schedule-tightness-is-derived-read-only
  (testing "纯函数口径: 非汇总叶任务的关键占比/平均/最小/最大总时差与定性档位 (5 叶 [0,0,2,1,8] -> 关键2 占40% 均2.2 档tight)"
    (let [sched {:working_days 20
                 :tasks [{:task_id "c1" :name "关键甲" :task_type "task" :total_float 0}
                         {:task_id "c2" :name "关键乙" :task_type "task" :total_float 0}
                         {:task_id "n1" :name "近关键甲" :task_type "task" :total_float 2}
                         {:task_id "n2" :name "近关键乙" :task_type "task" :total_float 1}
                         {:task_id "k1" :name "宽松" :task_type "task" :total_float 8}
                         {:task_id "s" :name "汇总" :task_type "summary" :total_float 0}]}
          t (schedule/float-tightness sched)]
      (is (true? (:available t)))
      (is (= 5 (:leaf-count t)))
      (is (= 2 (:critical-count t)))
      (is (= 40 (:critical-pct t)))
      (is (= 2.2 (:avg-float t)))
      (is (= 0 (:min-float t)))
      (is (= 8 (:max-float t)))
      (is (= 20 (:span t)))
      (is (= "tight" (:tightness-level t)))))
  (testing "档位阈值: 全关键 -> 100% very-tight; 10 叶 1 关键 -> 10% moderate"
    (let [vt (schedule/float-tightness {:working_days 10 :tasks [{:task_id "a" :task_type "task" :total_float 0}
                                                                 {:task_id "b" :task_type "task" :total_float 0}]})
          leaves (into [{:task_id "c" :task_type "task" :total_float 0}]
                       (for [i (range 9)] {:task_id (str "x" i) :task_type "task" :total_float 5}))
          md (schedule/float-tightness {:working_days 30 :tasks leaves})]
      (is (= 100 (:critical-pct vt)))
      (is (= "very-tight" (:tightness-level vt)))
      (is (= 10 (:critical-pct md)))
      (is (= "moderate" (:tightness-level md)))))
  (testing "空排程 available=false, 占比0 均0 最小最大nil 档位nil"
    (let [t (schedule/float-tightness {:working_days nil :tasks []})]
      (is (false? (:available t)))
      (is (= 0 (:leaf-count t)))
      (is (= 0 (:critical-pct t)))
      (is (= 0 (:avg-float t)))
      (is (nil? (:min-float t)))
      (is (nil? (:max-float t)))
      (is (nil? (:tightness-level t)))))
  (testing "read-plan 集成: 暴露 :schedule_tightness 且关键占比与关键数一致, 无版本漂移"
    (let [id (project!)
          a (task! id "T1A" 3) b (task! id "T1B" 3)
          _ (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b) :dependency_type "FS" :lag_days 0})
          c (task! id "T2" 2)
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          t (:schedule_tightness model)]
      (is (some? t))
      (is (true? (:available t)))
      (is (= (:critical-pct t)
             (int (Math/round (* 100.0 (/ (double (:critical-count t)) (double (:leaf-count t))))))))
      (is (contains? #{"very-tight" "tight" "moderate" "loose"} (:tightness-level t)))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))

(deftest network-connectivity-is-derived-read-only
  (testing "纯函数口径: 单一连通链 A->B->C -> linked3 占100% 未链接0 段数1 档connected"
    (let [tasks [{:task_id "A" :wbs_code "1.1" :name "甲" :task_type "task"}
                 {:task_id "B" :wbs_code "1.2" :name "乙" :task_type "task"}
                 {:task_id "C" :wbs_code "1.3" :name "丙" :task_type "task"}]
          deps [{:predecessor_id "A" :successor_id "B"}
                {:predecessor_id "B" :successor_id "C"}]
          n (schedule/network-connectivity tasks deps)]
      (is (true? (:available n)))
      (is (= 3 (:leaf-count n)))
      (is (= 2 (:dependency-count n)))
      (is (= 3 (:linked-count n)))
      (is (= 100 (:linked-pct n)))
      (is (= 0 (:unlinked-count n)))
      (is (empty? (:unlinked-tasks n)))
      (is (= 1 (:component-count n)))
      (is (= "connected" (:connectivity-level n)))))
  (testing "纯函数口径: 存在孤立任务 -> A->B 链接, C 完全未链接; linked2 占67% 未链接清单含 C 档unlinked 段数仍1"
    (let [tasks [{:task_id "A" :wbs_code "1.1" :name "甲" :task_type "task"}
                 {:task_id "B" :wbs_code "1.2" :name "乙" :task_type "task"}
                 {:task_id "C" :wbs_code "1.3" :name "孤丙" :task_type "task"}]
          deps [{:predecessor_id "A" :successor_id "B"}]
          n (schedule/network-connectivity tasks deps)]
      (is (= 3 (:leaf-count n)))
      (is (= 1 (:dependency-count n)))
      (is (= 2 (:linked-count n)))
      (is (= 67 (:linked-pct n)))
      (is (= 1 (:unlinked-count n)))
      (is (= [{:task_id "C" :wbs_code "1.3" :name "孤丙"}] (:unlinked-tasks n)))
      (is (= 1 (:component-count n)))
      (is (= "unlinked" (:connectivity-level n)))))
  (testing "纯函数口径: 两段平行链 A->B 与 C->D 无孤立 -> linked100% 未链接0 段数2 档fragmented"
    (let [tasks [{:task_id "A" :wbs_code "1.1" :task_type "task"}
                 {:task_id "B" :wbs_code "1.2" :task_type "task"}
                 {:task_id "C" :wbs_code "1.3" :task_type "task"}
                 {:task_id "D" :wbs_code "1.4" :task_type "task"}]
          deps [{:predecessor_id "A" :successor_id "B"}
                {:predecessor_id "C" :successor_id "D"}]
          n (schedule/network-connectivity tasks deps)]
      (is (= 4 (:leaf-count n)))
      (is (= 4 (:linked-count n)))
      (is (= 100 (:linked-pct n)))
      (is (= 0 (:unlinked-count n)))
      (is (= 2 (:component-count n)))
      (is (= "fragmented" (:connectivity-level n)))))
  (testing "汇总任务不计入分母, 叶子经汇总节点相连仍算 linked"
    (let [tasks [{:task_id "A" :wbs_code "1.1" :task_type "task"}
                 {:task_id "S" :wbs_code "1" :task_type "summary"}
                 {:task_id "B" :wbs_code "1.2" :task_type "task"}]
          deps [{:predecessor_id "A" :successor_id "S"}
                {:predecessor_id "S" :successor_id "B"}]
          n (schedule/network-connectivity tasks deps)]
      (is (= 2 (:leaf-count n)))
      (is (= 2 (:linked-count n)))
      (is (= 0 (:unlinked-count n)))
      (is (= 1 (:component-count n)))
      (is (= "connected" (:connectivity-level n)))))
  (testing "空计划 available=false, 计数0, 未链接空, 段数0, 档位nil"
    (let [n (schedule/network-connectivity [] [])]
      (is (false? (:available n)))
      (is (= 0 (:leaf-count n)))
      (is (= 0 (:dependency-count n)))
      (is (= 0 (:linked-count n)))
      (is (= 0 (:linked-pct n)))
      (is (= 0 (:unlinked-count n)))
      (is (empty? (:unlinked-tasks n)))
      (is (= 0 (:component-count n)))
      (is (nil? (:connectivity-level n)))))
  (testing "read-plan 集成: 暴露 :schedule_connectivity, 孤立任务被标出, 无版本漂移"
    (let [id (project!)
          a (task! id "NCA" 2) b (task! id "NCB" 2) orphan (task! id "NCC" 2)
          _ (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b) :dependency_type "FS" :lag_days 0})
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          n (:schedule_connectivity model)]
      (is (some? n))
      (is (true? (:available n)))
      (is (= (:leaf-count n) (+ (:linked-count n) (:unlinked-count n))))
      (is (= (:linked-pct n)
             (int (Math/round (* 100.0 (/ (double (:linked-count n)) (double (:leaf-count n))))))))
      (is (pos? (:unlinked-count n)))
      (is (some #(= (:task_id orphan) (:task_id %)) (:unlinked-tasks n)))
      (is (= "unlinked" (:connectivity-level n)))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))

(deftest dependency-type-mix-is-derived-read-only
  (testing "纯函数口径: 全 FS -> parallel0 pct0 档fully-serial, by-type 含四类零计数"
    (let [deps [{:dependency_type "FS" :lag_days 0}
                {:dependency_type "FS" :lag_days 0}
                {:dependency_type "FS" :lag_days 0}]
          m (schedule/dependency-type-mix deps)]
      (is (true? (:available m)))
      (is (= 3 (:dependency-count m)))
      (is (= 3 (:fs-count m)))
      (is (= 0 (:parallel-count m)))
      (is (= 0 (:parallel-pct m)))
      (is (= 0 (:lagged-count m)))
      (is (= "fully-serial" (:serialization-level m)))
      (is (= ["FS" "SS" "FF" "SF"] (map :type (:by-type m))))
      (is (= 3 (:count (first (:by-type m)))))
      (is (= 0 (:count (last (:by-type m)))))))
  (testing "纯函数口径: FS+FS(lag)+SS+FF(lag) -> parallel=SS+FF=2 pct50 档parallel-heavy, lagged=2"
    (let [deps [{:dependency_type "FS" :lag_days 0}
                {:dependency_type "FS" :lag_days 2}
                {:dependency_type "SS" :lag_days 0}
                {:dependency_type "FF" :lag_days -1}]
          m (schedule/dependency-type-mix deps)]
      (is (= 4 (:dependency-count m)))
      (is (= 2 (:fs-count m)))
      (is (= 2 (:parallel-count m)))
      (is (= 50 (:parallel-pct m)))
      (is (= 2 (:lagged-count m)))
      (is (= 50 (:lagged-pct m)))
      (is (= "parallel-heavy" (:serialization-level m)))
      (is (= {:type "SS" :label "开始-开始" :count 1 :pct 25}
             (second (:by-type m))))))
  (testing "纯函数口径: mostly-serial 边界 parallel-pct<25 (4FS+1SS=20%)"
    (let [deps (concat (repeat 4 {:dependency_type "FS" :lag_days 0})
                       [{:dependency_type "SS" :lag_days 0}])
          m (schedule/dependency-type-mix deps)]
      (is (= 5 (:dependency-count m)))
      (is (= 1 (:parallel-count m)))
      (is (= 20 (:parallel-pct m)))
      (is (= "mostly-serial" (:serialization-level m)))))
  (testing "纯函数口径: mixed 边界 25<=parallel-pct<50 (3FS+1SF=25%)"
    (let [deps (concat (repeat 3 {:dependency_type "FS" :lag_days 0})
                       [{:dependency_type "SF" :lag_days 0}])
          m (schedule/dependency-type-mix deps)]
      (is (= 4 (:dependency-count m)))
      (is (= 1 (:parallel-count m)))
      (is (= 25 (:parallel-pct m)))
      (is (= "mixed" (:serialization-level m)))))
  (testing "无效或缺失类型不计入分母"
    (let [deps [{:dependency_type "FS" :lag_days 0}
                {:dependency_type "XX" :lag_days 0}
                {:lag_days 0}]
          m (schedule/dependency-type-mix deps)]
      (is (= 1 (:dependency-count m)))
      (is (= 1 (:fs-count m)))))
  (testing "空依赖 available=false, 计数0, by-type 四类皆零, 档位nil"
    (let [m (schedule/dependency-type-mix [])]
      (is (false? (:available m)))
      (is (= 0 (:dependency-count m)))
      (is (= 0 (:fs-count m)))
      (is (= 0 (:parallel-count m)))
      (is (= 0 (:parallel-pct m)))
      (is (= 0 (:lagged-count m)))
      (is (nil? (:serialization-level m)))
      (is (= [0 0 0 0] (map :count (:by-type m))))))
  (testing "read-plan 集成: 暴露 :dependency_type_mix, parallel-pct 与计数一致, 无版本漂移"
    (let [id (project!)
          a (task! id "DTA" 3) b (task! id "DTB" 3) c (task! id "DTC" 2)
          _ (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b) :dependency_type "FS" :lag_days 0})
          _ (command! plan/create-dependency! id [] {:predecessor_id (:task_id b) :successor_id (:task_id c) :dependency_type "SS" :lag_days 1})
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          m (:dependency_type_mix model)]
      (is (some? m))
      (is (true? (:available m)))
      (is (= (:parallel-pct m)
             (int (Math/round (* 100.0 (/ (double (:parallel-count m)) (double (:dependency-count m))))))))
      (is (contains? #{"fully-serial" "mostly-serial" "mixed" "parallel-heavy"} (:serialization-level m)))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))

(deftest duration-granularity-is-derived-read-only
  (testing "纯函数口径: 均衡细粒度 [4,4,4] -> dominant33 fine, 无过粗, 中位/平均 4.0"
    (let [tasks [{:task_id "a" :wbs_code "A" :name "甲" :task_type "task" :duration_days 4}
                 {:task_id "b" :wbs_code "B" :name "乙" :task_type "task" :duration_days 4}
                 {:task_id "c" :wbs_code "C" :name "丙" :task_type "task" :duration_days 4}]
          m (schedule/duration-granularity tasks)]
      (is (true? (:available m)))
      (is (= 3 (:leaf-count m)))
      (is (= 12 (:sum-days m)))
      (is (= 4 (:max-duration m)))
      (is (= 4 (:min-duration m)))
      (is (= 4.0 (:avg-days m)))
      (is (= 4 (:median-days m)))
      (is (= 0 (:coarse-count m)))
      (is (= 33 (:dominant-pct m))) ;; round(100*4/12)
      (is (= "fine" (:granularity-level m)))))
  (testing "纯函数口径: 单巨任务 [12,2,2] -> dominant75 hard-to-track, coarse1 含该任务, 中位2"
    (let [tasks [{:task_id "a" :wbs_code "A" :name "吞链巨任务" :task_type "task" :duration_days 12}
                 {:task_id "b" :wbs_code "B" :name "乙" :task_type "task" :duration_days 2}
                 {:task_id "c" :wbs_code "C" :name "丙" :task_type "task" :duration_days 2}]
          m (schedule/duration-granularity tasks)]
      (is (= 3 (:leaf-count m)))
      (is (= 16 (:sum-days m)))
      (is (= 12 (:max-duration m)))
      (is (= 2 (:min-duration m)))
      (is (= 5.3 (:avg-days m))) ;; round(10*16/3)/10 = 5.3
      (is (= 2 (:median-days m))) ;; 奇数取中间项 (sorted [2,2,12])
      (is (= 1 (:coarse-count m)))
      (is (= 75 (:dominant-pct m))) ;; round(100*12/16)
      (is (= "hard-to-track" (:granularity-level m)))
      (is (= [{:task_id "a" :wbs_code "A" :name "吞链巨任务" :duration_days 12}] (:coarse-tasks m)))))
  (testing "纯函数口径: 多过粗但无单任务独大 [10,10,10,10] -> coarse-count4 dominant25 档 coarse"
    (let [tasks (mapv (fn [i] {:task_id (str i) :wbs_code (str i) :name (str i)
                               :task_type "task" :duration_days 10}) (range 4))
          m (schedule/duration-granularity tasks)]
      (is (= 40 (:sum-days m)))
      (is (= 4 (:coarse-count m)))
      (is (= 25 (:dominant-pct m))) ;; 一个任务只占 25%, 未达 40
      (is (= "coarse" (:granularity-level m))) ;; 有过粗但非单任务吞链
      (is (= 10.0 (:avg-days m)))))
  (testing "纯函数口径: 偶数中位取中间两项均值 [2,2,3,3] -> median2.5, 全<10 fine"
    (let [tasks (mapv (fn [d] {:task_id (str "t" d (rand)) :wbs_code "W" :name "任"
                               :task_type "task" :duration_days d}) [2 2 3 3])
          m (schedule/duration-granularity tasks)]
      (is (= 10 (:sum-days m)))
      (is (= 2.5 (:median-days m))) ;; (2+3)/2
      (is (= 2.5 (:avg-days m))) ;; round(10*10/4)/10
      (is (= 30 (:dominant-pct m))) ;; round(100*3/10)
      (is (= "fine" (:granularity-level m)))))
  (testing "汇总节点不计入叶分母: summary(100) 被排除, 两只 3 天叶 -> leaf-count2 dominant50"
    (let [tasks [{:task_id "s" :wbs_code "1" :name "汇总" :task_type "summary" :duration_days 100}
                 {:task_id "a" :wbs_code "1.1" :name "甲" :task_type "task" :duration_days 3}
                 {:task_id "b" :wbs_code "1.2" :name "乙" :task_type "task" :duration_days 3}]
          m (schedule/duration-granularity tasks)]
      (is (= 2 (:leaf-count m))) ;; summary 不进入分母
      (is (= 6 (:sum-days m))) ;; 只算两只 3 天叶任务, summary(100) 被排除
      (is (= 3 (:max-duration m))) ;; max 只统计叶任务, 不被 summary 的 100 污染
      (is (= 50 (:dominant-pct m)))
      (is (= 0 (:coarse-count m)))))
  (testing "空任务 available=false, 各计数0, 中位/max/min nil, 档位nil"
    (let [m (schedule/duration-granularity [])]
      (is (false? (:available m)))
      (is (= 0 (:leaf-count m)))
      (is (= 0 (:sum-days m)))
      (is (= 0 (:dominant-pct m)))
      (is (= 0 (:coarse-count m)))
      (is (nil? (:median-days m)))
      (is (nil? (:max-duration m)))
      (is (nil? (:min-duration m)))
      (is (nil? (:granularity-level m)))
      (is (= [] (:coarse-tasks m)))))
  (testing "read-plan 集成: 暴露 :duration_granularity, dominant-pct 与 max/sum 一致, 无版本漂移"
    (let [id (project!)
          a (task! id "DGA" 12) b (task! id "DGB" 2) c (task! id "DGC" 2)
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          m (:duration_granularity model)]
      (is (some? m))
      (is (true? (:available m)))
      (is (= 3 (:leaf-count m)))
      (is (= 12 (:max-duration m)))
      (is (= 1 (:coarse-count m)))
      (is (= (:dominant-pct m)
             (int (Math/round (* 100.0 (/ (double (:max-duration m)) (double (:sum-days m))))))))
      (is (= "hard-to-track" (:granularity-level m)))
      (is (= "DGA" (:wbs_code (first (:coarse-tasks m)))))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))
