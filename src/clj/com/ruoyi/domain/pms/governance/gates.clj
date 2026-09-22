(ns com.ruoyi.domain.pms.governance.gates
  "关口模板,指定版本证据,独立签核及生命周期前置检查."
  (:require [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r]))

(defn- template-checks!
  "校验关口模板的非空且唯一检查项."
  [checks]
  (when-not (and (vector? checks) (<= 1 (count checks) 30))
    (r/fail! 400 "关口模板需要1到30个检查项"))
  (let [result (mapv (fn [check]
                       (r/object! check [:code :title :required])
                       {:code (s/text! check :code 50) :title (s/text! check :title 200)
                        :required (s/boolean! (:required check) "required")}) checks)]
    (when-not (= (count result) (count (set (map :code result))))
      (r/fail! 400 "关口检查项编码重复"))
    result))

(defn create-template!
  "登记不可变的项目关口模板和适用阶段."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "gate-template.created"
    (fn [q project]
      (s/input! body [:code :title :stage :required :checks])
      (let [checks (template-checks! (:checks body))]
        (when (and (:required body) (not-any? :required checks))
          (r/fail! 400 "必需关口模板至少应有一个必需检查项")))
      (s/insert! q project actor "gate-template"
                 {:code (s/text! body :code 100) :title (s/text! body :title 200)
                  :stage (s/enum! (:stage body) #{"execution" "closure"} "stage")
                  :required (s/boolean! (:required body) "required")
                  :checks (template-checks! (:checks body))} {:status "registered"}))))

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
            (let [result (get results (:code item))]
              (r/object! result [:code :passed :evidence_ids])
              (assoc item :passed (s/boolean! (:passed result) "passed")
                     :evidence_ids (s/evidence! q project (:evidence_ids result) false))))
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
  "所有必需项须通过并提供确定文档版本."
  [q project gate]
  (doseq [check (:checks gate) :when (:required check)]
    (when-not (:passed check) (r/fail! 409 (str "必需检查未通过: " (:code check))))
    (s/evidence! q project (:evidence_ids check) true))
  true)

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
