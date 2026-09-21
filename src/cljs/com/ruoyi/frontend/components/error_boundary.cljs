(ns com.ruoyi.frontend.components.error-boundary
  "React Error Boundary：捕获子组件渲染错误，避免整个应用白屏。"
  (:require
    [reagent.core :as r]))


(defn- fallback-ui
  [error _info]
  [:div {:style {:padding 48
                 :textAlign "center"
                 :background "var(--ant-color-bg-container, #fff)"
                 :borderRadius 8
                 :boxShadow "0 2px 8px rgba(0,0,0,0.06)"}}
   [:div {:style {:fontSize 48 :marginBottom 16}} "⚠️"]
   [:h2 {:style {:margin "0 0 16px" :color "#262626"}} "页面加载失败"]
   [:p {:style {:color "#595959" :marginBottom 24 :lineHeight "1.6"}}
    "系统遇到意外错误，请刷新页面或切换菜单重试。"]
   [:button {:onClick #(.reload js/location)
             :style {:padding "8px 16px"
                     :background "#1677ff"
                     :color "#fff"
                     :border "none"
                     :borderRadius 4
                     :cursor "pointer"
                     :fontSize 14}}
    "刷新页面"]
   (when js/goog.DEBUG
     [:details {:style {:marginTop 24 :textAlign "left"}}
      [:summary {:style {:cursor "pointer" :color "#1677ff"}} "错误详情"]
      [:pre {:style {:background "#f5f5f5"
                     :padding 12
                     :borderRadius 4
                     :overflow "auto"
                     :fontSize 12
                     :maxHeight 200}}
       (str error)]])])


(def boundary
  "React 错误边界组件.用法:[boundary child]"
  (r/create-class
    {:get-initial-state
     (fn [_this] nil)
     :component-did-catch
     (fn [this error info]
       (js/console.error "ErrorBoundary caught error:" error info "STACK=" (when error (.-stack error)))
       (r/set-state this {:error error :info info}))
     :reagent-render
     (fn [child]
       (let [this (r/current-component)
             st (r/state this)]
         (if (:error st)
           (r/as-element [fallback-ui (:error st) (:info st)])
           (r/as-element child))))}))
