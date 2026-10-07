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
                                           [:span {:style {:color "#98a2b3"}} "未设定"]))))}
     {:title "追踪状态" :dataIndex "trace_state" :width 130
      :render (fn [v row]
                (let [color ({"complete" "green" "missing-design" "gold" "missing-verification" "volcano" "untracked" "default"} v)
                      label ({"complete" "追踪完整" "missing-design" "缺设计关联" "missing-verification" "缺验证关联" "untracked" "未追踪"} v)
                      des (aget row "trace_design_links")
                      ver (aget row "trace_verification_links")]
                  (r/as-element (if (nil? label) [:span {:style {:color "#98a2b3"}} "—"]
                                    [antd/tag {:color color} (str label " " des "/" ver)]))))}
     {:title "验证对齐" :dataIndex "verification_alignment" :width 150
      :render (fn [v]
                (let [[text color] (get {"aligned" ["已配验证关联" "green"]
                                         "declared-unverified" ["声明方式·缺验证关联" "volcano"]}
                                        v ["未声明方式" "default"])]
                  (r/as-element [antd/tag {:color color} text])))}
     {:title "验证证据" :dataIndex "verification_evidence_state" :width 170
      :render (fn [v row]
                (let [[text color] (get {"released" ["证据已发布" "green"]
                                         "pending" ["证据待发布" "gold"]
                                         "no-verification" ["缺验证关联" "default"]}
                                        v ["未声明方式" "default"])
                      voided (true? (aget row "verification_evidence_voided"))]
                  (r/as-element
                    [:div {:style {:display "flex" :flex-wrap "wrap" :gap 4}}
                     [antd/tag {:color color} text]
                     (when voided [antd/tag {:color "red"} "证据已作废"])])))}]
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


(defn- alignment-section
  "按每个需求编号的最新有效版本只读交叉核对已声明验证方式与是否已配验证(verifies)证据关联: 声明数, 其中已对齐数, 尚缺验证关联数与对齐率; 进一步区分已配关联者其证据文档是否已发布(approved)记已发布证据, 有验证关联但证据未发布记待发布证据; 未声明方式不进入分母, 修订不重复计数, 已作废不计入."
  [{:keys [model]}]
  (let [al (:verification_evidence_alignment model)
        declared (:declared al 0)
        aligned (:aligned al 0)
        gap (:gap al 0)
        pct (:alignment-pct al 0)
        released (:evidence-released al 0)
        pending (:evidence-pending al 0)
        released-pct (:evidence-released-pct al 0)]
    [shared/panel "验证方式与验证关联对齐" "按每个需求编号的最新有效版本交叉核对: 声明了验证方式的需求里有多少已真正挂上验证(verifies)证据关联, 并进一步区分该关联所指向的证据文档是否已发布; 未声明方式不进分母, 修订不重复计数, 已作废不计入"
     (if (zero? declared)
       [:span {:style {:color "#8793a3"}} "暂无声明验证方式的URS需求, 登记并声明验证方式后可在此查看对齐情况."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "已声明验证方式 " declared)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已配验证关联 " pct "%")]
         [antd/tag {:color "green"} (str "对齐 " aligned)]
         (when (pos? gap)
           [antd/tag {:color "volcano"} (str "缺验证关联 " gap)])]
        [antd/space {:wrap true}
         [antd/tag {:color (cond (= released-pct 100) "green" (zero? released-pct) "default" :else "cyan")}
          (str "已发布证据 " released-pct "%")]
         [antd/tag {:color "geekblue"} (str "证据已发布 " released)]
         (when (pos? pending)
           [antd/tag {:color "gold"} (str "证据待发布 " pending)])]])]))


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


(defn- engagement-cell
  "渲染干系人参与态度标签 (PMBOK 投入度评估), 未设定时显示灰字."
  [row]
  (let [e (aget row "engagement")
        label (get {"unaware" "未知晓" "resistant" "抵制" "neutral" "中立"
                    "supportive" "支持" "leading" "主导"} e)
        color (get {"unaware" "default" "resistant" "red" "neutral" "blue"
                    "supportive" "green" "leading" "gold"} e "default")]
    (r/as-element
      (if (nil? e)
        [:span {:style {:color "#98a2b3"}} "未设定"]
        [antd/tag {:color color} label]))))


(defn- desired-engagement-cell
  "渲染干系人期望参与态度标签 (PMBOK 投入度评估目标), 未设定时显示灰字."
  [row]
  (let [e (aget row "desired_engagement")
        label (get {"unaware" "未知晓" "resistant" "抵制" "neutral" "中立"
                    "supportive" "支持" "leading" "主导"} e)
        color (get {"unaware" "default" "resistant" "orange" "neutral" "blue"
                    "supportive" "cyan" "leading" "gold"} e "default")]
    (r/as-element
      (if (nil? e)
        [:span {:style {:color "#98a2b3"}} "未设定"]
        [antd/tag {:color color} label]))))


(defn- engagement-gap-cell
  "渲染当前与期望参与态度之间的投入度差距标签 (只读派生): 达标/需提升/需降低/未标注."
  [row]
  (let [state (aget row "stakeholder_engagement_state")
        gap (aget row "stakeholder_engagement_gap")
        text (case state
               "on" "达标"
               "up" (str "需提升 +" gap " 档")
               "down" (str "需降低 " (js/Math.abs gap) " 档")
               "未标注")]
    (r/as-element
      [antd/tag {:color (get {"on" "green" "up" "volcano" "down" "blue"} state "default")} text])))


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
     {:title "参与态度" :dataIndex "engagement" :width 110 :render (fn [_ row] (engagement-cell row))}
     {:title "期望态度" :dataIndex "desired_engagement" :width 110 :render (fn [_ row] (desired-engagement-cell row))}
     {:title "投入差距" :dataIndex "stakeholder_engagement_state" :width 140 :render (fn [_ row] (engagement-gap-cell row))}
     {:title "管理策略" :dataIndex "stakeholder_quadrant" :width 200 :render (fn [_ row] (quadrant-cell row))}
     (w/state-column)]
    (when editable? (fn [row] [antd/space {:wrap true}
                         [w/edit-button "新修订" #(open! (forms/stakeholder-dialog base options row))]
                         [w/edit-button "级联影响" #(preview! {:collection "stakeholders" :id (:id row) :label (str "干系人 " (:code row))})]
                         (when (= "active" (:status row))
                           [w/edit-button "作废" #(open! (forms/discard-dialog (str base "/stakeholders/" (:id row) "/discard") "干系人"))])
                         (when (= "discarded" (:status row))
                           [w/edit-button "恢复" #(open! (forms/restore-dialog (str base "/stakeholders/" (:id row) "/restore") "干系人"))])]))]])


(defn- engagement-coverage-section
  "按每个干系人最新有效版本只读聚合参与态度(PMBOK投入度)声明覆盖度: 五类态度各自计数与覆盖率; 只读派生, 不改变干系人状态."
  [{:keys [model]}]
  (let [cov (:stakeholder_engagement_coverage model)
        total (:total cov 0)
        undeclared (:undeclared cov 0)
        pct (:coverage-pct cov 0)
        engagement-label {"unaware" "未知晓" "resistant" "抵制" "neutral" "中立"
                          "supportive" "支持" "leading" "主导"}
        engagement-color {"unaware" "default" "resistant" "red" "neutral" "blue"
                          "supportive" "green" "leading" "gold"}]
    [shared/panel "参与态度覆盖度" "按每个干系人的最新有效版本统计 PMBOK 投入度评估五类参与态度声明情况; 只读派生, 不改变干系人状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目干系人, 登记后可在此查看参与态度覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "干系人总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已声明参与态度 " pct "%")]
         (when (pos? undeclared)
           [antd/tag {:color "orange"} (str "未设定 " undeclared)])]
        [:div
         [:span {:style {:fontWeight 500}} "按参与态度: "]
         [antd/space {:wrap true}
          (for [{:keys [engagement count]} (:by-engagement cov)]
            ^{:key engagement} [antd/tag {:color (if (pos? count) (get engagement-color engagement "geekblue") "default")}
                              (str (get engagement-label engagement engagement) " · " count)])]]])]))


(defn- engagement-matrix-section
  "按每个干系人最新有效版本只读比对当前与期望参与态度 (PMBOK 投入度评估矩阵): 达标/需提升/需降低/未标注统计与优先级清单; 只读派生, 不改变干系人状态."
  [{:keys [model]}]
  (let [mx (:stakeholder_engagement_matrix model)
        total (:total mx 0)
        marked (:marked mx 0)
        pct (:on-target-pct mx 0)
        label {"unaware" "未知晓" "resistant" "抵制" "neutral" "中立"
               "supportive" "支持" "leading" "主导"}
        enum-col (fn [k title]
                   {:title title :dataIndex (name k) :width 90
                    :render (fn [_ row] (r/as-element [antd/tag (get label (aget row (name k)) "-")]))})
        gap-col {:title "差距" :dataIndex "gap" :width 90
                 :render (fn [_ row]
                           (let [g (aget row "gap")]
                             (r/as-element [:span (if (nil? g) "-" (str (when (pos? g) "+") g))])))}]
    [shared/panel "参与态度评估矩阵" "按每个干系人的最新有效版本比对当前与期望参与态度 (PMBOK 投入度评估), 给出达标/需提升/需降低/未标注统计与优先级清单; 只读派生, 不改变干系人状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目干系人, 登记并设定当前与期望参与态度后可在此查看投入度评估矩阵."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "干系人总数 " total)]
         [antd/tag {:color "geekblue"} (str "已标注 " marked)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")} (str "达标率 " pct "%")]
         [antd/tag {:color (if (pos? (:need-up mx 0)) "volcano" "default")} (str "需提升 " (:need-up mx 0) " (合计 " (:up-steps mx 0) " 档)")]
         [antd/tag {:color (if (pos? (:need-down mx 0)) "blue" "default")} (str "需降低 " (:need-down mx 0))]
         [antd/tag {:color (if (pos? (:unmarked mx 0)) "orange" "default")} (str "未标注 " (:unmarked mx 0))]]
        [:div
         [:div {:style {:fontWeight 500 :marginBottom 4}} "需提升优先级"]
         [w/record-table (:need-up-stakeholders mx [])
          [(w/text-column :code "编号") (w/text-column :name "名称")
           (enum-col :current "当前") (enum-col :desired "期望") gap-col] nil]]
        (when (seq (:need-down-stakeholders mx))
          [:div
           [:div {:style {:fontWeight 500 :marginBottom 4}} "需降低"]
           [w/record-table (:need-down-stakeholders mx)
            [(w/text-column :code "编号") (w/text-column :name "名称")
             (enum-col :current "当前") (enum-col :desired "期望") gap-col] nil]])])]))


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
     {:title "最近沟通方式" :dataIndex "last_communication_channel" :width 130
      :render (fn [_ row]
                (let [c (aget row "last_communication_channel")
                      label (cond (= c "meeting") "会议" (= c "email") "邮件" (= c "dashboard") "看板"
                                  (= c "report") "报告" (= c "review") "评审" :else nil)]
                  (r/as-element (if label [antd/tag {:color "blue"} label]
                                    [:span {:style {:color "#98a2b3"}} "未记录"]))))}
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


(defn- comm-cadence-section
  "按每个沟通计划最新有效版本只读聚合下次沟通到期节奏: 已逾期/临期/未来到期三分, 并回显五种沟通频率的计划条数分布; 复用逐条台账沟通到期口径, 只读派生, 不改变计划状态."
  [{:keys [model]}]
  (let [cad (:comm_cadence_summary model)
        total (:total cad 0)
        overdue (:overdue cad 0)
        due-soon (:due-soon cad 0)
        upcoming (:upcoming cad 0)
        by-freq (:by-frequency cad [])]
    [shared/panel "沟通节奏到期汇总" "按每个沟通计划最新有效版本只读聚合下次沟通到期节奏(已逾期/临期/未来到期), 并按五种沟通频率回显计划条数分布; 与逐条台账沟通到期同源, 只读派生, 不改变计划状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无沟通计划, 登记后可在此查看节奏到期汇总."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "沟通计划总数 " total)]
         (when (pos? overdue)
           [antd/tag {:color "red"} (str "已逾期 " overdue)])
         (when (pos? due-soon)
           [antd/tag {:color "gold"} (str "临期 " due-soon)])
         (when (pos? upcoming)
           [antd/tag {:color "green"} (str "未来到期 " upcoming)])]
        [antd/space {:wrap true}
         (for [f by-freq :when (pos? (:count f 0))]
           ^{:key (:frequency f)} [antd/tag {:color "default"} (str (:label f) " · " (:count f))])]])]))


