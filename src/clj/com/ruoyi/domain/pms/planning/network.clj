(ns com.ruoyi.domain.pms.planning.network
  "主/子/单机计划网络 (B02/B03/H04): 从模板阶段派生子项目与单机计划, 主子约束冲突定位, 节点计划重排 (保留原基线承诺),
   以及项目级阶段权重覆盖 (工程默认为模板权重, 覆盖记录版本化, 待业务口径批准前只是本地规则)."
  (:require
    [com.ruoyi.domain.pms.governance.store :as gov]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.planning.progress :as progress]
    [com.ruoyi.domain.pms.planning.schedule :as schedule]
    [com.ruoyi.domain.pms.planning.store :as store]
    [com.ruoyi.domain.pms.planning.tasks :as tasks]
    [com.ruoyi.domain.pms.rules :as rules])
  (:import
    (java.time
      LocalDate)))


(def default-levels
  "模板阶段快照未声明 levels 时的工程默认派生层级 (按内置订单模板阶段编码)."
  {"S1" ["main"] "S2" ["main" "sub"] "S3" ["sub" "machine"] "S4" ["machine"]
   "S5" ["sub" "machine"] "S6" ["main"] "S7" ["main" "machine"] "S8" ["main"]})


(defn stage-levels
  "阶段派生到的计划层级."
  [stage]
  (or (seq (:levels stage)) (get default-levels (:code stage)) ["main"]))


(defn- instance!
  [q project]
  (or (first (gov/records q project "template-instance"))
      (rules/fail! 409 "项目尚未实例化模板, 没有阶段定义可用")))


(defn effective-stages
  "有效阶段权重: 模板快照阶段, 若存在项目级覆盖记录则以最新覆盖的权重替换 (名称/编码/层级仍来自模板)."
  [q project]
  (let [instance (first (gov/records q project "template-instance"))
        override (first (gov/records q project "stage-weights"))
        weights (into {} (map (juxt :code :weight) (:stages override)))]
    (when instance
      (mapv (fn [stage] (if (contains? weights (:code stage)) (assoc stage :weight (get weights (:code stage)) :override true) stage))
            (:stages instance)))))


(defn stage-weight-source
  "进度卷积权重来源: project_override / template / equal_weights."
  [q project]
  (cond (seq (gov/records q project "stage-weights")) "project_override"
        (seq (gov/records q project "template-instance")) "template"
        :else "equal_weights"))


