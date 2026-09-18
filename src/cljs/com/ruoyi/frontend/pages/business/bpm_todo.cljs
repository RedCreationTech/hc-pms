(ns com.ruoyi.frontend.pages.business.bpm-todo
  "我的待办 —— 审批面板（通过/驳回/转办/委派）。"
  (:require
   [clojure.walk :as walk]
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined CheckOutlined CloseOutlined SwapOutlined SendOutlined EyeOutlined UserAddOutlined MailOutlined RollbackOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.form-render :as fr]
   [com.ruoyi.frontend.components.bpm-flow-designer :as bpmfd]))

(defn- task-columns [open-ops open-detail]
  #js [#js {:title "任务" :dataIndex "name" :key "name"}
       #js {:title "流程定义" :dataIndex "process-definition-id" :key "process-definition-id"
            :width 180 :ellipsis true}
       #js {:title "流程实例" :dataIndex "process-instance-id" :key "process-instance-id"
            :width 80 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "创建时间" :dataIndex "create-time" :key "create-time" :width 160}
       #js {:title "操作" :key "action" :width 260
            :render (fn [_ ^js record]
                      (let [task (js->clj record :keywordize-keys true)]
                        (r/as-element
                         [antd/space
                          [antd/button {:size "small"
                                        :icon (r/as-element [:> EyeOutlined])
                                        :on-click #(open-detail task)}
                           "详情"]
                          [antd/button {:type "primary" :size "small"
                                        :icon (r/as-element [:> CheckOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-approve task])}
                           "通过"]
                          [antd/button {:danger true :size "small"
                                        :icon (r/as-element [:> CloseOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-reject task])}
                           "驳回"]
                          [antd/button {:size "small"
                                        :icon (r/as-element [:> SwapOutlined])
                                        :on-click #(open-ops task "transfer")}
                           "转办"]
                          [antd/button {:size "small"
                                        :icon (r/as-element [:> SendOutlined])
                                        :on-click #(open-ops task "delegate")}
                           "委派"]
                          [antd/button {:size "small"
                                        :icon (r/as-element [:> UserAddOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-sign task])}
                           "加签"]
                          [antd/button {:size "small"
                                        :icon (r/as-element [:> MailOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-copy task])}
                           "抄送"]
                          [antd/button {:size "small"
                                        :icon (r/as-element [:> RollbackOutlined])
                                        :on-click #(rf/dispatch [:bpm/todo-open-reject task])}
                           "退回"]])))}])

(defn- comment-item
  [label]
  [antd/form-item {:label label :name "comment"}
   [antd/text-area {:placeholder "请输入意见(可选)" :rows 3}]])

(defn- user-select [users]
  [antd/select {:mode "multiple" :style {:width "100%"}
                :placeholder "请选择用户(可多选)"
                :options (clj->js (mapv (fn [u]
                                          {:value (:user_name u)
                                           :label (str (:nick_name u) " (" (:user_name u) ")")})
                                        users))}])

(defn- sign-children-block [task]
  (let [signs @(rf/subscribe [:bpm-todo/sign-list])]
    (if (empty? signs)
      [:div {:style {:color "#c0c4cc" :padding "4px 0" :marginTop 8}} "暂无加签子任务"]
      [:div {:style {:marginTop 8}}
       [:div {:style {:fontWeight 600 :marginBottom 6}} "加签子任务（可减签）"]
       (doall
        (for [sgn signs]
          ^{:key (:task-id sgn)}
          [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                         :padding "4px 0" :borderBottom "1px solid #f0f0f0"}}
           [:span
            [:b (:assignee sgn)]
            [:span {:style {:color "#909399" :marginLeft 8}}
             (if (= "RUNNING" (:status sgn)) "进行中" "已完成")]]
           (when (= "RUNNING" (:status sgn))
             [antd/popconfirm {:title (str "确认对 " (:assignee sgn) " 减签?")
                               :on-confirm #(rf/dispatch [:bpm/todo-delete-sign (:task-id task) (:assignee sgn)])}
              [antd/button {:danger true :size "small"} "减签"]])]))])))

