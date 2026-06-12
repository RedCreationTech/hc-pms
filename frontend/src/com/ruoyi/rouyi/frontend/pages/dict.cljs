(ns com.ruoyi.rouyi.frontend.pages.dict
  "字典管理页面 — 完整 CRUD。两级联动：类型列表 → 数据列表。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defonce selected-type (r/atom nil))
(defonce type-modal-visible? (r/atom false))
(defonce type-editing (r/atom nil))
(defonce data-modal-visible? (r/atom false))
(defonce data-editing (r/atom nil))

;; ─── 字典类型 ─────────────────────────────────────────────────────────────────

(defn- type-form-modal []
  (let [form-data (r/atom {})]
    (when @type-modal-visible?
      (reset! form-data (or @type-editing {})))
    [antd/modal {:open @type-modal-visible?
                 :title (if @type-editing "编辑字典类型" "新增字典类型")
                 :on-ok #(do
                           (if (:dict_id @form-data)
                             (rf/dispatch [:dicts/update-type (:dict_id @form-data) @form-data])
                             (rf/dispatch [:dicts/create-type @form-data]))
                           (reset! type-modal-visible? false)
                           (reset! type-editing nil))
                 :on-cancel #(do (reset! type-modal-visible? false) (reset! type-editing nil))
                 :okText "确定" :cancelText "取消"}
     [:div {:style {:display "flex" :flexDirection "column" :gap 12}}
      [:div [:span {:style {:color "red"}} "*"] " 字典名称:"]
      [antd/input {:value (:dict_name @form-data "")
                   :on-change #(swap! form-data assoc :dict_name (.. % -target -value))}]
      [:div [:span {:style {:color "red"}} "*"] " 字典类型:"]
      [antd/input {:value (:dict_type @form-data "")
                   :on-change #(swap! form-data assoc :dict_type (.. % -target -value))}]
      [:div "状态:"]
      [antd/radio-group {:value (:status @form-data "0")
                         :on-change #(swap! form-data assoc :status (.. % -target -value))}
       [antd/radio {:value "0"} "正常"]
       [antd/radio {:value "1"} "停用"]]
      [:div "备注:"]
      [antd/text-area {:value (:remark @form-data "") :rows 3
                       :on-change #(swap! form-data assoc :remark (.. % -target -value))}]]]))

(defn- type-columns []
  #js [#js {:title "字典编号" :dataIndex "dict_id" :key "dict_id" :width 80}
       #js {:title "字典名称" :dataIndex "dict_name" :key "dict_name"}
       #js {:title "字典类型" :dataIndex "dict_type" :key "dict_type"}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:color (if (= v "0") "green" "red")}
                        (if (= v "0") "正常" "停用")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 180 :fixed "right"
            :render (fn [record _]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/space
                          [antd/button {:type "link" :size "small"
                                        :onClick #(reset! selected-type row)}
                           "字典数据"]
                          [antd/button {:type "link" :size "small"
                                        :onClick #(do (reset! type-editing row) (reset! type-modal-visible? true))}
                           "编辑"]
                          [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                            :on-confirm #(rf/dispatch [:dicts/delete-type (:dict_id row)])}
                           [antd/button {:type "link" :danger true :size "small"} "删除"]]])))}])

(defn- type-section []
  (hooks/use-effect (fn []
                      (rf/dispatch [:dicts/fetch-types {}])
                      js/undefined)
                    [])
  (let [types @(rf/subscribe [:dicts/types])
        loading? @(rf/subscribe [:dicts/loading?])]
    [:div
     [:div {:style {:marginBottom 16}}
      [antd/button {:type "primary" :onClick #(do (reset! type-editing nil) (reset! type-modal-visible? true))}
       "新增字典类型"]]
     [antd/table {:rowKey "dict_id" :loading loading? :scroll #js {:x 700}
                  :columns (type-columns)
                  :dataSource (clj->js types)
                  :pagination {:pageSize 10 :show-total (fn [t] (str "共 " t " 条"))}}]
     [type-form-modal]]))

;; ─── 字典数据 ─────────────────────────────────────────────────────────────────

(defn- data-form-modal [dict-type]
  (let [form-data (r/atom {})]
    (fn [dict-type]
      (when @data-modal-visible?
        (reset! form-data (or @data-editing {})))
      [antd/modal {:open @data-modal-visible?
                   :title (if @data-editing "编辑字典数据" "新增字典数据")
                   :on-ok #(do
                             (let [params (assoc @form-data :dict_type (:dict_type dict-type))]
                               (if (:dict_code @form-data)
                                 (rf/dispatch [:dicts/update-data (:dict_code @form-data) params])
                                 (rf/dispatch [:dicts/create-data params])))
                             (reset! data-modal-visible? false)
                             (reset! data-editing nil))
                   :on-cancel #(do (reset! data-modal-visible? false) (reset! data-editing nil))
                   :okText "确定" :cancelText "取消"}
       [:div {:style {:display "flex" :flexDirection "column" :gap 12}}
        [:div [:span {:style {:color "red"}} "*"] " 字典标签:"]
        [antd/input {:value (:dict_label @form-data "")
                     :on-change #(swap! form-data assoc :dict_label (.. % -target -value))}]
        [:div [:span {:style {:color "red"}} "*"] " 字典键值:"]
        [antd/input {:value (:dict_value @form-data "")
                     :on-change #(swap! form-data assoc :dict_value (.. % -target -value))}]
        [:div "排序:"]
        [antd/input-number {:value (:dict_sort @form-data 0) :min 0
                            :on-change #(swap! form-data assoc :dict_sort %)}]
        [:div "状态:"]
        [antd/radio-group {:value (:status @form-data "0")
                           :on-change #(swap! form-data assoc :status (.. % -target -value))}
         [antd/radio {:value "0"} "正常"]
         [antd/radio {:value "1"} "停用"]]
        [:div "备注:"]
        [antd/text-area {:value (:remark @form-data "") :rows 2
                         :on-change #(swap! form-data assoc :remark (.. % -target -value))}]]])))

(defn- data-columns [dict-type]
  #js [#js {:title "字典编码" :dataIndex "dict_code" :key "dict_code" :width 80}
       #js {:title "字典标签" :dataIndex "dict_label" :key "dict_label"}
       #js {:title "字典键值" :dataIndex "dict_value" :key "dict_value"}
       #js {:title "排序" :dataIndex "dict_sort" :key "dict_sort" :width 60}
       #js {:title "状态" :dataIndex "status" :key "status" :width 80
            :render (fn [v _]
                      (r/as-element
                       [antd/tag {:color (if (= v "0") "green" "red")}
                        (if (= v "0") "正常" "停用")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "操作" :key "action" :width 120 :fixed "right"
            :render (fn [record _]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/space
                          [antd/button {:type "link" :size "small"
                                        :onClick #(do (reset! data-editing row) (reset! data-modal-visible? true))}
                           "编辑"]
                          [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                            :on-confirm #(rf/dispatch [:dicts/delete-data (:dict_code row)])}
                           [antd/button {:type "link" :danger true :size "small"} "删除"]]])))}])

(defn- data-section []
  (let [dict-type @selected-type
        data @(rf/subscribe [:dicts/data])
        loading? @(rf/subscribe [:dicts/loading?])]
    (hooks/use-effect (fn []
                        (when dict-type
                          (rf/dispatch [:dicts/fetch-data {:dict_type (:dict_type dict-type)}]))
                        js/undefined)
                      [(:dict_type dict-type)])
    (if dict-type
      [:div {:style {:marginTop 24}}
       [:div {:style {:display "flex" :justifyContent "space-between"
                      :alignItems "center" :marginBottom 16}}
        [:h4 {:style {:margin 0}} (str "字典数据 — " (:dict_name dict-type) " (" (:dict_type dict-type) ")")]
        [antd/button {:type "link" :onClick #(reset! selected-type nil)}
         "返回类型列表"]]
       [:div {:style {:marginBottom 16}}
        [antd/button {:type "primary" :onClick #(do (reset! data-editing nil) (reset! data-modal-visible? true))}
         "新增字典数据"]]
       [antd/table {:rowKey "dict_code" :loading loading? :scroll #js {:x 600}
                    :columns (data-columns dict-type)
                    :dataSource (clj->js data)
                    :pagination {:pageSize 10 :show-total (fn [t] (str "共 " t " 条"))}}]
       [data-form-modal dict-type]]
      [:div])))

;; ─── 主页面 ───────────────────────────────────────────────────────────────────

(defn dict-page []
  [:div
   [:h3 {:style {:marginBottom 16}} "字典管理"]
   [type-section]
   [data-section]])
