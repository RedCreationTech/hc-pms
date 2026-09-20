(ns com.ruoyi.web.controllers.common
  "通用控制器 — 文件上传、下载、资源访问。"
  (:require
    [clojure.java.io :as io]
    [ring.util.response :as response]))


(def upload-dir "uploads/")
(def resource-dir "resources/")


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn upload
  "通用文件上传。"
  [_ request]
  (try
    (let [dir (io/file upload-dir)]
      (when-not (.exists dir) (.mkdirs dir))
      (let [file (get-in request [:params :file])
            temp-file (:tempfile file)
            filename (:filename file)]
        (if (and temp-file filename)
          (let [target (io/file upload-dir filename)]
            (io/copy temp-file target)
            (ok {:fileName filename :url (str "/uploads/" filename)}))
          (ok 500 "上传失败" nil))))
    (catch Exception e (ok 500 (.getMessage e) nil))))


(defn download
  "通用文件下载。"
  [_ request]
  (let [filename (get-in request [:query-params :fileName])
        file (io/file upload-dir filename)]
    (if (.exists file)
      (-> (response/response file)
          (response/header "Content-Disposition" (str "attachment; filename=\"" filename "\""))
          (response/content-type "application/octet-stream"))
      (ok 404 "文件不存在" nil))))


(defn download-resource
  "下载资源文件。"
  [_ request]
  (let [resource-path (get-in request [:query-params :resource])
        file (io/file resource-dir resource-path)]
    (if (and resource-path (.exists file))
      (-> (response/response file)
          (response/header "Content-Disposition" (str "attachment; filename=\"" (.getName file) "\""))
          (response/content-type "application/octet-stream"))
      (ok 404 "资源不存在" nil))))
