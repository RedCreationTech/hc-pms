(ns com.ruoyi.frontend.pages.pms.cancellation
  "B17 项目/单机受控取消 (本地侧): 登记取消申请须关联已批准的变更依据,
   审批通过后停止该范围后续下发, 未执行的外发记录列为待补偿对账项.
   补偿回执与外部系统对账仍待合同, 界面如实标注 not_configured."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def status-labels
  {"pending" "待提交" "submitted" "待审批" "approved" "已批准"
   "rejected" "已驳回" "withdrawn" "已撤回"})


(def status-colors
  {"pending" "blue" "submitted" "orange" "approved" "green"
   "rejected" "red" "withdrawn" "default"})


(def target-labels
  {"project" "整项目" "node" "单机/节点"})


(defn- summary-row
  "取消汇总: 待提交/待审批/已批准, 以及已停止任务与待补偿项."
  [{:keys [summary]}]
  [antd/space {:size 24 :wrap true}
   [antd/tag {:color "blue"} (str "待提交 " (:pending summary 0))]
   [antd/tag {:color "orange"} (str "待审批 " (:submitted summary 0))]
   [antd/tag {:color "green"} (str "已批准 " (:approved summary 0))]
   [antd/tag {:color "geekblue"} (str "已停止任务 " (:stopped summary 0))]
   (if (pos? (:pending_compensation summary 0))
     [antd/tag {:color "red"} (str "待补偿 " (:pending_compensation summary))]
     [antd/tag {:color "green"} "无待补偿"])])


