(ns com.ruoyi.rouyi.frontend.subs
  "re-frame 订阅定义。"
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :page
  (fn [db _]
    (:page db)))

(rf/reg-sub :auth/token
  (fn [db _]
    (get-in db [:auth :token])))

(rf/reg-sub :auth/user
  (fn [db _]
    (get-in db [:auth :user])))

(rf/reg-sub :auth/loading?
  (fn [db _]
    (get-in db [:auth :loading?])))

(rf/reg-sub :auth/logged-in?
  (fn [db _]
    (boolean (get-in db [:auth :token]))))

(rf/reg-sub :users/items
  (fn [db _]
    (get-in db [:users :items])))

(rf/reg-sub :users/total
  (fn [db _]
    (get-in db [:users :total])))

(rf/reg-sub :users/loading?
  (fn [db _]
    (get-in db [:users :loading?])))

(rf/reg-sub :online-users/items
  (fn [db _]
    (get-in db [:online-users :items])))

(rf/reg-sub :online-users/total
  (fn [db _]
    (get-in db [:online-users :total])))

(rf/reg-sub :online-users/loading?
  (fn [db _]
    (get-in db [:online-users :loading?])))

(rf/reg-sub :jobs/items
  (fn [db _]
    (get-in db [:jobs :items])))

(rf/reg-sub :jobs/total
  (fn [db _]
    (get-in db [:jobs :total])))

(rf/reg-sub :jobs/loading?
  (fn [db _]
    (get-in db [:jobs :loading?])))

(rf/reg-sub :job-logs/items
  (fn [db _]
    (get-in db [:job-logs :items])))

(rf/reg-sub :job-logs/total
  (fn [db _]
    (get-in db [:job-logs :total])))

(rf/reg-sub :job-logs/loading?
  (fn [db _]
    (get-in db [:job-logs :loading?])))

(rf/reg-sub :profile/data
  (fn [db _]
    (get-in db [:profile :data])))

(rf/reg-sub :profile/loading?
  (fn [db _]
    (get-in db [:profile :loading?])))

(rf/reg-sub :notification
  (fn [db _]
    (:notification db)))
