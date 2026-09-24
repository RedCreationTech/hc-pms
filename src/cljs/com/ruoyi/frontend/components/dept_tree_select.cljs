(ns com.ruoyi.frontend.components.dept-tree-select
  "可复用部门树选择器组件."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [re-frame.core :as rf]
    [reagent.hooks :as hooks]))


(defn root-depts
  "树的根: 上级不在列表中的部门 (数据权限只返回部分部门时, 以可见部门的最高层为根)."
  [items]
  (let [ids (set (map :dept_id items))]
    (filterv #(not (contains? ids (:parent_id %))) items)))


(defn build-tree
  "把平铺部门列表转换为树; node-fn 把部门转换为节点 (子节点放在 :children)."
  [items node-fn]
  (let [by-parent (group-by :parent_id items)
        build (fn build [d]
                (let [node (node-fn d)
                      children (mapv build (get by-parent (:dept_id d)))]
                  (if (seq children) (assoc node :children children) node)))]
    (mapv build (root-depts items))))


(defn- build-tree-data
  "将部门列表转换为 TreeSelect 使用的树形数据."
  [items]
  (build-tree items (fn [d] {:title (:dept_name d) :value (str (:dept_id d)) :key (str (:dept_id d))})))


(defn- normalize-props
  "兼容 js/React props 与 Clojure map."
  [props]
  (cond
    (map? props) props
    (object? props) (js->clj props :keywordize-keys true)
    :else {}))


(defn dept-tree-select
  "部门树选择器.
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
     {:style (merge {:width "100%"} (:style props))
      :placeholder (or placeholder "请选择部门")
      :allowClear (if (false? allow-clear?) false true)
      :showSearch true
      :treeDefaultExpandAll true
      :loading loading?
      :treeData (clj->js (build-tree-data items))
      :value (when value (str value))
      :on-change (fn [v]
                   (when (fn? on-change)
                     (on-change (when v (js/parseInt v 10)))))}]))
