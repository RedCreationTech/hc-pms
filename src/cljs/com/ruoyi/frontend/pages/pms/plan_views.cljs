(ns com.ruoyi.frontend.pages.pms.plan-views
  "服务端 CPM 排程,资源负荷和基线差异的可视化."
  (:require [clojure.string :as str]
            [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]))

(defn- day-index
  "把服务端 ISO 日期转换为图表日轴."
  [date]
  (/ (js/Date.parse (str date "T00:00:00Z")) 86400000))

(defn- gantt-row
  "以服务端最早日期绘制任务条,红色明确关键任务."
  [task model origin span]
  (let [colors (shared/use-colors)
        offset (- (day-index (:start_date task)) origin)
        length (inc (- (day-index (:end_date task)) (day-index (:start_date task))))
        critical? (:critical task)]
    [:div {:style {:display "grid" :gridTemplateColumns "240px 1fr" :minHeight 46 :borderBottom (str "1px solid " (:border colors))}}
     [:div {:style {:padding "12px 8px" :fontSize 12}}
      (w/related-label (:tasks model) :task_id :name (:task_id task))
      (when critical? [:span {:style {:color "#e05b64" :marginLeft 8}} "关键"])]
     [:div {:style {:position "relative" :borderLeft (str "1px solid " (:border colors))
                    :backgroundImage "linear-gradient(to right, transparent 99%, #edf1f5 99%)"
                    :backgroundSize "10% 100%"}}
      [:div {:title (str (:start_date task) " → " (:end_date task) ",总时差 " (:total_float task) " 天")
             :style {:position "absolute" :top 13 :height 20 :borderRadius 4
                     :left (str (* 100 (/ offset span)) "%") :width (str (max 1 (* 100 (/ length span))) "%")
                     :background (if critical? "#e05b64" (:primary colors))}}]]]))

(defn gantt
  "只可视化服务端计算出的工作日历与关键路径结果."
  [model]
  (let [schedule (:schedule model) tasks (:tasks schedule)
        origin (day-index (:start_date schedule))
        span (max 1 (inc (- (day-index (:end_date schedule)) origin)))]
    [shared/panel "CPM 排程与关键路径" "日期由任务依赖和工作日历计算,悬停查看总时差" nil
     (if (seq tasks)
       [:div {:style {:overflowX "auto"}}
        [:div {:style {:minWidth 840}}
         [:div {:style {:display "flex" :justifyContent "space-between" :padding "0 0 16px 248px" :fontSize 12 :color "#718096"}}
          [:span (:start_date schedule)] [:span (str "跨度 " span " 个自然日")] [:span (:end_date schedule)]]
         (for [task tasks] ^{:key (:task_id task)} [gantt-row task model origin span])]]
       [shared/empty-state "添加任务并设置开始日期后生成排程" nil])]))

