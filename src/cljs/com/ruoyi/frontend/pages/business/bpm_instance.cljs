(ns com.ruoyi.frontend.pages.business.bpm-instance
  "我的流程 —— 流程详情（基本信息/表单回显/审批历史/流程图高亮/打印）。"
  (:require
    ["@ant-design/icons" :refer [ReloadOutlined EyeOutlined RollbackOutlined StopOutlined PrinterOutlined]]
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.bpm-flow-designer :as bpm-flow-designer]
    [com.ruoyi.frontend.components.form-render :as form-render]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn- summary-text
  [summary]
  (if (seq summary)
    (str/join "　" (map #(str (:label %) "：" (:value %)) summary))
    "-"))


(defn- do-print!
  "按模型打印模板(占位符 {{字段}},{{流程记录}})或默认样式拼 HTML,新窗口打开并调用打印."
  [data]
  (let [inst (:instance data)
        model (:model data)
        values (or (:values (:form data)) {})
        fields (or (get-in data [:form :schema :fields]) [])
        esc #(str (or % ""))
        record-html (str/join "<br/>"
                              (map (fn [t]
                                     (str (:name t) " · " (:assignee t)
                                          (when (:comment t) (str " · 意见：" (:comment t)))
                                          (when (:sign-pic-url t) (str " · <img src='" (:sign-pic-url t) "' style='height:32px'/>"))
                                          (when (:end-time t) (str " · " (subs (:end-time t) 0 19)))))
                                   (:task-history data)))
        default-html (str "<h2 style='text-align:center'>" (esc (:model_name model)) "</h2>"
                          "<p>单号：" (esc (:bill_code inst)) "　发起人：" (esc (:starter_id inst))
                          "　发起时间：" (esc (:create_time inst)) "</p>"
                          "<table border='1' cellspacing='0' cellpadding='6' style='border-collapse:collapse;width:100%'>"
                          (str/join (for [f fields]
                                      (str "<tr><th style='text-align:left;background:#f5f5f5;width:30%'>"
                                           (esc (:title f)) "</th><td>"
                                           (esc (get values (keyword (:field f)))) "</td></tr>")))
                          "</table>"
                          "<h3>审批记录</h3><div style='line-height:1.8'>" record-html "</div>")
        html (if (and (= "1" (str (:print_template_enable model)))
                      (seq (:print_template_html model)))
               (-> (:print_template_html model)
                   (str/replace #"\{\{([\w-]+)\}\}"
                                (fn [[_ k]] (esc (get values (keyword k) (get values k "")))))
                   (str/replace "{{流程记录}}" record-html))
               default-html)]
    (when-let [w (js/window.open "" "_blank")]
      (doto (.-document w)
        (.write (str "<!DOCTYPE html><html><head><meta charset='utf-8'><title>打印 - "
                     (esc (:name inst)) "</title></head><body style='font-family:sans-serif;padding:24px'>"
                     html
                     "<script>window.onload=function(){window.print()}</script></body></html>"))
        (.close)))))


(defn- status-tag
  [v]
  (let [[label color] (case v
                        "1" ["审批中" "processing"]
                        "2" ["已结束" "success"]
                        "3" ["已驳回" "error"]
                        "CANCELED" ["已取消" "warning"]
                        ["未知" "default"])]
    [antd/tag {:color color} label]))


(defn- cancel-modal
  [{:keys [pid visible? on-close on-ok]}]
  (let [[reason set-reason!] (hooks/use-state "")]
    [antd/modal {:title "取消流程" :open visible? :width 420
                 :onOk (fn [] (on-ok pid reason))
                 :onCancel on-close}
     [:div {:style {:marginBottom 8}} "确认取消该流程实例？取消后流程终止，不可恢复。"]
     [antd/text-area {:value reason :rows 3 :placeholder "取消原因(可选)"
                      :onChange #(set-reason! (.. % -target -value))}]]))


(defn- instance-columns
  [open-detail on-withdraw-to-start on-cancel]
  #js [#js {:title "模型" :dataIndex "model_name" :key "model_name" :width 120}
       #js {:title "流程名称" :dataIndex "name" :key "name" :width 180 :ellipsis true
            :render (fn [v] (r/as-element [:span (if (seq v) v "-")]))}
       #js {:title "单号" :dataIndex "bill_code" :key "bill_code" :width 140
            :render (fn [v] (r/as-element (if v [antd/tag {:color "geekblue"} v] "-")))}
       #js {:title "摘要" :dataIndex "summary" :key "summary" :width 180 :ellipsis true
            :render (fn [v]
                      (let [s (summary-text (js->clj v :keywordize-keys true))]
                        (r/as-element [:span {:style {:color (if (= "-" s) "#c0c4cc" "#606266")}} s])))}
       #js {:title "流程实例" :dataIndex "process_instance_id" :key "process_instance_id"
            :width 90 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "业务键" :dataIndex "business_key" :key "business_key" :width 130}
       #js {:title "发起人" :dataIndex "starter_id" :key "starter_id" :width 100}
       #js {:title "状态" :dataIndex "status" :key "status" :width 100
            :render (fn [v] (r/as-element (status-tag v)))}
       #js {:title "发起时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 220
            :render (fn [_ ^js record]
                      (let [instance (js->clj record :keywordize-keys true)
                            pid (:process_instance_id instance)
                            running? (= "1" (:status instance))
                            allow-cancel? (= "1" (str (:allow_cancel instance)))
                            allow-withdraw? (= "1" (str (:allow_withdraw instance)))]
                        (r/as-element
                          [antd/space
                           [antd/button {:type "link" :size "small"
                                         :icon (r/as-element [:> EyeOutlined])
                                         :on-click #(open-detail pid)}
                            "详情"]
                           (when (and running? allow-withdraw?)
                             [antd/popconfirm {:title "确认撤回到起始节点重新编辑?"
                                               :on-confirm #(on-withdraw-to-start pid)}
                              [antd/button {:type "link" :size "small"
                                            :icon (r/as-element [:> RollbackOutlined])}
                               "撤回"]])
                           (when (and running? allow-cancel?)
                             [antd/button {:type "link" :size "small" :danger true
                                           :icon (r/as-element [:> StopOutlined])
                                           :on-click #(on-cancel pid)}
                              "取消"])])))}])


(defn- detail-drawer
  [{:keys [pid data loading? diagram on-cancel]}]
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
                  :extra (r/as-element
                           [antd/space
                            (when (and (:running? data-val) (= "1" (str (:allow_cancel model))))
                              [antd/button {:size "small" :danger true
                                            :icon (r/as-element [:> StopOutlined])
                                            :on-click #(on-cancel pid)}
                               "取消"])
                            [antd/button {:size "small"
                                          :icon (r/as-element [:> PrinterOutlined])
                                          :on-click (fn []
                                                      (let [iid (:instance_id (:instance data-val))]
                                                        (api/bpm-print-data
                                                          iid
                                                          (fn [res]
                                                            (if (= 200 (:code res))
                                                              (do-print! (:data res))
                                                              (antd/error! (str "加载打印数据失败: " (:msg res)))))
                                                          (fn [_] (antd/error! "加载打印数据失败")))))}
                             "打印"]])
                  :onClose #(reset! pid nil)}
     (if @loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div
        [antd/descriptions {:column 3 :size "small" :bordered true :style {:marginBottom 16}}
         [antd/descriptions-item {:label "流程模型"} (:model_name model)]
         [antd/descriptions-item {:label "流程名称"} (:name base)]
         [antd/descriptions-item {:label "单号"} (:bill_code base)]
         [antd/descriptions-item {:label "发起人"} (:starter_id base)]
         [antd/descriptions-item {:label "状态"} (status-tag (:status base))]
         [antd/descriptions-item {:label "流程实例"} (:process_instance_id base)]
         [antd/descriptions-item {:label "业务键"} (:business_key base)]
         [antd/descriptions-item {:label "发起时间"} (:create_time base)]]
        [form-block]
        [history-block]
        [diagram-block]])]))


(defn bpm-instance-page
  []
  (r/with-let [items (r/atom [])
               total (r/atom 0)
               loading? (r/atom true)
               cancel-pid (r/atom nil)
               detail-pid (r/atom nil)
               detail-loading? (r/atom false)
               detail-data (r/atom nil)
               detail-diagram (r/atom nil)
               page (r/atom 1)
               page-size (r/atom 10)
               refresh (fn []
                         (reset! loading? true)
                         (api/bpm-list-instances {:page @page :size @page-size}
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
                            :columns (instance-columns open-detail
                                                       (fn [pid] (api/bpm-withdraw-to-start pid refresh (fn [_] nil)))
                                                       (fn [pid] (reset! cancel-pid pid)))
                            :dataSource (clj->js @items)
                            :loading @loading?
                            :pagination {:total @total :pageSize @page-size :showSizeChanger true
                                         :current @page
                                         :onChange (fn [p s]
                                                     (reset! page p)
                                                     (reset! page-size s)
                                                     (refresh))
                                         :showTotal (fn [total] (str "共 " total " 条"))}}]
               [cancel-modal {:pid @cancel-pid
                              :visible? (some? @cancel-pid)
                              :on-close #(reset! cancel-pid nil)
                              :on-ok (fn [pid reason]
                                       (api/bpm-cancel-instance {:id pid :reason reason}
                                                                (fn [res]
                                                                  (if (= 200 (:code res))
                                                                    (do (antd/success! "已取消") (reset! cancel-pid nil) (refresh))
                                                                    (antd/error! (str "取消失败: " (:msg res)))))
                                                                (fn [_] (antd/error! "取消失败"))))}]
               [detail-drawer {:pid detail-pid :data detail-data :loading? detail-loading? :diagram detail-diagram
                               :on-cancel (fn [pid] (reset! cancel-pid pid))}]]))
