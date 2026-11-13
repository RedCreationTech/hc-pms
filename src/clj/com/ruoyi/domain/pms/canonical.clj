(ns com.ruoyi.domain.pms.canonical
  "确定性键序规范化: 递归把嵌套结构中的 map 转为按字符串比较的有序 map, 使相同内容产生字节稳定的序列化结果. 纯函数, 不查库不落库不投递."
  (:require [clojure.walk :as walk]))


(defn stable-sorted
  "递归把 value 中所有 map 转为按 (str 键) 升序比较的 sorted-map (键类型混合时也可比较), 供稳定 JSON 编码与摘要哈希前的规范化. 统一原 integration.codec/encode 与 closure/encoded-snapshot 两处内联 walk/postwalk 惯用法."
  [value]
  (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %) value))
