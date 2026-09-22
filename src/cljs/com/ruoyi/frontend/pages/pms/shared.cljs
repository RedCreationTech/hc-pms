(ns com.ruoyi.frontend.pages.pms.shared
  "PMS 共用主题,请求与反馈组件."
  (:require
    ["antd" :refer [theme]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [re-frame.core :as rf]
    [reagent.hooks :as hooks]))

(def statuses
  [{:value "draft" :label "草稿" :color "default"}
   {:value "initiated" :label "已立项" :color "blue"}
   {:value "planning" :label "计划中" :color "cyan"}
   {:value "execution" :label "执行中" :color "processing"}
   {:value "paused" :label "已暂停" :color "orange"}
   {:value "closing" :label "收尾中" :color "purple"}
   {:value "closed" :label "已结项" :color "green"}
   {:value "cancelled" :label "已取消" :color "red"}])

(def project-types
  [{:value "equipment" :label "单机设备"}
   {:value "line" :label "整线工程"}
   {:value "service" :label "技术服务"}])

(defn use-colors
  "读取当前 Ant Design 主题,保持亮暗模式一致."
  []
  (let [token (.-token (.useToken theme))]
    {:primary (.-colorPrimary token) :text (.-colorText token)
     :muted (.-colorTextSecondary token) :border (.-colorBorderSecondary token)
     :bg (.-colorBgContainer token) :soft (.-colorFillAlter token)
     :accent (.-colorInfoBg token) :danger (.-colorError token)}))

(defn use-permission
  "沿用认证信息中的按钮权限."
  [permission]
  (let [auth @(rf/subscribe [:auth/user])
        permissions (set (:permissions auth))]
    (boolean (or (contains? (set (:roles auth)) "admin")
                 (contains? permissions "*:*:*")
                 (contains? permissions permission)))))

(defn error-message
  "提取可读的服务器错误."
  [error]
  (or (:msg error) (get-in error [:response :msg])
      (:status-text error) "请求失败,请检查网络后重试"))

(defn request!
  "统一解包 PMS 业务响应."
  [method path params success failure]
  (api/pms-request method path params
    (fn [response]
      (if (= 200 (:code response))
        (success (:data response))
        (failure (error-message response))))
    #(failure (error-message %))))

(defn use-resource
  "加载服务端资源并忽略已卸载组件的迟到响应,nil路径不发出请求."
  [path params dependencies]
  (let [[state set-state!] (hooks/use-state {:loading? true})
        [revision set-revision!] (hooks/use-state 0)]
    (hooks/use-effect
      (fn []
        (let [active? (volatile! true)]
          (if path
            (do (set-state! #(assoc % :loading? true :error nil))
                (request! :get path params
                  #(when @active? (set-state! {:data % :loading? false}))
                  #(when @active? (set-state! {:error % :loading? false}))))
            (set-state! {:data nil :loading? false}))
          #(vreset! active? false)))
      (into [path revision] dependencies))
    (assoc state :refresh! #(set-revision! inc))))

(defn use-action
  "提交修改并保留错误信息,成功后刷新调用方资源."
  [on-success]
  (let [[state set-state!] (hooks/use-state {:busy? false})]
    (assoc state :run!
      (fn [method path params message]
        (set-state! {:busy? true})
        (request! method path params
          (fn [data]
            (set-state! {:busy? false})
            (antd/success! message)
            (on-success data))
          #(set-state! {:busy? false :error %}))))))

(defn status-tag
  "显示统一的生命周期状态."
  [status]
  (let [item (some #(when (= status (:value %)) %) statuses)]
    [antd/tag {:color (:color item "default") :style {:marginInlineEnd 0}}
     (:label item (or status "未设置"))]))

(defn panel
  "带轻边框的内容面板."
  [title subtitle actions & children]
  (let [colors (use-colors)]
    [:section {:style {:border (str "1px solid " (:border colors))
                      :background (:bg colors) :borderRadius 10 :overflow "hidden"}}
     [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                    :gap 16 :padding "18px 22px" :borderBottom (str "1px solid " (:border colors))}}
      [:div [:h3 {:style {:fontSize 15 :margin 0 :fontWeight 650}} title]
       (when subtitle [:div {:style {:fontSize 12 :marginTop 4 :color (:muted colors)}} subtitle])]
      actions]
     (into [:div {:style {:padding 22}}] children)]))

(defn error-panel
  "显示明确错误并提供重试入口."
  [message retry!]
  (let [colors (use-colors)]
    [:div {:role "alert" :style {:padding 16 :borderRadius 8 :color (:danger colors)
                                 :border (str "1px solid " (:border colors)) :marginBottom 16}}
     [:span message]
     (when retry! [antd/button {:type "link" :on-click retry!} "重新加载"])]))

(defn page-heading
  "项目页面的标题与上下文."
  [eyebrow title subtitle actions]
  (let [colors (use-colors)]
    [:header {:style {:display "flex" :flexWrap "wrap" :gap 20 :justifyContent "space-between"
                     :alignItems "center" :marginBottom 28}}
     [:div
      [:div {:style {:fontSize 11 :fontWeight 700 :letterSpacing 2 :color (:primary colors)
                     :marginBottom 8}} eyebrow]
      [:h1 {:style {:fontSize 28 :lineHeight 1.3 :fontWeight 650 :margin "0 0 8px"}} title]
      [:p {:style {:margin 0 :color (:muted colors) :fontSize 13}} subtitle]]
     actions]))

(defn empty-state
  "展示无数据时的操作引导."
  [description action]
  [antd/empty-component {:description description :style {:padding "32px 0"}} action])

(defn display-value
  "未录入字段统一显示占位符."
  [value]
  (if (or (nil? value) (= "" value)) "—" value))
