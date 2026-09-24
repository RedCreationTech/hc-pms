(ns com.ruoyi.pms-documents-test
  "证据文档二进制附件 (C04/C05/C06): 真实文件上传的内容寻址存储, SHA256 复核, 密级访问, 版本不可变, 批量 ZIP 与 HTTP 合同的真实数据库测试."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [com.ruoyi.domain.pms.governance :as gov]
    [com.ruoyi.domain.pms.governance.evidence :as evidence]
    [com.ruoyi.domain.pms.governance.files :as files]
    [com.ruoyi.domain.pms.queries :as queries]
    [com.ruoyi.domain.pms.service :as pms]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.routes.api :as api]
    [com.ruoyi.web.routes.pms :as routes]
    [conman.core :as conman]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [reitit.ring :as ring]
    [ring.mock.request :as mock])
  (:import
    (java.io
      ByteArrayInputStream
      File)
    (java.nio.file
      Files)
    (java.util
      UUID)
    (java.util.zip
      ZipInputStream)))


(def ^:dynamic *service* nil)


(def ^:dynamic *handler* nil)


(def ^:dynamic *jdbc-url* (System/getenv "PMS_TEST_JDBC_URL"))


(defn- query-function
  [db]
  (let [queries (:fns (apply conman/bind-connection-map db {} queries/filenames))]
    (fn
      ([name params] ((get-in queries [name :fn]) params))
      ([tx name params] ((get-in queries [name :fn]) tx params)))))


(defn- seed!
  "文档测试专属角色与用户: 9801 编辑+密级, 9802 只读无密级权限, 9803 质量审批人."
  [db]
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9800,'Docs test','pms-docs-test',80,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9800,menu_id FROM sys_menu WHERE perms LIKE 'pms:%'"])
  (jdbc/execute! db ["INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES (9810,'Docs reader','pms-docs-reader',81,'0','0')"])
  (jdbc/execute! db ["INSERT INTO sys_role_menu(role_id,menu_id) SELECT 9810,menu_id FROM sys_menu WHERE perms IN ('pms:project:list','pms:project:query')"])
  (doseq [[id role] [[9801 9800] [9802 9810] [9803 9800]]]
    (jdbc/execute! db ["INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES (?,1,?,?,'0','0')"
                       id (str "docs-test-" id) (str "文档测试" id)])
    (jdbc/execute! db ["INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)" id role])))


(defn- delete-tree!
  [^File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))


