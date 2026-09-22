# PMS 扩展迁移往返与代码规模验收

验证日期: 2026-09-22. 验证对象: `feat/pms-lifecycle`, 基础提交 `cfe4b15eb28ee81961d22d72648127b2e150b01b`.

本地环境: OpenJDK 25.0.2, Clojure CLI 1.12.4.1618, Babashka 1.12.218. 使用项目现有 JDBC 和 Migratus 依赖. 以下实验仅创建操作系统临时目录中的独立 SQLite 文件, 显式启用外键, 退出时删除临时数据库及日志旁文件. 不读取或修改运行中的 `ruoyi.db` / `rouyi.db`.

## 实测结果

从本文提取下方完整复现命令, 在提交 `cfe4b15` 上重新执行. 使用普通 `next.jdbc` DataSource, 真实项目创建已经过新的 PMS `IMMEDIATE` 事务封装, 未使用连接池替代或测试重试. 迁移往返命令退出码为 0, **34 项断言全部通过**:

1. 完整应用 `migrations-sqlite` 中 52 个迁移, 包含全部上游迁移和 PMS 001-009.
2. 逐个调用 Migratus `down`: `202609220009`, `202609220008`, `202609220007`, `202609220006`, `202609220005`, `202609220004`. 没有跳过迁移或手工替代其 SQL.
3. 004-009 创建的 24 张扩展表全部移除. 38 张保留表的名称及结构不变, 包括上游表, PMS 001-003 基础表和迁移账本.
4. 对保留业务表逐行比较, 数据不变. 比较仅排除 Migratus 账本数据和本次按预期删除的 5030-5041 权限/角色授权. 账本另行验证恰好删除上述六个迁移编号.
5. 重新执行全量 `migrate`, 62 张表及完整表/索引定义恢复一致, 52 个迁移编号恢复一致; 12 项工作台权限和 12 项管理员授权恢复.
6. 通过真实项目服务预先创建的项目, 唯一主节点和 `aggregate_version=1` 的创建审计在回滚及重放后均保留. 重放后再次通过真实服务创建项目也成功.
7. `PRAGMA foreign_key_check` 返回空集合, `PRAGMA integrity_check` 返回 `ok`.

最终输出:

```edn
{:checks 34, :extension_tables 24, :retained_tables 38,
 :total_tables 62, :migrations 52, :status :passed}
```

本次没有执行 MySQL 往返. MySQL 的全量迁移和业务测试由独立 CI 验证, 不能由本 SQLite 结果代替.

## 迁移复现命令

在仓库根目录运行以下完整命令. 其数据库地址由 `createTempFile` 生成, 不采用环境变量中的生产或开发数据库地址. 实验使用项目默认种子管理员 `user_id=1` 和部门 `dept_id=1`.

