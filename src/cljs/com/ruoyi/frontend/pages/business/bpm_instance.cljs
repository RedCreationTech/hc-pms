(ns com.ruoyi.frontend.pages.business.bpm-instance
  "我的流程 —— 流程详情（基本信息/表单回显/审批历史/流程图高亮）。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined EyeOutlined]]
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
                        ["未知" "default"])]
    [antd/tag {:color color} label]))

(defn- instance-columns [open-detail]
  #js [#js {:title "模型" :dataIndex "model_name" :key "model_name" :width 140}
       #js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id"
            :width 90 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "业务键" :dataIndex "business_key" :key "business_key" :width 130}
       #js {:title "发起人" :dataIndex "starter_id" :key "starter_id" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "发起时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 100
            :render (fn [_ ^js record]
                      (let [instance (js->clj record :keywordize-keys true)
                            pid (:process_instance_id instance)]
                        (r/as-element
                         [antd/button {:type "link" :size "small"
                                       :icon (r/as-element [:> EyeOutlined])
                                       :on-click #(open-detail pid)}
                          "详情"])))}])

(defn- detail-drawer [{:keys [pid data loading? diagram]}]
  (let [data-val (or @data {})
        instance (or (:instance data-val) {})
        form (:form data-val)
        schema (or (:schema form) {:fields []})
        values (or (:values form) {})
        task-history (or (:task-history data-val) [])
        model (or (:model data-val) {})
        base (:instance data-val)
        form-block (fn []
                     (if (and (seq (:fields schema)) instance)
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
         [antd/descriptions-item {:label "发起人"} (:starter_id base)]
         [antd/descriptions-item {:label "状态"} (status-tag (:status base))]
         [antd/descriptions-item {:label "流程实例"} (:process_instance_id base)]
         [antd/descriptions-item {:label "业务键"} (:business_key base)]
         [antd/descriptions-item {:label "发起时间"} (:create_time base)]]
        [form-block]
        [history-block]
        [diagram-block]])]))
(defn bpm-instance-page []
  (r/with-let [items (r/atom [])
               total (r/atom 0)
               loading? (r/atom true)
               detail-pid (r/atom nil)
               detail-loading? (r/atom false)
               detail-data (r/atom nil)
               detail-diagram (r/atom nil)
               refresh (fn []
                         (reset! loading? true)
                         (api/bpm-list-instances {:page 1 :size 10}
                                                 (fn [res]
                                                   (reset! items (or (:rows (:data res)) []))
                                                   (reset! total (:total (:data res) 0))
                                                   (reset! loading? false))
                                                 (fn [_] (reset! loading? false) (antd/error! "加载失败"))))
               _ (refresh)
               open-detail (fn [pid]
                             (reset! detail-pid pid)
                             (reset! detail-loading? true)
                             (reset! detail-data nil)
                             (reset! detail-diagram nil)
                             (api/bpm-task-history pid
                                                   (fn [res]
                                                     (reset! detail-data (:data res))
                                                     (reset! detail-loading? false))
                                                   (fn [e] (reset! detail-loading? false) (antd/error! (str "加载详情失败: " e))))
                             (api/bpm-instance-diagram pid
                                                       (fn [res]
                                                         (reset! detail-diagram (:data res)))
                                                       (fn [_] nil)))]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [:div {:style {:fontSize 15 :fontWeight 600}} "我的流程"]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click refresh}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "instance_id"
                  :columns (instance-columns open-detail)
                  :dataSource (clj->js @items)
                  :loading @loading?
                  :pagination {:total @total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))}}]
     [detail-drawer {:pid detail-pid :data detail-data :loading? detail-loading? :diagram detail-diagram}]]))
