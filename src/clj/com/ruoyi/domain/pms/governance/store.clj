(ns com.ruoyi.domain.pms.governance.store
  "治理记录的类型边界,项目引用与受控持久化."
  (:require
    [cheshire.core :as json]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.rules :as rules]))


(def kinds
  "允许持久化的治理对象类型."
  #{"charter" "requirement" "document" "trace" "risk" "issue"
    "meeting" "action" "change" "gate-template" "gate"
    "stakeholder" "raci" "comm-plan" "template-instance" "dq" "node-pause"})


(defn input!
  "校验请求字段白名单,保留聚合版本用于事务边界."
  [body fields]
  (rules/object! body (conj (vec fields) :version))
  body)


(defn text!
  "读取必填业务文本并限制长度."
  ([body key] (text! body key 2000))
  ([body key limit] (rules/text! (get body key) (name key) limit true)))


(defn optional-text!
  "读取可选业务文本."
  [body key limit]
  (rules/text! (get body key) (name key) limit false))


(defn enum!
  "校验明确的业务枚举."
  [value allowed field]
  (when-not (contains? allowed value) (rules/fail! 400 (str field "取值不合法")))
  value)


(defn boolean!
  "拒绝字符串等伪布尔值."
  [value field]
  (when-not (boolean? value) (rules/fail! 400 (str field "必须为布尔值")))
  value)


(defn user!
  "确保责任人是有效的本地用户."
  [q uid]
  (let [id (rules/positive-id! uid "人员")]
    (when-not (q :pms/user {:user_id id}) (rules/fail! 400 "人员不存在或已停用"))
    id))


(defn date!
  "验证必填业务日期."
  [body key]
  (rules/date! (text! body key 10) (name key)))


(defn decode
  "把受控payload转换为平铺只读对象."
  [row]
  (when row
    (merge (json/parse-string (:payload row) true)
           (dissoc row :payload) {:id (:record_id row)})))


(defn records
  "读取项目内指定类型的全部版本."
  [q project kind]
  (enum! kind kinds "kind")
  (mapv decode (q :gov/list {:project_id (:project_id project) :kind kind})))


(defn record!
  "读取同项目指定类型记录,不接受跨项目引用."
  [q project kind id]
  (when-not (string? id) (rules/fail! 400 "引用ID必须为字符串"))
  (let [row (decode (q :gov/record {:project_id (:project_id project) :record_id id}))]
    (when-not (and row (= kind (:kind row))) (rules/fail! 404 "治理对象不存在或类型不匹配"))
    row))


(defn latest
  "从每个业务编码取最新版本."
  [rows]
  (mapv #(apply max-key :revision %) (vals (group-by :code rows))))


(defn latest!
  "阻止在旧版本上继续提出变更."
  [q project record]
  (when (some #(and (= (:code %) (:code record)) (> (:revision %) (:revision record)))
              (records q project (:kind record)))
    (rules/fail! 409 "对象已有更新版本,请使用最新版本"))
  record)


(defn insert!
  "插入经过类型校验的新记录或不可变版本."
  [q project actor kind fields opts]
  (enum! kind kinds "kind")
  (let [id (kernel/id)
        row {:record_id id :project_id (:project_id project) :kind kind
             :code (or (:code fields) id) :revision (or (:revision opts) 1)
             :status (or (:status opts) "draft") :created_by (:user_id actor)
             :owner_id (:owner_id fields)
             :payload (json/generate-string (dissoc fields :code :owner_id))}]
    (q :gov/insert! row)
    (record! q project kind id)))


(defn change!
  "仅供受控命令修改工作流状态和类型校验后的字段."
  [q project record status patch]
  (let [payload (apply dissoc (merge record patch)
                       [:id :record_id :project_id :kind :code :revision :status
                        :created_by :owner_id :created_at :updated_at])]
    (rules/changed! (q :gov/update! {:record_id (:id record) :project_id (:project_id project)
                                     :status status :owner_id (or (:owner_id patch) (:owner_id record))
                                     :payload (json/generate-string payload)}))
    (record! q project (:kind record) (:id record))))


(defn status!
  "限制当前命令允许的对象状态."
  [record allowed]
  (when-not (contains? allowed (:status record))
    (rules/fail! 409 "当前状态不允许此操作"))
  record)


(defn evidence!
  "绑定同项目不可变文档版本作为证据."
  [q project ids required?]
  (when-not (and (vector? ids) (<= (count ids) 50) (= (count ids) (count (set ids))))
    (rules/fail! 400 "证据必须为不重复的文档版本ID数组"))
  (when (and required? (empty? ids)) (rules/fail! 409 "必须提供文档版本证据"))
  (doseq [id ids] (record! q project "document" id))
  ids)


(defn reviewer!
  "选择具有项目阅读资格和质量审批权限的独立审核人."
  [q project actor uid]
  (let [id (user! q uid)
        reviewer (rules/actor q {:user-id id})]
    (when (= id (:user_id actor)) (rules/fail! 409 "审核人不得为提交人"))
    (rules/permit! reviewer "pms:quality:approve")
    (rules/access! q reviewer project false)
    id))


(defn decision-actor!
  "核验指定审核人与提交人职责分离."
  [actor record]
  (when-not (= (:user_id actor) (:reviewer_id record))
    (rules/fail! 403 "只有指定审核人可以作出决定"))
  (when (= (:user_id actor) (:submitted_by record))
    (rules/fail! 409 "禁止自行审核本人提交内容")))


(defn task-referenced?
  "识别会议行动或需求追踪使用的任务,供计划删除前保护引用."
  [q project task-id]
  (boolean
    (or (some #(= task-id (:target_task_id %)) (records q project "action"))
        (some #(and (= "task" (:target_kind %)) (= task-id (:target_id %)))
              (records q project "trace")))))
