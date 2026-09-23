(ns com.ruoyi.domain.pms.governance.risk-assessment
  "风险概率 x 影响评分与超阈值升级判定的纯函数与常量, 供登记与复评重新评分共用; 不依赖其它治理命名空间以避免循环引用."
  (:require [com.ruoyi.domain.pms.rules :as r]))


(def escalation-threshold
  "风险评分(概率 x 影响, 1 到 25)达到该值即自动升级, 须独立质量审批人确认处置后方可缓解."
  16)


(defn score!
  "校验风险概率或影响等级为1到5."
  [value]
  (when-not (and (integer? value) (<= 1 value 5)) (r/fail! 400 "概率与影响必须为1到5整数"))
  value)


(defn escalation-level
  "按评分划分升级处置层级, 未达阈值返回 nil."
  [score]
  (cond (>= score 20) "steering"
        (>= score escalation-threshold) "management"
        :else nil))


(defn escalation-reason
  "生成可读升级依据, 说明触发阈值与所需独立确认层级."
  [score level]
  (str "风险评分 " score " 已达到升级阈值 " escalation-threshold ", 须由独立质量审批人确认"
       (case level "steering" "管理层" "经理层")
       "处置后方可缓解."))


(defn assessment
  "给定已通过 score! 的概率与影响, 返回规范化评分与升级字段; 达阈值附 pending 升级, 未达阈值不写任何 escalation 键 (与登记路径口径一致)."
  [probability impact]
  (let [score (* probability impact)
        escalated? (>= score escalation-threshold)
        level (escalation-level score)]
    (cond-> {:probability probability :impact impact :score score :escalated escalated?}
      escalated? (assoc :escalation_state "pending" :escalation_level level
                        :escalation_reason (escalation-reason score level)))))
