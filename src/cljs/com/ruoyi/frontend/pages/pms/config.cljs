(ns com.ruoyi.frontend.pages.pms.config
  "模板与规则配置页 (A09/A10): 平台级项目模板, 关口目录与编码规则的版本化维护."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.approval :as approval]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def status-labels
  {"draft" "草稿" "published" "已发布" "retired" "已退役" "frozen" "已冻结" "locked" "已锁定"})


(defn status-badge
  [status]
  [antd/tag {:color (case status "published" "green" "frozen" "green" "locked" "orange" "retired" "default" "blue")}
   (get status-labels status status)])


(def type-labels
  {"equipment" "单机设备" "line" "整线工程" "service" "技术服务" "new_product" "新产品研发"
   "new_technology" "新技术研发" "special_rd" "专题研发" "dept_affairs" "部门事务"})


(def object-labels
  {"project" "项目" "sub" "子项目/单元" "machine" "单机" "document" "证据文档" "requirement" "URS需求" "task" "WBS任务"})


(defn config-dialog
  "平台配置命令弹窗: 不携带项目版本, 成功后刷新列表."
  [{:keys [title path fields initial transform description on-close on-saved]}]
  (let [[form] (antd/form-use-form)
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title title :open true :onCancel on-close :onOk #(.submit form)
                 :okText "保存" :cancelText "返回" :confirmLoading busy? :destroyOnHidden true
                 :style {:maxWidth "calc(100vw - 32px)"} :width 720}
     (when description [:p {:style {:color "#718096" :lineHeight 1.8}} description])
     (when error [shared/error-panel error nil])
     [antd/form {:form form :layout "vertical" :initialValues initial :disabled busy?
                 :onFinish (fn [values]
                             (let [data (js->clj values :keywordize-keys true)]
                               (run! :post path (if transform (transform data) data) (str title "成功"))))}
      (for [field fields] ^{:key (:key field)} [w/form-field field])]]))


(defn- parse-json
  "解析高级结构 JSON, 非法时抛出可读错误."
  [text]
  (if (str/blank? text) nil
      (js->clj (.parse js/JSON text) :keywordize-keys true)))


(defn- template-fields
  "模板主字段 + 高级结构 JSON (阶段/结构/Gate/交付/收尾)."
  []
  [{:key :code :label "模板编码" :required? true}
   {:key :title :label "模板名称" :required? true}
   {:key :description :label "说明" :type :textarea}
   {:key :project_types :label "适用项目类别" :type :multi :required? true
    :options (mapv (fn [[v l]] {:value v :label l}) type-labels)}
   {:key :advanced :label "阶段/结构/Gate/交付/收尾 (JSON)" :type :textarea :required? true :max 20000
    :hint "键: stages(权重合计100), structure, gate_templates, team_roles, document_categories, delivery, closure_items."}])


(defn- advanced-json
  "把模板高级部分序列化为可编辑 JSON 文本."
  [record]
  (.stringify js/JSON (clj->js (select-keys record [:stages :structure :gate_templates :team_roles :document_categories :delivery :closure_items])) nil 2))


(defn- template-transform
  [data]
  (merge (dissoc data :advanced) (parse-json (:advanced data))))


