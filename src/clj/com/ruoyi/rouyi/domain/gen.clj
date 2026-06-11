(ns com.ruoyi.rouyi.domain.gen
  "代码生成器领域服务，根据数据库表结构生成前后端代码。
  支持读取 PostgreSQL 表元数据，生成完整的 Clojure/CLJS 模板代码。"
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]))

;; ──────────────────────────────────────────────
;; 命名转换工具
;; ──────────────────────────────────────────────

(defn- str->words
  "将下划线/短横线分隔的字符串拆分为单词列表。"
  [s]
  (let [s' (str/replace s #"[-_]" "_")]
    (filter seq (str/split s' #"_"))))

(defn to-camel-case
  "下划线或短横线转驼峰（首字母小写）。"
  [s]
  (let [words (str->words s)]
    (str (first words)
         (str/join "" (map #(str (str/upper-case (subs % 0 1)) (subs % 1)) (rest words))))))

(defn to-pascal-case
  "下划线或短横线转大驼峰（首字母大写）。"
  [s]
  (let [words (str->words s)]
    (str/join "" (map #(str (str/upper-case (subs % 0 1)) (subs % 1)) words))))

(defn to-kebab-case
  "下划线转短横线。"
  [s]
  (str/replace s #"_" "-"))

(defn to-snake-case
  "短横线/驼峰转下划线。"
  [s]
  (-> s
      (str/replace #"-" "_")
      (str/replace #"([a-z])([A-Z])" "$1_$2")
      str/lower-case))

;; ──────────────────────────────────────────────
;; 类型映射
;; ──────────────────────────────────────────────

(def sql-type->clj
  "PostgreSQL SQL 类型到 Clojure 类型的映射。"
  {"varchar"               "String"
   "character varying"     "String"
   "text"                  "String"
   "char"                  "String"
   "bpchar"                "String"
   "int"                   "Long"
   "int4"                  "Long"
   "integer"               "Long"
   "int2"                  "Long"
   "smallint"              "Long"
   "int8"                  "Long"
   "bigint"                "Long"
   "serial"                "Long"
   "bigserial"             "Long"
   "numeric"               "BigDecimal"
   "decimal"               "BigDecimal"
   "real"                  "Double"
   "float4"                "Double"
   "float8"                "Double"
   "double precision"      "Double"
   "boolean"               "Boolean"
   "bool"                  "Boolean"
   "timestamp"             "java.time.Instant"
   "timestamptz"           "java.time.Instant"
   "timestamp with time zone" "java.time.Instant"
   "date"                  "java.time.LocalDate"
   "time"                  "java.time.LocalTime"
   "json"                  "String"
   "jsonb"                 "String"
   "uuid"                  "String"
   "bytea"                 "String"})

(def sql-type->cljs
  "PostgreSQL SQL 类型到 CLJS 类型的映射。"
  {"varchar"           "string"
   "character varying" "string"
   "text"              "string"
   "char"              "string"
   "bpchar"            "string"
   "int"               "number"
   "int4"              "number"
   "integer"           "number"
   "int2"              "number"
   "smallint"          "number"
   "int8"              "number"
   "bigint"            "number"
   "serial"            "number"
   "bigserial"         "number"
   "numeric"           "number"
   "decimal"           "number"
   "real"              "number"
   "float4"            "number"
   "float8"            "number"
   "double precision"  "number"
   "boolean"           "boolean"
   "bool"              "boolean"
   "timestamp"         "string"
   "timestamptz"       "string"
   "date"              "string"
   "time"              "string"
   "json"              "object"
   "jsonb"             "object"
   "uuid"              "string"})

(defn- lookup-type [m sql-type]
  (or (m sql-type)
      (some (fn [[k v]] (when (str/starts-with? sql-type k) v)) m)
      "String"))

(defn- clj-type [sql-type]
  (lookup-type sql-type->clj (str/lower-case sql-type)))

(defn- cljs-type [sql-type]
  (lookup-type sql-type->cljs (str/lower-case sql-type)))

;; ──────────────────────────────────────────────
;; 列分析
;; ──────────────────────────────────────────────

(defn- pk-columns
  "从列列表中筛选主键列名。"
  [columns]
  (set (map :column_name (filter #(= "YES" (:is_pk %)) columns))))

(defn- text-columns
  "从列列表中筛选适合用于搜索的文本/字符串列。"
  [columns]
  (filter #(str/includes? (clj-type (:data_type %)) "String") columns))

(defn- date-columns
  "从列列表中筛选日期/时间列。"
  [columns]
  (filter #(str/includes? (str (:data_type %)) "timestamp") columns))

(defn- numeric-columns
  "从列列表中筛选数字列。"
  [columns]
  (filter #(str/includes? (clj-type (:data_type %)) "Long") columns))

(defn- guess-label
  "根据列名猜测中文标签。"
  [column-name]
  (let [name (str/lower-case column-name)]
    (cond
      (str/ends-with? name "_id")        (str (-> name
                                                  (str/replace #"_id$" "")
                                                  to-pascal-case) "ID")
      (str/includes? name "name")        "名称"
      (str/includes? name "title")       "标题"
      (str/includes? name "status")      "状态"
      (str/includes? name "type")        "类型"
      (str/includes? name "code")        "编码"
      (str/includes? name "remark")      "备注"
      (str/includes? name "sort")        "排序"
      (str/includes? name "order")       "排序"
      (str/includes? name "create")      "创建时间"
      (str/includes? name "update")      "更新时间"
      (str/includes? name "description") "描述"
      (str/includes? name "content")     "内容"
      (str/includes? name "email")       "邮箱"
      (str/includes? name "phone")       "手机号"
      (str/includes? name "mobile")      "手机号"
      (str/includes? name "avatar")      "头像"
      (str/includes? name "parent")      "父级"
      (str/includes? name "enabled")     "启用"
      (str/includes? name "deleted")     "删除"
      (str/includes? name "flag")        "标记"
      :else                              (-> (str/replace name #"_" " "
                                                          (str/replace #"(?<=^| )." #(str/upper-case %))
                                                          str/trim)))))

(defn- skip-column?
  "判断列是否应在表单/列表中跳过（系统字段）。"
  [column-name]
  (let [name (str/lower-case column-name)]
    (or (contains? #{"create_time" "update_time" "create_by" "update_by"
                     "del_flag" "delete_flag"} name)
        (and (str/ends-with? name "_id")
             (not (str/includes? name "parent_id"))))))

;; ──────────────────────────────────────────────
;; SQL 模板
;; ──────────────────────────────────────────────

(defn- gen-sql-file
  "生成 HugSQL CRUD 查询文件。"
  [{:keys [table-name entity-name kebab-name camel-name columns]}]
  (let [all-cols (map :column_name columns)
        col-list (str/join ", " all-cols)
        pk-col (or (some #(when (= "YES" (:is_pk %)) (:column_name %)) columns)
                   (first all-cols))
        text-cols (map :column_name (text-columns columns))
        search-clause (when (seq text-cols)
                        (str "-- :name search-" kebab-name " :? :*\n"
                             "-- :doc 搜索 " entity-name "\n"
                             "SELECT " col-list "\n"
                             "FROM " table-name "\n"
                             "WHERE " (str/join " OR " (map #(str % " LIKE :pattern") text-cols)) "\n"
                             "ORDER BY " (first all-cols) " DESC\n"
                             "LIMIT :limit OFFSET :offset\n\n"))]
    (str "-- " entity-name " — 自动生成的 CRUD 查询\n"
         "-- 表名: " table-name "\n"
         "-- 生成时间: " (java.time.Instant/now) "\n\n"
         "-- :name list-" kebab-name " :? :*\n"
         "-- :doc 查询 " entity-name " 列表\n"
         "SELECT " col-list "\n"
         "FROM " table-name "\n"
         "ORDER BY " (first all-cols) " DESC\n"
         "LIMIT :limit OFFSET :offset\n\n"
         "-- :name get-" kebab-name " :? :1\n"
         "-- :doc 根据主键查询 " entity-name "\n"
         "SELECT " col-list "\n"
         "FROM " table-name "\n"
         "WHERE " pk-col " = :id\n\n"
         search-clause
         "-- :name count-" kebab-name " :? :1\n"
         "-- :doc 查询 " entity-name " 总数\n"
         "SELECT COUNT(*) AS total FROM " table-name "\n\n"
         "-- :name insert-" kebab-name "! :! :n\n"
         "-- :doc 创建 " entity-name "\n"
         "INSERT INTO " table-name " (" col-list ")\n"
         "VALUES (" (str/join ", " (map #(str ":" %) all-cols)) ")\n\n"
         "-- :name update-" kebab-name "! :! :n\n"
         "-- :doc 更新 " entity-name "\n"
         "UPDATE " table-name "\n"
         "SET " (str/join ", " (map #(str % " = :" %) all-cols)) "\n"
         "WHERE " pk-col " = :id\n\n"
         "-- :name delete-" kebab-name "! :! :n\n"
         "-- :doc 删除 " entity-name "\n"
         "DELETE FROM " table-name " WHERE " pk-col " = :id\n\n"
         "-- :name delete-" kebab-name "s! :! :n\n"
         "-- :doc 批量删除 " entity-name "\n"
         "DELETE FROM " table-name " WHERE " pk-col " IN (:ids)\n\n")))

;; ──────────────────────────────────────────────
;; Clojure 后端模板
;; ──────────────────────────────────────────────

(defn- gen-domain-file
  "生成 Clojure domain 服务文件。"
  [{:keys [entity-name kebab-name camel-name columns table-name]}]
  (let [pks (pk-columns columns)
        pk-col (or (first (keep #(when (contains? pks (:column_name %)) (:column_name %)) columns))
                   (first (map :column_name columns)))
        text-cols (map :column_name (text-columns columns))]
    (str ";; " entity-name " — 自动生成的领域服务\n"
         ";; 源表: " table-name "\n\n"
         "(ns com.ruoyi.rouyi.domain." kebab-name "\n"
         "  \"" entity-name " 领域服务\"\n"
         "  (:require\n"
         "    [clojure.java.io :as io]))\n\n"
         ";; ──── 列表查询 ────\n\n"
         "(defn list-" kebab-name "\n"
         "  \"查询 " entity-name " 列表。\"\n"
         "  [{:keys [query-fn]} params]\n"
         "  {:rows (query-fn :list-" kebab-name " (merge params {:limit 10 :offset 0}))\n"
         "   :total (:total (first (query-fn :count-" kebab-name " {})))})\n\n"
         ";; ──── 详情查询 ────\n\n"
         "(defn get-" kebab-name "\n"
         "  \"根据 ID 查询 " entity-name " 详情。\"\n"
         "  [{:keys [query-fn]} id]\n"
         "  (first (query-fn :get-" kebab-name " {:id id})))\n\n"
         (when (seq text-cols)
           (str ";; ──── 搜索 ────\n\n"
                "(defn search-" kebab-name "\n"
                "  \"搜索 " entity-name "。\"\n"
                "  [{:keys [query-fn]} {:keys [keyword limit offset]}]\n"
                "  (let [pattern (str \"%\" keyword \"%\")]\n"
                "    (query-fn :search-" kebab-name " {:pattern pattern\n"
                "                                       :limit (or limit 10)\n"
                "                                       :offset (or offset 0)})))\n\n"))
         ";; ──── 创建 ────\n\n"
         "(defn create-" kebab-name "\n"
         "  \"创建 " entity-name "。\"\n"
         "  [{:keys [query-fn]} params]\n"
         "  (query-fn :insert-" kebab-name "! params)\n"
         "  1)\n\n"
         ";; ──── 更新 ────\n\n"
         "(defn update-" kebab-name "\n"
         "  \"更新 " entity-name "。\"\n"
         "  [{:keys [query-fn]} id params]\n"
         "  (query-fn :update-" kebab-name "! (assoc params :id id))\n"
         "  1)\n\n"
         ";; ──── 删除 ────\n\n"
         "(defn delete-" kebab-name "\n"
         "  \"删除 " entity-name "。\"\n"
         "  [{:keys [query-fn]} id]\n"
         "  (query-fn :delete-" kebab-name "! {:id id})\n"
         "  1)\n\n"
         ";; ──── 批量删除 ────\n\n"
         "(defn batch-delete-" kebab-name "\n"
         "  \"批量删除 " entity-name "。\"\n"
         "  [{:keys [query-fn]} ids]\n"
         "  (query-fn :delete-" kebab-name "s! {:ids ids})\n"
         "  (count ids))\n\n")))

(defn- gen-controller-file
  "生成 Clojure controller 文件。"
  [{:keys [entity-name kebab-name camel-name]}]
  (str ";; " entity-name " — 自动生成的控制器\n\n"
       "(ns com.ruoyi.rouyi.web.controllers." kebab-name "\n"
       "  \"" entity-name " 控制器\"\n"
       "  (:require\n"
       "    [com.ruoyi.rouyi.domain." kebab-name " :as " camel-name "-svc]\n"
       "    [ring.util.response :as response]))\n\n"
       "(defn- ok\n"
       "  ([data] (ok 200 \"操作成功\" data))\n"
       "  ([code msg data]\n"
       "   (-> (response/response {:code code :msg msg :data data})\n"
       "       (response/content-type \"application/json\"))))\n\n"
       ";; ──── 列表 ────\n\n"
       "(defn list-" kebab-name "\n"
       "  [{:keys [" camel-name "-svc]} request]\n"
       "  (ok (" camel-name "-svc/list-" kebab-name " request))))\n\n"
       ";; ──── 详情 ────\n\n"
       "(defn get-" kebab-name "\n"
       "  [{:keys [" camel-name "-svc]} request]\n"
       "  (let [id (Long/parseLong (get-in request [:path-params :id]))]\n"
       "    (ok (" camel-name "-svc/get-" kebab-name " id))))\n\n"
       ";; ──── 创建 ────\n\n"
       "(defn create-" kebab-name "\n"
       "  [{:keys [" camel-name "-svc]} request]\n"
       "  (let [params (get-in request [:body-params])]\n"
       "    (ok (" camel-name "-svc/create-" kebab-name " params))))\n\n"
       ";; ──── 更新 ────\n\n"
       "(defn update-" kebab-name "\n"
       "  [{:keys [" camel-name "-svc]} request]\n"
       "  (let [id (Long/parseLong (get-in request [:path-params :id]))\n"
       "        params (get-in request [:body-params])]\n"
       "    (ok (" camel-name "-svc/update-" kebab-name " id params))))\n\n"
       ";; ──── 删除 ────\n\n"
       "(defn delete-" kebab-name "\n"
       "  [{:keys [" camel-name "-svc]} request]\n"
       "  (let [id (Long/parseLong (get-in request [:path-params :id]))]\n"
       "    (ok (" camel-name "-svc/delete-" kebab-name " id))))\n\n"
       ";; ──── 批量删除 ────\n\n"
       "(defn batch-delete-" kebab-name "\n"
       "  [{:keys [" camel-name "-svc]} request]\n"
       "  (let [ids (get-in request [:body-params :ids])]\n"
       "    (ok (" camel-name "-svc/batch-delete-" kebab-name " ids))))\n"))

(defn- gen-routes-file
  "生成 Clojure 路由文件。"
  [{:keys [entity-name kebab-name camel-name]}]
  (str ";; " entity-name " — 自动生成的路由\n\n"
       "(ns com.ruoyi.rouyi.web.routes." kebab-name "\n"
       "  \"" entity-name " 路由\"\n"
       "  (:require\n"
       "    [com.ruoyi.rouyi.web.controllers." kebab-name " :as " camel-name "]\n"
       "    [com.ruoyi.rouyi.web.middleware.auth :as auth-mw]))\n\n"
       "(defn " kebab-name "-routes [{:keys [" camel-name "-svc]}]\n"
       "  [\"/" kebab-name "\"\n"
       "   {:middleware [(auth-mw/auth-middleware {:required? true})]}\n"
       "   [\"\" {:get {:handler (partial " camel-name "/list-" kebab-name
       " {: " camel-name "-svc " camel-name "-svc})}\n"
       "         :post {:handler (partial " camel-name "/create-" kebab-name
       " {: " camel-name "-svc " camel-name "-svc})}}]\n"
       "   [\"/:id\" {:get {:handler (partial " camel-name "/get-" kebab-name
       " {: " camel-name "-svc " camel-name "-svc})}\n"
       "              :put {:handler (partial " camel-name "/update-" kebab-name
       " {: " camel-name "-svc " camel-name "-svc})}\n"
       "              :delete {:handler (partial " camel-name "/delete-" kebab-name
       " {: " camel-name "-svc " camel-name "-svc})}}]\n"
       "   [\"/batch\" {:delete {:handler (partial " camel-name "/batch-delete-" kebab-name
       " {: " camel-name "-svc " camel-name "-svc})}}]])\n"))

;; ──────────────────────────────────────────────
;; 前端 CLJS 模板
;; ──────────────────────────────────────────────

(defn- frontend-column-config
  "生成前端表格列配置。"
  [columns]
  (let [display-cols (remove #(skip-column? (:column_name %)) columns)]
    (str/join "\n" (map-indexed
                    (fn [idx col]
                      (let [col-name (:column_name col)
                            label (or (:column_comment col) (guess-label col-name))
                            cljs-t (cljs-type (:data_type col))
                            time? (str/includes? cljs-t "string")]
                        (str "#js {:title \"" label "\"\n"
                             "       :dataIndex \"" col-name "\"\n"
                             "       :key \"" col-name "\""
                             (when (< idx 3) "\n       :width 120")
                             (when (= col-name "status")
                               (str "\n       :render (fn [v _]\n"
                                    "                (r/as-element\n"
                                    "                  [antd/tag {:color (if (= v \"0\") \"green\" \"red\")}\n"
                                    "                   (if (= v \"0\") \"正常\" \"停用\")]))"))
                             "}")))
                    display-cols))))

(defn- frontend-form-fields
  "生成前端表单字段。"
  [columns]
  (let [form-cols (remove #(or (skip-column? (:column_name %))
                               (= "YES" (:is_pk %))
                               (str/includes? (str/lower-case (:column_name %)) "time")
                               (str/includes? (str/lower-case (:column_name %)) "create")
                               (str/includes? (str/lower-case (:column_name %)) "update"))
                          columns)]
    (str/join "\n\n" (map-indexed
                      (fn [idx col]
                        (let [col-name (:column_name col)
                              label (or (:column_comment col) (guess-label col-name))
                              cljs-t (cljs-type (:data_type col))
                              required? (= "NO" (:is_nullable col))
                              text-area? (str/includes? (:data_type col) "text")
                              numeric? (str/includes? cljs-t "number")
                              boolean? (str/includes? cljs-t "boolean")]
                          (str "       [antd/form-item {:label \"" label "\""
                               " :name \"" col-name "\"\n"
                               "        :rules #js [#js {:required " required?
                               " :message \"请输入" label "\"}]}\n"
                               (cond text-area?
                                     "        [antd/textarea {:rows 4}]"
                                     numeric?
                                     "        [antd/input-number {:style {:width \"100%\"}}]"
                                     boolean?
                                     "        [antd/switch]"
                                     :else
                                     "        [antd/input])")))))
              form-cols)))

(defn- gen-frontend-page
  "生成 CLJS 前端页面文件。"
  [{:keys [entity-name kebab-name camel-name columns]}]
  (let [display-cols (remove #(skip-column? (:column_name %)) columns)
        form-fields (remove #(or (skip-column? (:column_name %))
                                 (= "YES" (:is_pk %))
                                 (str/includes? (str/lower-case (:column_name %)) "time")
                                 (str/includes? (str/lower-case (:column_name %)) "create")
                                 (str/includes? (str/lower-case (:column_name %)) "update"))
                            columns)]
    (str ";; " entity-name " — 自动生成的页面\n\n"
         "(ns com.ruoyi.rouyi.frontend.pages." kebab-name "\n"
         "  \"" entity-name " 页面\"\n"
         "  (:require\n"
         "    [reagent.core :as r]\n"
         "    [reagent.hooks :as hooks]\n"
         "    [re-frame.core :as rf]\n"
         "    [com.ruoyi.rouyi.frontend.antd :as antd]))\n\n"
         "(defonce modal-state (r/atom {:open? false :record nil}))\n"
         "(defonce form-ref (r/atom nil))\n\n"
         "(defn- open-create-modal []\n"
         "  (reset! modal-state {:open? true :record nil}))\n\n"
         "(defn- open-edit-modal [record]\n"
         "  (reset! modal-state {:open? true :record record}))\n\n"
         "(defn- close-modal []\n"
         "  (reset! modal-state {:open? false :record nil}))\n\n"
         ";; ──── 表格列定义 ────\n\n"
         "(defn- " kebab-name "-columns []\n"
         "  #js [" (frontend-column-config columns) "\n"
         "       #js {:title \"操作\" :key \"action\" :width 150\n"
         "            :render (fn [_ record]\n"
         "                     (r/as-element\n"
         "                       [antd/space\n"
         "                        [antd/button {:type \"link\" :size \"small\"\n"
         "                                      :onClick #(open-edit-modal record)}\n"
         "                         [antd/edit-icon] \"编辑\"]\n"
         "                        [antd/popconfirm {:title \"确认删除?\"\n"
         "                                          :onConfirm #(rf/dispatch [:" kebab-name "/delete (.-" (first (map :column_name display-cols)) " record)])}\n"
         "                         [antd/button {:type \"link\" :danger true :size \"small\"}\n"
         "                          [antd/delete-icon] \"删除\"]]])}]])\n\n"
         ";; ──── 表单弹窗 ────\n\n"
         "(defn- form-modal []\n"
         "  (let [record (:record @modal-state)\n"
         "        editing? (boolean record)]\n"
         "    (fn []\n"
         "      [antd/modal {:title (if editing? \"编辑" entity-name "\" \"新增" entity-name "\")\n"
         "                   :open (:open? @modal-state)\n"
         "                   :onOk (fn []\n"
         "                           (when-let [form-instance @form-ref]\n"
         "                             (-> form-instance (.validateFields)\n"
         "                                 (.then (fn [values]\n"
         "                                          (let [v (js->clj values :keywordize-keys true)]\n"
         "                                            (if editing?\n"
         "                                              (rf/dispatch [:" kebab-name "/update (.-" (first (map :column_name display-cols)) " record) v])\n"
         "                                              (rf/dispatch [:" kebab-name "/create v]))\n"
         "                                            (close-modal))))\n"
         "                                 (.catch (fn [_])))))\n"
         "                   :onCancel close-modal\n"
         "                   :destroyOnClose true}\n"
         "       [antd/form {:ref #(reset! form-ref %)\n"
         "                   :labelCol {:span 6}\n"
         "                   :wrapperCol {:span 16}\n"
         "                   :initialValues (when editing?\n"
         "                                    #js {" (str/join "\n")
         (map (fn [col]
                (let [col-name (:column_name col)]
                  (str "                                          " col-name " (.-" col-name " record)")))
              form-fields)
         "                                                   })}\n"
         (frontend-form-fields columns) "\n"
         "       ]])))"
         "\n\n"
         ";; ──── 页面组件 ────\n\n"
         "(defn " kebab-name "-page []\n"
         "  (hooks/use-effect (fn []\n"
         "                      (rf/dispatch [:" kebab-name "/fetch {}])\n"
         "                      js/undefined)\n"
         "                    [])\n"
         "  (let [items @(rf/subscribe [:" kebab-name "/items])\n"
         "        total @(rf/subscribe [:" kebab-name "/total])\n"
         "        loading? @(rf/subscribe [:" kebab-name "/loading?])]\n"
         "    (fn []\n"
         "      [:div\n"
         "       [:h3 \"" entity-name "管理\"]\n"
         "       [antd/space {:style {:marginBottom 16}}\n"
         "        [antd/button {:type \"primary\" :onClick open-create-modal}\n"
         "         [antd/plus-icon] \"新增" entity-name "\"]]\n"
         "       [antd/table {:rowKey \"" (first (map :column_name display-cols)) "\"\n"
         "                    :loading loading?\n"
         "                    :columns (" kebab-name "-columns)\n"
         "                    :dataSource (clj->js items)\n"
         "                    :pagination {:pageSize 10 :total total}}]\n"
         "       [form-modal]])))\n")))

(defn- gen-frontend-api
  "生成 CLJS API 调用文件。"
  [{:keys [entity-name kebab-name camel-name]}]
  (str ";; " entity-name " — 自动生成的 API 调用\n\n"
       "(defn list-" kebab-name "\n"
       "  \"查询 " entity-name " 列表。\"\n"
       "  [params on-success on-error]\n"
       "  (request {:method :get\n"
       "            :uri \"/" kebab-name "\"\n"
       "            :params params\n"
       "            :on-success on-success\n"
       "            :on-error on-error}))\n\n"
       "(defn get-" kebab-name "\n"
       "  \"查询 " entity-name " 详情。\"\n"
       "  [id on-success on-error]\n"
       "  (request {:method :get\n"
       "            :uri (str \"/" kebab-name "/\" id)\n"
       "            :on-success on-success\n"
       "            :on-error on-error}))\n\n"
       "(defn create-" kebab-name "\n"
       "  \"创建 " entity-name "。\"\n"
       "  [params on-success on-error]\n"
       "  (request {:method :post\n"
       "            :uri \"/" kebab-name "\"\n"
       "            :params params\n"
       "            :on-success on-success\n"
       "            :on-error on-error}))\n\n"
       "(defn update-" kebab-name "\n"
       "  \"更新 " entity-name "。\"\n"
       "  [id params on-success on-error]\n"
       "  (request {:method :put\n"
       "            :uri (str \"/" kebab-name "/\" id)\n"
       "            :params params\n"
       "            :on-success on-success\n"
       "            :on-error on-error}))\n\n"
       "(defn delete-" kebab-name "\n"
       "  \"删除 " entity-name "。\"\n"
       "  [id on-success on-error]\n"
       "  (request {:method :delete\n"
       "            :uri (str \"/" kebab-name "/\" id)\n"
       "            :on-success on-success\n"
       "            :on-error on-error}))\n\n"))

(defn- gen-frontend-events
  "生成 CLJS re-frame 事件文件。"
  [{:keys [entity-name kebab-name camel-name]}]
  (str ";; " entity-name " — 自动生成的 re-frame 事件\n\n"
       "(rf/reg-event-db :" kebab-name "/set-list\n"
       "  (fn [db [_ data]]\n"
       "    (-> db\n"
       "        (assoc-in [:" kebab-name " :items] (:rows data))\n"
       "        (assoc-in [:" kebab-name " :total] (:total data))\n"
       "        (assoc-in [:" kebab-name " :loading?] false))))\n\n"
       "(rf/reg-event-fx :" kebab-name "/fetch\n"
       "  (fn [{:keys [db]} [_ params]]\n"
       "    {:db (assoc-in db [:" kebab-name " :loading?] true)\n"
       "     :api/list-" kebab-name " params}))\n\n"
       "(rf/reg-fx :api/list-" kebab-name "\n"
       "  (fn [params]\n"
       "    (api/list-" kebab-name " params\n"
       "      (fn [result]\n"
       "        (when (= 200 (:code result))\n"
       "          (rf/dispatch [:" kebab-name "/set-list (:data result)])))\n"
       "      (fn [_]))))\n\n"
       "(rf/reg-event-fx :" kebab-name "/create\n"
       "  (fn [{:keys [db]} [_ params]]\n"
       "    {:db db\n"
       "     :api/create-" kebab-name " params}))\n\n"
       "(rf/reg-fx :api/create-" kebab-name "\n"
       "  (fn [params]\n"
       "    (api/create-" kebab-name " params\n"
       "      (fn [result]\n"
       "        (when (= 200 (:code result))\n"
       "          (rf/dispatch [:" kebab-name "/fetch {}])\n"
       "          (.success js/antd.message \"创建成功\")))\n"
       "      (fn [_] (.error js/antd.message \"网络错误\")))))\n\n"
       "(rf/reg-event-fx :" kebab-name "/update\n"
       "  (fn [{:keys [db]} [_ id params]]\n"
       "    {:db db\n"
       "     :api/update-" kebab-name " [id params]}))\n\n"
       "(rf/reg-fx :api/update-" kebab-name "\n"
       "  (fn [[id params]]\n"
       "    (api/update-" kebab-name " id params\n"
       "      (fn [result]\n"
       "        (when (= 200 (:code result))\n"
       "          (rf/dispatch [:" kebab-name "/fetch {}])\n"
       "          (.success js/antd.message \"更新成功\")))\n"
       "      (fn [_] (.error js/antd.message \"网络错误\")))))\n\n"
       "(rf/reg-event-fx :" kebab-name "/delete\n"
       "  (fn [{:keys [db]} [_ id]]\n"
       "    {:db db\n"
       "     :api/delete-" kebab-name " id}))\n\n"
       "(rf/reg-fx :api/delete-" kebab-name "\n"
       "  (fn [id]\n"
       "    (api/delete-" kebab-name " id\n"
       "      (fn [result]\n"
       "        (when (= 200 (:code result))\n"
       "          (rf/dispatch [:" kebab-name "/fetch {}])\n"
       "          (.success js/antd.message \"删除成功\")))\n"
       "      (fn [_] (.error js/antd.message \"网络错误\")))))\n\n"))

(defn- gen-frontend-subs
  "生成 CLJS re-frame 订阅文件。"
  [{:keys [entity-name kebab-name camel-name]}]
  (str ";; " entity-name " — 自动生成的 re-frame 订阅\n\n"
       "(rf/reg-sub :" kebab-name "/items\n"
       "  (fn [db _]\n"
       "    (get-in db [:" kebab-name " :items])))\n\n"
       "(rf/reg-sub :" kebab-name "/total\n"
       "  (fn [db _]\n"
       "    (get-in db [:" kebab-name " :total])))\n\n"
       "(rf/reg-sub :" kebab-name "/loading?\n"
       "  (fn [db _]\n"
       "    (get-in db [:" kebab-name " :loading?])))\n\n"))

(defn- gen-migration-up
  "生成数据库迁移 UP 文件。"
  [{:keys [table-name columns entity-name]}]
  (let [col-defs (map (fn [col]
                        (let [col-name (:column_name col)
                              data-type (:data_type col)
                              nullable? (= "YES" (:is_nullable col))
                              default (:column_default col)
                              comment (:column_comment col)]
                          (str "  " col-name " " data-type
                               (when (not nullable?) " NOT NULL")
                               (when default (str " DEFAULT " default))
                               (when comment (str " -- " comment)))))
                      columns)]
    (str "-- " entity-name " — 自动生成的建表脚本\n"
         "-- 源表: " table-name "\n"
         "-- 生成时间: " (java.time.Instant/now) "\n\n"
         "CREATE TABLE IF NOT EXISTS " table-name "_gen (\n"
         (str/join ",\n" col-defs) "\n"
         ");\n")))

;; ──────────────────────────────────────────────
;; 公共 API
;; ──────────────────────────────────────────────

(defn list-tables
  "查询数据库中的所有表（含注释）。"
  [{:keys [query-fn]}]
  (query-fn :list-tables {}))

(defn table-columns
  "查询指定表的列信息（含注释和主键）。"
  [{:keys [query-fn]} table-name]
  (let [columns (query-fn :table-columns {:table_name table-name})
        pk-set (set (map :column_name (query-fn :table-primary-keys {:table_name table-name})))]
    (map #(assoc % :is_pk (if (contains? pk-set (:column_name %)) "YES" "NO")) columns)))

(defn generate-code
  "根据表名生成完整的 CRUD 代码模板。
  返回包含所有代码文件的 map，每个值都是字符串内容。"
  [{:keys [query-fn]} table-name]
  (let [raw-cols (or (query-fn :table-columns {:table_name table-name}) [])
        pk-set (set (map :column_name (query-fn :table-primary-keys {:table_name table-name})))
        columns (mapv #(assoc % :is_pk (if (contains? pk-set (:column_name %)) "YES" "NO")) raw-cols)
        entity-name (to-pascal-case table-name)
        kebab-name (to-kebab-case table-name)
        camel-name (to-camel-case table-name)
        ctx {:table-name table-name
             :entity-name entity-name
             :kebab-name kebab-name
             :camel-name camel-name
             :columns columns}]
    {:table-name table-name
     :entity-name entity-name
     :kebab-name kebab-name
     :camel-name camel-name
     :columns (mapv #(select-keys % [:column_name :data_type :is_nullable
                                     :column_default :column_comment]) columns)
     :backend-sql (gen-sql-file ctx)
     :backend-domain (gen-domain-file ctx)
     :backend-controller (gen-controller-file ctx)
     :backend-routes (gen-routes-file ctx)
     :frontend-api (gen-frontend-api ctx)
     :frontend-page (gen-frontend-page ctx)
     :frontend-events (gen-frontend-events ctx)
     :frontend-subs (gen-frontend-subs ctx)
     :migration-up (gen-migration-up ctx)}))
