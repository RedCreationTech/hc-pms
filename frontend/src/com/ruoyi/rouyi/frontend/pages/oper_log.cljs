(ns com.ruoyi.rouyi.frontend.pages.oper-log
  "操作日志页面。只读 Table + 详情弹窗 + 清空 + 导出。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defonce detail-modal (r/atom {:open? false :record nil}))

(defn- open-detail [record]
  (reset! detail-modal {:open? true :record record}))

(defn- close-detail []
  (reset! detail-modal {:open? false :record nil}))

(defn- oper-log-columns []
  #js [#js {:title "日志编号" :dataIndex "oper_id" :key "oper_id" :width 80}
       #js {:title "系统模块" :dataIndex "title" :key "title"}
       #js {:title "操作类型" :dataIndex "business_type" :key "business_type"}
       #js {:title "操作人员" :dataIndex "oper_name" :key "oper_name"}
       #js {:title "操作IP" :dataIndex "oper_ip" :key "oper_ip"}
       #js {:title "操作地点" :dataIndex "oper_location" :key "oper_location"}
       #js {:title "状态" :dataIndex "status" :key "status"
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:color (if (= v 0) "green" "red")}
                        (if (= v 0) "正常" "失败")]))}
       #js {:title "操作日期" :dataIndex "oper_time" :key "oper_time"}
       #js {:title "操作" :key "action" :width 80
            :render (fn [_ record]
                      (r/as-element
                       [antd/button {:type "link" :size "small"
                                     :onClick #(open-detail record)}
                        [antd/eye-icon] "详情"]))}])

(defn- detail-modal-content []
  (let [^js record (:record @detail-modal)]
    (when record
      [:div
       [antd/descriptions {:column 2 :size "small" :bordered true}
        [antd/descriptions-item {:label "日志编号"} (.-oper_id record)]
        [antd/descriptions-item {:label "系统模块"} (.-title record)]
        [antd/descriptions-item {:label "操作类型"} (.-business_type record)]
        [antd/descriptions-item {:label "请求方式"} (.-request_method record)]
        [antd/descriptions-item {:label "操作人员"} (.-oper_name record)]
        [antd/descriptions-item {:label "部门"} (.-dept_name record)]
        [antd/descriptions-item {:label "请求URL"} (.-oper_url record)]
        [antd/descriptions-item {:label "请求IP"} (.-oper_ip record)]
        [antd/descriptions-item {:label "操作地点"} (.-oper_location record)]
        [antd/descriptions-item {:label "请求参数"} [:pre {:style {:maxHeight 100 :overflow "auto"}} (.-oper_param record)]]
        [antd/descriptions-item {:label "状态"} (if (= (.-status record) 0) "正常" "失败")]
        [antd/descriptions-item {:label "错误信息"} (.-error_msg record)]
        [antd/descriptions-item {:label "操作时间"} (.-oper_time record)]
        [antd/descriptions-item {:label "消耗时间"} (str (.-cost_time record) "ms")]]])))

(defn oper-log-page []
  (hooks/use-effect (fn []
                      (rf/dispatch [:oper-logs/fetch {}])
                      js/undefined)
                    [])
  (let [items @(rf/subscribe [:oper-logs/items])
        total @(rf/subscribe [:oper-logs/total])
        loading? @(rf/subscribe [:oper-logs/loading?])]
    (fn []
      [:div
       [:h3 "操作日志"]
       [antd/space {:style {:marginBottom 16}}
        [antd/button {:type "primary" :danger true
                      :onClick #(rf/dispatch [:oper-logs/clear])}
         "清空"]
        [antd/button {:icon (r/as-element [antd/download-icon])}
         "导出"]]
       [antd/table {:rowKey "oper_id"
                    :loading loading?
                    :columns (oper-log-columns)
                    :dataSource (clj->js items)
                    :pagination {:pageSize 10 :total total}}]
       [antd/modal {:title "操作日志详情"
                    :open (:open? @detail-modal)
                    :footer nil
                    :onCancel close-detail
                    :width 800}
        [detail-modal-content]]])))
