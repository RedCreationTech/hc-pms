(ns com.ruoyi.rouyi.frontend.events
  "re-frame 事件处理器。"
  (:require
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.db :as db]
    [com.ruoyi.rouyi.frontend.api :as api]))

(rf/reg-event-db :initialize-db
  (fn [_ _]
    db/default-db))

(rf/reg-event-db :navigate
  (fn [db [_ page]]
    (assoc db :page page)))

(rf/reg-event-db :auth/set-token
  (fn [db [_ token]]
    (assoc-in db [:auth :token] token)))

(rf/reg-event-fx :auth/set-user
  (fn [{:keys [db]} [_ user]]
    {:db       (assoc-in db [:auth :user] user)
     :dispatch [:navigate :dashboard]}))

(rf/reg-event-db :auth/set-loading
  (fn [db [_ loading?]]
    (assoc-in db [:auth :loading?] loading?)))

(rf/reg-event-fx :auth/login
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:auth :loading?] true)
     :api/login params}))

(rf/reg-fx :api/login
  (fn [params]
    (api/login params
               (fn [result]
                 (when (= 200 (:code result))
                   (rf/dispatch [:auth/login-success (:data result)]))
                 (when (not= 200 (:code result))
                   (rf/dispatch [:auth/login-failure (:msg result)])))
               (fn [_]
                 (rf/dispatch [:auth/login-failure "网络错误"])))))

(rf/reg-event-fx :auth/login-success
  (fn [{:keys [db]} [_ data]]
    {:db (-> db
              (assoc-in [:auth :token] (:token data))
              (assoc-in [:auth :loading?] false))
     :dispatch [:navigate :dashboard]}))

(rf/reg-event-db :auth/login-failure
  (fn [db [_ msg]]
    (-> db
        (assoc-in [:auth :loading?] false)
        (assoc :notification {:type :error :message msg}))))

(rf/reg-event-fx :auth/fetch-info
  (fn [{:keys [db]} _]
    {:db db
     :api/get-info nil}))

(rf/reg-fx :api/get-info
  (fn [_]
    (api/get-info
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:auth/set-user (:data result)])))
      (fn [_]))))

(rf/reg-event-db :theme/toggle-mode
  (fn [db _]
    (update-in db [:theme :mode] #(if (= % :light) :dark :light))))

(rf/reg-event-db :users/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:users :items] (:rows data))
        (assoc-in [:users :total] (:total data))
        (assoc-in [:users :loading?] false))))

(rf/reg-event-fx :users/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:users :loading?] true)
     :api/list-users params}))

(rf/reg-event-db :dicts/set-types
  (fn [db [_ data]]
    (-> db
        (assoc-in [:dicts :types] (:rows data))
        (assoc-in [:dicts :loading?] false))))

(rf/reg-event-fx :dicts/fetch-types
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:dicts :loading?] true)
     :api/list-dict-types params}))

(rf/reg-fx :api/list-dict-types
  (fn [params]
    (api/list-dict-types params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:dicts/set-types (:data result)])))
      (fn [_]))))

(rf/reg-event-db :dicts/set-data
  (fn [db [_ data]]
    (-> db
        (assoc-in [:dicts :data] (:rows data))
        (assoc-in [:dicts :loading?] false))))

(rf/reg-event-fx :dicts/fetch-data
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:dicts :loading?] true)
     :api/list-dict-data params}))

(rf/reg-fx :api/list-dict-data
  (fn [params]
    (api/list-dict-data params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:dicts/set-data (:data result)])))
      (fn [_]))))

(rf/reg-event-db :configs/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:configs :items] (:rows data))
        (assoc-in [:configs :total] (:total data))
        (assoc-in [:configs :loading?] false))))

(rf/reg-event-fx :configs/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:configs :loading?] true)
     :api/list-configs params}))

(rf/reg-fx :api/list-configs
  (fn [params]
    (api/list-configs params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:configs/set-list (:data result)])))
      (fn [_]))))

