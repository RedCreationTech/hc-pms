(ns com.ruoyi.web.controllers.pms-governance
  "治理工作台的安全HTTP边界与真实文本附件下载."
  (:require
    [clojure.string :as str]
    [com.ruoyi.domain.pms.governance :as governance]
    [com.ruoyi.web.controllers.pms-http :as http]
    [ring.util.response :as response])
  (:import
    (java.io
      ByteArrayOutputStream)
    (java.net
      URLEncoder)
    (java.nio.charset
      StandardCharsets)
    (java.util.zip
      ZipEntry
      ZipOutputStream)))


(defn workspace
  "读取已授权项目的治理只读模型."
  [svc request]
  (http/invoke svc request #(governance/workspace %1 %2 (http/project-id request))))


(defn command
  "解析业务命令并交由明确的类型化领域服务."
  [svc resource action request]
  (http/invoke svc request
               #(governance/command! %1 %2 (http/project-id request) resource action
                                     (http/param request :record_id) (:body-params request))))


(defn preview
  "预检需求CSV并返回行号和错误详情."
  [svc request]
  (http/invoke svc request #(governance/preview %1 %2 (http/project-id request) (:body-params request))))


(defn content
  "读取统一JSON信封中的实际文档版本正文和摘要."
  [svc request]
  (http/invoke svc request
               #(governance/document-content %1 %2 (http/project-id request) (http/param request :record_id))))


(defn discard-preview
  "读取受控作废的级联影响预览(只读, 不改变任何状态)."
  [svc kind request]
  (http/invoke svc request
               #(governance/discard-preview %1 %2 (http/project-id request) kind (http/param request :record_id))))


(defn- form-body
  "把 multipart 表单字段转成领域命令体: 只取白名单键, version 转为整数 (表单值均为字符串)."
  [request]
  (let [params (:params request)
        version (get params "version" (get params :version))]
    (cond-> (into {} (for [k [:code :title :classification :stage :structure_node :category]
                           :let [v (get params (name k) (get params k))]
                           :when (and (string? v) (not (str/blank? v)))]
                       [k v]))
      (some? version) (assoc :version (if (string? version)
                                        (try (Long/parseLong version) (catch Exception _ version))
                                        version)))))


(defn- file-part
  "读取 multipart 文件部件 (ring 解析后的 {:filename :content-type :tempfile :size})."
  [request]
  (let [part (get-in request [:params "file"] (get-in request [:params :file]))]
    (when (map? part) part)))


(defn upload
  "multipart 上传真实文件登记证据文档首版; 文件按内容寻址落盘, 记录保存元数据与 SHA256."
  [svc request]
  (http/invoke svc request
               #(governance/document-upload %1 %2 (http/project-id request) (form-body request) (file-part request))))


(defn upload-revision
  "multipart 上传真实文件新增不可变修订."
  [svc request]
  (http/invoke svc request
               #(governance/document-upload-revision %1 %2 (http/project-id request) (http/param request :record_id)
                                                     (form-body request) (file-part request))))


(defn- encoded-filename
  [filename]
  (.replace (URLEncoder/encode ^String filename "UTF-8") "+" "%20"))


(defn- serve-document
  "统一的文档字节下发: 文本证据取 UTF-8 字节, 二进制证据读取物理文件并复核 SHA256; disposition 为 attachment 或 inline."
  [svc request disposition]
  (let [result (http/invoke svc request
                            #(governance/document-content %1 %2 (http/project-id request)
                                                          (http/param request :record_id)))]
    (if-not (= 200 (:status result)) result
            (let [document (get-in result [:body :data])]
              (if (and (= "inline" disposition) (= "file" (:content_kind document)) (not (:preview document)))
                (http/reply 415 "该文件类型不支持在线预览, 请下载后查看" nil)
                (let [bytes-result (try {:bytes (governance/document-bytes svc document)}
                                        (catch clojure.lang.ExceptionInfo e
                                          (if (:pms-error (ex-data e)) {:error (http/reply (:status (ex-data e)) (.getMessage e) nil)} (throw e))))]
                  (or (:error bytes-result)
                      (-> (response/response (java.io.ByteArrayInputStream. ^bytes (:bytes bytes-result)))
                          (response/content-type (:content_type document))
                          (response/header "Content-Length" (alength ^bytes (:bytes bytes-result)))
                          (response/header "X-Content-SHA256" (:sha256 document))
                          (response/header "X-Content-Kind" (or (:content_kind document) "text"))
                          (response/header "Content-Disposition"
                                           (str disposition "; filename*=UTF-8''" (encoded-filename (:filename document))))))))))))


(defn download
  "下载经项目授权验证的持久化文件版本 (文本或二进制), 响应头携带 SHA256 供客户端比对."
  [svc request]
  (serve-document svc request "attachment"))


(defn preview
  "内联预览 (PDF / 图片 / 文本) 经项目授权与密级校验的文件版本; 不可预览类型返回 415."
  [svc request]
  (serve-document svc request "inline"))


(defn appointment-content
  "读取统一JSON信封中的确定任命书正文与团队快照."
  [svc request]
  (http/invoke svc request
               #(governance/appointment-content %1 %2 (http/project-id request) (http/param request :record_id))))


(defn appointment-download
  "下载经项目授权验证的任命书正文, 附带团队快照摘要."
  [svc request]
  (let [result (http/invoke svc request
                            #(governance/appointment-content %1 %2 (http/project-id request)
                                                             (http/param request :record_id)))]
    (if-not (= 200 (:status result)) result
            (let [appt (get-in result [:body :data])]
              (-> (response/response (:content appt))
                  (response/content-type "text/plain; charset=utf-8")
                  (response/header "X-Content-SHA256" (:snapshot_sha256 appt))
                  (response/header "Content-Disposition"
                                   (str "attachment; filename*=UTF-8''"
                                        (.replace (URLEncoder/encode (str "appointment-v" (:revision appt) ".txt") "UTF-8") "+" "%20"))))))))


(defn- zip-entry-name
  "以记录ID前缀保证ZIP内文件名唯一, 保留原始文件名可读."
  [doc]
  (str (subs (:id doc) 0 8) "_" (:filename doc)))


(defn- zip-response
  "把已复核的字节按文档打包为 ZIP, 附 MANIFEST.tsv 供逐文件摘要校验."
  [docs byte-arrays]
  (let [baos (ByteArrayOutputStream.)
        zip (ZipOutputStream. baos)]
    (doseq [[doc bytes] (map vector docs byte-arrays)]
      (.putNextEntry zip (ZipEntry. (zip-entry-name doc)))
      (.write zip ^bytes bytes)
      (.closeEntry zip))
    (let [manifest (str/join "\n"
                             (cons "record_id\tcode\trevision\tentry\tsha256\tbyte_size\tclassification\tstage\tstructure_node\tcontent_kind\tcategory"
                                   (mapv (fn [doc]
                                           (str (:id doc) "\t" (:code doc) "\t" (:revision doc) "\t"
                                                (zip-entry-name doc) "\t" (:sha256 doc) "\t" (:byte_size doc) "\t"
                                                (:classification doc) "\t" (:stage doc) "\t" (:structure_node doc) "\t"
                                                (or (:content_kind doc) "text") "\t" (or (:category doc) "")))
                                         docs)))]
      (.putNextEntry zip (ZipEntry. "MANIFEST.tsv"))
      (.write zip (.getBytes ^String manifest StandardCharsets/UTF_8))
      (.closeEntry zip))
    (.close zip)
    (-> (response/response (.toByteArray baos))
        (response/content-type "application/zip")
        (response/header "X-Batch-Count" (count docs))
        (response/header "Content-Disposition" "attachment; filename*=UTF-8documents.zip"))))


(defn batch-download
  "把经项目授权验证的多个文件版本 (文本或二进制, 二进制读取时复核 SHA256) 打包为ZIP下载, 附清单供逐文件摘要校验."
  [svc request]
  (let [result (http/invoke svc request
                            #(governance/document-batch %1 %2 (http/project-id request) (:body-params request)))]
    (if-not (= 200 (:status result)) result
            (let [docs (get-in result [:body :data :documents])
                  loaded (try {:bytes (mapv #(governance/document-bytes svc %) docs)}
                              (catch clojure.lang.ExceptionInfo e
                                (if (:pms-error (ex-data e)) {:error (http/reply (:status (ex-data e)) (.getMessage e) nil)} (throw e))))]
              (or (:error loaded)
                  (zip-response docs (:bytes loaded)))))))
