(ns com.ruoyi.frontend.pages.pms.search
  "全局检索 (G16): 跨项目按权限检索项目/任务/治理/交付对象与经验教训, 机密文档按密级权限过滤, 结果回链到项目页签.
   H15 追加跨项目知识复用面板: 按适用场景/分类/关键字检索授权范围内的经验, 并呈现场景覆盖与责任人落地度."
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


(defn- knowledge-panel
  "H15 跨项目可复用经验只读面板: 覆盖度标签 + 按场景分布 + 可回链项目的经验台账."
  []
  (let [[q set-q!] (hooks/use-state "")
        [stage set-stage!] (hooks/use-state nil)
        [category set-category!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state {})
        resource (shared/use-resource "/knowledge" query [query])]
    [shared/panel "跨项目知识复用"
     "按适用场景/分类/关键字检索授权项目内的经验教训; 只读派生, 不返回其他项目未授权的经验"
     nil
     [:div {:style {:display "grid" :gap 12}}
      [:div {:style {:display "flex" :gap 12 :flexWrap "wrap" :alignItems "center"}}
       [antd/input {:value q :placeholder "检索标题或正文" :aria-label "经验关键字" :style {:width 280}
                    :onChange #(set-q! (.. % -target -value))}]
       [antd/select {:value stage :placeholder "全部场景" :allowClear true :aria-label "适用场景筛选" :style {:width 160}
                     :options (mapv (fn [s] {:value s :label s}) (or (get-in resource [:data :stages]) []))
                     :onChange set-stage!}]
       [antd/input {:value (or category "") :placeholder "全部分类" :aria-label "经验分类筛选" :style {:width 160}
                    :onChange #(set-category! (.. % -target -value))}]
       [antd/button {:type "primary" :aria-label "知识检索"
                     :on-click #(set-query! (cond-> {}
                                              (seq q) (assoc :q q)
                                              stage (assoc :stage stage)
                                              (seq category) (assoc :category category)))} "检索经验"]
       [antd/button {:aria-label "重置知识检索"
                     :on-click #(do (set-q! "") (set-stage! nil) (set-category! "") (set-query! {}))} "重置"]]
      [w/resource-view
       resource
       (fn [d]
         (if-not (:available d)
           [shared/empty-state "授权范围内暂无可复用经验, 在项目收尾登记经验后在此汇总" nil]
           [:div {:style {:display "grid" :gap 12}}
            [antd/space {:wrap true}
             [antd/tag {:color "blue"} (str "可复用经验 " (:total d))]
             [antd/tag {:color "geekblue"} (str "涉及项目 " (:project-count d))]
             [antd/tag {:color (if (= 100 (:stage-coverage-pct d)) "green" "gold")}
              (str "已标注适用场景 " (:stage-declared d) " (" (:stage-coverage-pct d) "%)")]
             [antd/tag {:color (if (pos? (:owner-declared d)) "green" "default")}
              (str "责任人落地 " (:owner-declared d) " (" (:owner-coverage-pct d) "%)")]]
            (when (seq (:by-stage d))
              [antd/space {:wrap true}
               (for [{:keys [stage count]} (:by-stage d)]
                 ^{:key stage} [antd/tag {:color "purple"} (str stage " " count " 条")])])
            [w/record-table
             (:rows d)
             [{:title "项目" :dataIndex "project_name" :width 200
               :render (fn [v row]
                         (r/as-element
                           [antd/button {:type "link" :style {:padding 0}
                                         :on-click #(todo/open-project! (aget row "project_id"))} v]))}
              (w/text-column :project_no "项目编号")
              (w/text-column :title "经验标题")
              (w/text-column :category "分类")
              (w/text-column :applicable_stage "适用场景")
              (w/text-column :owner_name "责任人")
              (w/text-column :created_at "登记时间")]
             nil]]))]]]))


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
                   nil]])])
      [knowledge-panel]]]))
