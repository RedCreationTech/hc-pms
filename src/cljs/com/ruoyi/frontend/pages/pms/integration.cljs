(ns com.ruoyi.frontend.pages.pms.integration
  "接口收发,来源版本与真实回执对账工作台."
  (:require [clojure.string :as str]
            [com.ruoyi.frontend.antd :as antd]
            [com.ruoyi.frontend.pages.pms.shared :as shared]
            [com.ruoyi.frontend.pages.pms.widgets :as w]
            [reagent.core :as r]
            [reagent.hooks :as hooks]))

(def systems {"crm" "CRM" "oa" "OA" "erp" "ERP / SAP" "plm" "PLM" "mes" "MES"
              "srm" "SRM" "sales_material" "销售物料" "bi" "BI"})
(def topics {"plan.published" "已批准计划" "cost.approved" "已批准成本" "document.registered" "文档版本"})
(def states {"queued" "待发送" "sending" "发送中" "retry_wait" "等待重试" "dead_letter" "死信待处理"
             "delivered" "已获回执" "applied" "已采用" "ignored" "历史版本" "indeterminate" "结果待确认"})

(defn- source-options
  "仅从真实已批准版本或已登记文档构造外发来源."
  [planning governance finance]
  (vec (concat
    (for [item (:baselines planning) :when (= "approved" (:status item))]
      {:value (str "plan.published:" (:baseline_id item)) :label (str "计划基线 / 修订 " (:plan_revision item))})
    (for [item (:cost_versions finance) :when (= "approved" (:status item))]
      {:value (str "cost.approved:" (:id item)) :label (str "成本 / " (:name item) " / V" (:version_no item))})
    (for [item (:documents governance)]
      {:value (str "document.registered:" (:id item)) :label (str "文档 / " (:code item) " / " (:title item) " / V" (:revision item))}))))

(defn- enqueue-dialog
  "服务端生成确定消息正文,前端只选择对象和业务幂等键."
  [{:keys [base sources]}]
  {:title "建立外发消息" :path (str base "/outbox")
   :description "排队保留来源版本,实际发送需另行操作.同一业务批次重复排队不会产生第二条消息."
   :transform (fn [data] (let [[topic id] (str/split (:source data) #":" 2)]
                          (-> data (dissoc :source) (assoc :topic topic :source_id id))))
   :fields [{:key :target :label "目标系统" :type :select :required? true
             :options (mapv (fn [[value label]] {:value value :label label}) (sort-by key systems))}
            {:key :source :label "业务版本来源" :type :select :required? true :options sources}
            {:key :idempotency_key :label "业务批次编号" :required? true :hint "同一批次重试保留相同编号."}]})

(defn- status-column
  "回执状态与来源版本状态使用独立语义."
  []
  {:title "状态" :dataIndex "status" :width 120
   :render #(r/as-element [antd/tag {:color (cond (contains? #{"delivered" "applied"} %) "green"
                                                   (contains? #{"dead_letter" "indeterminate"} %) "red" :else "blue")}
                           (get states % (get w/labels % %))])})

(defn- connections
  "区分部署配置与实际回执,不声称已连接外部企业系统."
  [model]
  [shared/panel "连接器配置" "仅显示部署配置状态,实际交付以每条消息的匹配回执为准" nil
   [:div {:style {:display "flex" :gap 12 :flexWrap "wrap"}}
    (for [item (:connectors model)] ^{:key (:system item)}
      [:div {:style {:padding "12px 16px" :background "#f7f9fc" :borderRadius 6}}
       [:strong (get systems (:system item) (:system item))] " "
       [antd/tag {:color (if (:configured item) "blue" "default")}
        (if (:configured item) "已配置" "未配置")]])]])

(defn- reconciliation
  "按实际收发记录展示对账口径."
  [model]
  [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit,minmax(150px,1fr))" :gap 12 :marginBottom 20}}
   (for [[key label] [[:received "累计收件"] [:applied "已采用来源版本"] [:ignored "历史或重复版本"]
                      [:queued "累计外发"] [:delivered "已有匹配回执"] [:unconfirmed "尚未确认"]]]
     ^{:key key} [:div {:style {:padding 16 :border "1px solid #e8edf3" :borderRadius 8}}
                  [:div {:style {:color "#718096" :fontSize 12}} label]
                  [:strong {:style {:display "block" :fontSize 24 :marginTop 8}} (get-in model [:reconciliation key] 0)]])])

