(ns com.ruoyi.frontend.pages.workflow.definitions
  "流程定义列表页面。"
  (:require [reagent.core :as r]
            [reagent.hooks :as hooks]
            [re-frame.core :as rf]
            ["@ant-design/icons" :refer [PlusOutlined DeleteOutlined StopOutlined CaretRightOutlined]]
            [com.ruoyi.frontend.antd :as antd]))

(defn- columns [on-delete on-suspend on-activate]
  #js [#js {:title "流程名称" :dataIndex "name" :key "name" :width 200}
       #js {:title "流程Key" :dataIndex "key" :key "key" :width 150}
       #js {:title "版本" :dataIndex "version" :key "version" :width 80
            :render (fn [v] (r/as-element [:span (str "v" v)]))}
       #js {:title "状态" :dataIndex "suspended" :key "status" :width 100
            :render (fn [v]
                      (r/as-element
                       [antd/tag {:color (if v "red" "green")}
                        (if v "已挂起" "已激活")]))}
       #js {:title "操作" :key "action" :width 200
            :render (fn [_ _ row]
                      (let [r (js->clj row :keywordize-keys true)]
                        (r/as-element
                         [antd/space {:size 4}
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> StopOutlined])
                                        :disabled (:suspended r)
                                        :onClick #(on-suspend (:id r))}
           "挂起"]
                          [antd/button {:type "link" :size "small"
                                        :icon (r/as-element [:> CaretRightOutlined])
                                        :disabled (not (:suspended r))
                                        :onClick #(on-activate (:id r))}
           "激活"]
                          [antd/button {:type "link" :size "small" :danger true
                                        :icon (r/as-element [:> DeleteOutlined])
                                        :onClick #(on-delete (:id r))}
           "删除"]])))}])

(defn definitions-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:workflow/fetch-definitions])
     js/undefined)
   [])
  (let [data @(rf/subscribe [:workflow/definitions])
        loading? @(rf/subscribe [:workflow/loading?])]
    [:div
     [antd/card {:title "流程定义" :size "small"
                 :extra (r/as-element
                         [antd/button {:type "primary" :size "small"
                                       :icon (r/as-element [:> PlusOutlined])
                                       :onClick #(rf/dispatch [:navigate :workflow-designer])}
                          "设计新流程"])}
      [antd/table {:dataSource (clj->js data)
                   :columns (columns
                             #(rf/dispatch [:workflow/delete-deployment %])
                             #(rf/dispatch [:workflow/suspend-definition %])
                             #(rf/dispatch [:workflow/activate-definition %]))
                   :rowKey "id"
                   :loading loading?
                   :pagination false
                   :size "small"}]]]))
