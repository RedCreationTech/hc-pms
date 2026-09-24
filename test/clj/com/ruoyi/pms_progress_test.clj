(ns com.ruoyi.pms-progress-test
  "增量6 计划与进度深化 (B02/B03/B12/B15/B19/H04/H06): 模板阶段派生子项目/单机计划, 主子冲突定位, 节点重排保留基线,
   项目级阶段权重覆盖, 挣值与完工预测, 定时扫描快照/提醒, 关口例外 的真实数据库测试."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.config.catalog :as catalog]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.store :as gov-store]
    [com.ruoyi.domain.pms.planning :as planning]
    [com.ruoyi.domain.pms.planning.earned-value :as ev]
    [com.ruoyi.domain.pms.planning.schedule :as schedule]
    [com.ruoyi.domain.pms.portfolio :as portfolio]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.scan :as scan]
    [com.ruoyi.domain.pms.service :as pms]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc])
  (:import
    (java.nio.file
      Files)
    (java.time
      LocalDate)
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
  "进度测试专属角色与用户: 9901 项目经理/配置, 9902 计划审批人, 9903 只读成员."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9900,'Progress test','pms-progress-test',90,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9900,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9910,'Progress reader','pms-progress-reader',91,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9910,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query')"])
  (doseq [[id role] [[9901 9900] [9902 9900] [9903 9910]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "progress-test-" id) (str "进度测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-progress-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
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


(defn- error
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- version
  [id]
  (:version (pms/project *service* (actor 9901) id)))


(defn- with-version
  [id body]
  (assoc body :version (version id)))


(defn- command!
  ([id resource action rid body] (command! 9901 id resource action rid body))
  ([uid id resource action rid body]
   (:result (gov/command! *service* (actor uid) id resource action rid (with-version id body)))))


(defn- template!
  "发布与内置目录一致的设备模板: 未发布则从目录导入; 共享库 (MySQL 回归) 下其它套件可能已发布改过阶段的版本, 此时按目录内容建修订并发布."
  []
  (let [q (:query-fn *service*)
        item (some #(when (= "TPL-EQUIPMENT" (:code %)) %) catalog/project-templates)
        published (config/published-by-code q "project-template" "TPL-EQUIPMENT")]
    (cond
      (and published (= (:stages item) (:stages published))) published
      published (let [rev (config/revise! *service* (actor 9901) "project-template" (:id published) (assoc item :reason "恢复目录阶段定义"))]
                  (config/publish! *service* (actor 9901) "project-template" (:id rev) {:reason "发布用于进度测试"}))
      :else (let [draft (config/import-catalog! *service* (actor 9901) "project-template" {:code "TPL-EQUIPMENT"})]
              (config/publish! *service* (actor 9901) "project-template" (:id draft) {:reason "发布用于进度测试"})))))


(defn- project!
  "建立设备项目并实例化模板 (U1 子项目含 M1 单机, U2 子项目无单机)."
  []
  (let [id (:project_id (pms/create-project! *service* (actor 9901)
                                             {:project_no (str "PRG-" (subs (str (UUID/randomUUID)) 0 8)) :name "进度深化验证"
                                              :customer "本地测试" :contract_no "PRG-TEST" :project_type "equipment"
                                              :manager_id 9901 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
    (pms/set-member! *service* (actor 9901) id {:user_id 9902 :role "viewer"})
    (pms/set-member! *service* (actor 9901) id {:user_id 9903 :role "viewer"})
    (command! id :template-instances :create nil {:template_id (:id (template!)) :reason "进度测试"})
    id))


(defn- plan
  [id]
  (planning/read-plan *service* (actor 9901) id))


(defn- node-by-code
  [id suffix]
  (first (filter #(.endsWith ^String (:node_code %) suffix) (:nodes (plan id)))))


(defn- derive!
  ([id] (derive! id {}))
  ([id body] (:result (planning/derive-network! *service* (actor 9901) id (with-version id body)))))


(defn- execution!
  [id]
  (jdbc/execute! (:db *service*) ["UPDATE pms_project SET status='execution' WHERE project_id=?" id]))


(deftest derive-builds-sub-and-machine-plans-from-template-stages-idempotently
  (let [id (project!)
        result (derive! id)
        tasks (:tasks (plan id))
        derived (filter #(= "derived" (:source_type %)) tasks)
        m1 (node-by-code id "-M1") u1 (node-by-code id "-U1")]
    (is (= 3 (:node_count result)) "U1, U2, M1")
    (is (= 5 (:main_stage_count result)) "主计划层 S1/S2/S6/S7/S8")
    (is (= 15 (:derived_count result)) "主计划 5 + 子项目 S2/S3/S5 x2 + 单机 S3/S4/S5/S7")
    (is (= 11 (:dependency_count result)) "主计划链 4 + 各节点内按阶段顺序 FS 串联 2+2+3")
    (is (= 15 (count derived)))
    (is (every? #(and (some? (:stage_code %)) (some? (:parent_id %)) (= "task" (:task_type %))) derived))
    (is (= 5 (count (filter #(nil? (:node_id %)) derived))) "主计划任务不绑定节点")
    (is (= 10 (count (filter #(some? (:node_id %)) derived))))
    (is (= ["S3" "S4" "S5" "S7"] (mapv :stage_code (sort-by :wbs_code (filter #(= (:node_id m1) (:node_id %)) derived)))))
    (is (= 9901 (:owner_id (first derived))) "派生任务默认由项目经理负责")
    (let [again (derive! id)]
      (is (= 0 (:derived_count again)))
      (is (= 15 (:skipped_count again)) "幂等: 已有 WBS 编号全部跳过"))
    (is (= [400 "结构节点不存在, 不属于当前项目或为主项目"] (error #(derive! id {:node_ids ["missing"]}))))
    (let [only-u1 (derive! id {:node_ids [(:node_id u1)]})]
      (is (= 1 (:node_count only-u1))))
    (let [plain (:project_id (pms/create-project! *service* (actor 9901) {:project_no (str "PRG-" (subs (str (UUID/randomUUID)) 0 8)) :name "未实例化" :customer "x" :contract_no "x" :project_type "equipment" :manager_id 9901 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
      (is (= [409 "项目尚未实例化模板, 没有阶段定义可用"] (error #(derive! plain)))))))


(deftest conflicts-locate-node-tasks-ending-after-main-stage-window
  (let [id (project!)
        m1 (node-by-code id "-M1")
        main (planning/create-task! *service* (actor 9901) id (with-version id {:wbs_code "MAIN-S4" :name "主计划装配调试" :task_type "task" :duration_days 5 :start_date "2026-10-01" :stage_code "S4"}))
        late (planning/create-task! *service* (actor 9901) id (with-version id {:wbs_code "M1-LATE" :name "单机装配 (晚于主计划)" :task_type "task" :duration_days 5 :start_date "2026-10-20" :stage_code "S4" :node_id (:node_id m1)}))
        conflicts (:plan_conflicts (plan id))]
    (is (map? (:result main)))
    (is (= 1 (count conflicts)))
    (is (= "M1-LATE" (:wbs_code (first conflicts))))
    (is (= "S4" (:stage (first conflicts))))
    (is (pos? (:days_late (first conflicts))))
    (is (= (:node_code m1) (:node_code (first conflicts))))
    (is (string? (:main_end_date (first conflicts))))
    (is (map? (:result late)))))


(deftest reschedule-shifts-untouched-node-tasks-and-keeps-approved-baseline
  (let [id (project!)
        _ (derive! id)
        u1 (node-by-code id "-U1")
        before (plan id)
        u1-ids (set (map :task_id (filter #(= (:node_id u1) (:node_id %)) (:tasks before))))
        _ (jdbc/execute! (:db *service*) ["UPDATE pms_project SET status='planning' WHERE project_id=?" id])
        submitted (:result (planning/submit-plan! *service* (actor 9901) id (with-version id {:comment "首个基线"})))
        _ (planning/review-plan! *service* (actor 9902) id (:baseline_id submitted) (with-version id {:decision "approved" :comment "独立批准"}))
        calendar (:calendar before)
        old-start (first (sort (map :start_date (filter #(and (= (:node_id u1) (:node_id %)) (= "derived" (:source_type %))) (:tasks before)))))
        new-start (schedule/shift-working-days calendar old-start 10)
        result (:result (planning/reschedule-node! *service* (actor 9901) id (:node_id u1) (with-version id {:start_date new-start :reason "客户交期顺延两周"})))
        after (plan id)]
    (is (= "recorded" (:status result)))
    (is (= 10 (:delta_working_days result)))
    (is (= new-start (:to_start result)))
    (is (pos? (:task_count result)) "U1 及其后代单机 M1 的任务一起重排")
    (let [shifted (filter #(contains? (set (:task_ids result)) (:task_id %)) (:tasks after))
          originals (into {} (map (juxt :task_id :start_date) (:tasks before)))]
      (is (every? #(= (schedule/shift-working-days calendar (originals (:task_id %)) 10) (:start_date %)) shifted) "每个任务保持相同工作日偏移")
      (is (some #(contains? u1-ids (:task_id %)) shifted)))
    (let [baseline (planning/read-baseline *service* (actor 9901) id (:baseline_id submitted))
          baseline-starts (into {} (map (juxt :task_id :start_date) (get-in baseline [:snapshot :tasks])))]
      (is (= old-start (first (sort (vals (select-keys baseline-starts (:task_ids result)))))) "已批准基线不被重排改写"))
    (is (= 1 (count (:reschedules after))))
    (is (= "客户交期顺延两周" (:reason (first (:reschedules after)))))
    (is (= [400 "新开始日期格式必须为 YYYY-MM-DD"] (error #(planning/reschedule-node! *service* (actor 9901) id (:node_id (node-by-code id "-M1")) (with-version id {:start_date "bad" :reason "x"})))))
    (let [main (first (filter #(= "main" (:node_type %)) (:nodes after)))]
      (is (= 400 (first (error #(planning/reschedule-node! *service* (actor 9901) id (:node_id main) (with-version id {:start_date new-start :reason "x"})))))))
    (execution! id)
    (let [task (first (filter #(and (= (:node_id u1) (:node_id %)) (= "derived" (:source_type %))) (:tasks after)))]
      (planning/task-feedback! *service* (actor 9901) id (:task_id task) (with-version id {:status "in_progress" :percent_complete 20 :remaining_days 4 :comment "开工"}))
      (is (= [409 "节点下已有开始或完成的任务, 不能整体重排"]
             (error #(planning/reschedule-node! *service* (actor 9901) id (:node_id u1) (with-version id {:start_date new-start :reason "再排"}))))))))


(deftest stage-weight-override-is-versioned-and-changes-rollup-only
  (let [id (project!)
        _ (derive! id)
        template-stages (:stages (first (gov-store/records (:query-fn *service*) {:project_id id} "template-instance")))
        codes (mapv :code template-stages)
        weights (fn [w] (mapv (fn [c x] {:code c :weight x}) codes w))]
    (is (= "template" (:stage_weight_source (plan id))))
    (is (= 400 (first (error #(planning/set-stage-weights! *service* (actor 9901) id (with-version id {:stages (weights [10 10 10 10 10 10 10 10])}))))) "合计不等于100")
    (is (= 400 (first (error #(planning/set-stage-weights! *service* (actor 9901) id (with-version id {:stages (subvec (weights [50 50 0 0 0 0 0 0]) 0 2)}))))) "缺阶段")
    (is (= 400 (first (error #(planning/set-stage-weights! *service* (actor 9901) id (with-version id {:stages (conj (subvec (weights [50 50 0 0 0 0 0 0]) 0 7) {:code "ZZ" :weight 0})}))))) "未知阶段")
    (let [first-override (:result (planning/set-stage-weights! *service* (actor 9901) id (with-version id {:stages (weights [5 5 5 55 10 10 5 5]) :reason "装配为主"})))
          p (plan id)]
      (is (= 1 (:revision first-override)))
      (is (= "project_override" (:stage_weight_source p)))
      (is (= 55 (:weight (first (filter #(= "S4" (:code %)) (:stages p))))))
      (is (= 55 (:weight (first (filter #(= "S4" (:code %)) (:stages (:progress_rollup p)))))))
      (is (= template-stages (:stages (first (gov-store/records (:query-fn *service*) {:project_id id} "template-instance")))) "模板快照不变")
      (is (= 2 (:revision (:result (planning/set-stage-weights! *service* (actor 9901) id (with-version id {:stages (weights [10 10 10 40 10 10 5 5]) :reason "再调"})))))))))


(deftest earned-value-math-and-forecast-are-deterministic
  (let [calendar schedule/default-calendar
        tasks [{:task_id "a" :wbs_code "A" :name "A" :task_type "task" :duration_days 4 :percent_complete 50 :stage_code "S4" :node_id "n1"}
               {:task_id "b" :wbs_code "B" :name "B" :task_type "task" :duration_days 4 :percent_complete 0 :stage_code "S5" :node_id "n1"}
               {:task_id "s" :wbs_code "S" :name "汇总" :task_type "summary" :duration_days 0 :percent_complete 0}]
        sched {:tasks [{:task_id "a" :working_dates ["2026-09-01" "2026-09-02" "2026-09-03" "2026-09-04"] :end_date "2026-09-04"}
                       {:task_id "b" :working_dates ["2026-09-07" "2026-09-08" "2026-09-09" "2026-09-10"] :end_date "2026-09-10"}]
               :end_date "2026-09-10"}
        entries [{:task_id "a" :status "approved" :minutes 960 :work_date "2026-09-01"}
                 {:task_id "a" :status "submitted" :minutes 480 :work_date "2026-09-02"}
                 {:task_id "a" :status "approved" :minutes 480 :work_date "2026-09-30"}]
        nodes [{:node_id "n1" :node_code "P-U1"}]
        result (ev/earned-value tasks sched calendar entries nodes "2026-09-02")]
    (is (= 8 (:bac_days result)))
    (is (= 2.0 (:pv_days result)) "状态日期前排程了 2 个工作日")
    (is (= 2.0 (:ev_days result)) "4 天 x 50%")
    (is (= 2.0 (:ac_days result)) "只计已批准且不晚于状态日期的工时: 960 分钟 / 8 小时")
    (is (= 1.0 (:spi result)))
    (is (= 1.0 (:cpi result)))
    (is (= 8.0 (:eac_days result)))
    (is (= 6.0 (:etc_days result)))
    (is (= "on_track" (:schedule_status result)))
    (is (= 25 (:percent_complete result)))
    (is (= "2026-09-10" (:planned_finish result)))
    (is (= (schedule/shift-working-days calendar "2026-09-02" 6) (:forecast_finish result)))
    (is (= [{:key "S4" :label "S4" :bac_days 4 :pv_days 2.0 :ev_days 2.0 :ac_days 2.0 :spi 1.0 :cpi 1.0 :task_count 1}
            {:key "S5" :label "S5" :bac_days 4 :pv_days 0.0 :ev_days 0.0 :ac_days 0.0 :spi nil :cpi nil :task_count 1}]
           (:stages result)))
    (is (= "P-U1" (:label (first (:nodes result)))))
    (let [late (ev/earned-value (assoc-in tasks [0 :percent_complete] 25) sched calendar entries nodes "2026-09-04")]
      (is (= 4.0 (:pv_days late)))
      (is (= 1.0 (:ev_days late)))
      (is (= 0.25 (:spi late)))
      (is (= "behind" (:schedule_status late)))
      (is (= 0.5 (:cpi late)))
      (is (= 16.0 (:eac_days late)) "BAC / CPI"))
    (let [none (ev/earned-value tasks sched calendar [] nodes "2026-08-01")]
      (is (nil? (:spi none)))
      (is (= "no_baseline_yet" (:schedule_status none)))
      (is (= "no_actuals" (:cost_status none))))))


(deftest feedback-records-actual-dates-and-plan-exposes-earned-value
  (let [id (project!)
        _ (derive! id)
        _ (execution! id)
        task (first (filter #(and (= "derived" (:source_type %)) (some? (:node_id %))) (:tasks (plan id))))
        today (str (LocalDate/now))]
    (is (= 400 (first (error #(planning/task-feedback! *service* (actor 9901) id (:task_id task) (with-version id {:status "in_progress" :percent_complete 10 :remaining_days 9 :actual_start "2999-01-01"}))))) "实际开始不能晚于今天")
    (is (= 400 (first (error #(planning/task-feedback! *service* (actor 9901) id (:task_id task) (with-version id {:status "in_progress" :percent_complete 10 :remaining_days 9 :actual_end "2026-09-01"}))))) "未完成不能填实际完成")
    (let [fb (:result (planning/task-feedback! *service* (actor 9901) id (:task_id task) (with-version id {:status "in_progress" :percent_complete 40 :remaining_days 6 :actual_start "2026-09-01" :comment "实际开工"})))]
      (is (= "2026-09-01" (:actual_start fb)))
      (is (nil? (:actual_end fb))))
    (let [done (:result (planning/task-feedback! *service* (actor 9901) id (:task_id task) (with-version id {:status "done" :percent_complete 100 :remaining_days 0 :actual_end today :comment "完工"})))
          stored (first (filter #(= (:task_id task) (:task_id %)) (:tasks (plan id))))]
      (is (= "2026-09-01" (:actual_start done)) "实际开始沿用首次反馈")
      (is (= today (:actual_end done)))
      (is (= "2026-09-01" (:actual_start stored)))
      (is (= today (:actual_end stored))))
    (jdbc/execute! (:db *service*) ["INSERT INTO pms_time_entry(entry_id,project_id,task_id,user_id,work_date,minutes,note,status,submitted_by,reviewer_id) VALUES (?,?,?,9901,'2026-09-02',480,'派生任务工时','approved',9901,9902)"
                                     (str (UUID/randomUUID)) id (:task_id task)])
    (let [p (plan id) evm (:earned_value p)]
      (is (= "working_days" (:unit evm)))
      (is (pos? (:bac_days evm)))
      (is (= (:duration_days task) (int (:ev_days evm))) "已完成任务贡献全部计划工期")
      (is (= 1.0 (:ac_days evm)) "480 分钟 / 8 小时 = 1 工作日")
      (is (some #(= (:node_id task) (:key %)) (:nodes evm)))
      (is (vector? (:progress_history p))))))


(deftest scan-writes-daily-snapshot-and-idempotent-reminders
  (let [id (project!)
        _ (derive! id)
        m1 (node-by-code id "-M1")
        _ (planning/create-task! *service* (actor 9901) id (with-version id {:wbs_code "OLD-1" :name "早已逾期的任务" :task_type "task" :duration_days 1 :start_date "2026-01-05" :owner_id 9903 :node_id (:node_id m1) :stage_code "S4"}))
        _ (command! id :issues :create nil {:title "逾期问题" :severity "major" :owner_id 9903 :due_date "2026-01-10"})
        _ (execution! id)
        date "2026-09-24"
        first-run (scan/scan-project! *service* (actor 9901) id date)
        second-run (scan/scan-project! *service* (actor 9901) id date)
        p (plan id)]
    (is (string? (:snapshot_id first-run)))
    (is (= (:snapshot_id first-run) (:snapshot_id second-run)) "同日快照覆盖更新, 不重复")
    (let [created (get-in first-run [:reminders :created])
          old-task (first (filter #(= "OLD-1" (:wbs_code %)) (:tasks p)))
          reminders (:reminders p)
          task-reminder (first (filter #(= (:task_id old-task) (:target_id %)) reminders))
          issue-reminder (first (filter #(= "issue" (:target_kind %)) reminders))]
      (is (>= created 2) "至少: 逾期任务 OLD-1 + 逾期问题 (派生任务中排程已过期的也会被提醒)")
      (is (= 0 (get-in second-run [:reminders :created])) "同日再扫描不重复登记")
      (is (= created (get-in second-run [:reminders :refreshed])))
      (is (= 1 (count (:progress_history p))))
      (is (= date (:snapshot_date (first (:progress_history p)))))
      (is (= created (count reminders)))
      (is (= 9903 (:owner_id task-reminder)))
      (is (pos? (:days_overdue task-reminder)))
      (is (= "open" (:status task-reminder)))
      (is (= 9903 (:owner_id issue-reminder)))
      (testing "责任人只看到自己负责的提醒, 项目经理看到全部, 无关成员看不到"
        (let [owner-todo (portfolio/todo *service* (actor 9903))
              pm-todo (portfolio/todo *service* (actor 9901))
              other-todo (portfolio/todo *service* (actor 9902))]
          (is (= 2 (count (filter #(= id (:project_id %)) (:reminders owner-todo)))))
          (is (= created (count (filter #(= id (:project_id %)) (:reminders pm-todo)))))
          (is (= 0 (count (filter #(= id (:project_id %)) (:reminders other-todo)))))
          (is (= 2 (get-in owner-todo [:summary :reminders])))))
      (testing "对象不再逾期时提醒被关闭"
        (planning/task-feedback! *service* (actor 9901) id (:task_id old-task) (with-version id {:status "done" :percent_complete 100 :remaining_days 0 :comment "补完"}))
        (let [third (scan/scan-project! *service* (actor 9901) id "2026-09-25")]
          (is (= 1 (get-in third [:reminders :closed])))
          (is (= (dec created) (get-in third [:reminders :open])))
          (is (= 2 (count (:progress_history (plan id))))))))
    (testing "全量扫描只处理执行中项目并需要平台配置权限"
      (is (= 403 (first (error #(scan/run-all! *service* (actor 9903) date)))) "只读用户不能触发")
      (let [all (scan/run-all! *service* (actor 9901) date)]
        (is (some #(= id (:project_id %)) (:results all)))))))


(deftest gate-check-waiver-counts-as-pass-only-with-reason
  (let [id (project!)
        template (command! id :gate-templates :create nil {:code "GT-WAIVE" :title "交接关口" :stage "execution" :required true
                                                            :checks [{:code "W1" :title "装配交检完成" :required true} {:code "W2" :title "测试条件齐备" :required true}]})
        gate (command! id :gates :create nil {:template_id (:id template) :title "交接检查" :reviewer_id 9902})
        doc (command! id :documents :create nil {:code "WV-DOC" :title "交检记录" :filename "w.txt" :content "record"})]
    (is (= 400 (first (error #(command! id :gates :checks (:id gate) {:checks [{:code "W1" :passed true :evidence_ids [(:id doc)]} {:code "W2" :passed false :waived true}]})))) "例外必须给出说明")
    (is (= 400 (first (error #(command! id :gates :checks (:id gate) {:checks [{:code "W1" :passed true :evidence_ids [(:id doc)] :waived true :waiver_reason "x"} {:code "W2" :passed false}]})))) "已通过的检查项不能同时标例外")
    (let [checked (command! id :gates :checks (:id gate) {:checks [{:code "W1" :passed true :evidence_ids [(:id doc)]}
                                                                   {:code "W2" :passed false :waived true :waiver_reason "测试工装下周到位, 接收人同意先交接"}]})]
      (is (= "ready" (:status checked)))
      (is (true? (:waived (second (:checks checked)))))
      (is (= "in_review" (:status (command! id :gates :submit (:id gate) {}))))
      (is (= "approved" (:status (command! 9902 id :gates :decision (:id gate) {:decision "approved" :reason "接受例外"}))))
      (let [progress (first (filter #(= (:id template) (:template_id %)) (:gate_progress (gov/workspace *service* (actor 9901) id))))]
        (is (= 2 (:passed_checks progress)))
        (is (= 1 (:waived_checks progress)))
        (is (true? (:passed progress)))))))
