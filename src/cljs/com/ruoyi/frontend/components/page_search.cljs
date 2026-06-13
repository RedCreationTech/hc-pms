(ns com.ruoyi.frontend.components.page-search
  "可折叠搜索区容器。"
  (:require
   [reagent.hooks :as hooks]))

(defn page-search [{:keys [visible?]} & children]
  (let [form-ref (hooks/use-ref nil)
        [height set-height!] (hooks/use-state (if visible? "auto" "0px"))]
    (hooks/use-effect
     (fn []
       (if visible?
         (when-let [el (.-current form-ref)]
           (set-height! "0px")
           (js/setTimeout
            (fn [] (set-height! (str (.-scrollHeight el) "px")))
            10)
           (js/setTimeout
            (fn [] (set-height! "auto"))
            320))
         (do (when-let [el (.-current form-ref)]
               (set-height! (str (.-scrollHeight el) "px"))
               (js/setTimeout
                (fn [] (set-height! "0px"))
                10))))
       js/undefined)
     [visible?])
    [:div {:ref form-ref
           :style {:overflow "hidden"
                   :height height
                   :opacity (if visible? 1 0)
                   :transition "height 0.3s ease, opacity 0.3s ease"}}
     (into [:div {:style {:background "var(--ant-color-bg-container, #fff)"
                          :padding 16
                          :marginBottom 12
                          :borderRadius 8
                          :border "1px solid var(--ant-color-border-secondary, #e8e8e8)"}}]
           children)]))
