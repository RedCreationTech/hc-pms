(ns com.ruoyi.domain.pms.finance-budget
  "H12 预算控制: 承诺提交时按启用规则评估预算占用率并给出 warn/require_approval/block 决定."
  (:require [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules]))

(def baselines
  "允许配置控制规则的费用基线口径, estimate 指概算, budget 指预算."
  #{"estimate" "budget"})

(def actions
  "预算控制动作枚举: 提醒, 需上级审批, 阻断."
  #{"warn" "require_approval" "block"})

(defn rule-dto
  "把预算控制规则行转换为前端可读结构, 布尔化 enabled."
  [row]
  (-> row
      (assoc :id (:rule_id row))
      (update :enabled #(= 1 (int %)))))

(defn active-rules
  "读取指定项目可见的启用预算控制规则, 项目层优先, 系统默认在后."
  [q project-id]
  (filterv :enabled (mapv rule-dto (q :finance/budget-rules {:project_id project-id}))))

(defn- latest-approved
  "按口径读取最新已批准费用版本及其本位总金额."
  [q project-id baseline]
  (when-let [row (q :finance/latest-approved-total {:project_id project-id :kind baseline})]
    (assoc (select-keys row [:version_id :currency :total_minor])
           :id (:version_id row)
           :kind baseline)))

(defn evaluate
  "给定承诺基线口径和本次本位金额, 汇总预算, 实际, 已承诺, 计算占用率, 返回决策上下文."
  [q project-id baseline committed-this]
  (let [budget (latest-approved q project-id baseline)
        actual (latest-approved q project-id "actual")
        consumed (long (or (:committed_minor (q :finance/commitment-consumed {:project_id project-id})) 0))
        projected (+ consumed committed-this)
        budget-total (when-let [t (:total_minor budget)] (long t))
        ratio (when (and budget-total (pos? budget-total))
                (long (Math/round (double (/ (* 100 projected) budget-total)))))
        rules (active-rules q project-id)
        triggered (filterv (fn [r] (and (= baseline (:baseline r))
                                        (some? ratio)
                                        (>= ratio (:threshold_pct r)))) rules)]
    {:baseline baseline
     :comparable (boolean (and budget-total (pos? budget-total)))
     :currency (:currency budget)
     :budget (select-keys budget [:id :kind :total_minor :currency])
     :actual (select-keys actual [:id :kind :total_minor :currency])
     :consumed_minor consumed
     :committed_this committed-this
     :projected_minor projected
     :remaining_minor (when budget-total (- budget-total projected))
     :ratio_pct ratio
     :triggered (mapv (fn [r] (select-keys r [:rule_id :threshold_pct :action])) triggered)
     :decision (cond
                 (some #(= "block" (:action %)) triggered) :block
                 (some #(= "require_approval" (:action %)) triggered) :require_approval
                 (seq triggered) :warn
                 :else :ok)}))

(defn summary
  "把评估结果写成一行简短中文摘要供 control_note 保存."
  [{:keys [baseline comparable ratio_pct consumed_minor projected_minor decision] :as _eval}]
  (str "基线=" baseline
       (if comparable
         (str ", 已承诺=" (money/money consumed_minor)
              ", 本次后=" (money/money projected_minor)
              ", 占用率=" ratio_pct "%")
         ", 缺少可比预算基线")
       ", 决定=" (name decision)))

(defn list-rules
  "读取项目层与系统默认层的全部预算控制规则, 含已停用."
  [q project-id]
  (mapv rule-dto (q :finance/budget-rules {:project_id project-id})))

(defn- new-rule!
  "新增一条预算控制规则, 校验输入合法性."
  [q actor project body]
  (rules/object! body [:baseline :threshold_pct :action :enabled :note :project_scoped :version])
  (when-not (baselines (:baseline body)) (rules/fail! 400 "基线口径只能为 estimate 或 budget"))
  (when-not (actions (:action body)) (rules/fail! 400 "动作只能为 warn, require_approval 或 block"))
  (let [threshold (rules/positive-id! (:threshold_pct body) "阈值")]
    (when-not (<= 5 threshold 500) (rules/fail! 400 "阈值必须在 5%-500% 之间"))
    (let [input {:rule_id (kernel/id)
                 :project_id (when (true? (:project_scoped body)) (:project_id project))
                 :baseline (:baseline body)
                 :threshold_pct (int threshold)
                 :action (:action body)
                 :enabled (if (false? (:enabled body)) 0 1)
                 :note (rules/text! (:note body) "备注" 1000 false)
                 :created_by (:user_id actor)}]
      (q :finance/insert-budget-rule! input)
      (rule-dto (q :finance/budget-rule {:rule_id (:rule_id input)})))))

(defn- existing-rule!
  "在授权范围内更新已有规则."
  [q project-id body]
  (let [existing-id (:rule_id body)
        existing (q :finance/budget-rule {:rule_id existing-id})]
    (when-not existing (rules/fail! 404 "预算控制规则不存在"))
    (when-not (or (nil? (:project_id existing))
                  (= (:project_id existing) project-id))
      (rules/fail! 403 "只能修改本项目或系统默认规则"))
    (rules/object! body [:rule_id :threshold_pct :action :enabled :note :version :project_scoped])
    (let [threshold (rules/positive-id! (:threshold_pct body) "阈值")]
      (when-not (<= 5 threshold 500) (rules/fail! 400 "阈值必须在 5%-500% 之间")))
    (when-not (actions (:action body)) (rules/fail! 400 "无效动作"))
    (rules/changed!
     (q :finance/update-budget-rule!
        {:rule_id existing-id
         :threshold_pct (int (:threshold_pct body))
         :action (:action body)
         :enabled (if (false? (:enabled body)) 0 1)
         :note (rules/text! (:note body) "备注" 1000 false)}))
    (rule-dto (q :finance/budget-rule {:rule_id existing-id}))))

(defn upsert-rule!
  "新增或修改预算控制规则; body 携带 rule_id 则更新现规则, 否则新建."
  [svc actor project-id body]
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "budget.rule.upserted"
    (fn [q project]
      (if (:rule_id body)
        (existing-rule! q project-id body)
        (new-rule! q actor project body)))))

(defn disable-rule!
  "停用指定预算控制规则, 保留历史."
  [svc actor project-id rule-id body]
  (rules/object! body [:version :reason])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "budget.rule.disabled"
    (fn [q _]
      (let [existing (q :finance/budget-rule {:rule_id rule-id})]
        (when-not existing (rules/fail! 404 "预算控制规则不存在"))
        (when-not (or (nil? (:project_id existing))
                      (= (:project_id existing) project-id))
          (rules/fail! 403 "只能停用本项目或系统默认规则"))
        (rules/changed!
         (q :finance/disable-budget-rule!
            {:rule_id rule-id
             :note (rules/text! (:reason body) "停用原因" 1000 true)}))
        (rule-dto (q :finance/budget-rule {:rule_id rule-id}))))))
