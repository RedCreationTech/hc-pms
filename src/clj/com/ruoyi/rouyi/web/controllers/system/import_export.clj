(ns com.ruoyi.rouyi.web.controllers.system.import-export
  "用户导入导出控制器，使用 multipart 上传与 clojure.data.csv。"
  (:require
    [com.ruoyi.rouyi.domain.system.user :as user-service]
    [com.ruoyi.rouyi.infra.data-perm :as data-perm]
    [ring.util.response :as response]
    [ring.middleware.multipart-params :as multipart]
    [clojure.data.csv :as csv]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn- parse-int [v]
  (when (and v (not (str/blank? (str v))))
    (try (Integer/parseInt (str v))
         (catch Exception _ nil))))

(defn- csv-row->user
  "将 CSV 行向量转换为用户参数映射。"
  [headers row]
  (let [m (zipmap headers row)]
    {:user_name (str/trim (get m "user_name" ""))
     :nick_name (str/trim (get m "nick_name" ""))
     :email (str/trim (get m "email" ""))
     :phonenumber (str/trim (get m "phonenumber" ""))
     :sex (or (str/trim (get m "sex" "0")) "0")
     :status (or (str/trim (get m "status" "0")) "0")
     :dept_id (or (parse-int (get m "dept_id")) 1)
     :user_type "00"
     :avatar ""
     :remark (str/trim (get m "remark" ""))}))

(defn- read-csv-rows
  "读取 multipart 上传的 CSV 文件，返回行向量列表。"
  [file]
  (let [tempfile (:tempfile file)]
    (with-open [reader (io/reader tempfile :encoding "UTF-8")]
      (doall (csv/read-csv reader)))))

(defn import-users
  "批量导入用户（multipart CSV）。"
  [{:keys [user-service]} request]
  (try
    (let [multipart-params (:multipart-params request)
          file (get multipart-params "file")
          identity (:identity request)
          _ (when (or (nil? file) (str/blank? (:filename file "")))
              (throw (Exception. "请选择要上传的文件")))
          rows (read-csv-rows file)
          headers (mapv str/trim (first rows))
          data-rows (rest rows)
          default-password "123456"
          results (mapv (fn [row]
                          (try
                            (let [user (csv-row->user headers row)]
                              (when (str/blank? (:user_name user))
                                (throw (Exception. "用户名不能为空")))
                              (when (str/blank? (:nick_name user))
                                (throw (Exception. "用户昵称不能为空")))
                              (user-service/create-user! user-service
                                                         (assoc user
                                                                :password default-password
                                                                :roles []
                                                                :posts []
                                                                :create_by (:user_name identity "")))
                              {:user_name (:user_name user) :status "success"})
                            (catch Exception e
                              {:user_name (first row) :status "failed" :msg (.getMessage e)})))
                        data-rows)
          success-count (count (filter #(= "success" (:status %)) results))
          failed-count (count (filter #(= "failed" (:status %)) results))]
      (ok {:total (count results)
           :success success-count
           :failed failed-count
           :details results}))
    (catch Exception e
      (fail (.getMessage e)))))

(defn- user->csv-row
  "将用户映射转换为 CSV 行向量。"
  [user]
  [(:user_name user)
   (:nick_name user)
   (:email user)
   (:phonenumber user)
   (:sex user)
   (:status user)
   (:dept_id user)
   (:remark user)])

(defn export-users
  "导出用户为 CSV 文件（带数据权限过滤）。"
  [{:keys [user-service]} request]
  (try
    (let [identity (:identity request)
          raw (:query-params request)
          data-perm-filter (data-perm/data-perm-filter identity "default" :alias "u")
          params (merge {:page-num 1 :page-size 10000}
                        (dissoc raw "page" "size")
                        (:params data-perm-filter))
          result (user-service/list-users user-service params)
          rows (:rows result)
          header ["user_name" "nick_name" "email" "phonenumber" "sex" "status" "dept_id" "remark"]
          csv-lines (mapv user->csv-row rows)
          output (java.io.StringWriter.)]
      (csv/write-csv output (cons header csv-lines)
                     :separator \, :quote \")
      (let [csv-str (str output)
            bom "\uFEFF"
            content (str bom csv-str)]
        (-> (response/response content)
            (response/header "Content-Type" "text/csv; charset=utf-8")
            (response/header "Content-Disposition" "attachment; filename=users.csv"))))
    (catch Exception e
      (fail (.getMessage e)))))

(defn import-template
  "下载用户导入模板。"
  [_ _]
  (let [header ["user_name" "nick_name" "email" "phonenumber" "sex" "status" "dept_id" "remark"]
        sample ["admin" "管理员" "admin@ruoyi.vip" "13800138000" "0" "0" "1" ""]
        output (java.io.StringWriter.)]
    (csv/write-csv output [header sample] :separator \, :quote \")
    (let [csv-str (str output)
          bom "\uFEFF"
          content (str bom csv-str)]
      (-> (response/response content)
          (response/header "Content-Type" "text/csv; charset=utf-8")
          (response/header "Content-Disposition" "attachment; filename=user_import_template.csv")))))
