(ns com.ruoyi.domain.pms.presales
  "A04 合同/售前资料交接与产品线资料检查 (本地侧).
   产品线应交资料由平台配置 presales-checklist 版本化管理 (调用方不能自带清单掩盖缺料);
   项目按产品线 (本地可用的分组维度为项目合同号) 选取生效清单, 逐项登记收到的售前资料与同项目文档版本证据;
   未收齐的必交项阻断提交并给出退回意见, 齐备后独立审批通过才可用于已批准立项.
   来源 CRM/OA 的真实回执仍待外部合同, 本地只记录来源标注, 不冒充已同步."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.governance.store :as g]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r]))


(def kind
  "治理记录类型: 售前资料交接."
  "presales-handover")


(def config-kind
  "平台配置类型: 产品线应交资料清单."
  "presales-checklist")


(def statuses
  "交接流程状态: 待补交 -> 已退回 (缺料) -> 已提交 -> 已批准/已退回."
  #{"pending" "submitted" "returned" "approved"})


(def sources
  "来源标注: 本地登记 / CRM / OA; 仅作来源追溯, 不代表已与外部系统对接."
  #{"local" "crm" "oa"})


(def source-sync
  "外部来源的同步状态: 本地登记为不适用, CRM/OA 一律 not_configured (待真实合同)."
  {"local" "not_applicable" "crm" "not_configured" "oa" "not_configured"})


(defn- text-list!
  "读取并规范化资料名称/文件编码数组, 去空去重."
  [value label limit]
  (when-not (and (vector? value) (<= (count value) limit))
    (r/fail! 400 (str label "必须是最多" limit "项的数组")))
  (vec (distinct (remove empty? (map #(r/text! % label 100 false) value)))))


(defn checklist!
  "产品线应交资料清单内容: 编码即产品线 (按编码唯一), 每项含名称与是否必交; 名称在清单内唯一."
  [body]
  (r/object! body [:code :name :product_line :required_docs :optional_docs :description :reason])
  (let [required (text-list! (:required_docs body) "必交资料" 30)
        optional (text-list! (or (:optional_docs body) []) "可选资料" 30)
        overlap (set/intersection (set required) (set optional))]
    (when (seq overlap) (r/fail! 400 (str "必交与可选资料重复: " (str/join ", " overlap))))
    {:code (r/text! (:code body) "清单编码" 100 true)
     :name (r/text! (or (:name body) (:code body)) "清单名称" 100 true)
     :product_line (r/text! (:product_line body) "产品线" 100 true)
     :required_docs required :optional_docs optional
     :description (g/optional-text! body :description 500)}))


(def read-permissions
  "读取售前资料交接的任一权限."
  config/read-permissions)


(defn- published
  "当前生效的产品线应交清单 (published/frozen/locked)."
  [q]
  (filterv #(contains? #{"published" "frozen" "locked"} (:status %)) (config/records q config-kind)))


(defn checklist-for
  "按项目所附产品线取当前生效清单; 未发布任何清单时不臆造缺料.
   返回 {:rule 清单或nil :rows 适用清单列表}."
  [q project]
  (let [rules (published q)]
    (if (empty? rules)
      {:rule nil :rows []}
      (let [rule (or (some #(when (= (:code %) (:contract_no project)) %) rules)
                     (some #(when (= "default" (:code %)) %) rules))]
        {:rule rule :rows (if rule (:required_docs rule) [])}))))


(defn- item-evidence
  "逐项校验并规范化已收资料; 证据必须是同项目文档版本."
  [q project item]
  (when-not (map? item) (r/fail! 400 "已收资料每项必须是对象"))
  {:name (r/text! (:name item) "资料名称" 100 true)
   :document_ids (g/evidence! q project (vec (:document_ids item)) false)})


(defn- items!
  "规范化已收资料: 只允许清单内名称, 名称不重复, 并附同项目文档版本作为证据."
  [q project rows received]
  (when-not (and (vector? received) (<= (count received) 60))
    (r/fail! 400 "已收资料必须是最多60项的数组"))
  (let [allowed (set (mapcat #(concat (:required_docs %) (:optional_docs %)) rows))
        items (mapv #(item-evidence q project %) received)]
    (doseq [{:keys [name]} items]
      (when-not (contains? allowed name) (r/fail! 400 (str "资料不在该产品线应交清单内: " name))))
    (when-not (= (count items) (count (set (map :name items))))
      (r/fail! 400 "同一资料不能重复登记"))
    items))


(defn- missing
  "必交项中尚未收到 (无论有无证据) 的资料名称."
  [rows items]
  (let [got (set (map :name items))]
    (vec (remove got (mapcat :required_docs rows)))))


(defn- checklist-view
  "交接记录的只读视图: 补上缺料与齐套判定 (读取时派生, 不写回)."
  [record]
  (let [rows (:checklist record)
        miss (missing rows (:items record))]
    (assoc record :rule_missing miss :rule_complete (empty? miss)
           :required_count (count (mapcat :required_docs rows)))))


(defn handovers
  "读取项目内全部售前资料交接."
  [q project]
  (mapv checklist-view (g/records q project kind)))


(defn handover!
  "读取单条交接, 不接受跨项目引用."
  [q project rid]
  (checklist-view (g/record! q project kind rid)))


(defn- mutate!
  "复用聚合事务, 乐观锁与项目范围; 版本号缺省取项目当前版本, 显式提供时参与一致性校验.
   options 可指定功能权限与只读范围, 供审批人 (项目观察者) 作出决定."
  ([svc actor id body event f] (mutate! svc actor id body event {} f))
  ([svc actor id body event {:keys [permission] :as options} f]
   (k/mutate! svc actor id (or permission "pms:project:edit") body event
              (merge {:write? true} (dissoc options :permission)) f)))


(defn- command!
  "项目版本随请求携带 (与治理命令入口同口径), 由聚合事务做乐观锁校验."
  ([svc actor id body event f] (command! svc actor id body event {} f))
  ([svc actor id body event options f]
   (mutate! svc actor id body event options f)))


(defn create!
  "按项目产品线建立售前资料交接: 未发布清单时不臆造必交项, 如实标注 checklist_missing."
  [svc actor id body]
  (command! svc actor id body "pms.presales.created"
            (fn [q project]
              (r/object! body [:version :title :source :reference_no :owner_id :items :reason])
              (let [{:keys [rule]} (checklist-for q project)
                    source (g/enum! (or (:source body) "local") sources "来源")]
                (-> {:title (r/text! (or (:title body) (str "售前资料交接: " (:name project))) "标题" 200 true)
                     :product_line (:contract_no project)
                     :checklist_code (:code rule)
                     :source source :source_sync (get source-sync source)
                     :reference_no (g/optional-text! body :reference_no 100)
                     :owner_id (k/user! q project (:owner_id body) "负责人")
                     :checklist (if rule [rule] [])
                     :items (items! q project (if rule [rule] []) (:items body))}
                    (#(g/insert! q project actor kind % {:status "pending"}))
                    checklist-view)))))


(defn update!
  "维护交接资料 (待补交或已退回时): 重新按当前生效清单核对, 缺料如实列出."
  [svc actor id rid body]
  (command! svc actor id body "pms.presales.updated"
            (fn [q project]
              (r/object! body [:version :title :owner_id :items :reference_no :reason])
              (let [record (g/record! q project kind rid)]
                (g/status! record #{"pending" "returned"})
                (let [rows (:checklist record)
                      items (items! q project rows (:items body))]
                  (checklist-view
                    (g/change! q project record (:status record)
                               (cond-> {:items items
                                        :title (r/text! (or (:title body) (:title record)) "标题" 200 true)
                                        :owner_id (k/user! q project (or (:owner_id body) (:owner_id record)) "负责人")}
                                 (contains? body :reference_no)
                                 (assoc :reference_no (g/optional-text! body :reference_no 100))))))))))


(defn submit!
  "提交交接评审: 必交项未收齐直接 409 并列出缺料 (由调用方登记退回意见)."
  [svc actor id rid body]
  (command! svc actor id body "pms.presales.submitted"
            (fn [q project]
              (r/object! body [:version :reviewer_id :note :reason])
              (let [record (g/record! q project kind rid)]
                (g/status! record #{"pending" "returned"})
                (let [miss (missing (:checklist record) (:items record))]
                  (when (seq miss)
                    (r/fail! 409 (str "缺少必交资料: " (str/join ", " miss))))
                  (g/change! q project record "submitted"
                             {:reviewer_id (g/reviewer! q project actor (:reviewer_id body))
                              :submitted_by (:user_id actor)
                              :submitted_on (str (java.time.LocalDate/now))
                              :note (g/optional-text! body :note 1000)}))))))


(defn return!
  "缺资料退回: 记录退回原因并把交接退回待补交, 保留本次缺料明细供追溯."
  [svc actor id rid body]
  (command! svc actor id body "pms.presales.returned" {:write? false :permission "pms:project:query"}
            (fn [q project]
              (r/object! body [:version :reason])
              (let [record (g/record! q project kind rid)]
                (g/status! record #{"submitted" "pending"})
                (when (and (:reviewer_id record) (not= (:user_id actor) (:reviewer_id record))
                           (not (:admin? actor)))
                  (r/fail! 403 "只有指定审核人可以作出决定"))
                (g/change! q project record "returned"
                           {:return_reason (g/text! body :reason)
                            :returned_by (:user_id actor)
                            :returned_on (str (java.time.LocalDate/now))
                            :return_missing (missing (:checklist record) (:items record))
                            :returns (conj (vec (:returns record))
                                           {:reason (g/text! body :reason) :by (:user_id actor)
                                            :on (str (java.time.LocalDate/now))
                                            :missing (missing (:checklist record) (:items record))})})))))


(defn approve!
  "独立审核人批准资料齐备的交接, 批准后才可用于已批准立项."
  [svc actor id rid body]
  (command! svc actor id body "pms.presales.approved" {:write? false :permission "pms:project:query"}
            (fn [q project]
              (r/object! body [:version :reason])
              (let [record (g/record! q project kind rid)]
                (g/status! record #{"submitted"})
                (g/decision-actor! actor record)
                (let [miss (missing (:checklist record) (:items record))]
                  (when (seq miss) (r/fail! 409 (str "缺少必交资料: " (str/join ", " miss))))
                  (g/change! q project record "approved"
                             {:decision_reason (g/text! body :reason)
                              :decided_by (:user_id actor)
                              :decided_on (str (java.time.LocalDate/now))}))))))


(defn workspace
  "项目内售前资料交接只读汇总: 记录, 当前产品线生效清单与齐套统计."
  [svc actor id]
  (r/permit! actor read-permissions)
  (let [q (:query-fn svc)
        project (r/access! q actor (q :pms/project {:project_id id}) false)
        row-list (handovers q project)
        {:keys [rule rows]} (checklist-for q project)]
    {:product_line (:contract_no project)
     :version (:version project)
     :checklist (when rule rule)
     :checklist_missing (boolean (nil? rule))
     :required_docs (or rows [])
     :rows row-list
     :summary {:total (count row-list)
               :pending (count (filter #(= "pending" (:status %)) row-list))
               :returned (count (filter #(= "returned" (:status %)) row-list))
               :approved (count (filter #(= "approved" (:status %)) row-list))
               :missing_all (vec (sort (distinct (mapcat :rule_missing row-list))))}}))
