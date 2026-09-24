(ns com.ruoyi.domain.pms.planning.progress
  "阶段权重进度卷积与主/子/单机结构进度只读派生 (B02/B03): 读取时按叶子任务完成百分比与工期加权计算, 不落库.")

(defn- inherit
  "任务未直接声明阶段/节点时, 沿 WBS 父链向上继承最近的声明值."
  [by-id task key]
  (loop [current task depth 0]
    (cond (nil? current) nil
          (some? (get current key)) (get current key)
          (> depth 40) nil
          :else (recur (get by-id (:parent_id current)) (inc depth)))))

(defn task-node
  "返回任务直接声明或沿父链继承的结构节点 id."
  [tasks task]
  (inherit (into {} (map (juxt :task_id identity) tasks)) task :node_id))

(defn- weighted-percent
  "按工期加权的完成百分比 (整数), 无任务返回 0."
  [tasks]
  (let [weights (map #(max 1 (or (:duration_days %) 1)) tasks)
        total (reduce + 0 weights)]
    (if (zero? total) 0
        (int (Math/round (double (/ (reduce + 0 (map * (map #(or (:percent_complete %) 0) tasks) weights)) total)))))))

(defn leaf-rows
  "为每个叶子任务标注其阶段与节点归属 (沿父链继承)."
  [tasks]
  (let [by-id (into {} (map (juxt :task_id identity) tasks))]
    (->> tasks
         (remove #(= "summary" (:task_type %)))
         (mapv #(assoc % :stage (inherit by-id % :stage_code) :node (inherit by-id % :node_id))))))

(defn node-descendants
  "返回节点及其全部后代节点 id 集合."
  [nodes node-id]
  (loop [result #{node-id} frontier [node-id]]
    (let [children (map :node_id (filter #(contains? (set frontier) (:parent_id %)) nodes))]
      (if (empty? children) result (recur (into result children) (vec children))))))

(defn rollup
  "输出阶段卷积 (权重来自模板实例, 无模板时等权), 结构节点卷积与总体进度."
  [tasks nodes stages]
  (let [leaves (leaf-rows tasks)
        declared (seq stages)
        stage-defs (if declared
                     stages
                     (let [codes (distinct (remove nil? (map :stage leaves)))]
                       (mapv (fn [code] {:code code :name code :weight (if (seq codes) (int (/ 100 (count codes))) 100)}) codes)))
        stage-rows (mapv (fn [stage]
                           (let [rows (filter #(= (:code stage) (:stage %)) leaves)]
                             (assoc stage :task_count (count rows) :done_count (count (filter #(= "done" (:status %)) rows))
                                    :percent (weighted-percent rows))))
                         stage-defs)
        unassigned (filter #(nil? (:stage %)) leaves)
        weight-total (reduce + 0 (map :weight stage-rows))
        overall (if (pos? weight-total)
                  (int (Math/round (double (/ (reduce + 0 (map #(* (:weight %) (:percent %)) stage-rows)) weight-total))))
                  (weighted-percent leaves))
        main? (fn [node] (= "main" (:node_type node)))
        node-rows (mapv (fn [node]
                          (let [ids (node-descendants nodes (:node_id node))
                                rows (if (main? node) leaves (filter #(contains? ids (:node %)) leaves))]
                            {:node_id (:node_id node) :parent_id (:parent_id node) :node_type (:node_type node)
                             :node_code (:node_code node) :name (:name node)
                             :task_count (count rows) :done_count (count (filter #(= "done" (:status %)) rows))
                             :percent (weighted-percent rows)}))
                        nodes)]
    {:source (if declared "template" "equal_weights")
     :overall_percent overall :leaf_count (count leaves)
     :stages stage-rows
     :unassigned_task_count (count unassigned) :unassigned_percent (weighted-percent unassigned)
     :nodes node-rows}))
