(ns com.ruoyi.frontend.pages.pms.todo
  "我的待办 (C07/C09 本地提醒): 跨项目待我审批, 待我确认升级, 我负责的到期/逾期事项; 只读派生, 不投递外部消息."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [re-frame.core :as rf]
    [reagent.core :as r]))


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


(defn- summary-cards
  [summary]
  [antd/space {:wrap true :style {:marginBottom 20}}
   [antd/tag {:color "blue" :style {:fontSize 14 :padding "4px 10px"}} (str "待我审批 " (:reviews summary 0))]
   [antd/tag {:color "purple" :style {:fontSize 14 :padding "4px 10px"}} (str "待我确认升级 " (:escalations summary 0))]
   [antd/tag {:color "geekblue" :style {:fontSize 14 :padding "4px 10px"}} (str "我负责的事项 " (:owned summary 0))]
   [antd/tag {:color "red" :style {:fontSize 14 :padding "4px 10px"}} (str "已逾期 " (:overdue summary 0))]
   [antd/tag {:color "gold" :style {:fontSize 14 :padding "4px 10px"}} (str "3天内到期 " (:due_soon summary 0))]])


(defn todo-page
  "我的待办."
  []
  (let [resource (shared/use-resource "/todo" {} [])]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "我的待办"
      "跨项目汇总待我独立审批, 待我确认的升级处置, 以及我负责的到期/逾期事项 (按服务器日期读取时派生, 本地提醒, 不投递外部消息)"
      [antd/button {:on-click #((:refresh! resource))} "刷新"]]
     [w/resource-view resource
      (fn [data]
        [:div {:style {:display "grid" :gap 20}}
         [summary-cards (:summary data)]
         [shared/panel "待我审批" "章程/变更/文档发布/Gate/风险复评/问题验证/行动核验/DQ/备料/BOM/装配/试验/发运/售后/工勘/工时" nil
          [item-table (:reviews data)]]
         [shared/panel "待我确认升级" "超阈值风险与阻断/逾期追溯升级的问题, 须由登记人之外的独立质量审批人确认" nil
          [item-table (:escalations data)]]
         [shared/panel "我负责的事项" "未关闭的问题/行动/风险复审, 待完成的交底与现场任务, 按逾期优先排序" nil
          [item-table (:owned data)]]])]]))
