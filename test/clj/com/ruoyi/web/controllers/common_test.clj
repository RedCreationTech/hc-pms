(ns com.ruoyi.web.controllers.common-test
  "通用控制器测试。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ruoyi.web.controllers.common :as common])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- temp-dir []
  (-> (Files/createTempDirectory "common-test" (make-array java.nio.file.attribute.FileAttribute 0))
      .toFile))

(defn- delete-recursive [^File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-recursive child)))
  (.delete f))

(defn- with-temp-dirs [test-fn]
  (let [uploads (temp-dir)
        resources (temp-dir)]
    (try
      (with-redefs [common/upload-dir (.getPath uploads)
                    common/resource-dir (.getPath resources)]
        (test-fn))
      (finally
        (delete-recursive uploads)
        (delete-recursive resources)))))

(use-fixtures :each with-temp-dirs)

(deftest test-upload-success
  (testing "通用文件上传成功"
    (let [source (File/createTempFile "source" ".txt")]
      (try
        (spit source "hello world")
        (let [request {:params {:file {:tempfile source :filename "hello.txt"}}}
              response (common/upload {} request)]
          (is (= 200 (:status response)))
          (is (= 200 (get-in response [:body :code])))
          (is (= "hello.txt" (get-in response [:body :data :fileName])))
          (is (= "/uploads/hello.txt" (get-in response [:body :data :url])))
          (let [target (io/file common/upload-dir "hello.txt")]
            (is (.exists target))
            (is (= "hello world" (slurp target)))))
        (finally
          (.delete source))))))

(deftest test-upload-missing-file
  (testing "上传请求缺少文件时返回失败"
    (let [response (common/upload {} {:params {}})]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (= "上传失败" (get-in response [:body :msg]))))))

(deftest test-upload-exception
  (testing "上传复制失败时返回异常信息"
    (let [response (common/upload {} {:params {:file {:tempfile (io/file "/nonexistent/path.txt")
                                                       :filename "x.txt"}}})]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (string? (get-in response [:body :msg]))))))

(deftest test-upload-creates-directory
  (testing "上传目录不存在时自动创建"
    (let [source (File/createTempFile "source" ".txt")
          nested (io/file common/upload-dir "new" "uploads")]
      (try
        (spit source "data")
        (with-redefs [common/upload-dir (.getPath nested)]
          (let [response (common/upload {} {:params {:file {:tempfile source :filename "nested.txt"}}})]
            (is (= 200 (:status response)))
            (is (= 200 (get-in response [:body :code])))
            (is (.exists nested))
            (is (.exists (io/file nested "nested.txt")))))
        (finally
          (.delete source))))))

(deftest test-download-success
  (testing "通用文件下载成功"
    (let [f (io/file common/upload-dir "report.txt")]
      (spit f "report content")
      (let [response (common/download {} {:query-params {:fileName "report.txt"}})]
        (is (= 200 (:status response)))
        (is (= "application/octet-stream" (get-in response [:headers "Content-Type"])))
        (is (= "attachment; filename=\"report.txt\"" (get-in response [:headers "Content-Disposition"])))
        (is (= "report content" (slurp (:body response))))))))

(deftest test-download-missing
  (testing "下载不存在的文件返回 404"
    (let [response (common/download {} {:query-params {:fileName "missing.txt"}})]
      (is (= 200 (:status response)))
      (is (= 404 (get-in response [:body :code])))
      (is (= "文件不存在" (get-in response [:body :msg]))))))

(deftest test-download-resource-success
  (testing "下载资源文件成功"
    (let [f (io/file common/resource-dir "templates" "demo.xlsx")]
      (.mkdirs (.getParentFile f))
      (spit f "resource content")
      (let [response (common/download-resource {} {:query-params {:resource "templates/demo.xlsx"}})]
        (is (= 200 (:status response)))
        (is (= "application/octet-stream" (get-in response [:headers "Content-Type"])))
        (is (= "attachment; filename=\"demo.xlsx\"" (get-in response [:headers "Content-Disposition"])))
        (is (= "resource content" (slurp (:body response))))))))

(deftest test-download-resource-missing
  (testing "下载不存在的资源返回 404"
    (let [response (common/download-resource {} {:query-params {:resource "missing.png"}})]
      (is (= 200 (:status response)))
      (is (= 404 (get-in response [:body :code])))
      (is (= "资源不存在" (get-in response [:body :msg]))))))

