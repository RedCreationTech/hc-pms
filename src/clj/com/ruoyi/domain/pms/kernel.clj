(ns com.ruoyi.domain.pms.kernel
  "PMS扩展模块共享的事务, 权限, 乐观锁和审计边界."
  (:require [cheshire.core :as json]
            [com.ruoyi.domain.pms.rules :as rules]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]
           [java.util UUID]))

(defn id
  "生成跨数据库稳定的UUID业务标识."
  []
  (str (UUID/randomUUID)))

(defn database-error!
  "将数据库唯一约束及锁冲突转换为安全的409响应."
  [error]
  (let [cause (first (filter #(instance? SQLException %)
                            (take-while some? (iterate #(.getCause ^Throwable %) error))))]
    (if-not cause (throw error)
      (cond
        (or (contains? #{"40001" "41000"} (.getSQLState ^SQLException cause))
            (contains? #{5 6 1205 1213} (.getErrorCode ^SQLException cause)))
        (rules/fail! 409 "存在并发修改,请刷新后重试")
        (or (= "23000" (.getSQLState ^SQLException cause))
            (re-find #"(?i)unique|duplicate|constraint" (or (.getMessage ^Throwable cause) "")))
        (rules/fail! 409 "数据约束冲突,请检查编号和关联对象")
        :else (throw error)))))

(defn transaction!
  "在同一连接执行参数化查询, 异常时原子回滚."
  [{:keys [db query-fn]} f]
  (try
    (jdbc/with-transaction [tx db]
      (f (fn [query params] (query-fn tx query params))))
    (catch Exception e (database-error! e))))

(defn read!
  "校验功能权限和项目读取范围后执行查询函数."
  [{:keys [query-fn]} actor project-id permission f]
  (rules/permit! actor permission)
  (let [project (rules/access! query-fn actor
                              (query-fn :pms/project {:project_id project-id}) false)]
    (f query-fn project)))

(defn event!
  "记录聚合版本下的业务动作, 不把附件正文或凭据写入通用审计."
  [q actor project event-type payload]
  (let [payload (if (.startsWith event-type "cost.") (dissoc payload :reason) payload)]
    (q :pms/insert-event!
     {:event_id (id) :project_id (:project_id project)
      :event_type event-type :description (subs (or (not-empty (:reason payload)) event-type) 0
                                                (min 500 (count (or (not-empty (:reason payload)) event-type))))
      :actor_id (:user_id actor) :actor_name (:user_name actor)
      :from_status (:status project)
      :to_status (:status (q :pms/project {:project_id (:project_id project)}))
      :payload (json/generate-string (merge {:from_status (:status project)
                                            :to_status (:status (q :pms/project {:project_id (:project_id project)}))}
                                           payload))
      :aggregate_version (:version (q :pms/project {:project_id (:project_id project)}))})))

(defn- writable!
  "校验项目版本及写入状态, 审批可显式使用项目只读范围."
  [q actor project-id body {:keys [write? allow-terminal? allow-paused?]}]
  (when-not (map? body) (rules/fail! 400 "请求体必须是JSON对象"))
  (let [project (rules/access! q actor (q :pms/project {:project_id project-id}) write?)]
    (when-not (or allow-terminal? (and allow-paused? (= "paused" (:status project))))
      (rules/editable! project))
    (when (and (= "paused" (:status project)) (not allow-paused?))
      (rules/fail! 409 "项目暂停中,请先完成受控恢复"))
    (rules/version! project (:version body))
    project))

(defn mutate!
  "原子执行项目命令, 检查版本, 递增聚合版本并记录审计."
  ([svc actor project-id permission body event-type f]
   (mutate! svc actor project-id permission body event-type {} f))
  ([svc actor project-id permission body event-type options f]
   (rules/permit! actor permission)
   (transaction! svc
     (fn [q]
       (let [project (writable! q actor project-id body (merge {:write? true} options))]
         (rules/changed! (q :pms/touch-project! project))
         (let [result (f q project)
               current (q :pms/project {:project_id project-id})]
           (event! q actor project event-type
                   {:reason (:reason body) :request_fields (mapv name (sort (keys body)))
                    :result (when (map? result)
                              (select-keys result [:id :record_id :task_id :baseline_id
                                                   :version_id :gate_id :status :decision]))})
           {:result result :project_version (:version current)}))))))

(defn user!
  "要求关联用户仍有效且属于项目, 管理者本身也允许."
  [q project user-id label]
  (let [uid (rules/positive-id! user-id label)]
    (when-not (q :pms/user {:user_id uid}) (rules/fail! 400 (str label "不存在或已停用")))
    (when-not (or (= uid (:manager_id project))
                  (q :pms/member {:project_id (:project_id project) :user_id uid}))
      (rules/fail! 400 (str label "必须是当前项目成员")))
    uid))

(defn independent-review!
  "检查指定审批人与提交者分离, 不允许管理员替代指定人员自审."
  [actor submitted-by reviewer-id]
  (when (= (:user_id actor) submitted-by)
    (rules/fail! 403 "提交者不能审批自己的申请"))
  (when-not (= (:user_id actor) reviewer-id)
    (rules/fail! 403 "只有指定审批人可以作出此决定")))
