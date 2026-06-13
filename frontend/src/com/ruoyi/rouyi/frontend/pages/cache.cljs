(ns com.ruoyi.rouyi.frontend.pages.cache
  "缓存监控页面 — RuoYi 风格。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined DeleteOutlined EyeOutlined]]
   ["antd" :refer [Progress Table Spin Modal]]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

;; ─── 进度条组件 ──────────────────────────────────────────────────────

(defn- usage-bar [{:keys [label used total unit color]}]
  (let [percent (if (and total (pos? total))
                  (min 100 (Math/round (* 100 (/ used total))))
                  0)
        color (or color
                  (cond
                    (> percent 80) "#ff4d4f"
                    (> percent 60) "#faad14"
                    :else "#52c41a"))]
    [:div {:style {:marginBottom 16}}
     [:div {:style {:display "flex" :justifyContent "space-between" :marginBottom 8}}
      [:span {:style {:fontSize 14 :fontWeight 500}} label]
      [:span {:style {:fontSize 14 :color "#666"}} (str percent "%")]]
     [:> Progress {:percent percent :strokeColor color :showInfo false
                   :size 10 :railColor "#f0f0f0"}]
     [:div {:style {:display "flex" :justifyContent "space-between" :marginTop 4 :fontSize 12 :color "#999"}}
      [:span (str "已用: " (if unit (unit used) used))]
      [:span (str "总计: " (if unit (unit total) total))]]]))

;; ─── 命令统计卡片 ──────────────────────────────────────────────────────

(defn- command-stat-card [{:keys [title value color]}]
  [:div {:style {:textAlign "center" :padding 16
                 :background "var(--ant-color-bg-container, #fff)"
                 :borderRadius 8}}
   [:div {:style {:fontSize 28 :fontWeight 600 :color (or color "#1677ff")}}
    (or value 0)]
   [:div {:style {:fontSize 12 :color "#999" :marginTop 4}} title]])

;; ─── 缓存名称表格 ──────────────────────────────────────────────────────

(defn- cache-names-table [names selected-name]
  [:> Table
   {:size "small"
    :pagination false
    :rowKey "name"
    :dataSource (clj->js (mapv (fn [n] {:name n}) names))
    :columns
    (clj->js
     [{:title "缓存名称" :dataIndex "name" :key "name"
       :render (fn [v]
                 (r/as-element
                  [:span {:style {:color "#1677ff" :cursor "pointer"}
                          :onClick #(do (rf/dispatch [:cache/select-name v])
                                        (rf/dispatch [:cache/fetch-keys]))}
                   v]))}
      {:title "操作" :key "action"
       :render (fn [_ record]
                 (let [cache-name (.-name record)]
                   (r/as-element
                    [:div
                     [antd/button {:type "link" :size "small"
                                   :onClick #(do (rf/dispatch [:cache/select-name cache-name])
                                                 (rf/dispatch [:cache/fetch-keys]))}
                      "查看"]
                     [antd/popconfirm
                      {:title (str "确认清空缓存 [" cache-name "]？")
                       :onConfirm #(rf/dispatch [:cache/clear-name cache-name])}
                      [antd/button {:type "link" :danger true :size "small"}
                       "删除"]]])))}])}])

;; ─── 缓存键表格 ──────────────────────────────────────────────────────

