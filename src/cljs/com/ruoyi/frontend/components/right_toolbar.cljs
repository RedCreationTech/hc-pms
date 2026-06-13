(ns com.ruoyi.frontend.components.right-toolbar
  "右侧工具按钮组。"
  (:require
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]))

(defn right-toolbar [{:keys [show-search? columns on-toggle-search on-refresh on-toggle-column]}]
  [antd/space
   [antd/tooltip {:title (if show-search? "隐藏搜索" "显示搜索")}
    [antd/button {:icon (r/as-element [antd/search-icon])
                  :size "small"
                  :type (if show-search? "primary" "default")
                  :on-click on-toggle-search}]]
   [antd/tooltip {:title "刷新"}
    [antd/button {:icon (r/as-element [antd/reload-icon])
                  :size "small"
                  :on-click on-refresh}]]
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
     [antd/button {:icon (r/as-element [antd/setting-icon])
                   :size "small"}]]]])
