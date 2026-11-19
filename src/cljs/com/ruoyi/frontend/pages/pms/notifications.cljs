(ns com.ruoyi.frontend.pages.pms.notifications
  "我的通知 (C11): 授权收件人的本地通知收件箱, 支持标为已读, 已处理与作废, 以及投递失败后重试.
   只投本地不投外部消息; 失去项目访问权后服务端即不再返回该通知, 页面如实显示为空."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def nature-labels
  {"overdue" "逾期提醒" "due-soon" "即将到期" "pending-review" "待我确认" "escalation" "升级待处理"})


(def status-labels
  {"unread" "未读" "read" "已读" "handled" "已处理" "dismissed" "已作废"})


(def status-colors
  {"unread" "red" "read" "blue" "handled" "green" "dismissed" "default"})


(defn- columns
  "通知列定义; 操作按钮按当前状态给出可执行的下一步."
  [act! busy?]
  [{:title "性质" :dataIndex "nature" :width 110
    :render (fn [v] (r/as-element [antd/tag (get nature-labels v v)]))}
   {:title "项目" :dataIndex "project_name" :width 200}
   {:title "提醒内容" :dataIndex "title" :width 320}
   {:title "到期日" :dataIndex "due_date" :width 110}
   {:title "产生日" :dataIndex "raised_on" :width 110}
   {:title "状态" :dataIndex "status" :width 100
    :render (fn [v] (r/as-element [antd/tag {:color (get status-colors v "default")} (get status-labels v v)]))}
   {:title "投递" :dataIndex "delivery" :width 100
    :render (fn [v] (r/as-element (if (= "failed" v) [antd/tag {:color "red"} "投递失败"] [antd/tag "本地"])))}
   {:title "操作" :width 240
    :render (fn [_ row]
              (let [state (aget row "status")]
                (r/as-element
                  [:div {:style {:display "flex" :gap 6}}
                   (when (= "unread" state)
                     [antd/button {:size "small" :disabled busy? :on-click #(act! (aget row "id") "read")} "标为已读"])
                   (when (#{"unread" "read"} state)
                     [antd/button {:size "small" :type "primary" :disabled busy? :on-click #(act! (aget row "id") "handled")} "已处理"])
                   (when (not= "handled" state)
                     [antd/button {:size "small" :type "text" :disabled busy? :on-click #(act! (aget row "id") "dismissed")} "作废"])])))}])


(defn- summary-row
  "收件箱汇总: 未读/已读/已处理/已作废与本地投递失败数."
  [{:keys [summary delivery]}]
  [antd/space {:size 24 :wrap true}
   [antd/tag {:color "red"} (str "未读 " (:unread summary 0))]
   [antd/tag {:color "blue"} (str "已读 " (:read summary 0))]
   [antd/tag {:color "green"} (str "已处理 " (:handled summary 0))]
   [antd/tag (str "已作废 " (:dismissed summary 0))]
   (if (pos? (:failed delivery 0))
     [antd/tag {:color "orange"} (str "投递失败 " (:failed delivery 0))]
     [antd/tag {:color "green"} "全部本地投递成功"])])


(defn- inbox-panel
  "收件箱表格与筛选; data 为 /notifications 返回的完整载荷."
  [data status set-status! busy? act! retry!]
  [shared/panel "通知收件箱"
   "同一事件对同一收件人只保留一条; 重试投递不会产生重复通知"
   [:div {:style {:display "flex" :gap 8}}
    [antd/select {:style {:width 140} :allowClear true :placeholder "全部状态" :value status
                  :options (clj->js (mapv (fn [[k v]] {:value k :label v}) status-labels))
                  :onChange set-status!}]
    (when (pos? (get-in data [:summary :failed] 0))
      [antd/button {:type "primary" :disabled busy? :on-click retry!} "重试失败投递"])]
   [antd/table {:size "small" :pagination false :rowKey "id"
                :columns (clj->js (columns act! busy?))
                :dataSource (clj->js (vec (:rows data)))
                :locale {:emptyText "暂无通知, 每日扫描或手动扫描后在此呈现"}}]])


(defn notifications-page
  "我的通知页."
  []
  (let [[status set-status!] (hooks/use-state nil)
        params (cond-> {} status (assoc :status status))
        resource (shared/use-resource "/notifications" params [status])
        [error set-error!] (hooks/use-state nil)
        [busy? set-busy!] (hooks/use-state false)
        refresh! (:refresh! resource)
        act! (fn [id next]
               (set-busy! true)
               (shared/request! :post (str "/notifications/" id "/status") {:status next}
                                (fn [_] (set-busy! false) (set-error! nil) (refresh!))
                                (fn [msg] (set-busy! false) (set-error! msg))))
        retry! (fn []
                 (set-busy! true)
                 (shared/request! :post "/notifications/retry" {}
                                  (fn [_] (set-busy! false) (set-error! nil)
                                       (antd/success! "已重试本地投递") (refresh!))
                                  (fn [msg] (set-busy! false) (set-error! msg))))]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "我的通知"
      "按项目授权投递到本人的本地通知: 逾期与即将到期事项, 待我确认与升级待处理; 只投本地, 撤权后立即不再返回" nil]
     (when error
       [antd/alert {:type "error" :show-icon true :message error :style {:marginBottom 16}}])
     [w/resource-view resource
      (fn [data]
        [:div
         [summary-row data]
         [:div {:style {:height 12}}]
         [inbox-panel data status set-status! busy? act! retry!]])]]))