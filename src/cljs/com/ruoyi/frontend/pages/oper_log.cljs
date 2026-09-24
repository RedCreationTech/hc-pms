(ns com.ruoyi.frontend.pages.oper-log
  "操作日志页面."
  (:require
    ["@ant-design/icons" :refer [DeleteOutlined DownloadOutlined EyeOutlined ReloadOutlined SearchOutlined]]
    ["antd" :refer [DatePicker]]
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-search :as page-search]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def range-picker (r/adapt-react-class (.-RangePicker DatePicker)))


(def business-types
  {"0" "其他"
   "1" "新增"
   "2" "修改"
   "3" "删除"
   "4" "授权"
   "5" "导出"
   "6" "导入"
   "7" "强退"
   "9" "清空"})


(defn- business-label
  [v]
  (get business-types (str v) "其他"))


(defn- business-tag
  [v]
  (let [text (business-label v)
        color (case (str v)
                "1" "blue"
                "2" "default"
                "3" "red"
                "5" "gold"
                "6" "gold"
                "default")]
    [antd/tag {:color color} text]))


(defn- status-tag
  [status]
  [antd/tag {:color (if (= (str status) "0") "blue" "red")}
   (if (= (str status) "0") "成功" "失败")])


(defn- detail-modal
  []
  (let [visible? @(rf/subscribe [:oper-logs/detail-visible?])
        data @(rf/subscribe [:oper-logs/detail-data])]
    [antd/modal {:title "操作日志详情"
                 :open visible?
                 :footer nil
                 :style {:width 760}
                 :onCancel #(rf/dispatch [:oper-logs/hide-detail])
                 :destroyOnHidden true}
     (when data
       [antd/descriptions {:column 2 :bordered true :size "small"}
        [antd/descriptions-item {:label "操作模块"} (:title data)]
        [antd/descriptions-item {:label "操作类型"} [business-tag (:business_type data)]]
        [antd/descriptions-item {:label "操作人员"} (:oper_name data)]
        [antd/descriptions-item {:label "主机地址"} (:oper_ip data)]
        [antd/descriptions-item {:label "操作地点"} (:oper_location data)]
        [antd/descriptions-item {:label "请求方式"} (:request_method data)]
        [antd/descriptions-item {:label "操作方法" :span 2} (:method data)]
        [antd/descriptions-item {:label "请求URL" :span 2} (:oper_url data)]
        [antd/descriptions-item {:label "操作状态"} [status-tag (:status data)]]
        [antd/descriptions-item {:label "操作时间"} (:oper_time data)]
        [antd/descriptions-item {:label "消耗时间"} (str (or (:cost_time data) 0) "毫秒")]
        [antd/descriptions-item {:label "部门名称"} (or (:dept_name data) "-")]
        [antd/descriptions-item {:label "请求参数" :span 2}
         [:pre {:style {:maxHeight 160 :overflow "auto" :fontSize 12 :background "#f5f7fa" :padding 8 :borderRadius 4}}
          (or (:oper_param data) "-")]]
        [antd/descriptions-item {:label "返回参数" :span 2}
         [:pre {:style {:maxHeight 160 :overflow "auto" :fontSize 12 :background "#f5f7fa" :padding 8 :borderRadius 4}}
          (or (:json_result data) "-")]]
        (when (seq (:error_msg data))
          [antd/descriptions-item {:label "错误消息" :span 2}
           [:pre {:style {:color "#f56c6c" :maxHeight 160 :overflow "auto" :fontSize 12 :background "#fef0f0" :padding 8 :borderRadius 4}}
            (:error_msg data)]])])]))


(defn- oper-log-columns
  []
  #js [#js {:title "日志编号" :dataIndex "oper_id" :key "oper_id" :width 100}
       #js {:title "系统模块" :dataIndex "title" :key "title" :width 130}
       #js {:title "操作类型" :dataIndex "business_type" :key "business_type" :width 120
            :render (fn [v _] (r/as-element [business-tag v]))}
       #js {:title "操作人员" :dataIndex "oper_name" :key "oper_name" :width 120
            :sorter true}
       #js {:title "操作地址" :dataIndex "oper_ip" :key "oper_ip" :width 160}
       #js {:title "操作地点" :dataIndex "oper_location" :key "oper_location" :width 160}
       #js {:title "操作状态" :dataIndex "status" :key "status" :width 110
            :render (fn [v _] (r/as-element [status-tag v]))}
       #js {:title "操作日期" :dataIndex "oper_time" :key "oper_time" :width 180
            :sorter true}
       #js {:title "消耗时间" :dataIndex "cost_time" :key "cost_time" :width 120
            :sorter true
            :render (fn [v _] (str (or v 0) "毫秒"))}
       #js {:title "操作" :key "action" :width 100 :fixed "right"
            :render (fn [_ record]
                      (r/as-element
                        [antd/button {:type "link"
                                      :size "small"
                                      :icon (r/as-element [:> EyeOutlined])
                                      :onClick #(rf/dispatch [:oper-logs/show-detail (js->clj record :keywordize-keys true)])}
                         "详细"]))}])