(defn overloads
  "逐日展示真实超负荷记录."
  [model]
  [shared/panel "资源负荷检查" "对照资源日历与任务分配识别超负荷日期" nil
   (if (seq (:overallocations model))
     [w/record-table (:overallocations model)
      [{:title "资源" :dataIndex "resource_id" :render #(w/related-label (:resources model) :resource_id :name %)}
       (w/text-column :date "日期") (w/text-column :planned_hours "计划工时")
       (w/text-column :capacity_hours "容量") (w/text-column :excess_hours "超出工时")] nil]
     [:div {:style {:padding 16 :color "#568775"}}
      (if (seq (:allocations model)) "当前排程未发现资源超负荷." "尚未分配资源,分配后将执行负荷检查.")])])

(def readable-fields
  {:name "名称" :start_date "开始日期" :end_date "结束日期" :duration_days "工期"
   :wbs_code "WBS编号" :task_type "类型" :daily_capacity "日容量" :hours_per_day "日负荷"
   :dependency_type "依赖关系" :lag_days "间隔" :capacity_hours "容量" :date "日期"})

(defn- describe-record
  "以业务字段解释基线快照差异."
  [record]
  (if (map? record)
    (let [parts (keep (fn [[key label]] (when (contains? record key)
                                        (str label ": " (shared/display-value (get record key))))) readable-fields)]
      (if (seq parts) (str/join "; " parts) "日历或关联关系发生变更"))
    (shared/display-value record)))

(defn baseline-diff
  "对照冻结快照查看当前计划变化."
  [base baseline on-close]
  (let [resource (shared/use-resource (str base "/planning/baselines/" (:baseline_id baseline) "/diff") {} [])]
    [antd/modal {:title (str "基线差异 / 修订 " (:plan_revision baseline)) :open true :footer nil
                 :onCancel on-close :width 960 :destroyOnHidden true}
     [w/resource-view resource
      (fn [data]
        [:div
         [:p (str "基线修订 " (:baseline_revision data) " → 当前修订 " (:current_revision data))]
         (if (seq (:changes data))
           [w/record-table (:changes data)
            [{:title "对象" :dataIndex "entity_type" :render #(get {"task" "WBS任务" "schedule" "排程" "calendar" "工作日历"
                                                                 "dependency" "任务依赖" "resource" "资源" "allocation" "资源分配" "capacity" "资源容量"} % %)}
             {:title "变化" :dataIndex "change_type" :render #(get {"added" "新增" "removed" "删除" "modified" "修改"} % %)}
             {:title "基线内容" :dataIndex "before" :render #(describe-record (js->clj % :keywordize-keys true))}
             {:title "当前内容" :dataIndex "after" :render #(describe-record (js->clj % :keywordize-keys true))}] nil]
           [shared/empty-state "当前计划与该基线一致" nil])])]]))

(defn- percent-bar
  "带百分比文字的进度条."
  [percent color]
  [antd/progress {:percent (or percent 0) :size "small" :strokeColor color :style {:width 200}}])

(defn progress-rollup
  "阶段权重进度卷积与主/子/单机结构进度 (B02/B03): 权重来自模板实例, 未实例化时等权; 读取时派生不落库."
  [model]
  (let [rollup (:progress_rollup model) colors (shared/use-colors)
        node-labels {"main" "主项目" "sub" "子项目" "machine" "单机"}]
    [:div {:style {:display "grid" :gap 20}}
     [shared/panel "阶段进度卷积" (if (= "template" (:source rollup)) "权重来自已实例化项目模板的阶段定义" "未实例化模板, 按已使用阶段等权卷积")
      [antd/space {:wrap true}
       [antd/tag {:color "blue"} (str "总体进度 " (:overall_percent rollup) "%")]
       [antd/tag (str "叶子任务 " (:leaf_count rollup))]
       (when (pos? (or (:unassigned_task_count rollup) 0))
         [antd/tag {:color "orange"} (str "未归属阶段任务 " (:unassigned_task_count rollup))])]
      (if (empty? (:stages rollup))
        [:span {:style {:color (:muted colors)}} "尚无阶段定义: 应用项目模板或为任务设置所属阶段后可见."]
        [antd/table {:rowKey "code" :size "small" :pagination false :dataSource (clj->js (:stages rollup))
                     :columns (clj->js [{:title "阶段" :dataIndex "code" :width 90}
                                        {:title "名称" :dataIndex "name"}
                                        {:title "权重%" :dataIndex "weight" :width 90}
                                        {:title "任务数" :dataIndex "task_count" :width 90}
                                        {:title "已完成" :dataIndex "done_count" :width 90}
                                        {:title "阶段进度" :dataIndex "percent" :width 240
                                         :render (fn [v] (r/as-element [percent-bar v (:primary colors)]))}])}])]
     [shared/panel "主/子/单机结构进度" "按任务所属结构节点 (含后代节点) 卷积, 子项目/单机分别可见" nil
      (if (empty? (:nodes rollup))
        [:span {:style {:color (:muted colors)}} "暂无结构节点."]
        [antd/table {:rowKey "node_id" :size "small" :pagination false :dataSource (clj->js (:nodes rollup))
                     :columns (clj->js [{:title "层级" :dataIndex "node_type" :width 90 :render (fn [v] (get node-labels v v))}
                                        {:title "节点编号" :dataIndex "node_code" :width 160}
                                        {:title "名称" :dataIndex "name"}
                                        {:title "任务数" :dataIndex "task_count" :width 90}
                                        {:title "已完成" :dataIndex "done_count" :width 90}
                                        {:title "节点进度" :dataIndex "percent" :width 240
                                         :render (fn [v] (r/as-element [percent-bar v (:primary colors)]))}])}])]]))
