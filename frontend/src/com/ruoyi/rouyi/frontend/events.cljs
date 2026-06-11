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
