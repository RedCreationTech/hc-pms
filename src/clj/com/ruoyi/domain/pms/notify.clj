(ns com.ruoyi.domain.pms.notify
  "本地通知收件箱 (C11): 把项目待办事件投影为授权收件人的本地通知, 支持已读、处理与失败重试.
   只投本地不投外部消息; 收件人失去该项目访问权后立即看不到该通知; 同一事件对同一收件人只落一条,
   重试只推进投递状态而不产生重复通知."
  (:require
    [com.ruoyi.domain.pms.governance.store :as gov]
    [com.ruoyi.domain.pms.kernel :as kernel]
    [com.ruoyi.domain.pms.portfolio :as portfolio]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.time
      LocalDate)))


(def statuses
  "通知本地状态; 投递结果是独立维度 (delivery), 不覆盖业务状态."
  #{"unread" "read" "handled" "dismissed"})


(defn- nature-of
  "把待办分组与时限折算为事件性质."
  [item]
  (cond (:overdue item) "overdue"
        (= "reviews" (:group item)) "pending-review"
        (= "escalations" (:group item)) "escalation"
        :else "due-soon"))


(def natures
  "事件性质与可读称谓."
  {"overdue" "逾期提醒" "due-soon" "即将到期" "pending-review" "待我确认" "escalation" "升级待处理"})


(defn- event-items
  "从待办读模型取需要通知的事件: 逾期/即将到期, 待我确认, 升级待处理.
   待办读模型与收件箱共用同一派生 (portfolio/todo-rows), 只取当前用户真实可见的项目, 不跨项目."
  [q actor today]
  (let [todo (portfolio/todo-rows q actor today)
        kinds #{"owned" "reminders" "reviews" "escalations"}
        projects (into {} (map (juxt :project_id identity)
                               (filter #(not (contains? #{"closed" "cancelled"} (:status %)))
                                       (portfolio/authorized-projects q actor))))
        items (->> (concat (:owned todo) (:reminders todo) (:reviews todo) (:escalations todo))
                   (filter #(contains? kinds (:group %)))
                   (filter #(contains? projects (:project_id %)))
                   (filter #(or (:overdue %) (:due_soon %) (contains? #{"reviews" "escalations"} (:group %))))
                   (keep (fn [item] (when (:project_id item) (assoc item :recipient_id (:user_id actor)))))
                   vec)]
    {:projects projects :items items}))


(defn- event-key
  [item uid]
  (str "notify:" (nature-of item) ":" (:kind item) ":" (:id item) ":" uid))


(defn- existing-by-code
  "当前用户收到的通知, 按编码索引最新版本 (已作废的不再参与去重)."
  [q actor project-ids]
  (reduce (fn [acc row]
            (let [rec (gov/decode row)]
              (if (and (= (:user_id actor) (:recipient_id rec)) (not= "dismissed" (:status rec)))
                (assoc acc (:code rec) rec)
                acc)))
          {} (q :pms/gov-in-projects {:project_ids (vec project-ids) :kinds ["notification"]})))


(defn deliver!
  "把当前可见事件投递为本地通知: 已有则保留原状态不做重复通知, 新事件写为 unread.
   返回本轮新建, 去重跳过与总数, 供调度重试时核对不重复."
  [q actor date]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (let [today (if (seq (str date)) (LocalDate/parse (str date)) (LocalDate/now))
        {:keys [projects items]} (event-items q actor today)
        existing (existing-by-code q actor (keys projects))
        created (atom 0)
        seen (atom #{})]
    (doseq [item items
            :let [code (event-key item (:user_id actor))]
            :when (not (contains? @seen code))]
      (swap! seen conj code)
      (when-not (contains? existing code)
        (when-let [project (get projects (:project_id item))]
          (gov/insert! q project actor "notification"
                       {:code code :recipient_id (:user_id actor)
                        :nature (nature-of item) :target_kind (:kind item) :target_id (str (:id item))
                        :project_no (:project_no item) :title (:title item) :tab (:tab item)
                        :due_date (:due_date item) :raised_on (str today)
                        :channel "local" :delivery "delivered" :attempts 0}
                       {:status "unread"})
          (swap! created inc))))
    {:created @created :existing (- (count @seen) @created) :total (count @seen)}))


(defn- inbox-rows
  "当前用户收件箱: 先按项目授权过滤 (撤权立即失效), 再按状态与项目筛选."
  [q actor filters]
  (let [projects (portfolio/authorized-projects q actor)
        by-project (into {} (map (juxt :project_id identity) projects))
        status (not-empty (str (or (:status filters) "")))
        project-id (not-empty (str (or (:project_id filters) "")))]
    (when status (gov/enum! status statuses "通知状态"))
    (->> (q :pms/gov-in-projects {:project_ids (keys by-project) :kinds ["notification"]})
         (map gov/decode)
         (filter #(= (:user_id actor) (:recipient_id %)))
         (filter #(contains? by-project (:project_id %)))
         (filter #(or (nil? status) (= status (:status %))))
         (filter #(or (nil? project-id) (= project-id (:project_id %))))
         (map (fn [rec]
                (let [project (get by-project (:project_id rec))]
                  ;; 项目名一律取项目主数据, 不沿用投递时快照, 避免改名后显示陈旧或与提醒标题混淆.
                  (assoc rec :project_no (:project_no project) :project_name (:name project)
                         :project_status (:status project)))))
         (sort-by (juxt (fn [rec] (if (= "unread" (:status rec)) 0 1)) :due_date (fn [rec] (str (:raised_on rec)))))
         vec)))


(defn inbox
  "当前用户本地通知收件箱与汇总; 无项目权限时为空, 不暴露跨项目内容."
  [svc actor filters]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (let [rows (inbox-rows (:query-fn svc) actor filters)
        tally (fn [status] (count (filter #(= status (:status %)) rows)))
        delivery (frequencies (map #(or (:delivery %) "delivered") rows))]
    {:rows rows
     :total (count rows)
     :summary {:unread (tally "unread") :read (tally "read")
               :handled (tally "handled") :dismissed (tally "dismissed")
               :failed (get delivery "failed" 0)}
     :delivery {:delivered (get delivery "delivered" 0) :failed (get delivery "failed" 0)}
     :channel "local"}))


(def transitions
  "允许的本地状态流转; 已处理与已作废为终态, 不能回退成未读."
  {"unread" #{"read" "handled" "dismissed"}
   "read" #{"handled" "dismissed"}
   "handled" #{"read"}
   "dismissed" #{}})


(defn- load!
  "按记录 ID 在当前用户可见项目内定位通知 (不带项目参数, 找不到即 404)."
  [q actor id]
  (let [projects (portfolio/authorized-projects q actor)]
    (when-let [row (->> (q :pms/gov-in-projects {:project_ids (mapv :project_id projects) :kinds ["notification"]})
                        (filter #(= id (:record_id %)))
                        first)]
      (let [project (first (filter #(= (:project_id row) (:project_id %)) projects))]
        {:record (gov/decode row) :project project}))))


(defn- own!
  "只允许收件人本人操作自己的通知."
  [actor record]
  (when-not (= (:user_id actor) (:recipient_id record)) (r/fail! 403 "该通知不属于当前用户")))


(defn mark!
  "标记通知状态 (已读/已处理/已作废), 非本人 403, 非法流转 409."
  [svc actor id status]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (let [target (gov/enum! status statuses "通知状态")]
    (kernel/transaction! svc
                         (fn [q]
                           (let [{:keys [record project]} (load! q actor id)]
                             (when-not record (r/fail! 404 "通知不存在"))
                             (own! actor record)
                             (let [current (:status record)]
                               (when-not (contains? (get transitions current #{}) target)
                                 (r/fail! 409 (str "通知状态不能从 " current " 变为 " target)))
                               (gov/change! q project record target {:status_changed_on (str (LocalDate/now))})))))))


(defn retry-failures!
  "重试本地投递失败的通知: 只把 delivery 从 failed 推进为 delivered 并累加尝试次数,
   不改业务状态, 不产生新通知 (同一编码仍只有一条)."
  [svc actor]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (kernel/transaction! svc
                       (fn [q]
                         (let [projects (portfolio/authorized-projects q actor)
                               rows (->> (q :pms/gov-in-projects {:project_ids (mapv :project_id projects)
                                                                  :kinds ["notification"]})
                                         (map gov/decode)
                                         (filter #(= (:user_id actor) (:recipient_id %)))
                                         (filter #(= "failed" (:delivery %))))]
                           {:retried (count rows)
                            :rows (mapv (fn [rec]
                                          (let [project (first (filter #(= (:project_id rec) (:project_id %)) projects))]
                                            (gov/change! q project rec (:status rec)
                                                         {:delivery "delivered" :attempts (inc (or (:attempts rec) 0))
                                                          :last_attempt_on (str (LocalDate/now))})
                                            {:id (:id rec) :code (:code rec) :attempts (inc (or (:attempts rec) 0))}))
                                        rows)}))))


(defn mark-delivery-failed!
  "把通知标为本地投递失败, 供调度记录失败并等待重试; 不改变业务状态."
  [svc actor id]
  (r/permit! actor ["pms:project:list" "pms:project:query"])
  (kernel/transaction! svc
                       (fn [q]
                         (let [{:keys [record project]} (load! q actor id)]
                           (when-not record (r/fail! 404 "通知不存在"))
                           (own! actor record)
                           (gov/change! q project record (:status record) {:delivery "failed"})))))