(rf/reg-event-fx :configs/create
  (fn [{:keys [db]} [_ params]]
    {:db db
     :api/create-config params}))

(rf/reg-fx :api/create-config
  (fn [params]
    (api/create-config params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:configs/created])
          (.success js/antd.message "创建成功"))
        (when (not= 200 (:code result))
          (.error js/antd.message (:msg result))))
      (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :configs/created
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:notification] nil)
     :dispatch [:configs/fetch {}]}))

(rf/reg-event-fx :configs/update
  (fn [{:keys [db]} [_ id params]]
    {:db db
     :api/update-config [id params]}))

(rf/reg-fx :api/update-config
  (fn [[id params]]
    (api/update-config id params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:configs/updated])
          (.success js/antd.message "更新成功"))
        (when (not= 200 (:code result))
          (.error js/antd.message (:msg result))))
      (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :configs/updated
  (fn [{:keys [db]} _]
    {:db db
     :dispatch [:configs/fetch {}]}))

(rf/reg-event-fx :configs/delete
  (fn [{:keys [db]} [_ id]]
    {:db db
     :api/delete-config id}))

(rf/reg-fx :api/delete-config
  (fn [id]
    (api/delete-config id
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:configs/deleted])
          (.success js/antd.message "删除成功"))
        (when (not= 200 (:code result))
          (.error js/antd.message (:msg result))))
      (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :configs/deleted
  (fn [{:keys [db]} _]
    {:db db
     :dispatch [:configs/fetch {}]}))

(rf/reg-event-db :oper-logs/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:oper-logs :items] (:rows data))
        (assoc-in [:oper-logs :total] (:total data))
        (assoc-in [:oper-logs :loading?] false))))

(rf/reg-event-fx :oper-logs/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:oper-logs :loading?] true)
     :api/list-oper-logs params}))

(rf/reg-fx :api/list-oper-logs
  (fn [params]
    (api/list-oper-logs params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:oper-logs/set-list (:data result)])))
      (fn [_]))))

(rf/reg-event-fx :oper-logs/clear
  (fn [{:keys [db]} _]
    {:db db
     :api/clear-oper-logs nil}))

(rf/reg-fx :api/clear-oper-logs
  (fn [_]
    (api/clear-oper-logs
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:oper-logs/cleared])
          (.success js/antd.message "清空成功")))
      (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :oper-logs/cleared
  (fn [{:keys [db]} _]
    {:db db
     :dispatch [:oper-logs/fetch {}]}))

(rf/reg-event-db :login-logs/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:login-logs :items] (:rows data))
        (assoc-in [:login-logs :total] (:total data))
        (assoc-in [:login-logs :loading?] false))))

(rf/reg-event-fx :login-logs/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:login-logs :loading?] true)
     :api/list-login-logs params}))

(rf/reg-fx :api/list-login-logs
  (fn [params]
    (api/list-login-logs params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:login-logs/set-list (:data result)])))
      (fn [_]))))

(rf/reg-event-fx :login-logs/clear
  (fn [{:keys [db]} _]
    {:db db
     :api/clear-login-logs nil}))

(rf/reg-fx :api/clear-login-logs
  (fn [_]
    (api/clear-login-logs
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:login-logs/cleared])
          (.success js/antd.message "清空成功")))
      (fn [_] (.error js/antd.message "网络错误")))))

(rf/reg-event-fx :login-logs/cleared
  (fn [{:keys [db]} _]
    {:db db
     :dispatch [:login-logs/fetch {}]}))

(rf/reg-fx :api/list-users
  (fn [params]
    (api/list-users params
                    (fn [result]
                      (when (= 200 (:code result))
                        (rf/dispatch [:users/set-list (:data result)])))
                    (fn [_]))))

;; ────── 在线用户 ──────

(rf/reg-event-db :online-users/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:online-users :items] (:rows data))
        (assoc-in [:online-users :total] (:total data))
        (assoc-in [:online-users :loading?] false))))

(rf/reg-event-fx :online-users/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:online-users :loading?] true)
     :api/list-online-users params}))

