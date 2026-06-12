(ns com.ruoyi.rouyi.frontend.pages.config
  "参数配置管理页面 — 完整 CRUD。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- search-bar []
  (let [keyword (r/atom "")]
    [antd/card {:style {:marginBottom 16}}
     [antd/space
      [antd/input {:placeholder "参数名称" :allowClear true
                   :style {:width 200}
                   :on-change #(reset! keyword (.. % -target -value))}]
      [antd/button {:type "primary" :onClick #(rf/dispatch [:configs/fetch {:configName @keyword}])}
       "搜索"]
      [antd/button {:onClick #(do (reset! keyword "") (rf/dispatch [:configs/fetch {}]))}
       "重置"]]]))

(defn- config-columns [editing-item]
  #js [#js {:title "参数ID" :dataIndex "config_id" :key "config_id" :width 80}
       #js {:title "参数名称" :dataIndex "config_name" :key "config_name"}
       #js {:title "参数键名" :dataIndex "config_key" :key "config_key"}
       #js {:title "参数键值" :dataIndex "config_value" :key "config_value"}
       #js {:title "系统内置" :dataIndex "config_type" :key "config_type" :width 100
            :render (fn [v]
                      (r/as-element
                       [antd/tag {:color (if (= v "Y") "blue" "default")}
                        (if (= v "Y") "是" "否")]))}
       #js {:title "备注" :dataIndex "remark" :key "remark"}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 170}
       #js {:title "操作" :key "action" :width 160 :fixed "right"
            :render (fn [_ record]
                      (r/as-element
                       [antd/space
                        [antd/button {:type "link" :size "small"
                                      :onClick #(reset! editing-item (js->clj record :keywordize-keys true))}
                         "编辑"]
                        [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                          :on-confirm #(rf/dispatch [:configs/delete (:config_id (js->clj record :keywordize-keys true))])}
                         [antd/button {:type "link" :danger true :size "small"} "删除"]]]))}])

(defn- config-modal [visible? editing-item]
  (let [form-data (r/atom {})]
    (fn [visible? editing-item]
      (when @visible?
        (reset! form-data (or @editing-item {})))
      [antd/modal {:open @visible?
                   :title (if @editing-item "编辑参数" "新增参数")
                   :on-ok #(do
                             (if (:config_id @form-data)
                               (rf/dispatch [:configs/update (:config_id @form-data) @form-data])
                               (rf/dispatch [:configs/create @form-data]))
                             (reset! visible? false)
                             (reset! editing-item nil))
                   :on-cancel #(do (reset! visible? false) (reset! editing-item nil))
                   :okText "确定" :cancelText "取消"}
       [:div {:style {:display "flex" :flexDirection "column" :gap 12}}
        [:div
         [:span {:style {:color "red"}} "*"] " 参数名称: "]
        [antd/input {:value (:config_name @form-data "")
                     :on-change #(swap! form-data assoc :config_name (.. % -target -value))}]
        [:div
         [:span {:style {:color "red"}} "*"] " 参数键名: "]
        [antd/input {:value (:config_key @form-data "")
                     :on-change #(swap! form-data assoc :config_key (.. % -target -value))}]
        [:div
         [:span {:style {:color "red"}} "*"] " 参数键值: "]
        [antd/input {:value (:config_value @form-data "")
                     :on-change #(swap! form-data assoc :config_value (.. % -target -value))}]
        [:div "系统内置:"]
        [antd/radio-group {:value (:config_type @form-data "Y")
                           :on-change #(swap! form-data assoc :config_type (.. % -target -value))}
         [antd/radio {:value "Y"} "是"]
         [antd/radio {:value "N"} "否"]]
        [:div "备注:"]
        [antd/text-area {:value (:remark @form-data "")
                         :rows 3
                         :on-change #(swap! form-data assoc :remark (.. % -target -value))}]]])))

(defn config-page []
  (let [modal-visible? (r/atom false)
        editing-item (r/atom nil)]
    (hooks/use-effect
     (fn []
       (rf/dispatch [:configs/fetch {}])
       js/undefined)
     [])
    (let [items @(rf/subscribe [:configs/items])
          total @(rf/subscribe [:configs/total])
          loading? @(rf/subscribe [:configs/loading?])]
      [:div
       [search-bar]
       [antd/card
        [:div {:style {:marginBottom 16}}
         [antd/button {:type "primary" :onClick #(do (reset! editing-item nil) (reset! modal-visible? true))}
          "新增"]]
        [antd/table {:rowKey "config_id" :loading loading? :scroll #js {:x 800}
                     :columns (config-columns editing-item)
                     :dataSource (clj->js items)
                     :pagination {:pageSize 10 :total total
                                  :show-total (fn [t] (str "共 " t " 条"))}}]]
       [config-modal modal-visible? editing-item]])))
