(ns com.ruoyi.domain.system.log
  "日志审计领域服务。")

(defn list-oper-logs
  "查询操作日志列表，支持分页。"
  [{:keys [query-fn]} params]
  (let [page-num (or (:page-num params) 1)
        page-size (or (:page-size params) 10)
        offset (* (dec page-num) page-size)
        filters (merge {:title nil :oper_name nil :oper_ip nil :business_type nil :status nil :begin_time nil :end_time nil}
                       (-> params
                           (dissoc :page-num :page-size)
                           (assoc :offset offset :page_size page-size)))
        rows (query-fn :list-oper-logs filters)
        total (query-fn :count-oper-logs filters)]
    {:rows rows :total (:total total)}))

(defn create-oper-log!
  "记录操作日志。"
  [{:keys [query-fn]} params]
  (query-fn :create-oper-log! params))

(defn clear-oper-logs!
  "清空操作日志。"
  [{:keys [query-fn]} params]
  (query-fn :clear-oper-logs! params))

(defn delete-oper-logs!
  "删除指定操作日志。"
  [{:keys [query-fn]} ids]
  (doseq [id ids]
    (query-fn :delete-oper-log! {:oper_id id})))

(defn list-login-logs
  "查询登录日志列表。"
  [{:keys [query-fn]} params]
  (let [page-num (or (:page-num params) 1)
        page-size (or (:page-size params) 10)
        offset (* (dec page-num) page-size)
        filters (merge {:user_name nil :ipaddr nil :status nil :begin_time nil :end_time nil}
                       (-> params
                           (dissoc :page-num :page-size)
                           (assoc :offset offset :page_size page-size)))
        rows (query-fn :list-login-logs filters)
        total (query-fn :count-login-logs filters)]
    {:rows rows :total (:total total)}))

(defn create-login-log!
  "记录登录日志。"
  [{:keys [query-fn]} params]
  (query-fn :create-login-log! params))

(defn clear-login-logs!
  "清空登录日志。"
  [{:keys [query-fn]} params]
  (query-fn :clear-login-logs! params))

(defn delete-login-logs!
  "删除指定登录日志。"
  [{:keys [query-fn]} ids]
  (doseq [id ids]
    (query-fn :delete-login-log! {:info_id id})))

(defn list-online-users
  "查询在线用户列表。"
  [{:keys [query-fn]} params]
  (let [page-num (or (:page-num params) 1)
        page-size (or (:page-size params) 10)
        offset (* (dec page-num) page-size)
        filters (-> params
                    (dissoc :page-num :page-size)
                    (assoc :offset offset :page_size page-size))
        rows (query-fn :list-online-users filters)
        total (query-fn :count-online-users filters)]
    {:rows rows :total (:total total)}))

(defn create-online-user!
  "记录在线用户。"
  [{:keys [query-fn]} params]
  (query-fn :create-online-user! params))

(defn update-online-user!
  "更新在线用户访问时间。"
  [{:keys [query-fn]} params]
  (query-fn :update-online-user! params))

(defn delete-online-user!
  "踢出在线用户。"
  [{:keys [query-fn]} session-id]
  (query-fn :delete-online-user! {:session_id session-id}))