(defn- approve-modal [users]
  (let [visible? @(rf/subscribe [:bpm-todo/modal-visible?])
        current @(rf/subscribe [:bpm-todo/current])
        action @(rf/subscribe [:bpm-todo/action])
        submitting? @(rf/subscribe [:bpm-todo/submitting?])
        form-data @(rf/subscribe [:bpm-todo/form-data])
        form-loading? @(rf/subscribe [:bpm-todo/form-loading?])
        return-list @(rf/subscribe [:bpm-todo/return-list])
        [form] (antd/form-use-form)
        title (case action
                "approve" "审批通过"
                "reject" "审批驳回"
                "sign" "加签"
                "copy" "抄送"
                "审批")]
    [antd/modal {:title (str title " · " (:name current))
                 :open visible? :confirmLoading submitting? :width 640
                 :onOk #(.submit form)
                 :onCancel #(rf/dispatch [:bpm/todo-close])}
     (when (#{"approve" "reject"} action)
       [:div {:style {:maxHeight 420 :overflow "auto" :marginBottom 12}}
        (if form-loading?
          [:div {:style {:padding 24 :textAlign "center" :color "#909399"}} "表单加载中..."]
          (if-let [form (:form form-data)]
            (let [schema (:schema form)
                  values (or (:values form) {})]
              (if (seq (:fields schema))
                [:div
                 [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                  [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
                  [:span {:style {:fontWeight 600}} "申请表单"]]
                 [fr/form-render {:schema schema :values values :disabled? true
                                   :field-permissions (:fields-permission form-data)}]]
                [:div {:style {:color "#c0c4cc" :textAlign "center" :padding 12}}
                 "该流程未配置动态表单"]))
            [:div {:style {:color "#c0c4cc" :textAlign "center" :padding 12}} "暂无表单数据"]))])
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:bpm/todo-submit (js->clj values :keywordize-keys true)]))}
      (case action
        "reject" [:<>
                 (when (seq return-list)
                   [antd/form-item {:label "退回到节点" :name "return_node_id"
                                    :initialValue (:activity-id (first return-list))}
                    [antd/select {:style {:width "100%"}
                                  :options (clj->js (mapv (fn [n]
                                                            {:value (:activity-id n)
                                                             :label (:activity-name n)})
                                                          return-list))}]])
                 [comment-item "审批意见"]]
        "sign" [:<>
                [antd/form-item {:label "加签人" :name "userIds"
                                 :rules [{:required true :message "请选择加签人"}]}
                 (user-select users)]
                [antd/form-item {:label "加签方式" :name "type" :initialValue "after"}
                 [antd/radio-group {:options (clj->js [{:value "before" :label "前加签"}
                                                       {:value "after" :label "后加签"}])}]]
                [comment-item "加签原因"]
                [sign-children-block current]]
        "copy" [:<>
                [antd/form-item {:label "抄送人" :name "userIds"
                                 :rules [{:required true :message "请选择抄送人"}]}
                 (user-select users)]
                [comment-item "抄送说明"]]
        [comment-item "审批意见"])]]))

