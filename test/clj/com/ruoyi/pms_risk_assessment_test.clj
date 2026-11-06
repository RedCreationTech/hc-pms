(ns com.ruoyi.pms-risk-assessment-test
  "com.ruoyi.domain.pms.governance.risk-assessment 纯函数与常量的表征测试: 锁定 score!, escalation-level, escalation-reason, assessment, review-cadence-days 及阈值/频率常量的行为, 作为 ub-mode 后续重构登记与复评重评分路径的行为保持门."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.domain.pms.governance.risk-assessment :as ra]))

(defn- err-data [f]
  (try (f) :no-throw (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest constants-pin-threshold-and-frequency-vocabulary
  (is (= 16 ra/escalation-threshold))
  (is (= #{"weekly" "biweekly" "monthly" "quarterly"} ra/review-frequencies))
  (is (= {"weekly" 7 "biweekly" 14 "monthly" 30 "quarterly" 90} ra/review-frequency-days)))

(deftest score!-accepts-1..5-integers-only
  (is (= 1 (ra/score! 1)))
  (is (= 5 (ra/score! 5)))
  (testing "越界, 非整数, nil 均 400"
    (is (= 400 (:status (err-data #(ra/score! 0)))))
    (is (= 400 (:status (err-data #(ra/score! 6)))))
    (is (= 400 (:status (err-data #(ra/score! 2.5)))))
    (is (= 400 (:status (err-data #(ra/score! nil)))))))

(deftest escalation-level-branches-on-score
  (is (= "steering" (ra/escalation-level 25)))
  (is (= "steering" (ra/escalation-level 20)))
  (is (= "management" (ra/escalation-level 19)))
  (is (= "management" (ra/escalation-level 16)))
  (is (nil? (ra/escalation-level 15)))
  (is (nil? (ra/escalation-level 1))))

(deftest escalation-reason-renders-level-wording
  (let [s (ra/escalation-reason 20 "steering")]
    (is (string? s))
    (is (str/includes? s "20"))
    (is (str/includes? s "管理层")))
  (let [m (ra/escalation-reason 16 "management")]
    (is (str/includes? m "经理层"))
    (is (str/includes? m "16"))))

(deftest assessment-derives-score-and-gates-escalation-keys
  (testing "未达阈值不写任何 escalation 键"
    (let [a (ra/assessment 3 3)]
      (is (= {:probability 3 :impact 3 :score 9 :escalated false} a))
      (is (not (contains? a :escalation_state)))))
  (testing "management 档 (>=16 且 <20) 附 pending 升级"
    (let [a (ra/assessment 4 4)]
      (is (= 16 (:score a)))
      (is (:escalated a))
      (is (= "pending" (:escalation_state a)))
      (is (= "management" (:escalation_level a)))
      (is (string? (:escalation_reason a)))))
  (testing "steering 档 (>=20)"
    (let [a (ra/assessment 5 5)]
      (is (= 25 (:score a)))
      (is (= "steering" (:escalation_level a))))))

(deftest review-cadence-days-maps-declared-frequency
  (is (= 7 (ra/review-cadence-days "weekly")))
  (is (= 14 (ra/review-cadence-days "biweekly")))
  (is (= 30 (ra/review-cadence-days "monthly")))
  (is (= 90 (ra/review-cadence-days "quarterly")))
  (is (nil? (ra/review-cadence-days nil)))
  (is (nil? (ra/review-cadence-days "bogus"))))
