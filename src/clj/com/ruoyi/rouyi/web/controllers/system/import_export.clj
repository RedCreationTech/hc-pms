(ns com.ruoyi.rouyi.web.controllers.system.import-export
  "用户导入导出控制器。"
  (:require
    [clojure.data.csv :as csv]
    [clojure.java.io :as io]
    [ring.util.response :as response]
    [ring.util.mime-type :as mime]))

(defn- ok
  ([data] (response/response {:code 200 :msg "操作成功" :data data}))
  ([data filename]
   (-> (response/response data)
       (response/header "Content-Disposition" (str "attachment; filename=" filename))
       (response/content-type "text/csv; charset=utf-8"))))

(defn export-users
  "导出用户数据为 CSV。"
  [{:keys [user-service]} _]
  (let [result (user-service/list-users user-service {})
        users (:rows result)
        headers ["用户ID" "用户名称" "昵称" "部门" "手机号" "邮箱" "性别" "状态" "创建时间"]
        rows (mapv (fn [u]
                     [(str (:user_id u)) (:user_name u) (:nick_name u) (:dept_name u)
                      (:phonenumber u) (:email u) (str (:sex u)) (str (:status u)) (:create_time u)])
                   users)
        csv-data (with-out-str (csv/write-csv *out* (cons headers rows)))]
    (ok csv-data "users_export.csv")))

(defn import-users
  "从上传的 CSV 文件批量导入用户。"
  [{:keys [user-service]} request]
  (try
    (let [file (get-in request [:params :file])
          temp-file (:tempfile file)]
      (if temp-file
        (let [rows (with-open [rdr (io/reader temp-file)]
                     (csv/read-csv rdr))
              data-rows (rest rows)
              imported (count data-rows)]
          (doseq [row data-rows]
            (try
              (when (>= (count row) 3)
                (user-service/create-user! user-service
                  {:user_name (nth row 1 "")
                   :nick_name (nth row 2 "")
                   :password "123456"
                   :phonenumber (nth row 4 "")
                   :email (nth row 5 "")
                   :sex (nth row 6 "")
                   :status "0"}))
              (catch Exception _)))
          (ok {:imported imported}))
        (ok {:imported 0})))
    (catch Exception e
      (response/response {:code 500 :msg (.getMessage e)}))))
