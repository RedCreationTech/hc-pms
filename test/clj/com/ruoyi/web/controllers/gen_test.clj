(ns com.ruoyi.web.controllers.gen-test
  "代码生成器控制器测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [com.ruoyi.web.controllers.gen :as gen]))

(def mock-columns
  [{:name "id" :type "INTEGER" :pk 1 :notnull 1}
   {:name "name" :type "varchar" :is_nullable "YES"}])

(def mock-gen-service
  {:query-fn (fn [q _params]
               (case q
                 :gen-tables [{:name "sys_gen_test"}]
                 :gen-columns mock-columns
                 []))})

(deftest test-list-tables
  (testing "查询所有表"
    (let [request {}
          response (gen/list-tables {:gen-service mock-gen-service} request)]
      (is (= 200 (:status response)))
      (is (= 200 (get-in response [:body :code])))
      (is (= [{:name "sys_gen_test"}] (get-in response [:body :data]))))))

(deftest test-table-columns
  (testing "查询表列信息"
    (let [request {:query-params {:tableName "sys_gen_test"}}
          response (gen/table-columns {:gen-service mock-gen-service} request)]
      (is (= 200 (:status response)))
      (is (= 2 (count (get-in response [:body :data]))))))
  (testing "缺少表名返回 nil"
    (is (nil? (gen/table-columns {:gen-service mock-gen-service} {:query-params {}})))))

(deftest test-preview-code
  (testing "预览代码"
    (let [request {:query-params {:tableName "sys_gen_test"}}
          response (gen/preview-code {:gen-service mock-gen-service} request)]
      (is (= 200 (:status response)))
      (is (= "gen-test" (get-in response [:body :data :kebab-name])))
      (is (string? (get-in response [:body :data :backend-domain])))))
  (testing "缺少表名返回 nil"
    (is (nil? (gen/preview-code {:gen-service mock-gen-service} {:query-params {}})))))

(deftest test-batch-generate
  (testing "批量生成"
    (let [request {:body-params {:tables ["sys_gen_test"]}}
          response (gen/batch-generate {:gen-service mock-gen-service} request)]
      (is (= 200 (:status response)))
      (is (= 1 (count (get-in response [:body :data]))))
      (is (= "gen-test" (get-in response [:body :data 0 :kebab-name])))))
  (testing "空表列表"
    (let [response (gen/batch-generate {:gen-service mock-gen-service} {:body-params {}})]
      (is (= 200 (:status response)))
      (is (= [] (get-in response [:body :data]))))))

(deftest test-deploy-code-failure
  (testing "无法生成 kebab 名时返回 500"
    (let [empty-service {:query-fn (fn [_ _] [])}
          request {:body-params {:tableName "sys_"}}
          response (gen/deploy-code {:gen-service empty-service} request)]
      (is (= 200 (:status response)))
      (is (= 500 (get-in response [:body :code])))
      (is (= "生成失败" (get-in response [:body :msg])))
      (is (= "无法生成代码" (get-in response [:body :data :error]))))))

(deftest test-deploy-code-success
  (testing "部署代码成功"
    (let [request {:body-params {:tableName "sys_gen_test"}}
          kebab "gen-test"
          generated-sql-file (io/file "resources/sql/generated.sql")
          original-generated (when (.exists generated-sql-file) (slurp generated-sql-file))
          response (gen/deploy-code {:gen-service mock-gen-service} request)]
      (try
        (is (= 200 (:status response)))
        (is (= "操作成功" (get-in response [:body :msg])))
        (is (string? (get-in response [:body :data :routes])))
        (is (some #(= "resources/sql/generated.sql" %) (get-in response [:body :data :written])))
        (finally
          (if original-generated
            (spit generated-sql-file original-generated)
            (.delete generated-sql-file))
          (doseq [path [(str "resources/sql/" kebab ".sql")
                        (str "src/clj/com/ruoyi/domain/system/" kebab ".clj")
                        (str "src/clj/com/ruoyi/web/controllers/system/" kebab ".clj")
                        (str "src/cljs/com/ruoyi/frontend/api/" kebab ".cljs")
                        (str "src/cljs/com/ruoyi/frontend/pages/" kebab ".cljs")
                        (str "src/cljs/com/ruoyi/frontend/events/" kebab ".cljs")
                        (str "src/cljs/com/ruoyi/frontend/subs/" kebab ".cljs")]]
            (.delete (io/file path)))
          (doseq [f (file-seq (io/file "resources/migrations-sqlite"))]
            (when (and (.isFile f) (.contains (.getName f) (str "_" kebab)))
              (.delete f))))))))

(deftest test-download-code-empty
  (testing "空表列表返回 400"
    (let [response (gen/download-code {:gen-service mock-gen-service} {:body-params {}})]
      (is (= 200 (:status response)))
      (is (= 400 (get-in response [:body :code])))
      (is (= "请选择要生成的表" (get-in response [:body :msg]))))))

(deftest test-download-code-success
  (testing "批量生成并打包下载"
    (let [response (gen/download-code {:gen-service mock-gen-service}
                                      {:body-params {:tables ["sys_gen_test"]}})]
      (try
        (is (= 200 (:status response)))
        (is (= "application/zip" (get-in response [:headers "Content-Type"])))
        (is (some? (:body response)))
        (finally
          (when-let [body (:body response)]
            (when (instance? java.io.File body)
              (let [zip-path (.getPath body)
                    temp-dir (io/file (.replace zip-path ".zip" ""))]
                (.delete body)
                (doseq [f (reverse (file-seq temp-dir))]
                  (.delete f))))))))))