(defn- comm-audience-section
  "按每个干系人与沟通计划最新有效版本只读聚合有多少有效干系人被至少一条活动沟通计划受众覆盖: 已覆盖/未覆盖与覆盖率并列出未覆盖干系人; 复用沟通计划受众口径, 只读派生, 不改变任何记录."
  [{:keys [model]}]
  (let [cov (:comm_audience_coverage model)
        total (:total cov 0)
        plans (:plans cov 0)
        covered (:covered cov 0)
        uncovered (:uncovered cov 0)
        pct (:coverage-pct cov 0)
        missing (:uncovered-stakeholders cov [])]
    [shared/panel "沟通受众覆盖度" "按每个干系人与沟通计划最新有效版本只读聚合有多少有效干系人被至少一条活动沟通计划的受众覆盖, 并列出尚未被任何沟通计划覆盖的干系人; 只读派生, 不改变任何记录"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无干系人, 登记后可在此查看沟通受众覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "干系人总数 " total)]
         [antd/tag {:color "default"} (str "沟通计划 " plans)]
         [antd/tag {:color "green"} (str "已覆盖 " covered)]
         (when (pos? uncovered)
           [antd/tag {:color "red"} (str "未覆盖 " uncovered)])
         [antd/tag {:color (if (zero? pct) "red" "cyan")} (str "覆盖率 " pct "%")]]
        (when (pos? uncovered)
          [antd/space {:wrap true}
           (for [st missing]
             ^{:key (:code st)} [antd/tag {:color "default"} (str (:code st) " · " (:name st))])])])]))


(defn- comm-execution-section
  "按每个沟通计划最新有效版本只读聚合有多少活动沟通计划已实际执行落地(标记过至少一次沟通或生成过至少一次会议): 已执行/尚未执行与执行率并列出尚未执行计划; 与沟通节奏到期面板互补(到期看时间分布而本项看是否已执行到位), 只读派生, 不改变任何记录."
  [{:keys [model]}]
  (let [cov (:comm_execution_coverage model)
        total (:total cov 0)
        executed (:executed cov 0)
        not-executed (:not-executed cov 0)
        logged (:logged cov 0)
        met (:met cov 0)
        pct (:execution-pct cov 0)
        pending (:not-executed-plans cov [])]
    [shared/panel "沟通计划执行落地覆盖度" "按每个沟通计划最新有效版本只读聚合有多少活动沟通计划已实际执行落地(标记过至少一次沟通或生成过至少一次会议), 并列出尚未执行的计划; 与沟通节奏到期面板互补, 只读派生, 不改变任何记录"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无沟通计划, 登记后可在此查看执行落地覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "沟通计划总数 " total)]
         [antd/tag {:color "green"} (str "已执行落地 " executed)]
         (when (pos? not-executed)
           [antd/tag {:color "red"} (str "尚未执行 " not-executed)])
         [antd/tag {:color (if (zero? pct) "red" "cyan")} (str "执行率 " pct "%")]]
        [antd/space {:wrap true}
         [antd/tag {:color "default"} (str "已标记沟通 " logged)]
         [antd/tag {:color "default"} (str "已生成会议 " met)]]
        (when (pos? not-executed)
          [antd/space {:wrap true}
           (for [pl pending]
             ^{:key (:code pl)} [antd/tag {:color "default"} (str (:code pl) " · " (:objective pl))])])])]))


(defn- raci-assignment-section
  "按活动只读聚合RACI职责分配完整度(每项活动是否同时指派了负责A与执行R): 完整/缺负责A/缺执行R与覆盖率并列出未完整活动; 与逐活动缺口冲突提示互补(冲突只列缺口子集而本项给出项目级正向覆盖率), 只读派生, 不改变任何记录."
  [{:keys [model]}]
  (let [cov (:raci_assignment_coverage model)
        total (:total cov 0)
        complete (:complete cov 0)
        missing-a (:missing-accountable cov 0)
        missing-r (:missing-responsible cov 0)
        pct (:coverage-pct cov 0)
        incomplete (:incomplete-activities cov [])]
    [shared/panel "RACI职责分配完整度" "按活动只读聚合每项活动是否同时指派了负责(A)与执行(R), 给出完整活动数/缺负责/缺执行与覆盖率并列出未完整活动; 与逐活动缺口提示互补, 只读派生, 不改变任何记录"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无RACI职责指派, 指派后可在此查看职责分配完整度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "活动总数 " total)]
         [antd/tag {:color "green"} (str "职责齐备 " complete)]
         (when (pos? missing-a)
           [antd/tag {:color "red"} (str "缺负责A " missing-a)])
         (when (pos? missing-r)
           [antd/tag {:color "red"} (str "缺执行R " missing-r)])
         [antd/tag {:color (if (zero? pct) "red" "cyan")} (str "完整覆盖率 " pct "%")]]
        (when (pos? (count incomplete))
          [antd/space {:wrap true}
           (for [ac incomplete]
             ^{:key (:activity ac)} [antd/tag {:color "default"}
                                     (str (:activity ac) " · "
                                          (if (and (:missing-accountable ac) (:missing-responsible ac))
                                            "缺负责A、缺执行R"
                                            (if (:missing-accountable ac) "缺负责A" "缺执行R")))])])])]))


(defn- raci-engagement-section
  "按活动只读聚合RACI咨询(C)与知会(I)角色配置覆盖度(每项活动是否同时指派了咨询C与知会I): 含咨询/含知会/两者齐备与覆盖度并列出配置单薄活动; 与只查负责A执行R的职责分配完整度互补(完整度回答谁做谁负责而本项回答决策有没有被充分咨询、相关方有没有被知会), 只读派生, 不改变任何记录."
  [{:keys [model]}]
  (let [cov (:raci_engagement_coverage model)
        total (:total cov 0)
        with-c (:with-consult cov 0)
        with-i (:with-inform cov 0)
        fully (:fully-engaged cov 0)
        pct (:engagement-pct cov 0)
        thin (:thin-activities cov [])]
    [shared/panel "RACI咨询知会覆盖度" "按活动只读聚合每项活动是否同时指派了咨询(C)与知会(I), 给出含咨询/含知会/两者齐备与覆盖度并列出配置单薄活动; 与职责分配完整度互补(完整度回答谁做谁负责, 本项回答有没有充分咨询与知会), 只读派生, 不改变任何记录"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无RACI职责指派, 指派后可在此查看咨询知会覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "活动总数 " total)]
         [antd/tag {:color "green"} (str "咨询知会齐备 " fully)]
         (when (pos? with-c)
           [antd/tag {:color "default"} (str "含咨询C " with-c)])
         (when (pos? with-i)
           [antd/tag {:color "default"} (str "含知会I " with-i)])
         [antd/tag {:color (if (zero? pct) "red" "cyan")} (str "咨询知会覆盖度 " pct "%")]]
        (when (pos? (count thin))
          [antd/space {:wrap true}
           (for [ac thin]
             ^{:key (:activity ac)} [antd/tag {:color "default"}
                                      (str (:activity ac) " · "
                                           (if (and (:missing-consult ac) (:missing-inform ac))
                                             "缺咨询C、缺知会I"
                                             (if (:missing-consult ac) "缺咨询C" "缺知会I")))])])])]))


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
     {:title "证据发布" :key "release" :width 160
      :render (fn [_ row]
                (let [st (aget row "evidence_release_state")
                      voided (true? (aget row "evidence_voided"))
                      color ({"released" "green" "pending" "gold" "rejected" "red" "missing" "volcano" "n/a" "default"} st)
                      label ({"released" "已发布" "pending" "待发布" "rejected" "已驳回" "missing" "证据缺失" "n/a" "任务关联"} st)]
                  (r/as-element [:div {:style {:display "flex" :flex-wrap "wrap" :gap 4}}
                                 (if (nil? label) [:span {:style {:color "#98a2b3"}} "—"] [antd/tag {:color color} label])
                                 (when voided [antd/tag {:color "red"} "证据已作废"])])))}
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
   (when (and editable? (contains? #{"open" "mitigated"} (:status risk)))
     [w/edit-button "落实预防措施"
      #(open! {:title "落实为预防行动项" :path (str base "/risks/" (:id risk) "/mitigation-action")
               :description "将风险的应对措施落实为有负责人和到期日的可追踪行动项, 默认沿用风险责任人与到期日."
               :initial {:title (let [m (:mitigation risk)] (if (seq m) (subs m 0 (min 200 (count m))) ""))}
               :fields [{:key :title :label "预防行动内容" :required? true}]})])
   [review-actions context "risks" risk]])


(defn- risk-coverage-section
  "按每个风险最新有效版本只读聚合应对策略声明覆盖度: 四类策略各自计数与覆盖率; 只读派生, 不改变风险状态."
  [{:keys [model]}]
  (let [cov (:risk_response_coverage model)
        total (:total cov 0)
        undeclared (:undeclared cov 0)
        pct (:coverage-pct cov 0)
        strategy-label {"avoid" "规避" "transfer" "转移" "mitigate" "减轻" "accept" "接受"}]
    [shared/panel "风险应对覆盖度" "按每个风险的最新有效版本统计 PMI 四类应对策略声明情况; 只读派生, 不改变风险状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目风险, 登记后可在此查看应对策略覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "风险总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已声明应对策略 " pct "%")]
         (when (pos? undeclared)
           [antd/tag {:color "orange"} (str "未设定 " undeclared)])]
        [:div
         [:span {:style {:fontWeight 500}} "按应对策略: "]
         [antd/space {:wrap true}
          (for [{:keys [strategy count]} (:by-strategy cov)]
            ^{:key strategy} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get strategy-label strategy strategy) " · " count)])]]])]))


(defn- risk-category-coverage-section
  "按每个风险最新有效版本只读聚合风险类别(RBS)声明覆盖度: 六类风险分解结构类别各自计数与覆盖率; 只读派生, 不改变风险状态."
  [{:keys [model]}]
  (let [cov (:risk_category_coverage model)
        total (:total cov 0)
        undeclared (:undeclared cov 0)
        pct (:coverage-pct cov 0)
        category-label {"technical" "技术" "external" "外部" "organizational" "组织" "schedule" "进度" "cost" "成本" "quality" "质量"}]
    [shared/panel "风险类别覆盖度" "按每个风险的最新有效版本统计 PMI 风险分解结构(RBS)六类类别声明情况; 只读派生, 不改变风险状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目风险, 登记后可在此查看风险类别覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "风险总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已声明风险类别 " pct "%")]
         (when (pos? undeclared)
           [antd/tag {:color "orange"} (str "未设定 " undeclared)])]
        [:div
         [:span {:style {:fontWeight 500}} "按风险类别: "]
         [antd/space {:wrap true}
          (for [{:keys [category count]} (:by-category cov)]
            ^{:key category} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get category-label category category) " · " count)])]]])]))


(defn- risk-escalation-section
  "按每个风险最新有效版本只读聚合超阈值升级处置进度: 升级总数, 待确认/已确认/已豁免及分级计数; 只读派生, 不改变风险状态."
  [{:keys [model]}]
  (let [esc (:risk_escalation_summary model)
        total (:total esc 0)
        escalated (:escalated esc 0)
        pending (:pending esc 0)
        acknowledged (:acknowledged esc 0)
        waived (:waived esc 0)
        level-label {"steering" "管理层" "management" "经理层"}]
    [shared/panel "风险升级处置汇总" "按每个风险的最新有效版本只读聚合超阈值升级处置进度; 只读派生, 不改变风险状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目风险, 登记后可在此查看超阈值升级处置情况."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "风险总数 " total)]
         [antd/tag {:color (if (pos? escalated) "red" "default")} (str "已超阈值升级 " escalated)]
         (when (pos? pending)
           [antd/tag {:color "orange"} (str "待独立确认 " pending)])
         (when (pos? acknowledged)
           [antd/tag {:color "green"} (str "已确认责成处置 " acknowledged)])
         (when (pos? waived)
           [antd/tag {:color "cyan"} (str "评估后豁免 " waived)])]
        [:div
         [:span {:style {:fontWeight 500}} "按升级层级: "]
         [antd/space {:wrap true}
          (for [{:keys [level count]} (:by-level esc)]
            ^{:key level} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get level-label level level) " · " count)])]]])]))


(defn- risk-score-distribution-section
  "按每个风险最新有效版本只读聚合概率x影响评分(1-25)热力分布: 低/中/高/极高四档各自计数, 高档及以上与达超阈值升级门控的极高计数及平均评分; 只读派生, 不改变风险状态."
  [{:keys [model]}]
  (let [dist (:risk_score_distribution model)
        total (:total dist 0)
        high-above (:high-or-above dist 0)
        critical (:critical dist 0)
        avg (:avg-score dist 0)
        band-color {"low" "green" "medium" "blue" "high" "gold" "critical" "red"}]
    [shared/panel "风险评分热力分布" "按每个风险的最新有效版本只读统计概率 x 影响评分(1-25)热力分布; 极高档与超阈值升级门控对齐, 只读派生, 不改变风险状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目风险, 登记后可在此查看评分热力分布."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "风险总数 " total)]
         [antd/tag {:color "default"} (str "平均评分 " avg)]
         (when (pos? high-above)
           [antd/tag {:color "gold"} (str "高档及以上 " high-above)])
         (when (pos? critical)
           [antd/tag {:color "red"} (str "极高(达升级阈值) " critical)])]
        [:div
         [:span {:style {:fontWeight 500}} "评分分布: "]
         [antd/space {:wrap true}
          (for [{:keys [band label count]} (:by-band dist)]
            ^{:key band} [antd/tag {:color (get band-color band "default")}
                          (str label " · " count)])]]])]))


