(ns com.ruoyi.rouyi.frontend.pages.oper-log
  "操作日志页面。只读 Table + 详情弹窗 + 清空。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [EyeOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

;; ─── 详情弹窗 ──────────────────────────────────────────────────────

(defn- detail-modal []
  (let [visible? @(rf/subscribe [:oper-logs/detail-visible?])
        data @(rf/subscribe [:oper-logs/detail-data])]
    [antd/modal {:title "操作日志详情" :open visible? :footer nil :width 700
                 :onCancel #(rf/dispatch [:oper-logs/hide-detail])}
     (when data
       [antd/descriptions {:column 2 :bordered true :size "small"}
        [antd/descriptions-item {:label "操作模块"} (:title data)]
        [antd/descriptions-item {:label "请求方式"} (:request_method data)]
        [antd/descriptions-item {:label "操作人员"} (:oper_name data)]
        [antd/descriptions-item {:label "操作地址"} (:oper_ip data)]
        [antd/descriptions-item {:label "操作状态"} (if (= "0" (:status data)) [antd/tag {:color "green"} "成功"] [antd/tag {:color "red"} "失败"])]
        [antd/descriptions-item {:label "操作时间"} (:oper_time data)]
        [antd/descriptions-item {:label "请求URL" :span 2} (:oper_url data)]
        [antd/descriptions-item {:label "请求参数" :span 2}
         [:pre {:style {:maxHeight 200 :overflow "auto" :fontSize 12 :background "#f5f5f5" :padding 8 :borderRadius 4}}
          (or (:oper_param data) "-")]]
        [antd/descriptions-item {:label "返回结果" :span 2}
         [:pre {:style {:maxHeight 200 :overflow "auto" :fontSize 12 :background "#f5f5f5" :padding 8 :borderRadius 4}}
          (or (:json_result data) "-")]]
        (when (:error_msg data)
          [antd/descriptions-item {:label "错误信息" :span 2}
           [:pre {:style {:color "red" :maxHeight 200 :overflow "auto" :fontSize 12 :background "#fff1f0" :padding 8 :borderRadius 4}}
            (:error_msg data)]])])]))

;; ─── 表格列 ──────────────────────────────────────────────────────

(defn- oper-log-columns []
  #js [#js {:title "日志编号" :dataIndex "oper_id" :key "oper_id" :width 80}
       #js {:title "系统模块" :dataIndex "title" :key "title" :width 120}
       #js {:title "操作类型" :dataIndex "business_type" :key "business_type" :width 100}
       #js {:title "操作人员" :dataIndex "oper_name" :key "oper_name" :width 100}
       #js {:title "操作IP" :dataIndex "oper_ip" :key "oper_ip" :width 130}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _] (r/as-element [antd/tag {:color (if (= v 0) "green" "red")} (if (= v 0) "正常" "失败")]))}
       #js {:title "操作日期" :dataIndex "oper_time" :key "oper_time" :width 180}
       #js {:title "操作" :key "action" :width 80
            :render (fn [_ record]
                      (r/as-element
                       [antd/button {:type "link" :size "small"
                                     :icon (r/as-element [:> EyeOutlined])
                                     :onClick #(rf/dispatch [:oper-logs/show-detail (js->clj record :keywordize-keys true)])}
                        "详情"]))}])

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn oper-log-page []
  (hooks/use-effect (fn [] (rf/dispatch [:oper-logs/fetch {}]) js/undefined) [])
  (let [items @(rf/subscribe [:oper-logs/items]) total @(rf/subscribe [:oper-logs/total]) loading? @(rf/subscribe [:oper-logs/loading?])]
    (fn []
      [:div
       [:h3 "操作日志"]
       [antd/space {:style {:marginBottom 16}}
        [antd/button {:type "primary" :danger true :onClick #(rf/dispatch [:oper-logs/clear])} "清空"]]
       [antd/table {:rowKey "oper_id" :loading loading? :columns (oper-log-columns)
                    :dataSource (clj->js items) :pagination {:pageSize 10 :total total}}]
       [detail-modal]])))
