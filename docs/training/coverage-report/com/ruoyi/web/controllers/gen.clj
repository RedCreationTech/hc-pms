✔ (ns com.ruoyi.web.controllers.gen
?   "代码生成器控制器。"
?   (:require
?    [com.ruoyi.domain.gen :as gen-service]
?    [ring.util.response :as response]
?    [clojure.java.io :as io]
?    [clojure.string :as str])
?   (:import [java.io File FileOutputStream]
?            [java.time LocalDateTime]
?            [java.time.format DateTimeFormatter]
?            [java.util.zip ZipOutputStream ZipEntry]))
  
✔ (defn- ok
✔   ([data] (ok 200 "操作成功" data))
?   ([code msg data]
✔    (-> (response/response {:code code :msg msg :data data})
✔        (response/content-type "application/json"))))
  
✔ (defn list-tables
?   "查询数据库中的所有表。"
?   [{:keys [gen-service]} _request]
✔   (ok (gen-service/list-tables gen-service)))
  
✔ (defn table-columns
?   "查询指定表列信息。"
?   [{:keys [gen-service]} request]
✔   (let [table-name (get-in request [:query-params :tableName])]
✔     (when (seq table-name)
✔       (ok (gen-service/table-columns gen-service table-name)))))
  
✔ (defn preview-code
?   "生成并预览代码。"
?   [{:keys [gen-service]} request]
✔   (let [table-name (get-in request [:query-params :tableName])]
✔     (when (seq table-name)
✔       (ok (gen-service/generate-code gen-service table-name)))))
  