(defn- risk-stage-distribution-section
  "按每个风险最新有效版本只读聚合项目阶段(:stage)分布: 各阶段风险计数与平均评分, 高档及以上与达超阈值升级门控的严重计数, 以及未标注阶段计数; 阶段取自典型风险库实例化, 只读派生, 不改变风险状态."
  [{:keys [model]}]
  (let [dist (:risk_stage_distribution model)
        total (:total dist 0)
        labeled (:labeled-stages dist 0)
        unclassified (:unclassified dist 0)
        stage-color (fn [s] (cond (pos? (:critical s 0)) "red"
                                  (pos? (:high-or-above s 0)) "gold"
                                  :else "geekblue"))]
    [shared/panel "风险项目阶段分布" "按每个风险的最新有效版本只读统计风险在项目阶段(如设计/采购/执行)的分布, 并计高危及以上与达超阈值升级门控的严重风险数; 只读派生, 不改变风险状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目风险, 登记后可在此查看阶段分布."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "风险总数 " total)]
         [antd/tag {:color "default"} (str "涉及阶段 " labeled)]
         (when (pos? unclassified)
           [antd/tag {:color "orange"} (str "未标注阶段 " unclassified)])]
        [:div
         [:span {:style {:fontWeight 500}} "按项目阶段: "]
         [antd/space {:wrap true}
          (for [{:keys [stage count avg-score high-or-above critical]} (:stages dist)]
            ^{:key stage} [antd/tag {:color (stage-color {:high-or-above high-or-above :critical critical})}
                            (str stage " · " count " · 均分" avg-score
                                 (when (pos? high-or-above) (str " · 高危" high-or-above))
                                 (when (pos? critical) (str " · 严重" critical)))])]]])]))


(defn- risk-review-cadence-section
  "按每个风险最新有效版本只读聚合复审到期节奏: 未关闭风险按到期日三分已逾期/临期/未来到期, 并给出待复审与已关闭计数; 复用逐条台账到期倒计时口径, 只读派生, 不改变风险状态."
  [{:keys [model]}]
  (let [cad (:risk_review_cadence model)
        total (:total cad 0)
        open (:open cad 0)
        closed (:closed cad 0)
        overdue (:overdue cad 0)
        due-soon (:due-soon cad 0)
        upcoming (:upcoming cad 0)]
    [shared/panel "风险复审到期节奏" "按每个风险的最新有效版本只读聚合复审到期节奏(未关闭风险按到期日三分已逾期/临期/未来到期, 并计待复审与已关闭); 与逐条台账到期倒计时同源, 只读派生, 不改变风险状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目风险, 登记后可在此查看复审到期节奏."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "风险总数 " total)]
         [antd/tag {:color "default"} (str "待复审 " open)]
         (when (pos? closed)
           [antd/tag {:color "default"} (str "已关闭 " closed)])
         (when (pos? overdue)
           [antd/tag {:color "red"} (str "已逾期 " overdue)])
         (when (pos? due-soon)
           [antd/tag {:color "gold"} (str "临期 " due-soon)])
         (when (pos? upcoming)
           [antd/tag {:color "green"} (str "未来到期 " upcoming)])]])]))


(defn- risk-template-section
  "自定义风险模板台账: 项目内可复用的风险评分与应对措施模板, 支持新建,编辑,实例化为真实风险与受控作废."
  [{:keys [base model options editable? open!]}]
  [shared/panel "自定义风险模板" "沉淀本项目常见的风险评分与标准应对措施, 供一键实例化为真实风险; 实例化时达到升级阈值的条目自动进入超阈值升级待独立确认门控. 编辑只影响此后实例化, 不追溯历史风险."
   (when editable?
     [antd/space
      [antd/button {:type "primary" :ghost true :on-click #(open! (forms/risk-template-create-dialog base))} "新建自定义风险模板"]])
   [w/record-table (:risk_templates model)
    [(w/text-column :title "模板标题") (w/text-column :probability "概率") (w/text-column :impact "影响")
     (w/text-column :score "评分")
     {:title "风险类别" :dataIndex "category" :width 100
      :render (fn [_ row]
                (let [c (aget row "category")
                      label (get {"technical" "技术" "external" "外部" "organizational" "组织"
                                  "schedule" "进度" "cost" "成本" "quality" "质量"} c)]
                  (r/as-element (if (nil? c)
                                  [:span {:style {:color "#98a2b3"}} "未设定"]
                                  [antd/tag {:color "geekblue"} label]))))}
     (w/text-column :stage "适用阶段")
     (w/text-column :mitigation "标准应对措施")
     {:title "升级预判" :dataIndex "score" :width 130
      :render (fn [_ row]
                (let [sc (aget row "score")]
                  (r/as-element
                   (cond (>= sc 20) [antd/tag {:color "red"} "实例化将升级至决策层"]
                         (>= sc 16) [antd/tag {:color "gold"} "实例化将超阈值升级"]
                         :else [:span {:style {:color "#98a2b3"}} "低于升级阈值"]))))}]
    (when editable? (fn [row] [antd/space {:wrap true}
                              [w/edit-button "实例化为风险" #(open! (forms/risk-custom-template-dialog base (:risk_templates model) options))]
                              [w/edit-button "编辑" #(open! (forms/risk-template-update-dialog base row))]
                              [w/edit-button "作废" #(open! (forms/discard-dialog (str base "/risk-templates/" (:id row) "/discard") "风险模板"))]]))]])


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
     {:title "风险类别" :dataIndex "risk_category" :width 100
      :render (fn [_ row]
                (let [c (aget row "risk_category")
                      label (get {"technical" "技术" "external" "外部" "organizational" "组织"
                                  "schedule" "进度" "cost" "成本" "quality" "质量"} c)
                      color (get {"technical" "blue" "external" "purple" "organizational" "geekblue"
                                  "schedule" "gold" "cost" "cyan" "quality" "green"} c "default")]
                  (r/as-element (if (nil? c)
                                  [:span {:style {:color "#98a2b3"}} "未设定"]
                                  [antd/tag {:color color} label]))))}
     {:title "复审频率" :dataIndex "review_frequency" :width 100
      :render (fn [_ row]
                (let [f (aget row "review_frequency")
                      label (get {"weekly" "每周" "biweekly" "双周" "monthly" "每月" "quarterly" "每季度"} f)
                      color (get {"weekly" "volcano" "biweekly" "orange" "monthly" "gold" "quarterly" "lime"} f "default")]
                  (r/as-element (if (nil? f)
                                  [:span {:style {:color "#98a2b3"}} "未设定"]
                                  [antd/tag {:color color} label]))))}
     (w/text-column :mitigation "应对措施")
     {:title "措施落实" :dataIndex "mitigation_action_state" :width 150
      :render (fn [_ row]
                (let [st (aget row "mitigation_action_state")
                      total (aget row "mitigation_action_total")
                      open (aget row "mitigation_action_open")
                      overdue (aget row "mitigation_action_overdue")
                      state-tag (cond
                                  (= st "completed") [antd/tag {:color "green"} (str "已落实 " total " 项")]
                                  (= st "in-progress") [antd/tag {:color "gold"} (str "落实中, " open " 项待办")]
                                  (= st "unimplemented") [antd/tag {:color "orange"} "措施未落实"]
                                  :else nil)]
                  (r/as-element
                   (if (nil? state-tag)
                     [:span {:style {:color "#98a2b3"}} "—"]
                     [:span
                      state-tag
                      (when (and (= st "in-progress") (pos? overdue))
                        [antd/tag {:color "red"} (str overdue " 项逾期")])]))))}
     (w/text-column :review_due_date "下次复评")
     {:title "复评提醒" :dataIndex "review_overdue" :render #(when % (r/as-element [antd/tag {:color "red"} "复评已逾期"]))}
     (due-countdown-column "review_due_in_days")
     {:title "转出问题" :dataIndex "risk_issue_title" :width 160
      :render (fn [_ row]
                (let [t (aget row "risk_issue_title")]
                  (r/as-element (if (some? t) [antd/tag {:color "cyan"} t] [:span {:style {:color "#98a2b3"}} "未转出"]))))}
     (owner-load-column)
     (w/state-column)] #(risk-actions context %)]])


(defn- resolution-type-cell
  "渲染问题解决方式标签 (可选枚举, 提交解决时未选择则视为未设定)."
  [row]
  (let [t (aget row "resolution_type")
        label (get {"fixed" "已修复" "workaround" "已规避" "by-design" "设计如此"
                    "duplicate" "重复" "cannot-reproduce" "无法复现" "wont-fix" "不予修复"} t)
        color (get {"fixed" "green" "workaround" "blue" "by-design" "geekblue"
                    "duplicate" "purple" "cannot-reproduce" "orange" "wont-fix" "red"} t "default")]
    (r/as-element
      (if (nil? t)
        [:span {:style {:color "#98a2b3"}} "未设定"]
        [antd/tag {:color color} label]))))


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
     {:title "解决方式" :dataIndex "resolution_type" :width 110
      :render (fn [_ row] (resolution-type-cell row))}
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
                            {:key :resolution_type :label "解决方式 (可选)" :type :select
                             :options [{:value "fixed" :label "已修复"} {:value "workaround" :label "已规避"}
                                       {:value "by-design" :label "设计如此"} {:value "duplicate" :label "重复"}
                                       {:value "cannot-reproduce" :label "无法复现"} {:value "wont-fix" :label "不予修复"}]}
                            (forms/evidence-field (:documents model)) (forms/reviewer-field options)]})])
       (when (and editable? (contains? #{"open" "rejected"} (:status issue)))
         [w/edit-button "转派" #(open! (forms/issue-reassign-dialog base options issue))])
       [review-actions context "issues" issue]])]])


(defn- issue-escalation-section
  "按每个问题最新有效版本只读聚合阻断级/逾期自动升级处置进度: 升级总数, 待确认/已确认/已豁免及分级计数; 只读派生, 不改变问题状态."
  [{:keys [model]}]
  (let [esc (:issue_escalation_summary model)
        total (:total esc 0)
        escalated (:escalated esc 0)
        pending (:pending esc 0)
        acknowledged (:acknowledged esc 0)
        waived (:waived esc 0)
        level-label {"steering" "管理层" "management" "经理层"}]
    [shared/panel "问题升级处置汇总" "按每个问题的最新有效版本只读聚合阻断级/逾期自动升级处置进度; 只读派生, 不改变问题状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目问题, 登记后可在此查看阻断级/逾期升级处置情况."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "问题总数 " total)]
         [antd/tag {:color (if (pos? escalated) "red" "default")} (str "已升级待处置 " escalated)]
         (when (pos? pending)
           [antd/tag {:color "orange"} (str "待独立确认 " pending)])
         (when (pos? acknowledged)
           [antd/tag {:color "green"} (str "已确认责成处置 " acknowledged)])
         (when (pos? waived)
           [antd/tag {:color "cyan"} (str "评估后豁免 " waived)])]
        [:div
         [:span {:style {:fontWeight 500}} "按升级层级: "]
         [antd/space {:wrap true}
          (for [{:keys [level count]} (:by-level esc)]
            ^{:key level} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get level-label level level) " · " count)])]]])]))


(defn- issue-closure-summary-section
  "按全部问题最新有效版本只读聚合闭环健康度: 闭环率/未关闭构成/逾期与各严重度分布; 只读派生, 不改变问题状态, 不构成门控."
  [{:keys [model]}]
  (let [cl (:issue_closure model)
        total (:total cl 0)
        closed (:closed cl 0)
        pending (:pending cl 0)
        in-review (:in-review cl 0)
        rejected (:rejected cl 0)
        overdue (:overdue cl 0)
        blocker-open (:blocker-open cl 0)
        pct (:closure-pct cl 0)
        sev-label {"blocker" "阻断" "major" "严重" "minor" "一般"}]
    [shared/panel "问题闭环与严重度分布汇总" "按每个问题的最新有效版本只读聚合闭环健康度(闭环率/待处理/验证中/已驳回/逾期未关闭/未关闭阻断级与各严重度分布); 只读派生, 不改变问题状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目问题, 登记后可在此查看闭环率与严重度分布."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "问题总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已闭环 " pct "% (" closed "/" total ")")]
         (when (pos? pending)
           [antd/tag {:color "gold"} (str "待处理 " pending)])
         (when (pos? in-review)
           [antd/tag {:color "orange"} (str "验证中 " in-review)])
         (when (pos? rejected)
           [antd/tag {:color "red"} (str "已驳回 " rejected)])
         (when (pos? overdue)
           [antd/tag {:color "volcano"} (str "逾期未关闭 " overdue)])
         (when (pos? blocker-open)
           [antd/tag {:color "magenta"} (str "未关闭阻断级 " blocker-open)])]
        [:div
         [:span {:style {:fontWeight 500}} "按严重度分布: "]
         [antd/space {:wrap true}
          (for [{:keys [severity count]} (:by-severity cl)]
            ^{:key severity} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get sev-label severity severity) " · " count)])]]])]))


