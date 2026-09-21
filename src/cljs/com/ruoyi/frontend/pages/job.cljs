(ns com.ruoyi.frontend.pages.job
  "定时任务管理页面."
  (:require
    ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined PlayCircleOutlined FileTextOutlined SearchOutlined ReloadOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn- status-tag
  [status]
  [antd/tag {:color (if (= status "0") "green" "red")}
   (if (= status "0") "正常" "暂停")])


(defn- job-columns
  [on-edit on-show-log]
  #js [#js {:title "任务ID" :dataIndex "job_id" :key "job_id" :width 80}
       #js {:title "任务名称" :dataIndex "job_name" :key "job_name"}
       #js {:title "任务组" :dataIndex "job_group" :key "job_group"}
       #js {:title "调用目标" :dataIndex "invoke_target" :key "invoke_target"}
       #js {:title "Cron表达式" :dataIndex "cron_expression" :key "cron_expression"}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v] (r/as-element [status-tag v]))}
       #js {:title "操作" :key "action" :width 280
            :render (fn [_ ^js record]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"
                                       :icon (r/as-element [:> EditOutlined])
                                       :onClick #(on-edit (js->clj record :keywordize-keys true))}
                          "编辑"]
                         [antd/popconfirm {:title "确认删除该任务？"
                                           :onConfirm #(rf/dispatch [:jobs/delete (.-job_id record)])}
                          [antd/button {:type "link" :danger true :size "small"
                                        :icon (r/as-element [:> DeleteOutlined])}
                           "删除"]]
                         [antd/button {:type "link" :size "small"
                                       :disabled (= (.-status record) "0")
                                       :onClick #(rf/dispatch [:jobs/update (.-job_id record) {:status "0"}])}
                          "恢复"]
                         [antd/button {:type "link" :size "small"
                                       :disabled (= (.-status record) "1")
                                       :onClick #(rf/dispatch [:jobs/update (.-job_id record) {:status "1"}])}
                          "暂停"]
                         [antd/button {:type "link" :size "small"
                                       :icon (r/as-element [:> FileTextOutlined])
                                       :onClick #(on-show-log (.-job_name record))}
                          "日志"]
                         [antd/popconfirm {:title "确认立即执行一次该任务？"
                                           :onConfirm #(rf/dispatch [:jobs/run-once (.-job_id record)])}
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> PlayCircleOutlined])}
                           "执行"]]]))}])


