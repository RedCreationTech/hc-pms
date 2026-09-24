(ns com.ruoyi.frontend.pages.pms.search
  "全局检索 (G16): 跨项目按权限检索项目/任务/治理/交付对象, 机密文档按密级权限过滤, 结果回链到项目页签."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.todo :as todo]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def class-labels {"public" "公开" "internal" "内部" "confidential" "机密"})


(defn- export-csv!
  "把检索结果导出为 CSV 报表 (授权范围内, 不含正文)."
  [results]
  (let [header "项目编号,项目,对象类型,编号,标题,版本,状态,密级,所在页签"
        lines (map #(str (:project_no %) "," (:project_name %) "," (:label %) "," (:code %) ",\"" (:title %) "\"," (or (:revision %) "") "," (or (:status %) "") "," (or (:classification %) "") "," (:tab %)) results)
        text (str "﻿" header "\n" (str/join "\n" lines))
        url (.createObjectURL js/URL (js/Blob. #js [text] #js {:type "text/csv;charset=utf-8"}))
        link (.createElement js/document "a")]
    (set! (.-href link) url) (set! (.-download link) "pms-search.csv") (.click link) (.revokeObjectURL js/URL url)))


(defn search-page
  "全局检索页."
  []
  (let [[text set-text!] (hooks/use-state "")
        [classification set-class!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state nil)
        resource (shared/use-resource (when query "/search") (or query {}) [(:q query) (:classification query)])]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "全局检索"
      "跨项目检索项目, WBS任务, 需求/文档/风险/问题/会议/变更/Gate/DQ, 备料/BOM/装配/试验/发运/售后/工勘/交底/现场任务; 先按项目权限与文档密级过滤, 不返回正文" nil]
     [shared/panel "检索条件" "关键字匹配编号/标题/描述; 密级筛选仅作用于证据文档"
      nil
      [:div {:style {:display "flex" :gap 12 :flexWrap "wrap" :alignItems "center"}}
       [antd/input {:value text :placeholder "输入关键字" :aria-label "检索关键字" :style {:width 360}
                    :onChange #(set-text! (.. % -target -value))
                    :onPressEnter #(when (seq text) (set-query! (cond-> {:q text} classification (assoc :classification classification))))}]
       [antd/select {:value classification :placeholder "全部密级" :allowClear true :aria-label "密级筛选" :style {:width 160}
                     :options (mapv (fn [[v l]] {:value v :label l}) class-labels) :onChange #(set-class! %)}]
       [antd/button {:type "primary" :disabled (empty? text)
                     :on-click #(set-query! (cond-> {:q text} classification (assoc :classification classification)))} "检索"]
       (when (get-in resource [:data :results])
         [antd/button {:on-click #(export-csv! (get-in resource [:data :results]))} "导出CSV"])]]
     [:div {:style {:marginTop 20}}
      (cond
        (nil? query) [shared/empty-state "输入关键字后检索授权范围内的项目对象" nil]
        :else [w/resource-view resource
               (fn [data]
                 [shared/panel (str "检索结果 " (:count data) (when (:truncated data) " (仅显示前200条)"))
                  (if (:confidential_visible data) "当前账号可见机密文档" "当前账号无机密文档权限, 机密文档已过滤") nil
                  [w/record-table (:results data)
                   [{:title "项目" :dataIndex "project_name" :width 200
                     :render (fn [v row] (r/as-element [antd/button {:type "link" :style {:padding 0} :on-click #(todo/open-project! (aget row "project_id"))} v]))}
                    (w/text-column :project_no "项目编号") (w/text-column :label "对象类型") (w/text-column :code "编号")
                    (w/text-column :title "标题") (w/text-column :revision "版本")
                    {:title "密级" :dataIndex "classification" :width 90 :render #(if % (get class-labels % %) "—")}
                    (w/text-column :tab "回链页签") (w/state-column)]
                   nil]])])]]))
