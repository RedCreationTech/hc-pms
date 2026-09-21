(ns com.ruoyi.frontend.components.search-input
  "搜索表单项布局."
  (:require
    [com.ruoyi.frontend.antd :as antd]))


(defn search-input
  [{:keys [label]} child]
  [:div {:style {:display "flex" :alignItems "center" :gap 8}}
   [:span {:style {:whiteSpace "nowrap" :fontSize 13}} label]
   child])
