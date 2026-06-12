(ns com.ruoyi.rouyi.frontend.components.theme-switcher
  "主题切换组件，提供亮色/暗色主题切换及主题自定义选项"
  (:require
   ["@ant-design/icons" :refer [BgColorsOutlined MoonOutlined SunOutlined SettingOutlined]]
   ["antd" :refer [Button ColorPicker Divider Popover Segmented Space]]
   [re-frame.core :as rf]
   [reagent.core :as r]))

;; ─── 预设颜色 ──────────────────────────────────────────────────────
(def preset-colors
  ["#1890ff"  ; 蓝色 (默认)
   "#52c41a"  ; 绿色
   "#f5222d"  ; 红色
   "#fa541c"  ; 橙红色
   "#fa8c16"  ; 橙色
   "#faad14"  ; 金色
   "#fadb14"  ; 黄色
   "#a0d911"  ; 青绿色
   "#13c2c2"  ; 青色
   "#2f54eb"  ; 深蓝色
   "#722ed1"  ; 紫色
   "#eb2f96"  ; 品红色
   "#666666"  ; 灰色
   ])

;; ─── 算法选项 ──────────────────────────────────────────────────────
(def theme-algorithms
  [{"label" "默认" "value" "default"}
   {"label" "暗色" "value" "dark"}
   {"label" "紧凑" "value" "compact"}])

;; ─── 组件尺寸选项 ──────────────────────────────────────────────────
(def component-sizes
  [{"label" "小" "value" "small"}
   {"label" "中" "value" "middle"}
   {"label" "大" "value" "large"}])

;; ─── 主题设置面板 ──────────────────────────────────────────────────
(defn theme-settings-panel []
  (let [theme-mode @(rf/subscribe [:theme/mode])
        theme-algorithm @(rf/subscribe [:theme/algorithm])
        primary-color @(rf/subscribe [:theme/primary-color])
        component-size @(rf/subscribe [:theme/component-size])]
    [:div {:style {:padding "16px" :width "280px"}}
     [:div {:style {:margin "0 0 16px 0" :fontSize 16 :fontWeight 600}} "主题设置"]

     ;; 主题模式切换
     [:div {:style {:marginBottom "16px"}}
      [:div {:style {:marginBottom "8px" :fontSize "14px" :fontWeight "500"}} "主题模式"]
      [:> Segmented
       {:value (name theme-mode)
        :onChange #(rf/dispatch [:theme/set-mode (keyword %)])
        :options [{"label" (r/as-element [:span [:> SunOutlined] " 亮色"])
                   "value" "light"}
                  {"label" (r/as-element [:span [:> MoonOutlined] " 暗色"])
                   "value" "dark"}]}]]

     [:> Divider {:style {:margin "16px 0"}}]

     ;; 算法选择
     [:div {:style {:marginBottom "16px"}}
      [:div {:style {:marginBottom "8px" :fontSize "14px" :fontWeight "500"}} "主题算法"]
      [:> Segmented
       {:value theme-algorithm
        :onChange #(rf/dispatch [:theme/set-algorithm %])
        :options theme-algorithms
        :size "small"}]]

     ;; 主色调选择
     [:div {:style {:marginBottom "16px"}}
      [:div {:style {:marginBottom "8px" :fontSize "14px" :fontWeight "500"}} "主色调"]
      [:> Space {:wrap true :size "small"}
       (for [[idx color] (map-indexed vector preset-colors)]
         ^{:key (str color "-" idx)}
         [:div
          {:style {:width "24px"
                   :height "24px"
                   :backgroundColor color
                   :borderRadius "4px"
                   :cursor "pointer"
                   :border (if (= color primary-color) "2px solid #000" "1px solid #d9d9d9")
                   :display "inline-block"}
           :onClick #(rf/dispatch [:theme/set-primary-color color])}])
       [:> ColorPicker
        {:value primary-color
         :onChange #(rf/dispatch [:theme/set-primary-color (.-toHexString ^js %)])
         :trigger "hover"
         :size "small"}
        [:div
         {:style {:width "24px"
                  :height "24px"
                  :backgroundColor primary-color
                  :borderRadius "4px"
                  :cursor "pointer"
                  :border "1px solid #d9d9d9"
                  :display "flex"
                  :alignItems "center"
                  :justifyContent "center"}}
         [:> BgColorsOutlined {:style {:fontSize "14px"}}]]]]]

     ;; 组件尺寸
     [:div
      [:div {:style {:marginBottom "8px" :fontSize "14px" :fontWeight "500"}} "组件尺寸"]
      [:> Segmented
       {:value component-size
        :onChange #(rf/dispatch [:theme/set-component-size %])
        :options component-sizes
        :size "small"}]]]))

;; ─── 主题切换按钮（带 Popover）──────────────────────────────────────
(defn theme-switcher-button []
  (let [theme-mode @(rf/subscribe [:theme/mode])]
    [:> Popover
     {:content (r/as-element [theme-settings-panel])
      :title nil
      :trigger "click"
      :placement "bottomRight"}
     [:> Button
      {:type "text"
       :icon (r/as-element [:> SettingOutlined])
       :title "主题设置"}]]))
