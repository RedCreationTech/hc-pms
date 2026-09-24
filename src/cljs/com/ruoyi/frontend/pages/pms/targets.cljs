(ns com.ruoyi.frontend.pages.pms.targets
  "经营目标看板 页面 (占位, 后续增量实现)."
  (:require
    [com.ruoyi.frontend.pages.pms.shared :as shared]))


(defn targets-page
  "经营目标看板."
  []
  [:div {:style {:padding 24}}
   [shared/page-heading "PROJECT MANAGEMENT" "经营目标看板" "季度经营目标与达成" nil]
   [shared/empty-state "本页面正在建设中" nil]])
