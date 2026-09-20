(ns com.ruoyi.frontend.components.layout-settings
  "布局设置抽屉，提供 RuoYi 风格的主题与系统布局配置。"
  (:require
    ["@ant-design/icons" :refer [CheckOutlined ReloadOutlined]]
    ["antd" :refer [Button ColorPicker Divider Segmented Space Switch Tooltip]]
    [com.ruoyi.frontend.antd :as antd]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(def theme-colors
  ["#409eff" "#67c23a" "#e6a23c" "#f56c6c" "#909399" "#1890ff" "#13c2c2" "#722ed1"])


(defn- set-layout!
  "更新单个布局设置项。"
  [k value]
  (rf/dispatch [:layout/set-setting k value]))


(defn- selected?
  "判断设置值是否为当前选中项。"
  [settings k value]
  (= value (get settings k)))


(defn- preview-block
  "绘制导航模式的缩略预览。"
  [mode selected?]
  [:div {:style {:width 78 :height 68 :borderRadius 4 :background "#f0f2f5"
                 :position "relative" :overflow "hidden"
                 :border (if selected? "2px solid #409eff" "1px solid #e5e7eb")
                 :boxShadow (when selected? "0 0 0 1px rgba(64,158,255,0.18)")}}
   (case mode
     "side" [:<> [:div {:style {:position "absolute" :left 0 :top 0 :bottom 0 :width 22 :background "#1f2a44"}}]
             [:div {:style {:position "absolute" :left 22 :top 0 :right 0 :height 18 :background "#fff"}}]]
     "top" [:<> [:div {:style {:position "absolute" :left 0 :top 0 :right 0 :height 20 :background "#1f2a44"}}]
            [:div {:style {:position "absolute" :left 10 :right 10 :top 30 :height 10 :background "#fff" :borderRadius 2}}]]
     "mix" [:<> [:div {:style {:position "absolute" :left 0 :top 0 :right 0 :height 20 :background "#1f2a44"}}]
            [:div {:style {:position "absolute" :left 0 :top 20 :bottom 0 :width 22 :background "#1f2a44"}}]
            [:div {:style {:position "absolute" :left 32 :right 10 :top 32 :height 10 :background "#fff" :borderRadius 2}}]]
     nil)
   (when selected?
     [:div {:style {:position "absolute" :right 6 :bottom 6 :color "#409eff" :fontSize 16}}
      [:> CheckOutlined]])])


(defn- option-card
  "显示可点击的缩略设置卡片。"
  [label selected? child on-click]
  [:div {:style {:display "flex" :flexDirection "column" :gap 8 :alignItems "center"
                 :cursor "pointer" :color (if selected? "#409eff" "#606266")
                 :fontSize 12}
         :on-click on-click}
   child
   [:span label]])


(defn- section-title
  "绘制分组标题。"
  [text]
  [:div {:style {:fontSize 16 :fontWeight 700 :color "#303133" :margin "0 0 18px"}}
   text])


(defn- switch-style-overrides
  "覆盖布局设置抽屉内开关颜色，确保开启状态为亮色。"
  []
  [:style
   ".layout-settings-drawer .ant-switch { background: #dcdfe6 !important; }\n.layout-settings-drawer .ant-switch:hover:not(.ant-switch-disabled) { background: #cfd3dc !important; }\n.layout-settings-drawer .ant-switch.ant-switch-checked { background: #409eff !important; }\n.layout-settings-drawer .ant-switch.ant-switch-checked:hover:not(.ant-switch-disabled) { background: #66b1ff !important; }"])


(defn- setting-row
  "绘制一行开关设置。"
  [label checked? on-change]
  [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                 :height 42 :fontSize 14 :color "#606266"}}
   [:span label]
   [:> Switch {:checked checked? :onChange on-change}]])


(defn- theme-style-preview
  "绘制亮色/暗色主题预览。"
  [mode selected?]
  [:div {:style {:width 58 :height 54 :borderRadius 4 :background (if (= mode "dark") "#f7f8fa" "#fff")
                 :position "relative" :overflow "hidden" :border "1px solid #e5e7eb"
                 :boxShadow (when selected? "0 0 0 2px rgba(64,158,255,0.2)")}}
   (when (= mode "dark")
     [:div {:style {:position "absolute" :left 0 :top 0 :bottom 0 :width 20 :background "#1f2a44"}}])
   (when selected?
     [:div {:style {:position "absolute" :right 8 :top 16 :color "#409eff" :fontSize 18}}
      [:> CheckOutlined]])])


