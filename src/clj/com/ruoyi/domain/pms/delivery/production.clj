(ns com.ruoyi.domain.pms.delivery.production
  "装配交检及SIT,FAT,SAT的受控试验,失败整改和独立确认."
  (:require [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.gates :as gates]
            [com.ruoyi.domain.pms.governance.store :as g]
            [com.ruoyi.domain.pms.rules :as r]))



(defn create-assembly!
  "建立基于实际齐套BOM的装配任务记录."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.assembly.created" false
    (fn [q project]
      (g/input! body [:code :title :bom_id :owner_id :task_id :requirement_ids])
      (let [bom (d/record! q project "bom" (:bom_id body))]
        (g/status! bom #{"ready"})
        (d/insert! q project actor "assembly"
                   (merge (d/references! q project body)
                          {:code (g/text! body :code 100) :title (g/text! body :title 200)
                           :bom_id (:id bom) :owner_id (d/owner! q project (:owner_id body))}) "draft")))))



(defn start!
  "齐套条件仍满足时登记装配开工或退回返工."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.assembly.started" false
    (fn [q project]
      (g/input! body [:evidence_ids])
      (d/execution! project)
      (let [assembly (d/record! q project "assembly" rid)]
        (g/status! assembly #{"draft" "rejected"})
        (g/status! (d/record! q project "bom" (:bom_id assembly)) #{"ready"})
        (gates/checkpoint-ready! q project "assembly.start")
        (d/change! q project assembly "in_progress"
                   {:started_by (:user_id actor) :start_evidence_ids (g/evidence! q project (:evidence_ids body) true)})))))



(defn submit-assembly!
  "提交完成装配的真实交检证据."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.assembly.submitted" false
    (fn [q project]
      (g/input! body [:reviewer_id :evidence_ids])
      (d/execution! project)
      (let [record (d/record! q project "assembly" rid)]
        (g/status! record #{"in_progress"})
        (d/submit! q project actor record body)))))



(defn decide-assembly!
  "独立确认交检结果,拒绝后须重新开工和提交."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.assembly.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (d/decide! q project actor (d/record! q project "assembly" rid) body "approved"))))



(defn- criteria!
  "定义唯一且至少含一项必检的试验验收准则."
  [criteria]
  (when-not (and (vector? criteria) (<= 1 (count criteria) 30))
    (r/fail! 400 "试验准则需要1到30项"))
  (let [checks (mapv (fn [criterion]
                       (r/object! criterion [:code :title :required])
                       {:code (g/text! criterion :code 50) :title (g/text! criterion :title 200)
                        :required (g/boolean! (:required criterion) "required")}) criteria)]
    (when (or (not-any? :required checks) (not= (count checks) (count (set (map :code checks)))))
      (r/fail! 400 "试验准则必须有必检项且编码不重复"))
    checks))



