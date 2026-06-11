(ns com.ruoyi.rouyi.frontend.app
  "前端应用入口。"
  (:require
    [reagent.core :as r]
    [reagent.dom.client :as rdc]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.events]
    [com.ruoyi.rouyi.frontend.subs]
    [com.ruoyi.rouyi.frontend.pages.login :as login]
    [com.ruoyi.rouyi.frontend.pages.layout :as layout]))

(defonce root (atom nil))

(defn- current-page []
  (let [page @(rf/subscribe [:page])
        logged-in? @(rf/subscribe [:auth/logged-in?])]
    (if logged-in?
      [layout/main-layout]
      [login/login-page])))

(defn app []
  [current-page])

(defn init []
  (rf/dispatch-sync [:initialize-db])
  (r/set-default-compiler! (r/create-compiler {:function-components true}))
  (let [container (.getElementById js/document "app")]
    (reset! root (rdc/create-root container))
    (rdc/render @root [app])))

(defn reload []
  (when @root
    (rdc/render @root [app])))
