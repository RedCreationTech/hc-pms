(ns com.ruoyi.frontend.pages.pms.search
  "全局检索 页面 (占位, 后续增量实现)."
  (:require
    [com.ruoyi.frontend.pages.pms.shared :as shared]))


(defn search-page
  "全局检索."
  []
  [:div {:style {:padding 24}}
   [shared/page-heading "PROJECT MANAGEMENT" "全局检索" "跨项目按权限与密级检索对象" nil]
   [shared/empty-state "本页面正在建设中" nil]])
