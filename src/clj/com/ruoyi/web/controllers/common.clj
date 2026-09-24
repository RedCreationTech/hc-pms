(ns com.ruoyi.web.controllers.common
  "通用控制器 -- 文件上传,下载,资源访问 (需登录).
  存储文件名由服务端生成; 下载只接受上传目录内的单层文件名, 并校验解析后的真实路径仍在目录内."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [ring.util.response :as response]))


(def upload-dir "uploads/")
(def resource-dir "resources/public/")


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(def ^:private safe-name #"^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")


(defn- extension
  "取原始文件名中的扩展名 (只保留字母数字, 最长 10 位)."
  [filename]
  (some->> (str filename) (re-find #"\.([A-Za-z0-9]{1,10})$") second str/lower-case (str ".")))


(defn resolve-inside
  "在目录 dir 内解析单层文件名; 名称不合法或解析结果越出目录时返回 nil."
  [dir filename]
  (when (and (string? filename) (re-matches safe-name filename) (not (str/includes? filename "..")))
    (let [base (.getCanonicalFile (io/file dir))
          file (.getCanonicalFile (io/file base filename))]
      (when (= (.getParentFile file) base)
        file))))


(defn upload
  "通用文件上传: 以服务端生成的名称保存, 返回存储名与原始文件名."
  [_ request]
  (try
    (let [dir (io/file upload-dir)]
      (when-not (.exists dir) (.mkdirs dir))
      (let [file (get-in request [:params :file])
            temp-file (:tempfile file)
            original (:filename file)]
        (if (and temp-file original)
          (let [stored (str (java.util.UUID/randomUUID) (or (extension original) ""))
                target (io/file dir stored)]
            (io/copy temp-file target)
            (ok {:fileName stored :originalFilename original :url (str "/uploads/" stored)}))
          (ok 500 "上传失败" nil))))
    (catch Exception e (ok 500 (.getMessage e) nil))))


(defn download
  "通用文件下载 (上传目录内的单层文件名)."
  [_ request]
  (let [filename (get-in request [:query-params "fileName"] (get-in request [:query-params :fileName]))
        file (resolve-inside upload-dir filename)]
    (cond
      (nil? file) (ok 400 "文件名不合法" nil)
      (.exists file) (-> (response/response file)
                         (response/header "Content-Disposition" (str "attachment; filename=\"" (.getName file) "\""))
                         (response/content-type "application/octet-stream"))
      :else (ok 404 "文件不存在" nil))))


(defn download-resource
  "下载公开资源目录内的单层文件."
  [_ request]
  (let [resource-path (get-in request [:query-params "resource"] (get-in request [:query-params :resource]))
        file (resolve-inside resource-dir resource-path)]
    (cond
      (nil? file) (ok 400 "资源名不合法" nil)
      (.isFile file) (-> (response/response file)
                         (response/header "Content-Disposition" (str "attachment; filename=\"" (.getName file) "\""))
                         (response/content-type "application/octet-stream"))
      :else (ok 404 "资源不存在" nil))))