(defn- resolution-coverage-section
  "按全部问题最新有效版本只读聚合解决方式(Issue 处置类型)声明覆盖度: 六类解决方式各自计数与覆盖率; 只读派生, 不改变问题状态, 不构成门控."
  [{:keys [model]}]
  (let [cov (:issue_resolution_coverage model)
        total (:total cov 0)
        undeclared (:undeclared cov 0)
        pct (:coverage-pct cov 0)
        resolution-label {"fixed" "已修复" "workaround" "已规避" "by-design" "设计如此"
                          "duplicate" "重复" "cannot-reproduce" "无法复现" "wont-fix" "不予修复"}
        resolution-color {"fixed" "green" "workaround" "cyan" "by-design" "purple"
                          "duplicate" "geekblue" "cannot-reproduce" "orange" "wont-fix" "red"}]
    [shared/panel "问题解决方式覆盖度" "按每个问题的最新有效版本统计提交解决时声明的六类处置方式(已修复/已规避/设计如此/重复/无法复现/不予修复)覆盖情况; 只读派生, 不改变问题状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目问题, 登记后可在此查看解决方式覆盖度."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "问题总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已声明解决方式 " pct "%")]
         (when (pos? undeclared)
           [antd/tag {:color "orange"} (str "未设定 " undeclared)])]
        [:div
         [:span {:style {:fontWeight 500}} "按解决方式: "]
         [antd/space {:wrap true}
          (for [{:keys [resolution count]} (:by-resolution cov)]
            ^{:key resolution} [antd/tag {:color (if (pos? count) (get resolution-color resolution "geekblue") "default")}
                              (str (get resolution-label resolution resolution) " · " count)])]]])]))


(defn- meeting-section
  "从会议纪要产生明确行动,避免只记录不执行.会前资料绑定项目内真实文档版本."
  [{:keys [base model options planning editable? approve? open! preview!]}]
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
     {:title "纪要发布" :dataIndex "status" :width 110
      :render #(r/as-element (let [[t c] (get {"recorded" ["草稿" "default"] "in_review" ["发布审批中" "blue"] "approved" ["已发布" "green"] "discarded" ["已作废" "red"]} % [% "default"])]
                              [antd/tag {:color c} t]))}
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
    (fn [meeting]
      (let [current (:currentUserId options) st (:status meeting)]
        [antd/space {:wrap true}
         (when (and editable? (not= "discarded" st)) [w/edit-button "形成行动" #(open! (forms/action-dialog base options meeting))])
         (when (and editable? (= "recorded" st))
           [w/edit-button "提交发布"
            #(open! {:title "提交会议纪要发布审批" :path (str base "/meetings/" (:id meeting) "/submit")
                     :description "选择具备质量审批权限的独立审核人, 冻结纪要进入发布评审, 驳回后可补充重提."
                     :fields [(forms/reviewer-field options)]})])
         (when editable? [w/edit-button "级联影响" #(preview! {:collection "meetings" :id (:id meeting) :label (str "会议 " (:title meeting))})])
         (when (and editable? (= "recorded" st))
           [w/edit-button "作废" #(open! (forms/discard-dialog (str base "/meetings/" (:id meeting) "/discard") "会议纪要"))])
         (when (and editable? (= "discarded" st))
           [w/edit-button "恢复" #(open! (forms/restore-dialog (str base "/meetings/" (:id meeting) "/restore") "会议纪要"))])
         (when (and approve? (= "in_review" st) (= current (:reviewer_id meeting)) (not= current (:submitted_by meeting)))
           [:<>
            [w/edit-button "批准发布" #(open! (forms/decision-dialog (str base "/meetings/" (:id meeting) "/decision") "approved" "正式批准会议纪要"))]
            [w/edit-button "驳回" #(open! (forms/decision-dialog (str base "/meetings/" (:id meeting) "/decision") "rejected" "驳回纪要发布"))]])]))]])


(defn- action-actions
  "会议行动的转任务,完成提交,独立核验与已关闭行动受控重开操作路径."
  [{:keys [base model options editable? approve? open!]} action]
  (let [state (:status action)
        current (:currentUserId options)
        reopen? (= "action_reopen" (:review_action action))
        verify-path (str base "/actions/" (:id action) "/verify")]
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
     (when (and editable? (= "closed" state))
       [w/edit-button "申请重开" #(open! (forms/action-reopen-dialog base options (:documents model) action))])
     (when (and approve? (= "in_review" state) (= current (:reviewer_id action)) (not= current (:submitted_by action)))
       [:<>
        [w/edit-button (if reopen? "批准重开" "批准关闭")
         #(open! (forms/decision-dialog verify-path "approved"
                                        (if reopen? "批准重开并重新打开行动" "核验通过并关闭行动")))]
        [w/edit-button "驳回"
         #(open! (forms/decision-dialog verify-path "rejected"
                                        (if reopen? "驳回重开并维持关闭" "驳回行动完成")))]])]))


(defn- action-closure-section
  "按全部会议行动与风险预防行动只读聚合闭环情况: closed 或 converted 视为已闭环; 逾期仅统计未闭环且到期日不晚于今日的行动; 只读派生, 不构成门控."
  [{:keys [model]}]
  (let [cl (:action_closure model)
        total (:total cl 0)
        closed (:closed cl 0)
        open (:open cl 0)
        converted (:converted cl 0)
        overdue (:overdue cl 0)
        pct (:closure-pct cl 0)]
    [shared/panel "会议行动闭环率" "统一统计会议行动与风险预防行动的闭环情况(closed 或转任务视为已闭环); 逾期仅计未闭环且到期日已过者; 只读派生, 不改变行动状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无行动项, 会议或风险落实行动后可在此查看闭环率."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "行动总数 " total)]
        [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
         (str "已闭环 " pct "% (" closed "/" total ")")]
        (when (pos? open)
          [antd/tag {:color "orange"} (str "未完成 " open)])
        (when (pos? overdue)
          [antd/tag {:color "red"} (str "逾期未闭环 " overdue)])
        (when (pos? converted)
          [antd/tag {:color "geekblue"} (str "转任务 " converted)])])]))


(defn- project-remediation-overview-section
  "跨对象项目级未闭环整改总览: 汇总试验/关口/质量/风险/绩效五类整改来源的总数与未闭环情况; 试验与各类整改来源台账逐项视图互补, 只读派生, 不构成门控."
  [{:keys [model]}]
  (let [ov (:project_remediation_overview model)
        total (:total ov 0)
        closed (:closed ov 0)
        open (:open ov 0)
        overdue (:overdue ov 0)
        pct (:closure-pct ov 0)
        sources (:sources-with-remediation ov 0)
        sources-open (:sources-with-open ov 0)]
    [shared/panel "未闭环整改总览" "把试验不合格/关口检查/质量检查/风险预防/绩效偏差五类整改来源的未闭环情况汇总到一处, 回答项目全局还欠多少整改没做完; 与各来源台账逐项整改视图互补; 只读派生, 不改变任何记录, 不构成门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无整改项, 在试验/关口/质量/风险/绩效登记整改行动或问题后可在此查看全局闭环情况."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "整改来源 " sources " 类")]
         [antd/tag {:color "purple"} (str "整改总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已闭环 " pct "% (" closed "/" total ")")]
         (when (pos? open)
           [antd/tag {:color "orange"} (str "未完成 " open)])
         (when (pos? overdue)
           [antd/tag {:color "volcano"} (str "逾期未闭环 " overdue)])
         (when (pos? sources-open)
           [antd/tag {:color "magenta"} (str "仍有未闭环来源 " sources-open)])]
        [:div
         [:span {:style {:fontWeight 500}} "按整改来源: "]
         [antd/space {:wrap true}
          (for [{:keys [key label total open closed overdue closure-pct]} (:by-source ov)]
            ^{:key key} [antd/tag {:color (cond (= closure-pct 100) "green" (pos? overdue) "red" (zero? open) "green" :else "gold")}
                         (str label " · 闭环 " closure-pct "% (" closed "/" total ")"
                              (when (pos? open) (str " · 未闭环 " open))
                              (when (pos? overdue) (str " · 逾期 " overdue)))])]]])]))


(defn- due-workload-overview-section
  "跨风险/问题/行动只读聚合未闭环治理事项的到期压力总览: 全局逾期/临期/未来到期/更远期/无到期日分档与逐来源计数, 另列按剩余天数升序(逾期在前)的最近到期清单; 只读派生, 不改变任何记录, 不构成门控."
  [{:keys [model]}]
  (let [ov (:due_workload_overview model)
        open-total (:open-total ov 0)
        overdue (:overdue ov 0)
        due-soon (:due-soon ov 0)
        upcoming (:upcoming ov 0)
        further (:further ov 0)
        undated (:undated ov 0)
        source-label {"risk" "风险" "issue" "问题" "action" "行动"}
        days-text (fn [d]
                    (cond
                      (nil? d) "无到期日"
                      (neg? d) (str "已逾期 " (- d) " 天")
                      (zero? d) "今天到期"
                      :else (str "剩 " d " 天")))
        src-line (fn [k label]
                   (let [s (k ov)
                         open (:open s 0)
                         od (:overdue s 0)
                         so (:due-soon s 0)
                         up (:upcoming s 0)]
                     ^{:key (name k)}
                     [antd/tag {:color (cond (pos? od) "red" (pos? so) "gold" (pos? up) "blue" (zero? open) "default" :else "geekblue")}
                      (str label " · 未闭环 " open
                           (when (pos? od) (str " · 逾期 " od))
                           (when (pos? so) (str " · 临期 " so))
                           (when (pos? up) (str " · 未来 " up)))]))]
    [shared/panel "到期治理事项总览" "把风险/问题/会议行动中所有未闭环事项的到期压力汇总到一处, 按已逾期/临期(7天内)/未来到期(30天内)/更远期/无到期日分档并给出最近到期清单(逾期在前); 与各来源台账逐项到期视图互补; 只读派生, 不改变任何记录, 不构成门控"
     (if (zero? open-total)
       [:span {:style {:color "#8793a3"}} "暂无未闭环的到期事项, 登记风险/问题/行动并设定到期日后可在此查看到期压力总览."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "purple"} (str "未闭环事项 " open-total)]
         (when (pos? overdue)
           [antd/tag {:color "red"} (str "已逾期 " overdue)])
         (when (pos? due-soon)
           [antd/tag {:color "volcano"} (str "临期(7天内) " due-soon)])
         (when (pos? upcoming)
           [antd/tag {:color "gold"} (str "未来到期(30天内) " upcoming)])
         (when (pos? further)
           [antd/tag {:color "blue"} (str "更远期 " further)])
         (when (pos? undated)
           [antd/tag {:color "default"} (str "无到期日 " undated)])]
        [:div
         [:span {:style {:fontWeight 500}} "按来源: "]
         (into [antd/space {:wrap true}]
               (map (fn [[k label]] (src-line k label))
                    [[:by-risk "风险"] [:by-issue "问题"] [:by-action "行动"]]))]
        (when (seq (:soonest ov))
          [:div
           [:span {:style {:fontWeight 500}} "最近到期: "]
           (into [antd/space {:wrap true}]
                 (map (fn [it]
                        (let [d (:due-days it)]
                          ^{:key (str (:source it) "-" (:id it))}
                          [antd/tag {:color (cond (nil? d) "default" (<= d 0) "red" (<= d 7) "volcano" :else "gold")}
                           (str "[" (source-label (:source it)) "] " (:title it)
                                " · " (days-text d)
                                (when (:due-date it) (str " (" (:due-date it) ")")))]))
                      (:soonest ov)))])])]))


(defn- owner-due-pressure-section
  "跨风险/问题/会议行动按责任人只读聚合其未闭环事项的到期压力热点: 以责任人维度加权排名(逾期x4+临期x2+未来x1)点名最热责任人, 无到期日不计压力, 无责任人事项单独计数不混入热点; 与按事项维度的到期治理事项总览互补; 只读派生, 不改变任何记录, 不构成门控."
  [{:keys [model]}]
  (let [op (:owner_due_pressure model)
        available (:available op false)
        assigned (:assigned-total op 0)
        unassigned (:unassigned-total op 0)
        owner-count (:owner-count op 0)
        owners-od (:owners-with-overdue op 0)
        overdue (:overdue op 0)
        due-soon (:due-soon op 0)
        upcoming (:upcoming op 0)
        undated (:undated op 0)
        by-owner (:by-owner op [])
        hottest (:hottest op)
        pressure-color (fn [p] (cond (>= p 8) "red" (>= p 4) "volcano" (>= p 2) "gold" :else "blue"))
        owner-text (fn [o]
                     (str (:name o) " · 压力 " (:pressure o 0)
                          " · 未闭环 " (:open o 0)
                          (when (pos? (:overdue o 0)) (str " · 逾期 " (:overdue o 0)))
                          (when (pos? (:due-soon o 0)) (str " · 临期 " (:due-soon o 0)))
                          (when (pos? (:upcoming o 0)) (str " · 未来 " (:upcoming o 0)))
                          (when (pos? (:further o 0)) (str " · 更远 " (:further o 0)))
                          (when (pos? (:undated o 0)) (str " · 无日期 " (:undated o 0)))))]
    [shared/panel "责任人到期压力热点" "把风险/问题/会议行动按责任人聚合其未闭环事项的到期压力并加权排名(已逾期x4 + 临期7天内x2 + 未来30天内x1), 压力降序点名最需要关注或再分配的责任人; 无到期日不计压力, 无责任人事项单独计数不混入热点; 与按事项维度的到期治理事项总览互补; 只读派生, 不改变任何记录, 不构成门控"
     (if (not available)
       [:span {:style {:color "#8793a3"}} "暂无分配了责任人的未闭环到期事项, 给风险/问题/行动指定责任人并设定到期日后可在此查看压力热点."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "purple"} (str "有责任人未闭环 " assigned)]
         (when (pos? unassigned)
           [antd/tag {:color "default"} (str "无责任人 " unassigned)])]
        [antd/space {:wrap true}
         [antd/tag {:color "geekblue"} (str "涉及责任人 " owner-count)]
         (when (pos? owners-od)
           [antd/tag {:color "red"} (str "有逾期责任人 " owners-od)])
         (when (pos? overdue)
           [antd/tag {:color "red"} (str "逾期事项 " overdue)])
         (when (pos? due-soon)
           [antd/tag {:color "volcano"} (str "临期事项 " due-soon)])
         (when (pos? upcoming)
           [antd/tag {:color "gold"} (str "未来事项 " upcoming)])
         (when (pos? undated)
           [antd/tag {:color "default"} (str "无到期日 " undated)])]
        (when hottest
          [:div
           [:span {:style {:fontWeight 500}} "最热责任人: "]
           [antd/tag {:color (pressure-color (:pressure hottest 0))} (owner-text hottest)]])
        (when (seq by-owner)
          [:div
           [:span {:style {:fontWeight 500}} "压力排名: "]
           (into [antd/space {:wrap true}]
                 (map (fn [o]
                        ^{:key (:owner-id o)}
                        [antd/tag {:color (pressure-color (:pressure o 0))} (owner-text o)])
                      by-owner))])])]))