(defn- chain!
  "按顺序创建派生任务并以 FS 串联; 已存在同 WBS 编号则跳过 (不重复建, 不重复连)."
  [q project actor container stages code-fn name-fn extra start counters]
  (let [deps (store/rows q project :planning/dependencies)]
    (loop [remaining stages prev nil]
      (when-let [stage (first remaining)]
        (let [code (code-fn stage)
              existing (q :planning/task-code {:project_id (:project_id project) :wbs_code code})
              task (or existing
                       (let [row (tasks/create-record! q project actor
                                                       (merge {:wbs_code code :name (name-fn stage) :task_type "task"
                                                               :duration_days (or (:default_days stage) 5) :start_date start
                                                               :owner_id (:manager_id project) :parent_id (:task_id container)
                                                               :stage_code (:code stage) :source_type "derived"
                                                               :description (str "由模板阶段 " (:code stage) " " (:name stage) " 派生")}
                                                              extra))]
                         (swap! (:created counters) conj (select-keys row [:task_id :wbs_code :name :stage_code :node_id :duration_days :start_date]))
                         row))]
          (when existing (swap! (:skipped counters) inc))
          (when (and prev (not existing)
                     (not-any? #(and (= (:task_id prev) (:predecessor_id %)) (= (:task_id task) (:successor_id %))) deps))
            (q :planning/create-dependency! {:dependency_id (kernel/id) :project_id (:project_id project)
                                             :predecessor_id (:task_id prev) :successor_id (:task_id task)
                                             :dependency_type "FS" :lag_days 0})
            (swap! (:linked counters) inc))
          (recur (rest remaining) task))))))


(defn derive!
  "从模板阶段派生主/子/单机计划 (B03): 主计划层按 levels 含 main 的阶段在阶段容器下建任务并按阶段顺序 FS 串联;
   每个子项目/单机节点按 levels 含其层级的阶段在节点计划容器下建任务 (WBS = <节点编码>-<阶段编码>) 并串联;
   已存在同 WBS 编号的任务跳过 (幂等), 不改动已有任务与实际进度. node_ids 可限定只派生部分节点 (主计划总是检查)."
  [svc actor id body]
  (rules/object! body [:version :node_ids :reason])
  (store/mutation! svc actor id body "plan.network.derived"
    (fn [q project]
      (let [instance (instance! q project)
            all-nodes (vec (q :pms/nodes {:project_id (:project_id project)}))
            candidates (filterv #(not= "main" (:node_type %)) all-nodes)
            wanted (:node_ids body)
            selected (if (seq wanted) (filterv #(contains? (set wanted) (:node_id %)) candidates) candidates)
            start (or (:start_date project) (str (LocalDate/now)))]
        (when (and (seq wanted) (not (vector? wanted))) (rules/fail! 400 "node_ids 必须是节点 id 数组"))
        (when (and (seq wanted) (not= (count selected) (count (set wanted)))) (rules/fail! 400 "结构节点不存在, 不属于当前项目或为主项目"))
        (let [counters {:created (atom []) :linked (atom 0) :skipped (atom 0)}
              main-stages (filter #(some #{"main"} (stage-levels %)) (:stages instance))
              stage-container (fn [stage]
                                (let [tasks (store/rows q project :planning/tasks)]
                                  (or (first (filter #(and (= (:code stage) (:wbs_code %)) (= "summary" (:task_type %))) tasks))
                                      (tasks/create-record! q project actor {:wbs_code (:code stage) :name (:name stage) :task_type "summary" :duration_days 0
                                                                              :start_date start :stage_code (:code stage) :source_type "template" :description ""}))))]
          ;; 主计划: 每个 main 阶段一个任务, 放在该阶段容器下, 跨阶段 FS 串联.
          (loop [remaining main-stages prev nil]
            (when-let [stage (first remaining)]
              (let [container (stage-container stage)
                    before (count @(:created counters))]
                (chain! q project actor container [stage] (fn [s] (str (:code s) "-MAIN")) (fn [s] (str (:name s) " (主计划)")) {} start counters)
                (let [task (q :planning/task-code {:project_id (:project_id project) :wbs_code (str (:code stage) "-MAIN")})
                      deps (store/rows q project :planning/dependencies)]
                  (when (and prev task (> (count @(:created counters)) before)
                             (not-any? #(and (= (:task_id prev) (:predecessor_id %)) (= (:task_id task) (:successor_id %))) deps))
                    (q :planning/create-dependency! {:dependency_id (kernel/id) :project_id (:project_id project)
                                                     :predecessor_id (:task_id prev) :successor_id (:task_id task)
                                                     :dependency_type "FS" :lag_days 0})
                    (swap! (:linked counters) inc))
                  (recur (rest remaining) task)))))
          (doseq [node selected]
            (let [tasks (store/rows q project :planning/tasks)
                  by-code (into {} (map (juxt :wbs_code identity) tasks))
                  container (or (by-code (:node_code node))
                                (first (filter #(and (= (:node_id node) (:node_id %)) (= "summary" (:task_type %))) tasks))
                                (tasks/create-record! q project actor {:wbs_code (:node_code node) :name (str (:name node) "计划") :task_type "summary"
                                                                        :duration_days 0 :start_date start
                                                                        :node_id (:node_id node) :source_type "template" :description ""}))
                  applicable (filter #(some #{(:node_type node)} (stage-levels %)) (:stages instance))]
              (chain! q project actor container applicable
                      (fn [stage] (str (:node_code node) "-" (:code stage)))
                      (fn [stage] (str (:name node) " " (:name stage)))
                      {:node_id (:node_id node) :source_id (:node_id node)}
                      (or (:start_date container) start) counters)))
          (when (and (empty? main-stages) (empty? selected)) (rules/fail! 409 "模板阶段没有可派生的层级"))
          {:node_count (count selected) :main_stage_count (count main-stages)
           :derived_count (count @(:created counters)) :skipped_count @(:skipped counters)
           :dependency_count @(:linked counters) :tasks @(:created counters)})))))


(defn conflicts
  "主子约束冲突定位: 子项目/单机的阶段任务排程完成日晚于主计划同阶段窗口 (主计划层同阶段叶子任务的最晚完成日) 即为冲突; 读取时派生, 不落库."
  [tasks schedule nodes]
  (let [leaves (progress/leaf-rows tasks)
        rows (into {} (map (juxt :task_id identity) (:tasks schedule)))
        node-name (into {} (map (juxt :node_id :node_code) nodes))
        main-window (->> leaves
                         (filter #(and (nil? (:node %)) (some? (:stage %))))
                         (group-by :stage)
                         (map (fn [[stage items]] [stage (last (sort (keep #(:end_date (rows (:task_id %))) items)))]))
                         (into {}))]
    (->> leaves
         (filter #(and (some? (:node %)) (some? (:stage %)) (get main-window (:stage %))))
         (keep (fn [task]
                 (let [end (:end_date (rows (:task_id task))) limit (get main-window (:stage task))]
                   (when (and end limit (pos? (compare end limit)))
                     {:task_id (:task_id task) :wbs_code (:wbs_code task) :name (:name task)
                      :node_id (:node task) :node_code (node-name (:node task)) :stage (:stage task)
                      :end_date end :main_end_date limit
                      :days_late (.between java.time.temporal.ChronoUnit/DAYS (LocalDate/parse limit) (LocalDate/parse end))}))))
         (sort-by (juxt :stage :node_code)) vec)))


(defn reschedule!
  "重排某个子项目/单机节点 (含后代节点) 下全部未开始叶子任务: 按项目日历把最早开始日移到新日期, 其余任务保持相同的工作日偏移;
   不触碰已开始/完成任务, 不改基线 (基线仍是原承诺, 执行期需再次提交并绑定已批准变更); 记录 reschedule 治理记录供追溯."
  [svc actor id node-id body]
  (rules/object! body [:version :start_date :reason])
  (store/mutation! svc actor id body "plan.node.rescheduled"
    (fn [q project]
      (let [node (or (q :pms/node {:project_id (:project_id project) :node_id node-id}) (rules/fail! 404 "结构节点不存在或不属于当前项目"))
            new-start (rules/date! (:start_date body) "新开始日期")
            reason (rules/text! (:reason body) "重排原因" 500 true)
            tasks (store/rows q project :planning/tasks)
            all-nodes (vec (q :pms/nodes {:project_id (:project_id project)}))
            ids (progress/node-descendants all-nodes node-id)
            targets (filterv #(and (not= "summary" (:task_type %)) (contains? ids (progress/task-node tasks %))) tasks)]
        (when (= "main" (:node_type node)) (rules/fail! 400 "主项目请直接调整主计划任务, 节点重排只针对子项目/单机"))
        (when (empty? targets) (rules/fail! 409 "该节点下没有可重排的叶子任务"))
        (when (some #(not= "todo" (:status %)) targets) (rules/fail! 409 "节点下已有开始或完成的任务, 不能整体重排"))
        (let [calendar (:calendar (store/plan q project))
              old-start (first (sort (map :start_date targets)))
              delta (schedule/working-days-between calendar old-start new-start)]
          (doseq [task targets]
            (q :planning/update-task! (assoc task :start_date (schedule/shift-working-days calendar (:start_date task) delta))))
          (gov/insert! q project actor "reschedule"
                       {:code (str "RS-" (:node_code node) "-" (count (gov/records q project "reschedule")))
                        :node_id node-id :node_code (:node_code node) :node_name (:name node)
                        :from_start old-start :to_start new-start :delta_working_days delta
                        :task_count (count targets) :task_ids (mapv :task_id targets) :reason reason}
                       {:status "recorded"}))))))


(defn set-stage-weights!
  "项目级阶段权重覆盖: 必须覆盖模板全部阶段编码, 权重为 0..100 整数且合计 100; 每次覆盖生成新版本, 最新版本生效, 模板快照不变."
  [svc actor id body]
  (rules/object! body [:version :stages :reason])
  (kernel/mutate! svc actor id "pms:project:edit" body "plan.stage-weights.set"
    (fn [q project]
      (let [instance (instance! q project)
            template-stages (:stages instance)
            codes (set (map :code template-stages))
            stages (:stages body)]
        (when-not (and (vector? stages) (seq stages)) (rules/fail! 400 "stages 必须是阶段权重数组"))
        (let [rows (mapv (fn [row]
                           (rules/object! row [:code :weight])
                           (when-not (contains? codes (:code row)) (rules/fail! 400 (str "阶段编码不在模板内: " (:code row))))
                           (when-not (and (integer? (:weight row)) (<= 0 (:weight row) 100)) (rules/fail! 400 "阶段权重必须是0到100的整数"))
                           (select-keys row [:code :weight]))
                         stages)]
          (when-not (= (count rows) (count (set (map :code rows)))) (rules/fail! 400 "阶段编码重复"))
          (when-not (= codes (set (map :code rows))) (rules/fail! 400 "必须给出模板全部阶段的权重"))
          (when-not (= 100 (reduce + (map :weight rows))) (rules/fail! 400 "阶段权重合计必须等于100"))
          (let [names (into {} (map (juxt :code :name) template-stages))
                prior (gov/records q project "stage-weights")
                revision (inc (reduce max 0 (map :revision prior)))]
            (gov/insert! q project actor "stage-weights"
                         {:code "stage-weights" :stages (mapv #(assoc % :name (names (:code %))) rows)
                          :reason (rules/text! (:reason body) "覆盖说明" 500 false) :source "project_override"}
                         {:status "recorded" :revision revision})))))))
