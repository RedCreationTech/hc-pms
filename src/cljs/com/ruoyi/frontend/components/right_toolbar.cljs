(ns com.ruoyi.frontend.components.right-toolbar
  "右侧工具按钮组。"
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-toolbar :as toolbar]
    [reagent.core :as r]))


(defn right-toolbar
  [{:keys [show-search? columns on-toggle-search on-refresh on-toggle-column]}]
  [toolbar/toolbar-right
   [toolbar/round-tool-button {:title (if show-search? "隐藏搜索" "显示搜索")
                               :icon (r/as-element [antd/search-icon])
                               :on-click on-toggle-search}]
   [toolbar/round-tool-button {:title "刷新"
                               :icon (r/as-element [antd/reload-icon])
                               :on-click on-refresh}]
   (when (seq columns)
     [antd/tooltip {:title "显隐列"}
      [antd/dropdown {:menu {:items (clj->js
                                      (map (fn [[key {:keys [label visible?]}]]
                                             {:key (name key)
                                              :label (r/as-element
                                                       [:div {:style {:display "flex"
                                                                      :justifyContent "space-between"
                                                                      :alignItems "center"
                                                                      :width 120}}
                                                        [:span label]
                                                        [antd/switch {:size "small"
                                                                      :checked visible?}]])})
                                           columns))
                             :onClick (fn [e]
                                        (let [key (keyword (.-key e))]
                                          (on-toggle-column key)))}
                      :trigger #js ["click"]}
       [antd/button {:shape "circle"
                     :icon (r/as-element [antd/setting-icon])
                     :style {:width 38
                             :height 38
                             :display "inline-flex"
                             :alignItems "center"
                             :justifyContent "center"
                             :color "#606266"
                             :border "1px solid #dcdfe6"
                             :boxShadow "0 2px 8px rgba(0,0,0,0.06)"}}]]])])
