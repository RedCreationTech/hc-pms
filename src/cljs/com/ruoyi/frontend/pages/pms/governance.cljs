(ns com.ruoyi.frontend.pages.pms.governance
  "需求,证据,风险与独立工程评审工作台."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.pages.pms.governance-forms :as forms]
    [com.ruoyi.frontend.pages.pms.approval :as approval]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn- file-size
  [bytes]
  (cond (nil? bytes) "-" (< bytes 1024) (str bytes " B") (< bytes 1048576) (str (.toFixed (/ bytes 1024) 1) " KiB")
        :else (str (.toFixed (/ bytes 1048576) 2) " MiB")))


(defn- review-actions
  "只向指定的独立审批人提供审批入口."
  [{:keys [base options editable? approve? open! policies chain-pending]} collection record]
  (let [state (:status record) path (str base "/" collection "/" (:id record))
        current (:currentUserId options)
        charter? (= "charters" collection)]
    [antd/space
     (when (and editable? (not (contains? #{"issues" "risks"} collection)) (contains? #{"draft" "rejected"} state))
       [w/edit-button "提交审批" #(open! {:title "提交独立审批" :path (str path "/submit")
                                      :fields (if charter?
                                                (approval/reviewer-fields policies "charter" (forms/reviewer-field options))
                                                [(forms/reviewer-field options)])})])
     (when (and approve? (= "in_review" state) (= current (:reviewer_id record)) (not= current (:submitted_by record))
                (not (and charter? (contains? chain-pending (:id record)))))
       [:<>
        [w/edit-button "批准" #(open! (forms/decision-dialog (str path "/decision") "approved" (if (= "reopen" (:review_action record)) "批准问题重开" "批准评审")))]
        [w/edit-button "驳回" #(open! (forms/decision-dialog (str path "/decision") "rejected" (if (= "reopen" (:review_action record)) "驳回问题重开" "驳回评审")))]])]))


(defn- charter-section
  "章程目标,范围和成功标准进入独立审批 (已发布 \"项目章程\" 审批策略时按策略逐级审批)."
  [{:keys [base model options editable? open!] :as context}]
  (let [policies (approval/use-policies)
        {:keys [flows]} (approval/use-project-flows base "charter" (hash (map (juxt :id :status) (:charters model))))
        context (assoc context :policies policies :chain-pending (approval/pending-ids flows))]
  [shared/panel "项目章程" "立项依据与责任共识"
   (when editable? [antd/button {:type "primary" :on-click #(open! (forms/charter-dialog base options))} "编制项目章程"])
   [w/record-table (:charters model)
    [(w/text-column :title "标题") (w/text-column :objective "项目目标") (w/text-column :scope "范围")
     (w/text-column :success_criteria "成功标准")
     {:title "初始预算" :dataIndex "initial_budget" :width 160
      :render (fn [_ row]
                (let [budget (aget row "initial_budget") currency (aget row "budget_currency")]
                  (if (and budget (not= "" budget))
                    (str budget " " currency)
                    (r/as-element [:span {:style {:color "#98a2b3"}} "未设定"]))))}
     {:title "授权PM" :dataIndex "authorized_pm_id" :width 140
      :render (fn [_ row]
                (let [uid (aget row "authorized_pm_id")
                      user (when (some? uid)
                             (first (filter #(= (str (:user_id %)) (str uid)) (:users options))))
                      label (when user (or (:nick_name user) (:user_name user)))]
                  (r/as-element
                   (if (some? uid)
                     [:span (or label (str uid))]
                     [:span {:style {:color "#98a2b3"}} "未指定"]))))}
     (w/state-column)]
    #(review-actions context "charters" %)]
   [approval/latest-flow-panel flows "章程审批进度"]]))


(defn- requirement-section
  "需求版本和责任人形成可追踪的URS台账."
  [{:keys [base model options editable? open! import! preview!]}]
  [shared/panel "URS 需求版本" "保留每次修订,追踪对应交付物和验证证据"
   (when editable? [antd/space
                    [antd/button {:on-click import!} "导入URS"]
                    [antd/button {:type "primary" :on-click #(open! (forms/requirement-dialog base options nil))} "新增URS需求"]])
   [w/record-table (:requirements model)
    [(w/text-column :code "编号") (w/text-column :revision "版本") (w/text-column :text "需求描述")
     (w/text-column :category "类别") {:title "优先级" :dataIndex "priority" :render #(get w/labels % %)}
     {:title "验证方式" :dataIndex "verification_method" :width 100
      :render (fn [v] (let [label (cond (= v "test") "测试" (= v "inspection") "检验"
                                         (= v "demonstration") "演示" (= v "analysis") "分析" :else nil)]
                         (r/as-element (if label [antd/tag {:color "geekblue"} label]
                                           [:span {:style {:color "#98a2b3"}} "未设定"]))))}]
    (when editable? (fn [row] [antd/space {:wrap true}
                              [w/edit-button "新修订" #(open! (forms/requirement-dialog base options row))]
                              [w/edit-button "级联影响" #(preview! {:collection "requirements" :id (:id row) :label (str "URS需求 " (:code row))})]
                              (when (= "registered" (:status row))
                                [w/edit-button "作废" #(open! (forms/discard-dialog (str base "/requirements/" (:id row) "/discard") "URS需求"))])
                              (when (= "discarded" (:status row))
                                [w/edit-button "恢复" #(open! (forms/restore-dialog (str base "/requirements/" (:id row) "/restore") "URS需求"))])]))]])


(defn- latest-document-ids
  "按文档编号取最新版本ID, 供批量下载打包."
  [documents]
  (->> (vals (group-by :code documents))
       (mapv #(->> % (apply max-key :revision) :id))))


(defn- release-tag
  "文档发布状态的语义标签."
  [status]
  (let [[text color] (get {"registered" ["已登记" "default"] "in_review" ["待发布审批" "blue"]
                           "approved" ["已发布" "green"] "rejected" ["已退回" "red"] "discarded" ["已作废" "red"]}
                          status [status "default"])]
    [antd/tag {:color color} text]))


(defn- collection-label
  [k]
  (if (= "" k) "未归集" k))


(def class-labels {"public" "公开" "internal" "内部" "confidential" "机密"})


(defn- tree-section
  "C04 多层下钻: 阶段 -> 结构节点 -> 密级 -> 最新版本文档, 只读派生."
  [{:keys [model document!]}]
  (let [tree (:document_tree model)
        node (fn [key title children] {:key key :title (r/as-element title) :children children})
        data (mapv (fn [stage]
                     (node (str "s:" (:stage stage)) [:span [:strong (:stage stage)] (str "  " (:count stage) " 份")]
                           (mapv (fn [n]
                                   (node (str "s:" (:stage stage) ":n:" (:structure_node n)) [:span (:structure_node n) (str "  " (:count n) " 份")]
                                         (mapv (fn [c]
                                                 (node (str "s:" (:stage stage) ":n:" (:structure_node n) ":c:" (:classification c))
                                                       [antd/tag {:color (get {"public" "green" "internal" "blue" "confidential" "red"} (:classification c))} (str (get class-labels (:classification c) (:classification c)) " " (:count c))]
                                                       (mapv (fn [d] {:key (:id d) :isLeaf true
                                                                      :title (r/as-element [antd/button {:type "link" :size "small" :style {:padding 0} :on-click #(document! d)}
                                                                                            (str (:code d) " V" (:revision d) " · " (:title d) " · " (get {"registered" "已登记" "in_review" "待发布审批" "approved" "已发布" "rejected" "已退回"} (:status d) (:status d)))])})
                                                             (:documents c))))
                                               (:classifications n))))
                                 (:nodes stage))))
                   tree)]
    [shared/panel "文档多层下钻" "阶段 -> 结构节点 -> 密级 -> 最新版本证据 (编号/版本/状态), 点击文档查看正文 (机密文档须密级权限)" nil
     (if (empty? tree)
       [:span {:style {:color "#8793a3"}} "暂无证据文档."]
       [antd/tree {:treeData (clj->js data) :defaultExpandAll true :selectable false :showLine true}])]))


(defn- collection-section
  "按每个文档编号的最新版本只读聚合阶段/结构节点/密级, 修订不重复计数; 最新版本已作废的编号不计入并单独提示."
  [{:keys [model]}]
  (let [col (:document_collection model)
        total (:total col 0)
        discarded (get col :discarded-count 0)
        class-label {"public" "公开" "internal" "内部" "confidential" "机密"}]
    [shared/panel "文档归集视图" "按每个文档编号的最新版本聚合阶段/结构节点/密级, 供分层查看; 修订不重复计数, 已作废不计入"
     (if (and (zero? total) (zero? discarded))
       [:span {:style {:color "#8793a3"}} "暂无证据文档, 登记后此处按阶段/结构/密级归集."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "最新版本证据 " total)]
         (when (pos? discarded)
           [antd/tag {:color "red"} (str "已作废 " discarded " 未计入")])
         (for [{:keys [classification count]} (:by-classification col)]
           ^{:key classification} [antd/tag (str (get class-label classification classification) " " count)])]
        (when (pos? total)
          ^{:key "by-stage"} [:div
                              [:span {:style {:fontWeight 500}} "按阶段: "]
                              [antd/space {:wrap true}
                               (for [{:keys [key count]} (:by-stage col)]
                                 ^{:key (str "s-" key)} [antd/tag {:color (if (= "" key) "default" "purple")}
                                                         (str (collection-label key) " · " count)])]])
        (when (pos? total)
          ^{:key "by-node"} [:div
                             [:span {:style {:fontWeight 500}} "按结构节点: "]
                             [antd/space {:wrap true}
                              (for [{:keys [key count]} (:by-structure-node col)]
                                ^{:key (str "n-" key)} [antd/tag (str (collection-label key) " · " count)])]])])]))


(defn- coverage-section
  "按每个需求编号的最新有效版本只读聚合验证方式声明覆盖度: 四类方法各自计数与覆盖率; 修订不重复计数, 已作废不计入."
  [{:keys [model]}]
  (let [cov (:verification_coverage model)
        total (:total cov 0)
        undeclared (:undeclared cov 0)
        pct (:coverage-pct cov 0)
        method-label {"test" "测试" "inspection" "检验" "demonstration" "演示" "analysis" "分析"}]
    [shared/panel "验证方式覆盖度" "按每个需求编号的最新有效版本统计验证方式声明情况; 修订不重复计数, 已作废不计入"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无URS需求, 登记后可在此查看验证方式覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "最新版本需求 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已声明验证方式 " pct "%")]
         (when (pos? undeclared)
           [antd/tag {:color "orange"} (str "未设定 " undeclared)])]
        [:div
         [:span {:style {:fontWeight 500}} "按验证方式: "]
         [antd/space {:wrap true}
          (for [{:keys [method count]} (:by-method cov)]
            ^{:key method} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get method-label method method) " · " count)])]]])]))


(defn- release-coverage-section
  "按每个文档编号的最新有效版本只读聚合发布审批链覆盖度: 已发布/待审/未提交/已驳回与已发布率; 修订不重复计数, 已作废不计入."
  [{:keys [model]}]
  (let [rel (:release_coverage model)
        total (:total rel 0)
        approved (:approved rel 0)
        in-review (:in-review rel 0)
        registered (:registered rel 0)
        rejected (:rejected rel 0)
        pct (:released-pct rel 0)]
    [shared/panel "证据发布覆盖度" "按每个文档编号的最新有效版本统计发布审批进度; 修订不重复计数, 已作废不计入"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无证据文档, 登记后可在此查看发布覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "覆盖文档 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已发布率 " pct "%")]
         [antd/tag {:color "green"} (str "已发布 " approved)]
         (when (pos? in-review)
           [antd/tag {:color "processing"} (str "待审 " in-review)])
         (when (pos? registered)
           [antd/tag {:color "default"} (str "未提交 " registered)])
         (when (pos? rejected)
           [antd/tag {:color "red"} (str "已驳回 " rejected)])]])]))


(defn- document-section
  "列出可校验的真实证据文档及不可变版本, 支持打包批量下载, 密级过滤与独立发布审批."
  [{:keys [base model options editable? approve? open! document! preview! upload!]}]
  (let [[class-filter set-class-filter!] (hooks/use-state nil)
        ids (latest-document-ids (:documents model)) current (:currentUserId options)
        all-docs (:documents model)
        categories (:document_categories (first (:template_instances model)))
        docs (if (nil? class-filter) all-docs (filterv #(= class-filter (:classification %)) all-docs))]
    [shared/panel "文档与版本证据" "文本与真实文件 (PDF/图片/Office 等) 均形成不可变版本, 服务器计算并复核 SHA256; 正式签发须经独立审批, 新修订不漂移旧批准"
     [antd/space {:wrap true}
      (when editable? [antd/button {:type "primary" :on-click #(upload! {:document nil :categories categories})} "上传证据文件"])
      (when editable? [antd/button {:on-click #(open! (forms/document-dialog base nil categories))} "登记证据文档"])
      (when (seq ids)
        [antd/button {:on-click
                      (fn []
                        (api/pms-batch-download-documents
                          (str base "/documents/batch-download") ids "证据文档.zip"
                          (fn [e] (antd/error! (.-message e)))))}
         "批量下载"])
      [antd/select {:value class-filter :placeholder "全部密级" :allowClear true :aria-label "密级筛选"
                    :style {:width 140} :options [{:value "public" :label "公开"}
                                                  {:value "internal" :label "内部"}
                                                  {:value "confidential" :label "机密"}]
                    :onChange #(set-class-filter! (not-empty %))}]]
     [w/record-table docs
      [(w/text-column :code "文档编号") (w/text-column :title "标题") (w/text-column :revision "版本")
       {:title "文件" :key "file" :width 200 :ellipsis true
        :render (fn [_ row] (r/as-element [:span (aget row "filename")
                                           [:span {:style {:color "#98a2b3" :marginLeft 6 :fontSize 12}}
                                            (if (= "file" (aget row "content_kind")) (file-size (aget row "byte_size")) "文本")]]))}
       (w/text-column :category "类别")
       {:title "密级" :dataIndex "classification"
        :render #(get {"public" "公开" "internal" "内部" "confidential" "机密"} % %)}
       (w/text-column :stage "阶段")
       {:title "发布状态" :dataIndex "status" :render #(r/as-element (release-tag %))}
       (w/text-column :sha256 "SHA256摘要")]
      (fn [row]
        [antd/space {:wrap true}
         [w/edit-button (if (= "file" (:content_kind row)) "预览/下载" "查看内容") #(document! row)]
         (when (and editable? (contains? #{"registered" "rejected"} (:status row)))
           [w/edit-button "提交发布"
            #(open! {:title "提交文档发布审批" :path (str base "/documents/" (:id row) "/submit")
                     :description "选择具备质量审批权限的独立审核人, 冻结当前版本进入发布评审."
                     :fields [(forms/reviewer-field options)]})])
         (when (and approve? (= "in_review" (:status row)) (= current (:reviewer_id row)) (not= current (:submitted_by row)))
           [:<>
            [w/edit-button "批准发布" #(open! (forms/decision-dialog (str base "/documents/" (:id row) "/decision") "approved" "正式签发发布"))]
            [w/edit-button "驳回" #(open! (forms/decision-dialog (str base "/documents/" (:id row) "/decision") "rejected" "驳回文档发布"))]])
         (when editable? [w/edit-button "新版本" (fn [] (if (= "file" (:content_kind row))
                                                       (upload! {:document row :categories categories})
                                                       (open! (forms/document-dialog base row categories))))])
         (when editable? [w/edit-button "级联影响" #(preview! {:collection "documents" :id (:id row) :label (str "证据文档 " (:code row))})])
         (when (and editable? (contains? #{"registered" "rejected"} (:status row)))
           [w/edit-button "作废" #(open! (forms/discard-dialog (str base "/documents/" (:id row) "/discard") "证据文档"))])
         (when (and editable? (= "discarded" (:status row)))
           [w/edit-button "恢复" #(open! (forms/restore-dialog (str base "/documents/" (:id row) "/restore") "证据文档"))])])]]))


(defn- appointment-section
  "签发不可变的项目成员任命书, 内容由服务器按当前团队快照生成."
  [{:keys [base model editable? open! appointment!]}]
  [shared/panel "项目成员任命书" "任命内容绑定签发时的团队快照, 再次任命保留不可变旧版本"
   (when editable? [antd/button {:type "primary"
                                 :on-click #(open! {:title "签发项目成员任命书"
                                                    :path (str base "/appointments")
                                                    :description "系统将读取当前项目成员生成不可变任命书, 生效日期与说明写入正文, 客户端不能伪造内容."
                                                    :fields [{:key :issued_on :label "任命生效日期" :type :date :required? true}
                                                             {:key :note :label "任命说明" :type :textarea :max 500}]})}
                    "签发任命书"])
   [w/record-table (:appointments model)
    [(w/text-column :code "编号") (w/text-column :revision "版本") (w/text-column :headcount "团队人数")
     (w/text-column :issued_on "生效日期") (w/text-column :issued_by "签发人")
     (w/text-column :snapshot_sha256 "快照摘要") (w/state-column)]
    (fn [row] [w/edit-button "查看任命书" #(appointment! row)])]])


(defn- quadrant-cell
  "渲染干系人权力-利益象限管理策略标签, 未绑定项目成员责任人时追加提示."
  [row]
  (let [q (aget row "stakeholder_quadrant")
        unbound (true? (aget row "stakeholder_unbound"))
        label (get {"manage-close" "重点管理" "keep-satisfied" "保持满意"
                    "keep-informed" "保持知会" "monitor" "持续监控"} q "-")
        color (get {"manage-close" "red" "keep-satisfied" "orange"
                    "keep-informed" "blue" "monitor" "default"} q "default")]
    (r/as-element [antd/space {:wrap true}
                   [antd/tag {:color color} label]
                   (when unbound [antd/tag {:color "volcano"} "未绑定责任人"])])))


(defn stakeholder-section
  "登记项目干系人并保留不可变修订."
  [{:keys [base model options editable? open! preview!]}]
  [shared/panel "干系人识别" "记录利益相关者职责, 关注度与影响力, 按权力-利益矩阵给出管理策略, 修订保留历史"
   (when editable? [antd/button {:on-click #(open! (forms/stakeholder-dialog base options nil))} "登记干系人"])
   [w/record-table (:stakeholders model)
    [(w/text-column :code "编号") (w/text-column :name "名称") (w/text-column :role "职责")
     (w/text-column :category "分类") (w/text-column :interest "关注度") (w/text-column :influence "影响力")
     {:title "管理策略" :dataIndex "stakeholder_quadrant" :width 200 :render (fn [_ row] (quadrant-cell row))}
     (w/state-column)]
    (when editable? (fn [row] [antd/space {:wrap true}
                         [w/edit-button "新修订" #(open! (forms/stakeholder-dialog base options row))]
                         [w/edit-button "级联影响" #(preview! {:collection "stakeholders" :id (:id row) :label (str "干系人 " (:code row))})]
                         (when (= "active" (:status row))
                           [w/edit-button "作废" #(open! (forms/discard-dialog (str base "/stakeholders/" (:id row) "/discard") "干系人"))])
                         (when (= "discarded" (:status row))
                           [w/edit-button "恢复" #(open! (forms/restore-dialog (str base "/stakeholders/" (:id row) "/restore") "干系人"))])]))]])


(defn- raci-conflict-text
  "把逐活动RACI缺口拼成一行行可读文本."
  [conflicts]
  (str/join "；"
            (for [c conflicts]
              (str (:activity c) ": "
                   (str/join "、"
                             (remove nil?
                                     [(when (:missing-accountable? c) "缺少负责(A)")
                                      (when (:missing-responsible? c) "缺少执行(R)")]))))))


(defn raci-section
  "RACI职责矩阵, 逐活动提示缺失的负责(A)或执行(R)."
  [{:keys [base model editable? open!]}]
  [shared/panel "RACI职责矩阵" "每项活动至多一个负责(A), 缺A或缺R在此提示"
   (when editable? [antd/button {:on-click #(open! (forms/raci-dialog base model))} "指派RACI职责"])
   (let [conflicts (:raci_conflicts model)]
     (when (seq conflicts)
       [antd/alert {:type "warning" :showIcon true :message "RACI完整性缺口"
                    :style {:marginBottom 12} :description (raci-conflict-text conflicts)}]))
   [w/record-table (:raci model)
    [(w/text-column :activity "活动") (w/text-column :stakeholder_name "干系人")
     {:title "职责" :dataIndex "responsibility" :render #(get {"R" "执行 R" "A" "负责 A" "C" "咨询 C" "I" "知会 I"} % %)}
     {:title "R职责负载" :dataIndex "raci_r_load" :width 160
      :render (fn [_ row]
                (let [load (aget row "raci_r_load") over (true? (aget row "raci_overloaded"))]
                  (r/as-element [antd/space {:wrap true}
                                 [antd/tag {:color (if (pos? load) "blue" "default")} (str "执行 R x " load)]
                                 (when over [antd/tag {:color "red"} "职责过载"])])))}
     (w/state-column)]
    nil]])


(defn- owner-load-column
  "跨问题/风险/行动统一展示责任人当前未关闭事项负载, 达到阈值时提示负载过重, 只读派生列."
  []
  {:title "责任人负载" :dataIndex "owner_open_load" :width 160
   :render (fn [_ row]
             (let [load (aget row "owner_open_load") over (true? (aget row "owner_overloaded"))]
               (r/as-element [antd/space {:wrap true}
                              [antd/tag {:color (if (pos? load) "blue" "default")} (str "未关闭 x " load)]
                                     (when over [antd/tag {:color "red"} "负载过重"])])))})


(defn- due-countdown-column
  "按服务端派生的剩余天数渲染未决事项到期倒计时: 逾期红色, 今天到期橙色, 临期金色, 尚远蓝色, 已完成或无到期日显示短横; 只读派生列."
  [js-key]
  {:title "到期倒计时" :dataIndex js-key :width 150
   :render (fn [_ row]
             (let [v (aget row js-key)]
               (r/as-element
                 (cond
                   (not (number? v)) [:span {:style {:color "#98a2b3"}} "-"]
                   (neg? v) [antd/tag {:color "red"} (str "已逾期 " (- v) " 天")]
                   (zero? v) [antd/tag {:color "volcano"} "今天到期"]
                   (<= v 3) [antd/tag {:color "gold"} (str "剩 " v " 天临期")]
                   :else [antd/tag {:color "blue"} (str "剩 " v " 天")]))))})


(defn comm-plan-section
  "沟通计划受控调整, 由最新版本生成受控会议形成闭环."
  [{:keys [base model options editable? open!]}]
  [shared/panel "沟通计划" "维护渠道与节奏, 由最新版本生成受控会议并回写来源"
   (when editable? [antd/button {:on-click #(open! (forms/comm-plan-dialog base model options nil))} "登记沟通计划"])
   [w/record-table (:comm_plans model)
    [(w/text-column :code "编号") (w/text-column :objective "沟通目标") (w/text-column :channel "渠道")
     (w/text-column :frequency "频率") (w/text-column :next_date "下次日期")
     {:title "沟通到期" :dataIndex "comm_overdue" :width 120
      :render (fn [_ row]
                (let [overdue (true? (aget row "comm_overdue")) days (aget row "comm_days_until")]
                  (r/as-element
                   (cond overdue [antd/tag {:color "red"} "沟通已到期"]
                         (and (number? days) (pos? days)) [antd/tag {:color "gold"} (str days " 天后沟通")]
                         (number? days) [antd/tag {:color "default"} "已过期"]
                         :else [antd/tag "未排期"]))))}
     (w/text-column :last_meeting_id "最近会议") (w/state-column)]
    (when editable?
      (fn [plan]
        [antd/space
         [w/edit-button "标记已沟通" #(open! (forms/comm-plan-log-dialog base plan))]
         [w/edit-button "新修订" #(open! (forms/comm-plan-dialog base model options plan))]
         [w/edit-button "生成会议" #(open! (forms/comm-plan-meeting-dialog base plan))]]))]])


(defn- trace-section
  "显式呈现需求到任务和证据的覆盖关系."
  [{:keys [base model planning editable? open!]}]
  [shared/panel "需求追踪矩阵" "满足关系与验证关系分别保留"
   (when editable? [antd/button {:on-click #(open! (forms/trace-dialog base model planning))} "建立需求追踪"])
   [w/record-table (:traces model)
    [{:title "URS需求" :dataIndex "requirement_id" :render #(w/related-label (:requirements model) :id :code %)}
     {:title "关联类型" :dataIndex "target_kind" :render #(if (= % "task") "WBS任务" "证据文档版本")}
     {:title "关联对象" :key "target" :render (fn [_ row]
                                            (let [item (js->clj row :keywordize-keys true)]
                                              (if (= "task" (:target_kind item)) (w/related-label (:tasks planning) :task_id :name (:target_id item))
                                                  (w/related-label (:documents model) :id :title (:target_id item)))))}
     {:title "关系" :dataIndex "relation" :render #(if (= % "satisfies") "满足需求" "验证需求")}
     {:title "阶段" :dataIndex "phase" :width 110 :render #(get forms/phase-labels % (or % "—"))}
     {:title "偏差" :dataIndex "deviation_level" :width 200
      :render (fn [v row] (r/as-element (cond (or (nil? v) (= v "none")) [:span {:style {:color "#98a2b3"}} "无偏差"]
                                              :else [antd/space [antd/tag {:color (get {"minor" "gold" "major" "orange" "blocker" "red"} v "default")} (get forms/deviation-labels v v)]
                                                     [:span {:style {:fontSize 12 :color "#718096"}} (aget row "deviation_note")]])))}]
    nil]])


(defn- gap-tags
  "把一条需求的缺链集合渲染为标签组, 无缺链时显示追踪完整."
  [missing]
  (let [gaps (js->clj missing)
        text {"satisfies" "缺设计满足" "verifies" "缺验证证据"}]
    (if (empty? gaps)
      [antd/tag {:color "green"} "追踪完整"]
      (into [antd/space {:wrap true}]
            (for [g gaps] ^{:key g} [antd/tag {:color "red"} (get text g g)])))))


(defn- traceability-section
  "URS追踪完整性检查: 按需求当前版本列出设计满足与验证证据缺链, 修订后须重新追踪."
  [{:keys [model]}]
  (let [report (:traceability model) summary (:trace_summary model)]
    [shared/panel "URS追踪完整性检查" "按需求当前版本检查设计满足与验证证据是否齐备, 修订产生新版本后须重新追踪"
     [antd/space {:wrap true :style {:marginBottom 12}}
      [antd/tag (str "需求版本 " (:requirements summary 0))]
      [antd/tag {:color "green"} (str "整链齐备 " (:fully-traced summary 0))]
      [antd/tag {:color (cond (>= (get summary :coverage-pct 0) 100) "green" (pos? (get summary :coverage-pct 0)) "gold" :else "default")}
       (str "整链覆盖率 " (get summary :coverage-pct 0) "%")]
      [antd/tag {:color "blue"} (str "设计覆盖 " (get summary :design-pct 0) "% · 验证覆盖 " (get summary :verification-pct 0) "%")]
      [antd/tag {:color (if (pos? (:missing-design summary 0)) "orange" "default")}
       (str "缺设计满足 " (:missing-design summary 0))]
      [antd/tag {:color (if (pos? (:missing-verification summary 0)) "red" "default")}
       (str "缺验证证据 " (:missing-verification summary 0))]
      (for [[phase n] (get summary :by-phase)] ^{:key phase}
        [antd/tag (str (get forms/phase-labels (name phase) (name phase)) " " n)])
      (for [[level n] (get summary :deviations) :when (pos? n)] ^{:key level}
        [antd/tag {:color (get {"minor" "gold" "major" "orange" "blocker" "red"} (name level) "default")} (str (get forms/deviation-labels (name level) (name level)) "偏差 " n)])]
     [w/record-table report
      [(w/text-column :code "URS编号") (w/text-column :revision "版本")
       {:title "优先级" :dataIndex "priority" :render #(get w/labels % %)}
       (w/text-column :design_links "设计满足数") (w/text-column :verification_links "验证证据数")
       {:title "阶段" :dataIndex "phases" :width 160 :render (fn [v] (str/join ", " (map #(get forms/phase-labels % %) (js->clj v))))}
       {:title "最严重偏差" :dataIndex "worst_deviation" :width 110
        :render (fn [v] (r/as-element (if (or (nil? v) (= v "none")) [:span {:style {:color "#98a2b3"}} "无"] [antd/tag {:color (get {"minor" "gold" "major" "orange" "blocker" "red"} v)} (get forms/deviation-labels v v)])))}
       {:title "缺链检查" :dataIndex "missing" :render #(r/as-element (gap-tags %))}
       (w/state-column)]
      nil]]))


(defn- risk-actions
  "发生,复评和独立关闭保持明确操作路径; 超阈值升级须独立质量审批人确认."
  [{:keys [base model options editable? approve? open!] :as context} risk]
  [antd/space {:wrap true}
   (when (and approve? (:escalated risk) (= "pending" (:escalation_state risk))
              (not= (:currentUserId options) (:created_by risk)))
     [w/edit-button "确认升级处置" #(open! (forms/risk-escalation-dialog base risk))])
   (when (and editable? (contains? #{"open" "mitigated" "materialized" "closed"} (:status risk)))
     [w/edit-button "提交复评" #(open! (forms/risk-review-dialog base options (:documents model) risk))])
   (when (and editable? (= "open" (:status risk)))
     [w/edit-button "风险发生,转问题"
      #(open! {:title "风险转问题" :path (str base "/risks/" (:id risk) "/materialize")
               :initial {:title (:title risk)} :fields [{:key :title :label "问题描述" :required? true}]})])
   [review-actions context "risks" risk]])


(defn- risk-section
  "风险台账保留应对,复评期限与独立关闭状态."
  [{:keys [base model options editable? open!] :as context}]
  [shared/panel "项目风险" "风险持续复评,实际发生关联问题,关闭需要证据与独立审核; 评分达到阈值的重大风险自动升级, 未经独立确认不得自行缓解"
   (when editable?
     [antd/space
      [antd/button {:on-click #(open! (forms/risk-dialog base options))} "登记项目风险"]
      [antd/button {:type "primary" :ghost true :on-click #(open! (forms/risk-library-dialog base (:risk_library model) options))} "从典型风险库选用"]])
   [w/record-table (:risks model)
    [(w/text-column :title "风险") (w/text-column :probability "概率") (w/text-column :impact "影响")
     (w/text-column :score "评分")
     {:title "来源" :dataIndex "source_key" :width 90
      :render (fn [_ row] (when (aget row "source_key") (r/as-element [antd/tag {:color "purple"} "风险库"])))}
     {:title "超阈值升级" :dataIndex "escalation_state" :width 180
      :render (fn [_ row]
                (let [esc (aget row "escalated") state (aget row "escalation_state")]
                  (r/as-element
                   (cond
                     (and (not esc) (true? (aget row "issue_escalation_suggested"))) [antd/tag {:color "orange"} "逾期未升级, 建议追溯升级"]
                     (not esc) [:span {:style {:color "#98a2b3"}} "未触发"]
                     (= state "pending") [antd/tag {:color "red"} (str "待升级确认 / " (aget row "escalation_level") (when (= "overdue_retroactive" (aget row "escalation_source")) " (逾期追溯)"))]
                     (= state "acknowledged") [antd/tag {:color "green"} "升级已确认"]
                     (= state "waived") [antd/tag {:color "blue"} "升级已豁免"]
                     :else [antd/tag state]))))}
     {:title "复审重评" :dataIndex "review_proposed_score" :width 150
      :render (fn [_ row]
                (let [proposed (aget row "review_proposed_score")]
                  (r/as-element
                   (if (some? proposed)
                     [antd/tag {:color "orange"} (str (aget row "score") " → " proposed " 待批准")]
                     [:span {:style {:color "#98a2b3"}} "—"]))))}
     {:title "应对策略" :dataIndex "response_strategy" :width 110
      :render (fn [_ row]
                (let [s (aget row "response_strategy")
                      label (cond (= s "avoid") "规避" (= s "transfer") "转移"
                                  (= s "mitigate") "减轻" (= s "accept") "接受" :else nil)]
                  (r/as-element (if label [antd/tag {:color "geekblue"} label]
                                    [:span {:style {:color "#98a2b3"}} "未设定"]))))}
     (w/text-column :mitigation "应对措施") (w/text-column :review_due_date "下次复评")
     {:title "复评提醒" :dataIndex "review_overdue" :render #(when % (r/as-element [antd/tag {:color "red"} "复评已逾期"]))}
     (due-countdown-column "review_due_in_days")
     {:title "转出问题" :dataIndex "risk_issue_title" :width 160
      :render (fn [_ row]
                (let [t (aget row "risk_issue_title")]
                  (r/as-element (if (some? t) [antd/tag {:color "cyan"} t] [:span {:style {:color "#98a2b3"}} "未转出"]))))}
     (owner-load-column)
     (w/state-column)] #(risk-actions context %)]])


(defn- issue-section
  "问题解决必须附证据并交独立人员验证; 阻断级问题登记即自动升级, 未经独立确认不得提交解决."
  [{:keys [base model options editable? approve? open!] :as context}]
  [shared/panel "问题闭环" "提交解决证据后由独立审批人验证关闭; 阻断级问题自动升级, 待独立确认处置后方可提交解决"
   (when editable? [antd/button {:on-click #(open! (forms/issue-dialog base options))} "登记项目问题"])
   [w/record-table (:issues model)
    [(w/text-column :title "问题") {:title "严重程度" :dataIndex "severity" :render #(r/as-element [w/badge %])}
     (w/text-column :due_date "到期日期")
     {:title "来源风险" :dataIndex "issue_source_risk_title" :width 160
      :render (fn [_ row]
                (let [t (aget row "issue_source_risk_title")]
                  (r/as-element (if (some? t) [antd/tag {:color "geekblue"} t] [:span {:style {:color "#98a2b3"}} "手工登记"]))))}
     {:title "逾期预警" :dataIndex "issue_overdue" :width 130
      :render (fn [_ row]
                (let [overdue (true? (aget row "issue_overdue")) critical (true? (aget row "issue_critical"))]
                  (r/as-element
                   [antd/space {:wrap true}
                    (when critical [antd/tag {:color "red"} "阻断级"])
                    (when overdue [antd/tag {:color "volcano"} "已逾期"])])))}
     (due-countdown-column "issue_due_in_days")
     {:title "超阈值升级" :dataIndex "escalation_state" :width 180
      :render (fn [_ row]
                (let [esc (aget row "escalated") state (aget row "escalation_state")]
                  (r/as-element
                   (cond
                     (and (not esc) (true? (aget row "issue_escalation_suggested"))) [antd/tag {:color "orange"} "逾期未升级, 建议追溯升级"]
                     (not esc) [:span {:style {:color "#98a2b3"}} "未触发"]
                     (= state "pending") [antd/tag {:color "red"} (str "待升级确认 / " (aget row "escalation_level") (when (= "overdue_retroactive" (aget row "escalation_source")) " (逾期追溯)"))]
                     (= state "acknowledged") [antd/tag {:color "green"} "升级已确认"]
                     (= state "waived") [antd/tag {:color "blue"} "升级已豁免"]
                     :else [antd/tag state]))))}
     (owner-load-column)
     (w/text-column :resolution "解决说明")
     {:title "评审事项" :dataIndex "review_action" :render #(if (= % "reopen") "申请重开" "解决验证")} (w/state-column)]
    (fn [issue]
      [antd/space
       (when (and approve? (:escalated issue) (= "pending" (:escalation_state issue))
                  (not= (:currentUserId options) (:created_by issue)))
         [w/edit-button "确认升级处置" #(open! (forms/issue-escalation-dialog base issue))])
       (when (and editable? (:issue_escalation_suggested issue))
         [w/edit-button "追溯升级" #(open! {:title "逾期追溯升级" :path (str base "/issues/" (:id issue) "/escalate-overdue")
                                          :description "问题已逾期且未升级: 追溯升级到经理层并进入待独立确认, 确认前不能提交解决."
                                          :fields [{:key :reason :label "升级说明" :type :textarea}]})])
       (when (and editable? (= "closed" (:status issue)))
         [w/edit-button "申请重开" #(open! (forms/issue-reopen-dialog base options (:documents model) issue))])
       (when (and editable? (contains? #{"open" "rejected"} (:status issue)))
         [w/edit-button "提交解决证据"
          #(open! {:title "提交问题解决验证" :path (str base "/issues/" (:id issue) "/resolve")
                   :fields [{:key :resolution :label "解决方案与验证结果" :type :textarea :required? true}
                            (forms/evidence-field (:documents model)) (forms/reviewer-field options)]})])
       (when (and editable? (contains? #{"open" "rejected"} (:status issue)))
         [w/edit-button "转派" #(open! (forms/issue-reassign-dialog base options issue))])
       [review-actions context "issues" issue]])]])


(defn- meeting-section
  "从会议纪要产生明确行动,避免只记录不执行.会前资料绑定项目内真实文档版本."
  [{:keys [base model options planning editable? open!]}]
  [shared/panel "会议与决策" "参会人员,正式纪要,会议类型,主计划基线引用与会前资料版本保留在项目中"
   (when editable? [antd/button {:on-click #(open! (forms/meeting-dialog base options (:documents model) (:baselines planning)))} "登记项目会议"])
   [w/record-table (:meetings model)
    [(w/text-column :title "会议主题")
     {:title "类型" :dataIndex "meeting_type" :width 100 :render #(get forms/meeting-type-labels % (or % "常规会议"))}
     (w/text-column :held_on "会议日期") (w/text-column :minutes "会议纪要")
     {:title "主计划基线" :dataIndex "baseline_revision" :width 150
      :render (fn [_ row] (let [rev (aget row "baseline_revision") stale (true? (aget row "baseline_stale"))]
                            (r/as-element (if (some? rev)
                                            [antd/space [antd/tag {:color "blue"} (str "计划修订 " rev)] (when stale [antd/tag {:color "red"} "基线已失效"])]
                                            [:span {:style {:color "#98a2b3"}} "未引用"]))))}
     {:title "会前资料" :dataIndex "material_ids" :render #(r/as-element [antd/tag {:color (if (pos? (count %)) "blue" "default")} (count %)])}
     {:title "行动闭环" :dataIndex "meeting_open_actions" :width 180
      :render (fn [_ row]
                (let [total (aget row "meeting_action_total")
                      open (aget row "meeting_open_actions")
                      overdue (aget row "meeting_overdue_actions")]
                  (r/as-element
                   [antd/space {:wrap true}
                    (cond (zero? total) [:span {:style {:color "#98a2b3"}} "无行动"]
                          (zero? open) [antd/tag {:color "green"} "行动已全部闭环"]
                          :else [antd/tag {:color "gold"} (str "未完成 " open "/" total)])
                    (when (and (number? overdue) (pos? overdue)) [antd/tag {:color "red"} (str "逾期 " overdue)])])))}]
    (when editable? (fn [meeting] [w/edit-button "形成行动" #(open! (forms/action-dialog base options meeting))]))]])


(defn- action-actions
  "会议行动的转任务,完成提交与独立核验操作路径."
  [{:keys [base model options editable? approve? open!]} action]
  (let [state (:status action) current (:currentUserId options)]
    [antd/space {:wrap true}
     (when (and editable? (= "open" state))
       [w/edit-button "转为WBS任务"
        #(open! {:title "会议行动转WBS任务" :path (str base "/actions/" (:id action) "/task")
                 :initial {:duration_days 1}
                 :fields [{:key :wbs_code :label "WBS编号"}
                          {:key :start_date :label "开始日期" :type :date :required? true}
                          {:key :duration_days :label "工作日工期" :type :number :min 1 :required? true}]})])
     (when (and editable? (contains? #{"open" "rejected"} state))
       [w/edit-button "提交完成" #(open! (forms/action-complete-dialog base options (:documents model) action))])
     (when (and approve? (= "in_review" state) (= current (:reviewer_id action)) (not= current (:submitted_by action)))
       [:<>
        [w/edit-button "批准关闭" #(open! (forms/decision-dialog (str base "/actions/" (:id action) "/verify") "approved" "核验通过并关闭行动"))]
        [w/edit-button "驳回" #(open! (forms/decision-dialog (str base "/actions/" (:id action) "/verify") "rejected" "驳回行动完成"))]])]))


(defn- action-section
  "会议行动转为实际WBS任务, 完成须证据与独立核验并提示逾期."
  [{:keys [base model editable? open!] :as context}]
  [shared/panel "会议行动" "转换后任务进入项目计划,重复转换保持同一任务;完成需证据与独立核验" nil
   [w/record-table (:actions model)
    [(w/text-column :title "行动内容") (w/text-column :due_date "到期日期")
     {:title "逾期" :dataIndex "action_overdue" :render #(when % (r/as-element [antd/tag {:color "red"} "已逾期"]))}
     (due-countdown-column "action_due_in_days")
     (owner-load-column)
     (w/state-column) (w/text-column :result "完成说明") (w/text-column :target_task_id "关联任务")]
    #(action-actions context %)]])


(defn- change-section
  "变更审批保留五维影响与独立判断."
  [{:keys [base model editable? open!] :as context}]
  [shared/panel "项目变更控制" "批准变更保留依据,计划调整仍进入计划修订与基线审批"
   (when editable? [antd/button {:on-click #(open! (forms/change-dialog base))} "提出项目变更"])
   [w/record-table (:changes model)
    [(w/text-column :title "变更") (w/text-column :reason "原因") (w/text-column :scope_impact "范围影响")
     (w/text-column :schedule_impact "进度影响") (w/text-column :cost_impact "成本影响")
     {:title "量化影响" :dataIndex "schedule_impact_days" :width 220
      :render (fn [_ row]
                (let [days (aget row "schedule_impact_days")
                      cost (aget row "cost_impact_amount")
                      high (true? (aget row "change_high_impact"))
                      quant? (or (some? days) (and (some? cost) (not= "" cost)))]
                  (r/as-element
                   (if (or quant? high)
                     (into [antd/space {:wrap true}]
                           (cond-> []
                             (some? days) (conj [antd/tag {:color "blue"} (str "工期 +" days " 天")])
                             (and (some? cost) (not= "" cost)) (conj [antd/tag {:color "blue"} (str "成本 +" cost)])
                             high (conj [antd/tag {:color "red"} "高影响"])))
                     [:span {:style {:color "#98a2b3"}} "未量化"]))))}
     (w/state-column)]
    #(review-actions context "changes" %)]])


(defn- gate-actions
  "先逐项验证证据,再提交独立Gate决策."
  [{:keys [base model options editable? approve? open!]} gate]
  (let [path (str base "/gates/" (:id gate)) current (:currentUserId options)]
    [antd/space
     (when (and editable? (contains? #{"draft" "ready" "rejected"} (:status gate)))
       [:<>
        [w/edit-button "填写检查" #(open! (forms/gate-check-dialog base model gate))]
        [w/edit-button "提交评审" #(open! {:title "提交Gate评审" :path (str path "/submit") :fields []})]
        [w/edit-button "申请豁免" #(open! {:title "申请Gate豁免" :path (str path "/submit")
                                       :fields [{:key :waiver_reason :label "豁免理由" :type :textarea :required? true}]})]])
     (when (and approve? (= "in_review" (:status gate)) (= current (:reviewer_id gate)) (not= current (:submitted_by gate)))
       [:<>
        [w/edit-button "通过" #(open! (forms/decision-dialog (str path "/decision") "approved" "批准Gate"))]
        [w/edit-button "驳回" #(open! (forms/decision-dialog (str path "/decision") "rejected" "驳回Gate"))]
        [w/edit-button "豁免" #(open! (forms/decision-dialog (str path "/decision") "waived" "豁免Gate"))]])]))


(def gate-stage-labels
  {"execution" "执行准入" "closure" "结项准出" "design" "设计阶段" "manufacturing" "制造阶段" "delivery" "交付阶段" "site" "现场阶段"})

(def checkpoint-labels
  {"assembly.start" "装配开工" "test.SIT" "SIT试验" "test.FAT" "FAT试验" "test.SAT" "SAT试验" "shipment.dispatch" "发运"})

(def gate-status-labels
  {"not_started" "未发起" "draft" "草稿" "ready" "待提交" "in_review" "评审中" "approved" "已通过" "rejected" "已驳回" "waived" "已豁免"})

(defn- gate-progress-section
  "只读汇总各关口模板的实例进展与检查项通过情况, 供主机/附件汇总等 Gate 下钻 (B09/B10)."
  [{:keys [model]}]
  (let [rows (:gate_progress model)]
    [shared/panel "Gate进展汇总" "按模板汇总实例状态与检查项通过数; 阻断型关口未通过时对应交付命令被 409 拒绝" nil
     (if (empty? rows)
       [:span {:style {:color "#8793a3"}} "尚无Gate模板, 可从关口目录建立或应用项目模板."]
       [antd/space {:wrap true}
        (for [row rows] ^{:key (:template_id row)}
          [:div {:style {:border "1px solid #e4e8ee" :borderRadius 8 :padding "10px 14px" :minWidth 220}}
           [:div {:style {:fontWeight 600}} (:title row)]
           [:div {:style {:fontSize 12 :color "#718096" :margin "4px 0"}} (str (get gate-stage-labels (:stage row) (:stage row)) " · " (:gate_type row))]
           [antd/space {:wrap true}
            [antd/tag {:color (cond (:passed row) "green" (= "not_started" (:status row)) "default" (= "rejected" (:status row)) "red" :else "blue")}
             (get gate-status-labels (:status row) (:status row))]
            [antd/tag (str "检查 " (:passed_checks row) "/" (:total_checks row))]
            (when (pos? (or (:waived_checks row) 0)) [antd/tag {:color "gold"} (str "例外 " (:waived_checks row))])
            (for [b (:blocks row)] ^{:key b} [antd/tag {:color "orange"} (str "阻断 " (get checkpoint-labels b b))])]])])]))

(defn- gate-section
  "Gate模板和逐项证据检查控制阶段准入."
  [{:keys [base model options editable? open!] :as context}]
  [:div {:style {:display "grid" :gap 20}}
   [gate-progress-section context]
   [shared/panel "Gate模板" "每个控制点声明类型, 适用阶段, 阻断检查点与必需检查项 (含须已发布证据的检查)"
    (when editable?
      [antd/space
       [antd/button {:on-click (fn []
                                 (open! {:title "从关口目录建立" :path (str base "/gate-templates/from-catalog")
                                         :description "原蓝图关口目录 (需求确认/主机汇总/附件汇总/齐套G4/装配测试交接G5/FAT确认G6/交底G7/SAT确认G8), 适用范围待业务签收."
                                         :fields [{:key :gate_type :label "关口类型" :type :select :required? true
                                                   :options (mapv (fn [g] {:value (:gate_type g) :label (str (:title g) " · " (get gate-stage-labels (:stage g) (:stage g)) " · 第" (:page g) "页")}) (:gate_catalog model))}]}))}
        "从关口目录建立"]
       [antd/button {:on-click #(open! (forms/template-dialog base))} "建立Gate模板"]])
    [w/record-table (:gate_templates model)
     [(w/text-column :code "编号") (w/text-column :title "模板")
      {:title "类型" :dataIndex "gate_type" :width 170 :render #(shared/display-value %)}
      {:title "控制阶段" :dataIndex "stage" :width 100 :render #(get gate-stage-labels % %)}
      {:title "阻断检查点" :dataIndex "blocks" :width 160
       :render (fn [v] (let [items (array-seq (or v #js []))]
                         (r/as-element (if (seq items) (into [antd/space {:wrap true}] (map (fn [b] [antd/tag {:color "orange"} (get checkpoint-labels b b)]) items))
                                           [:span {:style {:color "#98a2b3"}} "无"]))))}
      {:title "检查项" :dataIndex "checks" :width 90 :render (fn [v] (count (array-seq (or v #js []))))}] nil]]
   [shared/panel "Gate检查与评审" "审批绑定具体检查结果及证据版本"
    (when editable? [antd/button {:type "primary" :on-click #(open! (forms/gate-dialog base model options))} "发起Gate检查"])
    [w/record-table (:gates model) [(w/text-column :title "检查")
                                    {:title "类型" :dataIndex "gate_type" :width 160 :render #(shared/display-value %)}
                                    {:title "阶段" :dataIndex "stage" :width 100 :render #(get gate-stage-labels % %)}
                                    (w/state-column)
                                    (w/text-column :reviewer_id "审批人") (w/text-column :decision_reason "评审意见")]
     #(gate-actions context %)]]])


(defn- dq-actions
  [{:keys [base options editable? approve? open!]} dq]
  (let [current (:currentUserId options)]
    [antd/space {:wrap true}
     (when (and editable? (contains? #{"draft" "ready" "rejected"} (:status dq)))
       [w/edit-button "填写检查" #(open! (forms/dq-check-dialog base dq))])
     (when (and editable? (contains? #{"ready" "rejected"} (:status dq)))
       [w/edit-button "提交签认" #(open! {:title "提交DQ签认" :path (str base "/dqs/" (:id dq) "/submit") :fields [(forms/reviewer-field options)]})])
     (when (and approve? (= "in_review" (:status dq)) (= current (:reviewer_id dq)) (not= current (:submitted_by dq)))
       [:<>
        [w/edit-button "签认" #(open! (forms/decision-dialog (str base "/dqs/" (:id dq) "/decision") "approved" "签认DQ"))]
        [w/edit-button "退回" #(open! (forms/decision-dialog (str base "/dqs/" (:id dq) "/decision") "rejected" "退回DQ"))]])]))

(defn- dq-section
  "B08 DQ 编制与确认关键任务: 检查清单 + 确定版本交付件 + 独立签认, 交付件更新即标注失效."
  [{:keys [base model options planning editable? open!] :as context}]
  [shared/panel "DQ 编制与确认" "管理检查清单与确定版本交付件, 满足条件并签认才完成; 交付件出现新版本时签认依据标注失效"
   (when editable? [antd/button {:type "primary" :on-click #(open! (forms/dq-dialog base options (:documents model) planning))} "建立DQ关键任务"])
   [w/record-table (:dqs model)
    [(w/text-column :code "编号") (w/text-column :title "DQ任务")
     {:title "检查通过" :key "checks" :width 100 :render (fn [_ row] (str (aget row "dq_passed") "/" (aget row "dq_total")))}
     {:title "交付件" :dataIndex "deliverable_ids" :width 220
      :render (fn [v] (str/join ", " (map #(w/related-label (:documents model) :id :code %) (js->clj v))))}
     {:title "版本失效" :dataIndex "dq_stale" :width 110
      :render (fn [v] (r/as-element (if (true? v) [antd/tag {:color "red"} "交付件已更新"] [antd/tag {:color "green"} "版本有效"])))}
     (w/state-column) (w/text-column :decision_reason "签认意见")]
    #(dq-actions context %)]])

(defn- pause-section
  "B16 项目/单机局部暂停与恢复."
  [{:keys [base model planning editable? open!]}]
  [shared/panel "单机/子项目局部暂停" "记录范围和原状态, 暂停期间冻结该节点任务的进度反馈, 恢复时校验并记录重排影响; 不误影响无关单机"
   (when editable? [antd/button {:danger true :on-click #(open! (forms/node-pause-dialog base (:nodes planning)))} "局部暂停"])
   [w/record-table (:node_pauses model)
    [(w/text-column :node_code "节点") (w/text-column :node_name "名称") (w/text-column :reason "暂停原因")
     (w/text-column :project_status_at_pause "暂停时项目状态") (w/text-column :paused_at "暂停时间")
     (w/text-column :impact_note "恢复影响说明") (w/text-column :resumed_at "恢复时间")
     {:title "状态" :dataIndex "status" :width 100 :render #(r/as-element [antd/tag {:color (if (= % "active") "red" "green")} (if (= % "active") "暂停中" "已恢复")])}]
    (fn [row] (when (and editable? (= "active" (:status row)))
                [w/edit-button "恢复" #(open! (forms/node-resume-dialog base row))]))]])

(defn- governance-content
  "按工程协作主题组织治理页面."
  [context]
  [antd/tabs {:items
              (mapv (fn [[key label components]]
                      {:key key :label label :children (r/as-element
                                                         (into [:div {:style {:display "grid" :gap 20}}] (map #(vector % context) components)))})
                    [["charter" "章程" [charter-section]]
                     ["requirements" "URS与追踪" [requirement-section coverage-section traceability-section trace-section]]
                     ["evidence" "证据版本" [document-section collection-section tree-section release-coverage-section]]
                     ["appointments" "成员任命" [appointment-section]]
                     ["stakeholders" "干系人与沟通" [stakeholder-section raci-section comm-plan-section]]
                     ["gates" "Gate评审" [gate-section]]
                     ["risks" "风险与问题" [risk-section issue-section]]
                     ["meetings" "会议行动" [meeting-section action-section]]
                     ["changes" "变更控制" [change-section]]
                     ["quality" "DQ与局部暂停" [dq-section pause-section]]])}])


(defn- import-dialog
  "先预检标准CSV再原子导入需求."
  [base project on-close on-saved]
  (let [[csv set-csv!] (hooks/use-state "code,text,category,priority,owner_id\n")
        [preview set-preview!] (hooks/use-state nil)
        [error set-error!] (hooks/use-state nil)
        {busy? :busy? mutation-error :error run! :run!} (shared/use-action on-saved)]
    [antd/modal {:title "批量导入URS" :open true :onCancel on-close :footer nil :width 760}
     [:p "CSV列: code,text,category,priority,owner_id. 正式导入前须通过整批预检."]
     (when (or error mutation-error) [shared/error-panel (or error mutation-error) nil])
     [antd/text-area {:rows 8 :value csv :aria-label "URS CSV内容"
                      :onChange #(do (set-csv! (.. % -target -value)) (set-preview! nil))}]
     [:div {:style {:marginTop 16}}
      [antd/space
       [antd/button {:on-click #(shared/request! :post (str base "/requirements/preview") {:csv csv} set-preview! set-error!)} "预检CSV"]
       [antd/button {:type "primary" :disabled (not (:valid? preview)) :loading busy?
                     :on-click #(run! :post (str base "/requirements/import") {:csv csv :version (:version project)} "URS导入成功")} "确认导入"]]]
     (when preview [:div {:style {:marginTop 16}}
                    [:p (str "预检记录 " (:count preview) " 条")]
                    (for [item (:errors preview)] ^{:key (:line item)} [:p {:role "alert"} (str "第 " (:line item) " 行: " (:error item))])])]))


(defn- download-text!
  "把已授权读取的文本按给定文件名下载到本地,保留原始内容."
  [text filename]
  (let [url (.createObjectURL js/URL (js/Blob. #js [text] #js {:type "text/plain;charset=utf-8"}))
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.click link)
    (.revokeObjectURL js/URL url)))


(defn- download-document!
  "使用已授权读取的真实正文生成本地下载,保留原始内容."
  [evidence]
  (download-text! (:content evidence) (:filename evidence)))


(defn- appointment-preview
  "读取受控任命书正文, 展示团队快照摘要并提供本地下载."
  [base appointment on-close]
  (let [resource (shared/use-resource (str base "/appointments/" (:id appointment) "/content") {} [])]
    [antd/modal {:title (str "项目成员任命书 V" (:revision appointment)) :open true :onCancel on-close :footer nil
                 :style {:maxWidth "calc(100vw - 32px)"} :width 760}
     [w/resource-view resource
      (fn [data]
        [:div
         [antd/button {:on-click #(download-text! (:content data) (str "appointment-v" (:revision data) ".txt"))} "下载此版本"]
         [:p {:style {:fontSize 12 :color "#718096" :overflowWrap "anywhere"}}
          (str "团队人数: " (:headcount data) "  |  团队快照SHA256: " (:snapshot_sha256 data))]
         [:pre {:style {:whiteSpace "pre-wrap" :maxHeight "60vh" :overflow "auto" :background "#f7f8fa" :padding 16 :borderRadius 6}}
          (:content data)]])]]))


(defn upload-dialog
  "以真实文件 (multipart) 登记或修订证据文档: 文件按内容寻址落盘, 服务端计算 SHA256 并形成不可变版本."
  [{:keys [base document categories project on-close on-saved]}]
  (let [[form] (antd/form-use-form)
        [file set-file!] (hooks/use-state nil)
        [busy? set-busy!] (hooks/use-state false)
        [error set-error!] (hooks/use-state nil)
        revision? (some? document)
        path (str base "/documents" (if revision? (str "/" (:id document) "/upload-revision") "/upload"))]
    [antd/modal {:title (if revision? "新增证据文档版本 (上传文件)" "上传证据文件") :open true :onCancel on-close :onOk #(.submit form)
                 :okText "上传" :cancelText "返回" :confirmLoading busy? :destroyOnHidden true
                 :style {:maxWidth "calc(100vw - 32px)"} :width 640}
     [:p {:style {:color "#718096" :lineHeight 1.8}}
      "支持 PDF / 图片 / Office / ZIP / DWG / STEP 等工程证据 (可执行文件不允许), 单文件上限由服务端配置; 文件按内容寻址存储, 服务端计算 SHA256, 下载时复核摘要, 版本不可变."]
     (when error [shared/error-panel error nil])
     [antd/form {:form form :layout "vertical" :disabled busy?
                 :initialValues (when document (select-keys document [:code :title :classification :stage :structure_node :category]))
                 :onFinish (fn [values]
                             (if-not file
                               (set-error! "请选择要上传的文件")
                               (let [data (js->clj values :keywordize-keys true)
                                     fd (js/FormData.)]
                                 (doseq [[k v] (select-keys data [:code :title :classification :stage :structure_node :category])
                                         :when (and (some? v) (not= "" v))]
                                   (.append fd (name k) v))
                                 (.append fd "version" (str (:version project)))
                                 (.append fd "file" file (.-name file))
                                 (set-busy! true) (set-error! nil)
                                 (api/pms-upload path fd
                                                 (fn [body] (set-busy! false) (antd/success! "上传成功") (on-saved (:data body)))
                                                 (fn [body] (set-busy! false) (set-error! (or (:msg body) "上传失败")))))))}
      (for [field (forms/document-meta-fields categories)] ^{:key (:key field)} [w/form-field field])
      [antd/form-item {:label "证据文件" :required true
                       :extra (if file (str (.-name file) " · " (file-size (.-size file))) "选择一个文件; 修订时可上传同编号的新文件版本")}
       [:input {:id "document_file" :type "file" :aria-label "证据文件"
                :on-change (fn [e] (let [f (aget (.. e -target -files) 0)] (set-file! f)))}]]]]))


(defn- file-preview
  "预览/下载二进制证据: PDF 用内嵌框, 图片直接显示, 文本取正文; 其它类型只提供下载; 摘要来自服务端响应头."
  [base document on-close]
  (let [[state set-state!] (hooks/use-state {:loading? (boolean (:preview document))})
        path (str base "/documents/" (:id document))]
    (hooks/use-effect
      (fn []
        (when (:preview document)
          (api/pms-fetch-blob (str path "/preview")
                              (fn [blob meta]
                                (let [ct (or (:content-type meta) "")]
                                  (if (.startsWith ct "text/")
                                    (.then (.text blob) (fn [text] (set-state! {:text text :meta meta})))
                                    (set-state! {:url (js/URL.createObjectURL blob) :meta meta}))))
                              (fn [body] (set-state! {:error (:msg body)}))))
        (fn [] (when-let [u (:url state)] (js/URL.revokeObjectURL u))))
      [(:id document)])
    [antd/modal {:title (str (:title document) " / V" (:revision document) " · " (:filename document)) :open true :onCancel on-close :footer nil
                 :style {:maxWidth "calc(100vw - 32px)"} :width 960}
     [:div {:style {:display "grid" :gap 10}}
      [antd/space {:wrap true}
       [antd/button {:on-click (fn [] (api/pms-fetch-blob (str path "/download")
                                                          (fn [blob _] (api/save-blob! blob (:filename document)))
                                                          (fn [body] (antd/error! (or (:msg body) "下载失败")))))} "下载此版本"]
       [antd/tag (str (:content_type document))] [antd/tag (file-size (:byte_size document))]
       (when (:category document) [antd/tag {:color "blue"} (:category document)])]
      [:p {:style {:fontSize 12 :color "#718096" :overflowWrap "anywhere" :margin 0}} (str "SHA256: " (:sha256 document)
                                                                                         (when-let [m (:meta state)] (str " · 服务端复核: " (if (= (:sha256 m) (:sha256 document)) "一致" "不一致"))))]
      (cond
        (:error state) [shared/error-panel (:error state) nil]
        (not (:preview document)) [:div {:style {:color "#98a2b3"}} "该文件类型不支持在线预览, 请下载后查看."]
        (:loading? state) [:div {:style {:padding 32 :textAlign "center"}} [antd/spin]]
        (:text state) [:pre {:style {:whiteSpace "pre-wrap" :maxHeight "60vh" :overflow "auto" :background "#f7f8fa" :padding 16 :borderRadius 6}} (:text state)]
        (.startsWith (or (:content_type document) "") "image/") [:img {:src (:url state) :alt (:filename document) :style {:maxWidth "100%" :maxHeight "70vh" :objectFit "contain"}}]
        :else [:iframe {:src (:url state) :title (:filename document) :style {:width "100%" :height "70vh" :border "1px solid #e4e8ee" :borderRadius 6}}])]]))


(defn- document-preview
  "通过授权请求读取保存的证据正文 (文本证据); 二进制证据走 file-preview."
  [base document on-close]
  (if (= "file" (:content_kind document))
    [file-preview base document on-close]
    (let [resource (shared/use-resource (str base "/documents/" (:id document) "/content") {} [])]
      [antd/modal {:title (str (:title document) " / V" (:revision document)) :open true :onCancel on-close :footer nil :width 800}
       [w/resource-view resource
        (fn [data]
          [:div
           [antd/button {:on-click #(download-document! data)} "下载此版本"]
           [:p {:style {:fontSize 12 :color "#718096" :overflowWrap "anywhere"}} (str "SHA256: " (:sha256 data))]
           [:pre {:style {:whiteSpace "pre-wrap" :maxHeight "60vh" :overflow "auto"}} (:content data)]])]])))


(defn- discard-preview-modal
  "只读展示对某记录发起受控作废将命中的状态门控与级联引用清单, 不改变任何状态."
  [base collection target on-close]
  (let [resource (shared/use-resource (str base "/" collection "/" (:id target) "/discard-preview") {} [])]
    [antd/modal {:title (str "级联影响预览 · " (:label target)) :open true :onCancel on-close :footer nil
                 :style {:maxWidth "calc(100vw - 32px)"} :width 640}
     [w/resource-view resource
      (fn [data]
        (let [status-text (get {"registered" "已登记" "rejected" "已退回" "in_review" "待审批"
                                "active" "有效" "discarded" "已作废"} (:status data) (:status data))]
          [:div {:style {:display "grid" :gap 12}}
           [antd/space {:wrap true}
            [antd/tag (str "当前状态: " status-text " (v" (:revision data) ")")]
            [antd/tag {:color (if (:latest? data) "green" "red")} (if (:latest? data) "最新版本" "非最新版本")]
            [antd/tag {:color (if (:status_discardable? data) "green" "orange")}
             (if (:status_discardable? data) "状态可作废" "状态不可作废")]
            [antd/tag {:color (if (:discardable? data) "green" "red")}
             (if (:discardable? data) "可安全作废" "不可作废")]]
           (if (seq (:references data))
             [:div
              [:div {:style {:fontWeight 500}} (str "仍被以下 " (count (:references data)) " 个对象引用, 需先解除引用才能作废:")]
              [:ul {:style {:margin "6px 0" :paddingLeft 22}}
               (for [ref (:references data)] ^{:key ref} [:li ref])]]
             [:div {:style {:color "#52708a"}} "未发现引用该记录的其它对象."])]))]]))


(defn governance-workspace
  "集中加载治理读模型并协调独立审批与版本写入."
  [project revision options changed!]
  (let [root (str "/projects/" (:project_id project)) base (str root "/governance")
        resource (shared/use-resource base {} [revision])
        planning (shared/use-resource (str root "/planning") {} [revision])
        [dialog set-dialog!] (hooks/use-state nil) [importing? set-importing!] (hooks/use-state false)
        [document set-document!] (hooks/use-state nil)
        [upload set-upload!] (hooks/use-state nil)
        [appointment set-appointment!] (hooks/use-state nil)
        [preview set-preview!] (hooks/use-state nil)
        editable? (and (shared/use-permission "pms:project:edit") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
        context {:base base :model (:data resource) :planning (:data planning) :options options
                 :editable? editable? :approve? (and (shared/use-permission "pms:quality:approve") (not (contains? #{"closed" "cancelled" "paused"} (:status project))))
                 :open! set-dialog! :import! #(set-importing! true) :document! set-document! :upload! set-upload!
                 :appointment! set-appointment! :preview! set-preview!}]
    ;; 弹窗在点击时固化计划读模型里的任务/节点选项; 首次加载时两类资源都返回后才渲染可操作内容 (与工程交付页一致).
    [:div
     [w/resource-view (shared/first-load resource planning) (fn [_] [governance-content context])]
     (when dialog [w/mutation-dialog (merge dialog {:project project :on-close #(set-dialog! nil)
                                                    :on-saved (fn [_] (set-dialog! nil) (changed!))})])
     (when importing? [import-dialog base project #(set-importing! false) (fn [_] (set-importing! false) (changed!))])
     (when document [document-preview base document #(set-document! nil)])
     (when upload [upload-dialog {:base base :document (:document upload) :categories (:categories upload) :project project
                                  :on-close #(set-upload! nil) :on-saved (fn [_] (set-upload! nil) (changed!))}])
     (when appointment [appointment-preview base appointment #(set-appointment! nil)])
     (when preview [discard-preview-modal base (:collection preview) preview #(set-preview! nil)])]))
