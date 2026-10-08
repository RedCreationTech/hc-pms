(ns com.ruoyi.frontend.pages.pms.external-interfaces
  "外部接口配置 (只读目录): 逐项登记仍依赖真实外部系统合同/规则的能力, 供集成配置人员对照准备接口字段, 认证, 责任人与测试环境. 数据随代码发布, 不查库, 不落库, 不做连通性测试, 不代表任何真实集成已通过."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [reagent.core :as r]))


(def group-labels {"adapter" "系统适配器" "business" "业务对接点"})
(def group-colors {"adapter" "blue" "business" "geekblue"})
(def status-colors {"待合同" "orange" "待规则" "purple"})


(defn- metric
  [label value color]
  [:div {:style {:padding "16px 20px" :borderRadius 10 :border "1px solid #e4e8ee" :minWidth 130}}
   [:div {:style {:fontSize 12 :color "#718096"}} label]
   [:div {:style {:fontSize 30 :fontWeight 650 :color (or color "#1f2937")}} value]])


(defn- fields-cell
  [value]
  (r/as-element
    (into [antd/space {:wrap true :size 4}]
          (map (fn [f] [antd/tag {:style {:marginInlineEnd 0}} f]) (js->clj value)))))


(defn- columns
  []
  [{:title "能力" :dataIndex "capability" :width 210
    :render (fn [v row] (r/as-element [:div {:style {:fontWeight 600}} v
                                       [:div {:style {:fontSize 11 :color "#8793a3" :fontWeight 400}} (aget row "key")]]))}
   {:title "外部系统" :dataIndex "system" :width 130}
   {:title "归类" :dataIndex "group" :width 120
    :render (fn [v] (r/as-element [antd/tag {:color (get group-colors v "default")} (get group-labels v v)]))}
   {:title "方向" :dataIndex "direction" :width 80}
   {:title "矩阵行" :dataIndex "matrix-rows" :width 160
    :render (fn [v] (r/as-element [:span {:style {:fontSize 12 :color "#5b6675"}} v]))}
   {:title "所需接口字段" :dataIndex "required-fields" :width 300 :render fields-cell}
   {:title "责任方" :dataIndex "owner" :width 180
    :render (fn [v] (r/as-element [:span {:style {:fontSize 12}} v]))}
   {:title "依赖状态" :dataIndex "status" :width 100
    :render (fn [v] (r/as-element [antd/tag {:color (get status-colors v "default")} v]))}
   {:title "说明" :dataIndex "notes" :width 320
    :render (fn [v] (r/as-element [:span {:style {:fontSize 12 :color "#5b6675"}} v]))}])


(defn external-interfaces-page
  "外部接口配置只读目录页."
  []
  (let [resource (shared/use-resource "/external-interfaces" {} [])]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "外部接口配置"
      "逐项登记仍依赖真实外部系统合同/规则的能力, 供集成配置人员对照准备接口字段, 认证, 责任人与测试环境; 只读静态清单, 不查库, 不落库, 不做连通性测试"
      [antd/button {:on-click #((:refresh! resource))} "刷新"]]
     (cond
       (:error resource) [shared/error-panel (:error resource) (:refresh! resource)]
       (and (:loading? resource) (nil? (:data resource))) [antd/spin]
       :else
       (let [data (:data resource)]
         [:div {:style {:display "grid" :gap 20}}
          [:div {:style {:display "flex" :gap 12 :flexWrap "wrap"}}
           [metric "能力总数" (:total data) nil]
           [metric "系统适配器" (:adapter-count data) "#1d4ed8"]
           [metric "业务对接点" (:business-count data) "#08979c"]
           [metric "待合同" (:contract-count data) "#d46b08"]
           [metric "待规则" (:rule-count data) "#722ed1"]]
          [shared/panel "依赖外部系统的能力清单" (:note data) nil
           [antd/table {:rowKey "key" :size "small" :pagination false :scroll {:x 1720}
                        :dataSource (clj->js (:rows data)) :columns (clj->js (columns))}]]]))]))
