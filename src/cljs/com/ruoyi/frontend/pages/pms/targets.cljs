(ns com.ruoyi.frontend.pages.pms.targets
  "经营目标看板 (F09): 季度经营目标版本化下达与达成 (实际取季度内关闭项目已批准决算, 目标修订不改写历史)."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.config :as config]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.todo :as todo]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def metric-labels {"revenue" "收入" "gross_margin" "毛利" "closed_projects" "结项数" "on_time_rate" "准时结项率%"})


(defn- target-dialog
  "新建或修订季度目标."
  [path initial]
  {:title (if (:year initial) "修订季度目标" "下达季度目标") :path path :initial (or initial {:year 2026 :quarter 4 :metric "revenue" :currency "CNY"})
   :fields [{:key :year :label "年度" :type :number :min 2000 :max 2100 :required? true}
            {:key :quarter :label "季度" :type :select :required? true :options (mapv (fn [qn] {:value qn :label (str "Q" qn)}) [1 2 3 4])}
            {:key :metric :label "指标" :type :select :required? true :options (mapv (fn [[v l]] {:value v :label l}) metric-labels)}
            {:key :target_value :label "目标值" :required? true :hint "收入/毛利为金额 (两位小数); 结项数为整数; 准时率为 0-100"}
            {:key :currency :label "币种 (金额指标)" :type :select :options (mapv (fn [c] {:value c :label c}) ["CNY" "USD" "EUR" "GBP" "HKD"])}
            {:key :basis :label "目标来源/口径" :type :textarea}]
   :transform (fn [data] (let [v (:target_value data)]
                           (cond-> (dissoc data :currency)
                             (contains? #{"revenue" "gross_margin"} (:metric data)) (assoc :currency (or (:currency data) "CNY"))
                             (= "closed_projects" (:metric data)) (assoc :target_value (js/parseInt v))
                             (= "on_time_rate" (:metric data)) (assoc :target_value (js/parseFloat v)))))})


(defn- achievement-cell
  [pct]
  (r/as-element [antd/progress {:percent (min 100 (or pct 0)) :size "small" :style {:width 160}
                                :format (fn [_] (str pct "%"))
                                :strokeColor (cond (>= (or pct 0) 100) "#389e0d" (>= (or pct 0) 60) "#faad14" :else "#cf1322")}]))


(defn targets-page
  "经营目标看板."
  []
  (let [resource (shared/use-resource "/targets/board" {} [])
        [dialog set-dialog!] (hooks/use-state nil)
        editable? (shared/use-permission "pms:target:edit")]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "经营目标看板"
      "季度经营目标 (收入/毛利/结项数/准时率) 版本化下达, 实际值按季度内关闭项目已批准决算复算, 目标修订保留历史版本"
      [antd/space
       [antd/button {:on-click #((:refresh! resource))} "刷新"]
       (when editable? [antd/button {:type "primary" :on-click #(set-dialog! (target-dialog "/config/quarterly-target" nil))} "下达季度目标"])]]
     [w/resource-view resource
      (fn [data]
        [shared/panel "季度目标与达成" (if (:finance_visible data) "实际值口径: 季度内关闭项目的已批准决算 (收入/毛利) 与关闭项目数" "当前账号无财务权限, 仅显示结项数口径") nil
         [w/record-table (:rows data)
          [{:title "季度" :key "quarter" :width 100 :render (fn [_ row] (str (aget row "year") " Q" (aget row "quarter")))}
           {:title "指标" :dataIndex "metric" :width 110 :render #(get metric-labels % %)}
           (w/text-column :revision "版本")
           {:title "目标" :dataIndex "target_value" :width 140 :render (fn [v row] (str v (when-let [c (aget row "currency")] (str " " c))))}
           {:title "实际" :dataIndex "actual" :width 140 :render (fn [v] (str v))}
           {:title "达成率" :dataIndex "achievement_pct" :width 190 :render (fn [v] (achievement-cell v))}
           {:title "关闭项目" :dataIndex "closed_projects" :width 220
            :render (fn [v] (r/as-element (into [antd/space {:wrap true}]
                                                (map (fn [p] [antd/button {:type "link" :size "small" :style {:padding 0} :on-click #(todo/open-project! (:project_id p))} (:project_no p)])
                                                     (js->clj v :keywordize-keys true)))))}
           (w/text-column :basis "口径")
           {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [config/status-badge %])}]
          (fn [row]
            (when editable?
              [antd/space
               (when (= "draft" (:status row))
                 [w/edit-button "发布" #(set-dialog! {:title "发布目标版本" :path (str "/config/quarterly-target/" (:id row) "/publish") :fields [{:key :reason :label "说明" :type :textarea}]})])
               (when (= "published" (:status row))
                 [w/edit-button "修订" (fn [] (set-dialog! (target-dialog (str "/config/quarterly-target/" (:id row) "/revisions") (select-keys row [:year :quarter :metric :target_value :currency :basis]))))])
               (when (contains? #{"draft" "published"} (:status row))
                 [w/edit-button "退役" #(set-dialog! {:title "退役目标版本" :path (str "/config/quarterly-target/" (:id row) "/retire") :fields [{:key :reason :label "原因" :type :textarea :required? true}]})])]))]])]
     (when dialog [config/config-dialog (merge dialog {:on-close #(set-dialog! nil) :on-saved (fn [_] (set-dialog! nil) ((:refresh! resource)))})])]))
