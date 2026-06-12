(ns com.ruoyi.rouyi.frontend.components.dict-tag
  "字典标签组件 — 根据 dict_type 和 value 渲染 antd Tag。"
  (:require
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]
   [reagent.core :as r]))

(defn dict-tag
  "根据字典类型和值显示标签。
  props: :dict-type :value :color"
  [{:keys [dict-type value color]}]
  (let [dict-data @(rf/subscribe [:dicts/data])]
    (if (and dict-type value)
      (let [label (or (some #(when (= (str value) (str (:dict_value %))) (:dict_label %))
                            (filter #(= dict-type (:dict_type %)) dict-data))
                      (str value))]
        [antd/tag {:color (or color "blue")} label])
      [antd/tag {} (str value)])))
