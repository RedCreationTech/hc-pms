(ns com.ruoyi.domain.pms.governance.evidence
  "需求与真实文本附件的不可变版本,CSV预检和双向证据关联."
  (:require
    [clojure.data.csv :as csv]
    [clojure.string :as str]
    [com.ruoyi.domain.pms.governance.files :as files]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.math
      BigInteger)
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)))


(def requirement-fields
  "需求条目的明确字段 (CSV 批量导入表头, 保持五列不变)."
  [:code :text :category :priority :owner_id])


(def requirement-input-fields
  "需求创建/修订请求体白名单: 在 CSV 五列基础上追加可选验证方式, 不影响批量导入."
  (conj requirement-fields :verification_method))


(def requirement-verification-methods
  "需求验证方式取值 (ISO/IEC/IEEE 29148 四类: 测试/检验/演示/分析), 仅用于验收规划, 不替代追踪与关闭证据."
  #{"test" "inspection" "demonstration" "analysis"})


(defn requirement!
  "校验需求编号,内容,分类,必要性和责任人; 验证方式为可选枚举, 缺省或空值不写键."
  [q project body]
  (s/input! body requirement-input-fields)
  (let [vm (:verification_method body)]
    (cond-> {:code (s/text! body :code 100) :text (s/text! body :text 10000)
             :category (s/text! body :category 100)
             :priority (s/enum! (:priority body) #{"required" "desired"} "priority")
             :owner_id (k/user! q project (:owner_id body) "需求负责人")}
      (seq vm)
      (assoc :verification_method (s/enum! vm requirement-verification-methods "验证方式")))))


(def document-classifications
  "文档密级取值, 仅用于归集与追踪, 不替代项目授权."
  #{"public" "internal" "confidential"})


(defn- document-meta!
  "文本与文件两类证据共用的归集字段: 编号/标题/密级/阶段/结构节点/文档类别 (类别可选, 用于按模板文档类别归集)."
  [body]
  {:code (s/text! body :code 100) :title (s/text! body :title 200)
   :classification (if (contains? body :classification)
                     (s/enum! (:classification body) document-classifications "classification")
                     "internal")
   :stage (s/optional-text! body :stage 100)
   :structure_node (s/optional-text! body :structure_node 100)
   :category (s/optional-text! body :category 50)})


(defn document!
  "校验真实文本附件并由服务器计算字节数和SHA256; 密级/阶段/结构节点/类别用于归集追踪, 密级缺省为内部."
  [_ _ body]
  (s/input! body [:code :title :filename :content :classification :stage :structure_node :category])
  (let [filename (s/text! body :filename 150)
        content (:content body)
        _ (when-not (and (string? content) (not (str/blank? content)))
            (r/fail! 400 "content必须为非空文本"))
        bytes (.getBytes ^String content StandardCharsets/UTF_8)]
    (when (or (re-find #"[/\\\r\n]" filename) (> (alength bytes) 1048576))
      (r/fail! 400 "文件名非法或文本附件超过1MiB"))
    (merge (document-meta! body)
           {:filename filename :content content :byte_size (alength bytes) :content_kind "text"
            :content_type "text/plain; charset=utf-8"
            :sha256 (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes)))})))


(defn file-document!
  "校验二进制证据: 表单字段白名单 + 文件名/类型白名单 + 大小上限, 文件按内容寻址落盘, 记录只保存元数据与摘要 (正文不进 JSON 载荷)."
  [svc project body file]
  (s/input! body [:code :title :classification :stage :structure_node :category])
  (let [filename (files/filename! (:filename file))
        stored (files/store! svc (:project_id project) filename (:tempfile file))]
    (merge (document-meta! body)
           (dissoc stored :preview)
           {:filename filename :content_kind "file" :preview (:preview stored)})))


(defn create!
  "创建经过明确字段校验的需求或文档首版."
  [svc actor id kind body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".created")
             (fn [q project]
               (s/insert! q project actor kind ((if (= kind "requirement") requirement! document!) q project body)
                          {:status "registered"}))))


(defn revise!
  "新增不可变修订,保持原编号与已引用版本不变."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".revised")
             (fn [q project]
               (let [old (s/latest! q project (s/record! q project kind rid))
                     fields ((if (= kind "requirement") requirement! document!) q project body)]
                 (when-not (= (:code old) (:code fields)) (r/fail! 400 "修订不得改变业务编号"))
                 (s/insert! q project actor kind (assoc fields :previous_id rid)
                            {:revision (inc (:revision old)) :status "registered"})))))


