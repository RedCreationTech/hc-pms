(ns com.ruoyi.domain.pms.read-model
  "PMS 只读派生视图的共享计算原语: 百分比与固定顺序频次分布. 纯函数, 不查库不落库不投递, 不构成任何门控.")


(defn pct
  "把 numer/denom 四舍五入为整数百分比; denom 非正时返回 0. 统一原各处内联的 (int (Math/round (* 100.0 (/ n d)))) 惯用法."
  [denom numer]
  (if (pos? denom)
    (int (Math/round ^double (* 100.0 (/ numer denom))))
    0))


(defn covered-count
  "统计固定顺序分布行中实际出现(:count 为正)的档数, 统一内联的 (count (filterv pos? (map :count rows))) 惯用法. rows 为投影后的分布行向量."
  [rows]
  (count (filterv pos? (map :count rows))))


(defn dominant-value
  "guard 为正时按 tally 频次从 order 取计数最大的档(max-key, 并列取扫描靠后者不承诺唯一), guard 非正时返回 nil. 统一内联的 (when (pos? guard) (apply max-key (fn [v] (get tally v 0)) order)) 惯用法."
  [tally order guard]
  (when (pos? guard)
    (apply max-key (fn [v] (get tally v 0)) order)))


(defn dominant-count
  "取主导档在 tally 中的频次计数, dominant 为 nil 时返回 0. 统一内联的 (if dominant (get tally dominant 0) 0) 惯用法."
  [tally dominant]
  (if dominant (get tally dominant 0) 0))


(defn distribution
  "固定 order 维度, 必填受控枚举的项目级只读频次分布核心: 对 active-rows 按 key-fn 取值做 frequencies, 依 order 投影为 [{value-k label count pct}] 向量 (未出现的档恒记 0), 并给出 total/covered/uncovered/coverage-pct/dominant/dominant-count. dominant 用 max-key 扫 order (并列取扫描靠后者, 不承诺唯一), total=0 时为 nil. opts: :key-fn 取值函数, :order 固定档序, :labels 值到中文标签映射, :value-k 每档值键, :dominant-k 主导值输出键, :by-k 分布输出键."
  [active-rows {:keys [key-fn order labels value-k dominant-k by-k]}]
  (let [total (count active-rows)
        tally (frequencies (map key-fn active-rows))
        by (mapv (fn [v] {value-k v
                          :label (get labels v v)
                          :count (get tally v 0)
                          :pct (pct total (get tally v 0))})
                 order)
        covered (covered-count by)
        dominant (dominant-value tally order total)
        dominant-count (dominant-count tally dominant)]
    {:available (pos? total)
     :total total
     :covered covered
     :uncovered (- (count order) covered)
     :coverage-pct (pct (count order) covered)
     dominant-k dominant
     :dominant-count dominant-count
     by-k by}))


(defn mean-int
  "把 numer/denom 四舍五入为整数均值; denom 非正时返回 0. 统一原各处内联的 (if (pos? n) (int (Math/round (/ (double sum) n))) 0) 与 (if (seq coll) (int (Math/round (/ (double (reduce + 0 xs)) (count xs)))) 0) 惯用法 (两者对自然计数等价: (seq coll) ⇔ (pos? (count coll)))."
  [denom numer]
  (if (pos? denom)
    (int (Math/round ^double (/ (double numer) (double denom))))
    0))


(defn round-dec
  "把 x 四舍五入保留 n 位小数; 走 double 避免 BigDecimal 非终止除法. 统一原各处内联的 (/ (Math/round ^double (* 10.0 (double x))) 10.0) 与 (/ (Math/round (* 100.0 (double x))) 100.0) 惯用法 (n=1 一位小数, n=2 两位小数)."
  [x n]
  (let [scale (Math/pow 10 ^long n)]
    (/ (Math/round ^double (* scale (double x))) scale)))
