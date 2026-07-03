(ns com.ruoyi.frontend.components.page-toolbar
  "页面工具栏容器。"
  (:require
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]))

(defn page-toolbar [{:keys [left right style]}]
  [:div {:style (merge {:display "flex"
                        :justifyContent "space-between"
                        :alignItems "center"
                        :gap 12
                        :padding "12px 22px 14px 22px"
                        :background "transparent"}
                       style)}
   left
   right])

(def button-colors
  {:add {:color "#409eff" :border "1px solid #a0cfff" :background "#ecf5ff"}
   :edit {:color "#67c23a" :border "1px solid #b3e19d" :background "#f0f9eb"}
   :delete {:color "#f56c6c" :border "1px solid #fab6b6" :background "#fef0f0"}
   :import {:color "#909399" :border "1px solid #d3d4d6" :background "#f4f4f5"}
   :export {:color "#e6a23c" :border "1px solid #f3d19e" :background "#fdf6ec"}
   :default {:height 36 :borderRadius 4}})

(defn toolbar-button
  [{:keys [kind icon on-click disabled? children label]}]
  [antd/button {:icon icon
                :disabled disabled?
                :on-click on-click
                :style (merge {:height 36
                               :minWidth 86
                               :padding "0 15px"
                               :borderRadius 4
                               :fontSize 14}
                              (get button-colors kind)
                              (when disabled?
                                {:opacity 0.55}))}
   (or label children)])

(defn toolbar-left [& children]
  (into [:div {:style {:display "flex" :gap 10}}] children))

(defn search-button [{:keys [icon on-click label]}]
  [antd/button {:type "primary"
                :icon icon
                :on-click on-click
                :style {:height 36
                        :minWidth 86
                        :borderRadius 4
                        :fontSize 14
                        :background "#409eff"
                        :border "1px solid #409eff"}}
   (or label "搜索")])

(defn reset-button [{:keys [icon on-click label]}]
  [antd/button {:icon icon
                :on-click on-click
                :style {:height 36
                        :minWidth 86
                        :borderRadius 4
                        :fontSize 14
                        :color "#606266"
                        :border "1px solid #dcdfe6"}}
   (or label "重置")])

(defn round-tool-button [{:keys [icon on-click title]}]
  [antd/tooltip {:title title}
   [antd/button {:shape "circle"
                 :icon icon
                 :on-click on-click
                 :style {:width 40
                         :height 40
                         :display "inline-flex"
                         :alignItems "center"
                         :justifyContent "center"
                         :color "#606266"
                         :border "1px solid #dcdfe6"
                         :boxShadow "0 2px 8px rgba(0,0,0,0.06)"}}]])

(defn toolbar-right [& children]
  (into [:div {:style {:display "flex" :gap 12
                         :alignItems "center"}}] children))
