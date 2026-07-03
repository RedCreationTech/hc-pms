(ns com.ruoyi.frontend.app
  "前端应用入口。"
  (:require
   [reagent.core :as r]
   [reagent.dom.client :as rdc]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["antd" :refer [ConfigProvider]]
   ["antd/locale/zh_CN" :default zh-CN]
   ["dayjs" :as dayjs]
   ["dayjs/locale/zh-cn"]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.error-boundary :as error-boundary]
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
        algorithm @(rf/subscribe [:theme/algorithm])
        component-size @(rf/subscribe [:theme/component-size])
        font-size @(rf/subscribe [:theme/font-size])]
    ;; 设置 body 背景色与 dark 类以匹配主题
    (hooks/use-effect
     (fn []
       (let [body (.-body js/document)
             is-dark? (= theme-mode :dark)]
         (set! (.-backgroundColor (.-style body))
               (if is-dark? "#000" "#f5f5f5"))
         (when body
           (if is-dark?
             (.add (.-classList body) "dark")
             (.remove (.-classList body) "dark")))
         js/undefined))
     [theme-mode])
    ;; 确保日期、时间等组件使用中文
    (hooks/use-effect
     (fn []
       (.locale dayjs "zh-cn")
       js/undefined)
     [])
    [:> ConfigProvider {:theme (theme/theme-config
                                {:mode theme-mode
                                 :primary-color primary-color
                                 :algorithm algorithm
                                 :font-size font-size})
                        :componentSize component-size
                        :locale zh-CN}
     [antd/app
      [message-init]
      (if logged-in?
        [layout/main-layout]
        [login/login-page])]]))

(defn app []
  [error-boundary/boundary [current-page]])

(defn init []
  (rf/dispatch-sync [:initialize-db])
  ;; 从 localStorage 读取主题模式并在初始化时同步应用
  (let [stored-mode (when js/localStorage (.getItem js/localStorage "ruoyi-theme-mode"))
        mode (if stored-mode (keyword stored-mode) :light)
        is-dark? (= mode :dark)]
    (rf/dispatch-sync [:theme/set-mode mode])
    ;; 给 body 添加/移除 dark 类，供全局 CSS 选择器使用
    (when-let [body (.-body js/document)]
      (if is-dark?
        (.add (.-classList body) "dark")
        (.remove (.-classList body) "dark"))))
  ;; 加载其他主题/布局设置
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
