(ns com.ruoyi.rouyi.frontend.pages.job
  "定时任务管理页面。"
  (:require
    [reagent.core :as r]
    [reagent.hooks :as hooks]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- status-tag [status]
  [antd/tag {:color (if (= status "0") "green" "red")}
   (if (= status "0") "正常" "暂停")])

(defn- job-columns [show-log-fn show-form-fn]
  #js [#js {:title "任务ID" :dataIndex "job_id" :key "job_id" :width 80}
       #js {:title "任务名称" :dataIndex "job_name" :key "job_name"}
       #js {:title "任务组" :dataIndex "job_group" :key "job_group"}
       #js {:title "调用目标" :dataIndex "invoke_target" :key "invoke_target"}
       #js {:title "Cron表达式" :dataIndex "cron_expression" :key "cron_expression"}
       #js {:title "状态" :dataIndex "status" :key "status"
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "操作" :key "action" :width 280
            :render (fn [_ record]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"
                                       :onClick #(show-form-fn record)} "编辑"]
                         [antd/button {:type "link" :size "small"
                                       :onClick #(rf/dispatch [:jobs/delete (:job_id record)])} "删除"]
                         [antd/button {:type "link" :size "small" :disabled (= (:status record) "0")
                                       :onClick #(rf/dispatch [:jobs/update (:job_id record) {:status "0"}])} "恢复"]
                         [antd/button {:type "link" :size "small" :disabled (= (:status record) "1")
                                       :onClick #(rf/dispatch [:jobs/update (:job_id record) {:status "1"}])} "暂停"]
                         [antd/button {:type "link" :size "small"
                                       :onClick #(show-log-fn (:job_name record))} "日志"]]))}])

(defn- job-log-columns []
  #js [#js {:title "日志ID" :dataIndex "job_log_id" :key "job_log_id" :width 80}
       #js {:title "任务名称" :dataIndex "job_name" :key "job_name"}
       #js {:title "任务组" :dataIndex "job_group" :key "job_group"}
       #js {:title "调用目标" :dataIndex "invoke_target" :key "invoke_target"}
       #js {:title "执行信息" :dataIndex "job_message" :key "job_message"}
       #js {:title "状态" :dataIndex "status" :key "status"
            :render (fn [v]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "成功" "失败")]))}
       #js {:title "执行时间" :dataIndex "create_time" :key "create_time"}])

(defn- job-form-modal [visible? record on-close]
  (let [form-data (r/atom (or record
                              {:job_name "" :job_group "DEFAULT"
                               :invoke_target "" :cron_expression ""
                               :misfire_policy "3" :concurrent "1"
                               :status "0" :remark ""}))]
    (fn [visible? record on-close]
      (let [editing? (some? (:job_id record))]
        [antd/modal {:title (if editing? "编辑任务" "新增任务")
                     :open visible?
                     :onOk (fn []
                             (if editing?
                               (rf/dispatch [:jobs/update (:job_id record) @form-data])
                               (rf/dispatch [:jobs/create @form-data]))
                             (on-close))
                     :onCancel on-close
                     :destroyOnClose true}
         [:div
          [:div {:style {:marginBottom 12}}
           [:label "任务名称"]
           [antd/input {:value (:job_name @form-data)
                        :onChange #(swap! form-data assoc :job_name (-> % .-target .-value))}]]
          [:div {:style {:marginBottom 12}}
           [:label "任务组"]
           [antd/input {:value (:job_group @form-data)
                        :onChange #(swap! form-data assoc :job_group (-> % .-target .-value))}]]
          [:div {:style {:marginBottom 12}}
           [:label "调用目标"]
           [antd/input {:value (:invoke_target @form-data)
                        :onChange #(swap! form-data assoc :invoke_target (-> % .-target .-value))}]]
          [:div {:style {:marginBottom 12}}
           [:label "Cron表达式"]
           [antd/input {:value (:cron_expression @form-data)
                        :onChange #(swap! form-data assoc :cron_expression (-> % .-target .-value))}]]
          [:div {:style {:marginBottom 12}}
           [:label "备注"]
           [antd/input {:value (:remark @form-data)
                        :onChange #(swap! form-data assoc :remark (-> % .-target .-value))}]]]]))))

(defn job-page []
  (let [show-form? (r/atom false)
        editing-record (r/atom nil)
        show-log? (r/atom false)
        log-job-name (r/atom nil)]
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
          log-loading? @(rf/subscribe [:job-logs/loading?])]

      [:div

       ;; Job log drawer
       [antd/drawer {:title (str "任务日志 - " @log-job-name)
                     :open @show-log?
                     :onClose #(reset! show-log? false)
                     :width 800
                     :destroyOnClose true}
        [antd/table {:rowKey "job_log_id"
                     :loading log-loading?
                     :columns (job-log-columns)
                     :dataSource (clj->js log-items)
                     :pagination {:pageSize 10 :total log-total}}]]

       ;; Main job table
       [antd/space {:style {:marginBottom 16}}
        [antd/button {:type "primary"
                      :onClick #(do (reset! editing-record nil)
                                    (reset! show-form? true))} "新增任务"]]

       [antd/table {:rowKey "job_id"
                    :loading loading?
                    :columns (job-columns
                               (fn [job-name]
                                 (reset! log-job-name job-name)
                                 (rf/dispatch [:job-logs/fetch {:job_name job-name}])
                                 (reset! show-log? true))
                               (fn [record]
                                 (reset! editing-record record)
                                 (reset! show-form? true)))
                    :dataSource (clj->js items)
                    :pagination {:pageSize 10 :total total}}]

       ;; Job form modal
       [job-form-modal @show-form? @editing-record #(reset! show-form? false)]])))
