(ns com.ruoyi.frontend.pages.business.bpm-copy
  "抄送我的 —— 抄送记录列表（查询分页 + 详情跳转）。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined EyeOutlined]]
   [clojure.string]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.form-render :as form-render]
   [com.ruoyi.frontend.components.bpm-flow-designer :as bpm-flow-designer]))

(defn- status-tag [v]
  (let [[label color] (case v
                        "1" ["审批中" "processing"]
                        "2" ["已结束" "success"]
                        "3" ["已驳回" "error"]
                        "CANCELED" ["已取消" "warning"]
                        ["未知" "default"])]
    [antd/tag {:color color} label]))

(defn- summary-text [summary]
  (if (seq summary)
    (clojure.string/join "　" (map #(str (:label %) "：" (:value %)) summary))
    "-"))

(defn- copy-columns [open-detail]
  #js [#js {:title "流程" :dataIndex "model_name" :key "model_name" :width 120}
       #js {:title "流程名称" :dataIndex "instance_name" :key "instance_name" :width 160 :ellipsis true
            :render (fn [v] (r/as-element [:span (if (seq v) v "-")]))}
       #js {:title "单号" :dataIndex "bill_code" :key "bill_code" :width 130
            :render (fn [v] (r/as-element (if v [antd/tag {:color "geekblue"} v] "-")))}
       #js {:title "摘要" :dataIndex "summary" :key "summary" :width 160 :ellipsis true
            :render (fn [v]
                      (let [s (summary-text (js->clj v :keywordize-keys true))]
                        (r/as-element [:span {:style {:color (if (= "-" s) "#c0c4cc" "#606266")}} s])))}
       #js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id"
            :width 90 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "抄送节点" :dataIndex "activity_name" :key "activity_name" :width 110}
       #js {:title "发起人" :dataIndex "starter_id" :key "starter_id" :width 100}
       #js {:title "状态" :dataIndex "instance_status" :key "instance_status" :width 90
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "抄送说明" :dataIndex "reason" :key "reason" :width 140
            :render (fn [v] (r/as-element (if (seq v) v "-")))}
       #js {:title "抄送时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 100
            :render (fn [_ ^js record]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/button {:type "link" :size "small"
                                       :icon (r/as-element [:> EyeOutlined])
                                       :on-click #(open-detail (:process_instance_id row))}
                          "详情"])))}])

(defn- detail-drawer [{:keys [pid data loading? diagram]}]
  (let [data-val (or @data {})
        instance (or (:instance data-val) {})
        form (:form data-val)
        schema (or (:schema form) {:fields []})
        values (or (:values form) {})
        task-history (or (:task-history data-val) [])
        model (or (:model data-val) {})
        form-block (fn []
                     (if (seq (:fields schema))
                       [:div {:style {:marginBottom 16}}
                        [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                         [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
                         [:span {:style {:fontWeight 600}} "表单数据"]]
                        [form-render/form-render {:schema schema :values values :disabled? true}]]
                       nil))
        history-block (fn []
                        [:div {:style {:marginBottom 16}}
                         [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                          [:div {:style {:width 4 :height 16 :background "#67c23a" :marginRight 8}}]
                          [:span {:style {:fontWeight 600}} "审批历史"]]
                         (if (seq task-history)
                           [:div
                            (doall
                             (for [t task-history]
                               ^{:key (:task-id t)}
                               [:div {:style {:padding "8px 12px" :borderLeft "3px solid"
                                              :borderColor (if (true? (:approved t)) "#67c23a"
                                                               (if (false? (:approved t)) "#f56c6c" "#409eff"))
                                              :background "#fafafa" :marginBottom 8 :borderRadius "0 6px 6px 0"}}
                                [:div {:style {:display "flex" :alignItems "center"}}
                                 [:b (:name t)]
                                 (when (:assignee t)
                                   [:span {:style {:color "#909399" :marginLeft 8}} (:assignee t)])
                                 (when (:end-time t)
                                   [:span {:style {:color "#c0c4cc" :marginLeft 8 :fontSize 12}} (subs (:end-time t) 0 16)])]
                                (when (:comment t)
                                  [:div {:style {:color "#606266" :marginTop 2}} (str "意见：" (:comment t))])]))]
                           [:div {:style {:color "#c0c4cc"}} "暂无审批记录"])])
        diagram-block (fn []
                        [:div
                         [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                          [:div {:style {:width 4 :height 16 :background "#e6a23c" :marginRight 8}}]
                          [:span {:style {:fontWeight 600}} "流程图"]]
                         (if-let [mid (:model_id model)]
                           [bpm-flow-designer/bpm-flow-designer
                            {:model-id mid :read-only? true
                             :active-ids (vec (:active-activity-ids @diagram))
                             :completed-ids (vec (:completed-activity-ids @diagram))}]
                           [:div {:style {:color "#c0c4cc"}} "暂无流程图"])])]
    [antd/drawer {:title (str "流程详情 · " (:model_name model))
                  :open (boolean @pid) :size 900
                  :onClose #(reset! pid nil)}
     (if @loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div
        [antd/descriptions {:column 3 :size "small" :bordered true :style {:marginBottom 16}}
         [antd/descriptions-item {:label "流程模型"} (:model_name model)]
         [antd/descriptions-item {:label "发起人"} (:starter_id instance)]
         [antd/descriptions-item {:label "状态"} (status-tag (:status instance))]
         [antd/descriptions-item {:label "流程实例"} (:process_instance_id instance)]
         [antd/descriptions-item {:label "发起时间"} (:create_time instance)]]
        [form-block]
        [history-block]
        [diagram-block]])]))

(defn bpm-copy-page []
  (let [items @(rf/subscribe [:bpm-copy/items])
        total @(rf/subscribe [:bpm-copy/total])
        loading? @(rf/subscribe [:bpm-copy/loading?])]
    (r/with-let [detail-pid (r/atom nil)
                 detail-loading? (r/atom false)
                 detail-data (r/atom nil)
                 detail-diagram (r/atom nil)
                 open-detail (fn [pid]
                               (reset! detail-pid pid)
                               (reset! detail-loading? true)
                               (reset! detail-data nil)
                               (reset! detail-diagram nil)
                               (api/bpm-task-history pid
                                                     (fn [res]
                                                       (reset! detail-data (:data res))
                                                       (reset! detail-loading? false))
                                                     (fn [_] (reset! detail-loading? false)))
                               (api/bpm-instance-diagram pid
                                                         (fn [res] (reset! detail-diagram (:data res)))
                                                         (fn [_] nil)))]
      [:div
       [page-toolbar/page-toolbar
        {:left [page-toolbar/toolbar-left
                [:div {:style {:fontSize 15 :fontWeight 600}} "抄送我的"]]
         :right [page-toolbar/toolbar-right
                 [page-toolbar/round-tool-button {:title "刷新"
                                                  :icon (r/as-element [:> ReloadOutlined])
                                                  :on-click #(rf/dispatch [:bpm/copy-fetch {}])}]]}]
       [antd/table {:scroll #js {:x "max-content"} :rowKey "copy_id"
                    :columns (copy-columns open-detail)
                    :dataSource (clj->js items)
                    :loading loading?
                    :pagination {:total total :pageSize 10 :showSizeChanger true
                                 :showTotal (fn [total] (str "共 " total " 条"))
                                 :onChange (fn [page size]
                                             (rf/dispatch [:bpm/copy-fetch {:page page :size size}]))}}]
       [detail-drawer {:pid detail-pid :data detail-data :loading? detail-loading?
                       :diagram detail-diagram}]])))
