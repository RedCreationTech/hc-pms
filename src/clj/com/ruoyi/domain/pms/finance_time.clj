(ns com.ruoyi.domain.pms.finance-time
  "项目工时提交和独立审核, 对跨项目同日工时执行事务容量校验."
  (:require [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules])
  (:import [java.time LocalDate]))

(defn dto
  "对外返回工时小时数, 保留精确分钟便于对账."
  [row]
  (assoc row :id (:entry_id row) :hours (money/hours (:minutes row))))

(defn- input!
  "校验任务归属, 工时日期和指定审核人."
  [q actor project body]
  (rules/object! body [:version :task_id :work_date :hours :note :reviewer_id])
  (when-not (= "execution" (:status project)) (rules/fail! 409 "仅执行中的项目可以提交工时"))
  (let [task (q :planning/task {:project_id (:project_id project) :task_id (:task_id body)})
        date (rules/date! (:work_date body) "工作日期")
        reviewer (kernel/user! q project (:reviewer_id body) "工时审核人")]
    (when-not (and task (not= "summary" (:task_type task))) (rules/fail! 400 "必须关联当前项目可执行任务"))
    (when (or (nil? date) (pos? (compare date (str (LocalDate/now)))))
      (rules/fail! 400 "工作日期不能为空或晚于今天"))
    (when (= reviewer (:user_id actor)) (rules/fail! 400 "工时审核人不能是提交者"))
    {:entry_id (kernel/id) :project_id (:project_id project) :task_id (:task_id task)
     :user_id (:user_id actor) :work_date date :minutes (money/minutes! (:hours body))
     :note (rules/text! (:note body) "工作说明" 1000 true)
     :submitted_by (:user_id actor) :reviewer_id reviewer}))

(defn submit!
  "提交本人真实任务工时, 原子预留跨项目同一天的总分钟数."
  [svc actor project-id body]
  (kernel/mutate! svc actor project-id "pms:project:edit" body "time.submitted"
    (fn [q project]
      (let [entry (input! q actor project body)]
        (when-not (q :finance/time-day entry) (q :finance/insert-day! entry))
        (when-not (= 1 (q :finance/reserve-day! entry))
          (rules/fail! 409 "同一天跨项目累计工时不能超过24小时"))
        (q :finance/insert-time! entry)
        (dto (q :finance/time entry))))))

(defn review!
  "由指定且不同于提交者的审核人决定工时, 驳回释放预留容量."
  [svc actor project-id entry-id body]
  (rules/object! body [:version :decision :reason])
  (when-not (contains? #{"approved" "rejected"} (:decision body)) (rules/fail! 400 "无效审核决定"))
  (kernel/mutate! svc actor project-id "pms:time:approve" body "time.reviewed" {:write? false}
    (fn [q project]
      (let [entry (q :finance/time {:project_id project-id :entry_id entry-id})
            reason (rules/text! (:reason body) "审核意见" 1000 (= "rejected" (:decision body)))]
        (when-not entry (rules/fail! 404 "工时单不存在"))
        (when-not (= "submitted" (:status entry)) (rules/fail! 409 "工时单已处理"))
        (kernel/independent-review! actor (:submitted_by entry) (:reviewer_id entry))
        (kernel/user! q project (:reviewer_id entry) "工时审核人")
        (rules/changed! (q :finance/review-time! (assoc entry :status (:decision body) :review_note reason)))
        (when (= "rejected" (:decision body)) (rules/changed! (q :finance/release-day! entry)))
        (dto (q :finance/time entry))))))