(defn job-page
  []
  (let [[show-form? set-show-form!] (hooks/use-state false)
        [job-name set-job-name!] (hooks/use-state "")
        [job-group set-job-group!] (hooks/use-state "")
        [editing-record set-editing-record!] (hooks/use-state nil)
        [show-log? set-show-log!] (hooks/use-state false)
        [log-job-name set-log-job-name!] (hooks/use-state "")
        ;; Form fields
        [form-name set-form-name!] (hooks/use-state "")
        [form-group set-form-group!] (hooks/use-state "DEFAULT")
        [form-target set-form-target!] (hooks/use-state "")
        [form-cron set-form-cron!] (hooks/use-state "")
        [form-remark set-form-remark!] (hooks/use-state "")]
    (hooks/use-effect
      (fn []
        (rf/dispatch [:jobs/fetch {}])
        js/undefined)
      [])
    (let [items @(rf/subscribe [:jobs/items])
          total @(rf/subscribe [:jobs/total])
          loading? @(rf/subscribe [:jobs/loading?])
          log-items @(rf/subscribe [:job-logs/items])
          log-total @(rf/subscribe [:job-logs/total])
          log-loading? @(rf/subscribe [:job-logs/loading?])
          on-edit (fn [record]
                    (set-form-name! (:job_name record ""))
                    (set-form-group! (:job_group record "DEFAULT"))
                    (set-form-target! (:invoke_target record ""))
                    (set-form-cron! (:cron_expression record ""))
                    (set-form-remark! (:remark record ""))
                    (set-editing-record! record)
                    (set-show-form! true))
          on-close-form (fn []
                          (set-show-form! false)
                          (set-editing-record! nil)
                          (set-form-name! "")
                          (set-form-group! "DEFAULT")
                          (set-form-target! "")
                          (set-form-cron! "")
                          (set-form-remark! ""))]
      [:div
       ;; 搜索栏
       [page-search/page-search {:visible? true}
        [page-search/search-row
         [page-search/search-item
          "任务名称"
          [antd/input {:placeholder "请输入任务名称"
                       :style page-search/input-style
                       :value job-name
                       :onChange #(set-job-name! (-> % .-target .-value))}]]
         [page-search/search-item
          "任务组名"
          [antd/input {:placeholder "请输入任务组名"
                       :style page-search/input-style
                       :value job-group
                       :onChange #(set-job-group! (-> % .-target .-value))}]]
         [page-search/search-actions
          [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                       :on-click #(rf/dispatch [:jobs/search {:job_name job-name :job_group job-group}])}]
          [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                      :on-click #(do (set-job-name! "")
                                                     (set-job-group! "")
                                                     (rf/dispatch [:jobs/fetch {}]))}]]]]
       ;; 工具栏
       [page-toolbar/page-toolbar
        {:left [page-toolbar/toolbar-left
                [page-toolbar/toolbar-button {:kind :add
                                              :icon (r/as-element [:> PlusOutlined])
                                              :on-click #(do (set-editing-record! nil)
                                                             (set-form-name! "")
                                                             (set-form-group! "DEFAULT")
                                                             (set-form-target! "")
                                                             (set-form-cron! "")
                                                             (set-form-remark! "")
                                                             (set-show-form! true))
                                              :label "新增"}]]
         :right [page-toolbar/toolbar-right
                 [page-toolbar/round-tool-button {:title "搜索"
                                                  :icon (r/as-element [:> SearchOutlined])
                                                  :on-click #(rf/dispatch [:jobs/search {:job_name job-name :job_group job-group}])}]
                 [page-toolbar/round-tool-button {:title "刷新"
                                                  :icon (r/as-element [:> ReloadOutlined])
                                                  :on-click #(rf/dispatch [:jobs/fetch {}])}]]}]

       ;; 表格
       [antd/table {:scroll #js {:x "max-content"} :rowKey "job_id"
                    :rowSelection #js {}
                    :loading loading?
                    :columns (job-columns
                               on-edit
                               (fn [job-name]
                                 (set-log-job-name! job-name)
                                 (rf/dispatch [:job-logs/fetch {:job_name job-name}])
                                 (set-show-log! true)))
                    :dataSource (clj->js items)
                    :pagination {:pageSize 10 :total total}}]

       ;; 新增/编辑弹窗
       [antd/modal {:title (if editing-record "编辑任务" "新增任务")
                    :open show-form?
                    :onOk (fn []
                            (let [data {:job_name form-name :job_group form-group
                                        :invoke_target form-target :cron_expression form-cron
                                        :remark form-remark :status "0" :misfire_policy "3" :concurrent "1"}]
                              (if editing-record
                                (rf/dispatch [:jobs/update (:job_id editing-record) data])
                                (rf/dispatch [:jobs/create data]))
                              (on-close-form)))
                    :onCancel on-close-form
                    :destroyOnHidden true}
        [antd/form {:layout "vertical"}
         [antd/form-item {:label "任务名称" :required true}
          [antd/input {:value form-name :onChange #(set-form-name! (-> % .-target .-value))}]]
         [antd/form-item {:label "任务组" :required true}
          [antd/select {:value form-group :onChange #(set-form-group! %)}
           [antd/select-option {:value "DEFAULT"} "默认"]
           [antd/select-option {:value "SYSTEM"} "系统"]]]
         [antd/form-item {:label "调用目标" :required true}
          [antd/input {:value form-target :onChange #(set-form-target! (-> % .-target .-value))}]]
         [antd/form-item {:label "Cron表达式" :required true}
          [antd/input {:value form-cron :onChange #(set-form-cron! (-> % .-target .-value))}]]
         [antd/form-item {:label "备注"}
          [antd/text-area {:value form-remark :rows 3 :onChange #(set-form-remark! (-> % .-target .-value))}]]]]

       ;; 日志抽屉
       [antd/drawer {:title (str "任务日志 - " log-job-name)
                     :open show-log?
                     :onClose #(set-show-log! false)
                     :style {:width 800}
                     :destroyOnHidden true}
        [antd/table {:scroll #js {:x "max-content"} :rowKey "job_log_id"
                     :rowSelection #js {}
                     :loading log-loading?
                     :columns (clj->js
                                [{:title "日志ID" :dataIndex "job_log_id" :width 80}
                                 {:title "任务名称" :dataIndex "job_name"}
                                 {:title "任务组" :dataIndex "job_group"}
                                 {:title "调用目标" :dataIndex "invoke_target"}
                                 {:title "执行信息" :dataIndex "job_message"}
                                 {:title "状态" :dataIndex "status" :width 80
                                  :render (fn [v] (r/as-element [antd/tag {:color (if (= v "0") "green" "red")} (if (= v "0") "成功" "失败")]))}
                                 {:title "执行时间" :dataIndex "create_time"}])
                     :dataSource (clj->js log-items)
                     :pagination {:pageSize 10 :total log-total}}]]])))
