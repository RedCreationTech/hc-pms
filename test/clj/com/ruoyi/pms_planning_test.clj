(ns com.ruoyi.pms-planning-test
  "计划工作台的隔离数据库集成测试和排程算法验收."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.finance-time :as finance-time]
            [com.ruoyi.domain.pms.governance.approval :as approval]
            [com.ruoyi.domain.pms.service :as pms]
            [com.ruoyi.domain.pms.planning :as plan]
            [com.ruoyi.domain.pms.planning.schedule :as schedule]
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
