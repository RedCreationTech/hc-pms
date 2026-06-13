(ns com.ruoyi.frontend.app
  "前端应用入口。"
  (:require
   [reagent.core :as r]
   [reagent.dom.client :as rdc]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["antd" :refer [ConfigProvider]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.events]
   [com.ruoyi.frontend.subs]
   [com.ruoyi.frontend.theme :as theme]
   [com.ruoyi.frontend.router :as router]
   [com.ruoyi.frontend.pages.login :as login]
   [com.ruoyi.frontend.pages.layout :as layout]))

(defonce root (atom nil))

(defn- message-init []
  (antd/use-app-message)
  nil)

(defn- current-page []
  (let [page @(rf/subscribe [:page])
        logged-in? @(rf/subscribe [:auth/logged-in?])
        theme-mode @(rf/subscribe [:theme/mode])
        primary-color @(rf/subscribe [:theme/primary-color])
        algorithm @(rf/subscribe [:theme/algorithm])]
    ;; 设置 body 背景色以匹配主题
    (hooks/use-effect
     (fn []
       (let [body (.-body js/document)
             is-dark? (= theme-mode :dark)]
         (set! (.-backgroundColor (.-style body))
               (if is-dark? "#000" "#f5f5f5"))
         js/undefined))
     [theme-mode])
    (if logged-in?
      [:> ConfigProvider {:theme (theme/theme-config
                                  {:mode theme-mode
                                   :primary-color primary-color
                                   :algorithm algorithm})}
       [antd/app
        [message-init]
        [layout/main-layout]]]
      [login/login-page])))

(defn app []
  [current-page])

(defn init []
  (rf/dispatch-sync [:initialize-db])
  ;; 从 localStorage 加载主题设置
  (rf/dispatch [:theme/load-from-storage])
  ;; 先在渲染前初始化路由（只 configure，不 dispatch）
  (router/init-routes!)
  ;; 如果 localStorage 中有 token，获取用户信息
  (when-let [token (try (.getItem js/localStorage "ruoyi_token") (catch js/Error _ nil))]
    (rf/dispatch [:auth/fetch-info]))
  (r/set-default-compiler! (r/create-compiler {:function-components true}))
  (let [container (.getElementById js/document "app")]
    (reset! root (rdc/create-root container))
    (rdc/render @root [app])))

(defn reload []
  (when @root
    (rdc/render @root [app])))
