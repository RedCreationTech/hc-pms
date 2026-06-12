(ns com.ruoyi.rouyi.frontend.pages.gen
  "代码生成器页面 — 数据库表选择、代码预览与生成。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined CodeOutlined EyeOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

;; ─── 工具函数 ──────────────────────────────────────────────────────

(def file-type-labels
  {"backend-sql" "SQL"
   "backend-domain" "Domain"
   "backend-controller" "Controller"
   "backend-routes" "Routes"
   "frontend-api" "API"
   "frontend-page" "页面"
   "frontend-events" "事件"
   "frontend-subs" "订阅"})

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn gen-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:gen/fetch-tables])
     js/undefined)
   [])
  (let [tables @(rf/subscribe [:gen/tables])
        tables-loading? @(rf/subscribe [:gen/tables-loading?])
        selected-tables @(rf/subscribe [:gen/selected-tables])
        preview-data @(rf/subscribe [:gen/preview-data])
        preview-loading? @(rf/subscribe [:gen/preview-loading?])
        preview-visible? @(rf/subscribe [:gen/preview-visible?])
        preview-table-name @(rf/subscribe [:gen/preview-table-name])]
    [:div
     [antd/card {:title "数据库表" :style {:marginBottom 16}
                 :extra (r/as-element
                         [antd/button {:icon (r/as-element [:> ReloadOutlined])
                                       :onClick #(rf/dispatch [:gen/fetch-tables])}
                          "刷新"])}
      (if tables-loading?
        [:div {:style {:textAlign "center" :padding 48}} [antd/button {:loading true} "加载中..."]]
        (when (seq tables)
          [antd/table {:rowKey "table_name"
                       :dataSource (clj->js tables)
                       :pagination false
                       :columns (clj->js
                                 [{:title "表名" :dataIndex "table_name" :key "table_name"}
                                  {:title "注释" :dataIndex "table_comment" :key "table_comment"}
                                  {:title "操作" :key "action" :width 200
                                   :render (fn [_ ^js record]
                                             (r/as-element
                                              [antd/space
                                               [antd/button {:type "link" :size "small"
                                                             :icon (r/as-element [:> EyeOutlined])
                                                             :onClick #(rf/dispatch [:gen/preview (.-table_name record)])}
                                                "预览"]
                                               [antd/button {:type "link" :size "small"
                                                             :icon (r/as-element [:> CodeOutlined])
                                                             :onClick #(rf/dispatch [:gen/generate [(.-table_name record)]])}
                                                "生成"]]))}])
                       :rowSelection {:type "checkbox"
                                      :selectedRowKeys (clj->js selected-tables)
                                      :onChange (fn [keys _]
                                                  (rf/dispatch [:gen/set-selected-tables (js->clj keys)]))}}]))]
     (when (seq selected-tables)
       [antd/space {:style {:marginBottom 16}}
        [antd/button {:type "primary"
                      :onClick #(rf/dispatch [:gen/generate selected-tables])}
         (str "批量生成 (" (count selected-tables) " 个表)")]])
       ;; 代码预览弹窗
     [antd/drawer {:title (str "代码预览 - " preview-table-name)
                   :open preview-visible?
                   :width 800
                   :onClose #(rf/dispatch [:gen/close-preview])}
      (if preview-loading?
        [:div {:style {:textAlign "center" :padding 48}} [antd/button {:loading true} "加载中..."]]
        (when preview-data
          [:div
           (for [[k v] (sort-by first (dissoc preview-data :table-name :entity-name :kebab-name :camel-name :columns))]
             (when (seq v)
               ^{:key (str k)}
               [:div {:style {:marginBottom 16}}
                [:h4 {:style {:margin "0 0 8px 0"}} (get file-type-labels k (name k))]
                [:pre {:style {:background "var(--ant-color-bg-layout, #f5f5f5)" :padding 12 :borderRadius 4 :maxHeight 400 :overflow "auto" :fontSize 12}}
                 v]]))]))]]))
