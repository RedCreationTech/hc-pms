(ns com.ruoyi.domain.pms.read-model
  "PMS 只读派生视图的共享计算原语: 百分比与固定顺序频次分布. 纯函数, 不查库不落库不投递, 不构成任何门控.")


(defn pct
  "把 numer/denom 四舍五入为整数百分比; denom 非正时返回 0. 统一原各处内联的 (int (Math/round (* 100.0 (/ n d)))) 惯用法."
  [denom numer]
  (if (pos? denom)
    (int (Math/round ^double (* 100.0 (/ numer denom))))
    0))


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
        covered (count (filterv pos? (map :count by)))
        dominant (when (pos? total)
                   (apply max-key (fn [v] (get tally v 0)) order))
        dominant-count (if dominant (get tally dominant 0) 0)]
    {:available (pos? total)
     :total total
     :covered covered
     :uncovered (- (count order) covered)
     :coverage-pct (pct (count order) covered)
     dominant-k dominant
     :dominant-count dominant-count
     by-k by}))
