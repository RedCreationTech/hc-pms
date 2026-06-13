(ns com.ruoyi.frontend.components.action-menu
  "表格操作菜单。"
  (:require
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]))

(defn action-menu [{:keys [on-edit on-delete more-items]}]
  [antd/space
   [antd/button {:type "link" :size "small" :on-click on-edit} "修改"]
   [antd/button {:type "link" :danger true :size "small" :on-click on-delete} "删除"]
   [antd/dropdown {:menu {:items (clj->js
                                  (mapv (fn [{:keys [key label on-click]}]
                                          {:key (str key)
                                           :label (r/as-element [:span label])})
                                        more-items))
                          :onClick (fn [e]
                                     (when-let [item (first (filter #(= (str (:key %)) (.-key e)) more-items))]
                                       ((:on-click item))))}}
    [antd/button {:type "link" :size "small"} "更多 ▾"]]])
