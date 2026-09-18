(ns com.ruoyi.frontend.pages.business.bpm-todo
  "我的待办 —— 审批面板（通过/驳回/转办/委派/加签/抄送）。
   Phase 2：按节点 buttons 配置显隐/改名操作按钮，支持手写签名(signEnable)与意见必填(reasonRequire)。"
  (:require
   [clojure.walk :as walk]
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [ReloadOutlined CheckOutlined CloseOutlined SwapOutlined SendOutlined EyeOutlined UserAddOutlined MailOutlined RollbackOutlined EditOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
   [com.ruoyi.frontend.components.form-render :as fr]
   [com.ruoyi.frontend.components.bpm-flow-designer :as bpmfd]))

;; ── 节点按钮配置（task-detail / todo 行附带，未配置默认全启用）────────────────

(defn- button-cfg
  "取任务某按钮配置 → {:enable? bool :label str}。keys: approve/reject/transfer/delegate/add-sign/return"
  [task k default-label]
  (let [buttons (:buttons task)
        b (or (get buttons k) (get buttons (keyword k)))]
    {:enable? (if (and (map? b) (some? (:enable b)))
                (boolean (:enable b))
                true)
     :label (if (and (map? b) (seq (:displayName b)))
              (:displayName b)
              default-label)}))

(defn- task-columns [open-ops open-detail]
  #js [#js {:title "任务" :dataIndex "name" :key "name"}
       #js {:title "流程定义" :dataIndex "process-definition-id" :key "process-definition-id"
            :width 180 :ellipsis true}
       #js {:title "流程实例" :dataIndex "process-instance-id" :key "process-instance-id"
            :width 80 :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} v] "-")))}
       #js {:title "创建时间" :dataIndex "create-time" :key "create-time" :width 160}
       #js {:title "操作" :key "action" :width 300
            :render (fn [_ ^js record]
                      (let [task (js->clj record :keywordize-keys true)
                            approve (button-cfg task "approve" "通过")
                            reject (button-cfg task "reject" "驳回")
                            transfer (button-cfg task "transfer" "转办")
                            delegate (button-cfg task "delegate" "委派")
                            add-sign (button-cfg task "add-sign" "加签")
                            return (button-cfg task "return" "退回")]
                        (r/as-element
                         (into [antd/space]
                               (concat
                                [[antd/button {:size "small"
                                               :icon (r/as-element [:> EyeOutlined])
                                               :on-click #(open-detail task)}
                                  "详情"]
                                 (when (:enable? approve)
                                   [[antd/button {:type "primary" :size "small"
                                                  :icon (r/as-element [:> CheckOutlined])
                                                  :on-click #(rf/dispatch [:bpm/todo-open-approve task])}
                                     (:label approve)]])
                                 (when (:enable? reject)
                                   [[antd/button {:danger true :size "small"
                                                  :icon (r/as-element [:> CloseOutlined])
                                                  :on-click #(rf/dispatch [:bpm/todo-open-reject task])}
                                     (:label reject)]])
                                 (when (:enable? transfer)
                                   [[antd/button {:size "small"
                                                  :icon (r/as-element [:> SwapOutlined])
                                                  :on-click #(open-ops task "transfer")}
                                     (:label transfer)]])
                                 (when (:enable? delegate)
                                   [[antd/button {:size "small"
                                                  :icon (r/as-element [:> SendOutlined])
                                                  :on-click #(open-ops task "delegate")}
                                     (:label delegate)]])
                                 (when (:enable? add-sign)
                                   [[antd/button {:size "small"
                                                  :icon (r/as-element [:> UserAddOutlined])
                                                  :on-click #(rf/dispatch [:bpm/todo-open-sign task])}
                                     (:label add-sign)]])
                                 [antd/button {:size "small"
                                               :icon (r/as-element [:> MailOutlined])
                                               :on-click #(rf/dispatch [:bpm/todo-open-copy task])}
                                  "抄送"]]
                                 (when (:enable? return)
                                   [[antd/button {:size "small"
                                                  :icon (r/as-element [:> RollbackOutlined])
                                                  :on-click #(rf/dispatch [:bpm/todo-open-reject task])}
                                     (:label return)]]))))))}])

;; ── 手写签名画布 ──────────────────────────────────────────────────────────

(defn- signature-pad
  "canvas 手写签名板：签名结果以 dataURL 形式通过 on-change 回调传出。"
  [{:keys [on-change]}]
  (let [canvas-ref (hooks/use-ref nil)
        drawing? (hooks/use-ref false)
        [empty? set-empty!] (hooks/use-state true)
        ctx (fn [] (some-> @canvas-ref (.getContext "2d")))
        pos (fn [e]
              (let [rect (.getBoundingClientRect @canvas-ref)
                    t (.-touches e)
                    client-x (if t (some-> t (aget 0) .-clientX) (.-clientX e))
                    client-y (if t (some-> t (aget 0) .-clientY) (.-clientY e))]
                [(- client-x (.-left rect)) (- client-y (.-top rect))]))
        start (fn [e]
                (.preventDefault e)
                (reset! drawing? true)
                (when-let [c (ctx)]
                  (let [[x y] (pos e)]
                    (.beginPath c)
                    (.moveTo c x y))))
        move (fn [e]
               (when @drawing?
                 (.preventDefault e)
                 (when-let [c (ctx)]
                   (let [[x y] (pos e)]
                     (.lineTo c x y)
                     (.stroke c)
                     (set-empty! false)))))
        end (fn [e]
              (when @drawing?
                (.preventDefault e)
                (reset! drawing? false)
                (when-let [cv @canvas-ref]
                  (on-change (.toDataURL cv "image/png")))))
        clear (fn []
                (when-let [c (ctx)]
                  (.clearRect c 0 0 (.-width @canvas-ref) (.-height @canvas-ref))
                  (set-empty! true)
                  (on-change nil)))]
    [:div
     [:canvas {:ref canvas-ref :width 560 :height 160
               :style {:border "1px dashed #d9d9d9" :borderRadius 6 :width "100%"
                       :touchAction "none" :background "#fff"}
               :on-mouse-down start :on-mouse-move move
               :on-mouse-up end :on-mouse-leave end
               :on-touch-start start :on-touch-move move :on-touch-end end}]
     [:div {:style {:marginTop 4 :display "flex" :justifyContent "space-between" :alignItems "center"}}
      [:span {:style {:color "#c0c4cc" :fontSize 12}}
       (if empty? "请在上方区域手写签名" "已签名，可重新书写")]
      [antd/button {:size "small" :icon (r/as-element [:> EditOutlined]) :on-click clear} "清除"]]]))

;; ── 审批弹窗 ──────────────────────────────────────────────────────────────

(defn- comment-item
  ([label] (comment-item label false))
  ([label required?]
   [antd/form-item {:label label :name "comment"
                    :rules (when required? [{:required true :message "当前节点要求填写审批意见"}])}
    [antd/text-area {:placeholder (if required? "请输入审批意见(必填)" "请输入意见(可选)") :rows 3}]]))

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
        [sign-data set-sign-data!] (hooks/use-state nil)
        sign-enable? (boolean (and visible?
                                   (:sign-enable form-data)
                                   (#{"approve" "reject"} action)))
        reason-required? (boolean (:reason-require form-data))
        default-return (or (some #(when (= (:reject-return-node form-data) (:activity-id %)) (:activity-id %))
                                 return-list)
                           (:activity-id (first return-list)))
        title (case action
                "approve" (str (or (get-in current [:buttons :approve :displayName]) "审批通过"))
                "reject" "审批驳回"
                "sign" "加签"
                "copy" "抄送"
                "审批")]
    (hooks/use-effect
     (fn [] (set-sign-data! nil))
     [visible? (:task-id current)])
    [antd/modal {:title (str title " · " (:name current))
                 :open visible? :confirmLoading submitting? :width 640
                 :onOk (fn []
                         (cond
                           (and sign-enable? (not sign-data))
                           (antd/warning! "请先手写签名")
                           :else (.submit form)))
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
                             (rf/dispatch [:bpm/todo-submit
                                           (assoc (js->clj values :keywordize-keys true)
                                                  :sign_data sign-data)]))}
      (case action
        "reject" [:<>
                 (when (seq return-list)
                   [antd/form-item {:label "退回到节点" :name "return_node_id"
                                    :initialValue default-return}
                    [antd/select {:style {:width "100%"}
                                  :options (clj->js (mapv (fn [n]
                                                            {:value (:activity-id n)
                                                             :label (:activity-name n)})
                                                          return-list))}]])
                 [comment-item "审批意见" reason-required?]
                 (when sign-enable?
                   [antd/form-item {:label "手写签名" :required true}
                    [signature-pad {:on-change set-sign-data!}]])]
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
        [:<>
         [comment-item "审批意见" reason-required?]
         (when sign-enable?
           [antd/form-item {:label "手写签名" :required true}
            [signature-pad {:on-change set-sign-data!}]])])]]))

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
                                   [:div {:style {:color "#606266" :marginTop 2}} (str "意见：" (:comment t))])
                                 (when (:sign-pic-url t)
                                   [:div {:style {:marginTop 4}}
                                    [:div {:style {:color "#909399" :fontSize 12 :marginBottom 2}} "手写签名："]
                                    [:img {:src (:sign-pic-url t) :alt "签名"
                                           :style {:maxWidth 180 :border "1px solid #ebeef5"
                                                   :borderRadius 4 :background "#fff"}}]])])))
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
