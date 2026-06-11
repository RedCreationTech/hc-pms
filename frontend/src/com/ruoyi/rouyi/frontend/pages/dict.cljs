(ns com.ruoyi.rouyi.frontend.pages.dict
  "字典管理页面。两级联动：类型列表 → 数据列表。"
  (:require
    [reagent.core :as r]
    [reagent.hooks :as hooks]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defonce selected-type (r/atom nil))

;; --- 字典类型部分 ---

(defn- type-columns []
  #js [#js {:title "字典编号" :dataIndex "dict_id" :key "dict_id" :width 80}
       #js {:title "字典名称" :dataIndex "dict_name" :key "dict_name"}
       #js {:title "字典类型" :dataIndex "dict_type" :key "dict_type"}
       #js {:title "状态" :dataIndex "status" :key "status"
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time"}
       #js {:title "操作" :key "action" :width 150
            :render (fn [record _]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"
                                       :onClick #(reset! selected-type record)}
                          [antd/search-icon] "字典数据"]
                         [antd/button {:type "link" :size "small"} "编辑"]
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]))}])

(defn- type-section []
  (hooks/use-effect (fn []
                      (rf/dispatch [:dicts/fetch-types {}])
                      js/undefined)
                    [])
  (let [types @(rf/subscribe [:dicts/types])
        loading? @(rf/subscribe [:dicts/loading?])]
    (fn []
      [:div
       [:h4 "字典类型"]
       [antd/space {:style {:marginBottom 16}}
        [antd/button {:type "primary"} [antd/plus-icon] "新增字典类型"]]
       [antd/table {:rowKey "dict_id"
                    :loading (and loading? (nil? @selected-type))
                    :columns (type-columns)
                    :dataSource (clj->js types)
                    :pagination {:pageSize 10}}]])))

;; --- 字典数据部分 ---

(defn- data-columns []
  #js [#js {:title "字典编码" :dataIndex "dict_code" :key "dict_code" :width 80}
       #js {:title "字典标签" :dataIndex "dict_label" :key "dict_label"}
       #js {:title "字典键值" :dataIndex "dict_value" :key "dict_value"}
       #js {:title "字典排序" :dataIndex "dict_sort" :key "dict_sort" :width 80}
       #js {:title "状态" :dataIndex "status" :key "status"
            :render (fn [v _]
                      (r/as-element
                        [antd/tag {:color (if (= v "0") "green" "red")}
                         (if (= v "0") "正常" "停用")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "操作" :key "action" :width 120
            :render (fn [_ _]
                      (r/as-element
                        [antd/space
                         [antd/button {:type "link" :size "small"} "编辑"]
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]))}])

(defn- data-section []
  (let [dict-type @selected-type]
    (hooks/use-effect (fn []
                        (when dict-type
                          (rf/dispatch [:dicts/fetch-data {:dict_type (.-dict_type dict-type)}]))
                        js/undefined)
                      [(when dict-type (.-dict_type dict-type))])
    (let [data @(rf/subscribe [:dicts/data])
          loading? @(rf/subscribe [:dicts/loading?])]
      (fn []
        (if dict-type
          [:div {:style {:marginTop 24}}
           [:div {:style {:display "flex" :justifyContent "space-between"
                          :alignItems "center" :marginBottom 16}}
            [:h4 {:style {:margin 0}} (str "字典数据 — " (.-dict_name dict-type) " (" (.-dict_type dict-type) ")")]
            [antd/button {:type "link" :onClick #(reset! selected-type nil)}
             "返回类型列表"]]
           [antd/space {:style {:marginBottom 16}}
            [antd/button {:type "primary"} [antd/plus-icon] "新增字典数据"]]
           [antd/table {:rowKey "dict_code"
                        :loading loading?
                        :columns (data-columns)
                        :dataSource (clj->js data)
                        :pagination {:pageSize 10}}]]
          [:div])))))

;; --- 主页面 ---

(defn dict-page []
  [:div
   [:h3 "字典管理"]
   [type-section]
   [data-section]])
