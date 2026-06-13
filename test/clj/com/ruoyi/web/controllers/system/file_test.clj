(ns com.ruoyi.web.controllers.system.file-test
  "文件管理控制器测试。"
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [com.ruoyi.web.controllers.system.file :as file]))

(defn- temp-dir
  "创建临时目录，返回 java.io.File 对象。"
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "file-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    dir))

(defn- clean-dir!
  "递归删除目录及其内容。"
  [dir]
  (when (.exists dir)
    (doseq [f (file-seq dir)]
      (when (not= f dir)
        (.delete f)))
    (.delete dir)))

(defmacro with-temp-upload-dir
  "将 file/upload-dir 临时绑定到 temp-dir，并在测试后清理。"
  [& body]
  `(let [dir# (temp-dir)]
     (try
       (with-redefs [file/upload-dir (.getPath dir#)]
         ~@body)
       (finally
         (clean-dir! dir#)))))

(deftest test-list-files-empty
  (testing "空目录时返回空列表"
    (with-temp-upload-dir
      (let [response (file/list-files {} {})
            body (:body response)]
        (is (= 200 (:status response)))
        (is (= 200 (:code body)))
        (is (empty? (:data body)))))))

(deftest test-list-files
  (testing "返回上传文件列表"
    (with-temp-upload-dir
      (let [f (io/file file/upload-dir "test.txt")]
        (spit f "hello")
        (.setLastModified f 1609459200000)
        (let [response (file/list-files {} {})
              files (:data (:body response))]
          (is (= 200 (:status response)))
          (is (= 1 (count files)))
          (is (= "test.txt" (:name (first files))))
          (is (= 5 (:size (first files))))
          (is (int? (:modified (first files)))))))))

(deftest test-upload-file-success
  (testing "成功上传文件"
    (with-temp-upload-dir
      (let [temp-file (java.io.File/createTempFile "upload" ".txt")]
        (try
          (spit temp-file "upload content")
          (let [request {:params {:file {:tempfile temp-file
                                         :filename "uploaded.txt"}}}
                response (file/upload-file {} request)
                body (:body response)
                target (io/file file/upload-dir "uploaded.txt")]
            (is (= 200 (:status response)))
            (is (= 200 (:code body)))
            (is (= "uploaded.txt" (get-in body [:data :name])))
            (is (= 14 (get-in body [:data :size])))
            (is (.exists target)))
          (finally
            (.delete temp-file)))))))

(deftest test-upload-file-missing
  (testing "缺少文件时上传失败"
    (with-temp-upload-dir
      (let [request {:params {}}
            response (file/upload-file {} request)
            body (:body response)]
        (is (= 200 (:status response)))
        (is (= 500 (:code body)))
        (is (= "上传失败" (:msg body)))
        (is (nil? (:data body)))))))

(deftest test-download-file-success
  (testing "成功下载文件"
    (with-temp-upload-dir
      (let [f (io/file file/upload-dir "report.pdf")]
        (spit f "pdf content")
        (let [request {:path-params {:filename "report.pdf"}}
              response (file/download-file {} request)]
          (is (= 200 (:status response)))
          (is (= f (:body response)))
          (is (= "application/octet-stream" (get-in response [:headers "Content-Type"])))
          (is (.contains (get-in response [:headers "Content-Disposition"]) "report.pdf")))))))

(deftest test-download-file-not-found
  (testing "下载不存在的文件"
    (with-temp-upload-dir
      (let [request {:path-params {:filename "missing.txt"}}
            response (file/download-file {} request)
            body (:body response)]
        (is (= 200 (:status response)))
        (is (= 404 (:code body)))
        (is (= "文件不存在" (:msg body)))))))

(deftest test-delete-file-success
  (testing "成功删除文件"
    (with-temp-upload-dir
      (let [f (io/file file/upload-dir "delete-me.txt")]
        (spit f "delete me")
        (let [request {:path-params {:filename "delete-me.txt"}}
              response (file/delete-file {} request)
              body (:body response)]
          (is (= 200 (:status response)))
          (is (= 200 (:code body)))
          (is (= "操作成功" (:msg body)))
          (is (= "删除成功" (:data body)))
          (is (not (.exists f))))))))

(deftest test-delete-file-not-found
  (testing "删除不存在的文件"
    (with-temp-upload-dir
      (let [request {:path-params {:filename "missing.txt"}}
            response (file/delete-file {} request)
            body (:body response)]
        (is (= 200 (:status response)))
        (is (= 404 (:code body)))
        (is (= "文件不存在" (:msg body)))))))
