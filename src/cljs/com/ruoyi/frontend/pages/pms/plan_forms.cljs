(ns com.ruoyi.frontend.pages.pms.plan-forms
  "计划工作台的任务,资源与审批业务表单定义."
  (:require [clojure.string :as str]
            [com.ruoyi.frontend.pages.pms.widgets :as w]))

(defn task-fields
  "选择汇总父项,责任人和明确的任务类型."
  [model options]
  [{:key :wbs_code :label "WBS编号" :required? true}
   {:key :name :label "任务名称" :required? true}
   {:key :task_type :label "任务类型" :type :select :required? true :options (w/choices ["summary" "task" "milestone"])}
   {:key :parent_id :label "汇总父项" :type :select
    :options (w/options (filter #(= "summary" (:task_type %)) (:tasks model)) :task_id :name)}
   {:key :owner_id :label "责任人" :type :select :options (w/user-options (:users options))}
   {:key :duration_days :label "工期(工作日)" :type :number :min 0 :required? true
    :hint "普通任务至少1天,汇总任务和里程碑填0."}
   {:key :start_date :label "最早开始日期" :type :date}
   {:key :description :label "交付内容" :type :textarea}])

(defn task-dialog
  "构造任务新增或编辑配置."
  [base model options task]
  {:title (if task "编辑WBS任务" "新建WBS任务")
   :path (str base "/tasks" (when task (str "/" (:task_id task))))
   :method (if task :put :post) :fields (task-fields model options)
   :initial (if task (select-keys task [:wbs_code :name :task_type :parent_id :owner_id :duration_days :start_date :description])
                {:task_type "task" :duration_days 1})})

(defn dependency-dialog
  "关联前置与后续任务并声明依赖方式."
  [base model]
  (let [tasks (w/options (remove #(= "summary" (:task_type %)) (:tasks model)) :task_id :name)]
    {:title "添加任务依赖" :path (str base "/dependencies") :initial {:dependency_type "FS" :lag_days 0}
     :fields [{:key :predecessor_id :label "前置任务" :type :select :options tasks :required? true}
              {:key :successor_id :label "后续任务" :type :select :options tasks :required? true}
              {:key :dependency_type :label "依赖关系" :type :select :required? true
               :options [{:value "FS" :label "完成后开始(FS)"} {:value "SS" :label "同时开始(SS)"}
                         {:value "FF" :label "同时完成(FF)"} {:value "SF" :label "开始后完成(SF)"}]}
              {:key :lag_days :label "间隔工作日" :type :number :min -365 :required? true}]}))

(defn resource-dialog
  "定义真实人员或设备资源的每日容量."
  [base options resource]
  {:title (if resource "编辑项目资源" "新增项目资源")
   :path (str base "/resources" (when resource (str "/" (:resource_id resource))))
   :method (if resource :put :post)
   :initial (if resource (select-keys resource [:name :resource_type :user_id :daily_capacity])
                {:resource_type "person" :daily_capacity 8})
   :fields [{:key :name :label "资源名称" :required? true}
            {:key :resource_type :label "资源类型" :type :select :options (w/choices ["person" "equipment"]) :required? true}
            {:key :user_id :label "关联用户" :type :select :options (w/user-options (:users options))}
            {:key :daily_capacity :label "每日可用工时" :type :number :min 0 :max 24 :required? true}]})

(defn allocation-dialog
  "把任务与资源关联并声明计划负荷."
  [base model]
  {:title "分配任务资源" :path (str base "/allocations") :initial {:hours_per_day 8}
   :fields [{:key :task_id :label "WBS任务" :type :select :required? true
             :options (w/options (filter #(= "task" (:task_type %)) (:tasks model)) :task_id :name)}
            {:key :resource_id :label "资源" :type :select :required? true
             :options (w/options (:resources model) :resource_id :name)}
            {:key :hours_per_day :label "每日计划工时" :type :number :min 0.01 :max 24 :required? true}]})

(defn capacity-dialog
  "为真实资源设置特定日期容量例外."
  [base resource]
  {:title "设置资源日历例外" :path (str base "/resources/" (:resource_id resource) "/capacity") :method :put
   :description (:name resource) :initial {:capacity_hours (:daily_capacity resource)}
   :fields [{:key :date :label "日期" :type :date :required? true}
            {:key :capacity_hours :label "当日可用工时" :type :number :min 0 :max 24 :required? true}]})

(defn calendar-dialog
  "以周历和日期清单维护工作日历."
  [base calendar]
  {:title "编辑项目工作日历" :path (str base "/calendar") :method :put
   :initial (-> (select-keys calendar [:working_days :hours_per_day])
                (assoc :holidays (str/join "\n" (:holidays calendar))
                       :extra_workdays (str/join "\n" (:extra_workdays calendar))))
   :transform #(-> % (update :holidays (fn [s] (vec (remove str/blank? (str/split (or s "") #"[\s,]+")))))
                     (update :extra_workdays (fn [s] (vec (remove str/blank? (str/split (or s "") #"[\s,]+"))))))
   :fields [{:key :working_days :label "每周工作日" :type :multi :required? true
             :options (mapv (fn [n label] {:value n :label label}) (range 1 8) ["周一" "周二" "周三" "周四" "周五" "周六" "周日"])}
            {:key :hours_per_day :label "标准日工时" :type :number :min 1 :max 24 :required? true}
            {:key :holidays :label "休息日" :type :textarea :hint "每行一个 YYYY-MM-DD 日期."}
            {:key :extra_workdays :label "调休工作日" :type :textarea :hint "每行一个 YYYY-MM-DD 日期."}]})

(defn feedback-dialog
  "执行阶段回填任务进度和剩余工期."
  [base task]
  {:title "反馈任务进度" :path (str base "/tasks/" (:task_id task) "/feedback")
   :initial {:status "in_progress" :percent_complete (:percent_complete task 0) :remaining_days (or (:remaining_days task) (:duration_days task))}
   :fields [{:key :status :label "执行状态" :type :select :options (w/choices ["in_progress" "blocked" "done"]) :required? true}
            {:key :percent_complete :label "完成比例(%)" :type :number :min 0 :max 100 :required? true}
            {:key :remaining_days :label "剩余工作日" :type :number :min 0 :required? true}
            {:key :comment :label "进度说明" :type :textarea :required? true}]})

(defn approval-dialog
  "审批冻结的计划版本并保留独立决策意见."
  [base baseline decision]
  {:title (if (= decision "approved") "批准计划基线" "驳回计划基线")
   :path (str base "/planning/baselines/" (:baseline_id baseline) "/review")
   :description (str "审批计划修订 " (:plan_revision baseline) ",提交人 " (:submitted_name baseline))
   :transform #(assoc % :decision decision)
   :fields [{:key :comment :label "审批意见" :type :textarea :required? (= decision "rejected")}]})
