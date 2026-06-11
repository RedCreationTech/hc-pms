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

(rf/reg-sub :notification
  (fn [db _]
    (:notification db)))
