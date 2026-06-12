(ns com.ruoyi.rouyi.frontend.pages.gen
  "代码生成器页面 — 数据库表选择、代码预览与生成。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined CodeOutlined EyeOutlined DownloadOutlined SettingOutlined]]
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
   "frontend-subs" "订阅"
   "migration-up" "Migration"})

;; ─── 配置弹窗 ──────────────────────────────────────────────────────

(defn- config-modal []
  (let [visible? @(rf/subscribe [:gen/config-visible?])
        config @(rf/subscribe [:gen/config])]
    [antd/modal {:title "生成配置" :open visible? :width 500
                 :onOk #(rf/dispatch [:gen/close-config])
                 :onCancel #(rf/dispatch [:gen/close-config])}
     [antd/form {:layout "vertical"}
      [antd/form-item {:label "包路径"}
       [antd/input {:value (:package-path config "com.ruoyi.rouyi")
                    :on-change #(rf/dispatch [:gen/update-config :package-path (.. % -target -value)])}]]
      [antd/form-item {:label "模块名"}
       [antd/input {:value (:module-name config "system")
                    :on-change #(rf/dispatch [:gen/update-config :module-name (.. % -target -value)])}]]
      [antd/form-item {:label "作者"}
       [antd/input {:value (:author config "ruoyi")
                    :on-change #(rf/dispatch [:gen/update-config :author (.. % -target -value)])}]]
      [antd/form-item {:label "表前缀（去除）"}
       [antd/input {:value (:table-prefix config "sys_")
                    :on-change #(rf/dispatch [:gen/update-config :table-prefix (.. % -target -value)])}]]]]))

;; ─── 代码预览（带 Tab） ──────────────────────────────────────────────

(defn- preview-tabs [preview-data]
  (let [active-tab (r/atom "backend-sql")]
    (fn [preview-data]
      (let [files (dissoc preview-data :table-name :entity-name :kebab-name :camel-name :columns)
            tab-items (clj->js
                       (map (fn [[k v]]
                              (when (seq v)
                                {:key (name k)
                                 :label (get file-type-labels (name k) (name k))}))
                            (sort-by first files)))]
        [:div
         [:> antd/tabs {:activeKey @active-tab
                        :onChange #(reset! active-tab %)
                        :items tab-items
                        :size "small"}]
         (let [current-file (get files (keyword @active-tab))]
           (when (seq current-file)
             [:pre {:style {:background "#1e1e1e" :color "#d4d4d4" :padding 16 :borderRadius 4
                            :maxHeight 500 :overflow "auto" :fontSize 12 :lineHeight 1.6
                            :whiteSpace "pre-wrap" :wordBreak "break-all"}}
              current-file]))]))))

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
     ;; 工具栏
     [antd/space {:style {:marginBottom 16}}
      [antd/button {:icon (r/as-element [:> ReloadOutlined])
                    :onClick #(rf/dispatch [:gen/fetch-tables])}
       "刷新"]
      [antd/button {:icon (r/as-element [:> SettingOutlined])
                    :onClick #(rf/dispatch [:gen/open-config])}
       "生成配置"]
      (when (seq selected-tables)
        [<>
         [antd/button {:type "primary"
                       :icon (r/as-element [:> CodeOutlined])
                       :onClick #(rf/dispatch [:gen/generate selected-tables])}
          (str "批量生成 (" (count selected-tables) " 个表)")]
         [antd/button {:icon (r/as-element [:> DownloadOutlined])
                       :onClick #(rf/dispatch [:gen/download selected-tables])}
          "下载ZIP"]])]

     ;; 表格
     [antd/card {:title "数据库表"}
      (if tables-loading?
        [:div {:style {:textAlign "center" :padding 48}} [antd/button {:loading true} "加载中..."]]
        (when (seq tables)
          [antd/table {:scroll #js {:x "max-content"} :rowKey "table_name"
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
                                                             :icon (r/as-element [:> DownloadOutlined])
                                                             :onClick #(rf/dispatch [:gen/download [(.-table_name record)]])}
                                                "下载"]]))}])
                       :rowSelection {:type "checkbox"
                                      :selectedRowKeys (clj->js selected-tables)
                                      :onChange (fn [keys _]
                                                  (rf/dispatch [:gen/set-selected-tables (js->clj keys)]))}}]))]

     ;; 代码预览弹窗
     [antd/drawer {:title (str "代码预览 - " preview-table-name)
                   :open preview-visible?
                   :width 900
                   :onClose #(rf/dispatch [:gen/close-preview])}
      (if preview-loading?
        [:div {:style {:textAlign "center" :padding 48}} [antd/button {:loading true} "加载中..."]]
        (when preview-data
          [preview-tabs preview-data]))]

     ;; 配置弹窗
     [config-modal]]))