(defn upload!
  "以真实上传文件登记证据文档首版 (multipart); 与文本证据共用编号/版本/发布/归集口径."
  [svc actor id body file]
  (k/mutate! svc actor id "pms:project:edit" body "document.created"
             (fn [q project]
               (s/insert! q project actor "document" (file-document! svc project body file) {:status "registered"}))))


(defn upload-revision!
  "以真实上传文件新增不可变修订, 保持原编号; 旧版本的物理文件与摘要不变."
  [svc actor id rid body file]
  (k/mutate! svc actor id "pms:project:edit" body "document.revised"
             (fn [q project]
               (let [old (s/latest! q project (s/record! q project "document" rid))
                     fields (file-document! svc project body file)]
                 (when-not (= (:code old) (:code fields)) (r/fail! 400 "修订不得改变业务编号"))
                 (s/insert! q project actor "document" (assoc fields :previous_id rid)
                            {:revision (inc (:revision old)) :status "registered"})))))


(defn file-bytes
  "读取二进制证据的完整字节并复核 SHA256 (仅 content_kind=file); 文本证据直接取 UTF-8 字节."
  ^bytes [svc document]
  (if (= "file" (:content_kind document))
    (files/verified-bytes svc document)
    (.getBytes ^String (or (:content document) "") StandardCharsets/UTF_8)))


(defn classified!
  "C05 密级过滤: 机密文档的正文预览/下载/打包须具备 pms:document:confidential 权限, 逐次访问逐文件检查."
  [actor document]
  (when (= "confidential" (:classification document))
    (when-not (or (:admin? actor) (contains? (:permissions actor) "*:*:*")
                  (contains? (:permissions actor) "pms:document:confidential"))
      (r/fail! 403 (str "无机密文档访问权限: " (:code document)))))
  document)


(defn content
  "读取已授权项目中的确切文件版本内容, 机密文档须具备密级权限."
  [svc actor id rid]
  (k/read! svc actor id "pms:project:query"
           (fn [q project] (classified! actor (s/record! q project "document" rid)))))


(defn batch-content
  "读取同一项目内多个确定文档版本的正文, 供打包批量下载; 任一引用非法则整体失败, 不泄露跨项目对象."
  [svc actor id body]
  (r/object! body [:record_ids])
  (k/read! svc actor id "pms:project:query"
           (fn [q project]
             (let [ids (:record_ids body)]
               (when-not (and (vector? ids) (<= 1 (count ids) 50) (= (count ids) (count (set ids))))
                 (r/fail! 400 "批量下载须为1到50个不重复的文档版本ID"))
               {:documents (mapv #(select-keys (classified! actor (s/record! q project "document" %))
                                               [:id :code :revision :filename :content_type :content :content_kind :storage_key
                                                :sha256 :byte_size :classification :stage :structure_node :category])
                                 ids)}))))


(defn submit-release!
  "冻结当前文档版本并选择具有质量审批权限的独立发布审核人."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "document.submitted"
             (fn [q project]
               (s/input! body [:reviewer_id])
               (let [record (s/latest! q project (s/record! q project "document" rid))
                     reviewer (s/reviewer! q project actor (:reviewer_id body))]
                 (s/status! record #{"registered" "rejected"})
                 (s/change! q project record "in_review"
                            {:reviewer_id reviewer :submitted_by (:user_id actor)})))))


