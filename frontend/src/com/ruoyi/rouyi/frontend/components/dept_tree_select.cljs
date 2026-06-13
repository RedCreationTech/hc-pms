(ns com.ruoyi.rouyi.frontend.components.dept-tree-select
  "可复用部门树选择器组件。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- build-tree-data
  "将部门列表转换为 TreeSelect 使用的树形数据。"
  [items parent-id]
  (->> items
       (filter #(= parent-id (:parent_id %)))
       (mapv (fn [d]
               (let [node {:title (:dept_name d) :value (:dept_id d) :key (str (:dept_id d))}]
                 (if-let [children (seq (build-tree-data items (:dept_id d)))]
                   (assoc node :children children)
                   node))))))

(defn- normalize-props
  "兼容 js/React props 与 Clojure map。"
  [props]
  (cond
    (map? props) props
    (object? props) (js->clj props :keywordize-keys true)
    :else {}))

(defn dept-tree-select
  "部门树选择器。
  props: :value :on-change/:onChange :placeholder :allow-clear?"
  [props]
  (let [props (normalize-props props)
        value (:value props)
        on-change (or (:on-change props) (:onChange props))
        placeholder (:placeholder props)
        allow-clear? (:allow-clear? props)
        items @(rf/subscribe [:depts/items])
        loading? @(rf/subscribe [:depts/loading?])]
    (hooks/use-effect
     (fn []
       (when (empty? items)
         (rf/dispatch [:depts/fetch {}]))
       js/undefined)
     [])
    [antd/tree-select
     {:style {:width "100%"}
      :placeholder (or placeholder "请选择部门")
      :allowClear (if (false? allow-clear?) false true)
      :showSearch true
      :treeDefaultExpandAll true
      :loading loading?
      :treeData (clj->js (build-tree-data items 0))
      :value (when value (str value))
      :on-change (fn [v]
                   (when (fn? on-change)
                     (on-change (when v (js/parseInt v 10)))))}]))
