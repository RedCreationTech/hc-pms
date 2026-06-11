(ns com.ruoyi.rouyi.frontend.pages.config
  "参数配置管理页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn config-page []
  (let [items @(rf/subscribe [:configs/items])
        total @(rf/subscribe [:configs/total])
        loading? @(rf/subscribe [:configs/loading?])]
    [:div
     [:h3 "参数管理"]
     [:p "系统参数配置列表，共 " (or total 0) " 条"]]))
