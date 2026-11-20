(ns com.ruoyi.domain.pms.scope
  "H03 范围排除项与验收准则的逐条映射只读派生.
   范围基线此前只有正向覆盖 (WBS 叶节点被需求 satisfies 指向即视为已覆盖), 章程即便声明了排除项和
   验收准则, 也没有任何地方核对它们是否真的落在某个叶节点上. 本命名空间把两项声明逐条映射到叶节点:
   排除项按命中叶节点或汇总任务的 WBS 编号/名称判定已声明, 验收准则按命中叶节点的 WBS 编号/名称/任务描述
   或满足其的需求正文判定已映射. 只读派生, 不落库不投递, 不构成门控 (未映射条目不阻断任何保存/提交/批准).
   独立成叶节点 (不 require rules/store), 由 planning 与 governance 读模型共同拼装, 避免环依赖."
  (:require
    [clojure.string :as str]))


(defn statements
  "把章程/变更体中可选的逐条声明归一为去重去空白的字符串向量; 支持向量与换行分隔文本, 非字符串项忽略."
  [value]
  (let [lines (cond (nil? value) []
                    (string? value) (str/split-lines value)
                    (sequential? value) value
                    :else [(str value)])]
    (->> lines
         (keep (fn [line] (when (string? line) (str/trim line))))
         (remove str/blank?)
         distinct
         vec)))


(defn- leaf-tasks
  "非汇总任务视为 WBS 叶节点 (与 tasks/scope-coverage 同一口径)."
  [tasks]
  (filterv #(not= "summary" (:task_type %)) tasks))


(defn- hits?
  "文本中是否包含关键片段 (大小写不敏感); 空白片段不会命中."
  [text token]
  (let [token (str/trim (str token))]
    (and (seq token)
         (some? text)
         (str/includes? (str/lower-case (str text)) (str/lower-case token)))))


(defn- task-haystack
  "叶节点用于排除项/验收准则命中的可搜索文本: WBS 编号与名称."
  [task]
  (str (or (:wbs_code task) "") " " (or (:name task) "")))


(defn exclusion-rows
  "逐条标注范围排除项是否已通过 WBS 汇总任务或叶节点的编号/名称显式声明 (排除项落在范围之外,
   因此命中汇总任务同样成立). 返回 [{:statement ... :mapped ... :matched_codes [...]}], 只读派生."
  [tasks exclusions]
  (mapv (fn [statement]
          (let [codes (->> tasks
                           (filterv #(hits? (task-haystack %) statement))
                           (keep :wbs_code)
                           vec)]
            {:statement statement :mapped (boolean (seq codes)) :matched_codes codes}))
        exclusions))


(defn acceptance-rows
  "逐条标注验收准则是否已映射: 命中叶节点 WBS 编号/名称/任务描述, 或命中覆盖该叶节点的需求正文.
   requirements 为 [{:id :code :text}], satisfies-by-task 为 {task_id [requirement_id ...]}.
   返回 [{:statement ... :mapped ... :matched_codes [...] :matched_requirements [...]}], 只读派生."
  [tasks exclusions-by-mapping requirements satisfies-by-task]
  (let [req-by-id (into {} (map (juxt :id identity) requirements))
        leaves (leaf-tasks tasks)]
    (mapv (fn [statement]
            (let [matched (filterv (fn [task]
                                     (or (hits? (task-haystack task) statement)
                                         (hits? (:description task) statement)
                                         (some (fn [rid]
                                                 (let [req (get req-by-id rid)]
                                                   (or (hits? (:text req) statement)
                                                       (hits? (:code req) statement))))
                                               (get satisfies-by-task (:task_id task) []))))
                                   leaves)]
              {:statement statement
               :mapped (boolean (seq matched))
               :matched_codes (mapv :wbs_code (remove (comp nil? :wbs_code) matched))
               :matched_requirements (->> matched
                                          (mapcat #(get satisfies-by-task (:task_id %)))
                                          distinct
                                          (keep #(get-in req-by-id [% :code]))
                                          vec)}))
          exclusions-by-mapping)))


(defn- rate
  "映射率: 已映射/总数四舍五入整数百分比; 总数为 0 时给 0."
  [total mapped]
  (if (zero? total) 0 (Math/round (double (/ (* 100.0 mapped) total)))))


(defn review
  "范围排除项与验收准则逐条映射的只读汇总. exclusions/acceptance 为 statements 归一后的声明清单,
   tasks 为项目 WBS 任务, requirements 为最新有效需求, satisfies-by-task 为 {task_id [requirement_id]}.
   输出 available (任一清单非空) / exclusions / acceptance_criteria 逐条明细与各自 mapped/total/pct,
   以及总映射率 mapped-total/mapped-pct 与未映射清单 unmapped. 只读派生, 不落库不投递, 不构成门控."
  [tasks requirements satisfies-by-task exclusions acceptance]
  (let [ex-rows (exclusion-rows tasks exclusions)
        ac-rows (acceptance-rows tasks acceptance requirements satisfies-by-task)
        total (+ (count ex-rows) (count ac-rows))
        mapped (+ (count (filterv :mapped ex-rows)) (count (filterv :mapped ac-rows)))]
    {:available (pos? total)
     :exclusions ex-rows
     :acceptance_criteria ac-rows
     :exclusions-total (count ex-rows)
     :exclusions-mapped (count (filterv :mapped ex-rows))
     :acceptance-total (count ac-rows)
     :acceptance-mapped (count (filterv :mapped ac-rows))
     :mapped-total mapped
     :mapped-pct (rate total mapped)
     :unmapped (vec (concat (keep #(when-not (:mapped %) (:statement %)) ex-rows)
                            (keep #(when-not (:mapped %) (:statement %)) ac-rows)))}))
