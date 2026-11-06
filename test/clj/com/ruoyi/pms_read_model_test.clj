(ns com.ruoyi.pms-read-model-test
  "com.ruoyi.domain.pms.read-model 纯函数原语的表征测试: 锁定 pct 与 distribution 的输出形状, 作为从 4 处同构治理分布克隆提取原语的行为保持门."
  (:require
    [clojure.test :refer [deftest is]]
    [com.ruoyi.domain.pms.read-model :as rm]))


(deftest pct-rounds-half-up-and-guards-zero-denominator
  (is (= 50 (rm/pct 2 1)))
  (is (= 33 (rm/pct 3 1)))               ; round(33.33)=33
  (is (= 67 (rm/pct 3 2)))               ; round(66.67)=67
  (is (= 20 (rm/pct 5 1)))
  (is (= 100 (rm/pct 5 5)))
  (is (= 0 (rm/pct 0 0)))                ; denom 非正恒 0, 不除零
  (is (= 0 (rm/pct 0 3)))
  (is (= 0 (rm/pct -1 3))))


(deftest distribution-empty-input
  (let [order ["a" "b" "c"]
        result (rm/distribution [] {:key-fn :kind
                                    :order order
                                    :labels {"a" "甲" "b" "乙" "c" "丙"}
                                    :value-k :kind
                                    :dominant-k :dominant-kind
                                    :by-k :by-kind})]
    (is (false? (:available result)))
    (is (= 0 (:total result)))
    (is (= 0 (:covered result)))
    (is (= 3 (:uncovered result)))
    (is (= 0 (:coverage-pct result)))
    (is (nil? (:dominant-kind result)))
    (is (= 0 (:dominant-count result)))
    (is (= [{:kind "a" :label "甲" :count 0 :pct 0}
            {:kind "b" :label "乙" :count 0 :pct 0}
            {:kind "c" :label "丙" :count 0 :pct 0}]
           (:by-kind result)))))


(deftest distribution-fixed-order-tally-with-unseen-zero-buckets
  ;; 未出现的档仍恒渲染为 0, 输出顺序固定不随 frequencies 漂移.
  (let [rows [{:kind "b"} {:kind "b"} {:kind "b"} {:kind "a"} {:kind "a"}]
        result (rm/distribution rows {:key-fn :kind
                                      :order ["a" "b" "c"]
                                      :labels {"a" "甲" "b" "乙" "c" "丙"}
                                      :value-k :kind
                                      :dominant-k :dominant-kind
                                      :by-k :by-kind})]
    (is (true? (:available result)))
    (is (= 5 (:total result)))
    (is (= 2 (:covered result)))          ; a 与 b 出现, c 未出现
    (is (= 1 (:uncovered result)))
    (is (= 67 (:coverage-pct result)))     ; round(100*2/3)
    (is (= "b" (:dominant-kind result)))
    (is (= 3 (:dominant-count result)))
    (is (= [{:kind "a" :label "甲" :count 2 :pct 40}
            {:kind "b" :label "乙" :count 3 :pct 60}
            {:kind "c" :label "丙" :count 0 :pct 0}]
           (:by-kind result)))))


(deftest distribution-key-fn-can-be-derived-not-field-accessor
  ;; key-fn 支持派生函数(如 quadrant-of), 与治理台账逐条象限列同口径.
  (let [rows [{:influence "high" :interest "high"}
              {:influence "high" :interest "high"}
              {:influence "low" :interest "low"}]
        quad (fn [{:keys [influence interest]}]
               (str influence "/" interest))
        result (rm/distribution rows {:key-fn quad
                                      :order ["high/high" "low/low"]
                                      :labels {"high/high" "紧密管理" "low/low" "监控"}
                                      :value-k :quadrant
                                      :dominant-k :dominant-quadrant
                                      :by-k :by-quadrant})]
    (is (= 3 (:total result)))
    (is (= "high/high" (:dominant-quadrant result)))
    (is (= [{:quadrant "high/high" :label "紧密管理" :count 2 :pct 67}
            {:quadrant "low/low" :label "监控" :count 1 :pct 33}]
           (:by-quadrant result)))))


(deftest distribution-dominant-tie-picks-later-in-order
  ;; 并列时 max-key 取扫描靠后者, 不承诺唯一 (锁定原实现语义).
  (let [rows [{:kind "a"} {:kind "b"}]
        result (rm/distribution rows {:key-fn :kind
                                      :order ["a" "b"]
                                      :labels {"a" "甲" "b" "乙"}
                                      :value-k :kind
                                      :dominant-k :dominant-kind
                                      :by-k :by-kind})]
    (is (= "b" (:dominant-kind result)))
    (is (= 1 (:dominant-count result)))))


(deftest distribution-missing-label-falls-back-to-raw-value
  ;; labels 未覆盖某档时 :label 回退为原始值 (get labels v v).
  (let [result (rm/distribution [{:kind "x"}] {:key-fn :kind
                                               :order ["x"]
                                               :labels {}
                                               :value-k :kind
                                               :dominant-k :dominant-kind
                                               :by-k :by-kind})]
    (is (= [{:kind "x" :label "x" :count 1 :pct 100}] (:by-kind result)))))
