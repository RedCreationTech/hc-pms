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
     (into [:div {:style {:background "#fff"
                          :padding "8px 22px 4px 22px"}}]
           children)]))

(defn search-row [& children]
  (into [:div {:style {:display "flex"
                       :flexWrap "wrap"
                       :columnGap 24
                       :rowGap 8
                       :alignItems "center"}}]
        children))

(defn search-item
  ([label child] (search-item label child {}))
  ([label child {:keys [label-width width]}]
   [:div {:style {:display "flex"
                  :alignItems "center"
                  :gap 8
                  :width (or width 300)}}
    [:span {:style {:whiteSpace "nowrap"
                    :fontSize 14
                    :fontWeight 600
                    :color "#606266"
                    :width (or label-width 58)
                    :textAlign "right"}}
     label]
    child]))

(def input-style {:width 232 :height 34 :borderRadius 4})
(def select-style {:width 232 :height 34})

(defn search-actions [& children]
  (into [:div {:style {:display "flex"
                       :gap 8
                       :alignItems "center"}}]
        children))
