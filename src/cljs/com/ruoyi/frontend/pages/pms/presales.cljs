(ns com.ruoyi.frontend.pages.pms.presales
  "售前资料交接 (A04): 按项目产品线选用生效的应交资料清单, 逐项登记已收资料与同项目文档版本证据;
   缺必交项阻断提交并可登记退回意见, 齐备后由指定独立审核人批准.
   来源 CRM/OA 只作来源标注, 外部回执未对接时如实显示待合同."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def status-labels
  {"pending" "待补交" "submitted" "已提交" "returned" "已退回" "approved" "已批准"})


(def status-colors
  {"pending" "blue" "submitted" "orange" "returned" "red" "approved" "green"})


(def source-labels
  {"local" "本地登记" "crm" "CRM 来源" "oa" "OA 来源"})


(defn- summary-row
  "交接汇总: 待补交/已提交/已退回/已批准与累计缺料."
  [{:keys [summary]}]
  [antd/space {:size 24 :wrap true}
   [antd/tag {:color "blue"} (str "待补交 " (:pending summary 0))]
   [antd/tag {:color "orange"} (str "已提交 " (get-in summary [:submitted] 0))]
   [antd/tag {:color "red"} (str "已退回 " (:returned summary 0))]
   [antd/tag {:color "green"} (str "已批准 " (:approved summary 0))]
   (if (seq (:missing_all summary))
     [antd/tag {:color "red"} (str "累计缺料 " (str/join " / " (:missing_all summary)))]
     [antd/tag {:color "green"} "当前无缺料"])])


(defn- checklist-panel
  "当前产品线生效的应交资料清单; 未发布时如实说明不臆造必交项."
  [{:keys [product_line checklist checklist_missing required_docs]}]
  [shared/panel "产品线应交资料"
   (if checklist
     (str "产品线 " product_line " 生效清单 " (:code checklist) " 第 " (:revision checklist) " 版")
     (str "产品线 " (or product_line "未填写合同号") " 尚未发布应交清单, 不臆造必交项"))
   (when checklist
     [antd/tag (str "清单 " (:code checklist))])
   [:div {:style {:display "flex" :gap 8 :flexWrap "wrap"}}
    (if (seq required_docs)
      (for [doc required_docs] ^{:key doc} [antd/tag {:color "red"} (str "必交: " doc)])
      [antd/tag {:color "default"} (if checklist_missing "未发布清单" "清单未要求必交资料")])]])


(defn- rows-table
  "交接记录表: 缺料与齐套由服务端只读派生, 操作按状态放出."
  [rows busy? actions]
  (let [columns
        [{:title "标题" :dataIndex "title" :width 220}
         {:title "来源" :dataIndex "source" :width 110
          :render (fn [v] (r/as-element [antd/tag (get source-labels v v)]))}
         {:title "外部回执" :dataIndex "source_sync" :width 120
          :render (fn [v] (r/as-element
                            (if (= "not_configured" v)
                              [antd/tag {:color "orange"} "待合同"]
                              [antd/tag {:color "default"} "本地"])))}
         {:title "已收/应交" :width 120
          :render (fn [_ row] (str (count (aget row "items")) " / " (aget row "required_count")))}
         {:title "缺料" :dataIndex "rule_missing" :width 180
          :render (fn [v]
                    (let [missing (js->clj v)]
                      (r/as-element (if (seq missing)
                                      [antd/tag {:color "red"} (str/join ", " missing)]
                                      [antd/tag {:color "green"} "齐备"]))))}
         {:title "状态" :dataIndex "status" :width 100
          :render (fn [v] (r/as-element [antd/tag {:color (get status-colors v "default")}
                                         (get status-labels v v)]))}
         {:title "操作" :width 260
          :render (fn [_ row]
                    (r/as-element
                      (into [:div {:style {:display "flex" :gap 4 :flexWrap "wrap"}}]
                            (for [[label next] (actions (aget row "status"))]
                              ^{:key label}
                              [antd/button {:size "small" :disabled busy?
                                            :on-click #((:run! actions) (aget row "id") next)}
                               label]))))}]]
    [antd/table {:size "small" :pagination false :rowKey "id" :scroll {:x 900}
                 :locale {:emptyText "暂无交接记录"}
                 :dataSource (clj->js (vec rows))
                 :columns (clj->js columns)}]))


(defn- submit-form
  "提交评审表单: 选择独立审核人并附说明."
  [on-submit on-close busy?]
  (let [[form] (antd/form-use-form)]
    [antd/modal {:title "提交交接评审" :open true :onCancel on-close :okText "提交"
                 :cancelText "返回" :confirmLoading busy?
                 :onOk (fn []
                         (.validateFields form)
                         (.then (fn [values]
                                  (on-submit (js->clj values :keywordize-keys true)))
                                (fn [_] nil)))}
     [antd/form {:form form :layout "vertical"}
      [antd/form-item {:name "reviewer_id" :label "独立审核人"
                       :rules [{:required true :message "请选择独立审核人"}]}
       [antd/input-number {:min 1 :style {:width "100%"}}]]
      [antd/form-item {:name "reason" :label "说明"}
       [antd/text-area {:rows 3 :maxLength 500}]]]]))


(defn- presales-view
  "售前资料交接主界面; project 提供项目标识, 版本随每次写入携带."
  [resource id]
  (let [{:keys [data loading? refresh!]} resource
        [busy? set-busy!] (hooks/use-state false)
        [error set-error!] (hooks/use-state nil)
        [modal set-modal!] (hooks/use-state nil)
        run! (fn [action rid payload]
               (set-busy! true)
               (shared/request! :post (str "/projects/" id "/presales/" rid "/" action)
                                (assoc payload :version (:version data))
                                (fn [_] (set-busy! false) (set-error! nil) (refresh!))
                                (fn [msg] (set-busy! false) (set-error! msg))))]
    [:div
     (when error [antd/alert {:type "error" :show-icon true :message error :style {:marginBottom 16}}])
     [w/resource-view resource
      (fn [data]
        [:div
         [summary-row data]
         [:div {:style {:height 12}}]
         [checklist-panel data]
         [:div {:style {:height 12}}]
         [shared/panel "交接记录"
          "已收资料须绑定同项目文档版本作为证据; 清单外资料不接受登记" nil
          [rows-table (:rows data) busy?
           (with-meta
             (fn [status]
               (case status
                 "pending" [["维护资料" :update] ["提交评审" :submit]]
                 "returned" [["补交资料" :update] ["提交评审" :submit]]
                 "submitted" [["批准" :approve] ["退回" :return]]
                 []))
             {:run! (fn [rid next]
                      (case next
                        :update (run! "" rid {})
                        :submit (set-modal! rid)
                        :approve (run! "approve" rid {:reason "资料齐备"})
                        :return (run! "return" rid {:reason "缺资料退回"})))})]]])]
     (when modal
       [submit-form (fn [{:keys [reviewer_id reason]}]
                      (run! "submit" modal {:reviewer_id reviewer_id :reason reason})
                      (set-modal! nil))
        #(set-modal! nil) busy?])]))


(defn presales-workspace
  "项目工作台内的售前资料交接页签; 成功写入后刷新项目统一版本."
  [project _revision changed!]
  (let [id (:project_id project)
        resource (shared/use-resource (str "/projects/" id "/presales") {} [(:status project)])]
    [presales-view (assoc resource :refresh! #(do ((:refresh! resource)) (changed!))) id]))
