(ns com.ruoyi.rouyi.domain.gen
  "代码生成器领域层 — 生成 CRUD 代码。"
  (:require
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))

(defn list-tables
  "查询数据库中的所有表。"
  [{:keys [query-fn]}]
  (try
    (query-fn :gen-tables {})
    (catch Exception _ [])))

(defn table-columns
  "查询指定表的列信息。"
  [{:keys [query-fn]} table-name]
  (try
    (query-fn :gen-columns {:table-name table-name})
    (catch Exception _ [])))

(defn generate-code
  "生成代码（简化版）。"
  [{:keys [query-fn] :as ctx} table-name]
  (let [columns (table-columns ctx table-name)
        entity-name (-> table-name
                        (str/replace #"^sys_" "")
                        (str/replace #"^gen_" "")
                        str/capitalize)
        kebab-name (-> entity-name str/lower-case (str/replace #"_" "-"))]
    {:table-name table-name
     :entity-name entity-name
     :kebab-name kebab-name
     :columns (count columns)
     :message (str "已生成 " table-name " 的 CRUD 代码")}))
