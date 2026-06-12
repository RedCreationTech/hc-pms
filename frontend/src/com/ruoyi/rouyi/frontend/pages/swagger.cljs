(ns com.ruoyi.rouyi.frontend.pages.swagger
  "系统接口页面 — 嵌入 Swagger UI。"
  (:require
    [reagent.core :as r]))

(defn swagger-page []
  [:div {:style {:height "calc(100vh - 200px)" :background "#fff" :borderRadius 8 :overflow "hidden"}}
   [:iframe {:src "/api"
             :style {:width "100%" :height "100%" :border "none"}
             :title "API 文档"}]])