✔ (defn batch-generate
?   "批量生成代码。"
?   [{:keys [gen-service]} request]
✔   (let [table-names (get-in request [:body-params :tables] [])]
✔     (ok (mapv #(gen-service/generate-code gen-service %) table-names))))
  
✔ (defn- write-file! [path content]
✔   (when (seq content)
✔     (let [file (io/file path)]
✔       (io/make-parents file)
✔       (spit file content)
✔       path)))
  
✔ (defn- upsert-generated-sql! [table-name hugsql]
?   "将 HugSQL 写入 generated.sql，按表名替换旧块，避免重复定义。"
✔   (let [file (io/file "resources/sql/generated.sql")
✔         marker (str "-- == generated " table-name " ==")
~         existing (if (.exists file) (slurp file) "")
✔         lines (str/split-lines existing)
?         ;; 找到本表块起止行（简单行匹配）
~         start (first (keep-indexed #(when (str/starts-with? %2 marker) %1) lines))
~         end (when start
✘               (first (keep-indexed #(when (and (> %1 start)
✘                                                (str/starts-with? %2 "-- == generated ")) %1)
✘                                    lines)))
~         before (if start (str/join "\n" (take start lines)) existing)
~         after (if end (str/join "\n" (drop end lines)) "")
✔         block (str marker "\n" hugsql)]
✔     (io/make-parents file)
✔     (spit file (str (str/trim before) "\n\n" block "\n\n" (str/trim after) "\n"))
✔     (.getPath file)))
  
✔ (defn- timestamp []
✔   (.format (DateTimeFormatter/ofPattern "yyyyMMddHHmmss") (LocalDateTime/now)))
  
✔ (defn deploy-code
?   "部署生成的代码到项目目录。"
?   [{:keys [gen-service]} request]
✔   (let [table-name (get-in request [:body-params :tableName])
✔         code (gen-service/generate-code gen-service table-name)
✔         kebab (:kebab-name code)]
✔     (if (seq kebab)
✔       (let [written (remove nil?
✔                             [(upsert-generated-sql! table-name (:backend-sql-queries code))
✔                              (write-file! (str "resources/sql/" kebab ".sql") (:backend-sql-queries code))
✔                              (write-file! (str "src/clj/com/ruoyi/domain/system/" kebab ".clj") (:backend-domain code))
✔                              (write-file! (str "src/clj/com/ruoyi/web/controllers/system/" kebab ".clj") (:backend-controller code))
✔                              (write-file! (str "src/cljs/com/ruoyi/frontend/api/" kebab ".cljs") (:frontend-api code))
✔                              (write-file! (str "src/cljs/com/ruoyi/frontend/pages/" kebab ".cljs") (:frontend-page code))
✔                              (write-file! (str "src/cljs/com/ruoyi/frontend/events/" kebab ".cljs") (:frontend-events code))
✔                              (write-file! (str "src/cljs/com/ruoyi/frontend/subs/" kebab ".cljs") (:frontend-subs code))
✔                              (write-file! (str "resources/migrations-sqlite/" (timestamp) "_" kebab ".up.sql") (:migration-up code))
✔                              (write-file! (str "resources/migrations-sqlite/" (timestamp) "_" kebab ".down.sql")
✔                                           (str "DROP TABLE IF EXISTS " table-name ";"))])]
✔         (ok {:message (str "代码部署成功: " table-name)
✔              :written written
✔              :routes (:backend-routes code)}))
✔       (ok 500 "生成失败" {:error "无法生成代码"}))))
  
✔ (defn- zip-directory! [src-dir ^File zip-file]
?   "将目录打包成 zip 文件。"
✔   (with-open [zos (ZipOutputStream. (FileOutputStream. zip-file))]
~     (doseq [file (file-seq (io/file src-dir))
~             :when (.isFile file)]
~       (let [entry-name (.replace (.getPath file) (str src-dir File/separator) "")]
~         (.putNextEntry zos (ZipEntry. entry-name))
~         (io/copy file zos)
~         (.closeEntry zos))))
✔   zip-file)
  
✔ (defn- write-generated-to-dir! [base-dir code]
?   "将单个表的生成代码写入临时目录。"
✔   (let [kebab (:kebab-name code)
✔         ts (timestamp)
✔         table-name (:table-name code)]
~     (doseq [[path content]
✔             [[(str "backend/src/clj/com/ruoyi/domain/system/" kebab ".clj") (:backend-domain code)]
✔              [(str "backend/src/clj/com/ruoyi/web/controllers/system/" kebab ".clj") (:backend-controller code)]
✔              [(str "backend/resources/sql/" kebab ".sql") (:backend-sql-queries code)]
✔              [(str "backend/resources/migrations-sqlite/" ts "_" kebab ".up.sql") (:migration-up code)]
✔              [(str "backend/resources/migrations-sqlite/" ts "_" kebab ".down.sql")
✔               (str "DROP TABLE IF EXISTS " table-name ";")]
✔              [(str "src/cljs/com/ruoyi/frontend/api/" kebab ".cljs") (:frontend-api code)]
✔              [(str "src/cljs/com/ruoyi/frontend/pages/" kebab ".cljs") (:frontend-page code)]
✔              [(str "src/cljs/com/ruoyi/frontend/events/" kebab ".cljs") (:frontend-events code)]
✔              [(str "src/cljs/com/ruoyi/frontend/subs/" kebab ".cljs") (:frontend-subs code)]]]
~       (when (seq content)
~         (write-file! (str base-dir File/separator path) content)))))
  
✔ (defn download-code
?   "批量生成代码并打包成 ZIP 下载。"
?   [{:keys [gen-service]} request]
✔   (let [table-names (get-in request [:body-params :tables] [])
✔         codes (mapv #(gen-service/generate-code gen-service %) table-names)
✔         temp-dir (io/file (str "target/gen-download-" (timestamp)))
✔         zip-file (io/file (str (.getPath temp-dir) ".zip"))]
✔     (if (empty? codes)
✔       (ok 400 "请选择要生成的表" {})
✔       (do
✔         (.mkdirs temp-dir)
~         (doseq [code codes]
~           (write-generated-to-dir! (.getPath temp-dir) code))
✔         (zip-directory! (.getPath temp-dir) zip-file)
✔         (.deleteOnExit zip-file)
✔         (-> (response/file-response (.getPath zip-file))
✔             (response/content-type "application/zip")
✔             (response/header "Content-Disposition" "attachment; filename=\"gen-code.zip\""))))))