(defn- outbox-actions
  "只有有权操作的人员才能显式发送或恢复失败消息."
  [{:keys [base model deliver? open! inspect!]} message]
  (let [path (str base "/outbox/" (:id message))
        configured? (some #(and (= (:target message) (:system %)) (:configured %)) (:connectors model))]
    [antd/space {:wrap true}
     [w/edit-button "查看来源" #(inspect! message)]
     (when (and deliver? configured? (contains? #{"queued" "retry_wait"} (:status message)))
       [w/edit-button "发送消息" #(open! {:title "发送外部消息" :path (str path "/deliver") :fields []
                                           :description "这将向已配置的目标系统发送当前确定版本,系统按实际回执记录结果."})])
     (when (and deliver? (contains? #{"sending" "retry_wait" "dead_letter"} (:status message)))
       [w/edit-button "恢复重试" #(open! {:title "恢复消息重试" :path (str path "/retry")
                                           :fields [{:key :reason :label "恢复原因" :type :textarea :required? true}]})])
     (when-not configured? [:span {:style {:fontSize 12 :color "#718096"}} "目标未配置"])]))

(defn- outbox-section
  "发件队列保留来源,业务批次与最后处理结果."
  [{:keys [model edit? open!] :as context}]
  [shared/panel "外发消息" "批准对象形成确定消息,排队与发送分别控制"
   (when edit? [antd/button {:type "primary" :on-click #(open! (enqueue-dialog context))} "建立外发消息"])
   [w/record-table (:outbox model)
    [{:title "目标" :dataIndex "target" :render #(get systems % %)}
     {:title "来源类型" :dataIndex "topic" :render #(get topics % %)}
     (w/text-column :idempotency_key "业务批次") (status-column)
     (w/text-column :attempts "尝试次数") (w/text-column :error_code "最近错误")]
    #(outbox-actions context %) ]])

(defn- inbox-section
  "外部事实保留来源版本,不隐式改变PMS内部批准对象."
  [{:keys [model]}]
  [:div {:style {:display "grid" :gap 20}}
   [shared/panel "来源收件" "来自认证接口,低版本不覆盖当前事实" nil
    [w/record-table (:inbox model)
     [(w/text-column :source "来源系统") (w/text-column :event_id "来源事件")
      (w/text-column :entity_type "事实类型") (w/text-column :external_key "来源业务编号")
      (w/text-column :source_revision "来源版本") (status-column)] nil]]
   [shared/panel "当前来源事实" "此处是来源事实投影,内部审批状态保持独立" nil
    [w/record-table (:facts model)
     [(w/text-column :source "来源系统") (w/text-column :entity_type "事实类型")
      (w/text-column :external_key "来源业务编号") (w/text-column :source_revision "当前版本")
      (w/text-column :payload_hash "来源摘要")] nil]]])

(defn- attempts-section
  "显示每次真实发送尝试及匹配回执."
  [{:keys [model]}]
  [shared/panel "发送尝试与回执" "HTTP成功与业务接受分别核对,失败记录不会被重试覆盖" nil
   [w/record-table (:attempts model)
    [(w/text-column :message_id "消息编号") (w/text-column :attempt_no "次数")
     (status-column) (w/text-column :http_status "HTTP状态") (w/text-column :receipt_id "远端回执")
     (w/text-column :error_code "失败原因") (w/text-column :completed_at "处理时间")] nil]])

(defn- message-preview
  "以业务内容预览受权限控制的确定外发来源."
  [base message on-close]
  (let [resource (shared/use-resource (str base "/outbox/" (:id message)) {} [])]
    [antd/modal {:title "确定外发来源" :open true :footer nil :onCancel on-close :width 900}
     [w/resource-view resource
      (fn [data]
        (let [source (get-in data [:payload :data])]
          [:div [:p (str "消息编号: " (:id data))] [:p (str "来源版本: " (:source_id data))]
           [:p {:style {:overflowWrap "anywhere" :fontSize 12}} (str "内容摘要: " (:payload_hash data))]
           (when-let [tasks (get-in source [:snapshot :tasks])]
             [w/record-table tasks [(w/text-column :wbs_code "WBS编号") (w/text-column :name "任务") (w/text-column :duration_days "工作日")] nil])
           (when (:entries source)
             [w/record-table (:entries source) [(w/text-column :label "成本条目") (w/text-column :amount "金额") (w/text-column :source_ref "来源单据")] nil])
           (when (:filename source) [:div [:p (:title source)] [:p (str (:filename source) " / " (:sha256 source))]])]))]]))

(defn- integration-content
  "组织连接状态,收发台账与回执对账."
  [context]
  [:div [reconciliation (:model context)] [connections (:model context)]
   [antd/tabs {:style {:marginTop 20}
               :items [{:key "outbox" :label "发件队列" :children (r/as-element [outbox-section context])}
                       {:key "inbox" :label "来源收件与事实" :children (r/as-element [inbox-section context])}
                       {:key "attempts" :label "回执对账" :children (r/as-element [attempts-section context])}]}]])

(defn- integration-data
  "来源选择严格沿用项目及财务读取权限,写入刷新聚合版本."
  [project revision changed!]
  (let [root (str "/projects/" (:project_id project)) base (str root "/integration")
        resource (shared/use-resource base {} [revision])
        can-query? (shared/use-permission "pms:project:query") can-finance? (shared/use-permission "pms:finance:query")
        planning (shared/use-resource (when can-query? (str root "/planning")) {} [revision])
        governance (shared/use-resource (when can-query? (str root "/governance")) {} [revision])
        finance (shared/use-resource (when can-finance? (str root "/finance")) {} [revision])
        [dialog set-dialog!] (hooks/use-state nil) [message set-message!] (hooks/use-state nil)
        writable? (not (contains? #{"closed" "cancelled" "paused"} (:status project)))
        context {:base base :model (:data resource) :sources (source-options (:data planning) (:data governance) (:data finance))
                 :edit? (and (shared/use-permission "pms:integration:edit") writable?)
                 :deliver? (and (shared/use-permission "pms:integration:deliver") writable?)
                 :open! set-dialog! :inspect! set-message!}]
    [:div [w/resource-view resource (fn [_] [integration-content context])]
     (when message [message-preview base message #(set-message! nil)])
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                   :on-saved (fn [_] (set-dialog! nil) (changed!))})])]))

(defn integration-workspace
  "接口运维拥有独立读取权限."
  [project revision _options changed!]
  (if (shared/use-permission "pms:integration:query")
    [integration-data project revision changed!]
    [shared/empty-state "当前账号没有接口运维读取权限" nil]))
