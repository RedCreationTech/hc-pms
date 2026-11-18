(ns com.ruoyi.domain.pms.governance.remediation
  "关口 (gate) 与 DQ 两条治理命令链共用的整改行动建立:
   同一套未通过必需检查项筛选, 目标选择与行动落库, 仅以领域差异 spec 区分.
   放在独立命名空间而不是任一领域内, 避免为共用而制造互相 require."
  (:require
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r]))


(def gate-spec
  "关口整改的领域差异: 状态门, 检查项取键与空失败文案."
  {:kind "gate" :statuses #{"draft" "ready" "rejected"} :checks-key :checks
   :empty-message "该关口没有未通过的必需检查项, 无需整改"
   :event "gate.remediation-action-created"
   :source-k :source_gate_id
   :owner-default :reviewer_id
   :required [:title :owner_id :due_date :check_code]
   :bulk-required [:owner_id :due_date]
   :bulk-event "gate.remediation-actions-created"
   :description "把关口未通过的必需检查项落实为可追踪的整改行动项: 复用行动类型与既有完成/独立验证/转任务生命周期, 记录来源关口与所选未通过必需检查项编码, 缺省沿用关口审核人为负责人; 需项目编辑权限, 仅对尚未签核(draft/ready/rejected)且确有未通过必需检查项的关口实例开放."})


(def dq-spec
  "DQ 整改的领域差异: 状态门, 检查项取键与空失败文案."
  {:kind "dq" :statuses #{"draft" "rejected"} :checks-key :checklist
   :empty-message "该 DQ 没有未通过的必需检查项, 无需整改"
   :event "dq.remediation-action-created"
   :source-k :source_dq_id
   :owner-default :owner_id
   :required [:title :owner_id :due_date :check_code]
   :bulk-required [:owner_id :due_date]
   :bulk-event "dq.remediation-actions-created"
   :description "把 DQ 未通过的必需检查项落实为可追踪的整改行动项: 复用行动类型与既有完成/独立验证/转任务生命周期, 记录来源 DQ 与所选未通过必需检查项编码, 缺省沿用 DQ 责任人; 需项目编辑权限, 仅对草稿或已退回且确有未通过必需检查项的 DQ 开放."})


;; 命令表按 (svc actor id rid body) 调用, 故各领域入口以 spec 作首参部分应用:
;; 下面的 create!/create-all! 显式接收 spec, 由 gate/dq 入口用 partial 绑定.

(defn- insert!
  [q project actor {:keys [source-k owner-default]} rid body record target given]
  (s/insert-remediation! q project actor body record
                         {:source-k source-k :owner-default owner-default
                          :rid rid :check-code (:code target)
                          :given given
                          :base-title (str (:title record) " / " (:title target))}))


(defn create!
  "按领域 spec 把指定 (缺省为唯一) 未通过的必需检查项落实为一条 open 整改行动; 唯一实现, 两个入口共用."
  [spec svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body (:event spec)
             (fn [q project]
               (s/input! body (:required spec))
               (let [[record failing] (s/failing-checks! q project rid spec)
                     target (s/remediation-target failing (not-empty (s/optional-text! body :check_code 50)))]
                 (insert! q project actor spec rid body record target
                          (s/optional-text! body :title 200))))))


(defn create-all!
  "按领域 spec 把全部未通过的必需检查项各落实为一条 open 整改行动; 与 create! 共用同一 spec 与落库口径."
  [spec svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body (:bulk-event spec)
             (fn [q project]
               (s/input! body (:bulk-required spec))
               (let [[record failing] (s/failing-checks! q project rid spec)]
                 (mapv #(insert! q project actor spec rid body record % nil) failing)))))
