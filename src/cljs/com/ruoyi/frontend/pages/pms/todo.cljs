(ns com.ruoyi.frontend.pages.pms.todo
  "我的待办 页面 (占位, 后续增量实现)."
  (:require
    [com.ruoyi.frontend.pages.pms.shared :as shared]))


(defn todo-page
  "我的待办."
  []
  [:div {:style {:padding 24}}
   [shared/page-heading "PROJECT MANAGEMENT" "我的待办" "跨项目待审批/到期/逾期事项" nil]
   [shared/empty-state "本页面正在建设中" nil]])
