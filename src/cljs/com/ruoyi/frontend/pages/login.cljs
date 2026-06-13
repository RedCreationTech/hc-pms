(ns com.ruoyi.frontend.pages.login
  "登录页面 — 带验证码。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [UserOutlined LockOutlined SafetyOutlined]]
   [com.ruoyi.frontend.antd :as antd]))

(defn login-page []
  (let [initial-uuid (str (random-uuid))
        [username set-username!] (hooks/use-state "admin")
        [password set-password!] (hooks/use-state "admin123")
        [captcha set-captcha!] (hooks/use-state "")
        [captcha-uuid set-captcha-uuid!] (hooks/use-state initial-uuid)
        [captcha-url set-captcha-url!] (hooks/use-state (str "/api/captcha/image?r=" initial-uuid))
        [loading? set-loading!] (hooks/use-state false)
        refresh-captcha (fn []
                          (let [uuid (str (random-uuid))]
                            (set-captcha-uuid! uuid)
                            (set-captcha-url! (str "/api/captcha/image?r=" uuid))))]
    [:div {:style {:display "flex" :justifyContent "center" :alignItems "center"
                   :height "100vh"
                   :background "linear-gradient(135deg, #667eea 0%, #764ba2 100%)"}}
     [:div {:style {:width 420 :borderRadius 12 :overflow "hidden"
                    :boxShadow "0 8px 32px rgba(0,0,0,0.15)"}}
      ;; 顶部标题区域
      [:div {:style {:background "rgba(255,255,255,0.1)" :padding "32px 40px 24px"
                     :textAlign "center" :backdropFilter "blur(10px)"}}
       [:h1 {:style {:margin 0 :fontSize 28 :fontWeight 600 :color "#fff" :letterSpacing 2}}
        "若依管理系统"]
       [:p {:style {:margin "8px 0 0" :fontSize 14 :color "rgba(255,255,255,0.7)"}}
        "Clojure + ClojureScript + Ant Design"]]
      ;; 表单区域
      [:div {:style {:background "#fff" :padding "32px 40px 40px"}}
       ;; 用户名
       [:div {:style {:marginBottom 20}}
        [:div {:style {:position "relative"}}
         [:span {:style {:position "absolute" :left 12 :top "50%" :transform "translateY(-50%)"
                         :color "#bfbfbf" :fontSize 16 :zIndex 1}}
          [:> UserOutlined]]
         [:input {:type "text" :placeholder "用户名" :value username
                  :onChange #(set-username! (-> % .-target .-value))
                  :style {:width "100%" :height 44 :paddingLeft 38 :border "1px solid #d9d9d9"
                          :borderRadius 6 :fontSize 14 :outline "none" :transition "border-color 0.2s"}
                  :onFocus (fn [e] (set! (.. e -target -style -borderColor) "#1677ff"))
                  :onBlur (fn [e] (set! (.. e -target -style -borderColor) "#d9d9d9"))}]]]
       ;; 密码
       [:div {:style {:marginBottom 20}}
        [:div {:style {:position "relative"}}
         [:span {:style {:position "absolute" :left 12 :top "50%" :transform "translateY(-50%)"
                         :color "#bfbfbf" :fontSize 16 :zIndex 1}}
          [:> LockOutlined]]
         [:input {:type "password" :placeholder "密码" :value password
                  :onChange #(set-password! (-> % .-target .-value))
                  :style {:width "100%" :height 44 :paddingLeft 38 :border "1px solid #d9d9d9"
                          :borderRadius 6 :fontSize 14 :outline "none" :transition "border-color 0.2s"}
                  :onFocus (fn [e] (set! (.. e -target -style -borderColor) "#1677ff"))
                  :onBlur (fn [e] (set! (.. e -target -style -borderColor) "#d9d9d9"))}]]]
       ;; 验证码
       [:div {:style {:marginBottom 24}}
        [:div {:style {:display "flex" :gap 12}}
         [:div {:style {:position "relative" :flex 1}}
          [:span {:style {:position "absolute" :left 12 :top "50%" :transform "translateY(-50%)"
                          :color "#bfbfbf" :fontSize 16 :zIndex 1}}
           [:> SafetyOutlined]]
          [:input {:type "text" :placeholder "验证码" :value captcha :maxLength 4
                   :onChange #(set-captcha! (-> % .-target .-value))
                   :style {:width "100%" :height 44 :paddingLeft 38 :border "1px solid #d9d9d9"
                           :borderRadius 6 :fontSize 14 :outline "none" :transition "border-color 0.2s"}
                   :onFocus (fn [e] (set! (.. e -target -style -borderColor) "#1677ff"))
                   :onBlur (fn [e] (set! (.. e -target -style -borderColor) "#d9d9d9"))}]]
         [:img {:src captcha-url :alt "验证码" :onClick refresh-captcha
                :style {:height 44 :cursor "pointer" :borderRadius 6 :border "1px solid #d9d9d9"
                        :userSelect "none"}
                :title "点击刷新验证码"}]]]
       ;; 登录按钮
       [:button {:onClick (fn []
                            (set-loading! true)
                            (rf/dispatch [:auth/login {:username username
                                                       :password password
                                                       :captcha captcha
                                                       :uuid captcha-uuid}]))
                 :disabled loading?
                 :style {:width "100%" :height 44 :background "#1677ff" :color "#fff"
                         :border "none" :borderRadius 6 :fontSize 16 :fontWeight 500
                         :cursor (if loading? "not-allowed" "pointer")
                         :opacity (if loading? 0.65 1)
                         :transition "all 0.2s"}}
        (if loading? "登录中..." "登 录")]]]]))
