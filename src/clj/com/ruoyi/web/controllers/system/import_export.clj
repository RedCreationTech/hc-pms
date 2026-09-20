(ns com.ruoyi.web.controllers.system.import-export
  "用户导入导出控制器，使用 multipart 上传与 clojure.data.csv。"
  (:require
    [clojure.data.csv :as csv]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.ruoyi.domain.system.config :as config-service]
    [com.ruoyi.domain.system.dept :as dept-service]
    [com.ruoyi.domain.system.dict :as dict-service]
    [com.ruoyi.domain.system.menu :as menu-service]
    [com.ruoyi.domain.system.post :as post-service]
    [com.ruoyi.domain.system.role :as role-service]
    [com.ruoyi.domain.system.user :as user-service]
    [com.ruoyi.infra.data-perm :as data-perm]
    [ring.middleware.multipart-params :as multipart]
    [ring.util.response :as response]))


(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))


(defn- fail
  [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))


(defn- parse-int
  [v]
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


(defn- update-support?
  "判断导入请求是否允许覆盖已有用户。"
  [request]
  (let [v (or (get-in request [:query-params "updateSupport"])
              (get-in request [:params "updateSupport"])
              (get-in request [:body-params :updateSupport]))]
    (contains? #{"1" "true" "on" true 1} v)))


(defn- import-one-user!
  "导入单个用户，按 updateSupport 决定新增或覆盖。"
  [user-service identity update-support? default-password row-user]
  (when (str/blank? (:user_name row-user))
    (throw (Exception. "用户名不能为空")))
  (when (str/blank? (:nick_name row-user))
    (throw (Exception. "用户昵称不能为空")))
  (if-let [existing (user-service/find-user-by-name user-service (:user_name row-user))]
    (if update-support?
      (user-service/update-user! user-service
                                 (assoc row-user
                                        :user-id (:user_id existing)
                                        :roles []
                                        :posts []
                                        :update_by (:user_name identity "")))
      (throw (Exception. "登录账号不能重复")))
    (user-service/create-user! user-service
                               (assoc row-user
                                      :password default-password
                                      :roles []
                                      :posts []
                                      :create_by (:user_name identity "")))))


(defn import-users
  "批量导入用户（multipart CSV），支持 updateSupport 覆盖已有用户。"
  [{:keys [user-service]} request]
  (try
    (let [multipart-params (:multipart-params request)
          file (get multipart-params "file")
          identity (:identity request)
          update-support? (update-support? request)
          _ (when (or (nil? file) (str/blank? (:filename file "")))
              (throw (Exception. "请选择要上传的文件")))
          rows (read-csv-rows file)
          headers (mapv str/trim (first rows))
          data-rows (rest rows)
          default-password "123456"
          results (mapv (fn [row]
                          (try
                            (let [user (csv-row->user headers row)]
                              (import-one-user! user-service identity update-support? default-password user)
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


(defn- parse-id-list
  [ids]
  (->> (str/split (str ids) #",")
       (map str/trim)
       (remove str/blank?)
       (mapv parse-long)))


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
          selected-ids (set (parse-id-list (get raw "ids")))
          rows (cond->> (:rows result)
                 (seq selected-ids) (filter #(contains? selected-ids (:user_id %))))
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


;; ─── 通用导出函数 ──────────────────────────────────────────────────────

(defn- generic-export
  "通用导出函数。"
  [list-fn service params header csv-fn filename request]
  (try
    (let [result (list-fn service (merge {:page-num 1 :page-size 10000} params))
          rows (if (sequential? result) result (:rows result []))
          csv-lines (mapv csv-fn rows)
          output (java.io.StringWriter.)]
      (csv/write-csv output (cons header csv-lines) :separator \, :quote \")
      (let [csv-str (str output)
            bom "\uFEFF"
            content (str bom csv-str)]
        (-> (response/response content)
            (response/header "Content-Type" "text/csv; charset=utf-8")
            (response/header "Content-Disposition" (str "attachment; filename=" filename)))))
    (catch Exception e
      (fail (.getMessage e)))))


(defn export-roles
  "导出角色数据。"
  [{:keys [role-service]} request]
  (let [header ["role_id" "role_name" "role_key" "role_sort" "status"]
        csv-fn (fn [r] [(:role_id r) (:role_name r) (:role_key r) (:role_sort r) (:status r)])]
    (generic-export role-service/list-roles role-service {} header csv-fn "roles.csv" request)))


(defn export-menus
  "导出菜单数据。"
  [{:keys [menu-service]} request]
  (let [header ["menu_id" "menu_name" "parent_id" "order_num" "path" "component" "menu_type" "status"]
        csv-fn (fn [m] [(:menu_id m) (:menu_name m) (:parent_id m) (:order_num m) (:path m) (:component m) (:menu_type m) (:status m)])]
    (generic-export menu-service/list-menus menu-service {} header csv-fn "menus.csv" request)))


(defn export-depts
  "导出部门数据。"
  [{:keys [dept-service]} request]
  (let [header ["dept_id" "parent_id" "dept_name" "order_num" "leader" "status"]
        csv-fn (fn [d] [(:dept_id d) (:parent_id d) (:dept_name d) (:order_num d) (:leader d) (:status d)])]
    (generic-export dept-service/list-depts dept-service {} header csv-fn "depts.csv" request)))


(defn export-posts
  "导出岗位数据。"
  [{:keys [post-service]} request]
  (let [header ["post_id" "post_code" "post_name" "post_sort" "status"]
        csv-fn (fn [p] [(:post_id p) (:post_code p) (:post_name p) (:post_sort p) (:status p)])]
    (generic-export post-service/list-posts post-service {} header csv-fn "posts.csv" request)))


(defn export-dict-types
  "导出字典类型数据。"
  [{:keys [dict-service]} request]
  (let [header ["dict_id" "dict_name" "dict_type" "status"]
        csv-fn (fn [d] [(:dict_id d) (:dict_name d) (:dict_type d) (:status d)])]
    (generic-export dict-service/list-dict-types dict-service {} header csv-fn "dict_types.csv" request)))


(defn export-dict-data
  "导出字典数据。"
  [{:keys [dict-service]} request]
  (let [header ["dict_code" "dict_sort" "dict_label" "dict_value" "dict_type" "status"]
        csv-fn (fn [d] [(:dict_code d) (:dict_sort d) (:dict_label d) (:dict_value d) (:dict_type d) (:status d)])]
    (generic-export dict-service/list-dict-data dict-service {} header csv-fn "dict_data.csv" request)))


(defn export-configs
  "导出参数配置数据。"
  [{:keys [config-service]} request]
  (let [header ["config_id" "config_name" "config_key" "config_value" "config_type"]
        csv-fn (fn [c] [(:config_id c) (:config_name c) (:config_key c) (:config_value c) (:config_type c)])]
    (generic-export config-service/list-configs config-service {} header csv-fn "configs.csv" request)))
