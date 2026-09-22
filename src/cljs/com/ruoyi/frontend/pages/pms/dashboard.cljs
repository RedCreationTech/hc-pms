(ns com.ruoyi.frontend.pages.pms.dashboard
  "项目驾驶舱,所有数字来自服务端权限范围内的项目."
  (:require
    ["@ant-design/icons" :refer [ArrowRightOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))

(def metrics
  [{:key :total :label "项目总数" :caption "当前权限范围内的全部项目" :number "01"}
   {:key :active :label "进行中" :caption "已立项及正在推进的项目" :number "02"}
   {:key :overdue :label "已逾期" :caption "超过计划完成日期的未关闭项目" :number "03"}
   {:key :draft :label "待立项" :caption "仍处于草稿阶段的项目" :number "04"}])

(defn- metric-card
  "用真实汇总数字呈现项目组合概况."
  [metric data loading?]
  (let [colors (shared/use-colors)
        overdue? (and (= :overdue (:key metric)) (pos? (get data :overdue 0)))]
    [:div {:style {:padding "22px 24px" :borderRadius 10 :background (:bg colors)
                   :border (str "1px solid " (:border colors)) :position "relative"}}
     [:div {:style {:display "flex" :justifyContent "space-between" :color (:muted colors)}}
      [:span {:style {:fontWeight 550}} (:label metric)]
      [:span {:style {:fontFamily "ui-monospace, SFMono-Regular, monospace" :fontSize 11}} (:number metric)]]
     [:div {:style {:fontSize 42 :fontWeight 650 :letterSpacing -1.5 :lineHeight 1.5 :margin "8px 0"
                    :fontVariantNumeric "tabular-nums" :color (if overdue? (:danger colors) (:text colors))}}
      (if loading? "—" (get data (:key metric) "—"))]
     [:div {:style {:fontSize 12 :color (:muted colors)}} (:caption metric)]]))

(defn- recent-projects
  "最近创建的项目直接进入项目详情."
  [revision]
  (let [{:keys [data loading? error refresh!]} (shared/use-resource "/projects" {:page 1 :size 6} [revision])
        can-query? (shared/use-permission "pms:project:query")]
    [shared/panel "最近项目" "从项目出发,继续你的工作"
     [antd/button {:type "link" :on-click #(rf/dispatch [:navigate :pms-project])} "查看全部 →"]
     (when error [shared/error-panel error refresh!])
     [antd/table {:rowKey "project_id" :dataSource (clj->js (:rows data [])) :loading loading?
                  :pagination false :size "middle" :scroll {:x 600}
                  :locale {:emptyText (r/as-element [shared/empty-state "暂无项目,前往项目中心创建第一个项目" nil])}
                  :columns
                  (clj->js
                    [{:title "项目名称" :dataIndex "name"
                      :render (fn [name ^js record]
                                (if can-query?
                                  (r/as-element [antd/button {:type "link" :style {:padding 0 :fontWeight 550}
                                                              :on-click #(rf/dispatch [:navigate :pms-project {:id (.-project_id record)}])}
                                                 name])
                                  name))}
                     {:title "项目经理" :dataIndex "manager_name" :width 120 :render shared/display-value}
                     {:title "状态" :dataIndex "status" :width 120 :render #(r/as-element [shared/status-tag %])}
                     {:title "计划完成" :dataIndex "end_date" :width 150 :render shared/display-value}])}]]))

(defn- workflow-note
  "给项目经理提供明确的首批业务路径."
  []
  (let [colors (shared/use-colors)]
    [:aside {:style {:padding "24px 26px" :borderRadius 10 :background (:soft colors)
                     :border (str "1px solid " (:border colors)) :marginTop 20}}
     [:div {:style {:display "flex" :alignItems "center" :gap 12 :marginBottom 10}}
      [:span {:style {:width 4 :height 18 :background (:primary colors) :borderRadius 2}}]
      [:strong "从清晰的项目起点,走向可追踪的交付"]]
     [:p {:style {:margin "0 0 14px" :fontSize 13 :lineHeight 1.9 :color (:muted colors)}}
      "创建项目草稿,明确项目经理和计划日期;完成立项后,建立主项目、子项目与单机结构,邀请成员共同准备项目计划."]
     [:div {:style {:display "flex" :gap 10 :alignItems "center" :flexWrap "wrap"}}
      [antd/tag "01 创建草稿"] [:span "→"] [antd/tag {:color "blue"} "02 项目立项"]
      [:span "→"] [antd/tag {:color "cyan"} "03 计划准备"]]]))

(defn- dashboard-workspace
  "汇总项目组合状态并提供项目入口."
  []
  (let [colors (shared/use-colors)
        [revision set-revision!] (hooks/use-state 0)
        {:keys [data loading? error refresh!]} (shared/use-resource "/dashboard" {} [revision])
        can-list? (shared/use-permission "pms:project:list")]
    [:main {:style {:padding "26px 28px" :color (:text colors) :maxWidth 1800 :margin "0 auto"}}
     [shared/page-heading "HC / PORTFOLIO OVERVIEW" "项目驾驶舱" "掌握项目组合的当前状态,把注意力放在需要推进的事情上."
      [antd/space
       [antd/button {:title "刷新驾驶舱" :icon (r/as-element [:> ReloadOutlined])
                     :loading loading? :on-click #(set-revision! inc)}]
       (when can-list? [antd/button {:type "primary" :icon (r/as-element [:> ArrowRightOutlined])
                                     :on-click #(rf/dispatch [:navigate :pms-project])} "进入项目中心"])]]
     (when error [shared/error-panel error refresh!])
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit, minmax(200px, 1fr))" :gap 18 :marginBottom 26}}
      (for [metric metrics] ^{:key (:key metric)} [metric-card metric data loading?])]
     (when can-list? [recent-projects revision])
     [workflow-note]]))

(defn dashboard-page
  "驾驶舱遵循当前用户的模块权限."
  []
  (if (shared/use-permission "pms:dashboard:query")
    [dashboard-workspace]
    [shared/empty-state "当前账号没有项目驾驶舱访问权限" nil]))