```bash
clojure -M:dev - <<'CLJ'
(require '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[com.ruoyi.domain.pms.service :as pms]
         '[conman.core :as conman]
         '[migratus.core :as migratus]
         '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as rs])
(def checks (atom 0))
(defn verify! "检查条件并累计通过项,失败立即中止." [label condition]
  (when-not condition (throw (ex-info label {})))
  (swap! checks inc)
  (println "PASS" label))
(defn query "查询当前显式传入的临时数据库." [db sql & args]
  (jdbc/execute! db (into [sql] args) {:builder-fn rs/as-unqualified-lower-maps}))
(defn schema "取得可稳定比较的表与索引定义." [db]
  (query db "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"))
(defn tables "读取非SQLite内部表名." [db]
  (set (map :name (filter #(= "table" (:type %)) (schema db)))))
(defn applied "读取已经应用的真实迁移编号." [db]
  (set (map :id (query db "SELECT id FROM schema_migrations"))))
(defn records "快照上游及基础表,仅排除本次新增权限及迁移账本." [db names]
  (into {} (for [table (disj names "schema_migrations")]
             [table (frequencies (query db (str "SELECT * FROM " table
               (when (#{"sys_menu" "sys_role_menu"} table)
                 " WHERE menu_id NOT BETWEEN 5030 AND 5041"))))])))
(defn service "加载全部PMS查询,仅注入临时数据库." [db]
  (let [files (->> (.listFiles (io/file "resources/sql"))
                   (filter #(re-matches #"pms.*\.sql" (.getName %)))
                   (map #(str "sql/" (.getName %))) sort)
        queries (:fns (apply conman/bind-connection-map db {} files))]
    {:db db :query-fn (fn
                       ([name params] ((get-in queries [name :fn]) params))
                       ([tx name params] ((get-in queries [name :fn]) tx params)))}))
(defn create-project! "使用实际服务创建用于保留性检查的项目." [svc]
  (pms/create-project! svc (pms/actor svc {:user-id 1})
    {:project_no (str "MIGRATION-" (java.util.UUID/randomUUID))
     :name "迁移往返验收" :manager_id 1 :dept_id 1
     :start_date "2026-09-22"}))
(defn project-intact! "验证真实项目,主节点及聚合审计记录." [db project]
  (let [id (:project_id project)]
    (verify! "project present" (= 1 (count (query db "SELECT * FROM pms_project WHERE project_id=?" id))))
    (verify! "one main node" (= 1 (count (query db "SELECT * FROM pms_node WHERE project_id=? AND node_type='main' AND parent_id IS NULL" id))))
    (verify! "versioned creation audit" (= [1] (mapv :aggregate_version (query db "SELECT aggregate_version FROM pms_event WHERE project_id=?" id))))))
(defn roundtrip! "全部迁移后仅回滚004至009,验证保留数据并重放全部迁移." [db]
  (let [config {:store :database :db {:datasource db} :migration-dir "migrations-sqlite"}
        ids (vec (range 202609220004 202609220010))
        files (filter #(re-matches #"20260922000[4-9].*\.up\.sql" (.getName %))
                       (.listFiles (io/file "resources/migrations-sqlite")))
        new-tables (set (map second (mapcat #(re-seq #"(?i)CREATE TABLE(?: IF NOT EXISTS)? ([A-Za-z_]+)" (slurp %)) files)))]
    (verify! "foreign keys enabled" (= 1 (:foreign_keys (first (query db "PRAGMA foreign_keys")))))
    (verify! "all migrations up" (nil? (migratus/migrate config)))
    (let [svc (service db), project (create-project! svc), full-schema (schema db)
          before-ids (applied db), retained (set/difference (tables db) new-tables)
          before-records (records db retained)
          before-schema (filterv #(not (new-tables (:tbl_name %))) full-schema)]
      (verify! "24 extension tables installed" (and (= 24 (count new-tables)) (set/subset? new-tables (tables db))))
      (verify! "all six migration ids installed" (set/subset? (set ids) before-ids))
      (doseq [id (reverse ids)]
        (migratus/down config id)
        (verify! (str "down " id) (not ((applied db) id))))
      (verify! "only 004-009 migration ids removed" (= (set/difference before-ids (set ids)) (applied db)))
      (verify! "all extension tables removed" (empty? (set/intersection new-tables (tables db))))
      (verify! "all upstream and 001-003 tables retained" (= retained (tables db)))
      (verify! "retained schema unchanged" (= before-schema (schema db)))
      (verify! "retained data unchanged" (= before-records (records db retained)))
      (verify! "workbench permissions removed" (empty? (query db "SELECT menu_id FROM sys_menu WHERE menu_id BETWEEN 5030 AND 5041")))
      (verify! "workbench grants removed" (empty? (query db "SELECT menu_id FROM sys_role_menu WHERE menu_id BETWEEN 5030 AND 5041")))
      (project-intact! db project)
      (verify! "all migrations replayed" (nil? (migratus/migrate config)))
      (verify! "migration ids restored" (= before-ids (applied db)))
      (verify! "full schema restored" (= full-schema (schema db)))
      (verify! "upstream and foundation data survive replay" (= before-records (records db retained)))
      (verify! "12 workbench permissions restored" (= 12 (count (query db "SELECT menu_id FROM sys_menu WHERE menu_id BETWEEN 5030 AND 5041"))))
      (verify! "12 admin grants restored" (= 12 (count (query db "SELECT menu_id FROM sys_role_menu WHERE role_id=1 AND menu_id BETWEEN 5030 AND 5041"))))
      (project-intact! db project)
      (project-intact! db (create-project! svc))
      (verify! "foreign key check" (empty? (query db "PRAGMA foreign_key_check")))
      (verify! "integrity check" (= "ok" (:integrity_check (first (query db "PRAGMA integrity_check")))))
      (prn {:checks @checks :extension_tables (count new-tables)
            :retained_tables (count retained) :total_tables (count (tables db))
            :migrations (count before-ids) :status :passed}))))
(let [file (java.io.File/createTempFile "hc-pms-migration-roundtrip-" ".db")
      url (str "jdbc:sqlite:" (.getAbsolutePath file) "?foreign_keys=on")]
  (try
    (println "Temporary database:" (.getAbsolutePath file))
    (roundtrip! (jdbc/get-datasource {:jdbcUrl url}))
    (finally
      (doseq [suffix ["" "-journal" "-wal" "-shm"]]
        (io/delete-file (str (.getAbsolutePath file) suffix) true)))))
(shutdown-agents)
CLJ
```

