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


(defn download
  "下载经项目授权验证的持久化文本文件版本."
  [svc request]
  (let [result (http/invoke svc request
                            #(governance/document-content %1 %2 (http/project-id request)
                                                          (http/param request :record_id)))]
    (if-not (= 200 (:status result)) result
            (let [document (get-in result [:body :data])]
              (-> (response/response (:content document))
                  (response/content-type (:content_type document))
                  (response/header "X-Content-SHA256" (:sha256 document))
                  (response/header "Content-Disposition"
                                   (str "attachment; filename*=UTF-8''" (.replace (URLEncoder/encode (:filename document) "UTF-8") "+" "%20"))))))))


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


(defn batch-download
  "把经项目授权验证的多个文本文件版本打包为ZIP下载, 附清单供逐文件摘要校验."
  [svc request]
  (let [result (http/invoke svc request
                            #(governance/document-batch %1 %2 (http/project-id request) (:body-params request)))]
    (if-not (= 200 (:status result)) result
            (let [docs (get-in result [:body :data :documents])
                  baos (ByteArrayOutputStream.)
                  zip (ZipOutputStream. baos)]
              (doseq [doc docs]
                (let [bytes (.getBytes ^String (:content doc) StandardCharsets/UTF_8)]
                  (.putNextEntry zip (ZipEntry. (zip-entry-name doc)))
                  (.write zip bytes)
                  (.closeEntry zip)))
              (let [manifest (str/join "\n"
                                       (cons "record_id\tcode\trevision\tentry\tsha256\tbyte_size\tclassification\tstage\tstructure_node"
                                             (mapv (fn [doc]
                                                     (str (:id doc) "\t" (:code doc) "\t" (:revision doc) "\t"
                                                          (zip-entry-name doc) "\t" (:sha256 doc) "\t" (:byte_size doc) "\t"
                                                          (:classification doc) "\t" (:stage doc) "\t" (:structure_node doc)))
                                                   docs)))]
                (.putNextEntry zip (ZipEntry. "MANIFEST.tsv"))
                (.write zip (.getBytes ^String manifest StandardCharsets/UTF_8))
                (.closeEntry zip))
              (.close zip)
              (-> (response/response (.toByteArray baos))
                  (response/content-type "application/zip")
                  (response/header "X-Batch-Count" (count docs))
                  (response/header "Content-Disposition" "attachment; filename*=UTF-8documents.zip"))))))
