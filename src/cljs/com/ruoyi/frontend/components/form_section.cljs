(ns com.ruoyi.frontend.components.form-section
  "表单分组容器，带主色左侧竖条和网格布局。")

(defn form-section [{:keys [title columns]} & children]
  [:div {:style {:marginTop 14}}
   [:div {:style {:borderLeft "3px solid var(--ant-color-primary, #1677ff)"
                  :paddingLeft 10 :marginBottom 10 :fontWeight 600
                  :color "var(--ant-color-text, #1f2937)"}}
    title]
   (into [:div {:style {:display "grid"
                        :gridTemplateColumns (str "repeat(" columns ", minmax(0, 1fr))")
                        :gap "10px 16px"
                        :alignItems "start"}}]
         children)])
