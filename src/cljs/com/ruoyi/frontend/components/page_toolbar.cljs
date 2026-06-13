(ns com.ruoyi.frontend.components.page-toolbar
  "页面工具栏容器。")

(defn page-toolbar [{:keys [left right style]}]
  [:div {:style (merge {:display "flex"
                        :justifyContent "space-between"
                        :gap 12
                        :marginBottom 10}
                       style)}
   left
   right])
