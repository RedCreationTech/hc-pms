(ns com.ruoyi.web.controllers.system.file
  "文件管理控制器 — 上传、列表、下载、删除。"
  (:require
   [clojure.java.io :as io]
   [ring.util.response :as response]))

(def upload-dir "uploads/")

(defn- ensure-dir! []
  (let [dir (io/file upload-dir)]
    (when-not (.exists dir) (.mkdirs dir))))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn list-files
  "获取上传文件列表。"
  [_ _]
  (ensure-dir!)
  (let [dir (io/file upload-dir)
        files (when (.exists dir)
                (->> (.listFiles dir)
                     (filter #(.isFile %))
                     (mapv (fn [f]
                             {:name (.getName f)
                              :size (.length f)
                              :modified (.lastModified f)}))
                     (sort-by :modified)
                     reverse))]
    (ok files)))

(defn upload-file
  "上传文件。"
  [_ request]
  (try
    (ensure-dir!)
    (let [file (get-in request [:params :file])
          temp-file (:tempfile file)
          filename (:filename file)]
      (if (and temp-file filename)
        (let [target (io/file upload-dir filename)]
          (io/copy temp-file target)
          (ok {:name filename :size (.length target)}))
        (ok 500 "上传失败" nil)))
    (catch Exception e
      (ok 500 (.getMessage e) nil))))

(defn download-file
  "下载文件。"
  [_ request]
  (let [filename (get-in request [:path-params :filename])
        file (io/file upload-dir filename)]
    (if (.exists file)
      (-> (response/response file)
          (response/header "Content-Disposition" (str "attachment; filename=\"" filename "\""))
          (response/content-type "application/octet-stream"))
      (ok 404 "文件不存在" nil))))

(defn delete-file
  "删除文件。"
  [_ request]
  (let [filename (get-in request [:path-params :filename])
        file (io/file upload-dir filename)]
    (if (.exists file)
      (do (.delete file) (ok "删除成功"))
      (ok 404 "文件不存在" nil))))
