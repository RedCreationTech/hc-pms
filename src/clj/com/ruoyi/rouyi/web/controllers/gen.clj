(ns com.ruoyi.rouyi.web.controllers.gen
  "代码生成器控制器。"
  (:require
   [com.ruoyi.rouyi.domain.gen :as gen-service]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

;; ──── 表列表 ────

(defn list-tables
  "查询数据库中的所有表（含注释）。"
  [{:keys [gen-service]} _request]
  (ok (gen-service/list-tables gen-service)))

;; ──── 表列信息 ────

(defn table-columns
  "查询指定表的列信息（含注释）。"
  [{:keys [gen-service]} request]
  (let [table-name (get-in request [:query-params :tableName])]
    (when (seq table-name)
      (ok (gen-service/table-columns gen-service table-name)))))

;; ──── 代码预览 ────

(defn preview-code
  "生成并返回指定表的完整代码模板。"
  [{:keys [gen-service]} request]
  (let [table-name (get-in request [:query-params :tableName])]
    (when (seq table-name)
      (ok (gen-service/generate-code gen-service table-name)))))

;; ──── 批量代码生成 ────

(defn batch-generate
  "为多个表批量生成代码。"
  [{:keys [gen-service]} request]
  (let [table-names (get-in request [:body-params :tables] [])]
    (ok (mapv #(gen-service/generate-code gen-service %) table-names))))