(defn- query-params
  [oper-ip title oper-name business-type status date-range]
  (cond-> {:oper_ip oper-ip
           :title title
           :oper_name oper-name
           :business_type business-type
           :status status}
    (first date-range) (assoc :begin_time (first date-range))
    (second date-range) (assoc :end_time (second date-range))))


(defn oper-log-page
  []
  (hooks/use-effect (fn [] (rf/dispatch [:oper-logs/fetch {}]) js/undefined) [])
  (let [items @(rf/subscribe [:oper-logs/items])
        total @(rf/subscribe [:oper-logs/total])
        loading? @(rf/subscribe [:oper-logs/loading?])
        [oper-ip set-oper-ip!] (hooks/use-state "")
        [title set-title!] (hooks/use-state "")
        [oper-name set-oper-name!] (hooks/use-state "")
        [business-type set-business-type!] (hooks/use-state nil)
        [status set-status!] (hooks/use-state nil)
        [date-range set-date-range!] (hooks/use-state [])
        [selected-ids set-selected-ids!] (hooks/use-state [])
        run-search #(rf/dispatch [:oper-logs/fetch (query-params oper-ip title oper-name business-type status date-range)])
        selected-id-string #(str/join "," selected-ids)]
    [:div
     [page-search/page-search {:visible? true}
      [page-search/search-row
       [page-search/search-item
        "操作地址"
        [antd/input {:placeholder "请输入操作地址"
                     :style page-search/input-style
                     :value oper-ip
                     :onChange #(set-oper-ip! (-> % .-target .-value))}]]
       [page-search/search-item
        "系统模块"
        [antd/input {:placeholder "请输入系统模块"
                     :style page-search/input-style
                     :value title
                     :onChange #(set-title! (-> % .-target .-value))}]]
       [page-search/search-item
        "操作人员"
        [antd/input {:placeholder "请输入操作人员"
                     :style page-search/input-style
                     :value oper-name
                     :onChange #(set-oper-name! (-> % .-target .-value))}]]
       [page-search/search-item
        "类型"
        [antd/select {:placeholder "操作类型"
                      :style page-search/select-style
                      :allowClear true
                      :value business-type
                      :onChange #(set-business-type! %)}
         (for [[value label] business-types]
           ^{:key value}
           [antd/select-option {:value value} label])]]
       [page-search/search-item
        "状态"
        [antd/select {:placeholder "操作状态"
                      :style page-search/select-style
                      :allowClear true
                      :value status
                      :onChange #(set-status! %)}
         [antd/select-option {:value "0"} "成功"]
         [antd/select-option {:value "1"} "失败"]]]
       [page-search/search-item
        "操作时间"
        [range-picker {:placeholder #js ["开始日期" "结束日期"]
                       :style {:width 260 :height 34}
                       :onChange (fn [_ date-strings]
                                   (set-date-range! (js->clj date-strings)))}]]
       [page-search/search-actions
        [page-toolbar/search-button {:icon (r/as-element [:> SearchOutlined])
                                     :on-click run-search}]
        [page-toolbar/reset-button {:icon (r/as-element [:> ReloadOutlined])
                                    :on-click #(do (set-oper-ip! "")
                                                   (set-title! "")
                                                   (set-oper-name! "")
                                                   (set-business-type! nil)
                                                   (set-status! nil)
                                                   (set-date-range! [])
                                                   (rf/dispatch [:oper-logs/fetch {}]))}]]]]
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [antd/popconfirm {:title "确认删除选中的操作日志？"
                                :onConfirm #(when (seq selected-ids)
                                              (rf/dispatch [:oper-logs/delete (selected-id-string)])
                                              (set-selected-ids! []))}
               [page-toolbar/toolbar-button {:perms "monitor:operlog:remove" :kind :delete
                                             :icon (r/as-element [:> DeleteOutlined])
                                             :disabled? (empty? selected-ids)
                                             :label "删除"}]]
              [antd/popconfirm {:title "确认清空所有操作日志？"
                                :onConfirm #(rf/dispatch [:oper-logs/clear])}
               [page-toolbar/toolbar-button {:perms "monitor:operlog:remove" :kind :delete
                                             :icon (r/as-element [:> DeleteOutlined])
                                             :label "清空"}]]
              [page-toolbar/toolbar-button {:perms "monitor:operlog:export" :kind :export
                                            :icon (r/as-element [:> DownloadOutlined])
                                            :on-click #(rf/dispatch [:oper-logs/export])
                                            :label "导出"}]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "搜索"
                                                :icon (r/as-element [:> SearchOutlined])
                                                :on-click run-search}]
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:oper-logs/fetch {}])}]]}]
     [antd/table {:scroll #js {:x "max-content"}
                  :rowKey "oper_id"
                  :loading loading?
                  :columns (oper-log-columns)
                  :rowSelection #js {:selectedRowKeys (clj->js selected-ids)
                                     :onChange (fn [keys _]
                                                 (set-selected-ids! (js->clj keys)))}
                  :dataSource (clj->js items)
                  :pagination {:pageSize 10
                               :total total
                               :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))}}]
     [detail-modal]]))
