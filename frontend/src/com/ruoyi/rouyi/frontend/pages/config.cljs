(ns com.ruoyi.rouyi.frontend.pages.config
  "参数配置管理页面 — 完整 CRUD。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined DownloadOutlined SearchOutlined ReloadOutlined]]
   [com.ruoyi.rouyi.frontend.antd :as antd]
   [com.ruoyi.rouyi.frontend.api :as api]))

(defn- search-bar []
  (let [[keyword set-keyword!] (hooks/use-state "")]
    [antd/card {:style {:marginBottom 16}}
     [antd/space
      [antd/input {:placeholder "参数名称" :allowClear true
                   :style {:width 200}
                   :value keyword
                   :on-change #(set-keyword! (.. % -target -value))}]
      [antd/button {:type "primary" :onClick #(rf/dispatch [:configs/fetch {:configName keyword}])}
       "搜索"]
      [antd/button {:onClick #(do (set-keyword! "") (rf/dispatch [:configs/fetch {}]))}
       "重置"]]]))

(defn- config-columns [on-edit on-delete]
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
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/space
                          [antd/button {:type "link" :size "small"
                                        :onClick #(on-edit row)}
                           "编辑"]
                          [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消"
                                            :on-confirm #(on-delete (:config_id row))}
                           [antd/button {:type "link" :danger true :size "small"} "删除"]]])))}])

(defn- config-modal [{:keys [visible? editing on-ok on-cancel]}]
  (let [[form set-form!] (hooks/use-state {})]
    (hooks/use-effect
     (fn [] (set-form! (or editing {})) js/undefined)
     #js [visible?])
    [antd/modal {:open visible?
                 :title (if editing "编辑参数" "新增参数")
                 :on-ok #(on-ok form)
                 :on-cancel on-cancel
                 :okText "确定" :cancelText "取消"}
     [:div {:style {:display "flex" :flexDirection "column" :gap 12}}
      [:div [:span {:style {:color "red"}} "*"] " 参数名称: "]
      [antd/input {:value (:config_name form "")
                   :on-change #(set-form! (assoc form :config_name (.. % -target -value)))}]
      [:div [:span {:style {:color "red"}} "*"] " 参数键名: "]
      [antd/input {:value (:config_key form "")
                   :on-change #(set-form! (assoc form :config_key (.. % -target -value)))}]
      [:div [:span {:style {:color "red"}} "*"] " 参数键值: "]
      [antd/input {:value (:config_value form "")
                   :on-change #(set-form! (assoc form :config_value (.. % -target -value)))}]
      [:div "系统内置:"]
      [antd/radio-group {:value (:config_type form "Y")
                         :on-change #(set-form! (assoc form :config_type (.. % -target -value)))}
       [antd/radio {:value "Y"} "是"]
       [antd/radio {:value "N"} "否"]]
      [:div "备注:"]
      [antd/text-area {:value (:remark form "") :rows 3
                       :on-change #(set-form! (assoc form :remark (.. % -target -value)))}]]]))

(defn config-page []
  (let [items @(rf/subscribe [:configs/items])
        total @(rf/subscribe [:configs/total])
        loading? @(rf/subscribe [:configs/loading?])
        [modal-visible? set-modal-visible!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)]
    (hooks/use-effect
     (fn [] (rf/dispatch [:configs/fetch {}]) js/undefined)
     [])
    [:div
     [search-bar]
     [antd/card
      [:div {:style {:marginBottom 16}}
       [antd/button {:type "primary" :onClick #(do (set-editing! nil) (set-modal-visible! true))}
        "新增"]
       [antd/button {:icon (r/as-element [:> DownloadOutlined])
                     :on-click #(api/export-configs {})}
        "导出"]]
      [antd/table {:rowKey "config_id" :loading loading? :scroll #js {:x 800}
                   :columns (config-columns
                             #(do (set-editing! %) (set-modal-visible! true))
                             #(rf/dispatch [:configs/delete %]))
                   :dataSource (clj->js items)
                   :pagination {:pageSize 10 :total total
                                :show-total (fn [t] (str "共 " t " 条"))}}]]
     [config-modal
      {:visible? modal-visible?
       :editing editing
       :on-ok (fn [form]
                (if (:config_id form)
                  (rf/dispatch [:configs/update (:config_id form) form])
                  (rf/dispatch [:configs/create form]))
                (set-modal-visible! false)
                (set-editing! nil))
       :on-cancel #(do (set-modal-visible! false) (set-editing! nil))}]]))
