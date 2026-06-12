(ns com.ruoyi.rouyi.web.controllers.gen
  "代码生成器控制器。"
  (:require
    [com.ruoyi.rouyi.domain.gen :as gen-service]
    [ring.util.response :as response]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import [java.io File]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn list-tables
  "查询数据库中的所有表。"
  [{:keys [gen-service]} _request]
  (ok (gen-service/list-tables gen-service)))

(defn table-columns
  "查询指定表的列信息。"
  [{:keys [gen-service]} request]
  (let [table-name (get-in request [:query-params :tableName])]
    (when (seq table-name)
      (ok (gen-service/table-columns gen-service table-name)))))

(defn preview-code
  "生成并预览代码。"
  [{:keys [gen-service]} request]
  (let [table-name (get-in request [:query-params :tableName])]
    (when (seq table-name)
      (ok (gen-service/generate-code gen-service table-name)))))

(defn batch-generate
  "批量生成代码。"
  [{:keys [gen-service]} request]
  (let [table-names (get-in request [:body-params :tables] [])]
    (ok (mapv #(gen-service/generate-code gen-service %) table-names))))

(defn deploy-code
  "部署生成的代码到项目（当前返回预览，不实际写入）。"
  [{:keys [gen-service]} request]
  (let [table-name (get-in request [:body-params :tableName])
        code (gen-service/generate-code gen-service table-name)]
    (ok {:message (str "代码生成成功: " table-name)
         :preview {:sql (count (:sql code ""))
                   :domain (count (:domain code ""))
                   :controller (count (:controller code ""))
                   :frontend (count (:frontend-page code ""))}})))
