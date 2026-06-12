(ns com.ruoyi.rouyi.web.controllers.system.file-test
  "文件控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [com.ruoyi.rouyi.web.controllers.system.file :as file]))

(deftest test-list-files
  (testing "获取文件列表"
    (let [response (file/list-files {})]
      (is (map? response)))))

(deftest test-upload-file
  (testing "上传文件"
    (let [request {:multipart-params {"file" {:filename "test.txt" :content-type "text/plain" :tempfile (java.io.File/createTempFile "test" ".txt")}}}
          response (file/upload-file {} request)]
      (is (map? response)))))

(deftest test-download-file
  (testing "下载文件"
    (let [request {:query-params {:fileName "test.txt"}}
          response (file/download-file {} request)]
      (is (map? response)))))

(deftest test-delete-file
  (testing "删除文件"
    (let [request {:query-params {:fileName "test.txt"}}
          response (file/delete-file {} request)]
      (is (map? response)))))
