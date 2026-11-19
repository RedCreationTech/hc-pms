(ns com.ruoyi.frontend.pages.pms.reports
  "报表中心 (G16): 展示已发布报表指标定义的求值结果, 定义按版本维护在模板与规则, 导出与页面同一口径."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def unit-labels {"count" "个" "money" "金额" "percent" "百分比"})


(def object-labels {"project" "项目" "cost-entry" "费用明细" "task" "WBS任务"})


(defn- export-csv!
  "经后端导出: 与页面同一求值与列定义, 服务器返回带 BOM 的 CSV."
  [on-error]
  (let [token (api/get-token)]
    (-> (js/fetch (str api/api-base "/pms/reports/export")
                  #js {:method "GET" :headers (clj->js (cond-> {} token (assoc "Authorization" (str "Bearer " token))))})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.blob resp)
                   (js/Promise.reject (js/Error. (str "导出失败: " (.-status resp)))))))
        (.then (fn [blob]
                 (let [url (js/URL.createObjectURL blob)
                       a (.createElement js/document "a")]
                   (set! (.-href a) url)
                   (set! (.-download a) "pms-report.csv")
                   (.appendChild (.-body js/document) a)
                   (.click a)
                   (.removeChild (.-body js/document) a)
                   (js/URL.revokeObjectURL url))))
        (.catch #(when on-error (on-error (str %)))))))


(defn- columns
  "报表列定义取服务器返回的 columns, 保证界面与 CSV 导出同列同序."
  [cols]
  (mapv (fn [col]
          (case (:key col)
            "value" {:title (:title col) :dataIndex "value" :width 150 :align "right"
                     :render (fn [v row] (if (aget row "available") (str v) "不可算"))}
            "row_count" {:title (:title col) :dataIndex "row_count" :width 90 :align "right"}
            "unit" {:title (:title col) :dataIndex "unit" :width 90
                    :render (fn [v] (get unit-labels v v))}
            "object_type" {:title (:title col) :dataIndex "object_type" :width 110
                           :render (fn [v] (get object-labels v v))}
            "filter_status" {:title (:title col) :dataIndex "filter_status" :width 110
                             :render (fn [v] (if (seq (or v "")) (r/as-element [antd/tag v]) "全部"))}
            {:title (:title col) :dataIndex (:key col)}))
        cols))


(defn- report-section
  [{:keys [data]}]
  (let [[error set-error!] (hooks/use-state nil)]
    [:div {:style {:display "grid" :gap 16}}
     (when error [shared/error-panel error nil])
     (when (and data (not (:available data)))
       [shared/empty-state "暂无生效的报表指标"
        [:span "指标定义需在 模板与规则 - 报表指标 中创建并发布, 草稿与已退役版本不参与求值"]])
     (when data
       [antd/space {:wrap true :size 8}
        [antd/tag (str "生效指标 " (:metric_count data))]
        [antd/tag (str "生成于 " (:generated_at data))]
        (when (pos? (:withheld_count data))
          [antd/tag {:color "orange"} (str "因无财务权限隐藏 " (:withheld_count data) " 项费用明细指标")])])
     (when (and data (seq (:rows data)))
       [w/record-table (:rows data) (columns (:columns data)) nil])
     (when (and data (seq (:excluded data)))
       [:div {:style {:fontSize 12 :color "#8793a3" :lineHeight 1.8}}
        [:div "未生效 (草稿/已退役) 的指标定义, 不参与求值:"]
        (for [row (:excluded data)] ^{:key (str (:code row) (:revision row))}
             [:div (str (:code row) " V" (:revision row) " · " (:status row))])])]))


(defn reports-page
  "报表中心页."
  []
  (let [resource (shared/use-resource "/reports" {} [])]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "报表中心"
      "已发布报表指标在授权项目范围内的求值; 指标口径由 对象/聚合/字段/状态过滤 组合定义, 导出 CSV 与页面同列同序" nil]
     [shared/panel "报表指标"
      "求值范围与登录用户的项目权限一致; 费用明细类指标另需财务查看权限, 无权限时如实隐藏而不是返回 0"
      [antd/button {:icon (r/as-element [antd/download-icon])
                    :on-click #(export-csv! (fn [msg] (antd/error! msg)))}
       "导出CSV"]
      [w/resource-view resource (fn [data] [report-section {:data data}])]]]))
