(ns com.ruoyi.frontend.pages.pms.portfolio
  "项目组合看板 (G02): 多项目进度/齐套/试验/问题/关口/成本卷积, 展开行下钻主/子/单机结构进度, 点击进入项目工作台."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.todo :as todo]
    [reagent.core :as r]))


(def node-labels {"main" "主项目" "sub" "子项目" "machine" "单机"})


(defn- metric
  [label value color]
  [:div {:style {:padding "16px 20px" :borderRadius 10 :border "1px solid #e4e8ee" :minWidth 150}}
   [:div {:style {:fontSize 12 :color "#718096"}} label]
   [:div {:style {:fontSize 30 :fontWeight 650 :color (or color "#1f2937")}} value]])


(defn- node-table
  "展开行: 结构节点进度下钻."
  [nodes]
  [antd/table {:rowKey "node_id" :size "small" :pagination false :dataSource (clj->js nodes)
               :columns (clj->js [{:title "层级" :dataIndex "node_type" :width 90 :render (fn [v] (get node-labels v v))}
                                  {:title "节点编号" :dataIndex "node_code" :width 200} {:title "名称" :dataIndex "name"}
                                  {:title "任务数" :dataIndex "task_count" :width 90} {:title "已完成" :dataIndex "done_count" :width 90}
                                  {:title "进度" :dataIndex "percent" :width 240 :render (fn [v] (r/as-element [antd/progress {:percent (or v 0) :size "small" :style {:width 200}}]))}])}])


(defn- finance-cell
  [fin]
  (if (nil? fin) (r/as-element [:span {:style {:color "#98a2b3"}} "无财务权限"])
      (r/as-element [:div {:style {:fontSize 12 :lineHeight 1.7}}
                     [:div (str "预算 " (or (:budget fin) "—") " / 核算 " (or (:actual fin) "—"))]
                     [:div (str "收入 " (or (:revenue fin) "—") " / 毛利 " (or (:margin fin) "—") (when (:currency fin) (str " " (:currency fin))))]
                     (when (:budget_variance fin) [:div {:style {:color (if (str/starts-with? (:budget_variance fin) "-") "#389e0d" "#cf1322")}} (str "核算-预算 " (:budget_variance fin))])])))


(defn- columns
  []
  [{:title "项目" :dataIndex "name" :width 240
    :render (fn [v row] (r/as-element [:div [antd/button {:type "link" :style {:padding 0 :fontWeight 600} :on-click #(todo/open-project! (aget row "project_id"))} v]
                                      [:div {:style {:fontSize 11 :color "#8793a3"}} (aget row "project_no")]]))}
   {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [shared/status-tag %])}
   {:title "类别" :dataIndex "project_type" :width 100 :render #(shared/project-type-label %)}
   {:title "进度" :dataIndex "overall_percent" :width 180 :render (fn [v] (r/as-element [antd/progress {:percent (or v 0) :size "small" :style {:width 140}}]))}
   {:title "SPI / CPI" :key "evm" :width 150
    :render (fn [_ row] (let [spi (aget row "spi") cpi (aget row "cpi")]
                          (r/as-element (if (and (nil? spi) (nil? cpi))
                                          [:span {:style {:color "#98a2b3" :fontSize 12}} "无快照"]
                                          [antd/space
                                           [antd/tag {:color (cond (nil? spi) "default" (< spi 0.9) "red" (> spi 1.1) "blue" :else "green")} (str "SPI " (if (nil? spi) "—" spi))]
                                           [antd/tag {:color (cond (nil? cpi) "default" (< cpi 0.9) "red" :else "green")} (str "CPI " (if (nil? cpi) "—" cpi))]]))))}
   {:title "阶段" :dataIndex "stages" :width 260
    :render (fn [v] (r/as-element (into [antd/space {:wrap true}] (map (fn [s] [antd/tag {:color (cond (>= (:percent s) 100) "green" (pos? (:percent s)) "blue" :else "default")} (str (:name s) " " (:percent s) "%")]) (js->clj v :keywordize-keys true)))))}
   {:title "齐套" :dataIndex "kit_percent" :width 110 :render (fn [v row] (r/as-element [:span (str v "% " (when (pos? (aget row "shortage_count")) (str "缺 " (aget row "shortage_count"))))]))}
   {:title "试验" :key "tests" :width 150
    :render (fn [_ row] (let [required (js->clj (aget row "tests_required")) approved (set (js->clj (aget row "tests_approved")))]
                          (r/as-element (into [antd/space {:wrap true}] (map (fn [t] [antd/tag {:color (if (approved t) "green" "default")} t]) required)))))}
   {:title "问题/风险" :key "issues" :width 130
    :render (fn [_ row] (r/as-element [antd/space
                                       [antd/tag {:color (if (pos? (aget row "blocker_issues")) "red" "default")} (str "阻断 " (aget row "blocker_issues"))]
                                       [antd/tag (str "问题 " (aget row "open_issues"))] [antd/tag (str "风险 " (aget row "open_risks"))]]))}
   {:title "Gate" :key "gates" :width 90 :render (fn [_ row] (str (aget row "gates_passed") "/" (aget row "gates_total")))}
   {:title "交付" :dataIndex "shipments_received" :width 80 :render (fn [v] (str "签收 " v))}
   {:title "计划完成" :dataIndex "end_date" :width 130
    :render (fn [v row] (let [d (aget row "days_to_end")] (r/as-element [:div v (when (and (some? d) (neg? d) (not (#{"closed" "cancelled"} (aget row "status")))) [antd/tag {:color "red" :style {:marginLeft 6}} "逾期"])])))}
   {:title "四算" :dataIndex "finance" :width 220 :render (fn [v] (finance-cell (js->clj v :keywordize-keys true)))}])


(defn portfolio-page
  "项目组合看板."
  []
  (let [resource (shared/use-resource "/portfolio" {} [])]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "项目组合看板"
      "当前授权范围内所有项目的进度/偏差/交付/齐套/试验/成本卷积, 展开行下钻主/子/单机结构; 数据时点为读取时刻, 不落库"
      [antd/button {:on-click #((:refresh! resource))} "刷新"]]
     (cond
       (:error resource) [shared/error-panel (:error resource) (:refresh! resource)]
       (and (:loading? resource) (nil? (:data resource))) [antd/spin]
       :else
       (let [data (:data resource) summary (:summary data)]
         [:div {:style {:display "grid" :gap 20}}
          [:div {:style {:display "flex" :gap 12 :flexWrap "wrap"}}
           [metric "项目总数" (:total summary) nil] [metric "进行中" (:active summary) "#1d4ed8"]
           [metric "已逾期" (:overdue summary) (when (pos? (:overdue summary 0)) "#cf1322")]
           [metric "阻断问题" (:blocker_issues summary) (when (pos? (:blocker_issues summary 0)) "#cf1322")]
           [metric "待确认升级" (:escalations summary) "#722ed1"] [metric "平均进度" (str (:average_percent summary) "%") nil]]
          [shared/panel "多项目渗透跟踪" (str "数据时点 " (:generated_at data) " · 展开行查看结构节点进度") nil
           [antd/table {:rowKey "project_id" :size "small" :pagination false :scroll {:x 1800}
                        :dataSource (clj->js (:projects data)) :columns (clj->js (columns))
                        :expandable {:expandedRowRender (fn [row] (r/as-element [node-table (js->clj (aget row "nodes") :keywordize-keys true)]))
                                     :rowExpandable (fn [row] (pos? (count (aget row "nodes"))))}}]]]))]))