(defn- meeting-release-coverage-section
  "按每个会议最新有效版本只读聚合纪要发布进度: 会议总数/已发布/审批中/草稿/已作废及发布率(分母排除已作废); 只读派生, 不改变会议状态, 不构成门控."
  [{:keys [model]}]
  (let [cov (:meeting_release_coverage model)
        total (:total cov 0)
        approved (:approved cov 0)
        in-review (:in-review cov 0)
        recorded (:recorded cov 0)
        discarded (:discarded cov 0)
        pct (:release-pct cov 0)
        denom (- total discarded)]
    [shared/panel "会议纪要发布覆盖度" "统计每个会议最新有效版本的纪要发布进度(草稿/发布审批中/已发布/已作废); 发布率分母排除已作废会议; 只读派生, 不改变会议发布状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无会议, 登记项目会议并提交发布后可在此查看发布覆盖度."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "会议总数 " total)]
        [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
         (str "已发布 " pct "% (" approved "/" denom ")")]
        (when (pos? in-review)
          [antd/tag {:color "orange"} (str "发布审批中 " in-review)])
        (when (pos? recorded)
          [antd/tag {:color "default"} (str "草稿 " recorded)])
        (when (pos? discarded)
          [antd/tag {:color "red"} (str "已作废 " discarded)])])]))


(defn- meeting-material-readiness-section
  "按每个会议最新有效版本只读聚合会前预读材料准备就绪度: 预读覆盖率与资料是否全部指向已发布证据文档; 只读派生, 不改变会议或文档状态, 不构成门控."
  [{:keys [model]}]
  (let [cov (:meeting_material_readiness model)
        total (:total cov 0)
        with-mat (:with-materials cov 0)
        without-mat (:without-materials cov 0)
        published (:materials-published cov 0)
        pending (:materials-pending cov 0)
        readiness (:readiness-pct cov 0)
        full (:full-readiness-pct cov 0)
        unprepared (:unprepared-meetings cov [])
        pending-meetings (:pending-material-meetings cov [])]
    [shared/panel "会议预读材料准备就绪度" "统计每个会议最新有效版本是否已挂会前预读资料, 以及所挂资料是否全部指向已发布证据文档; 只读派生, 不构成门控(启动会会前包强制关联仍由登记环节把关), 不改变会议或文档状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无会议, 登记项目会议并关联会前资料后可在此查看预读就绪度."]
       [:div
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "会议总数 " total)]
         [antd/tag {:color (cond (= readiness 100) "green" (zero? readiness) "red" :else "gold")}
          (str "已备预读 " readiness "% (" with-mat "/" total ")")]
         [antd/tag {:color (cond (= full 100) "green" (zero? full) "red" :else "gold")}
          (str "资料全部已发布 " full "% (" published "/" total ")")]
         (when (pos? without-mat)
           [antd/tag {:color "red"} (str "未备资料 " without-mat)])
         (when (pos? pending)
           [antd/tag {:color "orange"} (str "含待发布资料 " pending)])]
        (when (seq unprepared)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "未准备会前资料: "]
           (into [antd/space {:wrap true}]
                 (map (fn [m] ^{:key (:code m)} [antd/tag {:color "default"} (:title m)]) unprepared))])
        (when (seq pending-meetings)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "预读资料待发布: "]
           (into [antd/space {:wrap true}]
                 (map (fn [m] ^{:key (:code m)} [antd/tag {:color "orange"} (str (:title m) " · 未发布 " (:pending-count m))]) pending-meetings))])])]))


(defn- meeting-attendance-section
  "按每个会议最新有效版本只读聚合参会覆盖与出勤分布: 平均每场参会人数, 出勤排行(参会次数降序), 会议类型分布, 项目成员参会覆盖率与从未参会成员点名; 只读派生, 不改变会议或成员状态, 不构成门控."
  [{:keys [model]}]
  (let [cov (:meeting_attendance_summary model)
        available (:available cov false)
        total (:total cov 0)
        discarded (:discarded cov 0)
        member-count (:member-count cov 0)
        attended-members (:attended-members cov 0)
        coverage (:coverage-pct cov 0)
        avg (:avg-attendance cov 0)
        by-type (:by-type cov [])
        top (:top-attendees cov [])
        unattended (:unattended-members cov [])
        type-label {"regular" "例会" "kickoff" "启动会" "review" "评审会" "fat-kickoff" "FAT启动" "fat-summary" "FAT总结"}
        attend-color (fn [p] (cond (>= p 80) "green" (>= p 50) "gold" :else "volcano"))]
    [shared/panel "会议参会覆盖与出勤分布" "统计每个会议最新有效版本的参会情况: 平均每场参会人数, 按人汇总的出勤排行, 会议类型分布, 以及以项目成员为口径的参会覆盖率与从未参会成员点名; 出勤排行含全部出席者(非成员按用户ID回退显示), 覆盖率仅统计项目成员; 只读派生, 不改变会议或成员状态, 不构成门控"
     (if (not available)
       [:span {:style {:color "#8793a3"}} "暂无有效会议, 登记项目会议并填写参会人后可在此查看参会覆盖与出勤分布."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "有效会议 " total)]
         [antd/tag {:color (cond (= coverage 100) "green" (zero? coverage) "red" :else "gold")}
          (str "成员参会覆盖 " coverage "% (" attended-members "/" member-count ")")]
         [antd/tag {:color "geekblue"} (str "平均每场参会 " avg " 人")]
         (when (pos? discarded)
           [antd/tag {:color "red"} (str "已作废会议 " discarded)])]
        (when (seq by-type)
          [:div
           [:span {:style {:fontWeight 500}} "会议类型分布: "]
           (into [antd/space {:wrap true}]
                 (map (fn [t]
                        ^{:key (:type t)}
                        [antd/tag {:color "cyan"} (str (get type-label (:type t) (:type t)) " · " (:count t) " 场")])
                      by-type))])
        (when (seq top)
          [:div
           [:span {:style {:fontWeight 500}} "出勤排行: "]
           (into [antd/space {:wrap true}]
                 (map (fn [o]
                        ^{:key (:user-id o)}
                        [antd/tag {:color (attend-color (:attendance-pct o 0))}
                         (str (:name o) " · 参会 " (:attended o 0) " 次 · " (:attendance-pct o 0) "%")])
                      top))])
        (when (seq unattended)
          [:div
           [:span {:style {:fontWeight 500}} "从未参会成员: "]
           (into [antd/space {:wrap true}]
                 (map (fn [m]
                        ^{:key (:user-id m)}
                        [antd/tag {:color "default"} (:name m)])
                      unattended))])])]))


(defn- meeting-cadence-section
  "按每个会议最新有效版本只读聚合会议节奏与间隔分布: 首末跨度, 相邻间隔最小/平均/最大, 逐月分布与最热月份, 星期分布; 只读派生, 不改变会议状态, 不构成门控."
  [{:keys [model]}]
  (let [cad (:meeting_cadence model)
        available (:available cad false)
        total (:total cad 0)
        discarded (:discarded cad 0)
        first-held (:first-held cad)
        last-held (:last-held cad)
        span (:span-days cad 0)
        months (:distinct-months cad 0)
        shortest (:shortest-gap-days cad 0)
        avg-gap (:avg-gap-days cad 0)
        longest (:longest-gap-days cad 0)
        busiest (:busiest-month cad)
        by-month (:by-month cad [])
        by-weekday (:by-weekday cad [])
        weekday-label {"monday" "周一" "tuesday" "周二" "wednesday" "周三" "thursday" "周四"
                       "friday" "周五" "saturday" "周六" "sunday" "周日"}]
    [shared/panel "会议节奏与间隔分布" "统计每个会议最新有效版本的举办时间节奏: 首末会议日期与跨天数, 相邻会议的最近/平均/最长间隔, 按月分布与最热月份, 按星期分布; 作废最新版会议即退出全部时间口径; 只读派生, 不改变会议状态, 不构成门控"
     (if (not available)
       [:span {:style {:color "#8793a3"}} "暂无带举办日期的有效会议, 登记会议并填写举办日期后可在此查看节奏与间隔分布."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "有效会议 " total)]
         [antd/tag {:color "geekblue"} (str "首末跨度 " span " 天")]
         [antd/tag {:color "gold"} (str "平均间隔 " avg-gap " 天")]
         [antd/tag {:color "volcano"} (str "最近间隔 " shortest " 天 · 最长间隔 " longest " 天")]
         [antd/tag {:color "cyan"} (str "跨 " months " 个月")]
         (when (some? busiest)
           [antd/tag {:color "purple"} (str "最热月份 " (:month busiest) " · " (:count busiest) " 场")])
         (when (pos? discarded)
           [antd/tag {:color "red"} (str "已作废会议 " discarded)])]
        (when (and (some? first-held) (some? last-held))
          [:div
           [:span {:style {:fontWeight 500}} "举办区间: "]
           [antd/tag (str first-held " ~ " last-held)]])
        (when (seq by-month)
          [:div
           [:span {:style {:fontWeight 500}} "按月分布: "]
           (into [antd/space {:wrap true}]
                 (map (fn [m]
                        ^{:key (:month m)}
                        [antd/tag {:color "blue"} (str (:month m) " · " (:count m) " 场")])
                      by-month))])
        (when (seq by-weekday)
          [:div
           [:span {:style {:fontWeight 500}} "按星期分布: "]
           (into [antd/space {:wrap true}]
                 (map (fn [w]
                        ^{:key (:weekday w)}
                        [antd/tag {:color "cyan"} (str (get weekday-label (:weekday w) (:weekday w)) " · " (:count w) " 场")])
                      by-weekday))])])]))




