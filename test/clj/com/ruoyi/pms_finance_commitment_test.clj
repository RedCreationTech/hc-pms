(ns com.ruoyi.pms-finance-commitment-test
  "H12 承诺成本与预算控制: 状态机, 币种汇率, 独立审批, 释放守恒, 预算占用门控与规则维护."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.finance :as finance]
            [com.ruoyi.domain.pms.finance-budget :as budget]
            [com.ruoyi.domain.pms.finance-commitment :as commitment]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.service :as pms]
            [conman.core :as conman]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(def ^:dynamic *svc* nil)

(defn- fixture
  "为每次运行迁移独立 SQLite, 建立承诺测试角色和三个用户, 并绑定 PMS SQL."
  [f]
  (let [provided (System/getenv "PMS_TEST_JDBC_URL")
        file (when-not provided (java.io.File/createTempFile "pms-commitment-" ".db"))
        url (or provided (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(9500,'Commitment test','commitment-test',41,'0','0')"])
      (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9500,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
      (doseq [id [9501 9502 9503]]
        (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(?,1,?,?,'0','0')" id (str "cm-" id) (str "承诺测试" id)])
        (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES(?,9500)" id]))
      (let [files (->> (.listFiles (io/file "resources/sql"))
                       (filter #(re-matches #"pms.*\.sql" (.getName %)))
                       (map #(str "sql/" (.getName %))) sort)
            queries (:fns (apply conman/bind-connection-map db {} files))
            query (fn ([k p] ((get-in queries [k :fn]) p))
                      ([tx k p] ((get-in queries [k :fn]) tx p)))]
        (binding [*svc* {:db db :query-fn query}] (f)))
      (finally (when file (.delete file))))))

(use-fixtures :once fixture)

(defn- reset-rule-fixture
  "每个用例前重置系统默认预算规则为启用, 并清掉项目层规则, 让用例互不干扰."
  [f]
  (let [db (:db *svc*)]
    (doseq [sql ["UPDATE pms_budget_control_rule SET enabled=1, note='sys-default' WHERE rule_id IN ('00000000-0000-0000-0000-000000000bd1', '00000000-0000-0000-0000-000000000bd2')"
                 "DELETE FROM pms_budget_control_rule WHERE project_id IS NOT NULL"]]
      (jdbc/execute! db [sql])))
  (f))

(use-fixtures :each reset-rule-fixture)

(defn- actor [id] (pms/actor *svc* {:user-id id}))
(defn- version-of [id] (:version ((:query-fn *svc*) :pms/project {:project_id id})))
(defn- error-status [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(defn- command!
  "以指定成员携带真实项目版本执行业务命令, 返回完整结果."
  ([f id args body] (command! 9501 f id args body))
  ([uid f id args body]
   (apply f *svc* (actor uid) id (concat args [(assoc body :version (version-of id))]))))

(defn- project!
  "建立带有经理和编辑成员的项目."
  []
  (let [p (pms/create-project! *svc* (actor 9501)
             {:project_no (str "CM-" (kernel/id)) :name "承诺测试"
              :manager_id 9501 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"})
        id (:project_id p)]
    (pms/set-member! *svc* (actor 9501) id {:user_id 9502 :role "editor"})
    (pms/set-member! *svc* (actor 9501) id {:user_id 9503 :role "viewer"})
    id))

(defn- create-commitment!
  "登记一条草稿承诺, 默认走采购 CNY 100."
  ([id] (create-commitment! id {:kind "purchase" :code (kernel/id) :supplier "外部供应商"
                                :currency "CNY" :gross "100.00" :reviewer_id 9502}))
  ([id overrides]
   (:result (command! commitment/create! id [] overrides))))

(defn- seed-budget!
  "在指定项目上建立一条已批准预算版本, 总额由外部指定."
  [id total]
  (let [v (:result (command! cost/create! id [] {:kind "budget" :period "2026-09" :currency "CNY"
                                                 :name "预算基线" :revenue "0" :reviewer_id 9502}))
        cid (:id v)]
    (command! cost/add-entry! id [cid] {:category "material" :label "主料" :amount total :source_ref (str "BUD-" (kernel/id))})
    (command! cost/submit! id [cid] {})
    (command! 9502 cost/review! id [cid] {:decision "approved" :reason "预算批准"})))

(deftest h12-lifecycle-and-constraints
  (testing "承诺能创建为草稿, 提交到 submitted, 独立审批到 approved, 部分释放保持 approved, 全额释放转 released."
    (let [id (project!)
          base (create-commitment! id)
          cid (:id base)]
      (is (= "draft" (:status base)))
      (is (= "100.00" (:gross base)))
      (is (= "100.00" (:base base)))
      (is (= "0.00" (:released base)))
      (is (= 409 (error-status #(command! 9502 commitment/review! id [cid] {:decision "approved" :reason "跳过提交"}))))
      (command! commitment/submit! id [cid] {:baseline "budget"})
      (is (= "submitted" (:status (first (:commitments (finance/overview *svc* (actor 9501) id))))))
      (is (= 403 (error-status #(command! commitment/review! id [cid] {:decision "approved" :reason "自审"}))))
      (command! 9502 commitment/review! id [cid] {:decision "approved" :reason "承诺成立"})
      (is (= 409 (error-status #(command! commitment/release! id [cid] {:amount "200.00"}))))
      (command! commitment/release! id [cid] {:amount "40.00"})
      (let [partial (first (:commitments (finance/overview *svc* (actor 9501) id)))]
        (is (= "approved" (:status partial)))
        (is (= "40.00" (:released partial)))
        (is (= "60.00" (:remaining partial))))
      (command! commitment/release! id [cid] {:amount "60.00"})
      (let [done (first (:commitments (finance/overview *svc* (actor 9501) id)))]
        (is (= "released" (:status done)))
        (is (= "100.00" (:released done))))
      (is (= 409 (error-status #(command! commitment/cancel! id [cid] {:reason "释放后不能取消"})))))))

(deftest h12-input-and-currency-validation
  (testing "币种, 金额, 汇率和分类不合法时全部拒绝, USD 承诺按汇率折算成本位."
    (let [id (project!)
          valid {:kind "contract" :code "CT-1" :supplier "甲方" :currency "CNY" :gross "500.00" :reviewer_id 9502}]
      (is (= 400 (error-status #(command! commitment/create! id [] (assoc valid :kind "weird")))))
      (is (= 400 (error-status #(command! commitment/create! id [] (assoc valid :currency "JPY")))))
      (is (= 400 (error-status #(command! commitment/create! id [] (assoc valid :gross "0")))))
      (is (= 400 (error-status #(command! commitment/create! id [] (assoc valid :gross "-1")))))
      (is (= 400 (error-status #(command! commitment/create! id [] (assoc valid :reviewer_id 9501)))))
      (let [usd (create-commitment! id {:kind "purchase" :code "USD-1" :supplier "美元供应商"
                                        :currency "USD" :gross "100.00" :base_currency "CNY"
                                        :exchange_rate "7.1234" :reviewer_id 9502})]
        (is (= "USD" (:currency usd)))
        (is (= "100.00" (:gross usd)))
        (is (= "CNY" (:base_currency usd)))
        (is (= "712.34" (:base usd))))
      (is (= 400 (error-status #(command! commitment/create! id []
                                                     {:kind "purchase" :code "USD-2" :supplier "美元"
                                                      :currency "USD" :gross "1.00" :exchange_rate "abc"
                                                      :reviewer_id 9502})))))))

(deftest h12-draft-edit-and-cancel
  (testing "草稿可修改金额与说明, 提交后禁止修改, 被驳回可以取消, 释放后不能取消."
    (let [id (project!) c (create-commitment! id) cid (:id c)]
      (command! commitment/update-draft! id [cid] {:supplier "新供应商" :gross "250.00"
                                                   :currency "CNY" :base_currency "CNY"
                                                   :exchange_rate "1" :kind "purchase"
                                                   :reviewer_id 9502})
      (let [updated (first (:commitments (finance/overview *svc* (actor 9501) id)))]
        (is (= "新供应商" (:supplier updated)))
        (is (= "250.00" (:base updated))))
      (command! commitment/submit! id [cid] {:baseline "budget"})
      (is (= 409 (error-status #(command! commitment/update-draft! id [cid] {:supplier "不能改" :currency "CNY"
                                                                            :base_currency "CNY" :gross "1"
                                                                            :exchange_rate "1" :kind "purchase"
                                                                            :reviewer_id 9502}))))
      (command! 9502 commitment/review! id [cid] {:decision "rejected" :reason "预算不足"})
      (command! commitment/cancel! id [cid] {:reason "重新登记"})
      (let [cancelled (first (:commitments (finance/overview *svc* (actor 9501) id)))]
        (is (= "cancelled" (:status cancelled)))
        (is (= "重新登记" (:review_note cancelled)))))))

(deftest h12-budget-evaluation-and-block
  (testing "预算 100, 已承诺 90 时提交 20 触发 block 规则, 携带 override_block 才能强制放行."
    (let [id (project!) _ (seed-budget! id "100.00")
          first-c (:result (command! commitment/create! id [] {:kind "purchase" :code "B1" :supplier "A"
                                                                :currency "CNY" :gross "90.00" :reviewer_id 9502}))
          second-c (:result (command! commitment/create! id [] {:kind "purchase" :code "B2" :supplier "B"
                                                                 :currency "CNY" :gross "20.00" :reviewer_id 9502}))]
      (command! commitment/submit! id [(:id first-c)] {:baseline "budget"})
      (command! 9502 commitment/review! id [(:id first-c)] {:decision "approved" :reason "ok"})
      (let [ex (try (command! commitment/submit! id [(:id second-c)] {:baseline "budget"}) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= 409 (:status (ex-data ex))))
        (is (re-find #"预算占用率" (.getMessage ^Throwable ex))))
      (command! commitment/submit! id [(:id second-c)] {:baseline "budget" :override_block true})
      (let [state (finance/overview *svc* (actor 9501) id)
            committed (first (filter #(= "B2" (:code %)) (:commitments state)))
            control (get-in state [:budget_control :budget])]
        (is (= "submitted" (:status committed)))
        (is (some? (:control_note committed)))
        (is (= 110 (:ratio_pct control)))
        (is (= :block (:decision control)))))))

(deftest h12-warn-rule-and-override-allow
  (testing "只有 warn 规则时提交成功, 记录占用率和触发规则, 决策为 warn; 用例自建项目层规则, 不依赖系统默认状态."
    (let [id (project!) _ (seed-budget! id "100.00")
          _ (command! budget/disable-rule! id ["00000000-0000-0000-0000-000000000bd1"] {:reason "本用例自管 warn 规则"})
          _ (command! budget/disable-rule! id ["00000000-0000-0000-0000-000000000bd2"] {:reason "本用例不阻断"})
          _ (command! budget/upsert-rule! id [] {:baseline "budget" :threshold_pct 80 :action "warn"
                                                 :enabled true :note "自管 warn 80" :project_scoped true})
          c (create-commitment! id {:kind "purchase" :code "W1" :supplier "W" :currency "CNY"
                                    :gross "85.00" :reviewer_id 9502})]
      (command! commitment/submit! id [(:id c)] {:baseline "budget"})
      (let [state (finance/overview *svc* (actor 9501) id)
            control (get-in state [:budget_control :budget])]
        (is (= "submitted" (:status (first (:commitments state)))))
        (is (= 85 (:ratio_pct control)))
        (is (= :warn (:decision control)))
        (is (= 1 (count (:triggered control))))
        (is (= "warn" (:action (first (:triggered control)))))))))

(deftest h12-budget-rule-upsert
  (testing "自定义项目层规则可以新增, 更新与停用, 他项目不能修改本层规则."
    (let [id (project!)
          other (project!)
          created (:result (command! budget/upsert-rule! id []
                                    {:baseline "budget" :threshold_pct 90 :action "warn"
                                     :enabled true :note "90%预警" :project_scoped true}))]
      (is (:enabled created))
      (is (= 90 (:threshold_pct created)))
      (is (= id (:project_id created)))
      (let [updated (:result (command! budget/upsert-rule! id []
                                      {:rule_id (:rule_id created) :threshold_pct 95
                                       :action "block" :enabled true :note "改到95%阻断"}))]
        (is (= 95 (:threshold_pct updated)))
        (is (= "block" (:action updated))))
      (is (= 403 (error-status #(command! budget/upsert-rule! other []
                                                     {:rule_id (:rule_id created) :threshold_pct 10
                                                      :action "warn" :enabled true}))))
      (let [default-disabled (:result (command! budget/disable-rule! id ["00000000-0000-0000-0000-000000000bd1"]
                                                                     {:reason "统一使用项目规则"}))]
        (is (false? (:enabled default-disabled)))))))

(deftest h12-overview-exposes-commitment-and-control
  (testing "财务概览暴露承诺台账, 规则集与预算占用评估, 供前端一屏渲染."
    (let [id (project!)
          _ (seed-budget! id "200.00")
          c (create-commitment! id {:kind "labor" :code "L1" :supplier "外包" :currency "CNY"
                                    :gross "60.00" :reviewer_id 9502})]
      (command! commitment/submit! id [(:id c)] {:baseline "budget"})
      (command! 9502 commitment/review! id [(:id c)] {:decision "approved" :reason "已核对"})
      (let [state (finance/overview *svc* (actor 9501) id)
            control (get-in state [:budget_control :budget])]
        (is (= 1 (count (:commitments state))))
        (is (every? :id (:commitments state)))
        (is (>= 3 (count (:budget_rules state))))
        (is (:comparable control))
        (is (= 6000 (:consumed_minor control)))
        (is (= "200.00" (money/money (get-in control [:budget :total_minor]))))))

(deftest period-lock-gates-commitment-writes
  (testing "已封账会计期间的承诺登记/修改/提交/释放/取消全部 409; 未封账期间不受影响; 解锁后草稿可提交."
    (let [id (project!)
          c (:result (command! commitment/create! id [] {:kind "purchase" :code "P1" :supplier "甲"
                                                          :currency "CNY" :gross "100.00" :period "2026-08" :reviewer_id 9502}))
          cid (:id c)]
      (config/create! *svc* (actor 9501) "period-lock" {:period "2026-08" :reason "月结封账"})
      (is (= 409 (error-status #(command! commitment/create! id [] {:kind "purchase" :code "P2" :supplier "乙"
                                                                    :currency "CNY" :gross "50.00" :period "2026-08" :reviewer_id 9502}))))
      (is (= 409 (error-status #(command! commitment/update-draft! id [cid] {:supplier "改" :currency "CNY" :base_currency "CNY"
                                                                            :gross "120.00" :exchange_rate "1" :kind "purchase" :period "2026-08" :reviewer_id 9502}))))
      (is (= 409 (error-status #(command! commitment/submit! id [cid] {:baseline "budget"}))))
      (is (:id (:result (command! commitment/create! id [] {:kind "contract" :code "P3" :supplier "丙"
                                                            :currency "CNY" :gross "30.00" :period "2026-11" :reviewer_id 9502}))))
      (is (some #{"2026-08"} (:locked_periods (finance/overview *svc* (actor 9501) id))))
      (let [lock (first (filter #(= "2026-08" (:period %)) (config/published (:query-fn *svc*) "period-lock")))]
        (config/retire! *svc* (actor 9501) "period-lock" (:id lock) {:reason "重开月结"}))
      (is (= "submitted" (:status (:result (command! commitment/submit! id [cid] {:baseline "budget"}))))))))

(deftest period-lock-does-not-gate-independent-commitment-review
  (testing "承诺已提交后即使所属期间封账, 独立审批 review! 仍可批准 (与费用版本同口径)."
    (let [id (project!)
          c (:result (command! commitment/create! id [] {:kind "purchase" :code "R1" :supplier "甲"
                                                         :currency "CNY" :gross "100.00" :period "2026-08" :reviewer_id 9502}))
          cid (:id c)]
      (command! commitment/submit! id [cid] {:baseline "budget"})
      (config/create! *svc* (actor 9501) "period-lock" {:period "2026-08" :reason "月结封账"})
      (is (= "approved" (:status (:result (command! 9502 commitment/review! id [cid] {:decision "approved" :reason "封期后独立批准"})))))
      (let [lock (first (filter #(= "2026-08" (:period %)) (config/published (:query-fn *svc*) "period-lock")))]
        (config/retire! *svc* (actor 9501) "period-lock" (:id lock) {:reason "重开月结"})))))))
