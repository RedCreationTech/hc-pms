(ns com.ruoyi.frontend.components.status-tag
  "状态标签组件."
  (:require
    [com.ruoyi.frontend.antd :as antd]))


(defn status-tag
  [{:keys [value options]}]
  (if-let [{:keys [label color]} (get options value)]
    [antd/tag {:color color} label]
    "-"))
