(ns com.ruoyi.domain.pms.finance-commitment
  "H12 承诺成本台账: 合同/采购/人工/其他承诺登记, 预算门控提交, 独立审批, 部分或全额释放, 受控取消."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.ruoyi.domain.pms.finance-budget :as budget]
            [com.ruoyi.domain.pms.finance-money :as money]
            [com.ruoyi.domain.pms.kernel :as kernel]
            [com.ruoyi.domain.pms.rules :as rules]))

(def kinds
  "承诺登记允许的业务分类."
  #{"contract" "purchase" "labor" "other"})

(def statuses
  "承诺台账的合法状态集."
  #{"draft" "submitted" "approved" "rejected" "released" "cancelled"})

(defn- commitment!
  "读取指定承诺, 缺失返回404."
  [q project-id commitment-id]
  (or (q :finance/commitment {:project_id project-id :commitment_id commitment-id})
      (rules/fail! 404 "承诺不存在")))

(defn- draft!
  "只有草稿可以修改, 其他状态一律通过修订或作废重录."
  [row]
  (when-not (= "draft" (:status row))
    (rules/fail! 409 "承诺已提交或已处理, 不能修改"))
  row)

(defn dto
  "把承诺行转换为前端 DTO, 附带金额字符串与 id 别名."
  [row]
  (-> row
      (assoc :id (:commitment_id row)
             :gross (money/money (:gross_minor row))
             :base (money/money (:base_minor row))
             :released (money/money (:released_minor row))
             :remaining (money/money (- (:base_minor row) (:released_minor row))))
      (dissoc :snapshot_json)))

(defn- convert-base
  "按手工录入的汇率把原始金额折算为本位金额; 同币种直接返回."
  [gross-minor rate]
  (let [s (str/trim (or rate "1"))]
    (when-not (re-matches #"[0-9]{1,10}(\.[0-9]{1,6})?" s)
      (rules/fail! 400 "汇率必须为最多六位小数的正数"))
    (let [product (.movePointRight (.multiply (bigdec s)
                                              (.movePointLeft (bigdec gross-minor) 2)) 2)]
      (long (.setScale product 0 java.math.RoundingMode/HALF_UP)))))

(defn- input!
  "校验承诺输入并生成本位金额, 返回可插入结构."
  [q actor project body]
  (rules/object! body [:version :kind :code :supplier :currency :base_currency
                        :gross :exchange_rate :description :reviewer_id])
  (when-not (kinds (:kind body)) (rules/fail! 400 "承诺分类只能为合同/采购/人工/其他"))
  (when-not (money/currencies (:currency body)) (rules/fail! 400 "承诺币种不在支持范围"))
  (let [base-currency (or (:base_currency body) "CNY")]
    (when-not (money/currencies base-currency) (rules/fail! 400 "本位币种不在支持范围"))
    (let [gross (money/amount! (:gross body) "承诺金额")
          _ (when (neg? gross) (rules/fail! 400 "承诺金额不能为负"))
          _ (when (zero? gross) (rules/fail! 400 "承诺金额必须大于零"))
          rate (if (= (:currency body) base-currency) "1" (:exchange_rate body))
          base (if (= (:currency body) base-currency) gross (convert-base gross rate))
          reviewer (kernel/user! q project (:reviewer_id body) "承诺审批人")]
      (when (= reviewer (:user_id actor)) (rules/fail! 400 "审批人不能是提交人"))
      {:commitment_id (kernel/id)
       :project_id (:project_id project)
       :kind (:kind body)
       :code (rules/text! (:code body) "承诺编号" 100 true)
       :supplier (rules/text! (:supplier body) "相对方" 200 true)
       :currency (:currency body)
       :gross_minor gross
       :base_currency base-currency
       :base_minor base
       :exchange_rate (str/trim (or rate "1"))
       :description (rules/text! (:description body) "说明" 1000 false)
       :reviewer_id reviewer
       :submitted_by (:user_id actor)})))

(defn create!
  "为项目登记一条承诺草稿."
  [svc actor project-id body]
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "commitment.created"
    (fn [q project]
      (let [input (input! q actor project body)
            next-no (:next_no (q :finance/commitment-next-no
                                 {:project_id project-id :code (:code input)}))]
        (q :finance/insert-commitment! (assoc input :version_no (int next-no)))
        (dto (commitment! q project-id (:commitment_id input)))))))

(defn update-draft!
  "修改草稿承诺的字段, 保留版本号与编号."
  [svc actor project-id commitment-id body]
  (rules/object! body [:version :kind :supplier :currency :base_currency
                        :gross :exchange_rate :description :reviewer_id])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "commitment.draft.updated"
    (fn [q project]
      (let [existing (draft! (commitment! q project-id commitment-id))
            input (input! q actor project
                          (-> body
                              (assoc :code (:code existing))
                              (update :kind #(or % (:kind existing)))
                              (update :supplier #(or % (:supplier existing)))
                              (update :currency #(or % (:currency existing)))
                              (update :base_currency #(or % (:base_currency existing)))
                              (update :gross #(or % (:gross existing)))
                              (update :exchange_rate #(or % (:exchange_rate existing)))
                              (update :reviewer_id #(or % (:reviewer_id existing)))))]
        (when-not (= (:kind input) (:kind existing))
          (rules/fail! 400 "已登记承诺不能修改分类"))
        (rules/changed!
         (q :finance/update-commitment-draft!
            (assoc input :project_id project-id :commitment_id commitment-id)))
        (dto (commitment! q project-id commitment-id))))))

(defn- evaluate-and-gate!
  "在提交承诺前评估预算控制, 阻断时返回409, 否则返回评估上下文."
  [q project-id commitment baseline override?]
  (let [eval-result (budget/evaluate q project-id baseline (:base_minor commitment))]
    (if (and (= :block (:decision eval-result)) (not override?))
      (rules/fail! 409 (str "预算占用率 " (or (:ratio_pct eval-result) "-") "% 触发阻断规则, "
                            "需上级修改规则或勾选强制放行才能提交"))
      eval-result)))

(defn submit!
  "冻结承诺并按基线评估预算控制, 阻断时要求显式放行."
  [svc actor project-id commitment-id body]
  (rules/object! body [:version :baseline :override_block :reason])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "commitment.submitted"
    (fn [q _project]
      (let [existing (draft! (commitment! q project-id commitment-id))
            baseline (or (:baseline body) "budget")
            _ (when-not (budget/baselines baseline) (rules/fail! 400 "预算基线只能为 estimate 或 budget"))
            evaluation (evaluate-and-gate! q project-id existing baseline (true? (:override_block body)))
            snapshot (dto existing)
            reason (str/trim (or (:reason body) ""))
            control-note (if (empty? reason)
                             (budget/summary evaluation)
                             (str (budget/summary evaluation) " 备注: " reason))]
        (rules/changed!
         (q :finance/submit-commitment!
            {:project_id project-id
             :commitment_id commitment-id
             :submitted_by (:user_id actor)
             :snapshot_json (json/generate-string (assoc snapshot :budget_control evaluation))
             :control_note control-note}))
        (dto (commitment! q project-id commitment-id))))))

(defn review!
  "由指定审批人独立批准或驳回提交后的承诺."
  [svc actor project-id commitment-id body]
  (rules/object! body [:version :decision :reason])
  (when-not (contains? #{"approved" "rejected"} (:decision body))
    (rules/fail! 400 "无效审批决定"))
  (kernel/mutate! svc actor project-id "pms:finance:approve" body "commitment.reviewed" {:write? false}
    (fn [q _project]
      (let [existing (commitment! q project-id commitment-id)]
        (when-not (= "submitted" (:status existing))
          (rules/fail! 409 "承诺不在待审批状态"))
        (kernel/independent-review! actor (:submitted_by existing) (:reviewer_id existing))
        (rules/changed!
         (q :finance/review-commitment!
            {:project_id project-id
             :commitment_id commitment-id
             :status (:decision body)
             :review_note (rules/text! (:reason body) "审批意见" 1000
                                       (= "rejected" (:decision body)))}))
        (dto (commitment! q project-id commitment-id))))))

(defn release!
  "把已批准承诺按本位金额部分或全部转为实付, 保持剩余可追踪."
  [svc actor project-id commitment-id body]
  (rules/object! body [:version :amount])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "commitment.released"
    (fn [q _]
      (let [existing (commitment! q project-id commitment-id)]
        (when-not (= "approved" (:status existing))
          (rules/fail! 409 "只有已批准承诺可以转为实付"))
        (let [delta (money/amount! (:amount body) "释放金额")]
          (when (neg? delta) (rules/fail! 400 "释放金额必须为正"))
          (let [next-released (+ (:released_minor existing) delta)]
            (when (> next-released (:base_minor existing))
              (rules/fail! 409 "释放金额超出承诺剩余"))
            (rules/changed!
             (q :finance/release-commitment!
                {:project_id project-id
                 :commitment_id commitment-id
                 :released_minor next-released
                 :status (if (= next-released (:base_minor existing)) "released" "approved")}))
            (dto (commitment! q project-id commitment-id))))))))

(defn cancel!
  "受控取消草稿或被驳回的承诺, 已批准或已释放不允许直接取消."
  [svc actor project-id commitment-id body]
  (rules/object! body [:version :reason])
  (kernel/mutate! svc actor project-id "pms:finance:edit" body "commitment.cancelled"
    (fn [q _]
      (let [existing (commitment! q project-id commitment-id)]
        (when-not (contains? #{"draft" "rejected"} (:status existing))
          (rules/fail! 409 "只能取消草稿或被驳回的承诺"))
        (rules/changed!
         (q :finance/cancel-commitment!
            {:project_id project-id
             :commitment_id commitment-id
             :review_note (rules/text! (:reason body) "取消原因" 1000 true)}))
        (dto (commitment! q project-id commitment-id))))))

(defn list-all
  "读取项目全部承诺台账行, 已按时间倒序."
  [q project-id]
  (mapv dto (q :finance/commitments {:project_id project-id})))
