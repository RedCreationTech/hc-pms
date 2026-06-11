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

(rf/reg-event-db :auth/set-user
  (fn [db [_ user]]
    (assoc-in db [:auth :user] user)))

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
     :dispatch [:auth/fetch-info]}))

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