(rf/reg-fx :api/list-online-users
  (fn [params]
    (api/list-online-users params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:online-users/set-list (:data result)])))
      (fn [_]))))

(rf/reg-event-fx :online-users/force-logout
  (fn [_ [_ token-id]]
    {:api/force-logout token-id}))

(rf/reg-fx :api/force-logout
  (fn [token-id]
    (api/force-logout token-id
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:online-users/fetch {}])))
      (fn [_]))))

;; ────── 定时任务 ──────

(rf/reg-event-db :jobs/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:jobs :items] (:rows data))
        (assoc-in [:jobs :total] (:total data))
        (assoc-in [:jobs :loading?] false))))

(rf/reg-event-fx :jobs/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:jobs :loading?] true)
     :api/list-jobs params}))

(rf/reg-fx :api/list-jobs
  (fn [params]
    (api/list-jobs params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:jobs/set-list (:data result)])))
      (fn [_]))))

(rf/reg-event-fx :jobs/create
  (fn [_ [_ params]]
    {:api/create-job params}))

(rf/reg-fx :api/create-job
  (fn [params]
    (api/create-job params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:jobs/fetch {}])))
      (fn [_]))))

(rf/reg-event-fx :jobs/update
  (fn [_ [_ id params]]
    {:api/update-job [id params]}))

(rf/reg-fx :api/update-job
  (fn [[id params]]
    (api/update-job id params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:jobs/fetch {}])))
      (fn [_]))))

(rf/reg-event-fx :jobs/delete
  (fn [_ [_ id]]
    {:api/delete-job id}))

(rf/reg-fx :api/delete-job
  (fn [id]
    (api/delete-job id
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:jobs/fetch {}])))
      (fn [_]))))

;; ────── 任务日志 ──────

(rf/reg-event-db :job-logs/set-list
  (fn [db [_ data]]
    (-> db
        (assoc-in [:job-logs :items] (:rows data))
        (assoc-in [:job-logs :total] (:total data))
        (assoc-in [:job-logs :loading?] false))))

(rf/reg-event-fx :job-logs/fetch
  (fn [{:keys [db]} [_ params]]
    {:db (assoc-in db [:job-logs :loading?] true)
     :api/list-job-logs params}))

(rf/reg-fx :api/list-job-logs
  (fn [params]
    (api/list-job-logs params
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:job-logs/set-list (:data result)])))
      (fn [_]))))

;; ────── 个人中心 ──────

(rf/reg-event-db :profile/set-data
  (fn [db [_ data]]
    (assoc-in db [:profile :data] data)))

(rf/reg-event-db :profile/set-loading
  (fn [db [_ loading?]]
    (assoc-in db [:profile :loading?] loading?)))

(rf/reg-event-fx :profile/fetch
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:profile :loading?] true)
     :api/get-profile nil}))

(rf/reg-fx :api/get-profile
  (fn [_]
    (api/get-profile
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:profile/set-data (:data result)])))
      (fn [_]))))

(rf/reg-event-fx :profile/update
  (fn [_ [_ params]]
    {:api/update-profile params}))

(rf/reg-fx :api/update-profile
  (fn [params]
    (api/update-profile params
      (fn [result]
        (when (= 200 (:code result))
          (js/alert "更新成功")
          (rf/dispatch [:profile/fetch])))
      (fn [_]))))

(rf/reg-event-fx :profile/change-password
  (fn [_ [_ params]]
    {:api/change-password params}))

(rf/reg-fx :api/change-password
  (fn [params]
    (api/change-password params
      (fn [result]
        (when (= 200 (:code result))
          (js/alert "密码修改成功"))
        (when (not= 200 (:code result))
          (js/alert (:msg result))))
      (fn [_]))))

(rf/reg-fx :api/upload-avatar
  (fn [form-data]
    (api/upload-avatar form-data
      (fn [result]
        (when (= 200 (:code result))
          (rf/dispatch [:profile/fetch])))
      (fn [_]))))
