(ns com.ruoyi.rouyi.web.controllers.system.import-export
  "用户导入导出控制器（简易文本版）。"
  (:require
    [com.ruoyi.rouyi.domain.system.user :as user-service]
    [ring.util.response :as response]
    [clojure.string :as str]))

(defn- ok
  ([data] (response/response {:code 200 :msg "操作成功" :data data}))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn import-users
  "批量导入用户。"
  [{:keys [user-service]} request]
  (try
    (let [body (:body-params request)
          rows (:rows body)
          identity (:identity request)
          results (mapv (fn [row]
                          (try
                            (let [params (merge {:dept_id 1 :user_type "00" :sex "0" :status "0"
                                                 :email "" :phonenumber "" :avatar "" :remark ""
                                                 :create_by (:user_name identity "") :password "123456"
                                                 :roles [] :posts []}
                                                row)]
                              (when (:user_name params)
                                (user-service/create-user! user-service params))
                              {:user_name (:user_name params) :status "success"})
                            (catch Exception e
                              {:user_name (:user_name row) :status "failed" :msg (.getMessage e)})))
                        rows)]
      (ok {:total (count results)
           :success (count (filter #(= "success" (:status %)) results))
           :failed (count (filter #(= "failed" (:status %)) results))
           :details results}))
    (catch Exception e
      (fail (.getMessage e)))))

(defn export-users
  "导出用户CSV。"
  [{:keys [user-service]} request]
  (try
    (let [identity (:identity request)
          result (user-service/list-users user-service {:page-num 1 :page-size 10000})
          rows (:rows result)
          csv-lines (mapv (fn [r]
                            (str (:user_name r) "," (:nick_name r) "," (:email r) ","
                                 (:phonenumber r) "," (:sex r) "," (:status r) ","
                                 (:dept_id r)))
                          rows)
          csv (str "user_name,nick_name,email,phonenumber,sex,status,dept_id\n"
                   (str/join "\n" csv-lines))]
      (-> (response/response csv)
          (response/header "Content-Type" "text/csv; charset=utf-8")
          (response/header "Content-Disposition" "attachment; filename=users.csv")))
    (catch Exception e
      (fail (.getMessage e)))))
