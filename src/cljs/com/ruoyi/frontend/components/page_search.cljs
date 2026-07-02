(ns com.ruoyi.frontend.components.page-search
  "可折叠搜索区容器。"
  (:require
   [reagent.hooks :as hooks]))

(defn page-search [{:keys [visible?]} & children]
  (let [form-ref (hooks/use-ref nil)
        [manual-visible? set-manual-visible!] (hooks/use-state true)
        effective-visible? (and visible? manual-visible?)
        [height set-height!] (hooks/use-state (if effective-visible? "auto" "0px"))]
    (hooks/use-effect
     (fn []
       (let [handler (fn [_]
                       (set-manual-visible! (not manual-visible?)))]
         (.addEventListener js/window "ruoyi-toggle-search" handler)
         (fn [] (.removeEventListener js/window "ruoyi-toggle-search" handler))))
     [manual-visible?])
    (hooks/use-effect
     (fn []
       (if effective-visible?
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
     [effective-visible?])
    [:div {:ref form-ref
           :className "ruoyi-page-search"
           :style {:overflow "hidden"
                   :height height
                   :opacity (if effective-visible? 1 0)
                   :transition "height 0.3s ease, opacity 0.3s ease"}}
     (into [:div {:style {:background "transparent"
                          :padding "12px 22px 6px 22px"}}]
           children)]))

(defn search-row [& children]
  (into [:div {:style {:display "flex"
                       :flexWrap "wrap"
                       :columnGap 18
                       :rowGap 8
                       :alignItems "center"}}]
        children))

(defn search-item
  ([label child] (search-item label child {}))
  ([label child {:keys [label-width width]}]
   [:div {:style {:display "flex"
                  :alignItems "center"
                  :gap 8
                  :width (or width 340)}}
    [:span {:style {:whiteSpace "nowrap"
                    :fontSize 14
                    :fontWeight 700
                    :color "var(--ant-color-text-secondary, #606266)"
                    :width (or label-width 68)
                    :textAlign "right"}}
     label]
    child]))

(def input-style {:width 260 :height 34 :borderRadius 4})
(def select-style {:width 260 :height 34})

(defn search-actions [& children]
  (into [:div {:style {:display "flex"
                       :gap 8
                       :alignItems "center"}}]
        children))