(defn- database-fixture
  [f]
  (let [file (when-not *jdbc-url* (Files/createTempFile "pms-docs-test-" ".db" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (or *jdbc-url* (str "jdbc:sqlite:" file))
        db (jdbc/get-datasource {:jdbcUrl url})
        store (.toFile (Files/createTempDirectory "pms-docs-files-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (migratus/migrate {:store :database :db {:datasource db}
                         :migration-dir (if (.contains url "mysql") "migrations" "migrations-sqlite")})
      (seed! db)
      (let [svc {:db db :query-fn (query-function db) :file-dir (.getPath store) :file-max-mb 2}
            handler (ring/ring-handler (ring/router [["/api" api/route-data
                                                      (routes/pms-routes {:pms-service svc})]]))]
        (binding [*service* svc *handler* handler] (f)))
      (finally
        (delete-tree! store)
        (when file (Files/deleteIfExists file))))))


(use-fixtures :once database-fixture)


(defn- actor
  [uid]
  (pms/actor *service* {:user-id uid}))


(defn- error
  "返回可预期业务异常的 [status message], 无异常返回 nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (.getMessage e)])))


(defn- project!
  []
  (let [id (:project_id (pms/create-project! *service* (actor 9801)
                                             {:project_no (str "DOC-" (subs (str (UUID/randomUUID)) 0 8)) :name "二进制证据验证"
                                              :customer "本地测试" :contract_no "DOC-TEST" :project_type "equipment"
                                              :manager_id 9801 :dept_id 1 :start_date "2026-09-01" :end_date "2026-12-31"}))]
    (pms/set-member! *service* (actor 9801) id {:user_id 9802 :role "viewer"})
    (pms/set-member! *service* (actor 9801) id {:user_id 9803 :role "viewer"})
    id))


(defn- version
  [id]
  (:version (pms/project *service* (actor 9801) id)))


(defn- temp-file
  "把字节写成临时文件, 模拟 ring multipart 解析结果."
  ^File [^bytes bytes]
  (let [f (File/createTempFile "upload-" ".bin")]
    (io/copy (ByteArrayInputStream. bytes) f)
    f))


(defn- pdf-bytes
  "生成一个带 PDF 头的确定内容, 用于摘要比对 (不要求是可渲染 PDF)."
  [^String marker]
  (.getBytes (str "%PDF-1.4\n% " marker "\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n") "UTF-8"))


(defn- part
  [filename ^bytes bytes]
  {:filename filename :content-type "application/octet-stream" :tempfile (temp-file bytes) :size (alength bytes)})


(defn- upload!
  ([id body filename bytes] (upload! 9801 id body filename bytes))
  ([uid id body filename bytes]
   (:result (evidence/upload! *service* (actor uid) id (assoc body :version (version id)) (part filename bytes)))))


(defn- command!
  [uid id resource action rid body]
  (:result (gov/command! *service* (actor uid) id resource action rid (assoc body :version (version id)))))


(deftest upload-stores-content-addressed-file-with-verified-sha256
  (let [id (project!)
        bytes (pdf-bytes "design review")
        expected (files/sha256 bytes)
        doc (upload! id {:code "DR-001" :title "设计评审报告" :classification "internal" :stage "设计" :category "设计"} "design-review.pdf" bytes)]
    (is (= "file" (:content_kind doc)))
    (is (= expected (:sha256 doc)))
    (is (= (alength bytes) (:byte_size doc)))
    (is (= "application/pdf" (:content_type doc)))
    (is (true? (:preview doc)))
    (is (= "设计" (:category doc)))
    (is (nil? (:content doc)) "二进制证据不把正文写入 JSON 载荷")
    (is (= (str id "/" expected) (:storage_key doc)))
    (is (.exists (io/file (:file-dir *service*) (:storage_key doc))))
    (is (= (seq bytes) (seq (evidence/file-bytes *service* doc))) "读取时复核摘要后返回原始字节")
    ;; 同项目相同内容共用一份物理文件 (内容寻址), 记录仍各自独立.
    (let [twin (upload! id {:code "DR-001-COPY" :title "同内容副本"} "copy.pdf" bytes)]
      (is (= (:storage_key doc) (:storage_key twin)))
      (is (not= (:id doc) (:id twin))))
    ;; 修订: 编号不变, 版本递增, 新文件新摘要, 旧版本文件与摘要不变.
    (let [v2-bytes (pdf-bytes "design review v2")
          v2 (:result (evidence/upload-revision! *service* (actor 9801) id (:id doc)
                                                 {:code "DR-001" :title "设计评审报告 v2" :version (version id)} (part "design-review-v2.pdf" v2-bytes)))]
      (is (= 2 (:revision v2)))
      (is (= "DR-001" (:code v2)))
      (is (= (files/sha256 v2-bytes) (:sha256 v2)))
      (is (= (:id doc) (:previous_id v2)))
      (is (= expected (:sha256 (evidence/content *service* (actor 9801) id (:id doc)))))
      (is (= [409 "对象已有更新版本,请使用最新版本"]
             (error #(evidence/upload-revision! *service* (actor 9801) id (:id doc)
                                                {:code "DR-001" :title "x" :version (version id)} (part "x.pdf" bytes))))
          "只能对最新版本再修订")
      (is (= [400 "修订不得改变业务编号"]
             (error #(evidence/upload-revision! *service* (actor 9801) id (:id v2)
                                                {:code "OTHER" :title "x" :version (version id)} (part "x.pdf" bytes))))))))


(deftest upload-rejects-disallowed-type-empty-oversized-and-unknown-fields
  (let [id (project!) bytes (pdf-bytes "guard")]
    (is (= 400 (first (error #(upload! id {:code "BAD-1" :title "可执行文件"} "payload.exe" bytes)))))
    (is (= 400 (first (error #(upload! id {:code "BAD-2" :title "无扩展名"} "README" bytes)))))
    (is (= [400 "上传文件为空"] (error #(upload! id {:code "BAD-3" :title "空文件"} "empty.pdf" (byte-array 0)))))
    (is (= 400 (first (error #(upload! id {:code "BAD-4" :title "超限"} "big.pdf" (byte-array (* 3 1048576)))))) "上限 2MiB (测试配置)")
    (is (= 400 (first (error #(upload! id {:code "BAD-5" :title "路径穿越"} "../evil.pdf" bytes)))))
    (is (= 400 (first (error #(evidence/upload! *service* (actor 9801) id {:code "BAD-6" :title "缺文件" :version (version id)} nil)))))
    (is (= 400 (first (error #(upload! id {:code "BAD-7" :title "非法字段" :sha256 "forged"} "ok.pdf" bytes)))) "客户端不能伪造摘要等只读字段")
    (is (= 400 (first (error #(upload! id {:code "BAD-8" :title "非法密级" :classification "secret"} "ok.pdf" bytes)))))
    (is (= 403 (first (error #(upload! 9802 id {:code "BAD-9" :title "只读用户"} "ok.pdf" bytes)))))))


(deftest confidential-download-classification-and-tamper-detection
  (let [id (project!) bytes (pdf-bytes "confidential spec")
        doc (upload! id {:code "SPEC-C" :title "机密规格书" :classification "confidential"} "spec.pdf" bytes)
        open (upload! id {:code "SPEC-P" :title "公开说明" :classification "public"} "notes.txt" (.getBytes "plain notes" "UTF-8"))]
    (is (= 403 (first (error #(evidence/content *service* (actor 9802) id (:id doc))))) "无密级权限读机密文档 403")
    (is (= (:id open) (:id (evidence/content *service* (actor 9802) id (:id open)))) "公开文档可读")
    (is (= (:id doc) (:id (evidence/content *service* (actor 9801) id (:id doc)))))
    (let [batch (evidence/batch-content *service* (actor 9801) id {:record_ids [(:id doc) (:id open)]})]
      (is (= 2 (count (:documents batch))))
      (is (= #{"file"} (set (map :content_kind (:documents batch)))))
      (is (every? :storage_key (:documents batch))))
    (is (= 403 (first (error #(evidence/batch-content *service* (actor 9802) id {:record_ids [(:id open) (:id doc)]}))))
        "批量里混入机密文档整体 403")
    ;; 篡改物理文件 -> 读取时摘要不一致 -> 拒绝下发, 不把损坏文件当原件.
    (let [stored (io/file (:file-dir *service*) (:storage_key doc))]
      (spit stored "tampered bytes")
      (is (= [500 "证据文件校验失败: SPEC-C"] (error #(evidence/file-bytes *service* doc))))
      (.delete stored)
      (is (= [500 "证据文件缺失: SPEC-C"] (error #(evidence/file-bytes *service* doc)))))))


(deftest release-approval-stamps-released-sha256-and-time
  (let [id (project!) bytes (pdf-bytes "fat report")
        doc (upload! id {:code "FAT-RPT" :title "FAT报告"} "fat-report.pdf" bytes)
        submitted (command! 9801 id :documents :submit (:id doc) {:reviewer_id 9803})
        approved (command! 9803 id :documents :decision (:id doc) {:decision "approved" :reason "签发"})]
    (is (= "in_review" (:status submitted)))
    (is (= "approved" (:status approved)))
    (is (= 9803 (:released_by approved)))
    (is (= (:sha256 doc) (:release_sha256 approved)) "签发记录固化被批准版本的摘要")
    (is (string? (:released_at approved)))
    (is (= 409 (first (error #(command! 9801 id :documents :submit (:id doc) {:reviewer_id 9803})))) "已签发版本不能再提交")))


(defn- request
  [method path uid & [{:keys [json params]}]]
  (let [req (cond-> (-> (mock/request method path) (mock/header "accept" "application/json"))
              uid (mock/header "authorization" (str "Bearer " (security/generate-token uid "test" [])))
              json (-> (mock/content-type "application/json") (mock/body (json/generate-string json)))
              params (assoc :params params))
        response (*handler* req)
        raw (:body response)
        bytes (cond (bytes? raw) raw (string? raw) (.getBytes ^String raw "UTF-8") (map? raw) (.getBytes (json/generate-string raw) "UTF-8")
                    (nil? raw) (byte-array 0) :else (let [out (java.io.ByteArrayOutputStream.)] (io/copy raw out) (.toByteArray out)))
        text (String. ^bytes bytes "UTF-8")]
    {:status (:status response) :headers (:headers response) :bytes bytes
     :body (when (and (seq text) (.startsWith text "{")) (json/parse-string text true))}))


(defn- zip-entries
  [^bytes zip-bytes]
  (with-open [zin (ZipInputStream. (ByteArrayInputStream. zip-bytes))]
    (loop [acc {}]
      (if-let [entry (.getNextEntry zin)]
        (let [out (java.io.ByteArrayOutputStream.)]
          (io/copy zin out)
          (recur (assoc acc (.getName entry) (.toByteArray out))))
        acc))))


(deftest documents-http-contract-upload-download-preview-and-zip
  (let [id (project!) base (str "/api/pms/projects/" id "/governance")
        bytes (pdf-bytes "http contract")
        upload-params (fn [filename] {"file" (part filename bytes) "code" "HTTP-1" "title" "HTTP上传" "classification" "internal" "stage" "验证" "version" (str (version id))})]
    (is (= 401 (:status (request :post (str base "/documents/upload") nil {:params (upload-params "a.pdf")}))))
    (is (= 403 (:status (request :post (str base "/documents/upload") 9802 {:params (upload-params "a.pdf")}))))
    (is (= 400 (:status (request :post (str base "/documents/upload") 9801 {:params (upload-params "a.exe")}))))
    (let [created (request :post (str base "/documents/upload") 9801 {:params (upload-params "report.pdf")})
          rid (get-in created [:body :data :result :id])]
      (is (= 200 (:status created)))
      (is (= "file" (get-in created [:body :data :result :content_kind])))
      (is (pos? (get-in created [:body :data :project_version])))
      (let [download (request :get (str base "/documents/" rid "/download") 9801)]
        (is (= 200 (:status download)))
        (is (= (seq bytes) (seq (:bytes download))))
        (is (= (files/sha256 bytes) (get-in download [:headers "X-Content-SHA256"])))
        (is (= "file" (get-in download [:headers "X-Content-Kind"])))
        (is (.startsWith ^String (get-in download [:headers "Content-Disposition"]) "attachment; filename*=UTF-8''report.pdf")))
      (let [preview (request :get (str base "/documents/" rid "/preview") 9801)]
        (is (= 200 (:status preview)))
        (is (.startsWith ^String (get-in preview [:headers "Content-Disposition"]) "inline;"))
        (is (= "application/pdf" (get-in preview [:headers "Content-Type"]))))
      ;; 旧版本仍可下载; 上传修订后新旧版本各自独立.
      (let [revised (request :post (str base "/documents/" rid "/upload-revision") 9801
                             {:params {"file" (part "report-v2.pdf" (pdf-bytes "v2")) "code" "HTTP-1" "title" "HTTP上传 v2" "version" (str (version id))}})]
        (is (= 200 (:status revised)))
        (is (= 2 (get-in revised [:body :data :result :revision]))))
      ;; 不可预览类型 -> 415; 文本证据仍走同一下载入口.
      (let [zipdoc (request :post (str base "/documents/upload") 9801
                            {:params {"file" (part "drawings.zip" (.getBytes "PK\u0003\u0004fake" "UTF-8")) "code" "ZIP-1" "title" "图纸包" "version" (str (version id))}})
            zid (get-in zipdoc [:body :data :result :id])
            text (request :post (str base "/documents") 9801 {:json {:code "TXT-1" :title "文本" :filename "note.txt" :content "hello" :version (version id)}})
            tid (get-in text [:body :data :result :id])]
        (is (= 200 (:status zipdoc)))
        (is (= 415 (:status (request :get (str base "/documents/" zid "/preview") 9801))))
        (is (= 200 (:status (request :get (str base "/documents/" zid "/download") 9801))))
        (is (= "hello" (String. ^bytes (:bytes (request :get (str base "/documents/" tid "/download") 9801)) "UTF-8")))
        (let [zip (request :post (str base "/documents/batch-download") 9801 {:json {:record_ids [rid zid tid]}})
              entries (zip-entries (:bytes zip))]
          (is (= 200 (:status zip)))
          (is (= "3" (str (get-in zip [:headers "X-Batch-Count"]))))
          (is (= 4 (count entries)) "三份文件 + MANIFEST.tsv")
          (is (= (seq bytes) (seq (get entries (str (subs rid 0 8) "_report.pdf")))))
          (is (.contains ^String (String. ^bytes (get entries "MANIFEST.tsv") "UTF-8") "\tfile\t")))))))
