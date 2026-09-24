(ns com.ruoyi.domain.pms.governance.files
  "证据文件的二进制存储: 内容寻址 (存储键 = 项目/SHA256), 落盘后不可变, 读取时复核摘要.
   目录由服务配置 :file-dir (环境变量 PMS_FILE_DIR, 缺省 data/pms-files) 决定, 大小上限由 :file-max-mb (PMS_FILE_MAX_MB, 缺省 50) 决定.
   这里只管字节与摘要, 不做业务授权; 密级与项目范围由调用方在读取记录时校验."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.io
      File
      InputStream)
    (java.math
      BigInteger)
    (java.security
      DigestInputStream
      MessageDigest)))


(def allowed-extensions
  "工程证据允许的文件扩展名白名单 (小写); 可执行与脚本类型不在其中."
  #{"pdf" "png" "jpg" "jpeg" "gif" "webp" "bmp" "svg" "txt" "csv" "md" "log" "json" "xml"
    "doc" "docx" "xls" "xlsx" "ppt" "pptx" "zip" "7z" "dwg" "dxf" "step" "stp" "igs" "iges"})


(def content-types
  "按扩展名确定的服务端 MIME, 不信任客户端声明."
  {"pdf" "application/pdf" "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "webp" "image/webp" "bmp" "image/bmp" "svg" "image/svg+xml" "txt" "text/plain; charset=utf-8"
   "csv" "text/csv; charset=utf-8" "md" "text/markdown; charset=utf-8" "log" "text/plain; charset=utf-8"
   "json" "application/json" "xml" "application/xml" "doc" "application/msword"
   "docx" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
   "xls" "application/vnd.ms-excel" "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
   "ppt" "application/vnd.ms-powerpoint" "pptx" "application/vnd.openxmlformats-officedocument.presentationml.presentation"
   "zip" "application/zip" "7z" "application/x-7z-compressed" "dwg" "application/octet-stream" "dxf" "application/octet-stream"
   "step" "application/octet-stream" "stp" "application/octet-stream" "igs" "application/octet-stream" "iges" "application/octet-stream"})


(def inline-previewable
  "浏览器可直接内联预览的类型 (PDF, 图片, 文本); 其余只提供下载."
  #{"pdf" "png" "jpg" "jpeg" "gif" "webp" "bmp" "svg" "txt" "csv" "md" "log" "json" "xml"})


(defn extension
  "取文件名的小写扩展名, 无扩展名返回空串."
  [filename]
  (let [i (.lastIndexOf ^String filename ".")]
    (if (neg? i) "" (str/lower-case (subs filename (inc i))))))


(defn dir
  "证据文件根目录."
  ^File [svc]
  (io/file (or (:file-dir svc) (System/getenv "PMS_FILE_DIR") "data/pms-files")))


(defn max-bytes
  "单个文件的字节上限."
  [svc]
  (* 1048576 (long (or (:file-max-mb svc) (some-> (System/getenv "PMS_FILE_MAX_MB") Long/parseLong) 50))))


(defn sha256
  "流式计算 SHA256 十六进制摘要."
  [source]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (DigestInputStream. (io/input-stream source) digest)]
      (let [buffer (byte-array 65536)]
        (loop [] (when-not (neg? (.read in buffer)) (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))


(defn filename!
  "校验上传文件名: 非空, 不含路径分隔符, 扩展名在白名单内."
  [filename]
  (let [name (r/text! filename "文件名" 150 true)]
    (when (re-find #"[/\\\r\n\u0000]" name) (r/fail! 400 "文件名非法"))
    (when-not (contains? allowed-extensions (extension name))
      (r/fail! 400 (str "不允许的文件类型: " (extension name))))
    name))


(defn store!
  "把上传的临时文件写入内容寻址位置, 返回 {:storage_key :sha256 :byte_size :content_type :preview}.
   同一项目内相同内容共用一份物理文件; 已存在的文件不会被覆盖 (不可变)."
  [svc project-id filename ^File tempfile]
  (when-not (and tempfile (.exists tempfile)) (r/fail! 400 "缺少上传文件"))
  (let [size (.length tempfile)]
    (when (zero? size) (r/fail! 400 "上传文件为空"))
    (when (> size (max-bytes svc)) (r/fail! 400 (str "文件超过上限 " (quot (max-bytes svc) 1048576) "MiB")))
    (let [sha (sha256 tempfile)
          ext (extension filename)
          target (io/file (dir svc) project-id sha)]
      (io/make-parents target)
      (when-not (.exists target)
        (let [tmp (io/file (dir svc) project-id (str sha ".part"))]
          (io/copy tempfile tmp)
          (when-not (.renameTo tmp target)
            (.delete tmp)
            (when-not (.exists target) (r/fail! 500 "证据文件写入失败")))))
      {:storage_key (str project-id "/" sha) :sha256 sha :byte_size size
       :content_type (get content-types ext "application/octet-stream")
       :preview (contains? inline-previewable ext)})))


(defn file-of
  "按存储键定位物理文件, 键必须是 项目/摘要 形式, 不接受任意路径."
  ^File [svc storage-key]
  (when-not (and (string? storage-key) (re-matches #"[A-Za-z0-9-]+/[0-9a-f]{64}" storage-key))
    (r/fail! 500 "证据文件存储键非法"))
  (io/file (dir svc) storage-key))


(defn verified-bytes
  "读取整个文件并复核 SHA256; 摘要不一致时拒绝提供, 避免把被篡改或损坏的证据当作原件下发."
  ^bytes [svc document]
  (let [file (file-of svc (:storage_key document))]
    (when-not (.exists file) (r/fail! 500 (str "证据文件缺失: " (:code document))))
    (let [bytes (with-open [in ^InputStream (io/input-stream file)]
                  (let [out (java.io.ByteArrayOutputStream. (int (.length file)))]
                    (io/copy in out)
                    (.toByteArray out)))
          actual (sha256 bytes)]
      (when-not (= actual (:sha256 document))
        (r/fail! 500 (str "证据文件校验失败: " (:code document))))
      bytes)))