(defn decide-release!
  "指定独立审核人正式签发(批准发布)或退回当前文档版本; 旧批准版本不随新修订漂移."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "document.released" {:write? false}
             (fn [q project]
               (s/input! body [:decision :reason])
               (let [record (s/latest! q project (s/record! q project "document" rid))
                     decision (s/enum! (:decision body) #{"approved" "rejected"} "decision")]
                 (s/status! record #{"in_review"})
                 (s/decision-actor! actor record)
                 (s/change! q project record decision
                            {:decision_reason (s/text! body :reason)
                             :released_by (when (= "approved" decision) (:user_id actor))
                             :released_at (when (= "approved" decision) (str (java.time.Instant/now)))
                             :release_sha256 (when (= "approved" decision) (:sha256 record))
                             :decided_by (:user_id actor)})))))


(defn- csv-rows!
  "解析限定大小的CSV并校验列名和列宽."
  [text]
  (let [text (r/text! text "csv" 1048576 true)
        _ (when (> (alength (.getBytes ^String text StandardCharsets/UTF_8)) 1048576)
            (r/fail! 400 "CSV超过1MiB"))
        rows (try (doall (csv/read-csv text))
                  (catch Exception _ (r/fail! 400 "CSV格式不合法")))
        header (mapv keyword (first rows))]
    (when-not (= header requirement-fields)
      (r/fail! 400 "CSV表头必须为code,text,category,priority,owner_id"))
    (when-not (<= 1 (count (rest rows)) 500) (r/fail! 400 "CSV必须包含1到500条需求"))
    (mapv (fn [line cells]
            {:line line :cells cells :body (when (= 5 (count cells)) (zipmap requirement-fields cells))})
          (range 2 (+ 2 (count (rest rows)))) (rest rows))))


(defn preflight
  "逐行预检并返回全部错误,不产生任何业务写入."
  [q project text]
  (let [seen (atom (set (map :code (s/records q project "requirement"))))
        checked (mapv (fn [{:keys [line body]}]
                        (try
                          (when-not body (r/fail! 400 "列数必须为5"))
                          (let [row (requirement! q project body)]
                            (when (@seen (:code row)) (r/fail! 409 "需求编号已存在或在文件内重复"))
                            (swap! seen conj (:code row))
                            {:line line :row row})
                          (catch clojure.lang.ExceptionInfo e
                            (if (:pms-error (ex-data e))
                              {:line line :error (.getMessage e)} (throw e))))) (csv-rows! text))
        errors (filterv :error checked)]
    {:valid? (empty? errors) :count (count checked) :errors errors
     :rows (mapv :row (remove :error checked))}))


(defn preview
  "对当前项目预检CSV,请求不需要项目写版本."
  [svc actor id body]
  (r/object! body [:csv])
  (k/read! svc actor id "pms:project:query"
           (fn [q project] (preflight q project (:csv body)))))


(defn import!
  "预检通过后整批导入需求,任一错误导致全部不写入."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "requirement.imported"
             (fn [q project]
               (s/input! body [:csv])
               (let [checked (preflight q project (:csv body))]
                 (when-not (:valid? checked)
                   (r/fail! 400 (str "CSV预检失败: " (pr-str (:errors checked)))))
                 {:rows (mapv #(s/insert! q project actor "requirement" % {:status "registered"}) (:rows checked))
                  :count (:count checked)}))))


(def trace-phases
  "追踪关系所属阶段: 设计/需求确认/SIT/FAT/SAT."
  #{"design" "requirement-confirm" "SIT" "FAT" "SAT"})


(def deviation-levels
  "偏差分级: 无/一般/严重/阻塞."
  #{"none" "minor" "major" "blocker"})


(defn trace!
  "将确定需求版本关联到同项目文档版本或真实WBS任务, 可选标注阶段与偏差分级 (C03 偏差校准)."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "trace.created"
             (fn [q project]
               (s/input! body [:requirement_id :target_kind :target_id :relation :phase :deviation_level :deviation_note])
               (let [req (s/record! q project "requirement" (:requirement_id body))
                     kind (s/enum! (:target_kind body) #{"document" "task"} "target_kind")
                     target (s/text! body :target_id 36)
                     relation (s/enum! (:relation body) #{"satisfies" "verifies"} "relation")
                     phase (when (seq (:phase body)) (s/enum! (:phase body) trace-phases "phase"))
                     deviation (when (seq (:deviation_level body)) (s/enum! (:deviation_level body) deviation-levels "deviation_level"))]
                 (if (= kind "document") (s/record! q project "document" target)
                     (when-not (q :planning/task {:project_id (:project_id project) :task_id target})
                       (r/fail! 404 "任务不存在或不属于本项目")))
                 (when (and deviation (not= "none" deviation) (empty? (:deviation_note body)))
                   (r/fail! 400 "登记偏差必须填写偏差说明"))
                 (s/insert! q project actor "trace"
                            (cond-> {:code (str (:id req) ":" kind ":" target ":" relation)
                                     :requirement_id (:id req) :target_kind kind :target_id target :relation relation}
                              phase (assoc :phase phase)
                              deviation (assoc :deviation_level deviation :deviation_note (s/optional-text! body :deviation_note 1000)))
                            {:status "registered"})))))


(defn traceability-report
  "按需求最新版本计算追踪链缺项: 只读聚合, 不引入覆盖率分母规则. 修订产生新版本后须重新追踪."
  [requirements traces]
  (let [by-req (group-by :requirement_id traces)]
    (mapv (fn [req]
            (let [links (get by-req (:id req) [])
                  satisfies (filterv #(= "satisfies" (:relation %)) links)
                  verifies (filterv #(= "verifies" (:relation %)) links)
                  satisfied? (pos? (count satisfies))
                  verified? (pos? (count verifies))]
              {:requirement_id (:id req) :code (:code req) :revision (:revision req)
               :priority (:priority req)
               :design_links (count (filterv #(= "document" (:target_kind %)) satisfies))
               :verification_links (count verifies)
               :satisfied? satisfied?
               :verified? verified?
               :phases (vec (distinct (remove nil? (map :phase links))))
               :worst_deviation (let [order ["none" "minor" "major" "blocker"]
                                      levels (remove nil? (map :deviation_level links))]
                                  (when (seq levels) (last (sort-by #(.indexOf ^java.util.List order %) levels))))
               :missing (cond-> []
                          (not satisfied?) (conj "satisfies")
                          (not verified?) (conj "verifies"))}))
          (filterv #(not= "discarded" (:status %)) (s/latest requirements)))))


(defn trace-summary
  "汇总追踪矩阵整链齐备与缺链计数; 分母仅取当前最新版本需求集合, 不等于对批准范围基线的覆盖率."
  [report]
  (let [total (count report)
        full (count (filterv #(and (:satisfied? %) (:verified? %)) report))
        pct (fn [n] (if (zero? total) 0 (int (Math/round (* 100.0 (/ n total))))))]
    {:requirements total
     :fully-traced full
     :missing-design (count (remove :satisfied? report))
     :missing-verification (count (remove :verified? report))
     :coverage-pct (pct full)
     :design-pct (pct (count (filter :satisfied? report)))
     :verification-pct (pct (count (filter :verified? report)))
     :by-phase (into {} (for [phase ["design" "requirement-confirm" "SIT" "FAT" "SAT"]]
                          [phase (count (filter #(some #{phase} (:phases %)) report))]))
     :deviations (into {} (for [level ["minor" "major" "blocker"]]
                            [level (count (filter #(= level (:worst_deviation %)) report))]))
     :blocking-deviations (count (filter #(= "blocker" (:worst_deviation %)) report))}))


(defn document-tree
  "C04 多层下钻: 阶段 -> 结构节点 -> 密级 -> 最新版本文档 (含编号/版本/状态/创建人/密级), 只读派生, 已作废最新版本不计."
  [documents]
  (let [latest (filterv #(not= "discarded" (:status %)) (s/latest documents))
        leaf (fn [doc] (select-keys doc [:id :code :revision :title :status :created_by :classification :stage :structure_node :filename :created_at]))]
    (vec (for [[stage stage-docs] (sort-by first (group-by #(or (:stage %) "") latest))]
           {:stage (if (= "" stage) "未归集阶段" stage) :count (count stage-docs)
            :nodes (vec (for [[node node-docs] (sort-by first (group-by #(or (:structure_node %) "") stage-docs))]
                          {:structure_node (if (= "" node) "未归集节点" node) :count (count node-docs)
                           :classifications (vec (for [[cls cls-docs] (sort-by first (group-by #(or (:classification %) "internal") node-docs))]
                                                   {:classification cls :count (count cls-docs)
                                                    :documents (mapv leaf (sort-by :code cls-docs))}))}))}))))


(defn document-collection
  "按阶段/结构节点/密级对每个业务编码的最新版本证据文档做只读归集, 供分层查看与密级过滤; 不改变不可变版本, 授权与SHA256校验. 最新版本已被受控作废(discarded)的编号不计入归集, 单独以 discarded-count 透明呈现."
  [documents]
  (let [latest (s/latest documents)
        active (filterv #(not= "discarded" (:status %)) latest)
        discarded-count (- (count latest) (count active))
        buckets (fn [keyfn]
                  (->> (group-by keyfn active)
                       (mapv (fn [[k rows]] {:key k :count (count rows)}))
                       (sort-by (fn [{:keys [key]}] [(if (= "" key) 1 0) key]))))
        class-count (fn [c] (count (filterv #(= c (:classification %)) active)))]
    {:total (count active)
     :discarded-count discarded-count
     :by-stage (buckets :stage)
     :by-structure-node (buckets :structure_node)
     :by-classification (mapv (fn [c] {:classification c :count (class-count c)})
                              ["public" "internal" "confidential"])}))


(defn verification-coverage
  "按每个业务编码最新有效版本统计需求验证方式声明的只读覆盖度: 四类方法各自计数, 已声明/未设定与百分比覆盖率; 最新版本被受控作废(discarded)的编号不计入. 只读派生, 不落库不投递, 不改变不可变版本."
  [requirements]
  (let [methods ["test" "inspection" "demonstration" "analysis"]
        active (filterv #(not= "discarded" (:status %)) (s/latest requirements))
        total (count active)
        declared (count (filterv #(some #{(:verification_method %)} methods) active))
        method-count (fn [m] (count (filterv #(= m (:verification_method %)) active)))]
    {:total total
     :declared declared
     :undeclared (- total declared)
     :coverage-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ declared total))))
                     0)
     :by-method (mapv (fn [m] {:method m :count (method-count m)}) methods)}))


(def requirement-priority-order
  "需求优先级固定枚举档 (与前端 widgets.cljs 显示口径一致), 供只读分布按此顺序统计; required/desired 为 s/enum! 必填受控枚举, 故两档计数之和恒等于需求总数."
  ["required" "desired"])

(def requirement-priority-labels
  "需求优先级中文显示标签 (前端台账列 w/labels 同口径: required=必需, desired=期望)."
  {"required" "必需" "desired" "期望"})

(defn requirement-priority-distribution
  "按每个需求业务编码最新有效版本(store/latest 折叠修订链)统计其优先级(:priority)的项目级只读分布: 按固定两档(required/desired)给出各优先级需求数与该档占需求总数百分比, 并给出需求总数/已覆盖档数/未覆盖档数/优先级覆盖率与主导优先级(需求数最多者). 最新版本被受控作废(discarded)的需求不计入. priority 由新增/修订经 s/enum! 校验取自 required/desired 且必填, 故两档计数之和恒等于需求总数(与会议类型/干系人类别/权力-利益象限分布同族, 都是必填受控枚举; 区别于可选的参与态度/渠道使用分布会有未设定档). 已覆盖档数等于登记中实际出现的不同优先级档数, 未覆盖档数=2-已覆盖(两档固定故不变量 covered+uncovered=2), 覆盖率=round(100*已覆盖/2)恒取整为0/50/100; 逐档占比之和等于 total. dominant 用 max-key 扫固定 requirement-priority-order(并列取扫描靠后者即 desired, 不承诺唯一), 需求总数为 0 时为 nil. 只读派生, 不落库不投递, 不改变不可变版本, 不构成任何门控; 与验证方式覆盖度(看多少需求声明了验证方式)互补(本项看需求在必要性维度的构成与必须项是否压顶)."
  [requirements]
  (let [active (filterv #(not= "discarded" (:status %)) (s/latest requirements))
        total (count active)
        tally (frequencies (map :priority active))
        pct (fn [n] (if (pos? total) (int (Math/round ^double (* 100.0 (/ n total)))) 0))
        by-priority (mapv (fn [p] {:priority p
                                   :label (get requirement-priority-labels p p)
                                   :count (get tally p 0)
                                   :pct (pct (get tally p 0))})
                         requirement-priority-order)
        covered (count (filterv pos? (map :count by-priority)))
        dominant (when (pos? total)
                   (apply max-key (fn [p] (get tally p 0)) requirement-priority-order))
        dominant-count (if dominant (get tally dominant 0) 0)]
    {:available (pos? total)
     :total total
     :covered covered
     :uncovered (- (count requirement-priority-order) covered)
     :coverage-pct (int (Math/round ^double (* 100.0 (/ covered (count requirement-priority-order)))))
     :dominant-priority dominant
     :dominant-count dominant-count
     :by-priority by-priority}))


(defn verification-evidence-alignment
  "按每个业务编码最新有效版本交叉核对需求已声明的验证方式与其是否已配验证(verifies)证据关联的只读一致性: 统计声明验证方式的需求数, 其中已挂至少一条 verifies 关联者(aligned)与尚无验证证据关联者(gap), 以及对齐率. 进一步区分已挂验证关联者其证据文档是否已发布(approved): 至少一条 verifies 关联指向已发布证据文档者计入 evidence-released, 有关联但证据文档尚未发布(或验证证据为任务)者计入 evidence-pending, 并以声明数为分母给出 evidence-released-pct. 未声明验证方式的需求不进入分母, 最新版本被受控作废(discarded)的编号不计入. 只读派生, 不落库不投递, 不门控, 不改变不可变版本, 键名不带尾随问号."
  [requirements traces docs-by-id]
  (let [verif-traces (filterv #(= "verifies" (:relation %)) traces)
        verified-req-ids (into #{} (map :requirement_id) verif-traces)
        released-req-ids (into #{} (comp (filter #(and (= "document" (:target_kind %))
                                                       (= "approved" (:status (get docs-by-id (:target_id %))))))
                                         (map :requirement_id))
                               verif-traces)
        active (filterv #(not= "discarded" (:status %)) (s/latest requirements))
        declared (filterv #(contains? requirement-verification-methods (:verification_method %)) active)
        aligned (filterv #(verified-req-ids (:id %)) declared)
        released (filterv #(released-req-ids (:id %)) aligned)
        total-declared (count declared)
        total-aligned (count aligned)]
    {:declared total-declared
     :aligned total-aligned
     :gap (- total-declared total-aligned)
     :alignment-pct (if (pos? total-declared)
                      (int (Math/round ^double (* 100.0 (/ total-aligned total-declared))))
                      0)
     :evidence-released (count released)
     :evidence-pending (- total-aligned (count released))
     :evidence-released-pct (if (pos? total-declared)
                              (int (Math/round ^double (* 100.0 (/ (count released) total-declared))))
                              0)}))


(defn release-coverage
  "按每个业务编码最新有效版本统计证据文档发布审批链的只读覆盖度: registered/in_review/approved/rejected 各计数与已发布率; 最新版本被受控作废(discarded)的编号不计入. 只读派生, 不落库不投递, 不改变不可变版本."
  [documents]
  (let [active (filterv #(not= "discarded" (:status %)) (s/latest documents))
        total (count active)
        status-count (fn [s] (count (filterv #(= s (:status %)) active)))
        approved (status-count "approved")
        in-review (status-count "in_review")
        registered (status-count "registered")
        rejected (status-count "rejected")]
    {:total total
     :approved approved
     :in-review in-review
     :registered registered
     :rejected rejected
     :released-pct (if (pos? total)
                     (int (Math/round ^double (* 100.0 (/ approved total))))
                     0)}))


(defn voided-document-codes
  "从按 id 索引的全部证据文档版本中派生其最新版本已被受控作废(discarded)的业务编码集合, 供追踪链/需求验证/Gate 验收快照只读标注其引用证据是否现已作废."
  [docs-by-id]
  (into #{} (comp (filter #(= "discarded" (:status %))) (map :code)) (s/latest (vals docs-by-id))))


(defn trace-read-model
  "只读派生追踪链所引用证据文档版本的发布状态: 文档目标按其确定版本状态标注 released/pending/rejected/missing, 任务目标为 n/a; 若所引用证据文档所属业务编码的最新版本已被受控作废(discarded), 追加 evidence_voided 为 true 以显式标注该追踪所依赖的证据现已作废. 免迁移读取时计算, 不写存储, 不改变不可变版本, 键名不带尾随问号."
  [docs-by-id trace]
  (let [voided-codes (voided-document-codes docs-by-id)]
    (if-not (= "document" (:target_kind trace))
      (assoc trace :evidence_release_state "n/a" :evidence_status nil :evidence_released nil :evidence_voided false)
      (let [doc (get docs-by-id (:target_id trace))
            status (:status doc)
            state (cond
                    (nil? doc) "missing"
                    (= "approved" status) "released"
                    (= "rejected" status) "rejected"
                    :else "pending")]
        (assoc trace :evidence_status status
               :evidence_release_state state
               :evidence_released (= "approved" status)
               :evidence_voided (boolean (voided-codes (:code doc))))))))


(defn requirement-trace-model
  "只读派生每条需求版本已登记的追踪关联: 按 relation 统计设计满足(satisfies)与验证(verifies)关联的条数与齐备状态; 交叉核对该版本已声明的验证方式与实际 verifies 验证关联是否一致(声明了验证方式却尚无验证证据关联即为 gap), 并进一步区分该验证关联所指向的证据文档是否已发布(approved): 已声明且至少一条 verifies 关联指向已发布证据文档记 released, 有 verifies 关联但证据文档尚未发布(或验证证据为任务)记 pending, 无 verifies 关联记 no-verification, 未声明验证方式记 not-applicable; 另追加 verification_evidence_voided 为 true 当任一 verifies 关联所指向的证据文档所属业务编码的最新版本已被受控作废(discarded), 以显式标注验证证据现已作废. 免迁移读取时计算, 不写存储, 不门控, 键名不带尾随问号."
  [traces-by-req docs-by-id req]
  (let [links (get traces-by-req (:id req) [])
        voided-codes (voided-document-codes docs-by-id)
        design (filterv #(= "satisfies" (:relation %)) links)
        verif (filterv #(= "verifies" (:relation %)) links)
        released-verif (filterv #(and (= "document" (:target_kind %))
                                      (= "approved" (:status (get docs-by-id (:target_id %)))))
                                verif)
        verif-voided (boolean (some #(and (= "document" (:target_kind %))
                                          (voided-codes (:code (get docs-by-id (:target_id %)))))
                                    verif))
        state (cond
                (empty? links) "untracked"
                (and (seq design) (seq verif)) "complete"
                (empty? design) "missing-design"
                :else "missing-verification")
        declared? (contains? requirement-verification-methods (:verification_method req))
        alignment (cond
                    (not declared?) "not-applicable"
                    (pos? (count verif)) "aligned"
                    :else "declared-unverified")
        evidence-state (cond
                         (not declared?) "not-applicable"
                         (empty? verif) "no-verification"
                         (pos? (count released-verif)) "released"
                         :else "pending")]
    (assoc req
      :trace_design_links (count design)
      :trace_verification_links (count verif)
      :trace_state state
      :verification_alignment alignment
      :verification_evidence_state evidence-state
      :verification_evidence_voided verif-voided)))
