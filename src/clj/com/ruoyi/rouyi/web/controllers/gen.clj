(ns com.ruoyi.rouyi.web.controllers.gen
  "代码生成器控制器。"
  (:require
    [com.ruoyi.rouyi.domain.gen :as gen-service]
    [ring.util.response :as response]))

(defn- ok ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn list-tables
  [{:keys [gen-service]} request]
  (ok (gen-service/list-tables gen-service)))

(defn preview-code
  [{:keys [gen-service]} request]
  (let [table-name (get-in request [:query-params :tableName])]
    (ok (gen-service/generate-code gen-service table-name))))
