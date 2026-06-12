(ns com.ruoyi.rouyi.web.controllers.system.notice
  "通知公告控制器。"
  (:require
   [ring.util.response :as response]))

(defn- ok
  "构造成功响应。"
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail
  "构造失败响应。"
  [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn- parse-int
  "将字符串解析为整数。"
  [v]
  (when v (Integer/parseInt v)))

(defn list-notices
  "查询通知公告列表。"
  [{:keys [query-fn]} request]
  (let [params (:query-params request)
        page (or (parse-int (get params "page")) 1)
        size (or (parse-int (get params "size")) 10)
        offset (* (dec page) size)
        query-params {:notice_name (get params "notice_name")
                      :notice_type (get params "notice_type")
                      :create_by   (get params "create_by")
                      :page_size   size
                      :offset      offset}
        rows (query-fn :list-notices query-params)
        total (query-fn :count-notices query-params)]
    (ok {:rows rows :total (:total total)})))

(defn get-notice
  "获取通知公告详情。"
  [{:keys [query-fn]} request]
  (let [notice-id (parse-int (get-in request [:path-params :id]))]
    (if-let [notice (query-fn :find-notice-by-id {:notice_id notice-id} {:result-set-fn first})]
      (ok notice)
      (fail "通知公告不存在"))))

(defn create-notice
  "新增通知公告。"
  [{:keys [query-fn]} request]
  (try
    (let [body (:body-params request)
          identity (:identity request)
          params {:notice_name (:notice_name body)
                  :notice_type (:notice_type body "1")
                  :status      (:status body "0")
                  :create_by   (:user_name identity "")
                  :remark      (:remark body "")}]
      (query-fn :create-notice! params)
      (ok "创建成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn update-notice
  "更新通知公告。"
  [{:keys [query-fn]} request]
  (try
    (let [notice-id (parse-int (get-in request [:path-params :id]))
          body (:body-params request)
          params {:notice_id   notice-id
                  :notice_name (:notice_name body)
                  :notice_type (:notice_type body)
                  :status      (:status body)
                  :update_by   (get-in request [:identity :user_name] "")
                  :remark      (:remark body)}]
      (query-fn :update-notice! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-notice
  "删除通知公告。"
  [{:keys [query-fn]} request]
  (let [notice-id (parse-int (get-in request [:path-params :id]))]
    (query-fn :delete-notice! {:notice_id notice-id})
    (ok 200 "删除成功" {})))
