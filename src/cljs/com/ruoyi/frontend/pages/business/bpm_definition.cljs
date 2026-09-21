(ns com.ruoyi.frontend.pages.business.bpm-definition
  "流程定义版本 -- 某模型 key 的全部历史部署版本(查看 XML / 恢复回模型)."
  (:require
    ["@ant-design/icons" :refer [ReloadOutlined EyeOutlined UndoOutlined
                                 ArrowLeftOutlined]]
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn- version-columns
  []
  #js [#js {:title "定义ID" :dataIndex "id" :key "id" :width 150 :ellipsis true}
       #js {:title "流程名称" :dataIndex "name" :key "name" :width 150}
       #js {:title "分类" :key "category" :width 110
            :render (fn [_ ^js record]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                          (if-let [cn (:category_name row)]
                            [antd/tag {:color "geekblue"} cn]
                            [:span {:style {:color "#c0c4cc"}} "-"]))))}
       #js {:title "发起权限" :key "start-perm" :width 160
            :render (fn [_ ^js record]
                      (let [row (js->clj record :keywordize-keys true)
                            users (:start_users row)]
                        (r/as-element
                          (if (seq users)
                            [antd/tag {:color "purple"}
                             (str (clojure.string/join "," (take 3 users))
                                  (when (> (count users) 3) (str " 等" (count users) "人")))]
                            [:span {:style {:color "#909399" :fontSize 12}} "全部"]))))}
       #js {:title "版本" :dataIndex "version" :key "version" :width 70
            :render (fn [v] (r/as-element [antd/tag {:color "blue"} (str "v" v)]))}
       #js {:title "状态" :dataIndex "suspended?" :key "suspended?" :width 90
            :render (fn [v]
                      (r/as-element
                        (if v
                          [antd/tag {:color "red"} "已挂起"]
                          [antd/tag {:color "green"} "激活中"])))}
       #js {:title "部署ID" :dataIndex "deployment-id" :key "deployment-id" :width 100 :ellipsis true}
       #js {:title "部署时间" :dataIndex "deploy-time" :key "deploy-time" :width 170
            :render (fn [v] (r/as-element [:span (if v (subs (str v) 0 19) "-")]))}
       #js {:title "绑定表单" :key "form" :width 120
            :render (fn [_ ^js record]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                          (if-let [fname (:form_name row)]
                            [antd/tag {:color "green"} fname]
                            [:span {:style {:color "#c0c4cc"}} "-"]))))}
       #js {:title "操作" :key "action" :width 170
            :render (fn [_ ^js record]
                      (let [row (js->clj record :keywordize-keys true)]
                        (r/as-element
                          [antd/space
                           [antd/button {:type "link" :size "small"
                                         :icon (r/as-element [:> EyeOutlined])
                                         :on-click #(rf/dispatch [:bpm/definition-xml-open (:id row)])}
                            "查看XML"]
                           [antd/popconfirm {:title (str "确认把 v" (:version row) " 恢复回模型？将覆盖当前模型 BPMN")
                                             :on-confirm #(rf/dispatch [:bpm/definition-restore (:id row)])}
                            [antd/button {:type "link" :size "small"
                                          :icon (r/as-element [:> UndoOutlined])}
                             "恢复"]]])))}])


(defn- xml-modal
  []
  (let [open? @(rf/subscribe [:bpm-definition/xml-open?])
        xml @(rf/subscribe [:bpm-definition/xml])
        loading? @(rf/subscribe [:bpm-definition/xml-loading?])]
    [antd/modal {:title "流程定义 BPMN XML"
                 :open open? :width 900 :footer nil
                 :onCancel #(rf/dispatch [:bpm/definition-xml-close])}
     (if loading?
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:pre {:style {:background "#f6f8fa" :padding 12 :borderRadius 4 :maxHeight 520
                      :overflow "auto" :fontSize 12 :lineHeight "1.6"}}
        [:code {:style {:fontFamily "monospace" :whiteSpace "pre-wrap" :wordBreak "break-all"}}
         xml]])]))


(defn bpm-definition-page
  []
  (let [model @(rf/subscribe [:bpm-definition/model])
        items @(rf/subscribe [:bpm-definition/items])
        total @(rf/subscribe [:bpm-definition/total])
        loading? @(rf/subscribe [:bpm-definition/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [antd/button {:type "link" :size "small"
                            :icon (r/as-element [:> ArrowLeftOutlined])
                            :on-click #(rf/dispatch [:navigate :bpm-model])}]
              [:div {:style {:fontSize 15 :fontWeight 600}}
               (str "流程定义版本 · " (:model_name model) "（" (:model_key model) "）")]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:bpm/definition-fetch {}])}]]}]
     [antd/table {:rowKey "id" :columns (version-columns)
                  :dataSource (clj->js items) :loading loading?
                  :pagination {:total total :pageSize 10 :showSizeChanger true
                               :showTotal (fn [t] (str "共 " t " 条"))
                               :onChange (fn [page size]
                                           (rf/dispatch [:bpm/definition-fetch {:page page :size size}]))}}]
     [xml-modal]]))