(defn- theme-color-picker
  "绘制主题颜色选择控件。"
  [primary-color]
  [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                 :height 44 :fontSize 14 :color "#606266"}}
   [:span "主题颜色"]
   [:> Space {:size 8}
    (for [color theme-colors]
      ^{:key color}
      [:> Tooltip {:title color}
       [:button {:type "button"
                 :aria-label (str "主题颜色 " color)
                 :style {:width 20 :height 20 :borderRadius 3 :border (if (= color primary-color) "2px solid #303133" "1px solid #dcdfe6")
                         :background color :cursor "pointer" :padding 0}
                 :on-click #(rf/dispatch [:theme/set-primary-color color])}]])
    [:> ColorPicker {:value primary-color
                     :onChange #(rf/dispatch [:theme/set-primary-color (.-toHexString ^js %)])
                     :size "small"}]]])


(defn layout-settings-drawer
  "显示布局设置抽屉。open? 控制显示，on-close 关闭抽屉。"
  [{:keys [open? on-close]}]
  (let [settings (merge {:nav-mode "side" :theme-style "light" :open-tags? true
                         :cache-tags? true :show-tab-icon? true :tab-style "google"
                         :fixed-header? true :show-logo? true :dynamic-title? true
                         :show-footer? true}
                        @(rf/subscribe [:layout/settings]))
        primary-color @(rf/subscribe [:theme/primary-color])]
    [antd/drawer {:open open? :onClose on-close :placement "right" :style {:width 390}
                  :className "layout-settings-drawer"
                  :destroyOnHidden true :closable false
                  :styles {:body {:padding "24px 22px"}}}
     [switch-style-overrides]
     [section-title "菜单导航设置"]
     [:div {:style {:display "flex" :gap 20 :marginBottom 28}}
      (for [[mode label] [["side" "侧边"] ["top" "顶部"] ["mix" "混合"]]]
        ^{:key mode}
        [option-card label (selected? settings :nav-mode mode)
         [preview-block mode (selected? settings :nav-mode mode)]
         #(set-layout! :nav-mode mode)])]
     [section-title "主题风格设置"]
     [:div {:style {:display "flex" :gap 24 :marginBottom 28}}
      (for [[mode label] [["dark" "暗色"] ["light" "亮色"]]]
        ^{:key mode}
        [option-card label (selected? settings :theme-style mode)
         [theme-style-preview mode (selected? settings :theme-style mode)]
         #(set-layout! :theme-style mode)])]
     [theme-color-picker primary-color]
     [:> Divider {:style {:margin "24px 0"}}]
     [section-title "系统布局配置"]
     [setting-row "开启页签" (:open-tags? settings) #(set-layout! :open-tags? %)]
     [setting-row "持久化标签页" (:cache-tags? settings) #(set-layout! :cache-tags? %)]
     [setting-row "显示页签图标" (:show-tab-icon? settings) #(set-layout! :show-tab-icon? %)]
     [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                    :height 46 :fontSize 14 :color "#606266"}}
      [:span "标签页样式"]
      [:> Segmented {:value (:tab-style settings)
                     :onChange #(set-layout! :tab-style %)
                     :options #js [#js {:label "卡片" :value "card"}
                                   #js {:label "谷歌" :value "google"}]}]]
     [setting-row "固定 Header" (:fixed-header? settings) #(set-layout! :fixed-header? %)]
     [setting-row "显示 Logo" (:show-logo? settings) #(set-layout! :show-logo? %)]
     [setting-row "动态标题" (:dynamic-title? settings) #(set-layout! :dynamic-title? %)]
     [setting-row "底部版权" (:show-footer? settings) #(set-layout! :show-footer? %)]
     [:> Divider {:style {:margin "24px 0 18px"}}]
     [:> Button {:icon (r/as-element [:> ReloadOutlined])
                 :block true
                 :onClick #(rf/dispatch [:layout/reset-settings])}
      "重置配置"]]))
