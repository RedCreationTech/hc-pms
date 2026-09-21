(ns com.ruoyi.frontend.pages.business.report
  "办公一体化报表看板."
  (:require
    ["@ant-design/icons" :refer [ReloadOutlined CarOutlined AccountBookOutlined DeploymentUnitOutlined TeamOutlined ShopOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn- stat-card
  [{:keys [title value suffix icon color loading?]}]
  [antd/card {:style {:marginBottom 16} :loading loading?}
   [antd/statistic {:title title :value (or value 0) :suffix suffix
                    :valueStyle {:color color}
                    :prefix (r/as-element [:> icon {:style {:color color}}])}]])


(defn report-page
  []
  (let [d @(rf/subscribe [:report/data])
        loading? @(rf/subscribe [:report/loading?])
        leave (or (:leave d) {}) reimburse (or (:reimburse d) {})
        proc (or (:process d) {})]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left [:div {:style {:fontSize 15 :fontWeight 600}} "办公报表"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新" :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:report/fetch])}]]}]
     ;; 审批流统计
     [antd/row {:gutter 16}
      [antd/col {:span 6} [stat-card {:title "请假单总数" :value (:total leave) :icon CarOutlined :color "#409eff" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "请假待审批" :value (:pending leave) :icon CarOutlined :color "#e6a23c" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "请假已通过" :value (:approved leave) :icon CarOutlined :color "#67c23a" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "请假已驳回" :value (:rejected leave) :icon CarOutlined :color "#f56c6c" :loading? loading?}]]]
     [antd/row {:gutter 16}
      [antd/col {:span 6} [stat-card {:title "报销单总数" :value (:total reimburse) :icon AccountBookOutlined :color "#409eff" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "报销待审批" :value (:pending reimburse) :icon AccountBookOutlined :color "#e6a23c" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "报销已通过" :value (:approved reimburse) :icon AccountBookOutlined :color "#67c23a" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "报销总额(元)" :value (:total-amount reimburse) :icon AccountBookOutlined :color "#b88230" :loading? loading?}]]]
     [antd/row {:gutter 16}
      [antd/col {:span 6} [stat-card {:title "运行中流程" :value (:running proc) :icon DeploymentUnitOutlined :color "#409eff" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "流程定义数" :value (:definitions proc) :icon DeploymentUnitOutlined :color "#722ed1" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "我的待办" :value (:my-todo proc) :icon DeploymentUnitOutlined :color "#fa8c16" :loading? loading?}]]
      [antd/col {:span 6} [stat-card {:title "员工数" :value (get-in d [:hrm :employee-total]) :icon TeamOutlined :color "#13c2c2" :loading? loading?}]]]
     [antd/row {:gutter 16}
      [antd/col {:span 6} [stat-card {:title "客户数" :value (get-in d [:crm :customer-total]) :icon ShopOutlined :color "#52c41a" :loading? loading?}]]]]))
