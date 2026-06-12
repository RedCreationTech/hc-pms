(ns com.ruoyi.rouyi.frontend.pages.form-builder
  "可视化表单构建器 — 拖拽式表单设计。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ToolOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn form-builder-page []
  [:div {:style {:padding 48 :textAlign "center"}}
   [antd/card {:title "表单构建器"
               :style {:maxWidth 600 :margin "0 auto"}}
    [:> ToolOutlined {:style {:fontSize 48 :color "#1677ff" :marginBottom 24}}]
    [:h3 {:style {:color "#666"}} "拖拽式表单设计器"]
    [:p {:style {:color "#999" :lineHeight 2}}
     "可视化拖拽创建表单页面，支持多种组件类型。"]
    [antd/tag {:color "orange"} "即将上线"]]])
