(ns com.ruoyi.rouyi.domain.gen
  "代码生成器领域服务，根据数据库表结构生成前后端代码。"
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]))

(defn- to-camel-case
  "下划线转驼峰。"
  [s]
  (let [parts (str/split s #"_")]
    (str (first parts)
         (str/join "" (map #(str (str/upper-case (subs % 0 1)) (subs % 1)) (rest parts))))))

(defn- to-pascal-case
  "下划线转大驼峰。"
  [s]
  (let [parts (str/split s #"_")]
    (str/join "" (map #(str (str/upper-case (subs % 0 1)) (subs % 1)) parts))))

(defn- to-kebab-case
  "下划线转短横线。"
  [s]
  (str/replace s #"_" "-"))

(defn- sql-type->clj
  "SQL类型转Clojure类型。"
  [sql-type]
  (cond
    (str/starts-with? sql-type "varchar") "String"
    (str/starts-with? sql-type "text") "String"
    (str/starts-with? sql-type "int") "Long"
    (str/starts-with? sql-type "bigint") "Long"
    (str/starts-with? sql-type "timestamp") "java.time.Instant"
    (str/starts-with? sql-type "boolean") "Boolean"
    :else "String"))

(defn list-tables
  "查询数据库中的所有表。"
  [{:keys [query-fn]}]
  (query-fn :list-tables {}))

(defn table-columns
  "查询指定表的列信息。"
  [{:keys [query-fn]} table-name]
  (query-fn :table-columns {:table_name table-name}))

(defn generate-code
  "根据表名生成前后端代码。"
  [{:keys [query-fn]} table-name]
  (let [columns (query-fn :table-columns {:table_name table-name})
        entity-name (to-pascal-case table-name)
        kebab-name (to-kebab-case table-name)
        camel-name (to-camel-case table-name)]
    {:entity-name entity-name
     :kebab-name kebab-name
     :camel-name camel-name
     :columns columns
     :backend-sql (str "-- 自动生成的 " table-name " SQL\n"
                       "-- :name list-" kebab-name " :? :*\n"
                       "SELECT * FROM " table-name " LIMIT :limit OFFSET :offset\n")
     :backend-controller (str ";; 自动生成的 " entity-name " 控制器\n"
                              "(ns com.ruoyi.rouyi.web.controllers." kebab-name ")\n"
                              "  (:require [ring.util.response :as response]))\n")
     :frontend-page (str ";; 自动生成的 " entity-name " 页面\n"
                         "(ns com.ruoyi.rouyi.frontend.pages." kebab-name ")\n"
                         "  (:require [re-frame.core :as rf]))\n")}))
