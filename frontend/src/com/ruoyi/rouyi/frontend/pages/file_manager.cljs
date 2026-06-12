(ns com.ruoyi.rouyi.frontend.pages.file-manager
  "文件管理页面 — 文件上传、存储、预览。"
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [FileTextOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn file-manager-page []
  [:div {:style {:padding 48 :textAlign "center"}}
   [antd/card {:title "文件管理"
               :style {:maxWidth 600 :margin "0 auto"}}
    [:> FileTextOutlined {:style {:fontSize 48 :color "#1677ff" :marginBottom 24}}]
    [:h3 {:style {:color "#666"}} "文件存储管理"]
    [:p {:style {:color "#999" :lineHeight 2}}
     "支持文件上传、存储、预览、下载管理。"]
    [antd/tag {:color "orange"} "即将上线"]]])