(defn- template-detail
  "只读展示模板版本内容."
  [record on-close]
  [antd/modal {:title (str (:title record) " / V" (:revision record)) :open true :onCancel on-close :footer nil :width 860
               :style {:maxWidth "calc(100vw - 32px)"}}
   [:div {:style {:display "grid" :gap 16}}
    [antd/space {:wrap true}
     [status-badge (:status record)]
     (for [t (:project_types record)] ^{:key t} [antd/tag {:color "geekblue"} (get type-labels t t)])
     [antd/tag (str "阶段 " (count (:stages record)))] [antd/tag (str "Gate " (count (:gate_templates record)))]
     [antd/tag (str "结构 " (count (:structure record)))]]
    [:div [:h4 "阶段与权重"]
     [antd/table {:size "small" :pagination false :rowKey "code" :dataSource (clj->js (:stages record))
                  :columns (clj->js [{:title "编码" :dataIndex "code" :width 100} {:title "阶段" :dataIndex "name"}
                                     {:title "权重%" :dataIndex "weight" :width 100}])}]]
    [:div [:h4 "结构模板"]
     (if (empty? (:structure record)) [:span {:style {:color "#98a2b3"}} "无结构节点 (研发/服务类)"]
         [:ul {:style {:margin 0 :paddingLeft 20}}
          (for [s (:structure record)] ^{:key (:suffix s)}
            [:li (str (:name s) " (" (:suffix s) ")")
             (when (seq (:machines s)) (str " -> " (str/join ", " (map #(str (:name %) "(" (:suffix %) ")") (:machines s)))))])])]
    [:div [:h4 "Gate 模板"]
     [antd/table {:size "small" :pagination false :rowKey "code" :dataSource (clj->js (:gate_templates record))
                  :columns (clj->js [{:title "编码" :dataIndex "code" :width 150} {:title "名称" :dataIndex "title"}
                                     {:title "类型" :dataIndex "gate_type" :width 170} {:title "阶段" :dataIndex "stage" :width 110}
                                     {:title "阻断检查点" :dataIndex "blocks" :width 160 :render (fn [v] (str/join "," (array-seq (or v #js []))))}
                                     {:title "检查项" :dataIndex "checks" :width 80 :render (fn [v] (count (array-seq (or v #js []))))}])}]]
    [:div [:h4 "交付要求 / 团队角色 / 文档类别 / 收尾清单"]
     [antd/space {:wrap true}
      (for [s (get-in record [:delivery :required_stages])] ^{:key s} [antd/tag s])
      (for [t (get-in record [:delivery :required_test_types])] ^{:key t} [antd/tag {:color "blue"} t])]
     [:p {:style {:margin "8px 0 0"}} (str "团队角色: " (str/join ", " (:team_roles record)))]
     [:p {:style {:margin "4px 0 0"}} (str "文档类别: " (str/join ", " (:document_categories record)))]
     [:p {:style {:margin "4px 0 0"}} (str "收尾清单: " (str/join ", " (map :title (:closure_items record))))]]
    (when (seq (:history record))
      [:div [:h4 "版本历史"]
       [:ul {:style {:margin 0 :paddingLeft 20}}
        (for [h (:history record)] ^{:key (:at h)} [:li (str (:at h) " · " (:action h) " · " (:actor_name h) (when (seq (:reason h)) (str " · " (:reason h))))])]])]])


(defn- record-actions
  "按状态提供发布/修订/退役入口."
  [{:keys [kind editable? open! detail!]} row]
  (let [base (str "/config/" kind "/" (:id row))]
    [antd/space {:wrap true}
     [w/edit-button "详情" #(detail! row)]
     (when (and editable? (= "draft" (:status row)))
       [w/edit-button "发布" #(open! {:title (if (= kind "rd-pool") "冻结费用池" "发布版本") :path (str base "/publish")
                                    :fields [{:key :reason :label "发布说明" :type :textarea}]})])
     (when (and editable? (contains? #{"published" "frozen"} (:status row)) (not= kind "period-lock"))
       [w/edit-button "修订" #(open! (if (= kind "project-template")
                                       {:title "修订模板" :path (str base "/revisions") :fields (vec (rest (template-fields)))
                                        :initial (assoc (select-keys row [:title :description :project_types]) :advanced (advanced-json row))
                                        :transform template-transform}
                                       {:title "修订规则" :path (str base "/revisions")
                                        :initial (select-keys row [:object_type :pattern :enforced :description :version_rule :collection_rule])
                                        :fields [{:key :object_type :label "对象类型" :type :select :required? true
                                                  :options (mapv (fn [[v l]] {:value v :label l}) object-labels)}
                                                 {:key :pattern :label "编码模式" :required? true}
                                                 {:key :enforced :label "强制校验" :type :select :options [{:value true :label "强制"} {:value false :label "仅建议"}]}
                                                 {:key :description :label "说明" :type :textarea}
                                                 {:key :version_rule :label "版本规则"} {:key :collection_rule :label "对象收集规则"}]}))])
     (when (and editable? (contains? #{"draft" "published" "frozen" "locked"} (:status row)))
       [w/edit-button (if (= "locked" (:status row)) "解锁" "退役") #(open! {:title (if (= "locked" (:status row)) "解锁封期" "退役版本") :path (str base "/retire")
                                                                          :fields [{:key :reason :label "原因" :type :textarea :required? true}]})])]))


(defn- template-section
  [{:keys [data editable? open!] :as context}]
  [:div {:style {:display "grid" :gap 20}}
   [shared/panel "内置模板目录" "随代码发布的工程默认模板 (订单设备/整线/服务, 新产品/专题研发, 部门事务), 导入后可版本化修订与发布"
    nil
    [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(280px, 1fr))" :gap 12}}
     (for [item (:catalog data)] ^{:key (:code item)}
       [:div {:style {:border "1px solid #e4e8ee" :borderRadius 8 :padding 14}}
        [:div {:style {:fontWeight 600}} (:title item)]
        [:div {:style {:fontSize 12 :color "#718096" :margin "6px 0"}} (:description item)]
        [antd/space {:wrap true}
         (for [t (:project_types item)] ^{:key t} [antd/tag {:color "geekblue"} (get type-labels t t)])
         [antd/tag (str "阶段 " (count (:stages item)))] [antd/tag (str "Gate " (count (:gate_templates item)))]]
        (when editable?
          [antd/button {:size "small" :style {:marginTop 8}
                        :on-click #(open! {:title (str "导入模板 " (:code item)) :path "/config/project-template/import"
                                           :initial {:code (:code item)} :fields [{:key :code :label "目录编码" :required? true}]})}
           "导入为草稿"])])]]
   [shared/panel "项目模板版本" "发布后供项目实例化; 修订产生新版本, 旧版本退役但已实例化项目保留原版本快照"
    (when editable? [antd/button {:type "primary" :on-click #(open! {:title "新建项目模板" :path "/config/project-template"
                                                                      :fields (template-fields) :transform template-transform
                                                                      :initial {:advanced (advanced-json {:stages [{:code "S1" :name "阶段一" :weight 100}] :structure [] :gate_templates [] :team_roles [] :document_categories [] :delivery {:required_stages ["materials" "assembly" "quality" "shipment"] :required_test_types ["SIT" "FAT" "SAT"]} :closure_items []})}})}
                      "新建项目模板"])
    [w/record-table (:rows data)
     [(w/text-column :code "编码") (w/text-column :revision "版本") (w/text-column :title "模板名称")
      {:title "适用类别" :dataIndex "project_types" :render (fn [v] (r/as-element (into [antd/space {:wrap true}] (map (fn [t] [antd/tag {:color "geekblue"} (get type-labels t t)]) (array-seq (or v #js []))))))}
      {:title "阶段/Gate" :key "counts" :width 110 :render (fn [_ row] (str (count (array-seq (or (aget row "stages") #js []))) " / " (count (array-seq (or (aget row "gate_templates") #js [])))))}
      {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [status-badge %])}
      (w/text-column :created_by_name "创建人")]
     #(record-actions context %)]]])


(defn- next-code-preview
  "按对象类型预览当前生效规则生成的下一编号."
  []
  (let [[type set-type!] (hooks/use-state "project")
        [result set-result!] (hooks/use-state nil)
        [error set-error!] (hooks/use-state nil)]
    [:div {:style {:display "flex" :gap 12 :alignItems "center" :flexWrap "wrap"}}
     [antd/select {:value type :style {:width 180} :aria-label "对象类型"
                   :options (mapv (fn [[v l]] {:value v :label l}) (select-keys object-labels ["project" "sub" "machine"]))
                   :onChange #(do (set-type! %) (set-result! nil))}]
     [antd/button {:on-click #(shared/request! :get "/coding-rules/next" {:object_type type} (fn [d] (set-error! nil) (set-result! d)) set-error!)} "预览下一编号"]
     (when result
       (if (:code result)
         [antd/tag {:color "green" :style {:fontSize 14}} (str "建议编号 " (:code result) (when (get-in result [:rule :enforced]) " (强制)"))]
         [antd/tag "该对象类型无生效规则"]))
     (when error [:span {:style {:color "#cf1322"}} error])]))


(defn- rule-section
  [{:keys [data editable? open!] :as context}]
  [:div {:style {:display "grid" :gap 20}}
   [shared/panel "编码规则" "占位符: {YYYY} {YY} {MM} {TYPE} {PROJECT} {SEQ:n}; 强制规则对新建对象校验格式, 版本与对象收集规则随规则版本记录"
    (when editable?
      [antd/space
       [antd/button {:on-click #(open! {:title "导入内置规则" :path "/config/coding-rule/import"
                                        :fields [{:key :code :label "目录编码" :type :select :required? true
                                                  :options (mapv (fn [c] {:value (:code c) :label (str (:code c) " · " (:pattern c))}) (:catalog data))}]})} "导入内置规则"]
       [antd/button {:type "primary" :on-click #(open! {:title "新建编码规则" :path "/config/coding-rule"
                                                         :initial {:object_type "project" :pattern "PRJ-{YYYY}-{SEQ:4}" :enforced false}
                                                         :fields [{:key :code :label "规则编码" :required? true}
                                                                  {:key :object_type :label "对象类型" :type :select :required? true
                                                                   :options (mapv (fn [[v l]] {:value v :label l}) object-labels)}
                                                                  {:key :pattern :label "编码模式" :required? true}
                                                                  {:key :enforced :label "强制校验" :type :select :options [{:value true :label "强制"} {:value false :label "仅建议"}]}
                                                                  {:key :description :label "说明" :type :textarea}
                                                                  {:key :version_rule :label "版本规则"} {:key :collection_rule :label "对象收集规则"}]})} "新建编码规则"]])
    [:div {:style {:marginBottom 16}} [next-code-preview]]
    [w/record-table (:rows data)
     [(w/text-column :code "规则") (w/text-column :revision "版本")
      {:title "对象" :dataIndex "object_type" :width 120 :render #(get object-labels % %)}
      (w/text-column :pattern "编码模式")
      {:title "强制" :dataIndex "enforced" :width 80 :render (fn [v] (r/as-element (if (true? v) [antd/tag {:color "red"} "强制"] [antd/tag "建议"])))}
      (w/text-column :version_rule "版本规则") (w/text-column :collection_rule "收集规则")
      {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [status-badge %])}]
     #(record-actions context %)]]])


(defn- rule-detail
  [record on-close]
  [antd/modal {:title (str (:code record) " / V" (:revision record)) :open true :onCancel on-close :footer nil}
   [antd/descriptions {:column 1 :size "small"
                       :items (mapv (fn [[k l]] {:key (name k) :label l :children (shared/display-value (let [v (get record k)] (if (boolean? v) (if v "强制" "建议") (str v))))})
                                    [[:object_type "对象类型"] [:pattern "编码模式"] [:enforced "强制校验"] [:description "说明"]
                                     [:version_rule "版本规则"] [:collection_rule "收集规则"] [:status "状态"]])}]
   (when (seq (:history record))
     [:ul {:style {:marginTop 12 :paddingLeft 20}}
      (for [h (:history record)] ^{:key (:at h)} [:li (str (:at h) " · " (:action h) " · " (:actor_name h))])])])


(defn- pool-preview
  "只读预览费用池按项目已批准工时的分摊结果 (守恒)."
  [pool on-close]
  (let [resource (shared/use-resource (str "/config/rd-pool/" (:id pool) "/preview") {} [])]
    [antd/modal {:title (str "分摊预览 · " (:code pool)) :open true :onCancel on-close :footer nil :width 760}
     [w/resource-view resource
      (fn [data]
        [:div {:style {:display "grid" :gap 12}}
         [antd/space {:wrap true}
          [antd/tag {:color "blue"} (str "费用池 " (get-in data [:pool :amount]) " " (get-in data [:pool :currency]))]
          [antd/tag (str "期间已批准工时 " (:total_minutes data) " 分钟")]
          [antd/tag {:color (if (:conserved data) "green" "red")} (if (:conserved data) "总额守恒" "不守恒")]]
         (if (empty? (:rows data))
           [:span {:style {:color "#98a2b3"}} "期间内没有已批准工时, 不能分摊."]
           [w/record-table (:rows data)
            [(w/text-column :project_no "项目编号") (w/text-column :project_name "项目") (w/text-column :hours "已批准工时(h)")
             (w/text-column :minutes "分钟") (w/text-column :amount "分摊金额")] nil])])]]))


(defn- pool-section
  "F05 跨项目研发费用池: 冻结后按期间内各项目已批准工时分摊, 在各项目生成待审核核算版本."
  [{:keys [data editable? open! detail!] :as context}]
  (let [approver? (shared/use-permission "pms:finance:approve")]
    [shared/panel "跨项目研发费用池" "冻结费用池与期间工时后按批准算法分摊 (最大余数法, 舍入守恒, 零工时项目不分摊, 直接人工不重计), 各项目核算版本仍需独立财务审批"
     (when approver?
       [antd/button {:type "primary" :on-click (fn [] (open! {:title "建立研发费用池" :path "/config/rd-pool" :initial {:currency "CNY"}
                                                               :fields [{:key :period :label "期间 (YYYY-MM)" :required? true}
                                                                        {:key :amount :label "费用池金额" :required? true}
                                                                        {:key :currency :label "币种" :type :select :options (mapv (fn [c] {:value c :label c}) ["CNY" "USD" "EUR" "GBP" "HKD"])}
                                                                        {:key :description :label "说明" :type :textarea}]}))} "建立研发费用池"])
     [w/record-table (:rows data)
      [(w/text-column :code "费用池") (w/text-column :period "期间") (w/text-column :amount "金额") (w/text-column :currency "币种")
       {:title "分摊" :dataIndex "allocation" :width 200
        :render (fn [v] (r/as-element (if v [antd/tag {:color "green"} (str "已分摊到 " (count (aget v "rows")) " 个项目")] [antd/tag "未分摊"])))}
       {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [status-badge %])}]
      (fn [row]
        [antd/space {:wrap true}
         [w/edit-button "预览分摊" #(detail! row)]
         (when (and approver? (= "draft" (:status row)))
           [w/edit-button "冻结" #(open! {:title "冻结费用池" :path (str "/config/rd-pool/" (:id row) "/publish") :fields [{:key :reason :label "说明" :type :textarea}]})])
         (when (and approver? (= "frozen" (:status row)) (nil? (:allocation row)))
           [w/edit-button "执行分摊" (fn [] (open! {:title "执行跨项目分摊" :path (str "/config/rd-pool/" (:id row) "/allocate")
                                                    :description "在每个有已批准工时的项目生成待审核的核算版本与人工成本条目; 幂等不重复计费."
                                                    :fields [{:key :reviewer_id :label "各项目核算版本的财务审批人" :type :select :required? true
                                                              :options (w/user-options (:users (:options context)))}
                                                             {:key :name :label "版本名称"}]}))])
         (when (and approver? (contains? #{"draft" "frozen"} (:status row)))
           [w/edit-button "退役" #(open! {:title "退役费用池" :path (str "/config/rd-pool/" (:id row) "/retire") :fields [{:key :reason :label "原因" :type :textarea :required? true}]})])])]]))


(defn- lock-section
  "F04 工时封期: 锁定期间后该期间工时不可提交/更正, 解锁保留审计."
  [{:keys [data open!]}]
  (let [approver? (shared/use-permission "pms:finance:approve")]
    [shared/panel "工时封期" "封期后该期间的工时提交与批准后更正均被拒绝; 解锁记录原因与操作人"
     (when approver?
       [antd/button {:type "primary" :on-click #(open! {:title "锁定期间" :path "/config/period-lock"
                                                         :fields [{:key :period :label "期间 (YYYY-MM)" :required? true}
                                                                  {:key :reason :label "封期原因" :type :textarea :required? true}]})} "锁定期间"])
     [w/record-table (:rows data)
      [(w/text-column :period "期间") (w/text-column :reason "原因") (w/text-column :created_by_name "操作人")
       {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [status-badge %])}]
      (fn [row]
        (when (and approver? (= "locked" (:status row)))
          [w/edit-button "解锁" #(open! {:title "解锁期间" :path (str "/config/period-lock/" (:id row) "/retire") :fields [{:key :reason :label "解锁原因" :type :textarea :required? true}]})]))]]))


(defn- kind-workspace
  "按类型加载配置列表并协调命令弹窗."
  [kind section detail-view]
  (let [resource (shared/use-resource (str "/config/" kind) {} [])
        options (shared/use-resource "/options" {} [])
        [dialog set-dialog!] (hooks/use-state nil)
        [detail set-detail!] (hooks/use-state nil)
        editable? (shared/use-permission "pms:config:edit")
        context {:kind kind :data (:data resource) :options (:data options) :editable? editable? :open! set-dialog! :detail! set-detail!
                 :refresh! (:refresh! resource)}]
    [:div
     [w/resource-view resource (fn [_] [section context])]
     (when dialog [config-dialog (merge dialog {:on-close #(set-dialog! nil)
                                                :on-saved (fn [_] (set-dialog! nil) ((:refresh! resource)))})])
     (when detail [detail-view detail #(set-detail! nil)])]))


(defn config-page
  "模板与规则页面."
  []
  [:div {:style {:padding 24}}
   [shared/page-heading "PROJECT MANAGEMENT" "模板与规则"
    "项目/计划/团队/文档/Gate 模板与编码/版本/生命周期规则的版本化配置, 修改不追溯覆盖已实例化项目" nil]
   [antd/tabs {:items [{:key "templates" :label "项目模板"
                        :children (r/as-element [kind-workspace "project-template" template-section template-detail])}
                       {:key "rules" :label "编码与版本规则"
                        :children (r/as-element [kind-workspace "coding-rule" rule-section rule-detail])}
                       {:key "pools" :label "研发费用池"
                        :children (r/as-element [kind-workspace "rd-pool" pool-section pool-preview])}
                       {:key "locks" :label "工时封期"
                        :children (r/as-element [kind-workspace "period-lock" lock-section rule-detail])}
                       {:key "approvals" :label "审批策略"
                        :children (r/as-element [kind-workspace "approval-policy" approval/policy-section approval/policy-detail])}]}]])