## 源码规模检查

检查范围包括 `domain/pms` 全部后端 namespace, `web/controllers/pms*.clj`, `web/routes/pms*.clj`, `frontend/pages/pms/*.cljs`, `resources/sql/pms*.sql`, 以及 MySQL/SQLite 两套 004-009 的 up/down 文件. 没有只检查本轮新增文件而遗漏被修改的 PMS 基础文件.

函数以 rewrite-clj 语法树定位完整表达式, 统计 `defn`, `defn-`, `defmethod`, `fn`, `fn*` 及 `#()` 的起止物理行, 包括声明、docstring 和函数内部空行. 因此不会把下一函数之间的空白误算进上一函数.

| 检查 | 数量 | 实测最大值 | 上限 | 结果 |
|---|---:|---:|---:|---|
| PMS 源文件 | 69 | 266 行 (`domain/pms/service.clj`) | 每 namespace 500 行 | 通过 |
| 具名/匿名函数形式 | 1,207 | 39 行 (`web/routes/pms_governance.clj`, 第 12 行) | 每函数 40 行 | 通过 |
| PMS SQL及扩展迁移文件 | 33 | 131 行 (`resources/sql/pms.sql`) | 每文件 200 行 | 通过 |

以下命令只读取源码, 违规时输出具体文件/行号并以非零状态退出:

```bash
bb /dev/stdin <<'CLJ'
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[rewrite-clj.parser :as parser]
         '[rewrite-clj.node :as node])
(def source-files
  (sort (concat (fs/glob "src/clj/com/ruoyi/domain/pms" "**.clj")
                (fs/glob "src/clj/com/ruoyi/web/controllers" "pms*.clj")
                (fs/glob "src/clj/com/ruoyi/web/routes" "pms*.clj")
                (fs/glob "src/cljs/com/ruoyi/frontend/pages/pms" "*.cljs"))))
(def sql-files
  (sort (concat (fs/glob "resources/sql" "pms*.sql")
                (fs/glob "resources/migrations" "20260922000[4-9]*.sql")
                (fs/glob "resources/migrations-sqlite" "20260922000[4-9]*.sql"))))
(defn line-count "计算文件物理行数." [file]
  (count (str/split-lines (slurp (str file)))))
(defn function-records "通过语法树记录具名函数与匿名函数的位置和行数." [file]
  (let [root (parser/parse-string-all (slurp (str file)))]
    (for [form (tree-seq node/inner? node/children root)
          :when (#{:list :fn} (node/tag form))
          :let [head (first (remove node/printable-only? (node/children form)))
                kind (when head (node/string head))]
          :when (or (= :fn (node/tag form)) (#{"defn" "defn-" "defmethod" "fn" "fn*"} kind))
          :let [{:keys [row end-row]} (meta form)]]
      {:file (str file) :row row :lines (inc (- end-row row))
       :kind (if (= :fn (node/tag form)) "#()" kind)})))
(def sources (mapv #(hash-map :file (str %) :lines (line-count %)) source-files))
(def functions (vec (mapcat function-records source-files)))
(def sql (mapv #(hash-map :file (str %) :lines (line-count %)) sql-files))
(def violations {:namespace (filterv #(> (:lines %) 500) sources)
                 :function (filterv #(> (:lines %) 40) functions)
                 :sql (filterv #(> (:lines %) 200) sql)})
(prn {:source_files (count sources) :function_forms (count functions) :sql_files (count sql)
      :max_source (apply max-key :lines sources) :max_function (apply max-key :lines functions)
      :max_sql (apply max-key :lines sql) :violations violations})
(System/exit (if (every? empty? (vals violations)) 0 1))
CLJ
```
