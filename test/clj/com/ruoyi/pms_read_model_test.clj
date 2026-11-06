(ns com.ruoyi.pms-read-model-test
  "com.ruoyi.domain.pms.read-model 纯函数原语的表征测试: 锁定 pct, distribution, mean-int, round-dec 的输出形状与数值语义, 作为跨治理/规划/组合模块内联惯用法收敛到共享原语的行为保持门."
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


;; ---------- Batch 3: mean-int / round-dec 内联惯用法收敛门 ----------


(deftest mean-int-rounds-half-up-and-guards-zero-denominator
  ;; 与旧内联 (if (pos? n) (int (Math/round (/ (double sum) n))) 0) 逐字节等价.
  (is (= 0 (rm/mean-int 0 0)))            ; 空集合分支
  (is (= 0 (rm/mean-int 0 7)))            ; denom 非正一律 0, 不除零
  (is (= 0 (rm/mean-int -3 7)))           ; 负 denom 走同一分支
  (is (= 2 (rm/mean-int 3 7)))            ; round(7/3=2.333)=2
  (is (= 3 (rm/mean-int 3 8)))            ; round(8/3=2.667)=3
  (is (= 2 (rm/mean-int 2 4)))            ; 精确整除
  (is (= 65 (rm/mean-int 3 196)))         ; (63+67+65)/3=65, 覆盖 utilization-pct 场景
  (is (= 66 (rm/mean-int 3 197))))         ; round(65.667)=66


(deftest mean-int-accepts-bigdecimal-and-double-numer
  ;; numer 走 double 参数化: Integer, Double, BigDecimal 都归入同一 Math/round 语义.
  (is (= 2 (rm/mean-int 3 7)))                      ; Integer numer
  (is (= 2 (rm/mean-int 3 7.0)))                    ; Double numer
  (is (= 2 (rm/mean-int 3 7M)))                     ; BigDecimal numer
  (is (= 3 (rm/mean-int 2 6.5)))                    ; Double 半进位到 +∞
  (is (= 0 (rm/mean-int 100 40))))                  ; round(0.4)=0


(deftest mean-int-equivalent-to-inline-coll-idiom
  ;; 明确锁定 (seq coll) ⇔ (pos? (count coll)): 空/单元素/多元素向量都跑一遍.
  (let [f-old (fn [xs] (if (seq xs)
                         (int (Math/round (/ (double (reduce + 0 xs)) (count xs))))
                         0))
        f-new (fn [xs] (rm/mean-int (count xs) (reduce + 0 xs)))]
    (doseq [xs [[]
                [1]
                [1 2 3]
                [65 67 63]
                [10 20 30 42]
                [-1 1]]]
      (is (= (f-old xs) (f-new xs)) (str "xs=" xs)))))


(deftest round-dec-n1-matches-inline-idiom
  ;; 与旧内联 (/ (Math/round ^double (* 10.0 (double x))) 10.0) 逐字节等价.
  (is (= 0.0 (rm/round-dec 0 1)))
  (is (= 1.0 (rm/round-dec 1 1)))
  (is (= 0.5 (rm/round-dec 0.5 1)))
  (is (= 0.3 (rm/round-dec (/ 2 7.0) 1)))           ; 0.2857→0.3
  (is (= 1.5 (rm/round-dec 1.45 1)))                ; round(14.5)=15→1.5
  (is (= 1.5 (rm/round-dec 1.54 1)))
  (is (= 1.6 (rm/round-dec 1.55 1)))                ; round(15.5)=16 (Math.round HALF_UP 到 +∞)
  (is (= -1.5 (rm/round-dec -1.55 1)))              ; round(-15.5)=-15 (向 +∞)
  (is (= 12.3 (rm/round-dec 12.34 1))))


(deftest round-dec-n2-matches-inline-idiom
  ;; 与旧内联 (/ (Math/round (* 100.0 (double x))) 100.0) 逐字节等价 (round2 in earned_value).
  (is (= 0.0 (rm/round-dec 0 2)))
  (is (= 1.23 (rm/round-dec 1.234 2)))
  (is (= 1.24 (rm/round-dec 1.235 2)))
  (is (= 1.24 (rm/round-dec 1.2399 2)))
  (is (= 0.67 (rm/round-dec (/ 2 3.0) 2)))          ; 0.6667→0.67
  (is (= 0.33 (rm/round-dec (/ 1 3.0) 2)))          ; 0.3333→0.33
  (is (= 10.0 (rm/round-dec 10 2)))                 ; 整数值也走同一 double 路径
  (is (= 1.23 (rm/round-dec 1.234M 2))))             ; BigDecimal x


(deftest round-dec-equivalent-to-inline-scale-factors
  ;; 明确锁定 n=1 与 n=2 两个 scale 分支, 与旧内联逐字节一致.
  (let [old-1 (fn [x] (/ (Math/round ^double (* 10.0 (double x))) 10.0))
        old-2 (fn [x] (/ (Math/round (* 100.0 (double x))) 100.0))]
    (doseq [x [0.0 0.1 0.15 0.149 1.0 1.2345 -0.7 -12.345 99.99 100.0]]
      (is (= (old-1 x) (rm/round-dec x 1)) (str "x=" x " n=1"))
      (is (= (old-2 x) (rm/round-dec x 2)) (str "x=" x " n=2")))))