(defn- action-section
  "会议行动转为实际WBS任务, 完成须证据与独立核验并提示逾期; 已关闭行动可受控重开."
  [{:keys [base model editable? open!] :as context}]
  [shared/panel "会议行动" "会议行动与风险预防行动统一追踪;转换后任务进入项目计划,重复转换保持同一任务;完成需证据与独立核验;已关闭行动须经独立审批重开" nil
   [w/record-table (:actions model)
    [(w/text-column :title "行动内容") (w/text-column :due_date "到期日期")
     {:title "优先级" :dataIndex "priority" :width 90
      :render (fn [_ row]
                (let [p (aget row "priority")
                      spec (cond (= p "high") ["red" "高"] (= p "medium") ["orange" "中"]
                                 (= p "low") ["green" "低"] :else nil)]
                  (r/as-element (if spec [antd/tag {:color (first spec)} (second spec)]
                                    [:span {:style {:color "#98a2b3"}} "未设定"]))))}
     {:title "来源风险" :dataIndex "action_source_risk_title" :width 160
      :render (fn [_ row]
                (let [t (aget row "action_source_risk_title")]
                  (r/as-element (if (some? t) [antd/tag {:color "purple"} t] [:span {:style {:color "#98a2b3"}} "非风险来源"]))))}
     {:title "评审事项" :dataIndex "review_action" :width 120
      :render (fn [v row]
                (let [st (aget row "status")]
                  (r/as-element
                    (cond
                      (and (= st "in_review") (= v "action_reopen")) [antd/tag {:color "volcano"} "重开审批中"]
                      (= v "action_closure") [antd/tag {:color "blue"} "完成核验中"]
                      :else [:span {:style {:color "#98a2b3"}} "—"]))))}
     {:title "逾期" :dataIndex "action_overdue" :render #(when % (r/as-element [antd/tag {:color "red"} "已逾期"]))}
     (due-countdown-column "action_due_in_days")
     (owner-load-column)
     (w/state-column) (w/text-column :result "完成说明") (w/text-column :target_task_id "关联任务")]
    #(action-actions context %)]])


(defn- change-actions
  "变更审批操作: 可设立或重置变更控制委员会并表决; 高影响变更须先独立确认升级, 达到表决门槛后方可批准."
  [{:keys [base model options approve? open!] :as context} change]
  (let [current (:currentUserId options)
        status (:status change)
        members (vec (:ccb_members change))]
    [antd/space {:wrap true}
     (when (and approve? (contains? #{"draft" "in_review"} status))
       [w/edit-button (if (:ccb_required change) "重置变更控制委员会" "设立变更控制委员会")
        #(open! (forms/change-ccb-dialog base (:users options) change))])
     (when (and approve? (= "in_review" status) (some #(= % current) members)
                (not= current (:created_by change)) (not= current (:submitted_by change)))
       [w/edit-button "委员会表决" #(open! (forms/change-ballot-dialog base change))])
     (when (and approve? (= "in_review" status) (true? (:escalated change)) (= "pending" (:escalation_state change))
                (not= current (:created_by change)) (not= current (:submitted_by change)))
       [w/edit-button "确认升级处置" #(open! (forms/change-escalation-dialog base change))])
     (review-actions context "changes" change)]))


(defn- change-section
  "变更审批保留五维影响与独立判断."
  [{:keys [base model editable? open!] :as context}]
  [shared/panel "项目变更控制" "批准变更保留依据,计划调整仍进入计划修订与基线审批"
   (when editable? [antd/button {:on-click #(open! (forms/change-dialog base))} "提出项目变更"])
   [w/record-table (:changes model)
    [(w/text-column :title "变更") (w/text-column :reason "原因")
     {:title "变更类型" :dataIndex "change_type" :width 110
      :render (fn [_ row]
                (let [t (aget row "change_type")
                      label (get {"corrective" "纠错性" "preventive" "预防性"
                                  "defect-repair" "缺陷修复" "updates" "更新"} t)
                      color (get {"corrective" "geekblue" "preventive" "green"
                                  "defect-repair" "orange" "updates" "purple"} t "default")]
                  (r/as-element (if (nil? t)
                                  [:span {:style {:color "#98a2b3"}} "未设定"]
                                  [antd/tag {:color color} label]))))}
     (w/text-column :scope_impact "范围影响")
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
     {:title "变更控制升级" :dataIndex "escalation_state" :width 150
      :render (fn [_ row]
                (let [esc (aget row "escalated") state (aget row "escalation_state")]
                  (r/as-element
                   (cond
                     (not esc) [:span {:style {:color "#98a2b3"}} "未触发"]
                     (= state "pending") [antd/tag {:color "red"} "待独立确认"]
                     (= state "acknowledged") [antd/tag {:color "green"} "升级已确认"]
                     (= state "waived") [antd/tag {:color "blue"} "升级已豁免"]
                     :else [antd/tag state]))))}
     {:title "变更控制表决" :dataIndex "ccb_summary" :width 190
      :render (fn [_ row]
                (let [s (aget row "ccb_summary")
                      state (when s (aget s "state"))
                      approve (when s (aget s "approve"))
                      required (when s (aget s "required"))
                      members (when s (aget s "members"))]
                  (r/as-element
                   (cond
                     (or (nil? s) (= state "none")) [:span {:style {:color "#98a2b3"}} "未设立委员会"]
                     (= state "passed") [antd/tag {:color "green"} (str "表决通过 " approve "/" required)]
                     (= state "failed") [antd/tag {:color "red"} (str "表决未通过 " approve "/" required " / 成员 " members)]
                     :else [antd/tag {:color "gold"} (str "表决中 " approve "/" required " / 成员 " members)]))))}
     (w/state-column)]
    #(change-actions context %)]])


(defn- change-closure-section
  "按每个变更最新有效版本只读聚合变更控制闭环: 变更总数与各状态(草稿/审批中/已批准/已驳回)及批准率, 高影响升级面与独立确认处置推进(待确认/已确认/已豁免), 以及变更控制委员会表决状态分布(未设立/表决中/通过/未通过/达门槛); 只读派生, 不改变变更状态或门控."
  [{:keys [model]}]
  (let [sum (:change_closure_summary model)
        total (:total sum 0)
        draft (:draft sum 0)
        in-review (:in-review sum 0)
        approved (:approved sum 0)
        rejected (:rejected sum 0)
        pct (:approval-pct sum 0)
        high (:high-impact sum 0)
        escalated (:escalated sum 0)
        pending (:pending sum 0)
        acknowledged (:acknowledged sum 0)
        waived (:waived sum 0)
        ccb-voting (:ccb-voting sum 0)
        ccb-passed (:ccb-passed sum 0)
        ccb-failed (:ccb-failed sum 0)
        ccb-quorum (:ccb-quorum sum 0)]
    [shared/panel "变更控制闭环汇总" "统计每个变更最新有效版本的工作流状态分布, 高影响升级与独立确认处置推进, 及变更控制委员会表决进度; 只读派生, 不改变变更状态或门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目变更, 提出并推进变更审批后可在此查看闭环进展."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "变更总数 " total)]
        [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
         (str "已批准 " pct "% (" approved "/" total ")")]
        (when (pos? draft) [antd/tag {:color "default"} (str "草稿 " draft)])
        (when (pos? in-review) [antd/tag {:color "orange"} (str "审批中 " in-review)])
        (when (pos? approved) [antd/tag {:color "green"} (str "已批准 " approved)])
        (when (pos? rejected) [antd/tag {:color "red"} (str "已驳回 " rejected)])
        (when (pos? high) [antd/tag {:color "volcano"} (str "高影响 " high)])
        (when (pos? escalated)
          [antd/tag {:color "red"} (str "已升级 " escalated)])
        (when (pos? pending) [antd/tag {:color "orange"} (str "待独立确认 " pending)])
        (when (pos? acknowledged) [antd/tag {:color "green"} (str "升级已确认 " acknowledged)])
        (when (pos? waived) [antd/tag {:color "blue"} (str "升级已豁免 " waived)])
        (when (pos? ccb-voting) [antd/tag {:color "gold"} (str "委员会表决中 " ccb-voting)])
        (when (pos? ccb-passed) [antd/tag {:color "green"} (str "委员会表决通过 " ccb-passed)])
        (when (pos? ccb-failed) [antd/tag {:color "red"} (str "委员会表决未通过 " ccb-failed)])
        (when (pos? ccb-quorum) [antd/tag {:color "geekblue"} (str "已达表决门槛 " ccb-quorum)])])]))


(defn- change-type-coverage-section
  "按每个变更最新有效版本只读聚合变更请求类型分布: PMBOK 四类变更请求(纠错性/预防性/缺陷修复/更新)各自计数与已声明覆盖率; 只读派生, 不改变变更状态或门控."
  [{:keys [model]}]
  (let [cov (:change_type_coverage model)
        total (:total cov 0)
        undeclared (:undeclared cov 0)
        pct (:coverage-pct cov 0)
        type-label {"corrective" "纠错性" "preventive" "预防性" "defect-repair" "缺陷修复" "updates" "更新"}]
    [shared/panel "变更请求类型分布" "按每个变更的最新有效版本统计 PMBOK 四类变更请求(纠错性/预防性/缺陷修复/更新)声明情况; 只读派生, 不改变变更状态或门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目变更, 登记变更后可在此查看请求类型分布."]
       [:div {:style {:display "grid" :gap 12}}
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "变更总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已标注类型 " pct "%")]
         (when (pos? undeclared)
           [antd/tag {:color "orange"} (str "未设定 " undeclared)])]
        [:div
         [:span {:style {:fontWeight 500}} "按请求类型: "]
         [antd/space {:wrap true}
          (for [{:keys [type count]} (:by-type cov)]
            ^{:key type} [antd/tag {:color (if (pos? count) "geekblue" "default")}
                            (str (get type-label type type) " · " count)])]]])]))


(defn- change-impact-coverage-section
  "按每个变更最新有效版本只读聚合变更量化影响覆盖度: 工期/成本两维量化申报数, 至少量化一项与量化率, 仅文字描述数与达高影响阈值数; 只读派生, 不改变变更状态或门控."
  [{:keys [model]}]
  (let [cov (:change_impact_coverage model)
        total (:total cov 0)
        sched (:schedule-declared cov 0)
        cost (:cost-declared cov 0)
        pct (:quantified-pct cov 0)
        narrative-only (:narrative-only cov 0)
        high-impact (:high-impact cov 0)]
    [shared/panel "变更量化影响覆盖度" "按每个变更的最新有效版本统计工期/成本两维量化影响申报覆盖与达高影响阈值数; 只读派生, 不改变变更状态或门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目变更, 登记变更后可在此查看量化影响覆盖度."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "变更总数 " total)]
        [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
         (str "量化影响 " pct "%")]
        [antd/tag {:color "geekblue"} (str "工期影响已量化 " sched)]
        [antd/tag {:color "cyan"} (str "成本影响已量化 " cost)]
        (when (pos? narrative-only)
          [antd/tag {:color "orange"} (str "仅文字描述 " narrative-only)])
        (when (pos? high-impact)
          [antd/tag {:color "volcano"} (str "达高影响阈值 " high-impact)])])]))


(defn- change-impact-pattern-section
  "按每个变更最新有效版本只读聚合变更量化影响申报模式分布: 工期与成本两维量化影响的联合申报模式(两维皆量化/仅工期/仅成本/两维皆未量化), 两维齐全率与模式档; 只读派生, 不改变变更状态或门控."
  [{:keys [model]}]
  (let [pat (:change_impact_pattern model)
        total (:total pat 0)
        both (:both pat 0)
        schedule-only (:schedule-only pat 0)
        cost-only (:cost-only pat 0)
        neither (:neither pat 0)
        full-pct (:full-pct pat 0)
        pattern-level (:pattern-level pat)]
    [shared/panel "变更量化影响申报模式分布" "按每个变更的最新有效版本统计工期与成本两维量化影响的联合申报模式分布(是否两维齐全); 只读派生, 不改变变更状态或门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目变更, 登记变更后可在此查看量化影响申报模式分布."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "变更总数 " total)]
        [antd/tag {:color (cond (= full-pct 100) "green" (zero? full-pct) "red" :else "gold")}
         (str "两维齐全 " full-pct "%")]
        (when (some? pattern-level)
          [antd/tag {:color (case pattern-level "thorough" "green" "partial" "gold" "sparse" "red")}
           (str "量化模式 " (case pattern-level "thorough" "两维齐全为主" "partial" "部分量化" "sparse" "量化稀疏"))])
        [antd/tag {:color "green"} (str "两维皆量化 " both)]
        [antd/tag {:color "geekblue"} (str "仅工期 " schedule-only)]
        [antd/tag {:color "cyan"} (str "仅成本 " cost-only)]
        (when (pos? neither)
          [antd/tag {:color "orange"} (str "两维皆未量化 " neither)])])]))


(defn- change-impact-magnitude-section
  "按每个变更最新有效版本只读聚合变更量化影响数值分档分布: 工期与成本两维量化影响的取值各自穷举分档(工期未量化/零/轻微/中等/高影响, 成本未量化/轻微/中等/重大/高影响), 顶档与高影响阈值对齐; 只读派生, 不改变变更状态或门控."
  [{:keys [model]}]
  (let [mag (:change_impact_magnitude model)
        total (:total mag 0)
        s-unq (:sched-unquantified mag 0)
        s-zero (:sched-zero mag 0)
        s-minor (:sched-minor mag 0)
        s-mod (:sched-moderate mag 0)
        s-high (:sched-high mag 0)
        s-q (:sched-quantified mag 0)
        c-unq (:cost-unquantified mag 0)
        c-minor (:cost-minor mag 0)
        c-mod (:cost-moderate mag 0)
        c-major (:cost-major mag 0)
        c-high (:cost-high mag 0)
        c-q (:cost-quantified mag 0)]
    [shared/panel "变更量化影响数值分档分布" "按每个变更的最新有效版本对工期与成本两维量化影响数值各自做穷举分档(工期未量化/零/轻微/中等/高影响, 成本未量化/轻微/中等/重大/高影响), 顶档与高影响阈值对齐; 只读派生, 不改变变更状态或门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无项目变更, 登记变更后可在此查看量化影响数值分档分布."]
       [:div
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "变更总数 " total)]
         [:span {:style {:fontWeight 600 :marginRight 4}} "工期影响分档"]
         [antd/tag {:color "geekblue"} (str "已量化 " s-q)]
         [antd/tag {:color "default"} (str "未量化 " s-unq)]
         [antd/tag {:color "default"} (str "零影响 " s-zero)]
         [antd/tag {:color "green"} (str "轻微 1-4 天 " s-minor)]
         [antd/tag {:color "gold"} (str "中等 5-9 天 " s-mod)]
         [antd/tag {:color "volcano"} (str "高影响 10 天及以上 " s-high)]]
        [:div {:style {:height 8}}]
        [antd/space {:wrap true}
         [:span {:style {:fontWeight 600 :marginRight 4}} "成本影响分档"]
         [antd/tag {:color "cyan"} (str "已量化 " c-q)]
         [antd/tag {:color "default"} (str "未量化 " c-unq)]
         [antd/tag {:color "green"} (str "轻微 1 万以下 " c-minor)]
         [antd/tag {:color "gold"} (str "中等 1 至 5 万 " c-mod)]
         [antd/tag {:color "orange"} (str "重大 5 至 10 万 " c-major)]
         [antd/tag {:color "volcano"} (str "高影响 10 万及以上 " c-high)]]])]))


