(ns com.ruoyi.domain.pms.governance.gates
  "关口模板,指定版本证据,独立签核及生命周期前置检查."
  (:require [clojure.string :as str]
            [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.config.catalog :as catalog]
            [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn create-template!
  "登记不可变的项目关口模板: 类型, 适用阶段, 阻断检查点与检查项 (含发布版本要求)."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate-template.created"
    (fn [q project]
      (s/input! body [:code :title :stage :required :checks :gate_type :blocks])
      (let [template (config/gate-template! (dissoc body :version))]
        (when (some #(= (:code template) (:code %)) (s/records q project "gate-template"))
          (r/fail! 409 "关口模板编号已存在"))
        (s/insert! q project actor "gate-template" template {:status "registered"})))))

(defn from-catalog!
  "按原蓝图关口目录一键建立项目内 Gate 模板 (B06-B15 模板候选, 适用范围待业务签收)."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate-template.created"
    (fn [q project]
      (s/input! body [:gate_type :required])
      (let [item (or (catalog/gate-type (:gate_type body)) (r/fail! 404 "关口目录项不存在"))
            template (config/gate-template! {:code (str "GT-" (str/upper-case (:gate_type item)))
                                             :title (:title item) :gate_type (:gate_type item) :stage (:stage item)
                                             :required (if (false? (:required body)) false true)
                                             :blocks (or (:blocks item) []) :checks (:checks item)})]
        (when (some #(= (:code template) (:code %)) (s/records q project "gate-template"))
          (r/fail! 409 "该类型关口模板已建立"))
        (s/insert! q project actor "gate-template" (assoc template :source "catalog") {:status "registered"})))))

(defn create!
  "从确定模板创建关口实例并绑定独立审核人."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.created"
    (fn [q project]
      (s/input! body [:template_id :title :reviewer_id])
      (let [template (s/record! q project "gate-template" (:template_id body))]
        (s/insert! q project actor "gate"
                   {:title (s/text! body :title 200) :template_id (:id template)
                    :stage (:stage template) :required (:required template)
                    :gate_type (:gate_type template "generic")
                    :reviewer_id (s/reviewer! q project actor (:reviewer_id body))
                    :checks (mapv #(assoc % :passed false :evidence_ids []) (:checks template))}
                   {})))))

(defn- completed-checks!
  "将检查结果绑定模板原有项目和不可变文档证据."
  [q project gate checks]
  (when-not (and (vector? checks) (= (count checks) (count (:checks gate))))
    (r/fail! 400 "必须提交模板的全部检查项"))
  (when-not (= (set (map :code checks)) (set (map :code (:checks gate))))
    (r/fail! 400 "检查项编码与模板不一致"))
  (let [results (into {} (map (juxt :code identity) checks))]
    (mapv (fn [item]
            (let [result (get results (:code item))
                  waived (boolean (:waived result))]
              (r/object! result [:code :passed :evidence_ids :waived :waiver_reason])
              (when (and (contains? result :waived) (not (boolean? (:waived result)))) (r/fail! 400 "waived必须为布尔值"))
              (when (and waived (s/boolean! (:passed result) "passed")) (r/fail! 400 (str "检查项 " (:code item) " 已通过, 无需例外")))
              (cond-> (assoc item :passed (s/boolean! (:passed result) "passed")
                             :evidence_ids (s/evidence! q project (or (:evidence_ids result) []) false)
                             :waived waived)
                waived (assoc :waiver_reason (r/text! (:waiver_reason result) "例外说明" 500 true))
                (not waived) (dissoc :waiver_reason))))
          (:checks gate))))

(defn checks!
  "编辑未签核关口的检查结果,保留模板必需性不被客户端覆盖."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.checked"
    (fn [q project]
      (s/input! body [:checks])
      (let [gate (s/record! q project "gate" rid)]
        (s/status! gate #{"draft" "ready" "rejected"})
        (s/change! q project gate "ready"
                   {:checks (completed-checks! q project gate (:checks body))})))))

(defn- evidence-ready!
  "所有必需项须通过并提供确定文档版本; 声明 require_released 的检查项其证据须已经独立发布 (C06 发布链联动)."
  [q project gate]
  (doseq [check (:checks gate) :when (:required check)]
    (when-not (or (:passed check) (:waived check)) (r/fail! 409 (str "必需检查未通过: " (:code check))))
    (when (:waived check) (when-not (seq (:waiver_reason check)) (r/fail! 409 (str "例外检查项缺少说明: " (:code check)))))
    (when (:passed check) (s/evidence! q project (:evidence_ids check) true))
    (when (:require_released check)
      (doseq [id (:evidence_ids check)]
        (when-not (= "approved" (:status (s/record! q project "document" id)))
          (r/fail! 409 (str "检查项 " (:code check) " 引用的证据尚未发布"))))))
  true)

(defn checkpoint-ready!
  "阻断型关口: 声明了该交付检查点的模板必须已有通过或豁免的实例, 否则拒绝后续交付命令."
  [q project checkpoint]
  (doseq [template (filter #(some #{checkpoint} (:blocks %)) (s/records q project "gate-template"))]
    (let [instances (filter #(= (:id template) (:template_id %)) (s/records q project "gate"))]
      (when-not (some #(contains? #{"approved" "waived"} (:status %)) instances)
        (r/fail! 409 (str "阻断关口尚未通过: " (:title template))))))
  true)

(defn gate-progress
  "只读汇总各关口模板的实例进展与检查项通过数, 供汇总 Gate 下钻 (B09/B10)."
  [templates gates]
  (mapv (fn [template]
          (let [instances (filter #(= (:id template) (:template_id %)) gates)
                latest (last (sort-by :created_at instances))
                checks (:checks latest)]
            {:template_id (:id template) :code (:code template) :title (:title template)
             :gate_type (:gate_type template "generic") :stage (:stage template) :blocks (:blocks template [])
             :required (:required template) :instance_count (count instances)
             :status (if latest (:status latest) "not_started")
             :passed_checks (count (filter #(or (:passed %) (:waived %)) checks)) :waived_checks (count (filter :waived checks))
             :total_checks (count (:checks template))
             :passed (boolean (some #(contains? #{"approved" "waived"} (:status %)) instances))}))
        templates))

(defn submit!
  "提交关口审核,允许完整检查或有理由的豁免申请."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:project:edit" body "gate.submitted"
    (fn [q project]
      (s/input! body [:waiver_reason])
      (let [gate (s/record! q project "gate" rid)
            waiver (s/optional-text! body :waiver_reason 2000)]
        (s/status! gate #{"draft" "ready" "rejected"})
        (s/reviewer! q project actor (:reviewer_id gate))
        (when (empty? waiver) (evidence-ready! q project gate))
        (s/change! q project gate "in_review"
                   {:submitted_by (:user_id actor) :waiver_reason waiver})))))

(defn decide!
  "指定审核人批准,拒绝或带充分理由豁免关口."
  [svc actor id rid body]
  (k/mutate! svc actor id "pms:quality:approve" body "gate.decided" {:write? false}
    (fn [q project]
      (s/input! body [:decision :reason])
      (let [gate (s/record! q project "gate" rid)
            decision (s/enum! (:decision body) #{"approved" "rejected" "waived"} "decision")
            reason (s/text! body :reason)]
        (s/status! gate #{"in_review"})
        (s/decision-actor! actor gate)
        (when (= decision "approved") (evidence-ready! q project gate))
        (when (and (= decision "waived") (empty? (:waiver_reason gate)))
          (r/fail! 409 "未提出豁免申请,不能直接豁免"))
        (s/change! q project gate decision {:decision_reason reason :decided_by (:user_id actor)})))))

(defn- stage-ready!
  "要求必需关口模板存在且全部实例已通过或被正式豁免."
  [q project stage]
  (let [templates (filter #(and (= stage (:stage %)) (:required %)) (s/records q project "gate-template"))
        gates (filter #(= stage (:stage %)) (s/records q project "gate"))]
    (when (empty? templates) (r/fail! 409 "尚未配置该阶段必需关口模板"))
    (doseq [template templates]
      (let [instances (filter #(= (:id template) (:template_id %)) gates)]
        (when (or (empty? instances) (some #(not (contains? #{"approved" "waived"} (:status %))) instances))
          (r/fail! 409 (str "必需关口尚未通过: " (:title template))))))
    true))

(defn execution-ready!
  "执行开始需要当前正式章程及所有必需执行关口通过."
  [q project]
  (let [charter (first (s/latest (s/records q project "charter")))]
    (when-not (= "approved" (:status charter)) (r/fail! 409 "当前项目章程尚未独立批准"))
    (stage-ready! q project "execution")))

(defn closure-ready!
  "收尾需要必需验收关口通过且无未关闭的阻塞问题."
  [q project]
  (when (some #(and (= "blocker" (:severity %)) (not= "closed" (:status %)))
              (s/records q project "issue"))
    (r/fail! 409 "仍有未关闭的阻塞问题"))
  (stage-ready! q project "closure"))