(defn- scope-panel
  "取消范围口径与外部补偿能力, 未配置时不臆造已对账结论."
  [{:keys [external_compensation targets]}]
[shared/panel "取消范围与外部补偿"
   (str "可取消范围: " (str/join " / " (map #(get target-labels % %) targets)))
   nil
   [antd/space {:size 12 :wrap true}
    (if (= "not_configured" external_compensation)
      [antd/tag {:color "orange"} "外部补偿回执待合同 (not_configured)"]
      [antd/tag {:color "green"} external_compensation])
    [antd/tag {:color "default"} "批准后立即停止该范围后续下发"]]])


(defn- records-table
  "取消申请表: 受影响任务/待补偿由服务端派生, 操作按状态与办理人放出."
  [rows busy? actions]
  (let [columns
        [{:title "取消申请" :dataIndex "title" :width 260}
         {:title "范围" :dataIndex "target" :width 110
          :render (fn [v] (r/as-element [antd/tag (get target-labels v v)]))}
         {:title "变更依据" :dataIndex "basis_change_title" :width 200
          :render (fn [v] (r/as-element [:span (or v "-")]))}
         {:title "受影响任务" :width 110
          :render (fn [v]
                    (let [tasks (js->clj (aget v "affected_tasks"))]
                      (r/as-element [:span (str (count tasks) " 项")])))}
         {:title "外发记录" :width 120
          :render (fn [v]
                    (let [out (js->clj (aget v "outbound"))]
                      (r/as-element (if (seq out)
                                      [antd/tag {:color "orange"} (str (count out) " 项")]
                                      [antd/tag {:color "default"} "无"]))))}
         {:title "停止下发" :width 110
          :render (fn [_ row]
                    (r/as-element
                      (cond
                        (true? (aget row "dispatch_stopped")) [antd/tag {:color "green"} "已停止"]
                        (false? (aget row "dispatch_stopped")) [antd/tag {:color "red"} "未停止"]
                        :else [antd/tag {:color "default"} "未生效"])))}
         {:title "状态" :dataIndex "status" :width 100
          :render (fn [v] (r/as-element [antd/tag {:color (get status-colors v "default")}
                                         (get status-labels v v)]))}
         {:title "操作" :width 280
          :render (fn [_ row]
                    (r/as-element
                      (into [:div {:style {:display "flex" :gap 4 :flexWrap "wrap"}}]
                            (for [[label next] (actions (aget row "status"))]
                              ^{:key label}
                              [antd/button {:size "small" :disabled busy?
                                            :on-click #((:run! actions) (aget row "id") next)}
                               label]))))}]]
    [antd/table {:size "small" :pagination false :rowKey "id" :scroll {:x 1100}
                 :locale {:emptyText "暂无取消申请"}
                 :dataSource (clj->js (vec rows))
                 :columns (clj->js columns)}]))


(defn- create-form
  "登记取消申请: 选择范围 (整项目或指定节点), 并关联已批准变更依据."
  [changed-options node-options on-create on-close busy?]
  (let [[form] (antd/form-use-form)]
    [antd/modal {:title "登记取消申请" :open true :onCancel on-close :okText "登记"
                 :cancelText "返回" :confirmLoading busy?
                 :onOk (fn []
                         (.validateFields form)
                         (.then (fn [values]
                                  (on-create (js->clj values :keywordize-keys true)))
                               (fn [_] nil)))}
     [antd/form {:form form :layout "vertical"}
      [antd/form-item {:name "target" :label "取消范围" :initialValue "project"}
       [antd/select {:options (clj->js [{:value "project" :label "整项目"}
                                        {:value "node" :label "单机/节点"}])}]]
      [antd/form-item {:noStyle true :shouldUpdate true}
       (fn [_]
         (when (= "node" (.getFieldValue form "target"))
           (r/as-element
             [antd/form-item {:name "node_id" :label "取消节点 (含其下级)"
                              :rules [{:required true :message "请选择取消节点"}]}
              [antd/select {:showSearch true :optionFilterProp "label"
                            :placeholder "选择结构节点"
                            :options (clj->js node-options)}]])))]
      [antd/form-item {:name "basis_change_id" :label "已批准的变更依据"
                       :rules [{:required true :message "取消必须关联已批准的变更"}]}
       [antd/select {:showSearch true :optionFilterProp "label"
                     :placeholder "选择已批准的变更"
                     :options (clj->js (mapv (fn [c] {:value (:id c)
                                                      :label (str (:code c) " " (:title c))})
                                             changed-options))}]]
      [antd/form-item {:name "reason" :label "取消原因"
                       :rules [{:required true :message "请填写取消原因"}]}
       [antd/text-area {:rows 3 :maxLength 1000}]]]]))


(defn- submit-form
  "提交取消审批: 未配置审批策略时须指定独立审批人."
  [requires-reviewer? on-submit on-close busy?]
  (let [[form] (antd/form-use-form)]
    [antd/modal {:title "提交取消审批" :open true :onCancel on-close :okText "提交"
                 :cancelText "返回" :confirmLoading busy?
                 :onOk (fn []
                         (.validateFields form)
                         (.then (fn [values]
                                  (on-submit (js->clj values :keywordize-keys true)))
                               (fn [_] nil)))}
     [antd/form {:form form :layout "vertical"}
      (when requires-reviewer?
        [antd/form-item {:name "reviewer_id" :label "独立审批人"
                         :rules [{:required true :message "请选择独立审批人"}]}
         [antd/input-number {:min 1 :style {:width "100%"}}]])
      [antd/form-item {:name "reason" :label "提交说明"}
       [antd/text-area {:rows 3 :maxLength 1000}]]]]))


(defn- cancellation-view
  "取消主界面; project 提供项目标识, 版本随每次写入携带."
  [resource changes nodes id]
  (let [{:keys [data loading? refresh!]} resource
        [busy? set-busy!] (hooks/use-state false)
        [error set-error!] (hooks/use-state nil)
        [modal set-modal!] (hooks/use-state nil)
        approved (filterv #(= "approved" (:status %)) changes)
        node-options (mapv (fn [n] {:value (:node_id n)
                                    :label (str (get target-labels (:node_type n) (:node_type n))
                                                " / " (:node_code n) " " (:name n))})
                           nodes)
        run! (fn [action rid payload]
               (set-busy! true)
               (shared/request! :post (str "/projects/" id "/cancellations/" rid "/" action)
                                (assoc payload :version (:version data))
                                (fn [_] (set-busy! false) (set-error! nil) (refresh!))
                                (fn [msg] (set-busy! false) (set-error! msg))))
        create! (fn [payload]
                  (set-busy! true)
                  (shared/request! :post (str "/projects/" id "/cancellations")
                                   (assoc payload :version (:version data))
                                   (fn [_] (set-busy! false) (set-error! nil) (refresh!))
                                   (fn [msg] (set-busy! false) (set-error! msg)))
                  (set-modal! nil))]
    [:div
     (when error [antd/alert {:type "error" :show-icon true :message error :style {:marginBottom 16}}])
     [w/resource-view resource
      (fn [data]
        [:div
         [summary-row data]
         [:div {:style {:height 12}}]
         [scope-panel data]
         [:div {:style {:height 12}}]
         [shared/panel "取消申请"
          (if (seq approved)
            "登记须关联已批准的变更依据; 批准后停止该范围后续下发并列待补偿"
            "尚无已批准变更, 需先在需求与治理中完成变更审批")
          [antd/button {:type "primary" :size "small" :disabled (or busy? (empty? approved))
                        :on-click #(set-modal! :create)}
           "登记取消申请"]
          [records-table (:rows data) busy?
           (with-meta
             (fn [status]
               (case status
                 "pending" [["提交审批" :submit] ["撤回" :withdraw]]
                 "submitted" [["批准" :approve] ["驳回" :reject]]
                 []))
             {:run! (fn [rid next]
                      (case next
                        :submit (set-modal! [:submit rid])
                        :withdraw (run! "withdraw" rid {:reason "申请人撤回"})
                        :approve (run! "review" rid {:decision "approved" :reason "同意取消"})
                        :reject (run! "review" rid {:decision "rejected" :reason "不同意取消"})))})]]])]
     (when (= modal :create)
       [create-form approved node-options create! #(set-modal! nil) busy?])
     (when (vector? modal)
       [submit-form (:requires_reviewer data)
        (fn [payload] (run! "submit" (second modal) payload) (set-modal! nil))
        #(set-modal! nil) busy?])]))


(defn cancellation-workspace
  "项目工作台内的取消申请页签; 成功写入后刷新项目统一版本."
  [project _revision changed!]
  (let [id (:project_id project)
        resource (shared/use-resource (str "/projects/" id "/cancellations") {} [(:status project)])
        governance (shared/use-resource (str "/projects/" id "/governance") {} [(:status project)])
        structure (shared/use-resource (str "/projects/" id "/nodes") {} [(:status project)])]
    [cancellation-view (assoc resource :refresh! #(do ((:refresh! resource)) (changed!)))
     (get-in governance [:data :changes]) (:rows (:data structure)) id]))