(defn- ccb-participation-section
  "只读汇总跨变更的变更控制委员会表决参与情况: 委员会覆盖的变更数, 席位与已投票数与参与率, 在途与停滞变更, 以及每位委员被邀请/已投/欠票负荷; 复用 ccb-tally 口径, 不改变任何表决进度或门控."
  [{:keys [model options]}]
  (let [sum (:ccb_participation model)
        user-label (fn [uid]
                     (let [u (first (filter #(= (str (:user_id %)) (str uid)) (:users options)))]
                       (or (:nick_name u) (:user_name u) (str uid))))
        committee-changes (:committee-changes sum 0)
        members (:members sum 0)
        seats (:seats sum 0)
        ballots (:ballots sum 0)
        pct (:participation-pct sum 0)
        open (:open-changes sum 0)
        stalled (:stalled-changes sum 0)
        by-member (:by-member sum [])]
    [shared/panel "委员会表决参与概览" "汇总已设立变更控制委员会的变更的表决参与度与每位委员的欠票负荷; 只读派生, 不改变任何表决进度或门控" nil
     (if (zero? committee-changes)
       [:span {:style {:color "#8793a3"}} "暂无已设立委员会的变更, 为审批中的变更登记委员会并表决后可在此查看参与度."]
       [:<>
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "委员会变更 " committee-changes)]
         [antd/tag {:color "geekblue"} (str "委员 " members " 人 / " seats " 席")]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "参与率 " pct "% (" ballots "/" seats " 票)")]
         [antd/tag {:color "orange"} (str "在途 " open)]
         (when (pos? stalled) [antd/tag {:color "red"} (str "停滞未决 " stalled)])]
        (when (seq by-member)
          [:div {:style {:marginTop 12}}
           [:div {:style {:color "#8793a3" :fontSize 12 :marginBottom 6}} "委员欠票负荷 (按欠票数排序)"]
           [antd/space {:wrap true}
            (for [m by-member]
              (let [mid (:member-id m)
                    inv (or (:invited m) 0)
                    cast (or (:cast m) 0)
                    pend (or (:pending m) 0)]
                ^{:key (str mid)}
                [antd/tag {:color (cond (pos? pend) "red" (= cast inv) "green" :else "default")}
                 (str (user-label mid) " · 已投 " cast "/" inv
                      (when (pos? pend) (str " · 欠 " pend)))]))]])])]))


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
     (when (and editable? (contains? #{"draft" "ready" "rejected"} (:status gate)) (pos? (or (:gate_required_missing gate) 0)))
       [w/edit-button "落实整改" #(open! (forms/gate-remediation-dialog base options gate))])
     (when (and editable? (contains? #{"draft" "ready" "rejected"} (:status gate)) (< 1 (or (:gate_required_missing gate) 0)))
       [w/edit-button "全部落实" #(open! (forms/gate-remediation-actions-dialog base options gate))])
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

(defn- gate-closure-summary-section
  "只读汇总全部关口实例的签核闭环健康度: 按状态计数与已签核占比, 另汇总被必需检查阻断, 证据待发布与证据已作废的实例数; 与 Gate 模板层进展汇总互补, 只读派生, 不改变任何关口状态."
  [{:keys [model]}]
  (let [sm (:gate_closure model)
        total (:total sm 0)
        approved (:approved sm 0)
        waived (:waived sm 0)
        in-review (:in-review sm 0)
        ready (:ready sm 0)
        draft (:draft sm 0)
        rejected (:rejected sm 0)
        signed (:signed sm 0)
        blocked (:blocked sm 0)
        pending (:evidence-pending sm 0)
        voided (:evidence-voided sm 0)
        remediated (:remediated sm 0)
        remediation-open (:remediation-open sm 0)
        pct (:closure-pct sm 0)]
    [shared/panel "关口验收签核闭环汇总" "统一统计全部关口实例的签核闭环 (已通过或已豁免视为已签核) 与占比; 另汇总被必需检查阻断, 证据待发布, 证据已作废的实例数, 以及未通过必需检查落实整改的已落实与未完成数; 只读派生, 不改变任何关口状态"
     nil
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "尚无关口实例, 发起 Gate 检查并逐项签核后可在此查看验收闭环概览."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "关口总数 " total)]
        [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
         (str "已签核 " pct "% (" signed "/" total " · 批准 " approved " 豁免 " waived ")")]
        (when (pos? in-review)
          [antd/tag {:color "orange"} (str "签核评审中 " in-review)])
        (when (pos? ready)
          [antd/tag {:color "gold"} (str "待提交 " ready)])
        (when (pos? draft)
          [antd/tag {:color "default"} (str "草稿 " draft)])
        (when (pos? rejected)
          [antd/tag {:color "red"} (str "已驳回 " rejected)])
        (when (pos? blocked)
          [antd/tag {:color "volcano"} (str "被必需检查阻断 " blocked)])
        (when (pos? pending)
          [antd/tag {:color "purple"} (str "证据待发布 " pending)])
        (when (pos? voided)
          [antd/tag {:color "red"} (str "证据已作废 " voided)])
        (when (pos? remediated)
          [antd/tag {:color "gold"} (str "已落实整改 " remediated)])
        (when (pos? remediation-open)
          [antd/tag {:color "orange"} (str "整改未完成 " remediation-open)])])]))

(defn- gate-exception-summary-section
  "只读汇总全部关口实例的检查项级例外放行治理健康度: 统计必需检查项总数, 其中靠豁免而非真实通过的数量与占比, 含豁免的关口数, 以及仅靠豁免才达到就绪的关口数; 与关口验收签核闭环汇总互补 (后者按实例状态计数, 本项按逐检查项豁免计数), 只读派生, 不改变任何关口状态或检查项."
  [{:keys [model]}]
  (let [sm (:gate_exception_summary model)
        total (:total sm 0)
        required (:required-checks sm 0)
        passed (:passed-checks sm 0)
        exception (:exception-checks sm 0)
        gates-with (:gates-with-exception sm 0)
        gates-dep (:gates-exception-dependent sm 0)
        reason-missing (:reason-missing sm 0)
        pct (:waiver-pct sm 0)]
    [shared/panel "关口检查项例外放行治理" "统一统计全部关口的必需检查项中被逐条豁免放行 (而非真实通过) 的数量与占比, 含豁免的关口数, 仅靠豁免才就绪的关口数与缺失豁免理由数; 回答\"关口通过有多少是靠例外撑起来的\"; 只读派生, 不改变任何关口状态或检查项"
     nil
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "尚无关口实例, 发起 Gate 检查并登记检查项豁免后可在此查看例外放行治理概览."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "关口总数 " total)]
        [antd/tag {:color "geekblue"} (str "必需检查项 " required)]
        [antd/tag {:color (cond (zero? exception) "green" (zero? required) "default" (= pct 100) "red" :else "gold")}
         (str "例外放行 " pct "% (" exception "/" required " · 真实通过 " passed ")")]
        (when (pos? gates-with)
          [antd/tag {:color "orange"} (str "含豁免关口 " gates-with)])
        (when (pos? gates-dep)
          [antd/tag {:color "volcano"} (str "仅靠豁免才就绪 " gates-dep)])
        (when (pos? reason-missing)
          [antd/tag {:color "red"} (str "缺失豁免理由 " reason-missing)])])]))

(defn- gate-velocity-summary-section
  "只读按签核流转阶段为全部关口实例分桶, 回答\"尚未签核的关口此刻卡在哪一步, 哪些可以立刻推进\": 已签核, 待独立裁决, 就绪待提交, 待满足检查, 需返工五桶互斥且并集为全部实例, 另给可推进积压数, 已签核占比, 各阶段推进度与可立即提交评审的关口点名; 只读派生, 不改变任何关口状态, 不构成门控."
  [{:keys [model]}]
  (let [sm (:gate_velocity model)
        total (:total sm 0)
        signed (:signed sm 0)
        awaiting (:awaiting-decision sm 0)
        ready (:ready-to-submit sm 0)
        needs (:needs-checks sm 0)
        rework (:rework sm 0)
        backlog (:actionable-backlog sm 0)
        pct (:closure-pct sm 0)
        by-stage (:by-stage sm {})
        ready-list (:ready-to-submit-list sm [])]
    [shared/panel "关口签核流转待办" "把每个关口实例按签核流转阶段唯一分桶 (已签核/待裁决/就绪待提交/待满足检查/需返工), 五桶互斥且并集恰为全部实例, 回答\"尚未签核的关口此刻卡在哪一步, 哪些可以立刻推进\"; 另给可推进积压数, 已签核占比, 各阶段推进度与可立即提交评审的关口点名; 只读派生, 不改变任何关口状态, 不构成门控"
     nil
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "尚无关口实例, 发起 Gate 检查并绑定独立评审人后可在此查看签核流转待办概览."]
       [:div
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "关口总数 " total)]
         [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
          (str "已签核 " pct "% (" signed "/" total ")")]
         (when (pos? awaiting)
           [antd/tag {:color "orange"} (str "待独立裁决 " awaiting)])
         (when (pos? ready)
           [antd/tag {:color "cyan"} (str "就绪待提交 " ready)])
         (when (pos? needs)
           [antd/tag {:color "default"} (str "待满足检查 " needs)])
         (when (pos? rework)
           [antd/tag {:color "red"} (str "需返工 " rework)])
         (when (pos? backlog)
           [antd/tag {:color "volcano"} (str "可推进积压 " backlog)])]
        (when (seq by-stage)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "各阶段签核推进度: "]
           (into [antd/space {:wrap true}]
                 (map (fn [[k v]] (let [ks (if (keyword? k) (name k) (str k))]
                        ^{:key (str "st-" ks)}
                        [antd/tag {:color (if (zero? (:pending v 0)) "green" "geekblue")}
                         (str (get gate-stage-labels ks ks) " 已签 " (:signed v 0) "/" (:total v 0))]))
                      (seq by-stage)))])
        (if (seq ready-list)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "可立即提交评审: "]
           (into [antd/space {:wrap true}]
                 (map (fn [g] ^{:key (:gate-id g)}
                        [antd/tag {:color "cyan"}
                         (str (:title g) (when-let [s (:stage g)] (str " · " (get gate-stage-labels s s)))
                              " · 检查 " (:checks-passed g 0) "/" (:checks-total g 0))])
                      ready-list))]
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "暂无可立即提交评审的关口 (需先满足全部必需检查项)."]])])]))


