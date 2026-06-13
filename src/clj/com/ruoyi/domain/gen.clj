(ns com.ruoyi.domain.gen
  "代码生成器领域层 — 根据表结构生成 CRUD 代码模板。"
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

;; ─── 列元数据归一化 ──────────────────────────────────────────────────────

(defn- col-name [col]
  (or (:name col) (:column_name col)))

(defn- col-type [col]
  (or (:type col) (:data_type col)))

(defn- col-pk? [col]
  (or (= 1 (:pk col))
      (= "YES" (:is_pk col))))

(defn- col-nullable? [col]
  (or (= 0 (:notnull col))
      (= "YES" (:is_nullable col))))

;; ─── 命名转换 ──────────────────────────────────────────────────────────

(defn- entity-name [table-name]
  (-> table-name
      (str/replace #"^(sys_|gen_|t_)" "")
      str/capitalize))

(defn- kebab-name [table-name]
  (-> table-name
      (str/replace #"^(sys_|gen_|t_)" "")
      (str/replace #"_" "-")
      str/lower-case))

(defn- plural [s] s)

;; ─── SQL 生成 ──────────────────────────────────────────────────────────

(defn- sql-type [db-type]
  (let [t (str/lower-case (str db-type))]
    (cond
      (str/starts-with? t "int") "INTEGER"
      (or (str/starts-with? t "varchar")
          (str/starts-with? t "char")
          (str/starts-with? t "text")
          (str/starts-with? t "datetime")
          (str/starts-with? t "date")) "TEXT"
      (str/starts-with? t "real") "REAL"
      (str/starts-with? t "blob") "BLOB"
      :else "TEXT")))

(defn- generate-ddl [table-name columns]
  (let [lines (mapv (fn [col]
                      (str "    " (col-name col) " " (sql-type (col-type col))
                           (when (col-pk? col) " PRIMARY KEY AUTOINCREMENT")
                           (when (not (col-nullable? col)) " NOT NULL")
                           (when-let [d (:dflt_value col)]
                             (str " DEFAULT " d))))
                    columns)]
    (str "CREATE TABLE IF NOT EXISTS " table-name " (\n"
         (str/join ",\n" lines)
         "\n);")))

(defn- generate-hugsql [table-name kebab columns]
  (let [pk-col (or (first (filter col-pk? columns)) (first columns))
        pk (col-name pk-col)
        non-pk (remove #(= (col-name %) pk) columns)
        list-fn (str "list-" kebab "s")
        find-fn (str "find-" kebab "-by-id")
        create-fn (str "create-" kebab "!")
        update-fn (str "update-" kebab "!")
        delete-fn (str "delete-" kebab "!")
        col-list (str/join ", " (map col-name columns))
        insert-cols (str/join ", " (map col-name non-pk))
        insert-params (str/join ", " (map #(str ":" (col-name %)) non-pk))
        set-clause (str/join ",\n" (map #(str "    " (col-name %) " = COALESCE(:" (col-name %) ", " (col-name %) ")") non-pk))]
    (str "-- :name " list-fn "\n"
         "-- :command :query\n"
         "-- :result :*\n"
         "SELECT " col-list "\n  FROM " table-name "\n ORDER BY " pk " DESC;\n\n"
         "-- :name " find-fn "\n"
         "-- :command :query\n"
         "-- :result :one\n"
         "SELECT " col-list "\n  FROM " table-name "\n WHERE " pk " = :" pk ";\n\n"
         "-- :name " create-fn "\n"
         "-- :command :insert\n"
         "INSERT INTO " table-name " (" insert-cols ")\n"
         "VALUES (" insert-params ");\n\n"
         "-- :name " update-fn "\n"
         "-- :command :execute\n"
         "UPDATE " table-name "\n"
         "   SET " set-clause "\n"
         " WHERE " pk " = :" pk ";\n\n"
         "-- :name " delete-fn "\n"
         "-- :command :execute\n"
         "DELETE FROM " table-name "\n"
         " WHERE " pk " = :" pk ";\n")))

;; ─── 后端 Domain 生成 ───────────────────────────────────────────────────

(defn- generate-domain [table-name entity kebab columns]
  (let [pk-col (or (first (filter col-pk? columns)) (first columns))
        pk-name (col-name pk-col)
        list-fn (str "list-" kebab "s")
        find-fn (str "find-" kebab "-by-id")
        create-fn (str "create-" kebab "!")
        update-fn (str "update-" kebab "!")
        delete-fn (str "delete-" kebab "!")]
    (str "(ns com.ruoyi.domain.system." kebab "\n"
         "  \"" entity " 领域服务。\")\n\n"
         "(defn " list-fn "\n"
         "  \"查询" entity "列表。\"\n"
         "  [{:keys [query-fn]} params]\n"
         "  (query-fn :" list-fn " (merge {"
         (str/join " " (map #(str ":" (col-name %) " nil") columns)) "} params)))\n\n"
         "(defn " find-fn "\n"
         "  \"根据ID查询" entity "。\"\n"
         "  [{:keys [query-fn]} id]\n"
         "  (query-fn :" find-fn " {:" pk-name " id}))\n\n"
         "(defn " create-fn "\n"
         "  \"创建" entity "。\"\n"
         "  [{:keys [query-fn]} params]\n"
         "  (-> (query-fn :" create-fn " params)\n"
         "      first\n"
         "      :" pk-name "))\n\n"
         "(defn " update-fn "\n"
         "  \"更新" entity "。\"\n"
         "  [{:keys [query-fn]} params]\n"
         "  (query-fn :" update-fn " (merge {"
         (str/join " " (map #(str ":" (col-name %) " nil") columns)) "} params)))\n\n"
         "(defn " delete-fn "\n"
         "  \"删除" entity "。\"\n"
         "  [{:keys [query-fn]} id]\n"
         "  (query-fn :" delete-fn " {:" pk-name " id}))\n")))

;; ─── 后端 Controller 生成 ───────────────────────────────────────────────

(defn- generate-controller [entity kebab pk-name]
  (str "(ns com.ruoyi.web.controllers.system." kebab "\\n"
       "  \"" entity "管理控制器。\"\\n"
       "  (:require\\n   [com.ruoyi.domain.system." kebab " :as " kebab "-service]\\n"
       "   [ring.util.response :as response]))\\n\\n"
       "(defn- ok ([data] (ok 200 \"操作成功\" data))\\n"
       "  ([code msg data]\\n"
       "   (-> (response/response {:code code :msg msg :data data})\\n"
       "       (response/content-type \"application/json\"))))\\n\\n"
       "(defn list-" kebab "s [{:keys [" kebab "-service]} request]\\n"
       "  (ok (" kebab "-service/list-" kebab "s " kebab "-service (:query-params request))))\\n\\n"
       "(defn get-" kebab " [{:keys [" kebab "-service]} request]\\n"
       "  (ok (" kebab "-service/find-" kebab "-by-id " kebab "-service (parse-long (get-in request [:path-params :id])))))\\n\\n"
       "(defn create-" kebab " [{:keys [" kebab "-service]} request]\\n"
       "  (ok (str \"创建成功: \" (" kebab "-service/create-" kebab "! " kebab "-service (:body-params request)))))\\n\\n"
       "(defn update-" kebab " [{:keys [" kebab "-service]} request]\\n"
       "  (let [id (parse-long (get-in request [:path-params :id]))]\\n"
       "    (" kebab "-service/update-" kebab "! " kebab "-service (assoc (:body-params request) :" pk-name " id))\\n"
       "    (ok \"更新成功\")))\\n\\n"
       "(defn delete-" kebab " [{:keys [" kebab "-service]} request]\\n"
       "  (" kebab "-service/delete-" kebab "! " kebab "-service (parse-long (get-in request [:path-params :id])))\\n"
       "  (ok \"删除成功\"))\\n"))

;; ─── 后端 Routes 生成 ───────────────────────────────────────────────────

(defn- generate-routes [kebab]
  (str "   [\"/" kebab "\"\n"
       "    [\"\" {:get  {:handler (partial " kebab "/list-" kebab "s {:" kebab "-service " kebab "-service})}\n"
       "         :post {:handler (partial " kebab "/create-" kebab " {:" kebab "-service " kebab "-service})}}]\n"
       "    [\"/export\" {:get {:handler ...}}]\n"
       "    [\"/" kebab "/:id\" {:get    {:parameters {:path PathId}\n"
       "                      :handler (partial " kebab "/get-" kebab " {:" kebab "-service " kebab "-service})}\n"
       "             :put    {:parameters {:path PathId}\n"
       "                      :handler (partial " kebab "/update-" kebab " {:" kebab "-service " kebab "-service})}\n"
       "             :delete {:parameters {:path PathId}\n"
       "                      :handler (partial " kebab "/delete-" kebab " {:" kebab "-service " kebab "-service})}}]]\n"))

;; ─── 前端 API 生成 ─────────────────────────────────────────────────────

(defn- generate-frontend-api [kebab]
  (str ";; ─── " kebab " 管理 ──────────────────────────────────────────────────────\n\n"
       "(defn list-" kebab "s\n"
       "  \"获取" kebab "列表。\"\n"
       "  [params on-success on-error]\n"
       "  (request {:method :get :uri \"/system/" kebab "\" :params params\n"
       "            :on-success on-success :on-error on-error}))\n\n"
       "(defn create-" kebab "\n"
       "  [params on-success on-error]\n"
       "  (request {:method :post :uri \"/system/" kebab "\" :params params\n"
       "            :on-success on-success :on-error on-error}))\n\n"
       "(defn update-" kebab "\n"
       "  [id params on-success on-error]\n"
       "  (request {:method :put :uri (str \"/system/" kebab "/\" id) :params params\n"
       "            :on-success on-success :on-error on-error}))\n\n"
       "(defn delete-" kebab "\n"
       "  [id on-success on-error]\n"
       "  (request {:method :delete :uri (str \"/system/" kebab "/\" id)\n"
       "            :on-success on-success :on-error on-error}))\n"))

;; ─── 前端页面/事件/订阅占位 ─────────────────────────────────────────────

(defn- generate-frontend-page [kebab entity]
  (str "(ns com.ruoyi.frontend.pages." kebab "\n"
       "  \"" entity "管理页面。\"\n"
       "  (:require [reagent.core :as r]\n"
       "            [reagent.hooks :as hooks]\n"
       "            [re-frame.core :as rf]\n"
       "            [com.ruoyi.frontend.antd :as antd]))\n\n"
       "(defn " kebab "-page []\n"
       "  (hooks/use-effect (fn [] (rf/dispatch [:" kebab "s/fetch {}]) js/undefined) [])\n"
       "  [:div \"TODO: " entity " 页面\"])\n"))

(defn- generate-frontend-events [kebab]
  (str ";; ────── " kebab " 管理 ──────\n\n"
       "(rf/reg-event-fx :" kebab "s/fetch\n"
       "  (fn [{:keys [db]} [_ params]]\n"
       "    {:db (assoc-in db [:" kebab "s :loading?] true)\n"
       "     :api/list-" kebab "s params}))\n"))

(defn- generate-frontend-subs [kebab]
  (str "(rf/reg-sub :" kebab "s/items\n"
       "  (fn [db _] (get-in db [:" kebab "s :items] [])))\n\n"
       "(rf/reg-sub :" kebab "s/loading?\n"
       "  (fn [db _] (get-in db [:" kebab "s :loading?] false)))\n"))

;; ─── 公开 API ──────────────────────────────────────────────────────────

(defn generate-code
  "根据表名生成完整 CRUD 代码模板。"
  [{:keys [query-fn] :as ctx} table-name]
  (let [columns (table-columns ctx table-name)
        entity (entity-name table-name)
        kebab (kebab-name table-name)
        pk-name (col-name (or (first (filter col-pk? columns)) (first columns)))]
    (merge
     {:table-name table-name
      :entity-name entity
      :kebab-name kebab
      :columns (count columns)
      :message (str "已生成 " table-name " 的 CRUD 代码")}
     (when (seq columns)
       {:backend-sql (generate-ddl table-name columns)
        :backend-sql-queries (generate-hugsql table-name kebab columns)
        :backend-domain (generate-domain table-name entity kebab columns)
        :backend-controller (generate-controller entity kebab pk-name)
        :backend-routes (generate-routes kebab)
        :frontend-api (generate-frontend-api kebab)
        :frontend-page (generate-frontend-page kebab entity)
        :frontend-events (generate-frontend-events kebab)
        :frontend-subs (generate-frontend-subs kebab)
        :migration-up (generate-ddl table-name columns)}))))
