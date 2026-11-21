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
            [com.ruoyi.domain.pms.planning.store :as store]
            [com.ruoyi.domain.pms.planning.tasks :as tasks]
            [com.ruoyi.domain.pms.planning.resources :as resources]
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
      (doseq [id [9201 9202 9203 9204 9210 9299]]
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


(defn- error-info
  "提取业务错误状态与文案, 成功时返回 nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))

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

(defn- fake-task-q
  "task-input! 纯表征用的桩查询: WBS 编号, 父任务, 结构节点, 用户与成员关系按罐头返回."
  [{:keys [existing parent nodes users members]}]
  (fn [name params]
    (case name
      :planning/task-code (get existing (:wbs_code params))
      :planning/task (get parent (:task_id params))
      :pms/node (get nodes [(:project_id params) (:node_id params)])
      :pms/user (get users (:user_id params))
      :pms/member (get members [(:project_id params) (:user_id params)])
      nil)))


(defn- task-in
  "在给定桩下校验任务设计: 通过返回任务行, 失败返回 [status message]."
  [stub project task-id body]
  (try (#'tasks/task-input! (fake-task-q stub) project task-id body)
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(def task-stub
  "无既有编号, 无父任务, 负责人 9201 存在且为项目成员的桩."
  {:users {9201 {:user_id 9201}} :members {[1 9201] {:role "editor"}}})


;; task-input! 重构前的行为钉: 类型/工期取值域/日期缺省/编号冲突/负责人/节点门与组装形状, 纯函数只读桩.
(deftest task-input-validates-type-duration-dates-and-keys
  (let [project {:project_id 1 :start_date "2026-08-01"}
        tid "22222222-2222-2222-2222-222222222222"
        base {:wbs_code "WBS.1" :name "任务甲" :duration_days 5 :start_date "2026-09-01" :owner_id 9201}]
    (testing "合法任务组装: 类型缺省 task, 阶段编码去空白, 无节点给 nil"
      (let [row (task-in task-stub project tid (assoc base :stage_code "  S4 " :description ""))]
        (is (= tid (:task_id row)))
        (is (= 1 (:project_id row)))
        (is (= "task" (:task_type row)))
        (is (= "WBS.1" (:wbs_code row)))
        (is (= "S4" (:stage_code row)))
        (is (= "" (:description row)))
        (is (nil? (:node_id row)))
        (is (= 9201 (:owner_id row)))
        (is (= "2026-09-01" (:start_date row)))))
    (testing "任务类型受控, 缺省 task"
      (is (= [400 "无效任务类型"] (task-in task-stub project tid (assoc base :task_type "epic"))))
      (is (= "summary" (:task_type (task-in task-stub project tid
                                             (assoc base :task_type "summary" :duration_days 0))))))
    (testing "工期按类型取值域: task 1..3650, summary/milestone 0..0"
      (is (= [400 "计划工期必须是 1 至 3650 的整数"]
             (task-in task-stub project tid (assoc base :duration_days 0))))
      (is (= [400 "计划工期必须是 0 至 0 的整数"]
             (task-in task-stub project tid (assoc base :task_type "summary" :duration_days 1))))
      (is (= 0 (:duration_days (task-in task-stub project tid
                                        (assoc base :task_type "milestone" :duration_days 0))))))
    (testing "开始日期缺省到项目, 缺失或格式错被拒"
      (is (= "2026-08-01" (:start_date (task-in task-stub project tid (dissoc base :start_date)))))
      (is (= [400 "请填写任务或项目开始日期"]
             (task-in task-stub {:project_id 1} tid (dissoc base :start_date))))
      (is (= [400 "任务开始日期格式必须为 YYYY-MM-DD"]
             (task-in task-stub project tid (assoc base :start_date "2026/09/01")))))
    (testing "WBS 编号必填且不与他任务冲突, 同任务更新放行"
      (is (= [400 "WBS编号不能为空或超出长度限制"]
             (task-in task-stub project tid (assoc base :wbs_code " "))))
      (is (= [409 "WBS编号已存在"]
             (task-in (assoc task-stub :existing {"WBS.1" {:task_id "33333333-3333-3333-3333-333333333333"}})
                      project tid base)))
      (is (map? (task-in (assoc task-stub :existing {"WBS.1" {:task_id tid}}) project tid base))))
    (testing "名称必填, 负责人与结构节点须属于项目"
      (is (= [400 "任务名称不能为空或超出长度限制"]
             (task-in task-stub project tid (assoc base :name ""))))
      (is (= [400 "任务负责人不存在或已停用"]
             (task-in (dissoc task-stub :users) project tid (assoc base :owner_id 9999))))
      (is (= [400 "结构节点不存在或不属于当前项目"]
             (task-in task-stub project tid (assoc base :node_id "N1")))))))


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

(defn- fake-resource-q
  "resources/input! 纯表征用的桩查询: 成员, 计划日历与既有资源按罐头返回 (不查库)."
  [{:keys [users members plan resources]}]
  (fn [name params]
    (case name
      :pms/user (get users (:user_id params))
      :pms/member (get members [(:project_id params) (:user_id params)])
      :planning/plan plan
      :planning/resources (or resources [])
      nil)))


(defn- resource-in
  "在给定桩下校验资源输入: 通过返回资源行, 失败返回 [status message]."
  [stub project resource-id body]
  (try (#'resources/input! (fake-resource-q stub) project resource-id body)
       (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


;; resources/input! 重构前的行为钉: 类型/人员设备交叉/重档/名称/成员/容量门与日历缺省, 纯函数只读桩.
(deftest resource-input-validates-type-member-and-duplicate
  (let [project {:project_id 1}
        rid "44444444-4444-4444-4444-444444444444"
        stub {:users {9201 {:user_id 9201}} :members {[1 9201] {:role "member"}}}]
    (testing "人员资源: 类型缺省 person, 容量缺省取计划日历工时"
      (let [row (resource-in stub project rid {:name "张三" :user_id 9201})]
        (is (= "person" (:resource_type row)))
        (is (= 9201 (:user_id row)))
        (is (= 8M (:daily_capacity row))))
      (is (= 9M (:daily_capacity (resource-in (assoc stub :plan {:project_id 1 :calendar_json "{\"hours_per_day\":9}"})
                                               project rid {:name "张三" :user_id 9201})))))
    (testing "设备资源: 不带人员, 显式容量"
      (let [row (resource-in stub project rid {:name "吊车" :resource_type "equipment" :daily_capacity 7.5})]
        (is (= "equipment" (:resource_type row)))
        (is (nil? (:user_id row)))
        (is (= 7.5M (:daily_capacity row)))))
    (testing "类型枚举与人员/设备交叉门"
      (is (= [400 "资源类别必须为 person 或 equipment"]
             (resource-in stub project rid {:name "x" :resource_type "vehicle"})))
      (is (= [400 "人员资源必须关联当前项目成员"]
             (resource-in stub project rid {:name "x"})))
      (is (= [400 "设备资源不能关联人员账号"]
             (resource-in stub project rid {:name "x" :resource_type "equipment" :user_id 9201}))))
    (testing "同一成员只建一个资源档案, 同 id 更新放行"
      (is (= [409 "该成员已经建立资源档案"]
             (resource-in (assoc stub :resources [{:resource_id "55555555-5555-5555-5555-555555555555" :user_id 9201}])
                          project rid {:name "张三" :user_id 9201})))
      (is (map? (resource-in (assoc stub :resources [{:resource_id rid :user_id 9201}])
                             project rid {:name "张三" :user_id 9201}))))
    (testing "名称必填, 成员须存在, 容量两位小数且 0 至 24"
      (is (= [400 "资源名称不能为空或超出长度限制"]
             (resource-in stub project rid {:name "" :user_id 9201})))
      (is (= [400 "资源成员不存在或已停用"]
             (resource-in (dissoc stub :users) project rid {:name "x" :user_id 9999})))
      (is (= [400 "每日容量必须在 0 至 24 之间"]
             (resource-in stub project rid {:name "x" :user_id 9201 :daily_capacity 25})))
      (is (= [400 "每日容量最多保留两位小数"]
             (resource-in stub project rid {:name "x" :user_id 9201 :daily_capacity 1.111}))))))


;; batch45 (mutator 方向: cover the uncovered): 补齐 resources.clj 中 cloverage 标记为未覆盖的端点与错误分支.
(deftest resource-endpoint-lifecycle-and-gates
  (let [id (project!)
        task (task! id "R-1" 3)
        summary (:result (command! plan/create-task! id [] {:wbs_code "R-S" :name "汇总"
                                                            :task_type "summary" :duration_days 0}))
        rid (:resource_id (:result (command! plan/create-resource! id [] {:name "张三" :user_id 9201})))]
    (testing "工作日历: 合法保存, 空工作日与非数组假日被拒"
      (let [calendar (:result (command! plan/update-calendar! id []
                                        {:working_days [1 2 3 4 5] :holidays ["2026-10-01"]
                                         :extra_workdays [] :hours_per_day 8}))]
        (is (= [1 2 3 4 5] (:working_days calendar)))
        (is (= ["2026-10-01"] (:holidays calendar))))
      (is (= 400 (status #(command! plan/update-calendar! id [] {:working_days []}))))
      (is (= 400 (status #(command! plan/update-calendar! id [] {:working_days [1] :holidays "x"})))))
    (testing "更新资源: 日容量改写保留类型与负责人, 改类型到设备因带人员被拒"
      (let [updated (:result (command! plan/update-resource! id [rid] {:daily_capacity 6}))]
        (is (= 6M (:daily_capacity updated)))
        (is (= "person" (:resource_type updated)))
        (is (= 9201 (:user_id updated))))
      (is (= 400 (status #(command! plan/update-resource! id [rid] {:resource_type "equipment"})))))
    (testing "容量覆盖: 缺日期被拒, 合法日期写入 (0 表示不可用)"
      (is (= 400 (status #(command! plan/set-capacity! id [rid] {:capacity_hours 4}))))
      (is (= "2026-09-21" (:date (:result (command! plan/set-capacity! id [rid]
                                                     {:date "2026-09-21" :capacity_hours 0}))))))
    (testing "分配: 普通任务可分配, 汇总任务与重复分配被拒"
      (let [aid (:allocation_id (:result (command! plan/create-allocation! id []
                                                   {:task_id (:task_id task) :resource_id rid :hours_per_day 6})))]
        (is (string? aid))
        (is (= 409 (status #(command! plan/create-allocation! id []
                                       {:task_id (:task_id task) :resource_id rid :hours_per_day 4}))))
        (testing "删除分配: 存在可删, 不存在 404"
          (is (= {:allocation_id aid :deleted true}
                 (:result (command! plan/delete-allocation! id [aid] {}))))
          (is (= 404 (status #(command! plan/delete-allocation! id ["nope"] {}))))))
      (is (= 400 (status #(command! plan/create-allocation! id []
                                     {:task_id (:task_id summary) :resource_id rid :hours_per_day 2})))))
    (testing "删除资源: 有分配 409, 释放分配后可删"
      (let [aid (:allocation_id (:result (command! plan/create-allocation! id []
                                                   {:task_id (:task_id task) :resource_id rid :hours_per_day 4})))]
        (is (= 409 (status #(command! plan/delete-resource! id [rid] {}))))
        (command! plan/delete-allocation! id [aid] {}))
      (is (= {:resource_id rid :deleted true}
             (:result (command! plan/delete-resource! id [rid] {})))))))


;; batch46 (mutator 方向: cover the uncovered): 补齐 planning/tasks 写路径守卫 — 父任务类型, 类型变更四态, 删除三态, 依赖三门与404.
(deftest task-write-guards-for-type-change-delete-and-dependency
  (let [id (project!)
        feedback-task (:task_id (task! id "F-1" 2))]
    (testing "父任务必须是汇总任务"
      (let [normal (task! id "G-1" 2)]
        (is (= 400 (status #(command! plan/create-task! id []
                                       {:wbs_code "G-1.1" :name "子任务" :duration_days 1
                                        :parent_id (:task_id normal)}))))))
    (testing "类型变更: 存在依赖时409"
      (let [a (task! id "T-2" 2) b (task! id "T-3" 2)]
        (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b)
                                                 :dependency_type "FS"})
        (is (= 409 (status #(command! plan/update-task! id [(:task_id a)]
                                      {:task_type "summary" :duration_days 0}))))))
    (testing "类型变更: 存在资源分配时409"
      (let [t (task! id "T-4" 2)
            r (:result (command! plan/create-resource! id [] {:name "设备甲" :resource_type "equipment"}))]
        (command! plan/create-allocation! id [] {:task_id (:task_id t) :resource_id (:resource_id r)
                                                 :hours_per_day 4})
        (is (= 409 (status #(command! plan/update-task! id [(:task_id t)]
                                      {:task_type "summary" :duration_days 0}))))))
    (testing "类型变更: 已有实际进度 (非 todo) 时409"
      (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id])
      (command! plan/task-feedback! id [feedback-task] {:status "in_progress" :percent_complete 50
                                                       :remaining_days 1})
      (is (= 409 (status #(command! plan/update-task! id [feedback-task]
                                    {:task_type "summary" :duration_days 0})))))
    (testing "删除: 无关联任务可删, 有资源分配或实际反馈409"
      (let [free (task! id "D-1" 2)]
        (is (= {:task_id (:task_id free) :deleted true}
               (:result (command! plan/delete-task! id [(:task_id free)] {})))))
      (let [t (task! id "D-2" 2)
            r (:result (command! plan/create-resource! id [] {:name "设备乙" :resource_type "equipment"}))]
        (command! plan/create-allocation! id [] {:task_id (:task_id t) :resource_id (:resource_id r)
                                                 :hours_per_day 4})
        (is (= 409 (status #(command! plan/delete-task! id [(:task_id t)] {})))))
      (is (= 409 (status #(command! plan/delete-task! id [feedback-task] {})))))
    (testing "依赖: 自依赖与涉汇总任务400, 非法类型400, 重复409, 删除不存在404"
      (let [a (task! id "K-1" 2) b (task! id "K-2" 2)
            s (:result (command! plan/create-task! id [] {:wbs_code "K-S" :name "汇总" :task_type "summary"
                                                          :duration_days 0}))]
        (is (= 400 (status #(command! plan/create-dependency! id []
                                       {:predecessor_id (:task_id a) :successor_id (:task_id a)
                                        :dependency_type "FS"}))))
        (is (= 400 (status #(command! plan/create-dependency! id []
                                       {:predecessor_id (:task_id a) :successor_id (:task_id s)
                                        :dependency_type "FS"}))))
        (is (= 400 (status #(command! plan/create-dependency! id []
                                       {:predecessor_id (:task_id a) :successor_id (:task_id b)
                                        :dependency_type "XX"}))))
        (command! plan/create-dependency! id [] {:predecessor_id (:task_id a) :successor_id (:task_id b)
                                                 :dependency_type "FS"})
        (is (= 409 (status #(command! plan/create-dependency! id []
                                       {:predecessor_id (:task_id a) :successor_id (:task_id b)
                                        :dependency_type "FS"}))))
        (is (= 404 (status #(command! plan/delete-dependency! id ["nope"] {}))))))))


;; batch49 (mutator 方向: cover the uncovered): planning/store 两守卫 — 收尾中不可改计划409, 资源不存在404.
(deftest planning-store-guards-closing-and-unknown-resource
  (let [id (project!)]
    (testing "项目收尾中不能修改计划"
      (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='closing' WHERE project_id=?" id])
      (is (= 409 (status #(command! plan/create-task! id []
                                    {:wbs_code "C-1" :name "收尾任务" :duration_days 1}))))
      (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='draft' WHERE project_id=?" id]))
  (testing "资源不存在404 (需为合法 UUID 形状)"
    (is (= 404 (status #(command! plan/update-resource! id ["11111111-1111-1111-1111-111111111111"]
                                  {:daily_capacity 4})))))))


;; batch53 (mutator 方向: cover the uncovered): 计划基线提交/审批守卫 — 未知基线404, 草稿409, 空汇总409, 重复提交409.
;; 注: approval-ready! 的 偏离快照409 与 execution-ready! 的 基线不一致409 在当前实现下不可达 —
;; 任何设计变更都经 store/mutation! 递增计划修订, 提交期间又被 editable! 锁定, 故快照内容与修订号恒同步.
(deftest plan-baseline-submission-guards
  (testing "未知计划基线404"
    (let [id (project!)]
      (is (= 404 (status #(plan/review-plan! *svc* (actor 9202) id "no-such-baseline"
                                              {:version (version id) :decision "approved" :comment "批准"}))))))
  (testing "草稿阶段不能提交计划审批409 (状态门先于任务检查)"
    (let [id (:project_id (pms/create-project! *svc* (actor 1)
                                               {:project_no (str "PLAN-D-" (kernel/id)) :name "草稿计划"
                                                :customer "本地测试" :contract_no "PLAN-TEST" :project_type "equipment"
                                                :manager_id 9201 :dept_id 1 :start_date "2026-09-21"}))]
      (is (= 409 (status #(command! plan/submit-plan! id [] {:comment "草稿期提交"}))))))
  (testing "空汇总任务不能提交审批409"
    (let [id (project!)]
      (command! plan/create-task! id [] {:wbs_code "BL-1" :name "叶子任务" :duration_days 2 :owner_id 9201})
      (command! plan/create-task! id [] {:wbs_code "BL-S" :name "空汇总" :task_type "summary" :duration_days 0})
      (is (= 409 (status #(command! plan/submit-plan! id [] {:comment "含空汇总"}))))))
  (testing "同一计划修订重复提交409"
    (let [id (project!)]
      (command! plan/create-task! id [] {:wbs_code "BL-2" :name "正常任务" :duration_days 2 :owner_id 9201})
      (command! plan/submit-plan! id [] {:comment "初版提交"})
      (is (= 409 (status #(command! plan/submit-plan! id [] {:comment "重复提交"})))))))


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
  "为多个项目建立相同人员的真实资源与分配 (共享人员可用 9204 或显式指定, 避开技能用例的 9203/9210)."
  ([id hours capacity] (shared-allocation! id hours capacity 9204))
  ([id hours capacity uid]
   (pms/set-member! *svc* (actor 1) id {:user_id uid :role "viewer"})
   (let [task (task! id "SHARED" 1)
         resource (:result (command! plan/create-resource! id []
                                     {:name "共享工程师" :resource_type "person" :user_id uid :daily_capacity capacity}))]
     (command! plan/create-allocation! id [] {:task_id (:task_id task) :resource_id (:resource_id resource) :hours_per_day hours})
     resource)))

(deftest shared-person-capacity-preserves-other-project-privacy
  (let [id (project!) other (project!)
        local (shared-allocation! id 5 8) _ (shared-allocation! other 4 8)]
    (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET manager_id=9204 WHERE project_id=?" other])
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

(defn- summary!
  "创建汇总任务 (可挂在某父级汇总任务下), 返回其 :task_id."
  ([id code]
   (:task_id (:result (command! plan/create-task! id [] {:wbs_code code :name code :task_type "summary" :duration_days 0}))))
  ([id code parent]
   (:task_id (:result (command! plan/create-task! id [] {:wbs_code code :name code :task_type "summary" :duration_days 0 :parent_id parent})))))

(defn- child!
  "在指定父级汇总任务下创建叶任务."
  [id code parent]
  (:result (command! plan/create-task! id [] {:wbs_code code :name code :duration_days 1 :owner_id 9201 :parent_id parent})))

(deftest wbs-hierarchy-is-derived-read-only
  (testing "纯函数口径: 全部无父级 -> flat, max-depth1, root=task, 无汇总"
    (let [tasks [{:task_id "a" :parent_id nil :wbs_code "A" :name "甲" :task_type "task"}
                 {:task_id "b" :parent_id nil :wbs_code "B" :name "乙" :task_type "task"}
                 {:task_id "c" :parent_id nil :wbs_code "C" :name "丙" :task_type "task"}]
          m (schedule/wbs-hierarchy tasks)]
      (is (true? (:available m)))
      (is (= 3 (:task-count m)))
      (is (= 3 (:root-count m)))
      (is (= 0 (:summary-count m)))
      (is (= 3 (:leaf-count m)))
      (is (= 1 (:max-depth m)))
      (is (= 1 (:leaf-min-depth m)))
      (is (= 1 (:leaf-max-depth m)))
      (is (= 0 (:depth-spread m)))
      (is (= 0 (:orphan-count m)))
      (is (= [{:level 1 :count 3}] (:level-counts m)))
      (is (= "flat" (:structure-level m)))))
  (testing "纯函数口径: 一汇总两叶 (叶在第二层) -> balanced, summary1 leaf2 root1 max-depth2 spread0"
    (let [tasks [{:task_id "s" :parent_id nil :wbs_code "1" :name "汇总" :task_type "summary"}
                 {:task_id "a" :parent_id "s" :wbs_code "1.1" :name "甲" :task_type "task"}
                 {:task_id "b" :parent_id "s" :wbs_code "1.2" :name "乙" :task_type "task"}]
          m (schedule/wbs-hierarchy tasks)]
      (is (= 3 (:task-count m)))
      (is (= 1 (:root-count m))) ;; 仅汇总节点无父
      (is (= 1 (:summary-count m)))
      (is (= 2 (:leaf-count m)))
      (is (= 2 (:max-depth m)))
      (is (= 2 (:leaf-min-depth m)))
      (is (= 2 (:leaf-max-depth m)))
      (is (= 0 (:depth-spread m)))
      (is (= 0 (:orphan-count m)))
      (is (= [{:level 1 :count 1} {:level 2 :count 2}] (:level-counts m)))
      (is (= "balanced" (:structure-level m)))))
  (testing "纯函数口径: 汇总链 S1>S2>S3>叶 -> max-depth4 deep"
    (let [tasks [{:task_id "s1" :parent_id nil :wbs_code "1" :name "一层" :task_type "summary"}
                 {:task_id "s2" :parent_id "s1" :wbs_code "1.1" :name "二层" :task_type "summary"}
                 {:task_id "s3" :parent_id "s2" :wbs_code "1.1.1" :name "三层" :task_type "summary"}
                 {:task_id "t" :parent_id "s3" :wbs_code "1.1.1.1" :name "叶" :task_type "task"}]
          m (schedule/wbs-hierarchy tasks)]
      (is (= 3 (:summary-count m)))
      (is (= 1 (:leaf-count m)))
      (is (= 4 (:max-depth m)))
      (is (= 4 (:leaf-max-depth m)))
      (is (= "deep" (:structure-level m)))))
  (testing "纯函数口径: 一个顶层叶(d1) + 一条链到d3 -> spread2 且 max-depth3<4 -> unbalanced"
    (let [tasks [{:task_id "x" :parent_id nil :wbs_code "A" :name "平铺叶" :task_type "task"}
                 {:task_id "s1" :parent_id nil :wbs_code "B" :name "汇总" :task_type "summary"}
                 {:task_id "s2" :parent_id "s1" :wbs_code "B.1" :name "子汇总" :task_type "summary"}
                 {:task_id "y" :parent_id "s2" :wbs_code "B.1.1" :name "深叶" :task_type "task"}]
          m (schedule/wbs-hierarchy tasks)]
      (is (= 3 (:max-depth m)))
      (is (= 1 (:leaf-min-depth m))) ;; x 在深度 1
      (is (= 3 (:leaf-max-depth m))) ;; y 在深度 3
      (is (= 2 (:depth-spread m)))
      (is (= "unbalanced" (:structure-level m)))))
  (testing "纯函数口径: 父级缺失 -> orphan-count1 且列入 orphans, 不计入 root"
    (let [tasks [{:task_id "s" :parent_id nil :wbs_code "1" :name "汇总" :task_type "summary"}
                 {:task_id "o" :parent_id "ghost" :wbs_code "1.1" :name "孤儿" :task_type "task"}]
          m (schedule/wbs-hierarchy tasks)]
      (is (= 1 (:orphan-count m)))
      (is (= [{:task_id "o" :wbs_code "1.1" :name "孤儿" :parent_id "ghost"}] (:orphans m)))
      (is (= 1 (:root-count m))) ;; 孤儿有父级引用, 不算顶层
      ))
  (testing "空任务 available=false, 各计数0, level-counts[], 档位nil"
    (let [m (schedule/wbs-hierarchy [])]
      (is (false? (:available m)))
      (is (= 0 (:task-count m)))
      (is (= 0 (:max-depth m)))
      (is (= [] (:level-counts m)))
      (is (= [] (:orphans m)))
      (is (nil? (:leaf-min-depth m)))
      (is (nil? (:leaf-max-depth m)))
      (is (nil? (:depth-spread m)))
      (is (nil? (:structure-level m)))))
  (testing "read-plan 集成: 汇总树暴露 :wbs_hierarchy, max-depth/summary/leaf/root 一致, 无版本漂移"
    (let [id (project!)
          s (summary! id "WBS")
          m (summary! id "WBS.1" s)
          _ (child! id "WBS.1.1" m)   ;; 深度 3
          _ (child! id "WBS.2" s)      ;; 深度 2
          v-before (version id)
          model (plan/read-plan *svc* (actor 9201) id)
          h (:wbs_hierarchy model)]
      (is (some? h))
      (is (true? (:available h)))
      (is (= 2 (:summary-count h)))
      (is (= 2 (:leaf-count h)))
      (is (= 1 (:root-count h)))
      (is (= 3 (:max-depth h)))
      (is (= 2 (:leaf-min-depth h))) ;; WBS.2 直接挂 s
      (is (= 3 (:leaf-max-depth h))) ;; WBS.1.1 挂 m 挂 s
      (is (= 0 (:orphan-count h)))
      (is (= v-before (version id)))
      (is (= v-before (:project_version model))))))

(deftest resource-load-leveling-is-derived-read-only
  (testing "纯函数口径: 各排定工作日负荷相同 -> level, peak-to-avg=1.0 cv=0 无空转日"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21"]}
                 {:task_id "b" :working_dates ["2026-09-22"]}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 8}
                  {:task_id "b" :resource_id "r" :hours_per_day 8}]
          rll (capacity/resource-load-leveling sched allocs)]
      (is (true? (:available rll)))
      (is (= 2 (:scheduled-days rll)))
      (is (= 2 (:active-days rll)))
      (is (= 0 (:idle-days rll)))
      (is (zero? (compare 16M (:total-hours rll))))
      (is (= "2026-09-21" (:peak-date rll)))
      (is (= 8.0 (:avg-hours rll)))
      (is (= 1.0 (:peak-to-avg rll)))
      (is (= 0 (:cv-pct rll)))
      (is (= "level" (:leveling-level rll)))))
  (testing "纯函数口径: 同日堆满异日轻载 -> spiky, peak-to-avg=1.8 cv=78"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21"]}
                 {:task_id "b" :working_dates ["2026-09-21"]}
                 {:task_id "c" :working_dates ["2026-09-22"]}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 8}
                  {:task_id "b" :resource_id "r" :hours_per_day 8}
                  {:task_id "c" :resource_id "r" :hours_per_day 2}]
          rll (capacity/resource-load-leveling sched allocs)]
      (is (= 2 (:scheduled-days rll)))
      (is (= 2 (:active-days rll)))
      (is (= 0 (:idle-days rll)))
      (is (= 16M (:peak-hours rll)))
      (is (= "2026-09-21" (:peak-date rll)))
      (is (= 9.0 (:avg-hours rll)))
      (is (= 1.8 (:peak-to-avg rll)))
      (is (= 78 (:cv-pct rll)))
      (is (= "spiky" (:leveling-level rll)))))
  (testing "纯函数口径: 排定三日却只投入一日 -> 空转日 idle-days=2, 仍 spiky"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22" "2026-09-23"]}
                 {:task_id "b" :working_dates ["2026-09-21"]}]
          allocs [{:task_id "b" :resource_id "r" :hours_per_day 8}]
          rll (capacity/resource-load-leveling sched allocs)]
      (is (= 3 (:scheduled-days rll)))
      (is (= 1 (:active-days rll)))
      (is (= 2 (:idle-days rll)))
      (is (= 3.0 (:peak-to-avg rll)))
      (is (pos? (:cv-pct rll)))
      (is (= "spiky" (:leveling-level rll)))))
  (testing "纯函数口径: 排了日历却无任何投入 -> unassigned, 总负荷 0 全部空转"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          rll (capacity/resource-load-leveling sched [])]
      (is (true? (:available rll)))
      (is (= 2 (:scheduled-days rll)))
      (is (= 0 (:active-days rll)))
      (is (= 2 (:idle-days rll)))
      (is (zero? (:total-hours rll)))
      (is (= 0 (:peak-to-avg rll)))
      (is (= "unassigned" (:leveling-level rll)))))
  (testing "纯函数口径: 无任何排定工作日 -> available=false 且档位 nil"
    (let [rll (capacity/resource-load-leveling [] [])]
      (is (false? (:available rll)))
      (is (= 0 (:scheduled-days rll)))
      (is (nil? (:leveling-level rll)))
      (is (nil? (:peak-date rll)))))
  (testing "read-plan 集成: 排定日+工时分配 -> 派生只读存在, active+idle=scheduled, 档位落在枚举内, 无版本漂移"
    (let [id (project!)
          t1 (task! id "RLA" 2) t2 (task! id "RLB" 2)
          equip (:result (command! plan/create-resource! id [] {:name "机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id t1) :resource_id (:resource_id equip) :hours_per_day 4})
      (command! plan/create-allocation! id [] {:task_id (:task_id t2) :resource_id (:resource_id equip) :hours_per_day 4})
      (let [v-before (version id)
            model (plan/read-plan *svc* (actor 9201) id)
            rll (:resource_load_leveling model)]
        (is (some? rll))
        (is (true? (:available rll)))
        (is (pos? (:scheduled-days rll)))
        (is (= (:scheduled-days rll) (+ (:active-days rll) (:idle-days rll))))
        (is (contains? #{"level" "moderate" "spiky" "unassigned"} (:leveling-level rll)))
        (is (= v-before (version id)))
        (is (= v-before (:project_version model)))))))

(deftest capacity-utilization-is-derived-read-only
  (testing "纯函数口径: 单资源轻载 (4/16=25%) -> underused, 计入轻载清单"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "r" :name "机床" :daily_capacity 8}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 2}]
          cu (capacity/capacity-utilization sched res [] allocs)]
      (is (true? (:available cu)))
      (is (= 1 (:resource-count cu)))
      (is (= 4M (:total-committed-hours cu)))
      (is (= 16M (:total-capacity-hours cu)))
      (is (= 25 (:overall-pct cu)))
      (is (= 25 (:min-pct cu)))
      (is (= 25 (:max-pct cu)))
      (is (= "underused" (:utilization-level cu)))
      (is (= 1 (count (:underused cu))))
      (is (= "r" (:resource_id (first (:underused cu)))))
      (is (= 2 (:active-days (first (:underused cu)))))
      (is (= 0 (count (:near-saturated cu))))))
  (testing "纯函数口径: 单资源贴满日历容量 (16/16=100%) -> saturated, 计入接近满负荷清单"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "r" :name "机床" :daily_capacity 8}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 8}]
          cu (capacity/capacity-utilization sched res [] allocs)]
      (is (= 16M (:total-committed-hours cu)))
      (is (= 100 (:overall-pct cu)))
      (is (= "saturated" (:utilization-level cu)))
      (is (= 0 (count (:underused cu))))
      (is (= 1 (count (:near-saturated cu))))
      (is (= "r" (:resource_id (first (:near-saturated cu)))))))
  (testing "纯函数口径: 单资源中等利用 (12/16=75%) -> balanced, 既非轻载也非满负荷"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "r" :name "机床" :daily_capacity 8}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 6}]
          cu (capacity/capacity-utilization sched res [] allocs)]
      (is (= 75 (:overall-pct cu)))
      (is (= "balanced" (:utilization-level cu)))
      (is (= 0 (count (:underused cu))))
      (is (= 0 (count (:near-saturated cu))))))
  (testing "纯函数口径: 日容量覆盖生效, 覆盖日按覆盖值计入分母 (8+4=12, 6/12=50%)"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "r" :name "机床" :daily_capacity 8}]
          caps [{:resource_id "r" :date "2026-09-22" :capacity_hours 4}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 3}]
          cu (capacity/capacity-utilization sched res caps allocs)]
      (is (= 6M (:total-committed-hours cu)))
      (is (= 12M (:total-capacity-hours cu)))
      (is (= 50 (:overall-pct cu)))
      (is (= "balanced" (:utilization-level cu)))))
  (testing "纯函数口径: 未被任何工时分配排入的资源不计入 (resource-count 只数已排入资源)"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21"]}]
          res [{:resource_id "r" :name "机床" :daily_capacity 8}
               {:resource_id "r2" :name "闲置台" :daily_capacity 8}]
          allocs [{:task_id "a" :resource_id "r" :hours_per_day 8}]
          cu (capacity/capacity-utilization sched res [] allocs)]
      (is (= 1 (:resource-count cu)))
      (is (= 8M (:total-capacity-hours cu)))
      (is (not-any? #(= "r2" (:resource_id %)) (concat (:underused cu) (:near-saturated cu))))))
  (testing "纯函数口径: 无任何工时分配 -> available=false, 资源数 0, 档位 nil"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21"]}]
          res [{:resource_id "r" :name "机床" :daily_capacity 8}]
          cu (capacity/capacity-utilization sched res [] [])]
      (is (false? (:available cu)))
      (is (= 0 (:resource-count cu)))
      (is (nil? (:utilization-level cu)))
      (is (nil? (:min-pct cu)))
      (is (nil? (:max-pct cu)))
      (is (= [] (:underused cu)))
      (is (= [] (:near-saturated cu)))))
  (testing "read-plan 集成: 排定日+工时分配 -> 派生只读存在, 档位落在枚举内, 无版本漂移"
    (let [id (project!)
          t1 (task! id "CUA" 2)
          equip (:result (command! plan/create-resource! id [] {:name "机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id t1) :resource_id (:resource_id equip) :hours_per_day 4})
      (let [v-before (version id)
            model (plan/read-plan *svc* (actor 9201) id)
            cu (:capacity_utilization model)]
        (is (some? cu))
        (is (true? (:available cu)))
        (is (pos? (:resource-count cu)))
        (is (contains? #{"underused" "balanced" "saturated"} (:utilization-level cu)))
        (is (= v-before (version id)))
        (is (= v-before (:project_version model)))))))

(deftest resource-type-mix-is-derived-read-only
  (testing "纯函数口径: 人力主导 (16/20=80%) -> concentrated, 两类均排入"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "p" :name "工程师" :resource_type "person"}
               {:resource_id "e" :name "机床" :resource_type "equipment"}]
          allocs [{:task_id "a" :resource_id "p" :hours_per_day 8}
                  {:task_id "a" :resource_id "e" :hours_per_day 2}]
          rt (capacity/resource-type-mix sched res allocs)]
      (is (true? (:available rt)))
      (is (= 2 (:type-count rt)))
      (is (= 20M (:total-committed-hours rt)))
      (is (= "person" (:dominant-type rt)))
      (is (= 80 (:dominant-share-pct rt)))
      (is (= "concentrated" (:structure-level rt)))
      (is (= "person" (:resource_type (first (:types rt)))))
      (is (= 16M (:committed-hours (first (:types rt)))))
      (is (= 1 (:engaged (first (:types rt)))))
      (is (= 0 (:idle (first (:types rt)))))))
  (testing "纯函数口径: 一类主导但未极端 (12/16=75%) -> skewed"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "p" :name "工程师" :resource_type "person"}
               {:resource_id "e" :name "机床" :resource_type "equipment"}]
          allocs [{:task_id "a" :resource_id "p" :hours_per_day 6}
                  {:task_id "a" :resource_id "e" :hours_per_day 2}]
          rt (capacity/resource-type-mix sched res allocs)]
      (is (= 75 (:dominant-share-pct rt)))
      (is (= "skewed" (:structure-level rt)))))
  (testing "纯函数口径: 人力与设备投入持平 (各 50%) -> balanced"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21" "2026-09-22"]}]
          res [{:resource_id "p" :name "工程师" :resource_type "person"}
               {:resource_id "e" :name "机床" :resource_type "equipment"}]
          allocs [{:task_id "a" :resource_id "p" :hours_per_day 4}
                  {:task_id "a" :resource_id "e" :hours_per_day 4}]
          rt (capacity/resource-type-mix sched res allocs)]
      (is (= 16M (:total-committed-hours rt)))
      (is (= 50 (:dominant-share-pct rt)))
      (is (= "balanced" (:structure-level rt)))))
  (testing "纯函数口径: 设备整类闲置 (建成却零工时) -> idle=1/share=0, 人力主导 concentrated"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21"]}]
          res [{:resource_id "p" :name "工程师" :resource_type "person"}
               {:resource_id "e" :name "闲置机床" :resource_type "equipment"}]
          allocs [{:task_id "a" :resource_id "p" :hours_per_day 8}]
          rt (capacity/resource-type-mix sched res allocs)
          eq (first (filter #(= "equipment" (:resource_type %)) (:types rt)))]
      (is (= 2 (:type-count rt)))
      (is (= "person" (:dominant-type rt)))
      (is (= "concentrated" (:structure-level rt)))
      (is (some? eq))
      (is (= 1 (:resource-total eq)))
      (is (= 0 (:engaged eq)))
      (is (= 1 (:idle eq)))
      (is (= 0M (:committed-hours eq)))
      (is (= 0 (:share-pct eq)))))
  (testing "纯函数口径: 排了资源却无任何工时分配 -> unassigned, available=true"
    (let [sched [{:task_id "a" :working_dates ["2026-09-21"]}]
          res [{:resource_id "p" :name "工程师" :resource_type "person"}]
          rt (capacity/resource-type-mix sched res [])]
      (is (true? (:available rt)))
      (is (= 0M (:total-committed-hours rt)))
      (is (= "unassigned" (:structure-level rt)))))
  (testing "纯函数口径: 无任何资源 -> available=false, type-count 0, 档位 nil"
    (let [rt (capacity/resource-type-mix [] [] [])]
      (is (false? (:available rt)))
      (is (= 0 (:type-count rt)))
      (is (= [] (:types rt)))
      (is (nil? (:dominant-type rt)))
      (is (nil? (:structure-level rt)))))
  (testing "read-plan 集成: 人力重载 + 设备整类闲置 -> 派生只读结构确定, 无版本漂移"
    (let [id (project!)
          _ (pms/set-member! *svc* (actor 1) id {:user_id 9210 :role "viewer"})
          t1 (task! id "RTMA" 2)
          person (:result (command! plan/create-resource! id [] {:name "工程师" :resource_type "person" :user_id 9210 :daily_capacity 8}))
          equip (:result (command! plan/create-resource! id [] {:name "机床" :resource_type "equipment" :daily_capacity 8}))]
      (command! plan/create-allocation! id [] {:task_id (:task_id t1) :resource_id (:resource_id person) :hours_per_day 6})
      ;; 设备建了却一条工时都没排 -> 整类闲置
      (let [v-before (version id)
            model (plan/read-plan *svc* (actor 9201) id)
            rt (:resource_type_mix model)
            person-row (first (filter #(= "person" (:resource_type %)) (:types rt)))
            equip-row (first (filter #(= "equipment" (:resource_type %)) (:types rt)))]
        (is (some? rt))
        (is (true? (:available rt)))
        (is (= 2 (:type-count rt)))
        (is (= "person" (:dominant-type rt)))
        (is (= 100 (:dominant-share-pct rt)))
        (is (= "concentrated" (:structure-level rt)))
        (is (= 1 (:engaged person-row)))
        (is (= 0 (:engaged equip-row)))
        (is (= 1 (:idle equip-row)))
        (is (= 0M (:committed-hours equip-row)))
        (is (= v-before (version id)))
        (is (= v-before (:project_version model)))))))


;; batch62 (mutator 方向: cover the uncovered): 审批落定后同修订重复提交409 (L79)
;; 与 排程日期超百年400 (L73). 注: L79 与 editable! 的'计划正在审批'是两道门 —
;; 后者挡在 submitted 状态前, 前者只在基线已落定 (approved/rejected) 后可达.
(deftest plan-resubmit-after-decision-and-century-range-guards
  (testing "独立审批落定后同一计划修订再次提交409"
    (let [id (project!)]
      (task! id "R-1" 2)
      (let [baseline (:result (command! plan/submit-plan! id [] {:comment "初版提交"}))]
        (plan/review-plan! *svc* (actor 9202) id (:baseline_id baseline)
                           {:version (version id) :decision "approved" :comment "独立批准"})
        (is (= [409 "此计划修订已提交,请修改设计后再发起新的审批"]
               (error-info #(command! plan/submit-plan! id [] {:comment "再次提交"})))))))
  (testing "排程日期超过一百年支持范围400"
    (let [id (project!)]
      (task! id "C-1" 2)
      (command! plan/create-task! id [] {:wbs_code "C-FAR" :name "百年外任务" :duration_days 1
                                         :owner_id 9201 :start_date "2200-01-01"})
      (is (= [400 "排程日期超过一百年支持范围"]
             (error-info #(command! plan/submit-plan! id [] {:comment "超远日期"})))))))

;; batch63 (mutator 方向: cover the uncovered): 执行准入时外部共享人员超配409 (L140).
;; 批准窗口 editable! 冻结本项目计划写入, 自身超配无法在批准后引入且不改修订 —
;; 超配只能来自 shared-person-projects 的外部同人项目, 本项目修订与快照哈希
;; 均不变, 恰好穿过 L136/L138 两门后命中 L140. 专用人员 9299 隔离同库其它用例.
(deftest execution-gate-blocks-on-external-shared-person-overload
  (let [a (project!)
        _ (pms/set-member! *svc* (actor 1) a {:user_id 9299 :role "viewer"})
        _ (task! a "X-1" 3)
        _ (command! plan/create-resource! a [] {:name "超配专用人员" :resource_type "person"
                                                :user_id 9299 :daily_capacity 8})
        baseline (:result (command! plan/submit-plan! a [] {:comment "初版提交"}))]
    (plan/review-plan! *svc* (actor 9202) a (:baseline_id baseline)
                       {:version (version a) :decision "approved" :comment "独立批准"})
    (let [b (project!)
          _ (pms/set-member! *svc* (actor 1) b {:user_id 9299 :role "viewer"})
          task-b (task! b "Y-1" 3)
          res-b (:result (command! plan/create-resource! b [] {:name "超配专用人员" :resource_type "person"
                                                               :user_id 9299 :daily_capacity 8}))]
      (command! plan/create-allocation! b [] {:task_id (:task_id task-b)
                                              :resource_id (:resource_id res-b) :hours_per_day 12})
      (is (= [409 "共享资源存在超配,请调整计划后再进入执行"]
             (error-info #(command! pms/transition-project! a [] {:status "execution" :reason "进入执行"}))))
      ;; 清理: 取消外部项目使其退出 shared-person-projects, 不污染同库其它用例
      (command! pms/transition-project! b [] {:status "cancelled" :reason "超配用例清理"}))))

;; batch64 (mutator 方向: cover the uncovered): 批准时外部共享人员超配409 (L95).
;; 与 L140 同源: shared-person-projects 的外部负载不在本项目快照/修订内,
;; 提交后引入即可让批准门命中; 清除外部负载后同一申请可正常批准.
(deftest approval-gate-blocks-on-external-shared-person-overload
  (let [a (project!)
        _ (pms/set-member! *svc* (actor 1) a {:user_id 9299 :role "viewer"})
        _ (task! a "X-2" 3)
        _ (command! plan/create-resource! a [] {:name "超配专用人员" :resource_type "person"
                                                :user_id 9299 :daily_capacity 8})
        baseline (:result (command! plan/submit-plan! a [] {:comment "初版提交"}))]
    (let [b (project!)
          _ (pms/set-member! *svc* (actor 1) b {:user_id 9299 :role "viewer"})
          task-b (task! b "Y-2" 3)
          res-b (:result (command! plan/create-resource! b [] {:name "超配专用人员" :resource_type "person"
                                                               :user_id 9299 :daily_capacity 8}))]
      (command! plan/create-allocation! b [] {:task_id (:task_id task-b)
                                              :resource_id (:resource_id res-b) :hours_per_day 12})
      (is (= [409 "当前共享资源存在超配,不能批准计划"]
             (error-info #(plan/review-plan! *svc* (actor 9202) a (:baseline_id baseline)
                                             {:version (version a) :decision "approved"
                                              :comment "独立批准"}))))
      ;; 清理外部负载: 取消外部项目后同一申请可正常批准
      (command! pms/transition-project! b [] {:status "cancelled" :reason "超配用例清理"})
      (is (= "approved" (get-in (plan/review-plan! *svc* (actor 9202) a (:baseline_id baseline)
                                                   {:version (version a) :decision "approved"
                                                    :comment "解除超配后批准"})
                                [:result :status]))))))

;; batch68 (mutator 方向: cover the uncovered): 手动进度快照命令 (planning L124-126).
(deftest snapshot-now-derives-progress-scan
  (let [id (project!)]
    (task! id "SNAP-1" 2)
    (let [result (command! plan/snapshot-now! id [] {})]
      (is (some? (:snapshot_id result)))
      (is (number? (:overall_percent result)))
      (is (map? (:reminders result))))))

;; batch69 (mutator 方向: cover the uncovered): 任务数量上限400 (tasks L77).
(deftest task-count-limit-enforced-at-1000
  (let [id (project!)]
    (dotimes [i 1000]
      (task! id (str "LIM-" i) 1))
    (is (= [400 "当前项目任务数量已达到 1000 个上限"]
           (error-info #(task! id "LIM-OVER" 1))))))

;; batch72 (mutator 方向: cover the uncovered): 计划提交/反馈/资源/日历守卫群 —
;; baseline L51/L53/L108, tasks L223/L234, store L84/L97-100 八门.
(deftest plan-submit-feedback-resource-and-calendar-guards
  (testing "无可执行任务不能提交计划审批409"
    (let [id (project!)]
      (is (= [409 "请先建立可执行任务或里程碑"]
             (error-info #(command! plan/submit-plan! id [] {:comment "空计划"}))))))
  (testing "任务缺少负责人不能提交409"
    (let [id (project!)]
      (command! plan/create-task! id [] {:wbs_code "NO-OWNER" :name "无主任务" :duration_days 1})
      (is (= [409 "任务缺少负责人: 无主任务"]
             (error-info #(command! plan/submit-plan! id [] {:comment "无主提交"}))))))
  (testing "审批决定受控400 (状态门与提交人门之后, 批准门之前)"
    (let [id (project!)]
      (task! id "R-1" 2)
      (let [baseline (:result (command! plan/submit-plan! id [] {:comment "待审"}))]
        (is (= [400 "审批决定必须为 approved 或 rejected"]
               (error-info #(plan/review-plan! *svc* (actor 9202) id (:baseline_id baseline)
                                              {:version (version id) :decision "maybe" :comment "x"})))))))
  (testing "非执行阶段不能反馈进度409 (先于任务查找)"
    (let [id (project!)]
      (is (= [409 "仅执行阶段可以反馈任务进度"]
             (error-info #(command! plan/task-feedback! id ["any-task"]
                                    {:status "done" :percent_complete 100 :remaining_days 0}))))))
  (testing "汇总任务不能直接反馈进度400"
    (let [id (project!)]
      (command! plan/create-task! id [] {:wbs_code "SUM-FB" :name "汇总反馈" :task_type "summary"
                                         :duration_days 0 :owner_id 9201})
      (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id])
      (let [summary (first (filter #(= "SUM-FB" (:wbs_code %))
                                   ((:query-fn *svc*) :planning/tasks {:project_id id})))]
        (is (= [400 "汇总任务不能直接反馈进度"]
               (error-info #(command! plan/task-feedback! id [(:task_id summary)]
                                      {:status "done" :percent_complete 100 :remaining_days 0})))))))
  (testing "资源标识必须为有效UUID400"
    (let [id (project!)]
      (is (= [400 "资源标识必须是有效UUID"]
             (error-info #(command! plan/update-resource! id ["not-a-uuid"] {:daily_capacity 4}))))))
  (testing "日历假日列表与日期门400 (纯函数直钉)"
    (is (= [400 "假日与额外工作日必须是有限的日期列表"]
           (error-info #(store/calendar! {:working_days [1] :holidays "oops"}))))
    (is (= [400 "日历日期不能为空"]
           (error-info #(store/calendar! {:working_days [1] :holidays [""]}))))))


;; H05 任务技能匹配: 任务可选声明 required_skills, 人力/设备资源可选声明 skills,
;; read-plan 读取时逐条比对并给出 已匹配/缺失/覆盖率; 只读派生, 不落库快照, 不构成任何门控.
(deftest skill-declarations-and-matching-are-optional-and-derived
  (testing "声明归一: 去空白/去重/小写, 数组与分隔文本等价"
    (let [id (project!)]
      (let [t (:result (command! plan/create-task! id []
                                {:wbs_code "S.1" :name "焊接" :duration_days 2 :owner_id 9201
                                 :required_skills [" 焊接 " "探伤" "焊接" ""]}))]
        (is (= "焊接,探伤" (:required_skills t))))
      (let [t (:result (command! plan/create-task! id []
                                {:wbs_code "S.2" :name "装配" :duration_days 1 :owner_id 9201
                                 :required_skills "装配; 调试 ,装配"}))]
        (is (= "装配,调试" (:required_skills t))))
      (testing "未声明不写键"
        (is (nil? (:required_skills (:result (command! plan/create-task! id []
                                                                       {:wbs_code "S.3" :name "无技能" :duration_days 1 :owner_id 9201}))))))))
  (testing "声明上限与单条长度400"
    (let [id (project!)]
      (is (= [400 "所需技能不得超过20项"]
             (error-info #(command! plan/create-task! id []
                                    {:wbs_code "L.1" :name "超量" :duration_days 1 :owner_id 9201
                                     :required_skills (mapv str (range 21))}))))
      (is (= [400 "单个技能不得超过50字"]
             (error-info #(command! plan/create-task! id []
                                    {:wbs_code "L.2" :name "超长" :duration_days 1 :owner_id 9201
                                     :required_skills [(apply str (repeat 51 "技"))]}))))
      (is (= [400 "技能声明不得超过20项"]
             (error-info #(command! plan/create-resource! id []
                                    {:name "超量资源" :resource_type "equipment" :daily_capacity 8
                                     :skills (mapv str (range 21))}))))
      (is (= [400 "单个技能不得超过50字"]
             (error-info #(command! plan/create-resource! id []
                                    {:name "超长资源" :resource_type "equipment" :daily_capacity 8
                                     :skills [(apply str (repeat 51 "技"))]}))))))
  (testing "读取派生: 逐任务缺失清单与项目覆盖率; 补声明后翻转; 设备资源不计入人员技能"
    (let [id (project!)
          _ (doseq [uid [9203 9210]] (pms/set-member! *svc* (actor 1) id {:user_id uid :role "viewer"}))]
      (let [a (:result (command! plan/create-task! id []
                                {:wbs_code "M.1" :name "焊接作业" :duration_days 2 :owner_id 9201
                                 :required_skills "焊接,探伤"}))
            b (:result (command! plan/create-task! id []
                                {:wbs_code "M.2" :name "装配作业" :duration_days 2 :owner_id 9201
                                 :required_skills "装配"}))
            c (:result (command! plan/create-task! id []
                                {:wbs_code "M.3" :name "未排人力" :duration_days 1 :owner_id 9201
                                 :required_skills "探伤"}))
            welder (:result (command! plan/create-resource! id []
                                      {:name "焊工" :resource_type "person" :user_id 9203 :daily_capacity 8 :skills "焊接"}))
            assembler (:result (command! plan/create-resource! id []
                                       {:name "装配工" :resource_type "person" :user_id 9210 :daily_capacity 8 :skills "装配"}))
            rig (:result (command! plan/create-resource! id []
                                  {:name "焊机" :resource_type "equipment" :daily_capacity 8 :skills "焊接,探伤"}))
            summary (:result (command! plan/create-task! id []
                                      {:wbs_code "M.0" :name "汇总" :task_type "summary" :duration_days 0 :owner_id 9201}))
            _ (command! plan/update-task! id [(:task_id c)] {:parent_id (:task_id summary)})]
        (is (= "焊接" (:skills welder)))
        (is (= "焊接,探伤" (:skills rig)))
        (command! plan/create-allocation! id [] {:task_id (:task_id a) :resource_id (:resource_id welder) :hours_per_day 6})
        (command! plan/create-allocation! id [] {:task_id (:task_id a) :resource_id (:resource_id rig) :hours_per_day 2})
        (command! plan/create-allocation! id [] {:task_id (:task_id b) :resource_id (:resource_id assembler) :hours_per_day 6})
        (let [sm (:skill_match (plan/read-plan *svc* (actor 1) id))]
          (is (true? (:available sm)))
          (testing "未排人力与汇总任务不进入列表也不进入分母 (C 是汇总下的空叶子)"
            (is (= 2 (:total-requirements sm)))
            (is (= ["M.1" "M.2"] (mapv :wbs_code (:tasks sm)))))
          (is (= 1 (:matched sm)))
          (is (= 1 (:unmatched sm)))
          (is (= 50 (:coverage-pct sm)))
          (let [row (first (filter #(= "M.1" (:wbs_code %)) (:tasks sm)))]
            (testing "设备资源声明不弥补人员缺失"
              (is (= ["焊接" "探伤"] (:required row)))
              (is (= ["焊接"] (:covered row)))
              (is (= ["探伤"] (:missing row)))
              (is (false? (:matched row)))))
          (is (true? (:matched (first (filter #(= "M.2" (:wbs_code %)) (:tasks sm)))))))
        (testing "补声明后读取时翻转, 证明是派生而非快照"
          (command! plan/update-resource! id [(:resource_id welder)] {:skills ["焊接" "探伤"]})
          (let [sm (:skill_match (plan/read-plan *svc* (actor 1) id))]
            (is (= 2 (:matched sm)))
            (is (= 100 (:coverage-pct sm)))
            (is (empty? (:missing (first (filter #(= "M.1" (:wbs_code %)) (:tasks sm))))))))
        (testing "不门控: 技能不匹配不阻止计划提交冻结 (跨项目共享人员负荷先降到可提交)"
          (doseq [uid [9203 9210]]
            (jdbc/execute! (:db *svc*) ["UPDATE pms_project SET status='cancelled'
                                         WHERE manager_id=? OR project_id IN
                                           (SELECT project_id FROM pms_plan_resource WHERE user_id=? AND project_id<>?)"
                                        uid uid id]))
          (is (= "submitted" (get-in (command! plan/submit-plan! id [] {:comment "技能只读"}) [:result :status])))))))
  (testing "空态: 无任务声明技能时 available=false 且覆盖率 0"
    (let [id (project!)]
      (task! id "E.1" 2)
      (let [sm (:skill_match (plan/read-plan *svc* (actor 1) id))]
        (is (false? (:available sm)))
        (is (zero? (:total-requirements sm)))
        (is (zero? (:coverage-pct sm)))
        (is (empty? (:tasks sm)))))))