(defn- gate-section
  "Gate模板和逐项证据检查控制阶段准入."
  [{:keys [base model options editable? open!] :as context}]
  [:div {:style {:display "grid" :gap 20}}
   [gate-progress-section context]
   [gate-closure-summary-section context]
   [gate-exception-summary-section context]
   [gate-velocity-summary-section context]
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
                                    {:title "检查就绪度" :key "readiness" :width 200
                                     :render (fn [_ row]
                                               (let [total (aget row "gate_total")
                                                     passed (aget row "gate_passed")
                                                     waived (aget row "gate_waived")
                                                     blocking (array-seq (or (aget row "blocking_checks") #js []))
                                                     ready (true? (aget row "ready_to_sign"))
                                                     voided (true? (aget row "gate_evidence_voided"))
                                                     unreleased (true? (aget row "gate_evidence_unreleased"))]
                                                 (r/as-element
                                                  (into [antd/space {:wrap true}]
                                                        (cons [antd/tag {:color (if ready "green" "red")} (str "检查 " passed "/" total)]
                                                              (cond-> []
                                                                (pos? waived) (conj [antd/tag {:color "blue"} (str "豁免 " waived)])
                                                                (seq blocking) (conj [antd/tag {:color "orange"} (str "待满足 " (str/join ", " blocking))])
                                                                ready (conj [antd/tag {:color "green"} "可签核"])
                                                                voided (conj [antd/tag {:color "red"} (str "证据已作废 " (aget row "gate_voided_checks"))])
                                                                unreleased (conj [antd/tag {:color "gold"} (str "证据待发布 " (aget row "gate_evidence_pending"))])))))))}
                                    {:title "整改情况" :key "gate_remediation" :width 160
                                     :render (fn [_ row]
                                               (let [state (aget row "gate_remediation_state")
                                                     total (aget row "gate_remediation_total")
                                                     open (aget row "gate_remediation_open")
                                                     overdue (aget row "gate_remediation_overdue")
                                                     missing (aget row "gate_required_missing")]
                                                 (r/as-element
                                                  [:span
                                                   (cond
                                                     (= state "completed") [antd/tag {:color "green"} (str "整改完成 " total "/" total)]
                                                     (= state "in-progress") [antd/tag {:color "gold"} (str "整改中 " (- total open) "/" total)]
                                                     :else (if (pos? (or missing 0))
                                                             [antd/tag {:color "red"} "待落实整改"]
                                                             [antd/tag {:color "default"} "无需整改"]))
                                                   (when (and (pos? (or overdue 0)) (not= state "completed"))
                                                     [antd/tag {:color "red"} (str "逾期 " overdue)])])))}
                                    (w/state-column)
                                    (w/text-column :reviewer_id "审批人") (w/text-column :decision_reason "评审意见")]
     #(gate-actions context %)]]])


(defn- dq-actions
  [{:keys [base options editable? approve? open!]} dq]
  (let [current (:currentUserId options)]
    [antd/space {:wrap true}
     (when (and editable? (contains? #{"draft" "ready" "rejected"} (:status dq)))
       [w/edit-button "填写检查" #(open! (forms/dq-check-dialog base dq))])
     (when (and editable? (contains? #{"draft" "rejected"} (:status dq)) (pos? (or (:dq_required_missing dq) 0)))
       [w/edit-button "落实整改" #(open! (forms/dq-remediation-dialog base options dq))])
     (when (and editable? (contains? #{"draft" "rejected"} (:status dq)) (< 1 (or (:dq_required_missing dq) 0)))
       [w/edit-button "全部落实" #(open! (forms/dq-remediation-actions-dialog base options dq))])
     (when (and editable? (contains? #{"ready" "rejected"} (:status dq)))
       [w/edit-button "提交签认" #(open! {:title "提交DQ签认" :path (str base "/dqs/" (:id dq) "/submit") :fields [(forms/reviewer-field options)]})])
     (when (and approve? (= "in_review" (:status dq)) (= current (:reviewer_id dq)) (not= current (:submitted_by dq)))
       [:<>
        [w/edit-button "签认" #(open! (forms/decision-dialog (str base "/dqs/" (:id dq) "/decision") "approved" "签认DQ"))]
        [w/edit-button "退回" #(open! (forms/decision-dialog (str base "/dqs/" (:id dq) "/decision") "rejected" "退回DQ"))]])]))

(defn- dq-summary-section
  "按全部 DQ 关键任务的最新状态只读聚合项目级质量检查闭环概览: 已签认占全部分母的整数百分比, 交付件失效/作废数; 只读派生, 不改变任何 DQ 状态, 不构成门控."
  [{:keys [model]}]
  (let [sm (:dq_summary model)
        total (:total sm 0)
        approved (:approved sm 0)
        in-review (:in-review sm 0)
        ready (:ready sm 0)
        draft (:draft sm 0)
        rejected (:rejected sm 0)
        required-met (:required-met sm 0)
        exception-met (:exception-met sm 0)
        methods-declared (:methods-declared sm 0)
        roles-declared (:roles-declared sm 0)
        remediated (:remediated sm 0)
        remediation-open (:remediation-open sm 0)
        stale (:stale sm 0)
        voided (:voided sm 0)
        pct (:closure-pct sm 0)]
    [shared/panel "DQ 质量检查闭环汇总" "统一统计全部 DQ 关键任务最新状态的签认闭环情况(已签认/审批中/待提交/草稿/已退回)与必需检查项达成数; 另汇总签认依据交付件失效与交付件作废数量, 以及未通过必需检查项落实整改的已落实与未完成数; 只读派生, 不改变 DQ 状态"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无 DQ 关键任务, 建立 DQ 并逐项检查签认后可在此查看质量闭环概览."]
       [antd/space {:wrap true}
        [antd/tag {:color "blue"} (str "DQ 总数 " total)]
        [antd/tag {:color (cond (= pct 100) "green" (zero? pct) "red" :else "gold")}
         (str "已签认 " pct "% (" approved "/" total ")")]
        (when (pos? required-met)
          [antd/tag {:color "green"} (str "必需项全满足 " required-met)])
        (when (pos? exception-met)
          [antd/tag {:color "gold"} (str "靠例外满足 " exception-met)])
        (when (pos? methods-declared)
          [antd/tag {:color "blue"} (str "检验方法已声明 " methods-declared)])
        (when (pos? roles-declared)
          [antd/tag {:color "cyan"} (str "执行角色已指定 " roles-declared)])
        (when (pos? remediated)
          [antd/tag {:color "gold"} (str "已落实整改 " remediated)])
        (when (pos? remediation-open)
          [antd/tag {:color "orange"} (str "整改未完成 " remediation-open)])
        (when (pos? in-review)
          [antd/tag {:color "orange"} (str "签认审批中 " in-review)])
        (when (pos? ready)
          [antd/tag {:color "gold"} (str "待提交 " ready)])
        (when (pos? draft)
          [antd/tag {:color "default"} (str "草稿 " draft)])
        (when (pos? rejected)
          [antd/tag {:color "red"} (str "已退回 " rejected)])
        (when (pos? stale)
          [antd/tag {:color "volcano"} (str "交付件失效 " stale)])
        (when (pos? voided)
          [antd/tag {:color "red"} (str "交付件作废 " voided)])])]))

(defn- dq-section
  "B08 DQ 编制与确认关键任务: 检查清单 + 确定版本交付件 + 独立签认, 交付件更新即标注失效."
  [{:keys [base model options planning editable? open!] :as context}]
  [shared/panel "DQ 编制与确认" "管理检查清单与确定版本交付件, 满足条件并签认才完成; 交付件出现新版本时签认依据标注失效"
   (when editable? [antd/button {:type "primary" :on-click #(open! (forms/dq-dialog base options (:documents model) planning))} "建立DQ关键任务"])
   [w/record-table (:dqs model)
    [(w/text-column :code "编号") (w/text-column :title "DQ任务")
     {:title "检验方法" :dataIndex "check_method" :width 100
      :render (fn [_ row]
                (let [m (aget row "check_method")
                      label (get {"inspection" "检验" "measurement" "测量"
                                  "test" "试验" "documentation-review" "文件评审"} m)
                      color (get {"inspection" "blue" "measurement" "cyan"
                                  "test" "purple" "documentation-review" "geekblue"} m "default")]
                  (r/as-element (if (nil? m)
                                  [:span {:style {:color "#98a2b3"}} "未设定"]
                                  [antd/tag {:color color} label]))))}
     {:title "执行角色" :dataIndex "responsible_role" :width 120
      :render (fn [_ row]
                (let [role (aget row "responsible_role")]
                  (if (str/blank? role)
                    (r/as-element [:span {:style {:color "#98a2b3"}} "未设定"])
                    (or role ""))))}
     {:title "检查通过" :key "checks" :width 100 :render (fn [_ row] (str (aget row "dq_passed") "/" (aget row "dq_total")))}
     {:title "必需检查就绪度" :key "dq_required" :width 180
      :render (fn [_ row]
                (let [rt (aget row "dq_required_total")
                      rs (aget row "dq_required_satisfied")
                      rw (aget row "dq_required_waived")
                      missing (aget row "dq_required_missing")
                      met (true? (aget row "dq_required_met"))]
                  (r/as-element
                   [:span
                    (if (zero? rt)
                      [antd/tag {:color "default"} "无必需项"]
                      (if met
                        [antd/tag {:color "green"} (str "必需就绪 " rs "/" rt)]
                        [antd/tag {:color "red"} (str "必需 " rs "/" rt " 缺 " missing)]))
                    (when (pos? (or rw 0)) [antd/tag {:color "gold"} (str "例外 " rw)])])))}
     {:title "整改情况" :key "dq_remediation" :width 160
      :render (fn [_ row]
                (let [state (aget row "dq_remediation_state")
                      total (aget row "dq_remediation_total")
                      open (aget row "dq_remediation_open")
                      overdue (aget row "dq_remediation_overdue")
                      missing (aget row "dq_required_missing")]
                  (r/as-element
                   [:span
                    (cond
                      (= state "completed") [antd/tag {:color "green"} (str "整改完成 " total "/" total)]
                      (= state "in-progress") [antd/tag {:color "gold"} (str "整改中 " (- total open) "/" total)]
                      :else (if (pos? (or missing 0))
                              [antd/tag {:color "red"} "待落实整改"]
                              [antd/tag {:color "default"} "无需整改"]))
                    (when (and (pos? (or overdue 0)) (not= state "completed"))
                      [antd/tag {:color "red"} (str "逾期 " overdue)])])))}
     {:title "交付件" :dataIndex "deliverable_ids" :width 220
      :render (fn [v] (str/join ", " (map #(w/related-label (:documents model) :id :code %) (js->clj v))))}
     {:title "版本失效" :dataIndex "dq_stale" :width 110
      :render (fn [v] (r/as-element (if (true? v) [antd/tag {:color "red"} "交付件已更新"] [antd/tag {:color "green"} "版本有效"])))}
     {:title "交付件作废" :key "dq_voided" :width 120
      :render (fn [_ row]
                (let [voided (true? (aget row "dq_deliverable_voided"))]
                  (r/as-element (if voided [antd/tag {:color "red"} (str "已作废 " (aget row "dq_voided_deliverables"))] [antd/tag {:color "green"} "未作废"]))))}
     (w/state-column) (w/text-column :decision_reason "签认意见")]
    #(dq-actions context %)]])

(defn- node-pause-label
  [k fallback]
  (get {"sub" "子项目" "machine" "单机" "main" "主项目"
       "draft" "起草" "approved" "已批准" "planning" "计划中" "executing" "执行中"
       "closing" "收尾" "closed" "已关闭" "paused" "已暂停" "cancelled" "已取消"}
    (if (string? k) k (name k)) fallback))

(defn- node-pause-summary-section
  "B02/B16/B12 结构节点暂停与复工概览: 只读聚合 node-pause 台账的暂停中/已复工节点数, 中断时项目状态分布, 最长暂停与平均复工时长, 并点名仍暂停的节点; 只读派生, 不改变暂停或复工状态, 不构成门控."
  [{:keys [model]}]
  (let [cov (:node_pause_summary model)
        total (:total cov 0)
        active (:active cov 0)
        resumed (:resumed cov 0)
        active-nodes (:active-nodes cov 0)
        longest (:longest-active-days cov 0)
        avg-resume (:avg-resume-days cov 0)
        by-type (:by-node-type cov {})
        by-status (:by-project-status cov {})
        active-list (:active-pauses cov [])]
    [shared/panel "结构节点暂停与复工概览" "按 node-pause 台账最新记录统计仍暂停与已复工的节点数, 中断时项目状态分布, 最长暂停与平均复工时长(整天粒度), 并点名当前仍暂停的节点; 只读派生, 不改变暂停或复工状态, 不构成门控"
     (if (zero? total)
       [:span {:style {:color "#8793a3"}} "暂无暂停记录, 在下方对单机或子项目发起局部暂停后可在此查看复工概览."]
       [:div
        [antd/space {:wrap true}
         [antd/tag {:color "blue"} (str "暂停记录总数 " total)]
         [antd/tag {:color (if (zero? active) "green" "red")} (str "暂停中 " active " 条 / " active-nodes " 节点")]
         [antd/tag {:color "default"} (str "已复工 " resumed)]
         [antd/tag {:color (if (>= longest 14) "volcano" "gold")} (str "最长暂停 " longest " 天")]
         (when (pos? avg-resume)
           [antd/tag {:color "cyan"} (str "平均复工 " avg-resume " 天")])]
        (when (seq by-type)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "暂停中按节点类型: "]
           (into [antd/space {:wrap true}]
                 (map (fn [[k n]] ^{:key (str "t-" k)} [antd/tag {:color "blue"} (str (node-pause-label k k) " " n)]) (seq by-type)))])
        (when (seq by-status)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "暂停中按中断时项目状态: "]
           (into [antd/space {:wrap true}]
                 (map (fn [[k n]] ^{:key (str "s-" k)} [antd/tag {:color "purple"} (str (node-pause-label k k) " " n)]) (seq by-status)))])
        (when (seq active-list)
          [:div {:style {:margin-top 8}}
           [:span {:style {:color "#8793a3"}} "仍暂停节点: "]
           (into [antd/space {:wrap true}]
                 (map (fn [p] ^{:key (:node-code p)}
                        [antd/tag {:color "red"}
                         (str (:node-code p) " " (:node-name p)
                              (when-let [t (:node-type p)] (str " · " (node-pause-label t t)))
                              " · 已停 " (:paused-days p 0) " 天")])
                      active-list))])])]))

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
                     ["requirements" "URS与追踪" [requirement-section coverage-section alignment-section traceability-section trace-section]]
                     ["evidence" "证据版本" [document-section collection-section tree-section release-coverage-section]]
                     ["appointments" "成员任命" [appointment-section]]
                     ["stakeholders" "干系人与沟通" [stakeholder-section engagement-coverage-section engagement-matrix-section raci-section raci-assignment-section raci-engagement-section comm-plan-section comm-cadence-section comm-audience-section comm-execution-section]]
                     ["gates" "Gate评审" [gate-section]]
                     ["risks" "风险与问题" [risk-section risk-coverage-section risk-category-coverage-section risk-escalation-section risk-score-distribution-section risk-stage-distribution-section risk-review-cadence-section risk-template-section issue-section issue-escalation-section issue-closure-summary-section resolution-coverage-section]]
                     ["meetings" "会议行动" [meeting-section meeting-release-coverage-section meeting-material-readiness-section meeting-attendance-section meeting-cadence-section action-closure-section project-remediation-overview-section due-workload-overview-section owner-due-pressure-section action-section]]
                     ["changes" "变更控制" [change-section change-closure-section change-type-coverage-section change-impact-coverage-section change-impact-pattern-section change-impact-magnitude-section ccb-participation-section]]
                     ["quality" "DQ与局部暂停" [dq-summary-section dq-section node-pause-summary-section pause-section]]])}])


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
