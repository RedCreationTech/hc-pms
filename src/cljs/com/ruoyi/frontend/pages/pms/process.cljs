(ns com.ruoyi.frontend.pages.pms.process
  "专项过程看板 (G03): 在同一项目内把主机/附件汇总, 原材预投/包材申请, 齐套, 装配, SIT/FAT/SAT, 偏差, 交付物与现场任务
   汇成可下钻的过程视图; 全部来自治理/交付/计划读模型的同一事实, 读取时派生."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]))


(defn- count-by
  [f rows]
  (frequencies (map f rows)))


(defn- status-tags
  [counts labels colors]
  (into [antd/space {:wrap true}]
        (for [[k n] (sort-by key counts)] [antd/tag {:color (get colors k "default")} (str (get labels k k) " " n)])))


(defn- card
  [title subtitle body]
  [:div {:style {:border "1px solid #e4e8ee" :borderRadius 10 :padding 18 :background "#fff"}}
   [:div {:style {:fontWeight 650 :fontSize 15}} title]
   [:div {:style {:fontSize 12 :color "#718096" :margin "4px 0 12px"}} subtitle]
   body])


(def request-labels {"standard" "标准备料" "long_lead" "长周期" "raw_material" "原材预投" "direct_ship" "直发" "packaging" "包材"})
(def status-labels {"draft" "草稿" "in_review" "待审" "approved" "已批准" "rejected" "已驳回" "frozen" "已冻结" "partial" "部分齐套" "ready" "已齐套"
                    "in_progress" "进行中" "released" "已放行" "shipped" "已发运" "received" "已签收" "open" "待处理" "closed" "已关闭"
                    "resolved" "待验证" "registered" "已登记" "not_started" "未发起" "waived" "已豁免"})
(def status-colors {"approved" "green" "ready" "green" "received" "green" "closed" "green" "in_review" "blue" "in_progress" "blue" "rejected" "red" "open" "orange" "partial" "gold"})


(defn- gate-board
  [gov]
  (let [rows (:gate_progress gov)
        pick (fn [types] (filter #(contains? types (:gate_type %)) rows))]
    [card "主机/附件汇总与关口进展" "按关口类型汇总实例状态与检查项通过数 (B09/B10 汇总 Gate 可下钻至 Gate评审页签)"
     (if (empty? rows) [:span {:style {:color "#98a2b3"}} "尚无关口模板"]
         [:div {:style {:display "grid" :gap 8}}
          (for [[label types] [["主机汇总" #{"host-summary"}] ["附件汇总" #{"attachment-summary"}] ["需求确认" #{"requirement-confirm"}]
                               ["齐套/交接" #{"kitting" "assembly-test-handover"}] ["FAT/交底/SAT" #{"fat-confirm" "handover" "sat-confirm"}] ["其它" #{"generic"}]]
                :let [items (pick types)] :when (seq items)] ^{:key label}
            [:div {:style {:display "flex" :gap 8 :alignItems "center" :flexWrap "wrap"}}
             [:span {:style {:width 90 :color "#718096"}} label]
             (for [g items] ^{:key (:template_id g)}
               [antd/tag {:color (cond (:passed g) "green" (= "not_started" (:status g)) "default" (= "rejected" (:status g)) "red" :else "blue")}
                (str (:title g) " " (:passed_checks g) "/" (:total_checks g) " · " (get status-labels (:status g) (:status g)))])])])]))


(defn- material-board
  [del]
  (let [rows (:material_requests del)]
    [card "原材预投 / 长周期 / 直发 / 包材申请" "按申请类型统计各状态数量; 采购/供应商进度未接入外部系统"
     (if (empty? rows) [:span {:style {:color "#98a2b3"}} "尚无申请"]
         [:div {:style {:display "grid" :gap 6}}
          (for [[type items] (group-by :request_type rows)] ^{:key type}
            [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
             [:span {:style {:width 80 :color "#718096"}} (get request-labels type type)]
             [status-tags (count-by :status items) status-labels status-colors]])])]))


(defn- kitting-board
  [del]
  (let [rollup (:kitting_rollup del)]
    [card "零件齐套" "冻结BOM按主/子/单机卷积, 缺件可在 工程交付/备料与BOM 下钻"
     [:div {:style {:display "grid" :gap 8}}
      [antd/progress {:percent (or (:kit_percent rollup) 0) :size "small"}]
      [antd/space {:wrap true}
       [antd/tag (str "冻结BOM " (:bom_count rollup))] [antd/tag (str "齐套行 " (:complete_lines rollup) "/" (:required_lines rollup))]
       [antd/tag {:color (if (pos? (count (:shortages rollup))) "red" "green")} (str "缺件行 " (count (:shortages rollup)))]]
      (for [n (:nodes rollup) :when (not= "main" (:node_type n))] ^{:key (:node_id n)}
        [:div {:style {:fontSize 12}} (str (:node_code n) " " (:name n) ": " (:kit_percent n) "% (" (:complete_lines n) "/" (:required_lines n) ")")])]]))


(defn- assembly-board
  [del]
  (let [rows (:assemblies del)]
    [card "装配执行" "上岛/装配/单机交检/连线交检/下岛/交接步骤与独立交检状态"
     (if (empty? rows) [:span {:style {:color "#98a2b3"}} "尚无装配任务"]
         [:div {:style {:display "grid" :gap 6}}
          (for [a rows] ^{:key (:id a)}
            [:div {:style {:display "flex" :gap 8 :alignItems "center" :flexWrap "wrap"}}
             [:span {:style {:minWidth 120}} (:code a)]
             [antd/tag {:color (get status-colors (:status a) "default")} (get status-labels (:status a) (:status a))]
             [antd/progress {:percent (int (* 100 (/ (or (:step_count a) 0) (max 1 (or (:step_total a) 6))))) :size "small" :style {:width 160}
                             :format (fn [_] (str (:step_count a) "/" (:step_total a) " 步"))}]
             [:span {:style {:fontSize 12 :color "#718096"}} (str "当前 " (get {"not_started" "未开始" "on_island" "上岛" "assembling" "装配" "unit_inspection" "单机交检" "wiring_inspection" "连线交检" "off_island" "下岛" "handover" "交接"} (:current_step a) (:current_step a)))]])])]))


(defn- test-board
  [del]
  (let [rows (:tests del) required (get-in del [:configuration :required_test_types])]
    [card "SIT / FAT / SAT" "按试验类别统计状态; 必需试验未批准时发运/收尾被阻塞"
     [:div {:style {:display "grid" :gap 6}}
      (for [type (or required ["SIT" "FAT" "SAT"])] ^{:key type}
        (let [items (filter #(= type (:test_type %)) rows)]
          [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
           [:span {:style {:width 50 :fontWeight 600}} type]
           (if (empty? items) [antd/tag "未建立"] [status-tags (count-by :status items) status-labels status-colors])]))]]))


(defn- issue-board
  [gov]
  (let [issues (:issues gov) summary (:trace_summary gov)]
    [card "偏差与问题" "试验失败自动生成阻断问题; 追踪矩阵偏差分级与逾期/升级状态"
     [:div {:style {:display "grid" :gap 8}}
      [antd/space {:wrap true}
       (for [[sev n] (count-by :severity (remove #(= "closed" (:status %)) issues))] ^{:key sev}
         [antd/tag {:color (get {"blocker" "red" "major" "orange" "minor" "gold"} sev)} (str (get w/labels sev sev) " " n)])
       [antd/tag (str "已关闭 " (count (filter #(= "closed" (:status %)) issues)))]
       [antd/tag {:color (if (pos? (count (filter :issue_overdue issues))) "red" "default")} (str "逾期 " (count (filter :issue_overdue issues)))]
       [antd/tag {:color (if (pos? (count (filter #(= "pending" (:escalation_state %)) issues))) "red" "default")} (str "待升级确认 " (count (filter #(= "pending" (:escalation_state %)) issues)))]]
      [antd/space {:wrap true}
       [antd/tag {:color "blue"} (str "追踪覆盖率 " (get summary :coverage-pct 0) "%")]
       (for [[level n] (get summary :deviations) :when (pos? n)] ^{:key level}
         [antd/tag {:color (get {"minor" "gold" "major" "orange" "blocker" "red"} (name level))} (str "追踪偏差 " (name level) " " n)])]]]))


(defn- deliverable-board
  [gov]
  (let [tree (:document_tree gov) coverage (:release_coverage gov)]
    [card "交付物 / 文档" "按阶段归集最新版本文档与发布覆盖度; 明细在 需求与治理/证据版本 页签下钻"
     [:div {:style {:display "grid" :gap 8}}
      [antd/space {:wrap true}
       [antd/tag {:color "blue"} (str "覆盖文档 " (:total coverage 0))] [antd/tag {:color "green"} (str "已发布率 " (get coverage :released-pct 0) "%")]
       [antd/tag (str "待审 " (get coverage :in-review 0))] [antd/tag (str "未提交 " (:registered coverage 0))]]
      (for [stage tree] ^{:key (:stage stage)}
        [:div {:style {:fontSize 12}} (str (:stage stage) ": " (:count stage) " 份 · 节点 " (count (:nodes stage)))])]]))


(defn- site-board
  [del]
  (let [handovers (:handovers del) tasks (:site_tasks del)]
    [card "交底与现场" "发运后交底时限, 定位/安装/调试/SAT 现场任务进展 (CRM/ERP 回传未接入)"
     [:div {:style {:display "grid" :gap 8}}
      [antd/space {:wrap true}
       [antd/tag (str "交底 " (count handovers))]
       [antd/tag {:color (if (pos? (count (filter :handover_overdue handovers))) "red" "default")} (str "交底逾期 " (count (filter :handover_overdue handovers)))]
       [antd/tag (str "现场任务 " (count tasks))]
       [antd/tag {:color "green"} (str "已完成 " (count (filter #(= "closed" (:status %)) tasks)))]
       [antd/tag {:color (if (pos? (count (filter :site_delayed tasks))) "red" "default")} (str "计划开始已过 " (count (filter :site_delayed tasks)))]]]]))


(defn process-workspace
  "专项过程看板页签."
  [project revision]
  (let [root (str "/projects/" (:project_id project))
        gov (shared/use-resource (str root "/governance") {} [revision])
        del (shared/use-resource (str root "/delivery") {} [revision])
        loading? (some #(and (:loading? %) (nil? (:data %))) [gov del])
        error (some :error [gov del])]
    (cond
      error [shared/error-panel error (:refresh! gov)]
      loading? [:div {:style {:padding 48 :textAlign "center"}} [antd/spin]]
      :else
      (let [g (:data gov) d (:data del)]
        [:div {:style {:display "grid" :gap 16}}
         [:p {:style {:margin 0 :fontSize 12 :color "#718096"}} (str "同一事实源: 治理与交付读模型 · 数据时点 " (.toLocaleString (js/Date.)) " · 外部系统状态 " (:external_sync_status d))]
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit, minmax(420px, 1fr))" :gap 16}}
          [gate-board g] [material-board d] [kitting-board d] [assembly-board d]
          [test-board d] [issue-board g] [deliverable-board g] [site-board d]]]))))