(defn create-test!
  "预先登记试验准则及范围,结果与批准在后续明确命令中完成."
  [svc actor id _ body]
  (d/mutate! svc actor id body "delivery.test.created" false
    (fn [q project]
      (g/input! body [:code :title :assembly_id :test_type :owner_id :criteria :task_id :requirement_ids])
      (d/record! q project "assembly" (:assembly_id body))
      (d/insert! q project actor "test"
                 (merge (d/references! q project body)
                        {:code (g/text! body :code 100) :title (g/text! body :title 200)
                         :assembly_id (:assembly_id body)
                         :test_type (g/enum! (:test_type body) #{"SIT" "FAT" "SAT"} "test_type")
                         :owner_id (d/owner! q project (:owner_id body))
                         :criteria (criteria! (:criteria body))}) "draft"))))



(defn- latest-test
  "按创建时聚合版本取指定装配及类型的当前试验."
  [q project assembly-id type]
  (let [rows (filter #(and (= assembly-id (:assembly_id %)) (= type (:test_type %))) (d/records q project "test"))]
    (when (seq rows) (apply max-key #(or (:created_project_version %) 0) rows))))


(defn- preceding-types
  "按项目适用配置确定SIT到FAT到SAT的前序."
  [q project type]
  (filterv (set (:required_test_types (d/configuration q project)))
           (case type "FAT" ["SIT"] "SAT" ["SIT" "FAT"] [])))


(defn type-approved?
  "当前试验须已批准且仍引用当前已批准的前序试验版本."
  [q project assembly-id type]
  (let [latest (latest-test q project assembly-id type)
        preceding (preceding-types q project type)
        ids (into {} (for [pre preceding] [(keyword pre) (:id (latest-test q project assembly-id pre))]))]
    (and (= "approved" (:status latest))
         (= ids (or (:prerequisite_test_ids latest) {}))
         (every? #(type-approved? q project assembly-id %) preceding))))


(defn- prerequisites!
  "记录实际结果前要求装配及适用前序通过,SAT还要求实际接收."
  [q project test]
  (g/status! (d/record! q project "assembly" (:assembly_id test)) #{"approved"})
  (let [preceding (preceding-types q project (:test_type test))]
    (doseq [type preceding]
      (when-not (type-approved? q project (:assembly_id test) type)
        (r/fail! 409 (str "前序试验尚未有效批准: " type))))
    (when (and (= "SAT" (:test_type test))
               (not-any? #(and (some #{(:assembly_id test)} (:assembly_ids %))
                               (contains? #{"received" "conditional"} (:status %))) (d/records q project "shipment")))
      (r/fail! 409 "SAT现场试验须有已接收的实际发运记录"))
    (into {} (for [type preceding] [(keyword type) (:id (latest-test q project (:assembly_id test) type))]))))


(defn- failure-issue!
  "对必检失败创建阻塞整改问题并保留试验和准则来源."
  [q project actor test criterion due-date]
  (g/insert! q project actor "issue"
             {:title (str "试验不合格: " (:title test) " / " (:title criterion))
              :severity "blocker" :owner_id (:owner_id test) :due_date due-date
              :source_test_id (:id test) :source_criterion_code (:code criterion)} {:status "open"}))



(defn- results!
  "完整校验结果,固定证据版本并为失败项生成一次整改链."
  [q project actor test body]
  (let [checks (:checks body) criteria (:criteria test)
        previous (into {} (map (juxt :code identity) (:checks test)))
        due-date (g/date! body :due_date)]
    (when-not (and (vector? checks) (= (count checks) (count criteria))
                  (= (set (map :code checks)) (set (map :code criteria))))
      (r/fail! 400 "必须完整提交全部试验准则结果"))
    (let [by-code (into {} (map (juxt :code identity) checks))]
      (mapv (fn [criterion]
              (let [result (get by-code (:code criterion))]
                (r/object! result [:code :passed :actual :evidence_ids])
                (let [passed (g/boolean! (:passed result) "passed")
                      old-issue (:issue_id (get previous (:code criterion)))
                      issue-id (or old-issue
                                   (when (and (:required criterion) (not passed))
                                     (:id (failure-issue! q project actor test criterion due-date))))]
                  (cond-> (assoc criterion :passed passed :actual (g/text! result :actual)
                                 :evidence_ids (g/evidence! q project (:evidence_ids result) true))
                    issue-id (assoc :issue_id issue-id))))) criteria))))



(defn record-results!
  "实际试验反馈和自动整改同事务保存,复验保留历次结果."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.test.results-recorded" false
    (fn [q project]
      (g/input! body [:checks :due_date])
      (d/execution! project)
      (let [test (d/record! q project "test" rid)]
        (g/status! test #{"draft" "ready" "rejected"})
        (gates/checkpoint-ready! q project (str "test." (:test_type test)))
        (when (>= (count (:result_history test)) 100) (r/fail! 409 "复验次数已达100,请创建新的受控试验记录"))
        (let [prerequisites (prerequisites! q project test)
              checks (results! q project actor test body)]
          (d/change! q project test "ready"
                     {:checks checks :prerequisite_test_ids prerequisites
                      :result_history (conj (vec (:result_history test))
                                            {:recorded_by (:user_id actor) :project_version (inc (:version project))
                                             :checks checks :prerequisite_test_ids prerequisites
                                             :recorded_on (str (java.time.LocalDate/now))})}))))))



(defn ready-test!
  "试验签核前要求装配通过,全部必检通过和已关联整改独立关闭."
  [q project test]
  (when-not (= (prerequisites! q project test) (:prerequisite_test_ids test))
    (r/fail! 409 "前序试验版本已经变化,请重新记录实际复验结果"))
  (when-not (= (count (:criteria test)) (count (:checks test))) (r/fail! 409 "试验结果尚未完整登记"))
  (doseq [check (:checks test)]
    (when (and (:required check) (not (:passed check))) (r/fail! 409 "仍有必检项未通过"))
    (g/evidence! q project (:evidence_ids check) true)
    (when-let [issue-id (:issue_id check)]
      (g/status! (g/record! q project "issue" issue-id) #{"closed"})))
  true)



(defn submit-test!
  "试验条件全部满足后提交独立检验."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.test.submitted" false
    (fn [q project]
      (g/input! body [:reviewer_id :evidence_ids])
      (d/execution! project)
      (let [test (d/record! q project "test" rid)]
        (g/status! test #{"ready" "rejected"})
        (ready-test! q project test)
        (d/submit! q project actor test body)))))



(defn decide-test!
  "指定独立检验人再次校验证据与整改后签核或退回."
  [svc actor id rid body]
  (d/mutate! svc actor id body "delivery.test.decided" true
    (fn [q project]
      (g/input! body [:decision :reason])
      (let [test (d/record! q project "test" rid)]
        (when (= "approved" (:decision body)) (ready-test! q project test))
        (d/decide! q project actor test body "approved")))))