(defn- todo-detail-drawer [{:keys [task visible? set-visible! form-data diagram history]}]
  (let [form (:form form-data)
        schema (or (:schema form) {:fields []})
        values (or (:values form) {})
        model (:model form-data)
        task-name (:name task)
        form-block (fn []
                     (if (seq (:fields schema))
                       [:div {:style {:marginBottom 16}}
                        [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                         [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
                         [:span {:style {:fontWeight 600}} "申请表单"]]
                        [fr/form-render {:schema schema :values values :disabled? true
                                         :field-permissions (:fields-permission form-data)}]]
                       [:div {:style {:color "#c0c4cc" :padding "12px 0"}} "该流程未配置动态表单"]))
        diagram-block (fn []
                        [:div {:style {:marginBottom 16}}
                         [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                          [:div {:style {:width 4 :height 16 :background "#e6a23c" :marginRight 8}}]
                          [:span {:style {:fontWeight 600}} "流程图"]]
                         (if-let [mid (:model-id model)]
                           [bpmfd/bpm-flow-designer {:model-id mid :read-only? true
                                                     :active-ids (vec (:active-activity-ids @diagram))
                                                     :completed-ids (vec (:completed-activity-ids @diagram))}]
                           [:div {:style {:color "#c0c4cc"}} "暂无流程图"])])
        history-block (fn []
                        [:div
                         [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
                          [:div {:style {:width 4 :height 16 :background "#67c23a" :marginRight 8}}]
                          [:span {:style {:fontWeight 600}} "审批历史"]]
                         (if (seq @history)
                           (doall
                            (for [t @history]
                              (let [color (if (true? (:approved t)) "#67c23a"
                                              (if (false? (:approved t)) "#f56c6c" "#409eff"))]
                                ^{:key (:task-id t)}
                                [:div {:style {:padding "8px 12px" :borderLeft "3px solid"
                                               :borderColor color :background "#fafafa"
                                               :marginBottom 8 :borderRadius "0 6px 6px 0"}}
                                 [:div {:style {:display "flex" :alignItems "center"}}
                                  [:b (:name t)]
                                  (when (:assignee t)
                                    [:span {:style {:color "#909399" :marginLeft 8}} (:assignee t)])
                                  (when (:end-time t)
                                    [:span {:style {:color "#c0c4cc" :marginLeft 8 :fontSize 12}}
                                     (subs (:end-time t) 0 16)])]
                                 (when (:comment t)
                                   [:div {:style {:color "#606266" :marginTop 2}} (str "意见：" (:comment t))])])))
                           [:div {:style {:color "#c0c4cc" :padding "12px 0"}} "暂无审批记录"])])]
    [antd/drawer {:title (str "待办详情 · " task-name)
                  :open visible? :size 900
                  :onClose #(set-visible! false)}
     [:div
      [antd/descriptions {:column 3 :size "small" :bordered true :style {:marginBottom 16}}
       [antd/descriptions-item {:label "流程模型"} (:model_name model)]
       [antd/descriptions-item {:label "任务"} task-name]
       [antd/descriptions-item {:label "流程实例"} (:process-instance-id task)]]
      [form-block]
      [diagram-block]
      [history-block]]]))

(defn- ops-modal [{:keys [task-type to-user set-to-user! users visible? set-visible!] :as props}]
  (let [op (or (:type props) "transfer")
        title (if (= op "transfer") "转办" "委派")]
    [antd/modal {:title (str title "任务")
                 :open visible? :width 420
                 :onOk #(let [task (:task props)]
                          (when task
                            (let [f (if (= op "transfer") api/bpm-transfer-task api/bpm-delegate-task)]
                              (f (:task-id task) to-user
                                 (fn [_] (set-visible! false) (set-to-user! nil)
                                   (antd/success! (str title "成功"))
                                   (rf/dispatch [:bpm/todo-fetch]))
                                 (fn [_] (antd/error! (str title "失败")))))))
                 :onCancel #(do (set-visible! false) (set-to-user! nil))}
     [:div
      [:div.bpm-f-label (str title "给（用户）")]
      [antd/select {:style {:width "100%"} :placeholder "请选择目标用户" :value to-user
                    :onChange set-to-user!}
       (doall (for [u users] ^{:key (:user_name u)}
                [antd/select-option {:value (:user_name u)} (:nick_name u)]))]]]))

(defn bpm-todo-page []
  (let [items @(rf/subscribe [:bpm-todo/items])
        total @(rf/subscribe [:bpm-todo/total])
        loading? @(rf/subscribe [:bpm-todo/loading?])]
    (r/with-let [ops-visible (r/atom false)
                 ops-task (r/atom nil)
                 ops-type (r/atom "transfer")
                 ops-user (r/atom nil)
                 detail-visible (r/atom false)
                 detail-task (r/atom nil)
                 detail-form (r/atom nil)
                 detail-diagram (r/atom nil)
                 detail-history (r/atom [])
                 users (r/atom [])
                 _ (api/list-users {:page 1 :size 1000}
                                   #(reset! users (walk/keywordize-keys (or (:rows (:data %)) [])))
                                   #())
                 open-ops (fn [task type]
                            (reset! ops-task task)
                            (reset! ops-type type)
                            (reset! ops-user nil)
                            (reset! ops-visible true))
                 open-detail (fn [task]
                               (reset! detail-task task)
                               (reset! detail-form nil)
                               (reset! detail-diagram nil)
                               (reset! detail-history [])
                               (reset! detail-visible true)
                               (api/bpm-task-detail (:task-id task)
                                                    (fn [res] (reset! detail-form (:data res)))
                                                    (fn [_] nil))
                               (api/bpm-instance-diagram (:process-instance-id task)
                                                         (fn [res] (reset! detail-diagram (:data res)))
                                                         (fn [_] nil))
                               (api/bpm-task-history (:process-instance-id task)
                                                     (fn [res]
                                                       (reset! detail-history (or (:task-history (:data res)) [])))
                                                     (fn [_] nil)))]
      [:div
       [page-toolbar/page-toolbar
        {:left [page-toolbar/toolbar-left
                [:div {:style {:fontSize 15 :fontWeight 600}} "我的待办"]]
         :right [page-toolbar/toolbar-right
                 [page-toolbar/round-tool-button {:title "刷新"
                                                  :icon (r/as-element [:> ReloadOutlined])
                                                  :on-click #(rf/dispatch [:bpm/todo-fetch])}]]}]
       [antd/table {:scroll #js {:x "max-content"} :rowKey "task-id"
                    :columns (task-columns open-ops open-detail)
                    :dataSource (clj->js items)
                    :loading loading?
                    :pagination {:total total :pageSize 10 :showSizeChanger true
                                 :showTotal (fn [total] (str "共 " total " 条"))}}]
       [approve-modal @users]
       [ops-modal {:task @ops-task :type @ops-type :to-user @ops-user
                   :users @users :visible? @ops-visible
                   :set-to-user! #(reset! ops-user %) :set-visible! #(reset! ops-visible %)}]
       [todo-detail-drawer {:task @detail-task :visible? @detail-visible
                            :set-visible! #(reset! detail-visible %)
                            :form-data @detail-form :diagram detail-diagram
                            :history detail-history}]])))
