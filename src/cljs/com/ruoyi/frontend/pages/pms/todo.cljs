(ns com.ruoyi.frontend.pages.pms.todo
  "我的待办 (C07/C09 本地提醒): 跨项目待我审批, 待我确认升级, 我负责的到期/逾期事项; 只读派生, 不投递外部消息."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.pages.pms.approval :as approval]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn open-project!
  "跳转到项目中心并打开该项目详情."
  [project-id]
  (rf/dispatch [:navigate :pms-project {:id project-id}]))


(defn countdown-tag
  "按剩余天数呈现逾期/临期/剩余."
  [days overdue]
  (cond (nil? days) [:span {:style {:color "#98a2b3"}} "无到期日"]
        overdue [antd/tag {:color "red"} (str "已逾期 " (- days) " 天")]
        (<= days 3) [antd/tag {:color "gold"} (str "剩 " days " 天临期")]
        :else [antd/tag {:color "blue"} (str "剩 " days " 天")]))


(defn- item-table
  [rows]
  [w/record-table rows
   [{:title "项目" :dataIndex "project_name" :width 220
     :render (fn [v row] (r/as-element [antd/button {:type "link" :style {:padding 0} :on-click #(open-project! (aget row "project_id"))} v]))}
    (w/text-column :project_no "项目编号") {:title "事项" :dataIndex "label" :width 130 :render shared/display-value}
    (w/text-column :title "内容") (w/text-column :code "编号") (w/text-column :tab "所在页签")
    (w/text-column :due_date "到期日")
    {:title "倒计时" :key "countdown" :width 140 :render (fn [_ row] (r/as-element [countdown-tag (aget row "days") (true? (aget row "overdue"))]))}
    (w/state-column)]
   nil])


(defn- approval-table
  "按审批策略逐级审批的待办, 当前级审批人可直接通过或驳回."
  [rows on-decide]
  [w/record-table rows
   [{:title "项目" :dataIndex "project_name" :width 200
     :render (fn [v row] (r/as-element [antd/button {:type "link" :style {:padding 0} :on-click #(open-project! (aget row "project_id"))} v]))}
    (w/text-column :type_label "类型") (w/text-column :level_name "审批级别") (w/text-column :title "内容")
    (w/text-column :submitted_by_name "提交人") (w/text-column :amount "金额")]
   (fn [row] [antd/button {:type "primary" :size "small" :on-click #(on-decide row)} "审批"])])


(defn- office-todo
  "办公流程 (请假/报销/自定义表单) 待办汇总, 在办公 \"我的待办\" 处理."
  []
  (let [[rows set-rows!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn []
        (api/bpm-list-todo #(set-rows! (vec (get-in % [:data :rows] []))) (fn [_] (set-rows! [])))
        js/undefined)
      [])
    [shared/panel "办公流程待办" "请假, 报销与自定义表单流程的待办任务, 在办公 \"我的待办\" 中审批"
     [antd/button {:on-click #(rf/dispatch [:navigate :bpm-todo])} "前往办公待办"]
     (if (empty? rows)
       [:span {:style {:color "#98a2b3"}} (if (nil? rows) "加载中..." "暂无办公流程待办")]
       [w/record-table rows
        [(w/text-column :instance-name "流程") (w/text-column :name "当前节点") (w/text-column :bill-code "单号")]
        nil])]))


(defn- summary-cards
  [summary]
  [antd/space {:wrap true :style {:marginBottom 20}}
   [antd/tag {:color "magenta" :style {:fontSize 14 :padding "4px 10px"}} (str "逐级审批 " (:approvals summary 0))]
   [antd/tag {:color "blue" :style {:fontSize 14 :padding "4px 10px"}} (str "待我审批 " (:reviews summary 0))]
   [antd/tag {:color "purple" :style {:fontSize 14 :padding "4px 10px"}} (str "待我确认升级 " (:escalations summary 0))]
   [antd/tag {:color "geekblue" :style {:fontSize 14 :padding "4px 10px"}} (str "我负责的事项 " (:owned summary 0))]
   [antd/tag {:color "volcano" :style {:fontSize 14 :padding "4px 10px"}} (str "系统提醒 " (:reminders summary 0))]
   [antd/tag {:color "red" :style {:fontSize 14 :padding "4px 10px"}} (str "已逾期 " (:overdue summary 0))]
   [antd/tag {:color "gold" :style {:fontSize 14 :padding "4px 10px"}} (str "3天内到期 " (:due_soon summary 0))]])


(defn todo-page
  "我的待办."
  []
  (let [resource (shared/use-resource "/todo" {} [])
        [deciding set-deciding!] (hooks/use-state nil)]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "我的待办"
      "跨项目汇总待我独立审批, 待我确认的升级处置, 以及我负责的到期/逾期事项 (按服务器日期读取时派生, 本地提醒, 不投递外部消息)"
      [antd/button {:on-click #((:refresh! resource))} "刷新"]]
     [w/resource-view resource
      (fn [data]
        [:div {:style {:display "grid" :gap 20}}
         [summary-cards (:summary data)]
         [shared/panel "逐级审批" "按 \"模板与规则 > 审批策略\" 配置的多级审批中, 轮到我处理的级别 (计划基线/项目章程/费用版本/项目结项)" nil
          [approval-table (:approvals data) set-deciding!]]
         [shared/panel "待我审批" "章程/变更/文档发布/Gate/风险复评/问题验证/行动核验/DQ/备料/BOM/装配/试验/发运/售后/工勘/工时/费用版本/计划基线/关闭与重开" nil
          [item-table (:reviews data)]]
         [shared/panel "待我确认升级" "超阈值风险与阻断/逾期追溯升级的问题, 须由登记人之外的独立质量审批人确认" nil
          [item-table (:escalations data)]]
         [shared/panel "我负责的事项" "未关闭的问题/行动/风险复审, 待完成的交底与现场任务, 按逾期优先排序" nil
          [item-table (:owned data)]]
         [shared/panel "系统提醒" "每日 06:00 定时扫描 (或手动快照) 登记的逾期任务/问题/行动/交底/现场任务, 责任人与项目经理可见; 对象不再逾期时自动关闭" nil
          [item-table (:reminders data)]]
         [office-todo]])]
     (when deciding
       [approval/decide-dialog {:item deciding :on-close #(set-deciding! nil)
                                :on-saved (fn [_] (set-deciding! nil) ((:refresh! resource)))}])]))
