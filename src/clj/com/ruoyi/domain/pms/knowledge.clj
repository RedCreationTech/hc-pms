(ns com.ruoyi.domain.pms.knowledge
  "H15 跨项目可复用经验的只读派生 (经验行本身仍由 closure/create-lesson! 写入)."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.read-model :as rm]))


(defn knowledge-reuse
  "H15 跨项目可复用经验检索的只读派生 (免迁移, 不构成门控): 此前经验只在单项目收尾台账可见,
   无法回答'别的项目做过什么、哪条经验可复用'. 本项对当前用户全部授权项目批量读取的经验派生
   available/total/project-count/stage-declared/stage-coverage-pct/owner-declared/owner-coverage-pct
   与 by-stage (同 lesson-summary 的条数降序/名称升序) 及 rows (按时间倒序的回链投影, 带项目编号与名称).
   选择性按适用场景 stage / 分类 category / 关键字 q 做大小写无关的包含匹配 (q 同时匹配标题与正文),
   不命中过滤键即返回全部. 全部读取时计算, 不写入存储, 不新增命令, 也不改变任何门控."
  [lessons projects {:keys [stage category q]}]
  (let [needle (some-> q str/lower-case str/trim not-empty)
        by-id (into {} (map (juxt :project_id identity) projects))
        hit? (fn [row]
               (and (or (nil? stage) (= stage (:applicable_stage row)))
                    (or (nil? category) (= category (:category row)))
                    (or (nil? needle)
                        (str/includes? (str/lower-case (str (:title row) " " (:content row))) needle))))
        rows (filterv hit? lessons)
        total (count rows)
        stages (keep #(when-let [s (:applicable_stage %)] (when (seq s) s)) rows)
        stage-counts (frequencies stages)
        pct (fn [n] (rm/pct total n))]
    {:available (pos? total)
     :total total
     :project-count (count (distinct (map :project_id rows)))
     :stage-declared (count stages)
     :stage-coverage-pct (pct (count stages))
     :owner-declared (count (keep :owner_id rows))
     :owner-coverage-pct (pct (count (keep :owner_id rows)))
     :by-stage (->> (mapv (fn [[st cnt]] {:stage st :count cnt}) stage-counts)
                    (sort-by (fn [{:keys [stage count]}] [(- count) stage]))
                    vec)
     :rows (->> rows
                (map (fn [row]
                       (let [project (get by-id (:project_id row))]
                         (merge (select-keys row [:title :category :content :applicable_stage :owner_id :owner_name :created_by :created_at])
                                {:project_id (:project_id row) :project_no (:project_no project) :project_name (:name project)
                                 :lesson_id (:lesson_id row)}))))
                (sort-by :created_at)
                reverse
                vec)}))
