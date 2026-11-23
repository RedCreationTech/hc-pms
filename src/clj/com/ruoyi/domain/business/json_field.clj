(ns com.ruoyi.domain.business.json-field
  "业务表 JSON 文本列的解析与按路径取值.叶子命名空间,无业务依赖."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]))


(defn parse-field
  "把 JSON 文本解析成 Clojure 数据(已是数据则原样返回,解析失败返回 nil)."
  [v]
  (cond
    (nil? v) nil
    (string? v) (try (json/parse-string v true) (catch Exception _ nil))
    :else v))


(defn parse-columns
  "把表中指定的 JSON 文本列解析成 Clojure 数据,其余列原样保留."
  [row ks]
  (reduce (fn [m k]
            (if-let [v (get row k)]
              (assoc m k (try (json/parse-string v true) (catch Exception _ v)))
              m))
          row ks))


(defn get-path
  "按点分路径(如 data.level / list.0.name)从解析后的数据取值."
  [data path]
  (reduce (fn [acc k]
            (cond
              (map? acc) (let [kk (keyword k)] (if (contains? acc kk) (get acc kk) (get acc k)))
              (sequential? acc) (let [i (try (Integer/parseInt (str k)) (catch Exception _ -1))]
                                  (when (and (>= i 0) (< i (count acc))) (nth acc i)))
              :else nil))
          data
          (str/split (str path) #"\.")))