(defn- cache-keys-table [keys selected-name]
  [:> Table
   {:size "small"
    :pagination false
    :rowKey "key"
    :title (fn [] (r/as-element [:span {:style {:fontWeight 500}} (str "缓存键列表 — " selected-name)]))
    :dataSource (clj->js (mapv (fn [k] {:key k}) keys))
    :columns
    (clj->js
     [{:title "缓存键名" :dataIndex "key" :key "key"}
      {:title "操作" :key "action"
       :render (fn [_ record]
                 (let [cache-key (.-key record)]
                   (r/as-element
                    [:div
                     [antd/button {:type "link" :size "small"
                                   :icon (r/as-element [:> EyeOutlined])
                                   :onClick #(rf/dispatch [:cache/fetch-value selected-name cache-key])}
                      "查看值"]
                     [antd/popconfirm
                      {:title (str "确认删除缓存键 [" cache-key "]？")
                       :onConfirm #(rf/dispatch [:cache/clear-key selected-name cache-key])}
                      [antd/button {:type "link" :danger true :size "small"
                                    :icon (r/as-element [:> DeleteOutlined])}
                       "删除"]]])))}])}])

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn cache-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:cache/fetch-info])
     (rf/dispatch [:cache/fetch-names])
     js/undefined)
   [])
  (let [cache-data @(rf/subscribe [:cache/data])
        names @(rf/subscribe [:cache/names])
        selected-name @(rf/subscribe [:cache/selected-name])
        keys @(rf/subscribe [:cache/keys])
        value @(rf/subscribe [:cache/value])
        value-visible? @(rf/subscribe [:cache/value-visible?])
        loading? @(rf/subscribe [:cache/loading?])
        command-stats (or (:commandStats cache-data) [])]
    [:div
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
      [:h3 {:style {:margin 0}} "缓存监控"]
      [:div
       [antd/button {:icon (r/as-element [:> ReloadOutlined])
                     :onClick #(do (rf/dispatch [:cache/fetch-info])
                                   (rf/dispatch [:cache/fetch-names])
                                   (when selected-name
                                     (rf/dispatch [:cache/fetch-keys])))}
        "刷新"]
       [antd/popconfirm
        {:title "确认清空全部缓存？"
         :onConfirm #(rf/dispatch [:cache/clear])}
        [antd/button {:style {:marginLeft 8} :danger true}
         "清空全部缓存"]]]]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48}}
        [:> Spin {:size "large"}]]
       [:div
        ;; 命令统计
        [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr 1fr" :gap 16 :marginBottom 16}}
         (for [[idx stat] (map-indexed vector command-stats)]
           ^{:key idx}
           [command-stat-card {:title (:name stat) :value (:value stat)}])]
        ;; 缓存信息
        [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
         [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
          [:h4 {:style {:margin 0 :fontSize 16 :fontWeight 600}} "缓存信息"]
          [:span {:style {:fontSize 14 :color "#666"}}
           (str "键值数量：" (or (:keysCount cache-data) 0) " 个")]]
         [usage-bar {:label "内存使用"
                     :used (or (:memoryUsed cache-data) 0)
                     :total (or (:memoryMax cache-data) 256)
                     :unit #(str % " MB")}]
         [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 16 :marginTop 16 :fontSize 13}}
          [:div [:span {:style {:color "#666"}} "缓存名称："] [:span (or (:name cache-data) "N/A")]]
          [:div [:span {:style {:color "#666"}} "缓存类型："] [:span (or (:type cache-data) "N/A")]]]]
        ;; 缓存名称列表
        [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
         [:h4 {:style {:margin "0 0 16px 0" :fontSize 16 :fontWeight 600}} "缓存名称"]
         [cache-names-table names selected-name]]
        ;; 选中缓存的键列表
        (when selected-name
          [:div {:style {:background "var(--ant-color-bg-container)" :borderRadius 8 :padding 24 :marginBottom 16}}
           [cache-keys-table keys selected-name]])
        ;; 缓存值弹窗
        [:> Modal {:title "缓存值"
                   :open value-visible?
                   :footer nil
                   :onCancel #(rf/dispatch [:cache/close-value])
                   :style {:width 560}}
         [:pre {:style {:background "#f6f8fa" :padding 16 :borderRadius 8
                        :maxHeight 400 :overflow "auto" :margin 0
                        :fontSize 13 :whiteSpace "pre-wrap" :wordBreak "break-all"}}
          (or value "")]]])]))
