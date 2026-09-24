(ns com.ruoyi.frontend.pages.pms.portfolio
  "项目组合看板 页面 (占位, 后续增量实现)."
  (:require
    [com.ruoyi.frontend.pages.pms.shared :as shared]))


(defn portfolio-page
  "项目组合看板."
  []
  [:div {:style {:padding 24}}
   [shared/page-heading "PROJECT MANAGEMENT" "项目组合看板" "多项目组合下钻到主/子/单机与任务证据" nil]
   [shared/empty-state "本页面正在建设中" nil]])
