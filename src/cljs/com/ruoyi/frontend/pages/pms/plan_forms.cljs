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
   {:key :node_id :label "结构节点 (主/子/单机计划)" :type :select
    :options (mapv #(hash-map :value (:node_id %) :label (str (:node_code %) " · " (:name %))) (:nodes model))
    :hint "留空表示主计划层; 子项目/单机任务映射到对应节点后参与分层卷积."}
   {:key :stage_code :label "所属阶段" :type :select
    :options (mapv #(hash-map :value (:code %) :label (str (:code %) " · " (:name %) " (" (:weight %) "%)")) (:stages model))
    :hint "阶段权重来自已实例化的项目模板, 用于进度卷积."}
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
   :initial (if task (select-keys task [:wbs_code :name :task_type :parent_id :owner_id :duration_days :start_date :description :node_id :stage_code])
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
   :transform (fn [data] (reduce (fn [m k] (if (or (nil? (get m k)) (= "" (get m k))) (dissoc m k) m)) data [:actual_start :actual_end]))
   :fields [{:key :status :label "执行状态" :type :select :options (w/choices ["in_progress" "blocked" "done"]) :required? true}
            {:key :percent_complete :label "完成比例(%)" :type :number :min 0 :max 100 :required? true}
            {:key :remaining_days :label "剩余工作日" :type :number :min 0 :required? true}
            {:key :actual_start :label "实际开始日期" :type :date :hint "首次反馈未填时记为今天, 不能晚于今天"}
            {:key :actual_end :label "实际完成日期" :type :date :hint "仅完成状态可填, 未填时记为今天"}
            {:key :comment :label "进度说明" :type :textarea :required? true}]})


(defn derive-dialog
  "从模板阶段派生主/子/单机计划 (幂等)."
  [base]
  {:title "派生主/子/单机计划" :path (str base "/planning/derive")
   :description "按已实例化模板的阶段层级 (main/sub/machine) 在阶段容器与节点容器下生成任务并按阶段顺序 FS 串联; 已有同 WBS 编号的任务跳过, 不改动已有任务与实际进度."
   :fields [{:key :reason :label "说明" :type :textarea}]})


(defn reschedule-dialog
  "重排子项目/单机节点下的未开始任务."
  [base node]
  {:title (str "重排节点计划 " (:node_code node)) :path (str base "/planning/nodes/" (:node_id node) "/reschedule")
   :description "把该节点 (含后代单机) 下全部未开始叶子任务的最早开始日移到新日期, 其余任务保持相同的工作日偏移; 已批准基线不变, 执行期须再次提交基线并绑定已批准变更."
   :fields [{:key :start_date :label "新开始日期" :type :date :required? true}
            {:key :reason :label "重排原因" :type :textarea :required? true}]})


(defn stage-weights-dialog
  "项目级阶段权重覆盖: 每个阶段一个整数权重, 合计 100."
  [base stages]
  {:title "覆盖阶段权重" :path (str base "/planning/stage-weights")
   :description "工程默认为模板权重; 覆盖后进度卷积按新权重计算, 模板快照不变, 每次覆盖生成新版本. 待业务口径批准前只是本地规则."
   :initial (into {} (map (fn [s] [(keyword (str "w_" (:code s))) (:weight s)]) stages))
   :transform (fn [data] {:stages (mapv (fn [s] {:code (:code s) :weight (js/parseInt (get data (keyword (str "w_" (:code s)))))}) stages)
                          :reason (:reason data)})
   :fields (conj (mapv (fn [s] {:key (keyword (str "w_" (:code s))) :label (str (:code s) " " (:name s) " 权重%") :type :number :min 0 :max 100 :required? true}) stages)
                 {:key :reason :label "覆盖说明" :type :textarea})})


(defn snapshot-dialog
  "手动生成当日进度快照与逾期提醒."
  [base]
  {:title "生成进度快照" :path (str base "/planning/snapshot")
   :description "与每日 06:00 定时扫描同一实现: 记录当日 PV/EV/AC/SPI/CPI 与完工预测进入趋势, 登记逾期对象的本地提醒 (只写我的待办, 不投递外部消息)."
   :fields [{:key :date :label "快照日期" :type :date :hint "留空为今天"}]
   :transform (fn [data] (if (or (nil? (:date data)) (= "" (:date data))) (dissoc data :date) data))})

(defn approval-dialog
  "审批冻结的计划版本并保留独立决策意见."
  [base baseline decision]
  {:title (if (= decision "approved") "批准计划基线" "驳回计划基线")
   :path (str base "/planning/baselines/" (:baseline_id baseline) "/review")
   :description (str "审批计划修订 " (:plan_revision baseline) ",提交人 " (:submitted_name baseline))
   :transform #(assoc % :decision decision)
   :fields [{:key :comment :label "审批意见" :type :textarea :required? (= decision "rejected")}]})
