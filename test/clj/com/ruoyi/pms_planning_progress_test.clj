(ns com.ruoyi.pms-planning-progress-test
  "com.ruoyi.domain.pms.planning.progress 的表征测试: 锁定 task-node 沿 WBS 父链继承, leaf-rows 剔除 summary 并标注阶段/节点, node-descendants 子树闭包, rollup 的模板/等权两种来源与工期加权百分比, 主节点聚合全叶子, 未归属阶段任务被排除出 overall 等行为. 作为 ub-mode 后续重构进度卷积路径的行为保持门."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.pms.planning.progress :as p]))

(deftest task-node-inherits-along-parent-chain
  (let [tasks [{:task_id 1 :node_id "N1"}
               {:task_id 2 :parent_id 1}
               {:task_id 3 :parent_id 2 :node_id "N3"}]
        by (into {} (map (juxt :task_id identity) tasks))]
    (is (= "N1" (p/task-node tasks (by 1))))
    (testing "自身未声明时沿父链继承最近的声明值"
      (is (= "N1" (p/task-node tasks (by 2)))))
    (testing "自身声明优先于父链"
      (is (= "N3" (p/task-node tasks (by 3)))))
    (testing "无祖先声明则 nil"
      (is (nil? (p/task-node [{:task_id 9}] {:task_id 9}))))))

(deftest leaf-rows-drops-summary-and-annotates-stage-node
  (let [tasks [{:task_id 1 :task_type "summary" :stage_code "design" :node_id "N1"}
               {:task_id 2 :task_type "task" :parent_id 1 :percent_complete 50}
               {:task_id 3 :task_type "task" :stage_code "build"}]
        rows (p/leaf-rows tasks)]
    (testing "summary 任务被剔除, 仅保留叶子"
      (is (= [2 3] (map :task_id rows))))
    (let [by (into {} (map (juxt :task_id identity) rows))]
      (testing "叶子沿父链继承 stage 与 node"
        (is (= "design" (:stage (by 2))))
        (is (= "N1" (:node (by 2)))))
      (testing "自身声明的 stage 直接命中, 无 node 祖先则 nil"
        (is (= "build" (:stage (by 3))))
        (is (nil? (:node (by 3))))))))

(deftest node-descendants-returns-subtree-closure
  (let [nodes [{:node_id "A"}
               {:node_id "B" :parent_id "A"}
               {:node_id "C" :parent_id "B"}
               {:node_id "D" :parent_id "X"}]]
    (is (= #{"A" "B" "C"} (p/node-descendants nodes "A")))
    (is (= #{"B" "C"} (p/node-descendants nodes "B")))
    (is (= #{"C"} (p/node-descendants nodes "C")))
    (testing "孤立节点只含自身"
      (is (= #{"D"} (p/node-descendants nodes "D"))))))

(deftest rollup-template-source-weights-by-stage
  (let [tasks [{:task_id 1 :task_type "task" :stage_code "design" :percent_complete 100 :status "done" :duration_days 1}
               {:task_id 2 :task_type "task" :stage_code "build" :percent_complete 0 :duration_days 1}]
        stages [{:code "design" :name "设计" :weight 50}
                {:code "build" :name "构建" :weight 50}]
        out (p/rollup tasks [] stages)]
    (is (= "template" (:source out)))
    (is (= 2 (:leaf_count out)))
    (is (= 50 (:overall_percent out)))
    (is (= 0 (:unassigned_task_count out)))
    (let [by-code (into {} (map (juxt :code identity)) (:stages out))]
      (is (= 1 (:task_count (by-code "design"))))
      (is (= 1 (:done_count (by-code "design"))))
      (is (= 100 (:percent (by-code "design"))))
      (is (= 0 (:percent (by-code "build")))))))

(deftest rollup-equal-weights-when-no-template
  (let [tasks [{:task_id 1 :task_type "task" :stage_code "design" :percent_complete 100 :duration_days 1}
               {:task_id 2 :task_type "task" :stage_code "build" :percent_complete 0 :duration_days 1}]
        out (p/rollup tasks [] [])]
    (is (= "equal_weights" (:source out)))
    (testing "无模板时按去重阶段等权 (100/2=50)"
      (is (= [50 50] (map :weight (:stages out)))))
    (is (= 50 (:overall_percent out)))))

(deftest rollup-unassigned-stage-task-excluded-from-overall
  (let [tasks [{:task_id 1 :task_type "task" :percent_complete 40 :duration_days 1}]
        out (p/rollup tasks [] [{:code "design" :name "设计" :weight 100}])]
    (testing "声明模板时, 无阶段任务落进 unassigned 桶而拉低 overall"
      (is (= 1 (:unassigned_task_count out)))
      (is (= 40 (:unassigned_percent out)))
      (is (= 0 (:overall_percent out)))
      (is (= 0 (:percent (first (:stages out))))))))

(deftest rollup-main-node-aggregates-all-leaves
  (let [tasks [{:task_id 1 :task_type "task" :node_id "S1" :percent_complete 100 :status "done" :duration_days 1}
               {:task_id 2 :task_type "task" :node_id "S2" :percent_complete 0 :duration_days 1}]
        nodes [{:node_id "M" :node_type "main"}
               {:node_id "S1" :node_type "sub" :parent_id "M"}]
        out (p/rollup tasks nodes [])
        by-node (into {} (map (juxt :node_id identity)) (:nodes out))]
    (testing "主节点聚合全部叶子 (含未归属任何子树的任务)"
      (is (= 2 (:task_count (by-node "M"))))
      (is (= 1 (:done_count (by-node "M"))))
      (is (= 50 (:percent (by-node "M")))))
    (testing "子节点只聚合自身子树的叶子"
      (is (= 1 (:task_count (by-node "S1"))))
      (is (= 100 (:percent (by-node "S1")))))))

(deftest rollup-empty-inputs-are-zero
  (let [out (p/rollup [] [] [])]
    (is (= "equal_weights" (:source out)))
    (is (= 0 (:overall_percent out)))
    (is (= 0 (:leaf_count out)))
    (is (= [] (:stages out)))
    (is (= [] (:nodes out)))
    (is (= 0 (:unassigned_task_count out)))
    (is (= 0 (:unassigned_percent out)))))
